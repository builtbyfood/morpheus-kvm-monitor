package com.morpheusdata.kvmmonitor

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import groovy.util.logging.Slf4j

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Collects KVM vCPU scheduling stats from each hypervisor host and writes them
 * to the SQLite store.
 *
 * KEY CHANGE vs the OpenSearch build:
 *   Commands now run through MorpheusContext.executeCommandOnServer(), which is
 *   the documented agent-first path (Edge/agent with automatic SSH fallback).
 *   No SSH key wrangling, no CSRF, no /api/tasks/executeOnServer polling.
 *
 * VM -> host mapping uses ComputeServer.getParentServer() per the plugin docs.
 */
@Slf4j
class KvmCollectorService {

    static final String HOST_MARKER = '---HOST'
    // One round trip per host: virsh stats (vcpu+block+interface) + /proc/loadavg
    // + /proc/stat + current MHz + max MHz fallback chain.
    // Max MHz tries (in order): /sys cpuinfo_max_freq (hardware max), /sys scaling_max_freq (policy max),
    // lscpu's "CPU max MHz" (×1000 to normalize to kHz), then 0.
    static final String COLLECT_CMD = '''sudo virsh domstats --vcpu --block --interface; echo '---HOST'; cat /proc/loadavg; head -1 /proc/stat; awk '/cpu MHz/{print $4; exit}' /proc/cpuinfo 2>/dev/null; ( cat /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq 2>/dev/null | grep -v '^0$' | head -1 || true; cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq 2>/dev/null | grep -v '^0$' | head -1 || true; lscpu 2>/dev/null | awk -F: '/CPU max MHz/{gsub(/ /,"",$2); printf "%d\\n", $2*1000; exit}' || true; echo 0 ) | head -1; lscpu 2>/dev/null | awk -F: '/^Socket\\(s\\)/{gsub(/ /,"",$2); print "SOCKETS="$2; exit}'; lscpu 2>/dev/null | awk -F: '/^Core\\(s\\) per socket/{gsub(/ /,"",$2); print "CORES="$2; exit}'; lscpu 2>/dev/null | awk -F: '/^Thread\\(s\\) per core/{gsub(/ /,"",$2); print "THREADS="$2; exit}' '''

    final MorpheusContext morpheus
    final KvmMetricStore store

    private ScheduledExecutorService scheduler
    private volatile int intervalSeconds = 60
    private volatile int retentionDays   = 30

