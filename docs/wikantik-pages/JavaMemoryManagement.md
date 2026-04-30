---
canonical_id: 01KQ0P44RA716VK4MYYAJE3WMS
title: Java Memory Management
type: article
cluster: java
status: active
date: '2026-04-26'
summary: Java memory management — heap regions, garbage collectors, the modern GC
  options (G1, ZGC, Shenandoah), and the practical tuning approaches that matter
  for production JVM applications.
tags:
- java
- memory-management
- gc
- garbage-collection
- jvm
related:
- MemoryArchitectures
- MemoryManagementFundamentals
hubs:
- JavaHub
---
# Java Memory Management

The JVM manages memory automatically through garbage collection. Understanding what's happening — heap regions, GC algorithms, allocation patterns — is essential for diagnosing performance issues and avoiding common pitfalls.

This page covers what every Java developer should know about JVM memory.

## The Java memory model (high level)

### Heap

Where objects live. Subject to garbage collection.

Subdivided by GC algorithm.

### Stack

Per-thread. Local variables and method call frames.

Cleaned up automatically when methods return.

### Metaspace

Class metadata, method bytecode (since Java 8 — replaced PermGen).

Grows as classes load.

### Off-heap

Direct ByteBuffers, native memory. Not GC-managed.

Used for I/O, native libraries, some caches.

## Heap structure (generational)

Most JVM GCs use generational hypothesis: most objects die young.

### Young generation

- Eden space: where new objects are allocated
- Two survivor spaces (S0, S1): hold survivors of minor GC

Minor GC: collects young gen. Fast.

Surviving objects move to other survivor space, then to old gen after enough survivals.

### Old generation (tenured)

Long-lived objects. Larger than young gen typically.

Major GC: collects old gen. Slower.

Full GC: collects entire heap. Slowest; usually a problem if frequent.

## Garbage collectors

### Serial GC

Single-threaded. Simple. For small applications or where simplicity matters.

`-XX:+UseSerialGC`

### Parallel GC

Multi-threaded but stop-the-world. Good throughput; long pauses.

Default before Java 9.

`-XX:+UseParallelGC`

### CMS (Concurrent Mark Sweep)

Mostly concurrent old-gen collection. Lower latency than Parallel.

Removed in Java 14. Don't use for new applications.

### G1 GC

Default since Java 9. Region-based heap; concurrent and parallel phases.

Configurable target pause time:
`-XX:MaxGCPauseMillis=200`

Good general-purpose default.

### ZGC

Sub-millisecond pauses. For very low-latency applications.

Production-ready since Java 15.

`-XX:+UseZGC`

Pays in throughput for latency.

### Shenandoah

Like ZGC: ultra-low pauses. Red Hat-developed.

`-XX:+UseShenandoahGC`

### Epsilon

No-op GC. Useful for benchmarking allocation paths or for very short-lived JVMs that won't GC.

## GC tuning basics

### Heap sizing

`-Xms` initial, `-Xmx` max heap.

For server applications, set `-Xms = -Xmx` to avoid resizing pauses.

Default: 25% of physical memory (varies).

### Generational sizing

For G1 and others, mostly automatic. For older GCs, you can tune young gen ratio.

### Pause time targets

For G1: `-XX:MaxGCPauseMillis=200` (target, not guarantee).

ZGC, Shenandoah: pauses sub-ms by design.

### GC logging

Always enable in production:
`-Xlog:gc*:file=gc.log:time,uptime,level,tags`

Use tools (GCViewer, GCEasy) to analyze.

## Object lifecycle

### Allocation

Most allocations are in Eden. Bump pointer is fast.

For very large objects, may go directly to old gen or "humongous" region in G1.

### TLABs (Thread-Local Allocation Buffers)

Each thread has its own Eden chunk. No locking for allocation.

Fast path is just a pointer increment.

### Promotion

Objects that survive enough minor GCs move to old gen.

Promotion happens during minor GC.

### Reclamation

Objects without references become garbage; GC reclaims memory.

## Reference types

### Strong reference

Normal reference. Object alive while reachable.

### Soft reference

GC may collect when memory is tight. Used for caches.

