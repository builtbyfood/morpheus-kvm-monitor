package com.morpheusdata.kvmmonitor

import groovy.sql.Sql
import groovy.util.logging.Slf4j
import org.sqlite.SQLiteDataSource

import java.util.concurrent.locks.ReentrantLock

/**
 * Self-contained SQLite store for KVM vCPU scheduling samples.
 *
 * Why SQLite instead of OpenSearch:
 *   - Zero external dependencies; survives any Morpheus storage migration
 *     (OpenSearch -> Elasticsearch and beyond).
 *   - Plugin owns the file outright.
 *   - Plenty fast for the data volume (a dozen VMs * 60s * 30 days ~ 500K rows).
 *
 * We store CUMULATIVE libvirt counters as-sampled and compute deltas at read
 * time. That keeps the collector stateless and makes "CPU Ready %" a pure
 * function of any two samples.
 */
@Slf4j
class KvmMetricStore {

    static final String DEFAULT_DB_PATH = '/var/opt/morpheus/morpheus-ui/plugins/kvm-monitor.db'

    private final String dbPath
    private SQLiteDataSource dataSource
    private final ReentrantLock writeLock = new ReentrantLock()
    private volatile boolean initialized = false

    KvmMetricStore(String dbPath = DEFAULT_DB_PATH) {
        this.dbPath = dbPath ?: DEFAULT_DB_PATH
    }

    /** Lazily create the datasource + schema. Safe to call repeatedly. */
    void init() {
        if (initialized) return
        synchronized (this) {
            if (initialized) return
            try {
                new File(dbPath).parentFile?.mkdirs()
                // Configure via SQLiteConfig to avoid depending on individual
                // setter signatures. WAL keeps reads non-blocking during writes.
                def config = new org.sqlite.SQLiteConfig()
                config.setJournalMode(org.sqlite.SQLiteConfig.JournalMode.WAL)
                config.setBusyTimeout(5000)
                dataSource = new SQLiteDataSource(config)
                dataSource.url = "jdbc:sqlite:${dbPath}".toString()

                withSql { Sql sql ->
                    sql.execute('''
                        CREATE TABLE IF NOT EXISTS vcpu_samples (
                            id            INTEGER PRIMARY KEY AUTOINCREMENT,
                            ts            INTEGER NOT NULL,
                            host_name     TEXT,
                            host_id       INTEGER,
                            vm_name       TEXT NOT NULL,
                            vm_id         INTEGER,
                            vcpu_count    INTEGER,
                            cpu_time_ns   INTEGER,
                            vcpu_delay_ns INTEGER,
                            vcpu_wait_ns  INTEGER
                        )
                    '''.toString())
                    sql.execute('CREATE INDEX IF NOT EXISTS idx_samples_vm_ts ON vcpu_samples(vm_name, ts)')
                    sql.execute('CREATE INDEX IF NOT EXISTS idx_samples_ts ON vcpu_samples(ts)')
                    // v1.16 schema migration — add IO columns idempotently.
                    // SQLite has no "ADD COLUMN IF NOT EXISTS"; swallow the
                    // "duplicate column" error on re-runs.
                    ['disk_rd_bytes', 'disk_wr_bytes', 'net_rx_bytes', 'net_tx_bytes'].each { String col ->
                        try {
                            sql.execute("ALTER TABLE vcpu_samples ADD COLUMN ${col} INTEGER DEFAULT 0".toString())
                            log.info("KvmMetricStore: added column vcpu_samples.${col}")
                        } catch (Exception e) {
                            // already exists — fine
                        }
                    }
                    sql.execute('''
                        CREATE TABLE IF NOT EXISTS host_samples (
                            id           INTEGER PRIMARY KEY AUTOINCREMENT,
                            ts           INTEGER NOT NULL,
                            host_id      INTEGER,
                            host_name    TEXT,
                            load1        REAL,
                            load5        REAL,
                            load15       REAL,
                            cpu_user     INTEGER,
                            cpu_nice     INTEGER,
                            cpu_system   INTEGER,
                            cpu_idle     INTEGER,
                            cpu_iowait   INTEGER,
                            cpu_irq      INTEGER,
                            cpu_softirq  INTEGER,
                            cpu_steal    INTEGER,
                            cpu_mhz      REAL,
                            cpu_mhz_max  REAL,
                            sockets      INTEGER,
                            cores_per_socket INTEGER,
                            threads_per_core INTEGER
                        )
                    '''.toString())
                    sql.execute('CREATE INDEX IF NOT EXISTS idx_host_samples_host_ts ON host_samples(host_id, ts)')
                    // v2.4 schema migration — add CPU topology columns idempotently.
                    ['sockets', 'cores_per_socket', 'threads_per_core'].each { String col ->
                        try {
                            sql.execute("ALTER TABLE host_samples ADD COLUMN ${col} INTEGER".toString())
                            log.info("KvmMetricStore: added column host_samples.${col}")
                        } catch (Exception e) { /* already exists */ }
                    }
                    sql.execute('''
                        CREATE TABLE IF NOT EXISTS settings (
                            key   TEXT PRIMARY KEY,
                            value TEXT
                        )
                    '''.toString())
                }
                initialized = true
                log.info("KvmMetricStore initialized at ${dbPath}")
            } catch (Exception e) {
                log.error("Failed to initialize SQLite store at ${dbPath}: ${e.message}", e)
                throw e
            }
        }
    }

