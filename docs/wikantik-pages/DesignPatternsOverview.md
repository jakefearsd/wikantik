---
canonical_id: 01KQ12YDTMVYT7HTEWG6DD4EHY
title: Design Patterns Overview
type: article
cluster: software-architecture
status: active
date: '2026-04-25'
tags:
- design-patterns
- gof
- software-design
- patterns
summary: GoF patterns at 30 — which still pay rent, which became language features,
  and which were always overkill. A reading guide for new engineers and a
  reality check for old ones.
related:
- AdapterPattern
- ObserverPattern
- DecoratorPattern
- SingletonPatternAndAlternatives
- DomainDrivenDesign
- HexagonalArchitecture
- CqrsPattern
hubs:
- SoftwareArchitecture Hub
---
# Design Patterns Overview

The Gang of Four book (Gamma, Helm, Johnson, Vlissides, 1994) catalogued 23 patterns in C++ and Smalltalk. Half of them still earn their keep in 2026. The other half either became language features (so you don't notice you're using them) or were always overengineering for problems most code doesn't have.

This page is the calibrated tour: which patterns to actually use, which to recognise but not invoke, and which to mostly skip.

## Patterns that still earn rent

### Adapter

A class that wraps another class to expose a different interface. Used everywhere you have a third-party API, a legacy system, or two incompatible interfaces that need to talk.

Example: your code expects `PaymentProcessor.charge(Money)` but the Stripe SDK has `stripe.Charge.create(amount=int_cents, currency='usd')`. Adapter: `StripePaymentProcessor` implements `PaymentProcessor.charge` and translates internally.

Always relevant. See [AdapterPattern].

### Strategy

Encapsulate a family of algorithms; pick one at runtime. The classic example is sorting comparators; the modern example is dependency-injected behaviours.

```python
class Discount:
    def __init__(self, strategy: PricingStrategy):
        self.strategy = strategy
    def apply(self, cart): return self.strategy.compute(cart)

# At runtime: pass StandardPricing(), HolidayPricing(), or VipPricing()
```

In modern languages, often just a function passed as an argument. Pattern is alive; the boilerplate is gone.

### Observer / Pub-Sub

Subjects publish events; subscribers react. Modern descendants: event buses, message queues, reactive streams.

The OO version (a `Subject` class with `addObserver`/`removeObserver`) is rarely written by hand anymore — your event bus, framework signal system, or message broker handles it. The pattern persists; the implementation is library-provided.

See [ObserverPattern], [EventDrivenArchitecture].

### Decorator

Wrap an object to add behaviour without modifying it. The HTTP middleware stack is a decorator chain. Logging wrappers, caching wrappers, retry wrappers — all decorators.

Python's `@decorator` syntax is named after this pattern. JavaScript / TypeScript decorators (TC39) too. The pattern is so embedded in modern language design that "decorator" the syntactic feature is what most engineers think of, not the GoF pattern.

See [DecoratorPattern].

### Iterator

