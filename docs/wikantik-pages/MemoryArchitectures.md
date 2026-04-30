---
canonical_id: 01KQ0P44SDTD7847AZ2Z0FECB2
title: Memory Architectures
type: article
cluster: computer-science-foundations
status: active
date: '2026-04-26'
summary: How computer memory is organized — the hierarchy from registers to disk,
  cache levels, NUMA, and the practical consequences for software performance.
tags:
- memory-architecture
- computer-science
- caches
- numa
- performance
related:
- MemoryManagementFundamentals
- JavaMemoryManagement
- QuantumComputing
hubs:
- ComputerScienceFoundationsHub
---
# Memory Architectures

Memory in modern computers is hierarchical, not uniform. Understanding the hierarchy explains why some code is fast and some slow even when the algorithms are the same.

This page covers the memory hierarchy and its software implications.

## The memory hierarchy

From fastest to slowest:

| Level | Capacity | Latency | Bandwidth |
|-------|----------|---------|-----------|
| Registers | ~256 bytes | <1 ns | ~TB/s |
| L1 cache | 32-64 KB | ~1 ns | hundreds of GB/s |
| L2 cache | 256 KB - 2 MB | ~3-10 ns | hundreds of GB/s |
| L3 cache | 4-128 MB | ~10-30 ns | hundreds of GB/s |
| RAM | GBs | ~50-100 ns | ~25-50 GB/s |
| SSD | TBs | ~10-100 μs | GB/s |
| HDD | TBs | ~10 ms | hundreds of MB/s |
| Network storage | PBs | ms+ | depends |

Each level is roughly 10x slower and 10x larger than the level above.

## Why hierarchy

Single fast/large memory would be ideal. Physics and economics conflict:
- Fast memory is small and expensive
- Large memory is slow and cheap

Hierarchy gives an effective speed close to fastest level (when cache hits) and capacity of slowest level.

## CPU caches

### L1

Per-core. Split into instruction (L1i) and data (L1d).

Smallest, fastest. Hit determines whether instruction runs immediately or waits.

### L2

Per-core (usually). Larger, slightly slower.

### L3

Shared across cores. Largest CPU cache.

Provides cache coherence reference for inter-core communication.

### Cache line

Unit of cache management. Typically 64 bytes.

Memory is read/written in cache lines. Touching one byte loads 64.

Has profound implications for data layout.

## Cache behavior

### Hit / miss

Cache hit: data in cache, fast access.

Cache miss: must fetch from slower level. Slow.

### Prefetching

CPU predicts what memory you'll access; loads it early.

Sequential access patterns prefetch well; random doesn't.

### Eviction

Cache full; old data evicted to make room.

Policies: LRU (or approximations), random.

## Cache-friendly programming

### Sequential access

Iterate through arrays in order. Prefetcher loves this.

### Cache-line-aligned data

Avoid splitting hot data across cache lines.

### Compact data

Smaller types fit more in cache.

### Avoid pointer chasing

Linked lists, trees with pointers cause cache misses.

Arrays are cache-friendly; pointer-based structures often aren't.

### Locality of reference

Use data soon after touching nearby data. Stays in cache.

## False sharing

Multiple cores writing different fields in same cache line. Each write invalidates other cores' caches.

Symptom: parallel code mysteriously slow.

Solution: pad data to cache line boundaries; separate hot fields.

## NUMA (Non-Uniform Memory Access)

