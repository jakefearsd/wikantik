---
cluster: distributed-systems
canonical_id: 01KQ0P44NAWVZHSMT53N7KE319
title: Clock Synchronization
type: article
tags:
- clock-synchronization
- distributed-systems
- ntp
- ptp
- observability
summary: Technical analysis of clock drift, offset estimation (NTP), and precision synchronization (PTP) in distributed clusters.
auto-generated: false
---

In distributed systems, physical clocks on independent nodes deviate due to thermal variations and crystal imperfections. Clock synchronization minimizes **skew** (instantaneous difference) by correcting for **drift** (deviation rate).

## Clock Offset and Delay Estimation (NTP)

The Network Time Protocol (NTP) estimates the offset between a client ($C$) and a server ($S$) using four timestamps:
-$T_1$: Client sends request.
-$T_2$: Server receives request.
-$T_3$: Server sends response.
-$T_4$: Client receives response.

### The Math
**Round-trip delay ($\delta$):**

$$
\delta = (T_4 - T_1) - (T_3 - T_2)
$$

**Clock Offset ($\theta$):**

$$
\theta = \frac{(T_2 - T_1) + (T_3 - T_4)}{2}
$$

This calculation assumes network symmetry ($\text{delay}_{C \to S} \approx \text{delay}_{S \to C}$). Asymmetry is the primary source of error in NTP.## NTP Stratum Hierarchy

Trust is organized into strata:
- **Stratum 0:** Reference clocks (GPS, Atomic clocks).
- **Stratum 1:** Servers directly attached to Stratum 0.
- **Stratum 2:** Synced via network from Stratum 1.

### Correction Mechanisms
1. **Stepping (The Jump):** For large offsets ($>125\text{ms}$), the clock is immediately set. This breaks monotonicity.
2. **Slewing (The Drift):** For small offsets, the kernel subtly speeds up or slows down the clock (typically at$500\text{ppm}$) to converge without jumps.

## Precision Time Protocol (PTP / IEEE 1588)

PTP is required for sub-microsecond precision (e.g., HFT, 5G base stations). It differs from NTP by using **hardware timestamping** at the MAC/PHY layer, bypassing OS kernel jitter.

| Feature | NTP | PTP |
|---|---|---|
| **Precision** |$1\text{ms}$-$50\text{ms}$|$< 1\mu\text{s}$ |
| **Implementation** | Software (UDP) | Hardware (NIC/MAC) |
| **Network** | Variable (Internet) | Low-jitter (LAN/PTP-aware switches) |

## Logical vs. Physical Time

Because physical clocks cannot be perfectly synced, distributed databases use logical time for ordering:
- **[LamportClocks](LamportClocks):** Tracks partial ordering based on causality.
- **[VectorClocks](VectorClocks):** Detects concurrent writes.
- **Hybrid Logical Clocks (HLC):** Combines the monotonicity of logical clocks with the approximate wall-clock time of physical clocks.

## Operational Failure Modes

1. **Leap Second Smearing:** Abruptly adding a second can crash applications. Modern providers (Google, AWS) "smear" the extra second over 24 hours.
2. **Virtualization Skew:** Hypervisor context switching can cause massive, unpredictable clock jumps in VMs. Use the hypervisor's specific clock source (e.g., `kvm-clock`).
3. **Partitioned NTP:** If a cluster loses access to external Stratum 1 sources, nodes will drift relative to each other. Use a local Stratum 1 source (GPS receiver) for isolated environments.

## Implementation Checklist
- Run `chronyd` instead of `ntp` for faster convergence.
- Monitor `offset` and `jitter` via `chronyc sources -v`.
- Disable `ntpdate` cronjobs; they cause non-monotonic jumps.
- Use **TrueTime** (Google Spanner approach) if you require external consistency across global regions.
