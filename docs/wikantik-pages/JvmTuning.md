---
cluster: devops-sre
canonical_id: 01KQ0P44RFJV22SJFX5W0T457V
title: JVM Tuning
type: article
tags:
- java
- jvm
- garbage-collection
- performance
- memory-management
status: active
date: 2025-05-15
summary: Technical guide to JVM performance tuning and Garbage Collection (GC) optimization. Covers G1GC, ZGC, and heap sizing strategies.
auto-generated: false
---

# JVM Tuning: Memory and GC Optimization

The Java Virtual Machine (JVM) provides high-level memory management via Garbage Collection (GC), which must be tuned to balance **Throughput** vs. **Latency**.

## 1. Garbage Collector Selection

| Collector | Target | Use Case |
| :--- | :--- | :--- |
| **G1GC** | Mixed | Default for most apps; handles heaps 4GB - 64GB well. |
| **ZGC** | Low Latency | Ultra-low pauses (<1ms) even on TB-sized heaps. |
| **ParallelGC** | Throughput | Batch jobs where pause time doesn't matter, only total speed. |

## 2. Heap Sizing and Memory Areas

*   **Heap (`-Xms`, `-Xmx`):** The main object area. **Expert Rule:** Set `-Xms` and `-Xmx` to the same value to avoid the performance jitter caused by JVM heap resizing.
*   **Young Gen (`-Xmn`):** Where new objects are born. High allocation rates require a larger Young Gen to prevent premature promotion to the Old Gen.
*   **Metaspace (`-XX:MaxMetaspaceSize`):** Stores class metadata. If your app dynamically loads many classes, increase this to avoid `OutOfMemoryError: Metaspace`.

## 3. Concrete Tuning Scenarios

### Scenario A: Low Latency API (Java 17+)
Use **ZGC**. It uses colored pointers and load barriers to perform compaction concurrently with application threads.
*   **Flags:** `-XX:+UseZGC -Xmx16G -XX:SoftMaxHeapSize=12G`

### Scenario B: Standard Web App
Use **G1GC**. Focus on the pause time goal.
*   **Flags:** `-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:InitiatingHeapOccupancyPercent=45`
*   **Logic:** G1 will automatically adjust Young/Old gen ratios to try and meet that 200ms goal.

## 4. Diagnostics: The GC Log

You cannot tune what you cannot see.
*   **Enable Logging:** `-Xlog:gc*:file=gc.log:time,uptime,level,tags`
*   **Analysis:** Use tools like **GCEasy.io** or **JDK Mission Control** to find "Stop-The-World" (STW) pauses. Look for "Full GC" events; a Full GC in G1 or ZGC indicates the collector is being overwhelmed and the heap is too small or allocation is too fast.

---
**See Also:**
- [Capacity Planning](CapacityPlanning) — Sizing the underlying VM/Container.
- [Auto Scaling Strategies](AutoScalingStrategies) — Handling heap-pressure induced slow-downs.
- [Dependency Injection Patterns](DependencyInjectionPatterns) — Managing object lifecycles.
