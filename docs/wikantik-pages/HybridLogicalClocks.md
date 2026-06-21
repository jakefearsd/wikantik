---
status: active
date: '2026-05-10'
summary: A sophisticated clock pattern that combines physical wall time with logical
  counters to provide causality-tracking timestamps without specialized hardware.
tags:
- distributed-systems
- causality
- consistency
- time
- cockroachdb
type: article
relations:
- type: component_of
  target_id: 01KQEKGD9XWDSFGH7TWHH63NZT
- type: related_to
  target_id: 01KS7V3M3QYAS6P09AM61S5E2Z
- type: alternative_to
  target_id: TrueTime
cluster: distributed-systems
canonical_id: 01KS7W4N4R838D4EYVWFA9F36D
title: Hybrid Logical Clocks (HLC)
---

# Hybrid Logical Clocks (HLC)

In 2026, **Hybrid Logical Clocks (HLC)** have become the standard for providing monotonic, causality-tracking timestamps in cloud-native distributed databases. HLC solves the primary drawback of physical clocks (drift and skew) and Lamport clocks (lack of correlation with real-world time) by combining both into a single 96-bit or 128-bit timestamp.

## 1. The Core Motivation

*   **Physical Clocks:** NTP can keep clocks within 50-200ms, but they are not monotonic; they can jump backward or stall.
*   **Lamport Clocks:** Provide causality but no relationship to "wall time" (seconds/minutes), which is required for user-facing features like "Snapshot Reads" or "Transaction Expiration."
*   **HLC Solution:** Provide a timestamp $T$ such that $T$ is always close to physical time, but also satisfies the causality property: if $a \to b$, then $T(a) < T(b)$.

## 2. Timestamp Structure

A typical HLC timestamp (as implemented in **CockroachDB**) consists of:
1.  **WallTime (64-bit):** Physical time in nanoseconds since the Unix epoch.
2.  **Logical (32-bit):** A counter used to order events that occur within the same nanosecond or during physical clock stalls.

## 3. The Update Algorithm

Every node maintains its local HLC state.

### Local Event
If node $i$ performs a local action at system time $S_i$:
*   `HLC.WallTime = max(HLC.WallTime, S_i)`
*   If $S_i > HLC.WallTime$: `HLC.Logical = 0`
*   Else: `HLC.Logical++`

### Message Receipt
When a node receives a message with timestamp $T_{msg}$:
*   `HLC.WallTime = max(HLC.WallTime, T_{msg}.WallTime, S_i)`
*   The `Logical` counter is incremented to ensure the new local timestamp is strictly greater than both the previous state and the incoming message.

## 4. Handling Clock Uncertainty

HLC does not eliminate clock skew; it requires an upper bound on how much nodes can drift (the **Max Offset**, typically 500ms).

*   **Read-Retry:** If a transaction at time $T$ attempts to read data and finds a value in the "Uncertainty Window" $[T, T + MaxOffset]$, it cannot be sure if that write happened before or after it.
*   **Action:** The database forces the transaction to **Restart** with a higher timestamp, ensuring it sees a consistent snapshot. This is the software-only alternative to the hardware-based "Commit-Wait" used in Google Spanner.

## 5. Comparison: HLC vs. TrueTime

| Feature | Hybrid Logical Clock (HLC) | Google TrueTime |
| :--- | :--- | :--- |
| **Hardware** | Commodity (NTP-based) | GPS + Atomic Clocks |
| **Uncertainty** | Large (~500ms) | Tight (<7ms) |
| **Strategy** | **Read-Retry** (Restart reads) | **Commit-Wait** (Delay writes) |
| **Availability** | Multi-Cloud / Portable | Google Cloud Only |

## See Also
*   [Distributed Systems Hub](DistributedSystemsHub) — Pattern index.
*   [Lamport and Vector Clocks](LamportAndVectorClocks) — Foundations of logical time.
*   [Consistency Models](ConsistencyModels) — How HLC enables Snapshot Isolation.
