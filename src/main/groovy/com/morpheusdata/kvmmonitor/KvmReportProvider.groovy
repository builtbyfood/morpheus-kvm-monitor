package com.morpheusdata.kvmmonitor

import com.morpheusdata.core.AbstractReportProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ReportResult
import com.morpheusdata.model.ReportResultRow
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

/**
 * Historical CPU Ready report. Appears under Operations -> Reports.
 *
 * Reads from the plugin's SQLite store (not the Morpheus DB), aggregates each
 * VM's avg/max Ready% and Steal% over a selectable window, and emits
 * ReportResultRows — which Morpheus renders and exposes for CSV export.
 */
@Slf4j
class KvmReportProvider extends AbstractReportProvider {

    Plugin          plugin
    MorpheusContext morpheus
    KvmMetricStore  store

    KvmReportProvider(Plugin plugin, MorpheusContext morpheus, KvmMetricStore store) {
        this.plugin   = plugin
        this.morpheus = morpheus
        this.store    = store
    }

    @Override String getCode() { 'kvmMonitorReport' }
    @Override String getName() { 'KVM Monitor Report' }
    @Override String getDescription() { 'Per-VM CPU Ready, Used, and Steal metrics audited over a configurable window.' }
    @Override String getCategory() { 'inventory' }
    @Override Boolean getOwnerOnly() { return false }
    @Override Boolean getMasterOnly() { return false }
    @Override Boolean getSupportsAllZoneTypes() { return true }

    @Override
    ServiceResponse validateOptions(Map opts) {
        return ServiceResponse.success()
    }

    @Override
    List<OptionType> getOptionTypes() {
        [
            new OptionType(
                code: 'kvmMonitorReport.window', name: 'Window (minutes)',
                fieldName: 'windowMinutes', fieldContext: 'config',
                fieldLabel: 'Window (minutes)', inputType: OptionType.InputType.NUMBER,
                defaultValue: '60', displayOrder: 0
            )
        ]
    }

    @Override
    void process(ReportResult reportResult) {
        try {
            awaitOrNoop(reportService().updateReportResultStatus(reportResult, ReportResult.Status.generating))
        } catch (Exception e) {
            log.error("KVM report: could not set generating status: ${e.message}", e)
            return
        }
        Long displayOrder = 0
        try {
            int minutes = ((reportResult.configMap?.windowMinutes ?: 60) as String).toInteger()
            log.info("KVM report: running for last ${minutes}-min window")
            List<Map> agg = store.aggregateByVm(minutes)
            log.info("KVM report: aggregated ${agg.size()} VM rows from store")

            List<ReportResultRow> rows = []
            agg.each { Map vm ->
                rows << new ReportResultRow(
                    section: ReportResultRow.SECTION_MAIN,
                    displayOrder: displayOrder++,
                    dataMap: [
                        vmName       : vm.vmName,
                        hostName     : vm.hostName,
                        vcpuCount    : vm.vcpuCount,
                        avgReadyPct  : vm.avgReadyPct,
                        maxReadyPct  : vm.maxReadyPct,
                        avgUsedPct   : vm.avgUsedPct,
                        maxUsedPct   : vm.maxUsedPct,
                        avgStealPct  : vm.avgStealPct,
                        maxStealPct  : vm.maxStealPct,
                        // v1.18: raw IO rates (also surfaced in CSV export)
                        avgDiskRdKBs : vm.avgDiskRdKBs,
                        maxDiskRdKBs : vm.maxDiskRdKBs,
                        avgDiskWrKBs : vm.avgDiskWrKBs,
                        maxDiskWrKBs : vm.maxDiskWrKBs,
                        avgNetRxKBs  : vm.avgNetRxKBs,
                        maxNetRxKBs  : vm.maxNetRxKBs,
                        avgNetTxKBs  : vm.avgNetTxKBs,
                        maxNetTxKBs  : vm.maxNetTxKBs,
                        // Pre-formatted "R / W" strings for the HBS template
                        avgDiskRW    : fmtKBs(vm.avgDiskRdKBs) + ' / ' + fmtKBs(vm.avgDiskWrKBs),
                        maxDiskRW    : fmtKBs(vm.maxDiskRdKBs) + ' / ' + fmtKBs(vm.maxDiskWrKBs),
                        avgNetRT     : fmtKBs(vm.avgNetRxKBs)  + ' / ' + fmtKBs(vm.avgNetTxKBs),
                        maxNetRT     : fmtKBs(vm.maxNetRxKBs)  + ' / ' + fmtKBs(vm.maxNetTxKBs),
                        samples      : vm.samples
                    ]
                )
            }

            log.info("KVM report: appending ${rows.size()} rows in chunks of 50")
            int chunkIdx = 0
            rows.collate(50).each { List<ReportResultRow> chunk ->
                chunkIdx++
                try {
                    awaitOrNoop(reportService().appendResultRows(reportResult, chunk))
                } catch (Exception ce) {
                    log.error("KVM report: chunk ${chunkIdx} (${chunk.size()} rows) failed: ${ce.message}", ce)
                    throw ce
                }
            }

            awaitOrNoop(reportService().updateReportResultStatus(reportResult, ReportResult.Status.ready))
            log.info("KVM report: completed (${rows.size()} rows)")
        } catch (Exception e) {
            log.error("KVM report failed: ${e.message}", e)
            try {
                awaitOrNoop(reportService().updateReportResultStatus(reportResult, ReportResult.Status.failed))
            } catch (Exception ignored) { /* already failed */ }
        }
    }

