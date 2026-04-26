---
title: Concurrency Patterns
type: article
cluster: software-architecture
status: active
date: '2026-04-25'
tags:
- concurrency
- threads
- async
- channels
- locks
summary: The concurrency primitives and patterns that show up across modern
  languages — locks, channels, async/await, actors, futures — with the
  trade-offs and the failure modes each one introduces.
related:
- ConcurrencyDistributed
- ActorModelProgramming
- JavaConcurrencyPatterns
- ReactiveProgramming
hubs:
- SoftwareArchitecture Hub
---
# Concurrency Patterns

Concurrency is "doing multiple things, possibly overlapping in time." Parallelism is "doing multiple things truly simultaneously, on different cores." You can have one without the other — async I/O is concurrent without being parallel; SIMD math is parallel without typical concurrency overhead.

Most application concurrency is about *waiting*. A web server isn't doing computation 99% of the time; it's waiting for a database, a downstream service, a file. The patterns are about how to wait for many things efficiently.

## The primitives

### Locks (mutexes)

Mutual exclusion. Only one thread holds the lock at a time. Critical sections inside the lock are atomic.

```python
with lock:
    counter += 1
```

Costs:

- **Contention** — threads queue at the lock. The "single-threaded section" becomes the bottleneck.
- **Deadlock** — two threads each holding a lock the other wants. Easy to create; surprisingly hard to debug.
- **Priority inversion** — low-priority thread holds lock; high-priority thread waits.

Use sparingly. The pattern "data structure protected by a lock" is correct but doesn't scale; "many readers, occasional writer" is the canonical example where read-write locks help; "shared mutable state" is a smell, not a pattern.

### Read-write locks

Multiple concurrent readers; exclusive writer. Useful when reads dominate and you can stand the complexity.

Implementation gotchas: writer starvation (constant readers prevent writes), priority handling, upgrade-to-writer (deadlock-prone). Many languages provide tested implementations; use those.

### Atomic operations

Hardware-supported single-word operations: compare-and-swap (CAS), atomic increment, atomic exchange. Building blocks for lock-free data structures.

```rust
counter.fetch_add(1, Ordering::SeqCst);
```

Faster than locks for simple operations; harder to compose for complex ones. Used heavily inside libraries (queues, hash tables); rarely the right level for application code.

### Channels

A queue with sender(s) and receiver(s). Senders put messages in; receivers take them out.

```go
ch := make(chan int, 10)  // buffered channel, size 10
ch <- 42                  // send
val := <-ch               // receive
```

The defining feature of Go's concurrency model. Also present in Rust (`std::sync::mpsc`, crossbeam), and effectively built on top of futures in async languages.

Why they're popular: communication without shared mutable state. "Don't communicate by sharing memory; share memory by communicating" (Go's mantra).

Costs: channels are usually slower than direct shared-memory access; debugging "where did this message go" can be harder than tracing function calls.

### Futures / Promises

A handle to a value that will be available later.

```javascript
const result = await fetchData();  // suspends here until data arrives
```

The dominant model in modern JavaScript, Python (asyncio), Rust (async/await), C# (Task), Java (CompletableFuture, virtual threads).

Win: code looks synchronous but doesn't block. One thread handles thousands of concurrent operations.

Cost: the "function colour problem" — async functions can only be called from async contexts. A library half-converted to async is more painful than a fully sync or fully async one.

### Actor model

Each actor has its own state and a mailbox. Actors communicate by sending messages. State is never shared; concurrency is structural.

```scala
class Counter extends Actor {
  var count = 0
  def receive = {
    case Increment => count += 1
    case Get => sender ! count
  }
}
```

Strong isolation; great for distributed systems where actors might be on different machines. Used in Erlang/Elixir (the canonical example), Akka, Orleans, Pony.

See [ActorModelProgramming].

## Patterns

### Producer-consumer

Producers generate work; consumers process it. A buffer / queue between them decouples rate.

```go
jobs := make(chan Job, 100)
go producer(jobs)
go consumer(jobs)
```

Universal pattern. Consider:

- **Bounded vs unbounded buffer.** Unbounded means producer can flood memory. Bounded means producer back-pressure when consumer is slow. Almost always bounded.
- **One producer, many consumers** — load balancing.
- **Many producers, one consumer** — serialisation.
- **Many producers, many consumers** — scaling.

### Fan-out / fan-in

Split work across multiple workers; collect results.

```go
// Fan out
for i := 0; i < numWorkers; i++ {
    go worker(jobs, results)
}

// Fan in
for r := range results {
    process(r)
}
```

