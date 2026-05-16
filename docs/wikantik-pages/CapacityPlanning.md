---
cluster: cloud-platforms
canonical_id: 01KQ0P44MYFJX1RPYABF70A080
title: Capacity Planning
type: article
tags:
- cloud
- engineering
- capacity-planning
- infrastructure
- performance
status: active
date: 2025-05-15
summary: Technical guide to infrastructure capacity planning. Covers CPU/Memory requests, IOPS calculations, and throughput modeling.
auto-generated: false
---

# Capacity Planning: Systems Sizing

Capacity planning is the process of determining the resources required to meet current and future demand while maintaining performance SLAs and minimizing cost.

## 1. Resource Modeling: Compute

In Kubernetes and cloud environments, sizing starts with **Requests** (guaranteed) and **Limits** (throttled).
*   **CPU:** Measured in millicores (m). 1000m = 1 vCPU.
*   **Memory:** Measured in bytes/MiB. Memory is non-compressible; if a process exceeds its limit, it is OOM-Killed (Out of Memory).
*   **Concrete Calculation:** If an application handles 100 req/sec at 50% CPU on 1 vCPU, and the target is 500 req/sec at <70% CPU:
    $$
    \text{Total vCPUs} = \frac{500}{100} \times \frac{0.50}{0.70} \approx 3.6 \text{ vCPUs}
    $$
## 2. Storage: IOPS and Throughput

Storage capacity is not just about GB; it is about I/O performance.
*   **IOPS (Input/Output Operations Per Second):** Critical for databases.
*   **Throughput (MiB/s):** Critical for streaming/logging.
*   **Concrete Spec:** An AWS EBS `gp3` volume provides a baseline of 3,000 IOPS. If your database requires 10,000 IOPS for a 2ms latency SLA, you must provision and pay for the additional 7,000 "Provisioned IOPS."

## 3. Network: Bandwidth and PPS

*   **Bandwidth:** Sized in Gbps. Important for data replication.
*   **PPS (Packets Per Second):** A common bottleneck in high-frequency trading or small-packet microservices. High CPU usage in the kernel (`si` in top) often indicates PPS saturation rather than application load.

## 4. The Planning Lifecycle

1.  **Baseline:** Measure current utilization during a 24-hour cycle.
2.  **Stress Testing:** Use `loadtest` or `locust` to find the **Break Point** (where latency increases non-linearly).
3.  **Buffer:** Add a 20-30% buffer for "Unknown Unknowns" and sudden traffic bursts.
4.  **Forecasting:** Apply growth rates (e.g., "5% user growth per month") to the breaking point to determine the **Exhaustion Date**.

---
**See Also:**
- [Auto Scaling Strategies](AutoScalingStrategies) — Automating capacity adjustments.
- [Cloud Cost Optimization](CloudCostOptimization) — Reducing excess capacity.
- [Jvm Tuning](JvmTuning) — Optimizing resource utilization for Java.
