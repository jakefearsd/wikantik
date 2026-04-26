---
title: Reactive Programming
type: article
cluster: software-architecture
status: active
date: '2026-04-25'
tags:
- reactive
- rxjs
- backpressure
- streams
- functional-reactive
summary: Reactive streams (RxJS, Reactor, Akka Streams) — when push-based,
  composable streams with explicit backpressure earn their complexity, and
  the patterns where they collapse into "just promises with extra steps."
related:
- ConcurrencyPatterns
- EventDrivenArchitecture
- ReactBestPractices
hubs:
- SoftwareArchitecture Hub
---
# Reactive Programming

Reactive programming models computation as streams of values that flow through composed operators. Async, push-based, with explicit handling of backpressure (producer outpaces consumer). RxJS, Reactor (Java), Akka Streams, ReactiveX family.

The model is powerful for specific situations — high-throughput streaming, complex async event composition, backpressure-sensitive pipelines. For everything else, async/await is usually simpler and equally good.

## The mental model

In imperative async code:

```
result = await fetchData(url)
processed = transform(result)
return await save(processed)
```

In reactive:

```
fetchData(url)
    .map(transform)
    .flatMap(save)
    .subscribe(result => ..., error => ...)
```

A stream emits zero or more values; operators (`map`, `filter`, `flatMap`) transform; subscribers consume. Async by default; composable.

## When reactive wins

- **Streams of events.** UI events, message bus, sensor data — naturally a stream.
- **Multiple async sources combining.** "Wait for all of these; on each, transform; combine; emit." Reactive operators (`combineLatest`, `merge`, `zip`) handle this elegantly.
- **Backpressure-critical pipelines.** Producer faster than consumer; reactive streams have a defined protocol for the consumer to signal ready.
- **Transformations that compose.** Map, filter, debounce, throttle, retry, switchMap as a chain.
- **UI reactivity.** RxJS in Angular; combineLatest of multiple stores into a derived UI state.

## When async/await wins

- **Sequential async work.** "Fetch this, then that, then save." `await` reads top-to-bottom; reactive's chain reads similarly but is more verbose.
- **Single-shot operations.** Not a stream; just one async call. No reason for reactive overhead.
- **Most of normal application code.** A REST handler calling a database and returning. async/await is enough.

The 2020s consensus: reactive is overused. For most async code, async/await with proper error handling is simpler.

## The 5 things reactive does that async/await doesn't

### 1. Backpressure

Async/await: producer awaits consumer's response, then produces next. Implicit one-at-a-time.

Reactive: producer can produce continuously; consumer requests N items; if producer is faster, it can buffer, drop, or apply policy.

```javascript
source$.pipe(
    bufferCount(100),    // batch in 100s
    concatMap(batch => process(batch))  // wait for one before next
)
```

For streams where production rate genuinely outpaces consumption rate, this matters.

### 2. Time-based operators

```javascript
source$.pipe(
    debounceTime(300),   // emit only after 300ms of silence
    distinctUntilChanged(),
    switchMap(query => searchApi(query))
)
```

Standard auto-complete. The combination of `debounce`, `distinct`, `switchMap` (cancels previous on new emission) is hard to write cleanly with async/await.

### 3. Cancellation propagation

When a subscriber unsubscribes, the upstream operations cancel. AbortController in browser fetch is the equivalent for plain async; reactive bakes it in.

### 4. Composing multiple streams

```javascript
combineLatest([userStore$, settingsStore$, currentRoute$]).pipe(
    map(([user, settings, route]) => deriveState(user, settings, route))
)
```

Re-emits whenever any of the sources emit. Async/await would require manual coordination.

### 5. Hot vs cold streams

A cold stream replays its values for each subscriber. A hot stream is shared; multiple subscribers see the same emissions.

```javascript
const sharedClicks$ = clicks$.pipe(share()); // hot
```

Useful for caching expensive computations; for multicasting events.

## Backpressure strategies

When producer outpaces consumer, what happens?

- **`buffer`** — accumulate; risk OOM if producer is too fast.
- **`drop`** — discard new items.
- **`drop_oldest`** — keep new; discard old.
- **`error`** — fail loudly when buffer is full.
- **`block` (in some implementations)** — pause producer until consumer catches up.

Reactive frameworks make this explicit. Pick a policy per stream.

## Common operators

