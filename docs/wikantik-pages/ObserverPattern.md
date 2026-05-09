---
canonical_id: 01KQEKGDDQPMEGTWKM44XBTTSE
title: "Reactive Events: The Observer Pattern and Flow API"
type: article
cluster: software-architecture
status: active
date: '2026-05-22'
tags:
- observer-pattern
- pub-sub
- reactive-streams
- backpressure
- flow-api
summary: Evolution of the Observer pattern from the GoF basics to modern Reactive Streams (Java Flow API). Covers asynchronous dispatch and the critical problem of backpressure.
related:
- DesignPatternsOverview
- EventDrivenArchitecture
- ReactiveProgramming
auto-generated: false
---

# Reactive Events: From Observer to Flow

The **Observer Pattern** defines a one-to-many dependency where state changes in a "Subject" (Publisher) are automatically broadcast to "Observers" (Subscribers). In modern high-throughput systems, the pattern has evolved into **Reactive Streams** to handle asynchronous data flow and **Backpressure**.

## I. Evolution: From Callbacks to Streams
*   **Classic GoF:** Synchronous method calls. If an observer is slow, the entire subject is blocked.
*   **Java Flow API (Java 9+):** Standardized interfaces (`Publisher`, `Subscriber`, `Subscription`) that support asynchronous, non-blocking flow with flow control.

## II. The Backpressure Problem
In a push-only observer model, a fast publisher can overwhelm a slow subscriber, leading to buffer overflows or `OutOfMemoryError`.
*   **Reactive Solution:** The Subscriber *requests* a specific number of items via `Subscription.request(n)`. The Publisher only pushes what has been requested.

## III. Concrete Example: `SubmissionPublisher`
Java provides a built-in `SubmissionPublisher` that implements the `Flow.Publisher` interface for easy in-process event bus creation.

```java
public class MetricService {
    private final SubmissionPublisher<Double> publisher = new SubmissionPublisher<>();

    public void start() {
        publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription sub) {
                this.subscription = sub;
                subscription.request(1); // Backpressure: request only 1
            }

            @Override
            public void onNext(Double item) {
                process(item); // Simulate work
                subscription.request(1); // Ask for next only after processing
            }

            @Override public void onError(Throwable t) { t.printStackTrace(); }
            @Override public void onComplete() { System.out.println("Done"); }
        });
    }

    public void submit(double val) { publisher.submit(val); }
}
```

## IV. Technical Integrity and Anti-patterns

1.  **Memory Leaks:** Always unsubscribe. In long-lived subjects, holding references to short-lived observers prevents GC. Use `WeakReference` if manual unsubscription isn't guaranteed.
2.  **Thread Safety:** Use a `CopyOnWriteArrayList` for the observer list to allow concurrent modification (subscription/unsubscription) during a broadcast.
3.  **Order Sensitivity:** Never design observers that depend on execution order. Notifications should be independent.
4.  **Deep Chaining:** Chaining observers (A notifies B, B notifies C) creates "Event Hell." Use a central event bus or orchestrator for complex flows.

---
**See Also:**
- [Reactive Programming](ReactiveProgramming) — Advanced stream composition (RxJS/Project Reactor).
- [Event Driven Architecture](EventDrivenArchitecture) — Scaling the observer pattern across services.
- [Java Concurrency](JavaConcurrencyPatterns) — Threads and executors used by publishers.
