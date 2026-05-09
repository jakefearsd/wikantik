---
cluster: devops-sre
canonical_id: 01KQ0P44TH23YHWB07DBCVWGM7
title: Performance Profiling
type: article
tags:
- profiling
- performance
- linux-perf
- flamegraphs
summary: A practitioner's guide to identifying bottlenecks using sampling profilers, hardware counters, and flamegraphs.
auto-generated: false
---
# Performance Profiling and Bottleneck Analysis

Performance profiling is the empirical process of measuring resource consumption (CPU, Memory, I/O) to identify the "critical path" that limits system throughput or increases latency.

## The Profiling Hierarchy

1.  **Whole-System Triage:** Use `top`, `htop`, or `iostat` to determine if the bottleneck is CPU, Memory (swapping), or Disk I/O.
2.  **Sampling Profiling:** Periodically capture stack traces to find "hot" functions. Low overhead (~1-5%).
3.  **Instrumentation:** Injecting code to measure every call. High overhead, but provides exact call counts.
4.  **Hardware Counters:** Reading CPU registers (PMC) for cache misses, branch mispredictions, and instruction retirement.

## Tools of the Trade

### Linux `perf`
The standard for low-level profiling on Linux. It accesses hardware performance counters and kernel tracepoints.

```bash
# Record CPU profile for 10 seconds
perf record -F 99 -a -g -- sleep 10

# View the report
perf report --stdio
```

### Flamegraphs
Visualizes stack traces where the X-axis is the population (width = time spent) and the Y-axis is the stack depth. It allows for instant identification of "hot" branches.

**Concrete Workflow:**
1.  Capture data: `perf record -g -p <pid>`
2.  Collapse stacks: `perf script | ./stackcollapse-perf.pl > out.folded`
3.  Generate SVG: `./flamegraph.pl out.folded > profile.svg`

## Common Bottlenecks and Signatures

### 1. CPU Bound: Algorithmic Inefficiency
-   **Signature:** High CPU usage, deep stacks in math or logic functions.
-   **Example:** A $O(N^2)$ nested loop instead of a $O(N \log N)$ map lookup.
-   **Fix:** Algorithmic refactoring.

### 2. CPU Bound: Cache Misses
-   **Signature:** High CPU usage but low "Instructions Per Cycle" (IPC). `perf stat` shows high `L1-dcache-load-misses`.
-   **Example:** Iterating over a linked list or an Array of Structures (AoS) with poor locality.
-   **Fix:** Switch to Structure of Arrays (SoA) or use contiguous memory blocks.

### 3. I/O Bound: External Latency
-   **Signature:** Low CPU usage, process in "Uninterruptible Sleep" (D state).
-   **Example:** Synchronous database calls in a loop.
-   **Fix:** Connection pooling, batching queries, or asynchronous I/O.

### 4. Lock Contention
-   **Signature:** High "System" CPU time (kernel) or many context switches.
-   **Example:** Multiple threads fighting for a single global `synchronized` block.
-   **Fix:** Use `ConcurrentHashMap`, fine-grained locking, or Lock-Free data structures (Atomics).

## The Optimization Loop

1.  **Measure:** Establish a baseline under realistic load.
2.  **Profile:** Identify the top bottleneck (the 80/20 rule).
3.  **Optimize:** Apply a surgical change (e.g., add an index, change an algorithm).
4.  **Verify:** Re-run the profile. If the bottleneck hasn't moved or overall time hasn't decreased, the change was ineffective.