### Weak reference

Collected at next GC. Used for canonical maps, listener registries.

### Phantom reference

Notification on collection. Used for cleanup.

## Common memory issues

### Memory leaks

Objects retained accidentally:
- Static collections growing
- Listeners not removed
- ThreadLocals not cleaned up
- Cache without eviction

Symptom: heap grows over time; eventually OOM.

Tools: heap dumps, jmap, MAT (Memory Analyzer Tool), VisualVM.

### Excessive allocation

High allocation rate → frequent minor GC → high CPU overhead.

Common causes:
- Boxing in hot loops
- String concatenation in loops
- Excessive intermediate objects

Profiling shows allocation hotspots.

### Long pauses

Full GCs or long old-gen collections.

Causes:
- Heap too small
- Bad GC choice
- Old gen fragmentation
- Concurrent mark fails

### Off-heap leaks

DirectByteBuffer not released. Native libraries.

Doesn't show in heap dump. Harder to diagnose.

## Diagnostic tools

### jcmd

Comprehensive command-line tool. Heap dump, GC info, thread dumps.

`jcmd <pid> GC.heap_dump filename.hprof`

### jstat

Live GC statistics.

`jstat -gc <pid> 1000` shows GC stats every second.

### VisualVM / JMC (Java Mission Control)

GUI tools for live profiling.

JMC's flight recorder is excellent and now open source.

### MAT (Eclipse Memory Analyzer)

Analyze heap dumps. Find memory leaks.

### Async-profiler

Low-overhead sampling profiler. Shows allocation hotspots.

## Production monitoring

Track:
- Heap usage (after GC)
- GC frequency
- GC pause time (especially p99)
- Old-gen growth rate
- Allocation rate

Sudden changes in any of these signal trouble.

## Memory issues by symptom

### "OutOfMemoryError: Java heap space"

Heap full; can't allocate.

Diagnose: heap dump, find dominant retained objects.

### "OutOfMemoryError: GC overhead limit exceeded"

Too much time in GC, too little reclaimed. Heap too small or memory leak.

### "OutOfMemoryError: Direct buffer memory"

Off-heap (Direct buffers) exhausted.

Tune `-XX:MaxDirectMemorySize`.

### "OutOfMemoryError: Metaspace"

Too many classes loaded.

Common with classloader leaks (frameworks reloading apps).

### Long pauses

GC issue. Profile GC.

### High CPU but low throughput

Excessive GC. Tune or reduce allocations.

## Best practices

### Object pooling — usually not

Modern GCs handle short-lived objects well. Pooling is rarely worth complexity.

Exception: very large objects, expensive to create (DB connections, threads).

### Prefer primitives

Avoid boxing in hot paths. Use IntStream over Stream<Integer>.

### Reuse buffers

For I/O paths, byte[] buffers can be reused.

### Avoid String concatenation in loops

Use StringBuilder.

### Watch ThreadLocals

Especially with thread pools — values persist across uses.

### Profile before tuning

Default GC is usually fine. Tune based on measured problems.

## When to switch GC

Default G1 is good for most. Switch to ZGC/Shenandoah if:
- Pause time matters more than throughput
- Heap is large (>32GB)
- p99 latency requirements are tight

For batch jobs: Parallel GC may give better throughput.

## Container considerations

Java in containers historically saw host memory, not container limit.

Java 10+: respects container limits (`-XX:+UseContainerSupport`, default since Java 11).

Set heap as fraction of container memory: `-XX:MaxRAMPercentage=75`.

## Memory tuning workflow

1. Run with default GC; measure
2. If GC is a problem (high pause times, OOM):
   - Enable GC logging
   - Profile with JMC or async-profiler
3. Identify root cause:
   - Heap too small? Increase
   - Memory leak? Heap dump analysis
   - High allocation rate? Profile allocations
4. Tune specifically for the problem
5. Re-measure

Don't tune blindly. JVM defaults are reasonable.

## Further Reading

- [MemoryArchitectures](MemoryArchitectures) — Hardware memory
- [MemoryManagementFundamentals](MemoryManagementFundamentals) — General memory management
- [Java Hub](JavaHub) — Cluster index
