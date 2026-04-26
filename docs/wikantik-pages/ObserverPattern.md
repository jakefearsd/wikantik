---
title: Observer Pattern
type: article
cluster: software-architecture
status: active
date: '2026-04-25'
tags:
- observer-pattern
- pub-sub
- event-bus
- reactive
summary: Observer pattern from GoF to modern event buses, signals, and
  reactive streams — what each implementation is good at, and the failure
  modes that keep showing up.
related:
- DesignPatternsOverview
- EventDrivenArchitecture
- ReactiveProgramming
hubs:
- SoftwareArchitecture Hub
---
# Observer Pattern

A subject maintains a list of observers; when state changes, the subject notifies each observer. The pattern lets one component publish events without knowing who consumes them.

It's one of the most-used patterns; in 2026, you mostly use library implementations rather than write your own. The pattern's principles still inform good design.

## The classical form

```java
class Subject {
    private List<Observer> observers = new ArrayList<>();
    
    public void subscribe(Observer o) { observers.add(o); }
    public void unsubscribe(Observer o) { observers.remove(o); }
    
    protected void notify(Event e) {
        for (Observer o : observers) o.onEvent(e);
    }
}

class Subject extends Subject {
    void doSomething() {
        // ... work ...
        notify(new Event(...));
    }
}
```

Subscribers register; subject notifies; subscribers react. Decoupled.

In modern code you rarely write this verbatim. The same shape shows up under different names:

- DOM `addEventListener`
- React `useEffect`
- Domain event publishing
- Message broker subscriptions
- Reactive streams `subscribe`
- Signals (Angular, Solid, Vue 3)
- Custom event emitters (Node EventEmitter)

## When the pattern earns its keep

- **Loose coupling.** Subject doesn't know who reacts; new observers can be added without changing the subject.
- **Many observers per subject.** UI elements reacting to model changes. N observers on one event.
- **Asynchronous boundaries.** Events from one part of the system processed by others, possibly later, possibly elsewhere.

## When something else fits better

- **One observer.** Direct method call is simpler.
- **Need synchronous result back.** Observer is for fire-and-forget; if you need a result, use direct call or request-response.
- **Cross-process.** A real message broker (Kafka, RabbitMQ, NATS) handles persistence, delivery guarantees, retries. The Observer pattern is for in-process.

## Common failure modes

### Memory leaks via subscriptions

Most common Observer bug: subscriber registers; never unregisters; subject keeps reference; subscriber never garbage-collected.

In React:

```javascript
useEffect(() => {
    socket.on("event", handler);
    return () => socket.off("event", handler);  // crucial cleanup
}, []);
```

In RxJS, subscriptions need explicit `.unsubscribe()` or operators like `takeUntil` for lifecycle management.

In Java GUI code (Swing), the classic bug: register a model listener; forget to unregister when the view is destroyed; garbage collection blocked.

The fix: every subscribe is paired with an unsubscribe. Use language idioms (`useEffect` cleanup, RxJS `takeUntil`, Kotlin `Lifecycle.onDestroy`) to enforce.

### Order dependency

Observers receive notifications in some order. If they have side effects that depend on each other, order matters; the pattern doesn't guarantee one.

Defence: don't rely on order. Each observer's reaction should be independent of others. If you need a specific order, use a coordinator that calls them explicitly in sequence.

### Notify cascades

Observer A reacts to event E by causing event F; observer B reacts to F by causing event G; etc. Cascade can deepen unboundedly.

Stack overflow: A notifies B notifies A. Mutual subscription = infinite loop.

Defence: detect cycles; either prevent registration of cyclic subscriptions or break the cycle at notify time. In React, this is what `setState` inside `useEffect` does — careful dependency arrays prevent loops.

### Synchronous notify blocks the publisher

Subject calls each observer synchronously. A slow observer blocks the publisher and all subsequent observers.

Defence: notify asynchronously (event loop dispatch); or have observers do their work async; or extract to an event queue / message broker for cross-process.

## Modern implementations

### Reactive streams (RxJS, Reactor)

Observer pattern with backpressure, composition, and cancellation built in.

```javascript
const subscription = source$.pipe(
    map(transform),
    filter(predicate)
).subscribe(value => handle(value));

// Later
subscription.unsubscribe();
```

For complex async event chains, reactive streams are the modern Observer pattern. See [ReactiveProgramming].

### Signals (Solid, Angular, Vue 3, Svelte 5)

Fine-grained reactivity. A signal is a piece of state; reading it inside a reactive context auto-subscribes; writing notifies subscribers.

```javascript
const count = signal(0);
const doubled = computed(() => count() * 2);

effect(() => console.log(doubled()));  // logs 0
count.set(5);                           // logs 10
```

The Observer pattern becomes implicit; the framework does the bookkeeping. Less verbose than manual subscribe/unsubscribe.

### Event buses

In-process pub/sub with a central bus:

```javascript
bus.on("user.created", handler);
bus.emit("user.created", user);
```

Domain events within a service. Decouples one part from another. Common in DDD-influenced architectures.

For cross-service events, a real message broker — see [EventDrivenArchitecture].

### Domain events

In a Domain-Driven Design context, important business events are explicitly modelled and published.

```java
class Order {
    public void confirm() {
        this.status = CONFIRMED;
        domainEvents.add(new OrderConfirmedEvent(this.id));
    }
}
```

After the transaction commits, events are dispatched. Receivers can be in the same process (sync handlers) or out (via outbox + broker).

This is Observer pattern at the architecture level. See [DomainAndIntegrationEvents].

## Synchronous vs asynchronous

Two distinct semantics:

- **Synchronous notify**: subject's call to `notify` blocks until all observers complete. Simpler; subject can detect failures; doesn't scale to slow observers.
- **Asynchronous notify**: subject queues events; another thread / event loop processes. Subject doesn't block; observer failures don't cascade.

For UIs, synchronous (event loop manages async). For backends with heavy observer logic, async via queue.

## Pattern variations

### Push vs pull

- **Push**: subject sends data with the notification ("here's the new value").
- **Pull**: subject sends a notification only ("something changed; check yourself"); observer asks for current state.

Push is more common; less network roundtrips for in-process; explicit. Pull is useful when observers want different views of the data, or when the data is large and observers might not need it.

### Filter at the subject

Observers register interest in specific event types or properties:

```javascript
bus.on("user.*", handler);    // wildcard
bus.on("user.created", handler);
bus.on({type: "user", action: "created"}, handler);
```

Less broadcast; less wasted work. Especially valuable in event buses with many topics.

### Priority / ordering

Some implementations let observers register with priority; high-priority handlers run first.

Use sparingly; usually a smell. If two observers need a specific order, they're not actually decoupled.

## A pragmatic stance

For most modern code:

- **Don't reinvent.** The DOM has events. React has hooks. RxJS has streams. Your message broker has subscriptions. Use them.
- **Pair subscribe with unsubscribe.** Always. Lifecycle hooks help.
- **Prefer signals / reactive primitives** for fine-grained UI reactivity.
- **Use a message broker** for cross-service.
- **Keep handlers simple.** Side effects in handlers should be small and idempotent.
- **Don't chain handlers deeply.** A → B → C → D event chains are debugging nightmares.

The Observer pattern's core insight — decoupled publish-subscribe — is fundamental and pervasive. The specific class-based GoF implementation is rarely what you write today.

## Further reading

- [DesignPatternsOverview] — broader pattern context
- [EventDrivenArchitecture] — events at the architecture level
- [ReactiveProgramming] — modern Observer pattern with backpressure
