---
type: article
status: active
cluster: computer-science-foundations
date: '2026-04-26'
title: Memory Management Fundamentals
hubs:
- ComputerScienceFoundationsHub
tags:
- memory-management
- allocation
- garbage-collection
- ownership
summary: Manual allocation, GC, smart pointers, arenas — the spectrum of approaches
  languages and runtimes use to track ownership and prevent memory bugs.
related:
- MemoryArchitectures
- JavaMemoryManagement
canonical_id: 01KQ0P44SEQSJA988GG55DSKAM
---
# Memory Management Fundamentals

Programs allocate and free memory. The schemes for doing this affect performance, correctness, and developer ergonomics. Different languages choose different points on a wide design spectrum.

This page covers the major approaches.

## Why it matters

Memory management bugs:
- Memory leaks: allocated but never freed
- Use-after-free: using freed memory
- Double-free: freeing same memory twice
- Buffer overflow: writing past allocation
- Dangling pointers: pointer to freed memory

These cause crashes, security vulnerabilities (most CVEs), and subtle corruption.

The design choice affects both performance and the developer's likelihood of these bugs.

## Manual memory management

The programmer explicitly allocates and frees.

### C / C++

`malloc` / `free` (C); `new` / `delete` (C++).

Pros:
- Maximum control
- Predictable performance
- No GC pauses

Cons:
- Easy to mess up
- Most CVEs come from memory bugs in C/C++
- Cognitive overhead

### Modern C++

RAII (Resource Acquisition Is Initialization): destructors free resources.

Smart pointers:
- `unique_ptr`: single owner; auto-free on destruction
- `shared_ptr`: reference-counted
- `weak_ptr`: non-owning observer

Modern C++ much safer than C-style.

### Discipline-based

Conventions like "function takes ownership" vs "function borrows."

Without language enforcement, mistakes happen.

## Garbage collection

Runtime tracks which memory is reachable; reclaims unreachable.

### Reference counting

Each object has count. Increment on new reference; decrement on lost reference. Free when count reaches 0.

Pros:
- Predictable timing
- Simple
- No GC pauses

Cons:
- Cycle leaks (A points to B points to A)
- Per-reference overhead
- Multi-threaded counts need atomics

Used in: Python (supplemented with cycle detector), Swift (ARC), Objective-C.

### Tracing GC

Periodically: from roots, mark all reachable; sweep unreachable.

### Mark-sweep

1. Mark phase: trace from roots
2. Sweep phase: free unmarked

Simple. Doesn't compact.

### Mark-compact

Mark, then compact live objects to consolidate free space.

Reduces fragmentation.

### Copying GC

Two regions; copy live objects to other; abandon old region.

Fast for young objects (most are dead — little to copy).

### Generational GC

Most objects die young. Collect young gen frequently; old gen rarely.

Used by JVM, .NET, V8.

### Concurrent / incremental GC

GC work overlaps with program execution. Reduces pause times.

ZGC, Shenandoah, .NET background GC.

### Tradeoffs

- Throughput: total work GC saves
- Latency: pause times
- Memory: GC needs headroom
- Concurrency: multi-threaded apps need careful design

## Ownership systems

A middle ground: language enforces correctness without runtime GC.

### Rust

Ownership: each value has unique owner.
Borrowing: temporary references to owned data.
Lifetimes: compile-time validation.

Pros:
- No GC overhead
- Memory safe (compile-time)
- Predictable performance

Cons:
- Steeper learning curve
- Some patterns awkward

Rust has demonstrated this works at scale.

### Affine / linear types

Generalization. Each value used exactly once (linear) or at most once (affine).

Research languages, parts of Rust.

## Region / arena allocation

Allocate from a region; free entire region at once.

### Arena allocation

Bump allocator. Allocate fast (just increment pointer); free everything together.

Used for:
- Compilers (per-pass arenas)
- Servers (per-request arenas)
- Game engines (per-frame arenas)

Pros:
- Allocation extremely fast
- No fragmentation
- No tracking overhead

Cons:
- All-or-nothing free
- Wasted memory if some lives much longer than rest
- Hard to mix with other approaches

### Region inference

Compiler determines regions automatically.

Used in some research languages; Rust does similar with lifetimes.

