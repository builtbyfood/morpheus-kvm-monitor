package com.morpheusdata.kvmmonitor

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType
import com.morpheusdata.views.HandlebarsRenderer
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

/**
 * KVM CPU Monitor plugin.
 *
 * Rewritten per the Morpheus plugin docs findings:
 *   - Collection via MorpheusContext.executeCommandOnServer (agent-first).
 *   - Storage in self-contained SQLite (KvmMetricStore).
 *   - Native host tab via KvmServerTabProvider, plus standalone dashboard.
 */
@Slf4j
class KvmMonitorPlugin extends Plugin {

    // Init block runs before registerPlugin — setting the renderer here avoids
    // the DynamicTemplateLoader crash seen on HPE Morpheus 8.1.x.
    {
        this.renderer = new HandlebarsRenderer()
    }

    KvmMetricStore       store
    KvmCollectorService  collector
    KvmMonitorController  controller
    KvmServerTabProvider tabProvider
    KvmReportProvider    reportProvider

    @Override
    String getCode() { 'kvmMonitor' }

    @Override
    String getName() { 'KVM Monitor' }

    @Override
    void initialize() {
        setName('KVM Monitor')
        setDescription('Per-VM CPU Ready, Used, Steal, and disk/network I/O for KVM / HPE VM Essentials. Host oversubscription, load avg, I/O wait, user/system split, CPU MHz. Native host tab, dashboard with light/dark theme, and historical report with CSV export.')
        setAuthor('Travis DeLuca')

        store     = new KvmMetricStore(resolveDbPath())
        collector = new KvmCollectorService(morpheus, store)

        // Controller (pages + JSON API). Register the same way as the
        // tab/report providers since controllers.add() alone doesn't appear
        // to trigger route registration in this API version.
        controller = new KvmMonitorController(this, morpheus, store, collector)
        this.controllers.add(controller)
        this.registerProvider(controller)

        // Native host detail tab
        tabProvider = new KvmServerTabProvider(this, morpheus, store)
        this.registerProvider(tabProvider)

        // Historical CPU Ready report (Operations -> Reports)
        reportProvider = new KvmReportProvider(this, morpheus, store)
        this.registerProvider(reportProvider)

        // Start background collection using saved (or default) settings.
        Map cfg = loadSettings()
        collector.start(
            (cfg.intervalSeconds ?: 60) as int,
            (cfg.retentionDays   ?: 30) as int
        )
        log.info("KVM CPU Monitor initialized")
	
	// Dashboard widget (item type) — register the SAME way as the other providers.
	KvmMonitorDashboardItemProvider kvmDashItem =
                new KvmMonitorDashboardItemProvider(this, morpheus)
	this.registerProvider(kvmDashItem)

	// The dashboard itself.
	KvmMonitorDashboardProvider kvmDashboard =
                new KvmMonitorDashboardProvider(this, morpheus)
	this.registerProvider(kvmDashboard)
    }

    @Override
    void onDestroy() {
        try { collector?.stop() } catch (Exception ignored) {}
        log.info("KVM CPU Monitor destroyed")
    }

    Boolean hasCustomRenderer() { return true }

    // ── Plugin settings (Administration -> Integrations -> Plugins) ────────────

    @Override
    List<OptionType> getSettings() {
        [
            new OptionType(
                name: 'Collection Interval (seconds)', code: 'kvmMonitor.intervalSeconds',
                fieldName: 'intervalSeconds', fieldLabel: 'Collection Interval (seconds)',
                inputType: OptionType.InputType.NUMBER, defaultValue: '60', displayOrder: 0
            ),
            new OptionType(
                name: 'Retention (days)', code: 'kvmMonitor.retentionDays',
                fieldName: 'retentionDays', fieldLabel: 'Retention (days)',
                inputType: OptionType.InputType.NUMBER, defaultValue: '30', displayOrder: 1
            ),
            new OptionType(
                name: 'SQLite Path', code: 'kvmMonitor.dbPath',
                fieldName: 'dbPath', fieldLabel: 'SQLite Database Path',
                inputType: OptionType.InputType.TEXT,
                defaultValue: KvmMetricStore.DEFAULT_DB_PATH, displayOrder: 2
            )
        ]
    }

    private Map loadSettings() {
        try {
            String json = morpheus.getSettings(this).blockingGet()
            if (json) return new JsonSlurper().parseText(json) as Map
        } catch (Exception e) {
            log.debug("Using default settings: ${e.message}")
        }
        return [:]
    }

    private String resolveDbPath() {
        (loadSettings().dbPath ?: KvmMetricStore.DEFAULT_DB_PATH) as String
    }
}