    private void withSql(Closure work) {
        Sql sql = new Sql(dataSource)
        try {
            work.call(sql)
        } finally {
            sql.close()
        }
    }

    // ── Writes ────────────────────────────────────────────────────────────────

    /**
     * Persist a batch of parsed samples. Each map is one VM at one point in time.
     * Expected keys: ts, hostName, hostId, vmName, vmId, vcpuCount,
     *                cpuTimeNs, vcpuDelayNs, vcpuWaitNs
     */
    int saveSamples(List<Map> samples) {
        if (!samples) return 0
        init()
        writeLock.lock()
        try {
            int count = 0
            withSql { Sql sql ->
                sql.withBatch(100, '''
                    INSERT INTO vcpu_samples
                        (ts, host_name, host_id, vm_name, vm_id, vcpu_count,
                         cpu_time_ns, vcpu_delay_ns, vcpu_wait_ns,
                         disk_rd_bytes, disk_wr_bytes, net_rx_bytes, net_tx_bytes)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                '''.toString()) { stmt ->
                    samples.each { Map s ->
                        stmt.addBatch([
                            (s.ts ?: System.currentTimeMillis()) as Long,
                            s.hostName as String,
                            s.hostId as Long,
                            s.vmName as String,
                            s.vmId as Long,
                            (s.vcpuCount ?: 0) as Long,
                            (s.cpuTimeNs ?: 0) as Long,
                            (s.vcpuDelayNs ?: 0) as Long,
                            (s.vcpuWaitNs ?: 0) as Long,
                            (s.diskRdBytes ?: 0) as Long,
                            (s.diskWrBytes ?: 0) as Long,
                            (s.netRxBytes ?: 0) as Long,
                            (s.netTxBytes ?: 0) as Long
                        ])
                        count++
                    }
                }
            }
            return count
        } finally {
            writeLock.unlock()
        }
    }

    /** Drop samples older than the retention window. */
    int prune(int retentionDays) {
        if (retentionDays <= 0) return 0
        init()
        long cutoff = System.currentTimeMillis() - (retentionDays * 86_400_000L)
        writeLock.lock()
        try {
            int removed = 0
            withSql { Sql sql ->
                removed  = sql.executeUpdate('DELETE FROM vcpu_samples WHERE ts < ?', [cutoff])
                removed += sql.executeUpdate('DELETE FROM host_samples WHERE ts < ?', [cutoff])
            }
            if (removed) log.info("Pruned ${removed} samples older than ${retentionDays}d")
            return removed
        } finally {
            writeLock.unlock()
        }
    }

    /** Persist a single host-level sample (load avg, /proc/stat cpu line, MHz). */
    void saveHostSample(Map s) {
        if (!s) return
        init()
        writeLock.lock()
        try {
            withSql { Sql sql ->
                sql.executeInsert('''
                    INSERT INTO host_samples
                        (ts, host_id, host_name, load1, load5, load15,
                         cpu_user, cpu_nice, cpu_system, cpu_idle, cpu_iowait,
                         cpu_irq, cpu_softirq, cpu_steal, cpu_mhz, cpu_mhz_max,
                         sockets, cores_per_socket, threads_per_core)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                '''.toString(), [
                    (s.ts ?: System.currentTimeMillis()) as Long,
                    s.hostId as Long,
                    s.hostName as String,
                    s.load1 as Double,
                    s.load5 as Double,
                    s.load15 as Double,
                    s.cpu_user as Long,
                    s.cpu_nice as Long,
                    s.cpu_system as Long,
                    s.cpu_idle as Long,
                    s.cpu_iowait as Long,
                    s.cpu_irq as Long,
                    s.cpu_softirq as Long,
                    s.cpu_steal as Long,
                    s.cpu_mhz as Double,
                    s.cpu_mhz_max as Double,
                    s.sockets as Long,
                    s.cores_per_socket as Long,
                    s.threads_per_core as Long
                ])
            }
        } finally {
            writeLock.unlock()
        }
    }

