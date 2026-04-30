---
canonical_id: 01KQ0P44R2YVNG5ZFC804AGDZP
title: Immutable Data Patterns
type: article
cluster: software-engineering-practices
status: active
date: '2026-04-26'
summary: Why immutability is the right default for most data, the language-specific
  patterns for working with immutable structures, and the cases where mutable design
  is the better choice.
tags:
- immutability
- data-structures
- software-engineering
- functional-programming
- concurrency
related:
- FunctionalProgrammingPrinciples
- CleanCodePrinciples
- JavaRecordsAndSealedClasses
- JavaCollectionsFramework
- DebuggingStrategies
hubs:
- SoftwareEngineeringPracticesHub
---
# Immutable Data Patterns

Immutability — once data is created, it does not change — eliminates a category of bugs that mutable data permits. Race conditions on shared state, "spooky action at a distance" where one piece of code modifies data another is reading, defensive copying needed everywhere. Immutability eliminates all of it at the cost of some allocation overhead and some indirection.

For most data in most modern systems, immutable is the right default. This page is about the language-specific patterns for working with immutable data, the cases where mutable design is genuinely better, and the practical implementation patterns.

## The core argument

Mutable data has three problems:

1. **Aliasing**: two references to the same data; modification through one affects the other invisibly
2. **Concurrency**: threads modifying shared state need synchronization, which is hard
3. **Reasoning**: a function that takes mutable data could change it; the caller cannot trust assumptions across calls

Immutable data eliminates all three. The "cost" — having to create a new structure when something changes — sounds expensive but is usually invisible in modern systems with good GC and structural sharing.

## Patterns by language

### Java (modern)

```java
// Records are immutable by default
record Order(String id, BigDecimal amount, OrderStatus status) {}

// Updating: produce a new record
Order updated = new Order(original.id(), original.amount(), OrderStatus.SHIPPED);
```

Plus immutable collections: `List.of(...)`, `Map.of(...)`, the immutable variants in `Collections.unmodifiableXxx`.

For larger structures, libraries like Vavr or Eclipse Collections provide proper functional collections.

### Python

```python
# NamedTuple, frozen dataclass, or immutable Pydantic models
from dataclasses import dataclass

@dataclass(frozen=True)
class Order:
    id: str
    amount: Decimal
    status: OrderStatus
```

Tuples and frozensets are built-in immutable collections; lists and dicts are mutable but can be defensively copied.

### TypeScript / JavaScript

```typescript
// readonly properties
type Order = {
    readonly id: string;
    readonly amount: number;
    readonly status: OrderStatus;
};

// Updates with spread
const updated: Order = { ...original, status: 'SHIPPED' };
```

Library support: Immer, Immutable.js, fp-ts.

### Rust

Immutable by default — `let x = 5` is immutable; `let mut x = 5` is mutable. The compiler enforces. The whole system is designed around immutability + ownership.

### Kotlin

`val` (immutable) vs. `var` (mutable). `data class` for immutable records. Standard collections have separate mutable/immutable variants (`List` vs. `MutableList`).

## The structural sharing optimization

Naively, "produce a new copy on each change" sounds expensive. In practice, immutable collections use structural sharing — the new structure shares most of its data with the old, only the modified portion is new.

A 1000-element immutable list updated at index 500 doesn't copy 1000 elements; it shares both halves and references the new element. The cost is logarithmic in the structure size, not linear.

This is what makes immutable collections practical for production systems.

## When immutable is correct

### Domain models

Order, Customer, Address — these have identity and lifetime, but their attributes do not change in place. They get *replaced* with new versions.

### Configuration

Configuration data is set at startup and read throughout. Mutating it during runtime is almost always a bug.

### Cache values

A cache that returns a mutable object risks the caller modifying the cached value. Returning immutable values eliminates the risk.

### Cross-thread data

Anything shared between threads. Immutable + safe publication is dramatically simpler than locks.

### Function parameters

A function that takes a list and "modifies" it has implicit contracts ("can I keep using the list afterward?"). A function that takes an immutable list cannot modify it; the caller's assumptions are protected.

## When mutable is correct

### Performance-critical paths

Inner loops, large data manipulation. The allocation overhead matters; explicit mutation is faster.

### Streaming or accumulating data

Building a large structure incrementally, where the intermediate states are not exposed externally. A `StringBuilder` (mutable) is correct; a string-concatenation chain (immutable) is N²-time.

### Local working state

Within a function, a counter or accumulator can be mutable without affecting anyone else.

### Designed-for-mutation APIs

Some libraries are built around mutation (StringBuilder, ByteBuffer). Wrap them at the boundary with immutable interfaces if needed; do not fight them internally.

## Specific implementation patterns

### Builder pattern for immutable construction

For records or value objects with many fields, a builder lets you construct incrementally without making the result mutable.

```java
Order order = Order.builder()
    .id("abc")
    .amount(new BigDecimal("99.99"))
    .status(OrderStatus.PENDING)
    .build();
```

### "With" methods for derivation

Producing modified copies:

```java
Order shippedOrder = order.withStatus(OrderStatus.SHIPPED);
```

Each `withX` returns a new instance with one field changed.

### Defensive copying at boundaries

When interacting with mutable APIs, copy on the way in and the way out:

```java
public ImmutableData(MutableSource source) {
    this.data = List.copyOf(source.data); // copy on the way in
}

public List<X> getData() {
    return List.copyOf(this.data); // copy on the way out
}
```

This is the price of working with mutable code; minimize the surface area.

### Persistent data structures

Libraries that provide proper functional collections with structural sharing. Vavr, Eclipse Collections (immutable), Immutable.js, Clojure-style structures.

For systems doing significant immutable manipulation, these are dramatically faster than copy-on-every-change.

## Common failure patterns

- **Pretending to be immutable while exposing mutable internals.** A `getList()` method that returns a mutable list defeats the purpose.
- **Defensive copying everywhere.** If everything is genuinely immutable, no defensive copying is needed.
- **Mutable shared state across threads.** Almost always a bug; almost always solved by making the state immutable.
- **Avoiding immutability for "performance" without measuring.** Allocation cost is usually invisible; structural sharing makes it cheaper than naive copying anyway.
- **Force-immutability in inappropriate places.** Local accumulators, `StringBuilder`-style operations are fine to mutate.

## Adoption strategies

For an existing codebase moving toward more immutability:

1. **Start with new code**: new types are immutable; new methods take immutable inputs
2. **Convert leaf data types**: Order, Customer, etc. — these change shape rarely and have many readers
3. **Push mutation to edges**: large algorithms have a mutable internal phase; expose immutable interfaces
4. **Refactor "mutate this list" functions to "return a new list"** as you encounter them
5. **Use language affordances**: Java records, Python frozen dataclasses, TypeScript readonly

The change does not need to be all-at-once. Each immutable boundary added pays off independently.

## Further Reading

- [FunctionalProgrammingPrinciples](FunctionalProgrammingPrinciples) — The broader paradigm
- [CleanCodePrinciples](CleanCodePrinciples) — Immutability as code-quality property
- [JavaRecordsAndSealedClasses](JavaRecordsAndSealedClasses) — Java's immutable-data primitive
- [JavaCollectionsFramework](JavaCollectionsFramework) — Mutable and immutable collections in Java
- [DebuggingStrategies](DebuggingStrategies) — Why immutable data is easier to debug
- [SoftwareEngineeringPractices Hub](SoftwareEngineeringPracticesHub) — Cluster index
