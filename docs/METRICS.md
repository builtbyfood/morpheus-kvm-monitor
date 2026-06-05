# KVM Monitor — Metrics Reference

This document explains every metric KVM Monitor collects, how it is derived from
`virsh domstats`, and the threshold guidance shown in the UI tooltips. All
percentages are normalized **per vCPU** unless noted.

---

## How collection works

A background thread (`KvmCollectorService`) samples each KVM host on a fixed
interval (default 60s). For each host it runs:

```
virsh list --name                  # enumerate running domains (VMs)
virsh domstats --vcpu <domain>     # per-vCPU scheduling counters
```

`virsh domstats` returns **cumulative counters** (nanoseconds, bytes) that only
increase. KVM Monitor stores each raw sample in SQLite, then derives rates and
percentages from the **delta** between two consecutive samples. This is why the
first reading after install may show zeros until a second sample exists.

---

## CPU Ready (`Ready %`)

**The headline metric — the KVM analog of VMware's CPU Ready.**

- **Source:** sum of `vcpu.N.delay` across all vCPUs of the domain.
- **What `vcpu.N.delay` is:** nanoseconds the vCPU was **runnable but not
  scheduled** — i.e. it had work to do but was waiting for a physical CPU to
  become available. This is exactly the condition VMware reports as CPU Ready.
- **Derivation:** `Ready % = Δ(delay) / Δ(wallclock) / vcpuCount × 100`, taken
  between two collection cycles and normalized per vCPU.

**Threshold guidance (used in tooltips):**

| Ready % | Meaning |
|---|---|
| 0–5% | Healthy. Normal scheduling latency. |
| 5–10% | Contention. vCPUs are starting to wait for physical cores; investigate host overcommit. |
| > 10% | Impacting. Guests are likely experiencing perceptible CPU stalls. Reduce vCPU oversubscription or rebalance VMs. |

The dashboard widget and standalone dashboard color Ready % green / amber / red
on these bands, and the "Impacting" count at the top of the widget tallies VMs
over 10%.

---

## CPU Used (`Used %`)

- **Source:** sum of `vcpu.N.time` across all vCPUs.
- **What it is:** nanoseconds of physical CPU actually consumed by the domain's
  vCPUs.
- **Derivation:** `Used % = Δ(time) / Δ(wallclock) / vcpuCount × 100`.
- **Reading it:** sustained values near 100% mean the VM is genuinely CPU-bound.
  High Used % with **low** Ready % is healthy saturation (the VM is getting the
  CPU it asks for). High Used % with **high** Ready % means the VM wants more CPU
  than the host can deliver.

---

## CPU Steal (`Steal %`)

- **Source:** host-level steal counters.
- **What it is:** the percentage of time the host's CPUs were unavailable to a
  guest because they were servicing other guests or the hypervisor under
  overcommit.
- **Reading it:** any sustained steal under load is a sign the host is
  oversubscribed. Steal and Ready % often rise together.

---

## vCPU count (`vCPU`)

- **Source:** `vcpu.current` (falling back to `vcpu.maximum`).
- The number of virtual CPUs currently allocated to the VM. Used to normalize the
  percentages above to a per-vCPU basis so a 1-vCPU and an 8-vCPU VM are
  comparable.

---

## Disk I/O (`Disk R/W`)

- **Source:** sum of `block.N.rd.bytes` and `block.N.wr.bytes` across the domain's
  block devices.
- **Derivation:** rate = `Δ(bytes) / Δ(wallclock)`, displayed as throughput.
- Read and write are tracked separately.

---

## Network I/O (`Net R/T`)

- **Source:** sum of `net.N.rx.bytes` and `net.N.tx.bytes` across the domain's
  interfaces.
- **Derivation:** rate = `Δ(bytes) / Δ(wallclock)`, displayed as throughput.
- Receive (rx) and transmit (tx) are tracked separately.

---

## Per-host overview metrics

Shown on the host overview cards (standalone dashboard and host tab):

- **Load avg** — the host's 1 / 5 / 15-minute load averages.
- **CPU breakdown** — user / system / iowait / steal percentages for the host as
  a whole.
- **MHz ratio** — consumed vs. available CPU frequency, shown as a utilization
  bar.
- **CPU topology** — physical sockets × cores per socket (and the resulting total
  physical core count), read from `lscpu`. Useful for gauging oversubscription:
  compare total physical cores against the sum of vCPUs assigned to the host's VMs.

---

## Notes on accuracy

- **First-sample zeros:** rates need two samples; expect zeros for one interval
  after install or after "Collect Now".
- **Counter resets:** if a VM restarts, its cumulative counters reset; KVM
  Monitor guards against negative deltas so a reset shows as zero for that
  interval rather than a spike.
- **Per-vCPU normalization:** all CPU percentages are divided by vCPU count, so
  values are directly comparable across differently-sized VMs.
