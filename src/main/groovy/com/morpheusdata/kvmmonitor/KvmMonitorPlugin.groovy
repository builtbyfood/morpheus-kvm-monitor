package com.morpheusdata.kvmmonitor

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.PluginProvider
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
    // Retained so the widget can be toggled at runtime (v2.7.0).
    KvmMonitorDashboardItemProvider dashItemProvider
    KvmMonitorDashboardProvider     dashboardProvider

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

        // Dashboard widget — opt-in, default OFF (the Morpheus 9.0 dashboard
        // 404 reproduction still argues for opt-in: it triggers under specific
        // cluster events and needs a manual plugin removal + reboot to clear).
        //
        // v2.7.0: both providers are always registered here so the dashboard
        // sync creates its rows on every startup. The collector then reconciles
        // the live pluginProviders map against the setting on each pass, so a
        // change applies within one collection interval with no restart.
        boolean dashboardEnabled = parseBoolean(cfg.dashboardWidgetEnabled)
        log.info("KVM Monitor settings keys=${cfg.keySet()} dashboardWidgetEnabled(raw)=" +
                "'${cfg.dashboardWidgetEnabled}' (${cfg.dashboardWidgetEnabled?.getClass()?.simpleName}) -> ${dashboardEnabled}")
        dashItemProvider  = new KvmMonitorDashboardItemProvider(this, morpheus)
        dashboardProvider = new KvmMonitorDashboardProvider(this, morpheus)
        this.registerProvider(dashItemProvider)
        this.registerProvider(dashboardProvider)
        log.info("KVM dashboard providers registered at startup so dashboard sync creates rows; " +
                "desired state from settings is ${dashboardEnabled ? 'on' : 'off'} and will be " +
                "applied within one collection interval.")

        // Reconcile the widget against the setting once per collection pass.
        collector.reconcileHook = { reconcileDashboardProviders() }
    }

    // ── Live dashboard widget toggle (v2.7.0) ─────────────────────────────────
    // Verified on Morpheus 9.0.2: dashboard providers are resolved live from
    // pluginProviders, so adding or removing them takes effect on the next
    // dashboard load with no plugin restart.
    //
    // Plugin.pluginProviders is protected, so these bridge methods are the only
    // way in. Every mutation is copy-on-write: build a new LinkedHashMap that
    // already contains the change, then assign it to the field in one step.
    // The live map is never modified in place — Plugin.getProviders() walks
    // keySet() while rendering, so an in-place edit could be observed midway.

    static final List<String> DASHBOARD_PROVIDER_CODES =
            ['dashboard-item-kvm-monitor', 'kvm-monitor-dashboard'].asImmutable()

    /** Current presence of each dashboard provider code in pluginProviders. */
    Map<String, Boolean> dashboardProviderPresence() {
        Map<String, PluginProvider> snapshot = this.pluginProviders
        Map<String, Boolean> rtn = [:]
        DASHBOARD_PROVIDER_CODES.each { String code ->
            rtn[code] = presentIn(snapshot, code)
        }
        return rtn
    }

    /**
     * Register or unregister both dashboard providers at runtime.
     * Returns the presence map after the change.
     */
    synchronized Map<String, Boolean> setDashboardProvidersEnabled(boolean on) {
        if (on) {
            addProvider('dashboard-item-kvm-monitor', dashItemProvider)
            addProvider('kvm-monitor-dashboard', dashboardProvider)
        } else {
            DASHBOARD_PROVIDER_CODES.each { String code -> removeProvider(code) }
        }
        Map<String, Boolean> after = dashboardProviderPresence()
        log.info("KVM dashboard widget: applied on=${on} -> ${after}")
        return after
    }

    /**
     * Compare the saved setting against the live map and apply only on a
     * difference. Called once per collection pass.
     */
    void reconcileDashboardProviders() {
        boolean desired = parseBoolean(loadSettings().dashboardWidgetEnabled)
        Map<String, Boolean> present = dashboardProviderPresence()
        boolean allPresent  = present.values().every { it }
        boolean nonePresent = present.values().every { !it }
        // A partial state (one of the two registered) counts as out of sync in
        // both directions, so it always gets corrected.
        boolean inSync = desired ? allPresent : nonePresent
        if (inSync) return
        log.info("KVM dashboard widget: setting=${desired ? 'on' : 'off'} but live state is " +
                "${present} — reconciling")
        setDashboardProvidersEnabled(desired)
    }

    /** Copy-on-write add. Returns true if the map was swapped. */
    private synchronized boolean addProvider(String code, PluginProvider provider) {
        if (provider == null) {
            log.warn("KVM dashboard widget: no retained instance for ${code}; cannot register")
            return false
        }
        Map<String, PluginProvider> current = this.pluginProviders
        if (presentIn(current, code)) return false
        Map<String, PluginProvider> next = new LinkedHashMap<String, PluginProvider>(current ?: [:])
        next.put(code, provider)
        this.pluginProviders = next
        log.info("KVM dashboard widget: registered ${code}")
        return true
    }

    /** Copy-on-write remove. Returns true if the map was swapped. */
    private synchronized boolean removeProvider(String code) {
        Map<String, PluginProvider> current = this.pluginProviders
        if (!presentIn(current, code)) return false
        Map<String, PluginProvider> next = new LinkedHashMap<String, PluginProvider>(current)
        List<String> keys = []
        next.each { k, v -> if (k == code || providerCode(v) == code) keys << (k as String) }
        keys.each { next.remove(it) }
        this.pluginProviders = next
        log.info("KVM dashboard widget: removed ${code} (keys ${keys})")
        return true
    }

    /** Match on the map key or the provider's own code, not just the key. */
    private static boolean presentIn(Map<String, PluginProvider> map, String code) {
        if (!map) return false
        boolean found = false
        map.each { k, v -> if (k == code || providerCode(v) == code) found = true }
        return found
    }

    private static String providerCode(def provider) {
        try { return provider?.getCode() as String } catch (Exception ignored) { return null }
    }

    @Override
    void onDestroy() {
        long t0 = System.currentTimeMillis()
        try { collector?.shutdown() } catch (Exception e) { log.warn("collector shutdown error: ${e.message}") }
        try { store?.close() }       catch (Exception e) { log.warn("store close error: ${e.message}") }
        log.info("KVM CPU Monitor destroyed in ${System.currentTimeMillis() - t0}ms")
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
            ),
            new OptionType(
                name: 'Dashboard Widget Enabled', code: 'kvmMonitor.dashboardWidgetEnabled',
                fieldName: 'dashboardWidgetEnabled',
                fieldLabel: 'Show Dashboard Widget (applies within one collection interval)',
                inputType: OptionType.InputType.CHECKBOX, defaultValue: 'off', displayOrder: 3
            )
        ]
    }

    private Map loadSettings() {
        try {
            String json = morpheus.getSettings(this).blockingGet()
            if (json) return new JsonSlurper().parseText(json) as Map
        } catch (Exception e) {
            // v2.6.1: was log.debug — a failure here silently forces every
            // setting to default, including dashboardWidgetEnabled=off, which
            // hides the widget even when the checkbox is ticked.
            log.warn("KVM Monitor: plugin settings could not be loaded, using defaults " +
                    "(dashboard widget will be OFF): ${e.class.simpleName}: ${e.message}")
        }
        return [:]
    }

    private String resolveDbPath() {
        (loadSettings().dbPath ?: KvmMetricStore.DEFAULT_DB_PATH) as String
    }

    /**
     * Parse a settings value as a boolean. Morpheus 9.x stores a CHECKBOX
     * OptionType as a JSON Boolean (true/false), so the value arrives here as
     * a java.lang.Boolean, not the 'on'/'off' string this once assumed. The
     * string forms are still accepted for older appliances and for the
     * 'off' defaultValue declared in getSettings().
     */
    private static boolean parseBoolean(Object v) {
        if (v == null) return false
        String s = v.toString().trim().toLowerCase()
        return s == 'on' || s == 'true' || s == '1' || s == 'yes'
    }
}
