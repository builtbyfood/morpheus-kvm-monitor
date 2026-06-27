package com.morpheusdata.kvmmonitor

import com.morpheusdata.core.AbstractServerTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

/**
 * Adds a "CPU Ready" tab directly to the Host (ComputeServer) detail page.
 *
 * This is the native integration point from the plugin docs
 * (AbstractServerTabProvider.renderTemplate(ComputeServer)). It renders a
 * server-side table of this host's VMs with their latest Ready%/Steal%, read
 * straight from the SQLite store — no extra JS round-trips or CSP headaches.
 */
@Slf4j
class KvmServerTabProvider extends AbstractServerTabProvider {

    Plugin plugin
    MorpheusContext morpheus
    KvmMetricStore store

    KvmServerTabProvider(Plugin plugin, MorpheusContext morpheus, KvmMetricStore store) {
        this.plugin    = plugin
        this.morpheus  = morpheus
        this.store     = store
    }

    @Override
    String getCode() { 'kvmMonitorServerTab' }

    @Override
    String getName() { 'KVM Monitor' }

    @Override
    HTMLResponse renderTemplate(ComputeServer server) {
        ViewModel<Map> model = new ViewModel<>()
        Map ctx = [hostName: server?.name, vms: [], hasData: false]
        try {
            long totalAllocated = 0L
            List<Map> vms = store.latestReadyByVm(server?.id).collect { Map vm ->
                double ready = (vm.readyPct ?: 0) as double
                double used  = (vm.usedPct  ?: 0) as double
                double steal = (vm.stealPct ?: 0) as double
                vm.readyClass    = ready >= 10 ? 'crit' : (ready >= 5 ? 'warn' : '')
                vm.readyBarWidth = (int) Math.min(Math.round(ready * 4), 120)
                // Force 2-decimal display so small but non-zero values
                // (e.g. 0.01%) are distinguishable from true zero.
                vm.readyPct = String.format('%.2f', ready)
                vm.usedPct  = String.format('%.2f', used)
                vm.stealPct = String.format('%.2f', steal)
                totalAllocated  += (vm.vcpuCount ?: 0) as long
                return vm
            }
            ctx.vms       = vms
            ctx.hasData   = !vms.isEmpty()
            ctx.allocated = totalAllocated

            Long hostCores = pickHostCores(server)
            if (hostCores && hostCores > 0) {
                ctx.hostCores = hostCores
                if (totalAllocated > 0) {
                    double ratio = totalAllocated / (double) hostCores
                    ctx.ratio      = Math.round(ratio * 10) / 10d
                    ctx.ratioClass = ratio >= 5 ? 'crit' : (ratio >= 3 ? 'warn' : '')
                }
            }

            // Host-level metrics: load avg, /proc/stat breakdown, MHz
            Map hs = store.latestHostStats(server?.id)
            if (hs) {
                if (hs.load1 != null) {
                    ctx.load1  = sprintf('%.2f', hs.load1)
                    ctx.load5  = sprintf('%.2f', hs.load5 ?: 0d)
                    ctx.load15 = sprintf('%.2f', hs.load15 ?: 0d)
                    if (hostCores && hostCores > 0) {
                        double load1Val = (hs.load1 as double)
                        ctx.loadClass = load1Val >= hostCores * 2 ? 'crit' :
                                        (load1Val >= hostCores ? 'warn' : '')
                    }
                    ctx.hasHostLoad = true
                }
                if (hs.iowaitPct != null) {
                    ctx.iowaitPct   = hs.iowaitPct
                    double iow      = (hs.iowaitPct as double)
                    ctx.iowaitClass = iow >= 15 ? 'crit' : (iow >= 10 ? 'warn' : '')
                    ctx.userPct     = hs.userPct
                    ctx.systemPct   = hs.systemPct
                    double sys      = (hs.systemPct ?: 0) as double
                    ctx.systemClass = sys >= 30 ? 'crit' : (sys >= 20 ? 'warn' : '')
                    ctx.hasHostCpu  = true
                }
                if (hs.cpuMhz != null) {
                    ctx.cpuMhz = Math.round((hs.cpuMhz as double))
                    if (hs.cpuMhzMax != null && (hs.cpuMhzMax as double) > 0) {
                        ctx.cpuMhzMax = Math.round((hs.cpuMhzMax as double))
                        double pct = ((hs.cpuMhz as double) / (hs.cpuMhzMax as double)) * 100d
                        ctx.cpuMhzPct   = Math.round(pct)
                        ctx.cpuMhzClass = pct < 60 ? 'crit' : (pct < 80 ? 'warn' : '')
                    }
                    ctx.hasHostMhz = true
                }
            }
        } catch (Exception e) {
            log.error("Failed loading KVM metrics for host ${server?.name}: ${e.message}", e)
        }
        model.object = ctx
        return getRenderer().renderTemplate('hbs/serverTab', model)
    }

    /** Defensively read host physical core count across possible field names. */
    private static Long pickHostCores(ComputeServer s) {
        if (!s) return null
        for (String f : ['maxCores', 'maxCpu', 'cores', 'cpuCount']) {
            try {
                def v = s."${f}"
                if (v && ((v as long) > 0)) return v as long
            } catch (Exception ignored) {
                // try next field
            }
        }
        return null
    }

    /**
     * Only show the tab on hosts that are hypervisors (where domstats applies).
     */
    @Override
    Boolean show(ComputeServer server, User user, Account account) {
        try {
            if (server?.computeServerType?.morpheusHypervisor) return true
            String code = (server?.computeServerType?.code ?: '').toLowerCase()
            return code.contains('kvm') || code.contains('mvm') || code.contains('vme') || code.contains('hvm') ||
                    server?.computeServerType?.vmHypervisor == true
        } catch (Exception ignored) {
            return false
        }
    }
}