    /**
     * Compact KB/s formatter — matches the dashboard's fmtKBs JS helper so
     * the report and the live dashboard show identical strings for the same
     * underlying values.
     *   0          → "0"
     *   < 10       → 1 decimal (e.g. "4.2")
     *   < 1000     → integer  (e.g. "237")
     *   < 1000000  → "1.2M"
     *   ≥ 1000000  → "1.2G"
     */
    private static String fmtKBs(def v) {
        if (v == null) return '0'
        double d
        try { d = (v as Number).doubleValue() } catch (Exception ignored) { return '0' }
        if (!Double.isFinite(d) || d <= 0d) return '0'
        if (d < 10d)         return String.format('%.1f', d)
        if (d < 1000d)       return String.format('%.0f', d)
        if (d < 1_000_000d)  return String.format('%.1fM', d / 1000d)
        return String.format('%.1fG', d / 1_000_000d)
    }

    /**
     * The morpheus async services return Completable for void-result calls and
     * Single for value-returning calls. Completable has blockingAwait(); Single
     * has blockingGet(). Some accessor paths may even return a plain non-rx
     * value. Probe the actual type and call whatever's there.
     */
    private static void awaitOrNoop(def rxValue) {
        if (rxValue == null) return
        try {
            if (rxValue.respondsTo('blockingAwait', null)) {
                rxValue.blockingAwait()
            } else if (rxValue.respondsTo('blockingGet', null)) {
                rxValue.blockingGet()
            }
        } catch (Throwable t) {
            // bubble for caller's logging/handling
            throw new RuntimeException("rx blocking call failed: ${t.message}", t)
        }
    }

    /**
     * Resolve the report service across API-version differences.
     * 1.3.3 namespaces async services under morpheus.async.X; the docs example
     * uses the bare morpheus.report shorthand. Try both so we don't break on
     * either layout.
     */
    private def reportService() {
        try {
            def svc = morpheus.async.report
            if (svc != null) return svc
        } catch (Exception ignored) {}
        return morpheus.report
    }

    @Override
    HTMLResponse renderTemplate(ReportResult reportResult,
                                Map<String, List<ReportResultRow>> reportRowsBySection) {
        ViewModel<Map> model = new ViewModel<>()
        model.object = reportRowsBySection
        return getRenderer().renderTemplate('hbs/kvmReport', model)
    }
}
