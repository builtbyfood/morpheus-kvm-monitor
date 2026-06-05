package com.morpheusdata.kvmmonitor

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import com.morpheusdata.model.Permission
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.JsonResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

/**
 * Serves the standalone dashboard and the JSON data API. All data now comes
 * from the SQLite store (KvmMetricStore) instead of OpenSearch.
 */
@Slf4j
class KvmMonitorController implements PluginController {

    Plugin               plugin
    MorpheusContext      morpheus
    KvmMetricStore       store
    KvmCollectorService  collector

    KvmMonitorController(Plugin plugin, MorpheusContext morpheus,
                         KvmMetricStore store, KvmCollectorService collector) {
        this.plugin    = plugin
        this.morpheus  = morpheus
        this.store     = store
        this.collector = collector
    }

    @Override Plugin getPlugin()            { return plugin }
    void setPlugin(Plugin plugin)           { this.plugin = plugin }
    @Override MorpheusContext getMorpheus() { return morpheus }
    Boolean isEnabled()                     { return true }
    String getCode()                        { 'kvmMonitorController' }
    String getName()                        { 'KVM Monitor Controller' }

    // ── Routes ─────────────────────────────────────────────────────────────
    @Override
    List<Route> getRoutes() {
        log.info("KvmMonitorController getRoutes - registering plugin controller routes")
        def p = { String path, String action ->
            Route.build(path, action, Permission.build('admin-cm', 'full'))
        }
        [
            p('/kvmMonitor',                'dashboard'),
            p('/kvmMonitor/dashboard',      'dashboard'),
            p('/kvmMonitor/api/status',     'status'),
            p('/kvmMonitor/api/hosts',      'apiHosts'),
            p('/kvmMonitor/api/hostStats',  'apiHostStats'),
            p('/kvmMonitor/api/vms',        'apiVms'),
            p('/kvmMonitor/api/series',     'apiSeries'),
            p('/kvmMonitor/collectNow',     'collectNow')
        ]
    }

    // ── Pages ─────────────────────────────────────────────────────────────────

    def dashboard(ViewModel<Map> model) {
        String nonce = safeNonce(model)
        return HTMLResponse.success(buildDashboardHtml(nonce))
    }

    // ── JSON API ────────────────────────────────────────────────────────────

    def status(ViewModel<Map> model) {
        return JsonResponse.of([
            ok          : true,
            sampleCount : store.sampleCount(),
            hosts       : store.hosts().size()
        ])
    }

    def apiHosts(ViewModel<Map> model) {
        return JsonResponse.of([hosts: store.hosts()])
    }

    /**
     * v1.16: all hosts with their latest CPU/load/MHz breakdown merged in.
     * Joins `store.hosts()` (which knows about VM counts) with
     * `store.latestHostStats(hostId)` per host. Returns one row per host with
     * everything the dashboard needs in a single fetch.
     */
    def apiHostStats(ViewModel<Map> model) {
        List<Map> rows = store.hosts().collect { Map h ->
            Map stats = store.latestHostStats(h.hostId as Long) ?: [:]
            return (h + stats) as Map
        }
        return JsonResponse.of([hosts: rows])
    }

    def apiVms(ViewModel<Map> model) {
        Long hostId = asLong(model?.object?.hostId)
        return JsonResponse.of([vms: store.latestReadyByVm(hostId)])
    }

    def apiSeries(ViewModel<Map> model) {
        String vm = model?.object?.vm as String
        int minutes = (asLong(model?.object?.minutes) ?: 60L) as int
        if (!vm) return JsonResponse.of([error: 'vm parameter required'])
        return JsonResponse.of([vm: vm, series: store.readySeries(vm, minutes)])
    }

