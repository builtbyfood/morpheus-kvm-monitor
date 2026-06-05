(function () {
  'use strict';

  var BASE = '/plugin/kvmMonitor';

  function cls(p) { return p >= 10 ? 'crit' : (p >= 5 ? 'warn' : ''); }

  function load() {
    fetch(BASE + '/api/status')
      .then(function (r) { return r.json(); })
      .then(function (s) {
        document.getElementById('kvm-status').textContent =
          s.sampleCount + ' samples · ' + s.hosts + ' host(s)';
      })
      .catch(function () {
        document.getElementById('kvm-status').textContent = 'status unavailable';
      });

    fetch(BASE + '/api/vms')
      .then(function (r) { return r.json(); })
      .then(function (data) {
        var rows = document.getElementById('kvm-rows');
        var vms = (data && data.vms) || [];
        if (!vms.length) {
          rows.innerHTML = '<tr><td colspan="6">No data collected yet.</td></tr>';
          return;
        }
        rows.innerHTML = vms.map(function (vm) {
          var ready = vm.readyPct || 0;
          var width = Math.min(Math.round(ready * 4), 120);
          return '<tr>' +
            '<td>' + esc(vm.vmName) + '</td>' +
            '<td>' + esc(vm.hostName || '') + '</td>' +
            '<td>' + (vm.vcpuCount || 0) + '</td>' +
            '<td><span class="kvm-bar ' + cls(ready) + '" style="width:' + width + 'px"></span> ' + ready + '%</td>' +
            '<td>' + (vm.usedPct || 0) + '%</td>' +
            '<td>' + (vm.stealPct || 0) + '%</td>' +
            '</tr>';
        }).join('');
      })
      .catch(function () {
        document.getElementById('kvm-rows').innerHTML =
          '<tr><td colspan="6">Failed to load metrics.</td></tr>';
      });
  }

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  document.addEventListener('DOMContentLoaded', function () {
    var btn = document.getElementById('kvm-collect');
    if (btn) {
      btn.addEventListener('click', function () {
        btn.disabled = true;
        btn.textContent = 'Collecting…';
        fetch(BASE + '/collectNow')
          .then(function (r) { return r.json(); })
          .then(function () { load(); })
          .finally(function () {
            btn.disabled = false;
            btn.textContent = 'Collect Now';
          });
      });
    }
    load();
    setInterval(load, 30000);
  });
})();
