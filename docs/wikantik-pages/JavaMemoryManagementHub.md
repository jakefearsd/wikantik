---
status: active
date: '2026-04-26'
summary: Index of JVM memory management — heap layout, garbage collectors, off-heap
  allocation, and the tuning that actually matters for production Java applications.
tags:
- java
- memory
- jvm
- garbage-collection
- hub
type: hub
canonical_id: 01KQ0P44RANZ15BBDJFRH6BDPS
cluster: java
related:
- JavaHub
- JavaCollectionsFramework
- JavaTwentyOneFeatures
title: Java Memory Management
---
# JavaMemoryManagement Hub

This sub-cluster within the broader Java cluster covers JVM memory management — heap structure, garbage collection algorithms, off-heap memory, and the tuning that production Java applications actually benefit from.

The honest framing: most Java applications do not need GC tuning. The default collector (G1 in modern JVMs, ZGC for low-latency cases) handles the vast majority of workloads. Tuning is for specific cases where measured behavior justifies it.

## Memory model

- [JavaMemoryManagement](JavaMemoryManagement) — Heap, stack, metaspace, the layout JVMs actually use
- [MemoryManagementFundamentals](MemoryManagementFundamentals) — General memory management concepts that apply across languages

## Garbage collection

- [JvmTuning](JvmTuning) — Practical GC tuning for production workloads — when it pays, what to measure, the parameters that actually matter

## Adjacent

- [Java Hub](JavaHub) — Parent cluster
- [JavaCollectionsFramework](JavaCollectionsFramework) — Memory characteristics of common collections
- [JavaTwentyOneFeatures](JavaTwentyOneFeatures) — Generational ZGC and other memory-related additions