    /**
     * Latest host metrics with CPU breakdown computed from delta of the two
     * newest samples. Load avg and MHz are point-in-time (from newest sample).
     */
    Map latestHostStats(Long hostId) {
        if (hostId == null) return null
        init()
        Map result = null
        withSql { Sql sql ->
            def rows = sql.rows(
                'SELECT * FROM host_samples WHERE host_id = ? ORDER BY ts DESC LIMIT 2',
                [hostId]
            )
            if (!rows) return
            result = computeHostMetrics(rows[0], rows.size() > 1 ? rows[1] : null)
        }
        return result
    }

    private static Map computeHostMetrics(def newer, def older) {
        Map out = [
            ts        : newer.ts as Long,
            hostId    : newer.host_id as Long,
            hostName  : newer.host_name as String,
            load1     : newer.load1 as Double,
            load5     : newer.load5 as Double,
            load15    : newer.load15 as Double,
            cpuMhz    : newer.cpu_mhz as Double,
            cpuMhzMax : newer.cpu_mhz_max as Double,
            sockets        : newer.sockets as Long,
            coresPerSocket : newer.cores_per_socket as Long,
            threadsPerCore : newer.threads_per_core as Long,
            totalCores     : ((newer.sockets ?: 0L) as Long) * ((newer.cores_per_socket ?: 0L) as Long),
            totalThreads   : ((newer.sockets ?: 0L) as Long) * ((newer.cores_per_socket ?: 0L) as Long) * ((newer.threads_per_core ?: 1L) as Long)
        ]
        if (older) {
            List<String> fields = ['cpu_user','cpu_nice','cpu_system','cpu_idle',
                                   'cpu_iowait','cpu_irq','cpu_softirq','cpu_steal']
            long totalDelta = 0L
            fields.each { String f ->
                totalDelta += Math.max((((newer[f] ?: 0) - (older[f] ?: 0)) as long), 0L)
            }
            if (totalDelta > 0) {
                def dPct = { String f ->
                    long d = Math.max((((newer[f] ?: 0) - (older[f] ?: 0)) as long), 0L)
                    (d / (double) totalDelta) * 100d
                }
                out.userPct   = round2((dPct('cpu_user') + dPct('cpu_nice')) as double)
                out.systemPct = round2((dPct('cpu_system') + dPct('cpu_irq') + dPct('cpu_softirq')) as double)
                out.iowaitPct = round2(dPct('cpu_iowait') as double)
                out.idlePct   = round2(dPct('cpu_idle') as double)
                out.stealPct  = round2(dPct('cpu_steal') as double)
            }
        }
        return out
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    /**
     * Latest CPU-Ready snapshot per VM, optionally scoped to one host.
     * Ready% is computed from the two most recent samples per VM:
     *   readyPct = delta(vcpu_delay_ns) / (intervalNs * vcpuCount) * 100
     */
    List<Map> latestReadyByVm(Long hostId = null) {
        init()
        List<Map> out = []
        withSql { Sql sql ->
            String hostFilter = hostId != null ? 'WHERE host_id = ?' : ''
            List params = hostId != null ? [hostId] : []
            // Pull the two newest rows per vm_name, newest first.
            String q = """
                SELECT s.* FROM vcpu_samples s
                JOIN (
                    SELECT vm_name, ts,
                           ROW_NUMBER() OVER (PARTITION BY vm_name ORDER BY ts DESC) rn
                    FROM vcpu_samples ${hostFilter}
                ) r ON r.vm_name = s.vm_name AND r.ts = s.ts
                WHERE r.rn <= 2
                ORDER BY s.vm_name, s.ts DESC
            """.toString()

            Map<String, List<Map>> byVm = [:]
            sql.eachRow(q, params) { row ->
                byVm.computeIfAbsent(row.vm_name as String, { [] }) << rowToMap(row)
            }
            byVm.each { String vm, List<Map> rows ->
                out << computeReady(rows)
            }
        }
        return out.sort { -((it.readyPct ?: 0) as double) }
    }

    /**
     * Ready% time series for a single VM over the last N minutes.
     * Returns points: [ts, readyPct, vcpuCount].
     */
    List<Map> readySeries(String vmName, int minutes = 60) {
        init()
        long since = System.currentTimeMillis() - (minutes * 60_000L)
        List<Map> rows = []
        withSql { Sql sql ->
            sql.eachRow(
                'SELECT * FROM vcpu_samples WHERE vm_name = ? AND ts >= ? ORDER BY ts ASC',
                [vmName, since]
            ) { row -> rows << rowToMap(row) }
        }
        List<Map> series = []
        for (int i = 1; i < rows.size(); i++) {
            Map pt = computeReady([rows[i], rows[i - 1]])
            series << [ts: rows[i].ts, readyPct: pt.readyPct, vcpuCount: rows[i].vcpuCount]
        }
        return series
    }

    /** Distinct hosts we currently have data for. */
    List<Map> hosts() {
        init()
        List<Map> out = []
        withSql { Sql sql ->
            sql.eachRow('''
                SELECT host_id, host_name, COUNT(DISTINCT vm_name) vm_count, MAX(ts) last_ts
                FROM vcpu_samples GROUP BY host_id, host_name
            '''.toString()) { row ->
                out << [hostId: row.host_id, hostName: row.host_name,
                        vmCount: row.vm_count, lastTs: row.last_ts]
            }
        }
        return out
    }

    /**
     * Per-VM aggregate over a window: avg/max Ready% and Steal% computed from
     * consecutive-sample deltas. Backs the historical CPU Ready report.
     */
    List<Map> aggregateByVm(int minutes = 60) {
        init()
        long since = System.currentTimeMillis() - (minutes * 60_000L)
        Map<String, List<Map>> byVm = [:]
        withSql { Sql sql ->
            sql.eachRow('SELECT * FROM vcpu_samples WHERE ts >= ? ORDER BY vm_name, ts ASC', [since]) { row ->
                byVm.computeIfAbsent(row.vm_name as String, { [] }) << rowToMap(row)
            }
        }
        List<Map> out = []
        byVm.each { String vm, List<Map> rows ->
            List<Double> readies = []
            List<Double> steals  = []
            List<Double> useds   = []
            List<Double> diskRd  = []
            List<Double> diskWr  = []
            List<Double> netRx   = []
            List<Double> netTx   = []
            for (int i = 1; i < rows.size(); i++) {
                Map pt = computeReady([rows[i], rows[i - 1]])
                readies << (pt.readyPct as double)
                steals  << (pt.stealPct as double)
                useds   << (pt.usedPct  as double)
                diskRd  << ((pt.diskRdKBs ?: 0d) as double)
                diskWr  << ((pt.diskWrKBs ?: 0d) as double)
                netRx   << ((pt.netRxKBs  ?: 0d) as double)
                netTx   << ((pt.netTxKBs  ?: 0d) as double)
            }
            if (readies) {
                Map last = rows[-1]
                out << [
                    vmName        : vm,
                    hostName      : last.hostName,
                    vcpuCount     : last.vcpuCount,
                    samples       : rows.size(),
                    avgReadyPct   : round2(readies.sum() / readies.size()),
                    maxReadyPct   : round2(readies.max()),
                    avgStealPct   : round2(steals.sum() / steals.size()),
                    maxStealPct   : round2(steals.max()),
                    avgUsedPct    : round2(useds.sum() / useds.size()),
                    maxUsedPct    : round2(useds.max()),
                    avgDiskRdKBs  : round2((diskRd.sum() ?: 0d) / Math.max(diskRd.size(), 1)),
                    maxDiskRdKBs  : round2((diskRd.max() ?: 0d) as double),
                    avgDiskWrKBs  : round2((diskWr.sum() ?: 0d) / Math.max(diskWr.size(), 1)),
                    maxDiskWrKBs  : round2((diskWr.max() ?: 0d) as double),
                    avgNetRxKBs   : round2((netRx.sum()  ?: 0d) / Math.max(netRx.size(),  1)),
                    maxNetRxKBs   : round2((netRx.max()  ?: 0d) as double),
                    avgNetTxKBs   : round2((netTx.sum()  ?: 0d) / Math.max(netTx.size(),  1)),
                    maxNetTxKBs   : round2((netTx.max()  ?: 0d) as double)
                ]
            }
        }
        return out.sort { -((it.avgReadyPct ?: 0) as double) }
    }

    long sampleCount() {
        init()
        long c = 0
        withSql { Sql sql -> c = (sql.firstRow('SELECT COUNT(*) n FROM vcpu_samples')?.n ?: 0) as long }
        return c
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static double round2(double v) { Math.round(v * 100) / 100d }

    private static Map rowToMap(row) {
        [
            ts          : row.ts as Long,
            hostName    : row.host_name as String,
            hostId      : row.host_id as Long,
            vmName      : row.vm_name as String,
            vmId        : row.vm_id as Long,
            vcpuCount   : (row.vcpu_count ?: 0) as Long,
            cpuTimeNs   : (row.cpu_time_ns ?: 0) as Long,
            vcpuDelayNs : (row.vcpu_delay_ns ?: 0) as Long,
            vcpuWaitNs  : (row.vcpu_wait_ns ?: 0) as Long,
            diskRdBytes : (safeColumn(row, 'disk_rd_bytes') ?: 0) as Long,
            diskWrBytes : (safeColumn(row, 'disk_wr_bytes') ?: 0) as Long,
            netRxBytes  : (safeColumn(row, 'net_rx_bytes')  ?: 0) as Long,
            netTxBytes  : (safeColumn(row, 'net_tx_bytes')  ?: 0) as Long
        ]
    }

    /** Read a column safely — returns null if the column doesn't exist
     *  (i.e. older rows written before the v1.16 migration). */
    private static Object safeColumn(row, String name) {
        try { return row[name] } catch (Exception ignored) { return null }
    }

    /**
     * Given [newer, older] (or [newer]) samples for one VM, compute Ready%.
     * vcpu.N.delay = ns the vCPU was runnable but waiting on the host scheduler
     * — the closest libvirt analog to VMware's CPU Ready.
     */
    private static Map computeReady(List<Map> rows) {
        Map newer = rows[0]
        Map older = rows.size() > 1 ? rows[1] : null
        double readyPct = 0d
        double stealPct = 0d
        double usedPct  = 0d
        double diskRdKBs = 0d
        double diskWrKBs = 0d
        double netRxKBs  = 0d
        double netTxKBs  = 0d
        if (older) {
            long intervalNs = (newer.ts - older.ts) * 1_000_000L  // ms -> ns
            long vcpus = Math.max((newer.vcpuCount ?: 1L) as long, 1L)
            long denom = intervalNs * vcpus
            if (denom > 0) {
                long delayDelta = Math.max((newer.vcpuDelayNs - older.vcpuDelayNs) as long, 0L)
                long waitDelta  = Math.max((newer.vcpuWaitNs  - older.vcpuWaitNs)  as long, 0L)
                long cpuDelta   = Math.max((newer.cpuTimeNs   - older.cpuTimeNs)   as long, 0L)
                readyPct = Math.min((delayDelta / (double) denom) * 100d, 100d)
                stealPct = Math.min((waitDelta  / (double) denom) * 100d, 100d)
                usedPct  = Math.min((cpuDelta   / (double) denom) * 100d, 100d)
            }
            // IO rates — bytes / second / 1024 → KB/s. Independent of vcpu count.
            double intervalSec = (newer.ts - older.ts) / 1000d
            if (intervalSec > 0) {
                def rateKB = { long n, long o ->
                    Math.max((n - o) as double, 0d) / intervalSec / 1024d
                }
                diskRdKBs = rateKB((newer.diskRdBytes ?: 0L) as long, (older.diskRdBytes ?: 0L) as long)
                diskWrKBs = rateKB((newer.diskWrBytes ?: 0L) as long, (older.diskWrBytes ?: 0L) as long)
                netRxKBs  = rateKB((newer.netRxBytes  ?: 0L) as long, (older.netRxBytes  ?: 0L) as long)
                netTxKBs  = rateKB((newer.netTxBytes  ?: 0L) as long, (older.netTxBytes  ?: 0L) as long)
            }
        }
        return [
            vmName    : newer.vmName,
            hostName  : newer.hostName,
            hostId    : newer.hostId,
            vmId      : newer.vmId,
            vcpuCount : newer.vcpuCount,
            ts        : newer.ts,
            readyPct  : (Math.round(readyPct * 100) / 100d),
            stealPct  : (Math.round(stealPct * 100) / 100d),
            usedPct   : (Math.round(usedPct  * 100) / 100d),
            diskRdKBs : round2(diskRdKBs),
            diskWrKBs : round2(diskWrKBs),
            netRxKBs  : round2(netRxKBs),
            netTxKBs  : round2(netTxKBs)
        ]
    }
}
