# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [2.7.1] — 2026-09

### Fixed

- **Widget reconciliation no longer runs on the collector's first pass.** That
  pass fires 5 seconds after start. On a plugin upload the dashboard sync runs
  about 200 ms after startup, so there was ample margin, but during a full
  appliance boot every plugin loads at once and the sync can take longer than
  5 seconds. Reconciling to `off` in that window would remove the dashboard
  providers before the sync wrote its rows, so on a fresh install the rows
  would never be created — and ticking the box later would register the
  providers with nothing to render. Reconciliation now starts on the second
  pass, one full collection interval after start, and the skip is logged at
  INFO.

### Changed

- **Corrected the stated reason for the widget defaulting to off.** It is off
  so that upgrading installs see no change in behavior. The Morpheus 9.0
  dashboard 404 was closed as a non-bug during 2.6.0 testing — deleting the
  plugin and rebooting the appliance cleared it, and it never recurred — so it
  is not a reason to keep the widget off. The decision itself is unchanged;
  only the justification was stale.

---

## [2.7.0] — 2026-09

The dashboard widget setting now applies without a plugin restart.

### Added

- **Live dashboard widget toggle.** Verified on Morpheus 9.0.2: Morpheus
  resolves dashboard providers live from the plugin's `pluginProviders` map,
  so adding or removing them takes effect on the next dashboard load with no
  restart. The collector re-reads `dashboardWidgetEnabled` on every collection
  pass and registers or unregisters `dashboard-item-kvm-monitor` and
  `kvm-monitor-dashboard` only when the live state differs from the setting.
  Each change is logged at INFO. A partially-registered state (one of the two
  present) counts as out of sync in both directions and is corrected.
- `setDashboardProvidersEnabled(boolean)` and `dashboardProviderPresence()`
  on `KvmMonitorPlugin`. `Plugin.pluginProviders` is protected, so these
  bridge methods are the only way to reach it.

### Changed

- **Both dashboard providers are now always registered during
  `initialize()`**, so the dashboard sync creates its rows on every startup.
  The setting no longer gates registration at load time; the collector
  reconciles to the desired state within one interval.
- **All `pluginProviders` mutations are copy-on-write.** Each change builds a
  new `LinkedHashMap` and assigns it to the field in one step; the live map is
  never modified in place. `Plugin.getProviders()` iterates `keySet()` while
  rendering, so an in-place edit could be observed half-applied.
- **Setting relabelled** to `Show Dashboard Widget (applies within one
  collection interval)` (62 characters, within the 255 limit).

### Notes

- The default remains **off**, so that upgrading installs see no behavior
  change. (An earlier draft of this note also cited the Morpheus 9.0 dashboard
  404; that was closed as a non-bug and is not a reason — see 2.7.1.)
- Reconciliation reads plugin settings once per collection pass (every 60s by
  default), which adds one `getSettings()` call per interval.

---

## [2.6.4] — 2026-09

### Fixed

- **Dashboard link rendered as `□97 Open KVM Dashboard`** on Morpheus
  9.0.2. The 2.6.3 templates styled the arrow with a CSS escape,
  `content: '<backslash>2197'`, but the generator that wrote those templates
  parsed `<backslash>21` as an octal escape and emitted a literal U+0011
  control character followed by `97`. The glyph now lives in the markup as
  the HTML entity `&#8599;` inside a `.kvm-dash-arrow` span, which also
  avoids the fact that HTML entities are not interpreted inside a CSS
  `content:` property. Affected both the host tab and the report.

---

## [2.6.3] — 2026-09

Adds an **Open KVM Dashboard** link to the host tab and the Operations
report, gated by a client-side permission probe.

### Added

- **"Open KVM Dashboard" link** in the KVM Monitor host tab and the KVM
  Monitor report, pointing at `/plugin/kvmMonitor/dashboard`.

  The dashboard route requires `admin-cm:full`, but plugin API 1.3.3 exposes
  no viewer permissions at render time: `ReportProvider` receives no `User`,
  `Account`, or request, and `ServerTabProvider` receives a `User` only in
  `show()`, never in `renderTemplate()`. Rather than guess, the link is
  rendered hidden and revealed from the browser: a nonce-tagged inline script
  fetches `/plugin/kvmMonitor/api/status` — which carries the same
  `admin-cm:full` permission as the dashboard — with same-origin
  credentials, and un-hides the link only on an OK response with a JSON
  content type. Any non-OK status, redirect, non-JSON body, network error, or
  CSP-dropped script leaves the link hidden. It fails closed.

  The nonce comes from `morpheus.getWebRequest().getNonceToken()`, the only
  source available to a provider, and is emitted with a triple-stache after
  being stripped to the base64 charset — handlebars.java escapes `=` to
  `&#x3D;`, which would corrupt a padded nonce.

---

## [2.6.2] — 2026-09

Settings clarity and packaging correctness. No functional change to
collection, the host tab, or the report.

### Changed

- **Widget setting relabeled** to `Show Dashboard Widget (restart plugin to
  apply)`. Plugin settings are read only in `initialize()`, so ticking the
  box has no effect until the plugin is restarted — the old label gave no
  hint of that.
- **Corrected the CHECKBOX comment** on `parseBoolean()`. Morpheus 9.x
  stores a CHECKBOX OptionType as a JSON Boolean `true`/`false`; the comment
  claimed it round-trips as `'on'`/`'off'` strings. The parser already
  accepted both, so behavior is unchanged — only the stated assumption was
  wrong.
- **`plugin.properties` now carries the real version** (2.6.2). It had read
  `1.0.0` since the first commit. The packaged `META-INF/MANIFEST.MF` was
  always correct, so this is a source-of-truth fix, not a deployment fix.

