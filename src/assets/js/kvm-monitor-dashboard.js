/**
 * KVM Monitor dashboard-level script.
 *
 * Required only because Morpheus' dashboard sync calls scriptPathForPlugin()
 * and NPEs on a null scriptPath. The actual widget rendering is done by the
 * item-type's compiled React widget (kvm-monitor-widget.js), so this is a
 * deliberate no-op placeholder.
 */
(function () {
  // intentionally empty — widget mounts via its own scriptPath
})();
