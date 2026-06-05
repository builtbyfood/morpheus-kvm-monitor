package com.morpheusdata.kvmmonitor

import com.morpheusdata.core.dashboard.AbstractDashboardItemTypeProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.DashboardItemType
import groovy.util.logging.Slf4j

/**
 * Dashboard item type for the KVM Monitor React widget.
 *
 * Mirrors the working CatalogItemProvider pattern:
 *  - templatePath -> the empty-mount hbs (no .hbs suffix)
 *  - scriptPath   -> the COMPILED jsx asset path (no 'js/' prefix, .js not .jsx)
 *    The jsx-asset-pipeline gradle plugin compiles
 *    src/assets/js/kvm-monitor-widget.jsx into the served asset referenced here.
 */
@Slf4j
class KvmMonitorDashboardItemProvider extends AbstractDashboardItemTypeProvider {

    Plugin plugin
    MorpheusContext morpheusContext

    KvmMonitorDashboardItemProvider(Plugin plugin, MorpheusContext context) {
        this.plugin = plugin
        this.morpheusContext = context
    }

    @Override MorpheusContext getMorpheus() { return morpheusContext }
    @Override Plugin getPlugin()            { return plugin }
    @Override String getCode()              { return 'dashboard-item-kvm-monitor' }
    @Override String getName()              { return 'KVM Monitor' }

    @Override
    DashboardItemType getDashboardItemType() {
        def rtn = new DashboardItemType()
        rtn.name        = getName()
        rtn.code        = getCode()
        rtn.category    = 'kvmMonitor'
        rtn.title       = 'KVM Monitor'
        rtn.description = 'Per-VM CPU Ready, Used, and Steal for KVM / HPE VM Essentials.'
        rtn.uiSize      = 'lg'
        rtn.templatePath = 'hbs/kvm-monitor-widget'
        rtn.scriptPath   = 'kvm-monitor-widget.js'
        // Dashboard items must use a feature permission the viewing user holds,
        // like the built-in widgets do (cluster widgets use 'infrastructure-cluster'
        // / 'provisioning'). 'admin-cm' is an admin-category permission that the
        // dashboard renderer filters the item out on -> empty payload -> success:false.
        rtn.permission = morpheusContext.getPermission().getByCode('infrastructure-cluster').blockingGet()
        rtn.setAccessTypes(['read', 'full'])
        return rtn
    }
}
