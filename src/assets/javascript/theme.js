// KVM Monitor - Theme toggle
// Loaded as a separate <script src> to avoid CSP inline restrictions
// Applies saved theme immediately on load to prevent flash of wrong theme

(function () {
  var STORAGE_KEY = 'kvm-monitor-theme';
  var ICONS       = { dark: '\u263D', light: '\u2600' }; // ☽ ☀

  function applyTheme(theme) {
    if (theme === 'light') {
      document.body.classList.add('light');
    } else {
      document.body.classList.remove('light');
    }
    var btn = document.getElementById('kvm-theme-btn');
    if (btn) btn.textContent = theme === 'light' ? ICONS.light : ICONS.dark;
  }

  function getTheme() {
    try { return localStorage.getItem(STORAGE_KEY) || 'dark'; } catch (e) { return 'dark'; }
  }

  function saveTheme(theme) {
    try { localStorage.setItem(STORAGE_KEY, theme); } catch (e) {}
  }

  // Apply immediately on script load — prevents flash of dark on light-pref users
  applyTheme(getTheme());

  // Wire up button once DOM is ready
  function wireButton() {
    var btn = document.getElementById('kvm-theme-btn');
    if (!btn) return;
    // Remove any previous listener clone
    var fresh = btn.cloneNode(true);
    btn.parentNode.replaceChild(fresh, btn);
    fresh.addEventListener('click', function () {
      var current = getTheme();
      var next    = current === 'dark' ? 'light' : 'dark';
      saveTheme(next);
      applyTheme(next);
    });
    applyTheme(getTheme()); // re-apply to set icon on fresh clone
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', wireButton);
  } else {
    wireButton();
  }
})();
