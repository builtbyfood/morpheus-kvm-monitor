# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
