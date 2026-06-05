/**
 * KVM Monitor dashboard widget.
 *
 * Renders into the Morpheus dashboard grid as a native React widget. Uses the
 * host-provided globals (React, ReactDOM, $, Widget, WidgetHeader, EmptyWidget,
 * LoadingWidget, Morpheus) exactly like the stock dashboard widgets — these are
 * NOT imported; the dashboard page provides them at runtime.
 *
 * Data comes from the existing, already-working controller routes:
 *   GET /plugin/kvmMonitor/api/hostStats  -> { hosts: [...] }
 *   GET /plugin/kvmMonitor/api/vms        -> { vms:   [...] }
 *
 * No new controller is needed — this reuses the JSON API the standalone
 * dashboard already consumes.
 */
class KvmMonitorWidget extends React.Component {

  constructor(props) {
    super(props);
    this.state = {
      loaded: false,
      autoRefresh: props.autoRefresh === false ? false : true,
      hosts: [],
      vms: [],
      error: false,
      errorMessage: null
    };
    this.setData = this.setData.bind(this);
    this.refreshData = this.refreshData.bind(this);
  }

  componentDidMount() {
    this.loadData();
    // hook into Morpheus' global dashboard refresh pulse
    $(document).on('morpheus:refresh', this.refreshData);
  }

  componentWillUnmount() {
    $(document).off('morpheus:refresh', this.refreshData);
  }

  refreshData() {
    if (this.state.autoRefresh === true) {
      this.loadData();
    }
  }

  loadData() {
    Promise.all([
      fetch('/plugin/kvmMonitor/api/hostStats', { credentials: 'same-origin' }).then(r => r.json()),
      fetch('/plugin/kvmMonitor/api/vms',       { credentials: 'same-origin' }).then(r => r.json())
    ])
    .then(([hostResp, vmResp]) => this.setData(hostResp, vmResp))
    .catch(err => this.setState({ loaded: true, error: true, errorMessage: String(err) }));
  }

  setData(hostResp, vmResp) {
    var hosts = (hostResp && hostResp.hosts) ? hostResp.hosts : (Array.isArray(hostResp) ? hostResp : []);
    var vms   = (vmResp   && vmResp.vms)     ? vmResp.vms     : (Array.isArray(vmResp)   ? vmResp   : []);
    this.setState({ loaded: true, error: false, errorMessage: null, hosts: hosts, vms: vms });
  }

  num(v, d) {
    var n = Number(v);
    if (isNaN(n)) return '0';
    return n.toFixed(d == null ? 1 : d);
  }

  readyClass(r) {
    if (r > 10) return 'text-danger';
    if (r >= 5) return 'text-warning';
    return 'text-success';
  }

  render() {
    var isLoaded = this.state.loaded === true;
    var vms   = this.state.vms || [];
    var hosts = this.state.hosts || [];

    // sort VMs by ready% desc, take a reasonable number for a widget
    var rows = vms.slice().sort((a, b) => (Number(b.readyPct) || 0) - (Number(a.readyPct) || 0));

    var showTable = isLoaded && rows.length > 0;
    var totalVms = vms.length;
    var impacting = vms.filter(v => (Number(v.readyPct) || 0) > 10).length;

    return (
      <Widget>
        <WidgetHeader title={'KVM Monitor — ' + totalVms + ' VMs, ' + hosts.length + ' hosts'}
                      link="/plugin/kvmMonitor/dashboard"/>
        <div className="dashboard-widget-content">
          {this.state.error ? (
            <div className="text-danger" style={{padding:'12px'}}>
              Could not load KVM data: {this.state.errorMessage}
            </div>
          ) : null}

          {impacting > 0 ? (
            <div style={{padding:'4px 12px'}} className="text-danger">
              {impacting} VM{impacting === 1 ? '' : 's'} with CPU Ready &gt; 10%
            </div>
          ) : null}

          <div className={'kvm-table-scroll' + (showTable ? '' : ' hidden')}
               style={{maxHeight:'360px', overflowY:'auto', overflowX:'hidden'}}>
            <table className="widget-table">
              <thead>
                <tr>
                  <th style={{position:'sticky', top:0}}>VM</th>
                  <th style={{position:'sticky', top:0}}>Host</th>
                  <th className="text-right" style={{position:'sticky', top:0}}>vCPU</th>
                  <th className="text-right" style={{position:'sticky', top:0}}>Ready %</th>
                  <th className="text-right" style={{position:'sticky', top:0}}>Used %</th>
                  <th className="text-right" style={{position:'sticky', top:0}}>Steal %</th>
                </tr>
              </thead>
              <tbody>
                { rows.map((vm, i) => {
                    var r = Number(vm.readyPct) || 0;
                    return (
                      <tr key={(vm.vmName || 'vm') + '-' + i}>
                        <td className="col-md nowrap">{vm.vmName || ''}</td>
                        <td className="col-md nowrap">{vm.hostName || '—'}</td>
                        <td className="col-md nowrap text-right">{vm.vcpuCount || 0}</td>
                        <td className={'col-md nowrap text-right ' + this.readyClass(r)}>{this.num(r)}%</td>
                        <td className="col-md nowrap text-right">{this.num(vm.usedPct)}%</td>
                        <td className="col-md nowrap text-right">{this.num(vm.stealPct)}%</td>
                      </tr>
                    );
                })}
              </tbody>
            </table>
          </div>

          <EmptyWidget isEmpty={isLoaded && !showTable}/>
          <LoadingWidget isLoading={!isLoaded}/>
        </div>
      </Widget>
    );
  }
}

//register with the Morpheus component registry
Morpheus.components.register('kvm-monitor-widget', KvmMonitorWidget);

$(document).ready(function () {
  var mount = document.querySelector('#kvm-monitor-widget');
  if (mount) {
    const root = ReactDOM.createRoot(mount);
    root.render(<KvmMonitorWidget/>);
  }
});
