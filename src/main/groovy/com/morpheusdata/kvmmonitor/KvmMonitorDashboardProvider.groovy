package com.morpheusdata.kvmmonitor

import com.morpheusdata.core.dashboard.AbstractDashboardProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Dashboard
import com.morpheusdata.model.DashboardItem
import groovy.util.logging.Slf4j

/**
 * Registers the "KVM Monitor" dashboard (appears in
 * Administration -> Settings -> Dashboards to Display) containing the single
 * KVM Monitor React widget.
 */
@Slf4j
class KvmMonitorDashboardProvider extends AbstractDashboardProvider {

    Plugin plugin
    MorpheusContext morpheusContext

    KvmMonitorDashboardProvider(Plugin plugin, MorpheusContext context) {
        this.plugin = plugin
        this.morpheusContext = context
    }

    @Override MorpheusContext getMorpheus() { return morpheusContext }
    @Override Plugin getPlugin()            { return plugin }
    @Override String getCode()              { return 'kvm-monitor-dashboard' }
    @Override String getName()              { return 'KVM Monitor' }

    @Override
    Dashboard getDashboard() {
        log.info("KVM getDashboard() called - building dashboard payload")
        def rtn = new Dashboard()
        rtn.name             = getName()
        rtn.code             = getCode()
        rtn.dashboardId      = 'kvmMonitor'
        rtn.category         = 'kvmMonitor'
        rtn.title            = 'KVM Monitor'
        rtn.description      = 'CPU Ready and performance metrics for KVM / HPE VM Essentials.'
        rtn.defaultDashboard = true
        rtn.enabled          = true
        rtn.sourceType       = 'system'
        rtn.templatePath     = 'hbs/kvm-monitor-dashboard'
        // scriptPath MUST be non-null: Morpheus' scriptPathForPlugin() calls
        // .hashCode() on it during dashboard sync and NPEs if null. Point it at
        // a real compiled asset (the dashboard-level loader).
        rtn.scriptPath       = 'kvm-monitor-dashboard.js'

        def dashboardItems = []
        def itemType = getMorpheus().getDashboard()
                .getDashboardItemType('dashboard-item-kvm-monitor').blockingGet()
        log.info("KVM item type lookup for 'dashboard-item-kvm-monitor' -> ${itemType ? 'FOUND' : 'NULL'}")
        if (itemType) {
            def item = new DashboardItem()
            item.type       = itemType
            item.itemRow    = 0
            item.itemColumn = 0
            item.itemGroup  = 'main'
            item.groupRow   = 0
            dashboardItems << item
        } else {
            log.warn("KVM Monitor dashboard item type not found at registration time")
        }
        rtn.dashboardItems = dashboardItems
        log.info("KVM dashboard built with ${dashboardItems.size()} item(s)")
        return rtn
    }
}