Pattern is the same across languages with different syntax. The difficulty is error handling: if one worker fails, do you cancel the rest, retry, or proceed? Each is right in different contexts.

### Pipeline

Chain of stages connected by channels. Each stage transforms data.

```
source → parser → enricher → writer
```

Each stage runs as its own goroutine / task / thread. Buffered channels between stages provide backpressure.

Used in stream processing (Apache Beam, Flink internally), build systems, data pipelines.

### Worker pool

Fixed number of workers; pull work from a shared queue. Limits resource use; balances load.

```python
with ThreadPoolExecutor(max_workers=10) as pool:
    results = pool.map(work, items)
```

The default for "I want to process N items in parallel but don't want N threads." Use the standard library's pool; don't roll your own.

### Single-flight

When many concurrent callers would do the same work, only do it once. Other callers wait for the first to finish.

```go
result, err, _ := singleflight.Do("key", func() (interface{}, error) {
    return expensiveOperation()
})
```

Critical for cache stampedes — when a hot cache key expires and 1000 requests miss simultaneously, you don't want 1000 database queries.

### Cancellation

Long-running concurrent work needs a cancellation mechanism — request abandoned by the user, timeout, system shutdown. Modern languages provide context.Context (Go), CancellationToken (C#), AbortController (JS), drop-on-future-cancel (Rust).

The pattern: every async function takes a cancellation handle; checks it periodically; returns early if cancelled.

Forgetting cancellation is the most common cause of "the request was cancelled but the work continued anyway" bugs.

### Backpressure

When producer is faster than consumer, something has to give:

- **Drop messages** — fastest, can lose data.
- **Buffer with bound** — pushes pressure back to producer.
- **Block producer** — simplest, can deadlock if producer holds resources.
- **Sample / aggregate** — producer-side smarts to reduce volume.

Reactive streams (Reactor, Rx, Akka Streams) make backpressure explicit and composable. See [ReactiveProgramming].

## Failure modes

**Deadlock.** Lock ordering is the standard prevention: every code path acquires locks in the same global order. Deadlock detection tools (Java's `jstack`, Go's deadlock detector) help in development.

**Race condition.** Two threads update shared state without synchronisation; result depends on timing. Symptoms: passes in development, fails in production under load. Prevention: profile shared state; lock or use atomics; design state to be thread-local where possible.

**Livelock.** Threads continually retry a failed operation, never making progress. Add backoff (with jitter); add a max-retry budget; eventually surface the failure.

**Lost wakeup.** Thread waits on a condition that signals just before the wait. Standard pattern: check condition after waking, retry if not yet satisfied. Use language's condition-variable API correctly.

**Goroutine / thread leak.** Started a worker; never told it to stop. Memory grows; eventually OOM. Always have a stop signal; verify shutdown.

**Memory model surprises.** Without proper synchronisation, the compiler / CPU may reorder memory accesses. Reads can see stale values, partial writes. Use language-provided synchronisation primitives; don't try to reason about reordering yourself.

## Choosing a model

For new code:

- **CPU-bound parallel work** → thread pool, structured concurrency, parallel collections.
- **I/O-bound concurrent work** → async/await (the language native version).
- **Many independent agents communicating** → channels (Go) or actors (Erlang/Akka).
- **Pipelines of transformations** → channels with stages, or reactive streams for explicit backpressure.
- **Shared state with low contention** → atomics + locks, used judiciously.
- **Shared state with high contention** → redesign. Almost always there's a way to avoid sharing.

## Language-specific notes

- **Go** — channels and goroutines as primary. Mature, well-trodden. See [JavaConcurrencyPatterns]-style equivalent for Go.
- **Java** — threads + Executor framework + virtual threads (Project Loom, GA in JDK 21). Virtual threads change the calculus dramatically — async/await mostly unnecessary in modern Java.
- **Python** — asyncio for I/O-bound, multiprocessing for CPU-bound (the GIL prevents true threading parallelism for CPU work). PEP 703 (no-GIL build) increasingly available.
- **Rust** — async/await + tokio runtime. Borrow checker catches data races at compile time; phenomenally good for concurrent code correctness.
- **JavaScript** — single-threaded event loop + Promises + async/await. Workers for CPU-bound work; the "shared memory model" is rare.

## Further reading

- [ConcurrencyDistributed] — concurrency across machines, not just cores
- [ActorModelProgramming] — actor model in depth
- [JavaConcurrencyPatterns] — Java specifics
- [ReactiveProgramming] — push-based, explicit-backpressure approach