Traverse a collection without exposing its internal structure. Every modern language ships this as a language feature (Python `__iter__`, C# `IEnumerable`, Rust `Iterator`, Java `Iterator`, JS `for...of`).

You don't write it; you use it. Recognise that it's an instance of the GoF pattern.

### Composite

Tree of objects where leaf and internal nodes share an interface. Used in DOM (every node is `Node`), AST traversal, file system hierarchies, and any UI tree.

When a UI framework lets you nest components arbitrarily and treats the whole tree as one renderable thing, that's Composite.

### Template Method

Define an algorithm's skeleton; subclasses fill in steps. Every framework's "extend this base class and override these hooks" is Template Method.

Less idiomatic in modern languages (we prefer composition over inheritance), but still pervasive in framework APIs.

### Facade

A single simplified interface in front of a complex subsystem. Most "client SDK" libraries are facades over the underlying APIs.

Recognise it; use it when wrapping complexity is genuinely useful. Skip it when the subsystem is already simple — you're just adding indirection.

## Patterns that became language features

### Singleton (with caveats)

Originally: a class with a private constructor and a static `getInstance()`. In practice, singletons turn into hidden global state, complicate testing, and resist dependency injection.

Modern replacement: dependency injection container manages lifecycle; the "single instance" property is a configuration choice, not a class structural feature.

If you find yourself reaching for Singleton, ask whether you actually want a registered service in your DI container. Usually you do. See [SingletonPatternAndAlternatives].

### Factory / Abstract Factory

Originally: a class whose method creates instances of related families.

In modern languages, often just a function that returns a configured object. In TypeScript / Java with DI, the framework is the factory. Still useful as a concept; the explicit `FooFactory` class is rarely needed.

### Command

Encapsulate a request as an object. CQRS (see [CqrsPattern]) is Command at architecture scale. Undo/redo systems use Command. Job queues use Command.

The pattern is alive; the explicit hand-rolled `CommandInterface` is rarely how it shows up — it's serialized job records, message envelopes, or domain events.

## Patterns to use sparingly

### Visitor

Add operations to a class hierarchy without modifying the classes. Powerful for AST processing or anything else with a closed type hierarchy and an open set of operations.

Trade-off: forces a double dispatch. In languages with pattern matching (Rust, Scala, modern Python), straight pattern matching is cleaner. Reach for Visitor only when the type hierarchy genuinely is closed and frequently traversed by new operations.

### Memento

Save and restore an object's internal state. Useful for undo, snapshots, transactional rollback. Often replaced by immutable data structures + persistent collections.

If you have immutable state, "snapshot" is just keeping the old reference; Memento adds nothing. If you have mutable state, Memento is sometimes the right pattern, but usually a redesign toward immutable state is better.

### Mediator

A class that handles communication between many other classes, so they don't need direct references to each other.

In practice, frequently turns into a "god object" containing all coupling rather than no coupling. The intent is sound; the executions go badly. Often: a message bus or event system is a better implementation.

## Patterns that were mostly always overkill

### Bridge

Decouple abstraction from implementation so they can vary independently. The example in the book is shapes (Circle/Square) crossed with renderers (Vector/Raster), so you get one class per (shape, renderer) pair without combinatorial explosion.

In practice: rarely the actual problem. Most code doesn't have an orthogonal abstraction × implementation matrix. When it does, composition handles it without naming it Bridge.

### Flyweight

Share fine-grained objects to save memory. The classic example is character glyphs in a text editor.

Almost never the problem in modern systems. Memory is cheap; the overhead of fine-grained objects is a micro-optimization for narrow workloads (game engines, embedded systems). Skip unless profiling demands it.

### Prototype

Create new objects by cloning an existing one. JavaScript's prototype-based inheritance is conceptually related but not the same pattern. The GoF Prototype is rarely the right answer — explicit constructors, builders, or factory methods are clearer.

### Chain of Responsibility

A request passes through a chain of handlers; each can handle it or pass on. Useful for some specific cases (HTTP middleware, exception handlers), but the explicit chain object is rarely the cleanest implementation.

The "middleware stack" pattern in modern web frameworks is essentially this; you rarely need to instantiate `Handler` classes by hand.

### Interpreter

Define a grammar and an interpreter for it. Useful for narrow DSLs; usually overkill — generate a parser with a tool (ANTLR, Lark, Pest) or use existing language features.

## Modern patterns the GoF book didn't have

The book is 30 years old. Patterns that have emerged or been formalised since:

- **Dependency Injection** — the framework supplies what an object needs. Pervasive. Often replaces Factory and Singleton.
- **Repository / Unit of Work** — abstraction over data access. Almost always paired with ORMs. See [DomainDrivenDesign].
- **CQRS** — separate read and write models. See [CqrsPattern].
- **Hexagonal / Ports and Adapters** — domain at the centre, adapters at the edges. See [HexagonalArchitecture].
- **Saga** — long-running transactions across services. See [EventDrivenArchitecture].
- **Circuit Breaker** — fail fast when a downstream is unhealthy.
- **Retry with backoff** — robust handling of transient failures.
- **Bulkhead** — isolate failures so one part of the system doesn't take down others.
- **Outbox** — reliably publish events alongside DB writes.

Most of these address distributed systems concerns the GoF book never anticipated.

## Reading the original

The book is worth reading once. Mental model: each pattern has a problem, a solution, consequences, and known uses. The catalogue style was novel in 1994 and the format set the template for every subsequent pattern catalogue.

Caveats when reading:

- The C++ examples are dated; squint past the syntax.
- Many patterns assumed less powerful languages (no first-class functions, no generics). The pattern's original verbosity often disappears in a modern language.
- "Favour composition over inheritance" is the book's own advice you should weight more heavily than it appears.

## A pragmatic stance on patterns

- **Recognise them.** Reading code becomes easier if you know the vocabulary.
- **Use them when they fit.** Force-fitting patterns produces overengineered code.
- **Don't name-drop.** "I implemented it as a Strategy with an Abstract Factory" is the kind of thing that drives team-mates nuts. Just describe what the code does.
- **Modern primitives often subsume them.** Functions, generics, and DI containers obviate many GoF patterns; learn the modern primitives and you'll need fewer patterns.

The pattern catalogue is a vocabulary, not a checklist.

## Further reading

- [AdapterPattern] — most-used pattern in this list, in depth
- [ObserverPattern] — and its modern descendants
- [DecoratorPattern] — through to language-level decorators
- [SingletonPatternAndAlternatives] — why DI usually wins
- [DomainDrivenDesign] — modern higher-level patterns
- [HexagonalArchitecture] — modern higher-level patterns
- [CqrsPattern] — modern higher-level patterns