    def collectNow(ViewModel<Map> model) {
        int written = 0
        String error = null
        try {
            written = collector.collectNow()
        } catch (Exception e) {
            error = e.message
            log.error("collectNow failed: ${e.message}", e)
        }
        return JsonResponse.of([success: error == null, written: written, error: error])
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Long asLong(Object o) {
        if (o == null) return null
        try { return Long.parseLong(o.toString()) } catch (Exception ignored) { return null }
    }

    private static String safeNonce(ViewModel model) {
        try { return (model?.request?.getAttribute('js-nonce') ?: '') as String }
        catch (Exception ignored) { return '' }
    }

    private String buildDashboardHtml(String nonce) {
        String n = nonce ? " nonce=\"${nonce}\"" : ''
        return """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>KVM Monitor</title>
<script${n}>
// Theme init — runs BEFORE body paints so there's no light/dark flash.
// Priority: localStorage (manual override) → cookie hint → OS preference → dark.
(function () {
  var t = null;
  try { t = localStorage.getItem('kvm-theme'); } catch (e) {}
  if (t !== 'light' && t !== 'dark') {
    var c = (document.cookie || '').toLowerCase();
    // Best-effort Morpheus cookie sniffing — common patterns
    if (/theme=light|darkmode=false|colormode=light/.test(c)) t = 'light';
    else if (/theme=dark|darkmode=true|colormode=dark/.test(c)) t = 'dark';
  }
  if (t !== 'light' && t !== 'dark') {
    t = (window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches) ? 'light' : 'dark';
  }
  document.documentElement.setAttribute('data-theme', t);
})();
</script>
<style>
  /* ── Theme variables ─────────────────────────────────────────────────── */
  :root[data-theme="dark"] {
    --bg:              #0d1117;
    --surface:         #161b22;
    --surface-2:       #0d1117;
    --surface-hover:   #1c2128;
    --border:          #21262d;
    --border-strong:   #30363d;
    --text:            #e8eaec;
    --text-muted:      #8b949e;
    --text-dim:        #6e7681;
    --text-tooltip:    #c9d1d9;
    --accent:          #58a6ff;
    --accent-ring:     rgba(88,166,255,0.15);
    --success:         #3fb950;
    --success-bg:      rgba(63,185,80,0.08);
    --success-border:  rgba(63,185,80,0.2);
    --warning:         #d29922;
    --warning-bg:      rgba(210,153,34,0.08);
    --warning-border:  rgba(210,153,34,0.2);
    --danger:          #f85149;
    --danger-bg:       rgba(248,81,73,0.08);
    --danger-border:   rgba(248,81,73,0.2);
    --primary:         #238636;
    --primary-hover:   #2ea043;
    --secondary:       #21262d;
    --secondary-hover: #30363d;
    --bar-bg:          #21262d;
    --shadow:          0 8px 24px rgba(0,0,0,0.4);
    --icon-bg:         rgba(139,148,158,0.2);
  }
  :root[data-theme="light"] {
    --bg:              #ffffff;
    --surface:         #f6f8fa;
    --surface-2:       #ffffff;
    --surface-hover:   #f0f3f6;
    --border:          #d0d7de;
    --border-strong:   #afb8c1;
    --text:            #1f2328;
    --text-muted:      #59636e;
    --text-dim:        #818b98;
    --text-tooltip:    #f6f8fa;
    --accent:          #0969da;
    --accent-ring:     rgba(9,105,218,0.15);
    --success:         #1a7f37;
    --success-bg:      rgba(26,127,55,0.08);
    --success-border:  rgba(26,127,55,0.3);
    --warning:         #9a6700;
    --warning-bg:      rgba(154,103,0,0.08);
    --warning-border:  rgba(154,103,0,0.3);
    --danger:          #cf222e;
    --danger-bg:       rgba(207,34,46,0.08);
    --danger-border:   rgba(207,34,46,0.3);
    --primary:         #1f883d;
    --primary-hover:   #1a7f37;
    --secondary:       #eaeef2;
    --secondary-hover: #d0d7de;
    --bar-bg:          #eaeef2;
    --shadow:          0 8px 24px rgba(0,0,0,0.1);
    --icon-bg:         rgba(89,99,110,0.2);
  }
  :root[data-theme="light"] .kvm-info-bubble { background: #1f2328; color: #f6f8fa; }
  :root[data-theme="light"] .kvm-info-bubble::before { border-bottom-color: #1f2328; }
  :root[data-theme="light"] .kvm-info-bubble strong { color: #79c0ff; }

  *, *::before, *::after { box-sizing: border-box; }
  html, body { margin: 0; padding: 0; background: var(--bg); color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    font-size: 14px; line-height: 1.5; }
  .kvm-wrap { max-width: 1400px; margin: 0 auto; padding: 24px; }

  /* Shared nav */
  .kvm-nav { display: flex; align-items: center; gap: 18px; padding-bottom: 14px;
    margin-bottom: 18px; border-bottom: 1px solid var(--border); }
  .kvm-nav .brand-name { font-size: 18px; font-weight: 500; color: var(--text); }
  .kvm-nav .links { display: flex; gap: 4px; margin-left: 8px; }
  .kvm-nav .links a { color: var(--text-muted); text-decoration: none; padding: 6px 12px;
    border-radius: 6px; font-size: 13px; transition: all 0.15s; }
  .kvm-nav .links a:hover { color: var(--text); background: var(--surface); }
  .kvm-nav .links a.active { color: var(--text); background: var(--surface);
    box-shadow: inset 0 -2px 0 var(--accent); }
  .kvm-nav .spacer { flex: 1; }
  .kvm-nav-actions { display: flex; align-items: center; gap: 10px; }

  .kvm-status { display: inline-flex; align-items: center; gap: 6px; font-size: 12px;
    padding: 6px 10px; border-radius: 6px; border: 1px solid var(--border-strong);
    background: var(--surface); color: var(--text-muted); }
  .kvm-status .dot { width: 6px; height: 6px; border-radius: 50%; background: var(--text-dim); }
  .kvm-status.live { color: var(--success); background: var(--success-bg);
    border-color: var(--success-border); }
  .kvm-status.live .dot { background: var(--success); animation: pulse 2s ease-in-out infinite; }
  .kvm-status.err { color: var(--danger); background: var(--danger-bg);
    border-color: var(--danger-border); }
  .kvm-status.err .dot { background: var(--danger); }
  @keyframes pulse { 0%, 100% { opacity: 1 } 50% { opacity: 0.4 } }

  .kvm-btn { background: var(--primary); color: #fff; border: 0; padding: 7px 14px;
    border-radius: 6px; font-size: 13px; font-weight: 500; cursor: pointer;
    transition: background 0.15s; font-family: inherit; }
  .kvm-btn:hover { background: var(--primary-hover); }
  .kvm-btn:disabled { background: var(--border-strong); cursor: not-allowed; opacity: 0.6; }
  .kvm-btn.secondary { background: var(--secondary); color: var(--text); }
  .kvm-btn.secondary:hover { background: var(--secondary-hover); }

  /* Theme toggle */
  .kvm-theme-btn { background: transparent; border: 1px solid var(--border-strong);
    color: var(--text-muted); width: 32px; height: 32px; border-radius: 6px; cursor: pointer;
    display: inline-flex; align-items: center; justify-content: center;
    font-size: 14px; transition: all 0.15s; padding: 0; }
  .kvm-theme-btn:hover { color: var(--text); background: var(--surface); border-color: var(--accent); }
  :root[data-theme="dark"]  .kvm-theme-btn .moon { display: none; }
  :root[data-theme="dark"]  .kvm-theme-btn .sun  { display: inline; }
  :root[data-theme="light"] .kvm-theme-btn .moon { display: inline; }
  :root[data-theme="light"] .kvm-theme-btn .sun  { display: none; }

  /* Stat cards */
  .kvm-cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px;
    margin-bottom: 20px; }
  .kvm-card { background: var(--surface); border: 1px solid var(--border); border-radius: 8px;
    padding: 14px 16px; }
  .kvm-card.healthy   { border-left: 3px solid var(--success); }
  .kvm-card.watch     { border-left: 3px solid var(--warning); }
  .kvm-card.impacting { border-left: 3px solid var(--danger); }
  .kvm-card .label { font-size: 11px; color: var(--text-dim); text-transform: uppercase;
    letter-spacing: 0.5px; margin-bottom: 6px; }
  .kvm-card .value { font-size: 24px; font-weight: 500; color: var(--text); line-height: 1; }
  .kvm-card.healthy   .value { color: var(--success); }
  .kvm-card.watch     .value { color: var(--warning); }
  .kvm-card.impacting .value { color: var(--danger); }
  .kvm-card .sub { font-size: 11px; color: var(--text-dim); margin-top: 4px; }

  /* Host cards (per-host overview) */
  .kvm-section-title { font-size: 11px; color: var(--text-dim); text-transform: uppercase;
    letter-spacing: 0.5px; margin: 0 0 10px; font-weight: 500; }
  .kvm-hostgrid { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
    gap: 12px; margin-bottom: 20px; }
  .kvm-hcard { background: var(--surface); border: 1px solid var(--border); border-radius: 8px;
    padding: 14px 16px; }
  .kvm-hcard .hname { font-size: 14px; font-weight: 500; color: var(--text); margin-bottom: 10px;
    display: flex; align-items: center; justify-content: space-between; gap: 8px; }
  .kvm-hcard .vmcount { font-size: 11px; color: var(--text-dim); font-weight: 400; }
  .kvm-hcard .row { display: flex; align-items: center; justify-content: space-between;
    font-size: 12px; padding: 3px 0; }
  .kvm-hcard .row .lbl { color: var(--text-muted); }
  .kvm-hcard .row .val { font-variant-numeric: tabular-nums; color: var(--text); }
  .kvm-hcard .row .val.warn { color: var(--warning); }
  .kvm-hcard .row .val.bad  { color: var(--danger); }
  .kvm-hcard .loadpill { display: inline-flex; gap: 8px; font-variant-numeric: tabular-nums;
    font-size: 12px; }
  .kvm-hcard .loadpill .v { color: var(--text); }
  .kvm-hcard .loadpill .s { color: var(--text-dim); }
  .kvm-hcard .mhzbar { width: 100%; height: 4px; background: var(--bar-bg); border-radius: 2px;
    overflow: hidden; margin-top: 4px; }
  .kvm-hcard .mhzfill { height: 100%; background: var(--accent); transition: width 0.3s ease; }
  .kvm-hcard .splitrow { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
  .kvm-hcard .stat { font-size: 11px; }
  .kvm-hcard .stat .lbl { color: var(--text-dim); text-transform: uppercase; letter-spacing: 0.5px;
    font-size: 10px; }
  .kvm-hcard .stat .val { font-size: 13px; font-weight: 500; color: var(--text);
    font-variant-numeric: tabular-nums; }
  .kvm-hcard .stat .val.warn { color: var(--warning); }
  .kvm-hcard .stat .val.bad  { color: var(--danger); }
  .kvm-hcard .divider { height: 1px; background: var(--border); margin: 8px 0; }
  .kvm-hcard.empty { padding: 24px; text-align: center; color: var(--text-dim); font-size: 12px;
    font-style: italic; }

  /* Toolbar */
  .kvm-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
  .kvm-search { flex: 1; position: relative; }
  .kvm-search input { width: 100%; background: var(--surface-2); border: 1px solid var(--border-strong);
    border-radius: 6px; color: var(--text); padding: 7px 10px 7px 32px; font-size: 13px;
    font-family: inherit; }
  .kvm-search input:focus { outline: none; border-color: var(--accent);
    box-shadow: 0 0 0 3px var(--accent-ring); }
  .kvm-search-icon { position: absolute; left: 10px; top: 50%; transform: translateY(-50%);
    color: var(--text-dim); pointer-events: none; font-size: 14px; line-height: 1; }
  .kvm-select { background: var(--surface-2); border: 1px solid var(--border-strong); border-radius: 6px;
    color: var(--text); padding: 7px 10px; font-size: 13px; font-family: inherit; cursor: pointer; }

  /* Table */
  .kvm-tbl-wrap { background: var(--surface); border: 1px solid var(--border); border-radius: 8px;
    overflow: hidden; }
  .kvm-tbl { width: 100%; border-collapse: collapse; font-size: 13px; }
  .kvm-tbl thead tr { background: var(--surface-2); }
  .kvm-tbl th { padding: 10px 14px; color: var(--text-dim); font-weight: 500; font-size: 11px;
    text-transform: uppercase; letter-spacing: 0.5px; text-align: left;
    border-bottom: 1px solid var(--border); white-space: nowrap; }
  .kvm-tbl th.num { text-align: right; }
  .kvm-tbl td { padding: 12px 14px; border-top: 1px solid var(--border); vertical-align: middle; }
  .kvm-tbl tbody tr:first-child td { border-top: 0; }
  .kvm-tbl tbody tr:hover { background: var(--surface-hover); }
  .kvm-tbl td.num { text-align: right; font-variant-numeric: tabular-nums; }
  .kvm-tbl td.vm-name { font-weight: 500; color: var(--text); }
  .kvm-tbl td.host { color: var(--text-muted); }

  .kvm-bar-cell { display: flex; align-items: center; gap: 10px; }
  .kvm-bar-track { width: 80px; height: 6px; background: var(--bar-bg); border-radius: 3px;
    overflow: hidden; flex-shrink: 0; }
  .kvm-bar-fill { height: 100%; transition: width 0.3s ease; }
  .kvm-bar-fill.healthy   { background: var(--success); }
  .kvm-bar-fill.watch     { background: var(--warning); }
  .kvm-bar-fill.impacting { background: var(--danger); }
  .kvm-bar-value { font-variant-numeric: tabular-nums; min-width: 48px; }
  .kvm-bar-value.healthy   { color: var(--success); font-weight: 500; }
  .kvm-bar-value.watch     { color: var(--warning); font-weight: 500; }
  .kvm-bar-value.impacting { color: var(--danger); font-weight: 500; }

  /* Info tooltips */
  .kvm-info { position: relative; display: inline-block; margin-left: 4px;
    cursor: help; vertical-align: middle; }
  .kvm-info-icon { display: inline-flex; align-items: center; justify-content: center;
    width: 14px; height: 14px; border-radius: 50%; background: var(--icon-bg);
    color: var(--text-muted); font-size: 9px; font-weight: 700; font-style: normal; }
  .kvm-info-bubble { position: absolute; top: calc(100% + 8px); left: 0;
    width: 280px; padding: 12px 14px; background: var(--surface-hover); color: var(--text-tooltip);
    border: 1px solid var(--border-strong); border-radius: 6px;
    font-size: 12px; font-weight: 400; line-height: 1.6; text-transform: none;
    letter-spacing: normal; text-align: left; white-space: normal;
    box-shadow: var(--shadow);
    opacity: 0; visibility: hidden; pointer-events: none;
    transition: opacity 0.12s, visibility 0.12s; z-index: 1000; }
  .kvm-info-bubble::before { content: ''; position: absolute; top: -6px; left: 4px;
    width: 0; height: 0; border-left: 6px solid transparent; border-right: 6px solid transparent;
    border-bottom: 6px solid var(--border-strong); }
  .kvm-info-bubble strong { color: var(--accent); font-weight: 500; }
  .kvm-info:hover .kvm-info-bubble { opacity: 1; visibility: visible; }
  .kvm-info.end .kvm-info-bubble { left: auto; right: 0; }
  .kvm-info.end .kvm-info-bubble::before { left: auto; right: 4px; }

  /* States */
  .kvm-skel { padding: 40px; text-align: center; color: var(--text-dim); font-size: 13px; }
  .kvm-spinner { display: inline-block; width: 16px; height: 16px; vertical-align: -3px;
    margin-right: 8px; border: 2px solid var(--border-strong); border-top-color: var(--accent);
    border-radius: 50%; animation: spin 0.8s linear infinite; }
  @keyframes spin { to { transform: rotate(360deg); } }
  .kvm-empty { padding: 60px 24px; text-align: center; color: var(--text-muted); }
  .kvm-empty .big { font-size: 15px; color: var(--text); margin-bottom: 6px; font-weight: 500; }
  .kvm-empty .small { font-size: 13px; color: var(--text-dim); }

  .kvm-foot { margin-top: 14px; font-size: 11px; color: var(--text-dim); text-align: center; }

  @media (max-width: 720px) {
    .kvm-cards { grid-template-columns: repeat(2, 1fr); }
    .kvm-bar-track { width: 50px; }
    .kvm-nav { flex-wrap: wrap; gap: 12px; }
    .kvm-nav .spacer { display: none; }
  }
</style>
</head>
<body>
<div class="kvm-wrap">

  <nav class="kvm-nav">
    <span class="brand-name">KVM Monitor</span>
    <div class="links">
      <a href="/plugin/kvmMonitor/dashboard" class="active">Dashboard</a>
    </div>
    <div class="spacer"></div>
    <div class="kvm-nav-actions">
      <button id="kvm-theme-toggle" class="kvm-theme-btn" type="button"
        title="Toggle light/dark theme" aria-label="Toggle theme">
        <span class="sun" aria-hidden="true">☀</span><span class="moon" aria-hidden="true">☾</span>
      </button>
      <span id="kvm-status" class="kvm-status">
        <span class="dot"></span><span id="kvm-status-text">loading…</span>
      </span>
      <button id="kvm-collect" class="kvm-btn" type="button">Collect Now</button>
    </div>
  </nav>

  <section class="kvm-cards">
    <div class="kvm-card"><div class="label">Total VMs</div><div class="value" id="kvm-stat-total">—</div></div>
    <div class="kvm-card healthy"><div class="label">Healthy</div><div class="value" id="kvm-stat-healthy">—</div><div class="sub">Ready &lt; 5%</div></div>
    <div class="kvm-card watch"><div class="label">Watch</div><div class="value" id="kvm-stat-watch">—</div><div class="sub">Ready 5–10%</div></div>
    <div class="kvm-card impacting"><div class="label">Impacting</div><div class="value" id="kvm-stat-impacting">—</div><div class="sub">Ready &gt; 10%</div></div>
  </section>

  <h3 class="kvm-section-title">Hosts</h3>
  <section class="kvm-hostgrid" id="kvm-hostgrid">
    <div class="kvm-hcard empty">Loading host metrics…</div>
  </section>

  <h3 class="kvm-section-title">Virtual Machines</h3>
  <div class="kvm-toolbar">
    <div class="kvm-search">
      <span class="kvm-search-icon">⌕</span>
      <input id="kvm-search" type="text" placeholder="Search VMs or hosts…" autocomplete="off">
    </div>
    <select id="kvm-sort" class="kvm-select">
      <option value="ready-desc">Sort: Ready % (high to low)</option>
      <option value="ready-asc">Sort: Ready % (low to high)</option>
      <option value="used-desc">Sort: Used % (high to low)</option>
      <option value="steal-desc">Sort: Steal % (high to low)</option>
      <option value="disk-desc">Sort: Disk I/O (high to low)</option>
      <option value="net-desc">Sort: Net I/O (high to low)</option>
      <option value="name-asc">Sort: VM name (A→Z)</option>
      <option value="host-asc">Sort: Host (A→Z)</option>
    </select>
  </div>

  <div class="kvm-tbl-wrap">
    <table class="kvm-tbl">
      <thead><tr>
        <th>VM</th>
        <th>Host</th>
        <th class="num">vCPUs<span class="kvm-info"><span class="kvm-info-icon">i</span>
          <span class="kvm-info-bubble"><strong>vCPUs</strong> — virtual CPUs assigned to this VM. Each must be placed on a physical core when the VM runs, so higher vCPU counts increase scheduling pressure on the host.</span></span></th>
        <th>Ready %<span class="kvm-info"><span class="kvm-info-icon">i</span>
          <span class="kvm-info-bubble"><strong>CPU Ready %</strong> — time a vCPU was runnable but waiting for the host scheduler to place it on a physical core. Normalized per vCPU.<br><br>
            • &lt; 5% — healthy<br>
            • 5–10% — watch<br>
            • &gt; 10% — performance-impacting<br><br>
            Caused by host oversubscription. Fix by reducing vCPU allocation, rebalancing VMs across hosts, or adding capacity.</span></span></th>
        <th class="num">Used %<span class="kvm-info"><span class="kvm-info-icon">i</span>
          <span class="kvm-info-bubble"><strong>CPU Used %</strong> — actual CPU consumed by this VM, normalized per vCPU. The %RUN counterpart to Ready %.<br><br>
            Read together with Ready %:<br>
            • High Used + high Ready — real contention<br>
            • Low Used + high Ready — host oversubscription<br>
            • High Used alone — busy VM, no scheduling pressure<br><br>
            Sustained &gt; 85–90% means the VM is CPU-saturated and likely needs more vCPUs or workload distribution.</span></span></th>
        <th class="num">Steal %<span class="kvm-info"><span class="kvm-info-icon">i</span>
          <span class="kvm-info-bubble"><strong>Steal %</strong> — time a vCPU was de-scheduled by the host while still wanting to run. The host took the pCPU back for something else.<br><br>
            Usually 0 on healthy hosts. Any reading above 0 means real CPU contention from other host workloads. Inside the VM, this is the same metric you'd see as %st in top.</span></span></th>
        <th class="num">Disk R/W<span class="kvm-info"><span class="kvm-info-icon">i</span>
          <span class="kvm-info-bubble"><strong>Disk read / write</strong> — block I/O rates in KB/s for this VM, summed across all attached block devices. Computed from the delta of cumulative <code>block.N.rd.bytes</code> / <code>block.N.wr.bytes</code> between the two newest samples.</span></span></th>
        <th class="num">Net R/T<span class="kvm-info end"><span class="kvm-info-icon">i</span>
          <span class="kvm-info-bubble"><strong>Net receive / transmit</strong> — network I/O rates in KB/s for this VM, summed across all network interfaces. Computed from the delta of cumulative <code>net.N.rx.bytes</code> / <code>net.N.tx.bytes</code> between the two newest samples.</span></span></th>
      </tr></thead>
      <tbody id="kvm-rows">
        <tr><td colspan="8"><div class="kvm-skel"><span class="kvm-spinner"></span>Loading metrics…</div></td></tr>
      </tbody>
    </table>
  </div>

  <p class="kvm-foot">
    Hover the <strong style="color:var(--text-muted)">i</strong> icons for guidance. All percentages normalized per vCPU.
  </p>
</div>

<script${n}>
(function () {
  'use strict';
  var BASE = '/plugin/kvmMonitor';
  var state = { vms: [], hosts: [], query: '', sort: 'ready-desc', lastFetch: null, refreshing: false };
  var REFRESH_MS = 30000;

  var \$rows     = document.getElementById('kvm-rows');
  var \$hostgrid = document.getElementById('kvm-hostgrid');
  var \$search   = document.getElementById('kvm-search');
  var \$sort     = document.getElementById('kvm-sort');
  var \$collect  = document.getElementById('kvm-collect');
  var \$status   = document.getElementById('kvm-status');
  var \$stText   = document.getElementById('kvm-status-text');
  var \$themeBtn = document.getElementById('kvm-theme-toggle');
  var \$cTotal   = document.getElementById('kvm-stat-total');
  var \$cHealth  = document.getElementById('kvm-stat-healthy');
  var \$cWatch   = document.getElementById('kvm-stat-watch');
  var \$cImpact  = document.getElementById('kvm-stat-impacting');

  // ── Theme toggle ──
  \$themeBtn.addEventListener('click', function () {
    var current = document.documentElement.getAttribute('data-theme') || 'dark';
    var next = (current === 'dark') ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    try { localStorage.setItem('kvm-theme', next); } catch (e) {}
  });

  // ── Helpers ──
  function esc(s) {
    if (s == null) return '';
    return String(s).replace(/[&<>"']/g, function (c) {
      return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[c];
    });
  }
  function readyClass(v) {
    var n = Number(v) || 0;
    if (n >= 10) return 'impacting';
    if (n >= 5)  return 'watch';
    return 'healthy';
  }
  function num(v, dec) {
    var n = Number(v);
    if (!isFinite(n)) return '—';
    return dec != null ? n.toFixed(dec) : Math.round(n).toString();
  }
  function setStatus(kind, text) {
    \$status.className = 'kvm-status' + (kind ? ' ' + kind : '');
    \$stText.textContent = text;
  }
  function relTime(d) {
    if (!d) return '';
    var s = Math.round((Date.now() - d.getTime()) / 1000);
    if (s < 5)   return 'just now';
    if (s < 60)  return s + 's ago';
    if (s < 3600) return Math.round(s/60) + 'm ago';
    return Math.round(s/3600) + 'h ago';
  }
  function fmtKBs(v) {
    var n = Number(v);
    if (!isFinite(n) || n <= 0) return '0';
    if (n < 10)      return n.toFixed(1);
    if (n < 1000)    return Math.round(n).toString();
    if (n < 1000000) return (n / 1000).toFixed(1) + 'M';
    return (n / 1000000).toFixed(1) + 'G';
  }

  function renderCards(vms) {
    var h = 0, w = 0, i = 0;
    vms.forEach(function (vm) {
      var r = Number(vm.readyPct) || 0;
      if (r >= 10) i++; else if (r >= 5) w++; else h++;
    });
    \$cTotal.textContent  = vms.length;
    \$cHealth.textContent = h;
    \$cWatch.textContent  = w;
    \$cImpact.textContent = i;
  }

  function renderTable() {
    var q = state.query.trim().toLowerCase();
    var rows = state.vms.filter(function (vm) {
      if (!q) return true;
      return (vm.vmName   || '').toLowerCase().indexOf(q) !== -1 ||
             (vm.hostName || '').toLowerCase().indexOf(q) !== -1;
    });
    var by = state.sort;
    rows.sort(function (a, b) {
      switch (by) {
        case 'ready-asc':  return (a.readyPct||0) - (b.readyPct||0);
        case 'used-desc':  return (b.usedPct||0)  - (a.usedPct||0);
        case 'steal-desc': return (b.stealPct||0) - (a.stealPct||0);
        case 'disk-desc':  return ((b.diskRdKBs||0)+(b.diskWrKBs||0)) - ((a.diskRdKBs||0)+(a.diskWrKBs||0));
        case 'net-desc':   return ((b.netRxKBs||0)+(b.netTxKBs||0))   - ((a.netRxKBs||0)+(a.netTxKBs||0));
        case 'name-asc':   return (a.vmName||'').localeCompare(b.vmName||'');
        case 'host-asc':   return (a.hostName||'').localeCompare(b.hostName||'');
        default:           return (b.readyPct||0) - (a.readyPct||0);
      }
    });
    if (!rows.length) {
      \$rows.innerHTML = '<tr><td colspan="8"><div class="kvm-empty">' +
        '<div class="big">' + (state.vms.length ? 'No matches' : 'No data yet') + '</div>' +
        '<div class="small">' + (state.vms.length
          ? 'Try a different search.'
          : 'Click <strong>Collect Now</strong> to gather the first sample, or wait for the next scheduled collection.') +
        '</div></div></td></tr>';
      return;
    }
    var html = '';
    for (var k = 0; k < rows.length; k++) {
      var vm = rows[k];
      var r = Number(vm.readyPct) || 0;
      var cls = readyClass(r);
      var fillW = Math.min(Math.max(r * 5, 2), 100);
      html += '<tr>' +
        '<td class="vm-name">' + esc(vm.vmName) + '</td>' +
        '<td class="host">'   + esc(vm.hostName || '—') + '</td>' +
        '<td class="num">'    + (vm.vcpuCount || 0) + '</td>' +
        '<td><div class="kvm-bar-cell">' +
          '<div class="kvm-bar-track"><div class="kvm-bar-fill ' + cls + '" style="width:' + fillW + '%"></div></div>' +
          '<span class="kvm-bar-value ' + cls + '">' + num(r, 1) + '%</span>' +
        '</div></td>' +
        '<td class="num">' + num(vm.usedPct, 1)  + '%</td>' +
        '<td class="num">' + num(vm.stealPct, 1) + '%</td>' +
        '<td class="num">' + fmtKBs(vm.diskRdKBs) + ' / ' + fmtKBs(vm.diskWrKBs) + '</td>' +
        '<td class="num">' + fmtKBs(vm.netRxKBs)  + ' / ' + fmtKBs(vm.netTxKBs)  + '</td>' +
      '</tr>';
    }
    \$rows.innerHTML = html;
  }

  function renderHostCards() {
    if (!\$hostgrid) return;
    var hosts = state.hosts || [];
    if (!hosts.length) {
      \$hostgrid.innerHTML = '<div class="kvm-hcard empty">No host data yet — waiting for first sample.</div>';
      return;
    }
    hosts = hosts.slice().sort(function (a, b) { return (b.load1||0) - (a.load1||0); });
    function loadCls(load1, vcpus) {
      if (!vcpus) return '';
      if (load1 >= vcpus * 1.5) return 'bad';
      if (load1 >= vcpus)        return 'warn';
      return '';
    }
    function pctCls(v, warn, bad) {
      if (v >= bad)  return 'bad';
      if (v >= warn) return 'warn';
      return '';
    }
    var html = '';
    for (var i = 0; i < hosts.length; i++) {
      var h = hosts[i];
      var l1 = Number(h.load1) || 0;
      var l5 = Number(h.load5) || 0;
      var l15 = Number(h.load15) || 0;
      var ucls = pctCls(h.userPct   || 0, 60, 85);
      var scls = pctCls(h.systemPct || 0, 20, 40);
      var icls = pctCls(h.iowaitPct || 0,  5, 15);
      var tcls = pctCls(h.stealPct  || 0,  1,  5);
      var lcls = loadCls(l1, h.vmCount);
      var mhzCur = Number(h.cpuMhz)    || 0;
      var mhzMax = Number(h.cpuMhzMax) || 0;
      var mhzPct = (mhzCur && mhzMax) ? Math.min(100, (mhzCur / mhzMax) * 100) : 0;
      var sockets = Number(h.sockets) || 0;
      var coresPer = Number(h.coresPerSocket) || 0;
      var totalCores = Number(h.totalCores) || (sockets * coresPer);
      var threadsPer = Number(h.threadsPerCore) || 0;
      var totalThreads = Number(h.totalThreads) || (totalCores * (threadsPer || 1));
      // Match Morpheus' native host page, which reports logical CPUs as "Cores".
      // Show physical cores AND threads: threads = the real vCPU-scheduling capacity.
      var cpuTopo = (sockets > 0 && coresPer > 0)
        ? (sockets + ' socket' + (sockets === 1 ? '' : 's') + ' \u00b7 ' + totalCores + ' cores / ' + totalThreads + ' threads')
        : '';
      html += '<div class="kvm-hcard">' +
        '<div class="hname">' +
          '<span>' + esc(h.hostName || '(unnamed)') + '</span>' +
          '<span class="vmcount">' + (h.vmCount || 0) + ' VM' + ((h.vmCount === 1) ? '' : 's') + '</span>' +
        '</div>' +
        '<div class="row">' +
          '<span class="lbl">Load avg</span>' +
          '<span class="loadpill">' +
            '<span class="v ' + lcls + '">' + l1.toFixed(2) + '</span>' +
            '<span class="s">/</span>' +
            '<span class="v">' + l5.toFixed(2) + '</span>' +
            '<span class="s">/</span>' +
            '<span class="v">' + l15.toFixed(2) + '</span>' +
          '</span>' +
        '</div>' +
        (cpuTopo
          ? ('<div class="row"><span class="lbl">CPU</span><span class="val">' + cpuTopo + '</span></div>')
          : '') +
        '<div class="divider"></div>' +
        '<div class="splitrow">' +
          '<div class="stat"><div class="lbl">User</div><div class="val ' + ucls + '">' + num(h.userPct, 1) + '%</div></div>' +
          '<div class="stat"><div class="lbl">System</div><div class="val ' + scls + '">' + num(h.systemPct, 1) + '%</div></div>' +
          '<div class="stat"><div class="lbl">IO Wait</div><div class="val ' + icls + '">' + num(h.iowaitPct, 1) + '%</div></div>' +
          '<div class="stat"><div class="lbl">Steal</div><div class="val ' + tcls + '">' + num(h.stealPct, 1) + '%</div></div>' +
        '</div>' +
        // CPU freq: show "cur / max MHz" + utilization bar only when a TRUE core
        // max is known (per-CPU cpufreq, present on e.g. ProDesks). Hosts without
        // it (e.g. HPE MicroServer Gen10/11 — no per-CPU cpufreq, no lscpu "CPU max
        // MHz") report max as 0; we then show current MHz alone, no bar, never "/ 0".
        (mhzMax > 0
          ? ('<div class="divider"></div>' +
             '<div class="row">' +
               '<span class="lbl">CPU freq</span>' +
               '<span class="val">' + Math.round(mhzCur) + ' / ' + Math.round(mhzMax) + ' MHz</span>' +
             '</div>' +
             '<div class="mhzbar"><div class="mhzfill" style="width:' + mhzPct.toFixed(0) + '%"></div></div>')
          : (mhzCur > 0
              ? ('<div class="divider"></div><div class="row"><span class="lbl">CPU freq</span><span class="val">' + Math.round(mhzCur) + ' MHz</span></div>')
              : '')) +
      '</div>';
    }
    \$hostgrid.innerHTML = html;
  }

  function fetchVms() {
    return fetch(BASE + '/api/vms', { credentials: 'same-origin' })
      .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
      .then(function (data) {
        state.vms = Array.isArray(data) ? data : ((data && data.vms) || []);
        state.lastFetch = new Date();
        renderCards(state.vms);
        renderTable();
        setStatus('live', 'Live · updated ' + relTime(state.lastFetch));
      })
      .catch(function (err) {
        setStatus('err', 'Error: ' + err.message);
        if (!state.vms.length) {
          \$rows.innerHTML = '<tr><td colspan="8"><div class="kvm-empty">' +
            '<div class="big">Could not load data</div>' +
            '<div class="small">' + esc(err.message) + '</div></div></td></tr>';
        }
      });
  }

  function fetchHostStats() {
    return fetch(BASE + '/api/hostStats', { credentials: 'same-origin' })
      .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
      .then(function (data) {
        state.hosts = Array.isArray(data) ? data : ((data && data.hosts) || []);
        renderHostCards();
      })
      .catch(function (err) {
        if (\$hostgrid) {
          \$hostgrid.innerHTML = '<div class="kvm-hcard empty">Host metrics unavailable: ' + esc(err.message) + '</div>';
        }
      });
  }

  function fetchAll() { return Promise.all([fetchVms(), fetchHostStats()]); }

  function collectNow() {
    if (state.refreshing) return;
    state.refreshing = true;
    \$collect.disabled = true;
    \$collect.textContent = 'Collecting…';
    setStatus('', 'collecting…');
    fetch(BASE + '/collectNow', { credentials: 'same-origin' })
      .then(function (r) { return r.json().catch(function () { return {}; }); })
      .then(function () { return fetchAll(); })
      .catch(function (err) { setStatus('err', 'Collect failed: ' + err.message); })
      .then(function () {
        state.refreshing = false;
        \$collect.disabled = false;
        \$collect.textContent = 'Collect Now';
      });
  }

  \$search.addEventListener('input', function (e) { state.query = e.target.value; renderTable(); });
  \$sort.addEventListener('change',  function (e) { state.sort  = e.target.value; renderTable(); });
  \$collect.addEventListener('click', collectNow);

  // Track OS-theme changes — only re-apply if user hasn't manually overridden.
  if (window.matchMedia) {
    var mql = window.matchMedia('(prefers-color-scheme: light)');
    var listener = function (e) {
      var saved = null;
      try { saved = localStorage.getItem('kvm-theme'); } catch (err) {}
      if (saved === 'light' || saved === 'dark') return;
      document.documentElement.setAttribute('data-theme', e.matches ? 'light' : 'dark');
    };
    if (mql.addEventListener) mql.addEventListener('change', listener);
    else if (mql.addListener) mql.addListener(listener);
  }

  fetchAll();
  setInterval(function () {
    if (!document.hidden && !state.refreshing) fetchAll();
  }, REFRESH_MS);
  setInterval(function () {
    if (state.lastFetch && !\$status.classList.contains('err') && !state.refreshing) {
      setStatus('live', 'Live · updated ' + relTime(state.lastFetch));
    }
  }, 5000);
})();
</script>
</body>
</html>
""".toString()
    }
}