In multi-socket systems:
- Each CPU has local memory
- Remote memory (other CPU's local) is slower

NUMA-aware programming:
- Pin threads to cores
- Allocate memory near where it's used
- Avoid cross-socket data sharing

OS schedulers and allocators are NUMA-aware to some extent. Critical workloads need explicit attention.

## Virtual memory

OS abstraction: each process sees its own address space.

### Page tables

Map virtual addresses to physical. Hardware MMU translates.

### TLB (Translation Lookaside Buffer)

Caches recent translations. TLB miss is expensive (full page table walk).

### Page faults

Virtual page not in physical memory. OS handles, possibly loading from disk.

Major faults (disk I/O) are very slow (~10ms).

### Huge pages

Standard pages: 4KB. Huge: 2MB or 1GB.

Fewer page table entries; better TLB hit rate.

Used for performance-critical apps with large working sets.

## RAM

Modern DDR4/DDR5:
- DDR4: ~25 GB/s per channel
- DDR5: ~50 GB/s per channel
- Multi-channel: 2x or more

Latency hasn't improved much over decades. Bandwidth has.

For LLM inference: bandwidth is often the bottleneck.

## Memory bandwidth

Sequential reads can hit max bandwidth.

Random reads: latency-bound; effective bandwidth much lower.

### Implication

A program reading random 8-byte values from RAM: ~50ns each. For 25 GB/s peak, that's 5 GB/s actually achieved (160M ops/sec). Sequential could hit 25 GB/s of real data.

## Persistent storage

### SSD

NAND flash. Block-based read/write.

Read latency: tens of microseconds.
Write latency: depends on technology.
Wear: limited write cycles per cell.

NVMe: PCIe-connected SSD. Fast.

### HDD

Spinning platters. Mechanical seek time dominates.

Sequential: ~200 MB/s.
Random: limited by seek time (~10ms each).

### Persistent memory (Optane was discontinued)

Byte-addressable persistent memory. Slower than DRAM, faster than SSD.

Niche product line.

## Memory access patterns

### Streaming / sequential

Hardware prefetcher kicks in. Effective bandwidth approaches peak.

### Strided

Access pattern with fixed stride. Prefetcher may detect.

If stride > cache line, every access is a miss.

### Random

Worst case. Each access pays full latency.

## Implications for algorithms

### Cache-oblivious algorithms

Designed to work well at all levels of hierarchy without tuning.

Examples: cache-oblivious matrix multiplication.

### Blocking

Process data in chunks that fit in cache.

Matrix multiplication: blocking is huge speedup.

### Locality-aware data structures

B-trees over binary trees for cache friendliness.

Arrays of structures (AoS) vs structures of arrays (SoA) tradeoffs.

### Memory-efficient algorithms

In-place where possible. Reduces working set.

## Modern challenges

### Memory wall

CPU speeds grew faster than memory speeds. Memory access dominates many workloads.

Solution: more cache, smarter prefetching, GPU-style throughput computing.

### Energy

Memory access uses far more energy than computation.

For mobile / edge: reducing memory traffic matters.

## Data layout patterns

### Array of structures (AoS)

```c
struct Point { float x, y, z; };
Point points[N];
```

Good when accessing whole structs.

### Structure of arrays (SoA)

```c
float xs[N], ys[N], zs[N];
```

Good when accessing one field across many elements (vectorization).

### Mixed / hybrid

Critical data hot, less-used cold. Lay out to keep hot data dense.

## Common failure patterns

### Treating memory as flat

Code that ignores locality is slow.

### Pointer-heavy structures

Linked lists, deep object graphs. Cache misses dominate.

### Ignoring NUMA

In multi-socket systems, ignoring NUMA wastes bandwidth.

### False sharing

Subtle multi-threaded performance bug.

### Random access at scale

When data exceeds RAM, random access becomes catastrophic.

### Page table thrashing

Working set exceeds TLB. Lots of page table walks.

## Profiling

Tools:
- perf (Linux): cache misses, branch mispredictions
- Intel VTune: detailed cache analysis
- LIKWID, PAPI: counter access
- AMD uProf: AMD equivalent

Key counters:
- Cache misses per instruction
- TLB misses
- Memory bandwidth
- Cycles stalled on memory

## Practical advice

1. Default to arrays over linked structures
2. Profile cache misses on hot paths
3. Pack data, align hot fields
4. Iterate in cache-friendly order
5. For multi-threaded: avoid false sharing
6. For NUMA systems: pin threads and allocate locally

For most application code: standard library defaults are good. Optimization matters when profiling reveals memory-bound code.

## Further Reading

- [MemoryManagementFundamentals](MemoryManagementFundamentals) — Allocator design
- [JavaMemoryManagement](JavaMemoryManagement) — JVM specifics
- [QuantumComputing](QuantumComputing) — Different paradigm
- [Computer Science Foundations Hub](ComputerScienceFoundationsHub) — Cluster index