| Operator | What it does |
|---|---|
| `map` | Transform each value |
| `filter` | Keep matching values |
| `flatMap` / `mergeMap` | Transform to a stream; flatten; concurrent |
| `concatMap` | Transform to a stream; flatten; sequential |
| `switchMap` | Transform to a stream; cancel previous on new |
| `debounceTime` | Emit only after silence |
| `throttleTime` | Emit at most once per period |
| `distinctUntilChanged` | Drop consecutive duplicates |
| `take` | Take first N |
| `takeUntil` | Stop on signal |
| `combineLatest` | Combine latest from multiple sources |
| `zip` | Combine pairs |
| `merge` | Interleave |
| `concat` | Sequential |
| `share` | Multicast |
| `retry` | Retry on error |
| `catchError` | Handle errors |

The vocabulary is extensive. Most production reactive code uses 10-15 operators heavily; the rest are edge-case-only.

## Anti-patterns

- **Reactive everywhere.** Wrapping plain async calls in observables. Adds verbosity; no win.
- **Side effects in `map`.** `map` should be pure; side effects in `tap` (named explicitly).
- **Subscribing without unsubscribing.** Memory leak; subscriptions hold reference to source.
- **`.toPromise()` everywhere.** Converting back to promise immediately. Stick to one paradigm.
- **Nested `flatMap`.** Hard to read; refactor to chains.
- **Imperative state outside the stream.** Defeats the model; mix carefully.

## Where reactive shines in 2026

- **Frontend UIs** — RxJS in Angular, signal-based reactivity in newer frameworks (SolidJS, Vue 3). The model fits user-event-driven UIs well.
- **Streaming backends** — Reactor (Java), specifically WebFlux / Spring Reactive, for high-throughput non-blocking servers.
- **Event-driven backends** — Akka Streams in Scala / Java; backpressure-aware event pipelines.
- **Bridging async sources** — composing data from multiple sources reactively.

## Where reactive has lost ground

- **In React** — moved away from RxJS. Hooks + context + signals (in Solid-like) cover most of what RxJS did.
- **Node.js general async** — async/await dominates; RxJS is one of several streaming options.
- **Java backends** — Project Loom (virtual threads, Java 21+) makes most reactive Java unnecessary. Code reads like blocking but doesn't block; backpressure handled differently. Reactive Java is in retreat.
- **Rust / Go async** — these languages didn't really adopt reactive patterns; channels + goroutines / async + tokio are simpler.

The high-water mark of reactive programming was around 2018-2020. Since then, language-level async (async/await, virtual threads) has handled what reactive promised, more simply.

## Concrete example: search-as-you-type

The canonical reactive use case:

```typescript
const searchResults$ = searchInput$.pipe(
    debounceTime(300),
    distinctUntilChanged(),
    filter(query => query.length >= 2),
    switchMap(query => searchApi(query).pipe(
        catchError(err => of([]))
    ))
);

searchResults$.subscribe(results => updateUI(results));
```

Without reactive:

```typescript
let lastQuery = '';
let cancelToken = null;

const onInput = debounce(async (query) => {
    if (query === lastQuery || query.length < 2) return;
    lastQuery = query;
    if (cancelToken) cancelToken.abort();
    cancelToken = new AbortController();
    try {
        const results = await searchApi(query, {signal: cancelToken.signal});
        updateUI(results);
    } catch (err) {
        if (!cancelToken.signal.aborted) updateUI([]);
    }
}, 300);
```

The reactive version is shorter and more obviously correct. For these specific patterns, reactive earns its keep.

## Tools

- **RxJS** — JavaScript/TypeScript. The dominant reactive lib.
- **Project Reactor** — Java. Used in Spring WebFlux.
- **Akka Streams** — Scala/Java. Stream-processing-oriented; powerful, complex.
- **ReactiveX** family — bindings in many languages (RxSwift, RxKotlin, RxRust).
- **MutationObserver, ResizeObserver** — DOM equivalents; reactive in spirit.

## A pragmatic position

Use reactive for streams. Use async/await for sequential async. Don't conflate them.

Specifically:

- **UI event streams** — reactive wins.
- **Data pipelines with backpressure** — reactive wins.
- **REST handlers, simple async** — async/await wins.
- **Mixed code** — keep them separate; bridge at boundaries.

The mistake is the all-or-nothing stance. Reactive is a tool; use where it fits.

## Further reading

- [ConcurrencyPatterns] — concurrency primitives broadly
- [EventDrivenArchitecture] — events at the architecture level
- [ReactBestPractices] — React's specific take on reactivity