    KvmCollectorService(MorpheusContext morpheus, KvmMetricStore store) {
        this.morpheus = morpheus
        this.store    = store
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    void start(int intervalSeconds = 60, int retentionDays = 30) {
        this.intervalSeconds = Math.max(intervalSeconds, 30)
        this.retentionDays   = retentionDays
        stop()
        store.init()
        scheduler = Executors.newSingleThreadScheduledExecutor({ Runnable r ->
            Thread t = new Thread(r, 'kvm-monitor-collector')
            t.daemon = true
            return t
        })
        scheduler.scheduleWithFixedDelay({ safeCollect() } as Runnable,
                5, this.intervalSeconds, TimeUnit.SECONDS)
        log.info("KVM collector started — interval=${this.intervalSeconds}s retention=${retentionDays}d")
    }

    void stop() {
        if (scheduler) {
            scheduler.shutdownNow()
            scheduler = null
        }
    }

    private void safeCollect() {
        try {
            collectAll()
            store.prune(retentionDays)
        } catch (Throwable t) {
            // Never let a collection error kill the scheduler thread.
            log.error("KVM collection cycle failed: ${t.message}", t)
        }
    }

    // ── Collection ────────────────────────────────────────────────────────────

    /** Run one collection pass across all KVM hosts. Returns rows written. */
    int collectAll() {
        List<ComputeServer> hosts = listKvmHosts()
        if (!hosts) {
            log.warn("No KVM/VME hypervisor hosts found to collect from")
            return 0
        }
        long ts = System.currentTimeMillis()
        List<Map> allSamples = []
        hosts.each { ComputeServer host ->
            try {
                Map<String, Long> vmNameToId = childVmNameToId(host)
                String output = runOnHost(host, COLLECT_CMD)
                if (!output) return

                // Split into domstats and host-metric sections.
                int marker = output.indexOf(HOST_MARKER)
                String domstatsPart = marker >= 0 ? output.substring(0, marker) : output
                String hostPart     = marker >= 0 ? output.substring(marker + HOST_MARKER.length()) : ''

                List<Map> parsed = parseDomstats(domstatsPart, host, vmNameToId, ts)
                allSamples.addAll(parsed)

                Map hostSample = parseHostStats(hostPart, host, ts)
                if (hostSample) store.saveHostSample(hostSample)
            } catch (Exception e) {
                log.error("Collection failed on host ${host?.name}: ${e.message}", e)
            }
        }
        int written = store.saveSamples(allSamples)
        log.debug("KVM collection wrote ${written} samples from ${hosts.size()} host(s)")
        return written
    }

    /** On-demand single pass (used by the 'Collect Now' button). */
    int collectNow() {
        store.init()
        return collectAll()
    }

    // ── Host / VM discovery via Morpheus data services ──────────────────────────

    /**
     * Discover hypervisor hosts. Prefer the morpheusHypervisor flag on the
     * ComputeServerType; fall back to type-code heuristics for VME/MVM/KVM.
     */
    List<ComputeServer> listKvmHosts() {
        try {
            List<ComputeServer> servers = morpheus.services.computeServer
                    .list(new DataQuery().withFilter('computeServerType.morpheusHypervisor', true))
            if (servers) return servers
        } catch (Exception e) {
            log.debug("morpheusHypervisor filter unavailable, falling back to heuristic: ${e.message}")
        }
        // Heuristic fallback — match common VME/MVM/KVM host type codes.
        List<ComputeServer> all = morpheus.services.computeServer.list(new DataQuery())
        return all.findAll { ComputeServer cs ->
            String code = (cs.computeServerType?.code ?: '').toLowerCase()
            code.contains('kvm') || code.contains('mvm') || code.contains('vme') || code.contains('hvm') ||
                    (cs.computeServerType?.vmHypervisor == true)
        }
    }

    /** Map libvirt domain name -> Morpheus ComputeServer id for VMs on this host. */
    Map<String, Long> childVmNameToId(ComputeServer host) {
        Map<String, Long> map = [:]
        try {
            List<ComputeServer> children = morpheus.services.computeServer
                    .list(new DataQuery().withFilter('parentServer.id', host.id))
            children.each { ComputeServer vm ->
                if (vm.name) map[vm.name] = vm.id
                // libvirt domains sometimes use externalId/uuid as the name
                if (vm.externalId) map[vm.externalId] = vm.id
            }
        } catch (Exception e) {
            log.debug("Could not load child VMs for host ${host?.name}: ${e.message}")
        }
        return map
    }

    // ── Command execution (agent-first, SSH fallback) ──────────────────────────

    /**
     * Execute a shell command on a host through Morpheus.
     *
     * NOTE: confirm the exact overload against the morpheus-plugin-api 1.3.3
     * javadoc for MorpheusContext.executeCommandOnServer(...). The 2-arg form
     * below is the simplest documented variant; if your appliance requires
     * explicit sudo/credential args, extend this single call site.
     */
    String runOnHost(ComputeServer server, String command) {
        try {
            def result = morpheus.executeCommandOnServer(server, command).blockingGet()
            if (result?.success) {
                return (result.output ?: result.data ?: '') as String
            }
            log.warn("Command on ${server.name} returned failure: ${result?.error ?: 'no detail'}")
        } catch (Exception e) {
            log.error("executeCommandOnServer failed on ${server.name}: ${e.message}", e)
        }
        return null
    }

    // ── Parsing ─────────────────────────────────────────────────────────────

    /**
     * Parse `virsh domstats --vcpu --all` output into per-VM sample maps holding
     * cumulative counters. We sum vcpu.N.delay (CPU-ready analog) and
     * vcpu.N.wait across all vCPUs; Ready% is derived later from deltas.
     */
    List<Map> parseDomstats(String output, ComputeServer host, Map<String, Long> vmNameToId, long ts) {
        if (!output) return []
        List<Map> docs = []
        // Each domain block begins with "Domain: 'name'".
        String[] blocks = output.split(/(?=Domain:\s*')/)
        blocks.each { String block ->
            if (!block?.trim()) return
            def nameMatch = (block =~ /Domain:\s*'(.+?)'/)
            if (!nameMatch) return
            String vmName = nameMatch[0][1]
            if (!vmName) return

            Map<String, String> stats = [:]
            block.readLines().each { String line ->
                def m = (line.trim() =~ /^([\w.]+)=(.+)$/)
                if (m) stats[m[0][1]] = m[0][2].trim()
            }

            long vcpuCount = asLong(stats['vcpu.current']) ?: asLong(stats['vcpu.maximum']) ?: 0L

            // Sum per-vcpu counters across all vCPUs of this domain.
            // Using sum(vcpu.N.time) for cpuTimeNs avoids depending on the
            // --cpu-total flag (the aggregate cpu.time field is only emitted
            // when --cpu-total is passed). Falls back to the aggregate if
            // present and the per-vcpu sum came out zero.
            long delaySum = 0L
            long waitSum  = 0L
            long timeSum  = 0L
            // v1.16: also sum block.N.rd/wr.bytes (disk IO) and net.N.rx/tx.bytes
            // across every block device + network interface attached to this VM.
            // Counters are cumulative since domain start; rates are derived at
            // read time from the delta between two consecutive samples.
            long diskRdSum = 0L
            long diskWrSum = 0L
            long netRxSum  = 0L
            long netTxSum  = 0L
            stats.each { String k, String v ->
                if (k ==~ /vcpu\.\d+\.delay/)       delaySum  += (asLong(v) ?: 0L)
                if (k ==~ /vcpu\.\d+\.wait/)        waitSum   += (asLong(v) ?: 0L)
                if (k ==~ /vcpu\.\d+\.time/)        timeSum   += (asLong(v) ?: 0L)
                if (k ==~ /block\.\d+\.rd\.bytes/)  diskRdSum += (asLong(v) ?: 0L)
                if (k ==~ /block\.\d+\.wr\.bytes/)  diskWrSum += (asLong(v) ?: 0L)
                if (k ==~ /net\.\d+\.rx\.bytes/)    netRxSum  += (asLong(v) ?: 0L)
                if (k ==~ /net\.\d+\.tx\.bytes/)    netTxSum  += (asLong(v) ?: 0L)
            }
            long cpuTime = timeSum > 0 ? timeSum : (asLong(stats['cpu.time']) ?: 0L)

            docs << [
                ts          : ts,
                hostName    : host.name,
                hostId      : host.id,
                vmName      : vmName,
                vmId        : vmNameToId[vmName],
                vcpuCount   : vcpuCount,
                cpuTimeNs   : cpuTime,
                vcpuDelayNs : delaySum,
                vcpuWaitNs  : waitSum,
                diskRdBytes : diskRdSum,
                diskWrBytes : diskWrSum,
                netRxBytes  : netRxSum,
                netTxBytes  : netTxSum
            ]
        }
        return docs
    }

    /**
     * Parse the trailing block that follows the ---HOST marker. Expected lines:
     *   1. /proc/loadavg          (1m 5m 15m runnable/total lastpid)
     *   2. /proc/stat first line  (cpu user nice system idle iowait irq softirq steal guest guest_nice)
     *   3. current CPU MHz        (from /proc/cpuinfo)
     *   4. max CPU freq in kHz    (from cpufreq, or 0 if unavailable)
     */
    Map parseHostStats(String text, ComputeServer host, long ts) {
        if (!text?.trim()) return null
        List<String> lines = text.readLines().findAll { it.trim() }
        if (lines.size() < 2) return null

        Map result = [ts: ts, hostId: host.id, hostName: host.name]

        try {
            String[] la = lines[0].trim().split(/\s+/)
            if (la.length >= 3) {
                result.load1  = la[0].toDouble()
                result.load5  = la[1].toDouble()
                result.load15 = la[2].toDouble()
            }
        } catch (Exception e) {
            log.debug("Failed to parse loadavg on ${host?.name}: ${e.message}")
        }

        try {
            String[] cs = lines[1].trim().split(/\s+/)
            if (cs.length >= 9 && cs[0].startsWith('cpu')) {
                result.cpu_user    = cs[1].toLong()
                result.cpu_nice    = cs[2].toLong()
                result.cpu_system  = cs[3].toLong()
                result.cpu_idle    = cs[4].toLong()
                result.cpu_iowait  = cs[5].toLong()
                result.cpu_irq     = cs[6].toLong()
                result.cpu_softirq = cs[7].toLong()
                result.cpu_steal   = cs[8].toLong()
            }
        } catch (Exception e) {
            log.debug("Failed to parse /proc/stat on ${host?.name}: ${e.message}")
        }

        if (lines.size() >= 3) {
            try { result.cpu_mhz = lines[2].trim().toDouble() } catch (Exception ignored) {}
        }
        if (lines.size() >= 4) {
            try {
                long khz = lines[3].trim().toLong()
                if (khz > 0) result.cpu_mhz_max = khz / 1000d
            } catch (Exception ignored) {}
        }
        // Socket/core arrive as TAGGED lines (SOCKETS= / CORES=), parsed by prefix
        // not position, so hosts lacking cpufreq or an lscpu "CPU max MHz" line
        // (which shift line positions) still populate correctly.
        lines.each { String ln ->
            String t = ln.trim()
            if (t.startsWith('SOCKETS=')) {
                try { long sk = t.substring(8).toLong(); if (sk > 0) result.sockets = sk } catch (Exception ignored) {}
            } else if (t.startsWith('CORES=')) {
                try { long cc = t.substring(6).toLong(); if (cc > 0) result.cores_per_socket = cc } catch (Exception ignored) {}
            } else if (t.startsWith('THREADS=')) {
                try { long th = t.substring(8).toLong(); if (th > 0) result.threads_per_core = th } catch (Exception ignored) {}
            }
        }
        return result
    }

    private static Long asLong(String s) {
        if (s == null) return null
        try { return Long.parseLong(s.trim()) } catch (NumberFormatException ignored) { return null }
    }
}