---

## [2.6.1] — 2026-09

Diagnostics-only release to pin down the dashboard widget not appearing /
not rendering on Morpheus 9.x. No behavior change when everything is healthy.

### Changed

- **Settings-load failures are now logged at WARN** (previously DEBUG). If
  `morpheus.getSettings()` fails during `initialize()`, every setting falls
  back to its default — including **Show Dashboard Widget = off** — so the
  widget was silently never registered even with the box ticked.
- **Startup logs the raw widget setting**: the parsed settings keys, the raw
  `dashboardWidgetEnabled` value and its type, and the resulting on/off
  decision, alongside the existing registered / NOT registered line.
- **Widget data fetches check HTTP status and content type** before parsing.
  A 403, 404, or login-redirect HTML page now shows as e.g.
  `/plugin/kvmMonitor/api/vms -> HTTP 403` in the widget and browser console,
  instead of `SyntaxError: Unexpected token <`.

---

## [2.6.0] — 2025-06

The Morpheus 9.0 release. KVM Monitor loads cleanly on 9.0 + OpenJDK 25 + the
new minimal-JRE classloader, but the upgrade exposed two issues that this
release addresses.

### Added

- **Opt-in dashboard widget toggle.** A new plugin setting,
  **Show Dashboard Widget**, controls whether the dashboard providers
  (`KvmMonitorDashboardProvider` and `KvmMonitorDashboardItemProvider`) are
  registered with Morpheus on plugin start.
  - **Default: OFF.** During Morpheus 9.0 testing we hit a dashboard 404
    reproduction that was triggered by specific cluster-creation events;
    manual plugin removal plus an appliance reboot recovered it. Until that
    is root-caused upstream, the safer default for new installs is to leave
    the dashboard widget off and rely on the host detail tab + Operations
    report, both of which continue to register unconditionally.
  - **To enable:** Administration → Integrations → Plugins → KVM Monitor
    (edit), check **Show Dashboard Widget**, save, and **restart the plugin**.
    Settings are read only during `initialize()`.

### Fixed

- **`kvm-monitor-collector` thread leak on plugin teardown** (Morpheus 9.0's
  Tomcat now logs a `clearReferencesThreads` warning for it). The collector
  now has a proper `shutdown()` sequence — graceful `awaitTermination(5s)`,
  forced `shutdownNow()` on timeout, with timing logged. `KvmMetricStore`
  gains a `close()` that releases the datasource reference and deregisters
  the bundled SQLite JDBC driver registered by this plugin's classloader
  (leaving Morpheus's own MySQL driver untouched). `KvmMonitorPlugin.onDestroy()`
  now calls both in sequence with timing.

### Changed

- `KvmCollectorService.stop()` is now a thin alias for `shutdown()`, kept
  for back-compat with any external callers.

### Known issues — Morpheus 9.0

- **CPU Ready % and Steal % may report 0.0** on some hosts after upgrading
  to 9.0 while CPU Used % continues to populate correctly. Diagnosis is
  open; we have not yet determined whether this is a collection-path
  regression (libvirt no longer emitting `vcpu.N.delay` / `vcpu.N.wait` in
  `domstats` output for affected hosts) or a display-path issue in the
  store's delta math. If you see this, please file an issue with the output
  of the diagnostic inspector against your `kvm-monitor.db`.

  **Resolved — not a bug. No issue needs filing.** Investigated after 2.6.0
  and closed on both counts:

  - **Ready %** was correct all along. Lightly loaded VMs genuinely sit
    around 0.003–0.02%, which rounds to `0.0` at one decimal place. The host
    tab now formats Ready / Used / Steal to two decimals, so a small non-zero
    value is distinguishable from a true zero.
  - **Steal %** is zero because libvirt on the affected hosts does not
    populate `vcpu.N.wait` in `domstats` output. Note that a constant zero
    can mean the counter is unavailable rather than that there is no
    contention — to check for real steal, look at `%st` in `top` from inside
    a guest.
- **Recurring `MissingMethodException` on `com.morpheus.compute.KvmComputeUtility._()`**
  in `morpheus-ui.log` is **not** a KVM Monitor bug — it is an upstream
  HPE Morpheus issue triggered when a storage pool has a broken mount
  (e.g. an I/O error on a `/mnt/...` path the compute service tries to
  enumerate). File with HPE separately if you encounter it.

### Migration notes — upgrading from 2.5.0 with the dashboard already installed

The new toggle gates whether the dashboard providers *register* on plugin
load. If you previously had the widget installed under
Administration → Settings → Dashboards to Display, the rows are already in
the Morpheus database. After upgrading and leaving the new toggle at its
default (off), the providers will not re-register, but the existing
`view_dashboard` and `view_dashboard_item` rows persist until cleaned up.

There is no plugin-side cleanup call in this release (the plugin API
surface for dashboard removal was not verified). If you want the rows gone,
remove them at the Morpheus database level:

```sql
-- Remove the dashboard items, then the dashboard, then the item type.
DELETE FROM view_dashboard_item
  WHERE dashboard_id IN
    (SELECT id FROM view_dashboard WHERE code = 'kvm-monitor-dashboard');

DELETE FROM view_dashboard WHERE code = 'kvm-monitor-dashboard';

DELETE FROM dashboard_item_type WHERE code = 'dashboard-item-kvm-monitor';
```

Reversible alternative: flip `enabled` to `0` on the matching rows instead
of deleting them.

---

## [2.5.0] and earlier

See the GitHub Releases page for the per-version history of prior releases,
covering host detail tab, Operations report, standalone dashboard, disk/net
I/O, host CPU breakdown, topology fields, and the initial dashboard widget.

[2.6.0]: ../../releases/tag/v2.6.0