## Custom allocators

Specialized allocators for specific workloads:

### Slab allocator

Pre-allocate blocks of fixed size. Used for objects of known size.

OS kernels for kernel objects.

### Buddy allocator

Allocate in powers of two. Combine adjacent free blocks.

OS for physical memory.

### Bump pointer

Increment a pointer; never free until all-at-once.

Arena style.

### Free list

Linked list of free blocks. Per size class.

General-purpose allocators.

### Pool

Pre-allocated objects of one type. Allocation just pulls from pool.

Common for connections, threads.

## Allocator quality

General-purpose allocators (malloc):
- glibc malloc (default Linux)
- jemalloc (Facebook): better fragmentation, multi-threaded
- tcmalloc (Google): thread-local caching
- mimalloc (Microsoft): efficient

For latency-sensitive servers: jemalloc or tcmalloc beats default.

## Stack vs heap

### Stack

Per-function call frame. LIFO. Auto-managed.

Fast: pointer arithmetic only.

Limited size. Recursion can overflow.

### Heap

Dynamic. Manually or GC-managed.

Slower than stack. Larger.

Default: prefer stack when possible. Heap when necessary (large, dynamic, shared).

## Off-heap / off-language

Sometimes you bypass language allocator:
- Memory-mapped files
- Direct memory in JVM
- Native libraries
- GPU memory

Powerful but easy to misuse.

## Memory leaks

In manual: forgot to free. In GC: held reference. Effect is same: memory grows.

Common causes:
- Listeners not removed
- Caches without eviction
- Static collections
- Closures capturing too much
- Cyclic references in reference-counted systems

Detection:
- Heap dumps
- Profilers
- Memory growth over time

## Specific concerns by language

### C / C++

Manual; use modern idioms (RAII, smart pointers). Use sanitizers (ASan, UBSan).

### Java / .NET / Go

GC. Tune GC for workload. Watch for allocation hotspots.

### Python

Reference counting + cycle detection. Allocation is cheap; GC is automatic but has overhead.

### JavaScript

GC. Limited control. V8 (Node) handles well.

### Rust

Ownership. Compile-time guarantees. Use Box, Rc, Arc as needed.

### Swift

Automatic Reference Counting. Manage cycles with weak/unowned.

## Memory safety

Languages either:
- Provide guarantees (Rust, Java, Python, Go, etc.)
- Don't provide guarantees (C, C++)

Memory-unsafe languages produce ~70% of CVEs in major projects. New systems code is increasingly written in memory-safe languages.

## Performance considerations

### Allocation overhead

Heap allocation: tens to hundreds of nanoseconds.

Stack: free.

Bump allocator: a few nanoseconds.

For high allocation rates, allocator quality matters.

### Cache locality

Allocators may scatter objects in memory. Hurts cache.

Pool / arena allocators give better locality.

### Fragmentation

Memory free but unusable due to fragmentation.

Compacting GCs handle. Manual / non-compacting may not.

### GC pauses

Stop-the-world pauses can affect latency.

Modern GCs (ZGC, Shenandoah) target sub-millisecond pauses.

## Common failure patterns

### Memory leaks

Forgetting cleanup. Pervasive in long-running systems.

### Use-after-free (in unsafe languages)

Pointer to freed memory. Subtle bugs.

### Buffer overflows

Writing past array bounds. Security vulnerability.

### Double-free

Freeing same memory twice. Crashes or worse.

### GC tuning misadventures

Tuning without measuring. Often makes things worse.

### Fighting the runtime

Working around GC instead of using it. Often counter-productive.

## Practical advice

For application code:
- Use a memory-safe language by default
- Don't hand-tune GC unless profiling shows need
- Profile allocation hotspots if performance matters
- Use sanitizers in test/CI for unsafe languages

For systems code:
- Memory-safe languages where possible (Rust)
- Disciplined manual management with sanitizers
- Custom allocators for specific hot paths

For learning: implement an allocator once. Builds intuition.

## Further Reading

- [MemoryArchitectures](MemoryArchitectures) — Hardware
- [JavaMemoryManagement](JavaMemoryManagement) — Specific runtime
- [Computer Science Foundations Hub](ComputerScienceFoundationsHub) — Cluster index
