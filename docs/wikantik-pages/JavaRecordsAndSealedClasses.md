---
canonical_id: 01KQ0P44RBMJ37JR1J1PXPDC57
title: Java Records and Sealed Classes
type: article
cluster: java
status: active
date: '2026-04-26'
summary: Records as immutable data carriers, sealed classes for restricted inheritance,
  and the combination that gives Java a working sum-type system — when each fits and
  the patterns that have stuck.
tags:
- java
- records
- sealed-classes
- immutability
- data-modeling
related:
- JavaTwentyOneFeatures
- JavaCollectionsFramework
- ImmutableDataPatterns
- JavaStreamsAndFunctionalProgramming
- DesignPatternsHub
hubs:
- JavaHub
---
# Java Records and Sealed Classes

Records (Java 14+ preview, 16 stable) and sealed classes (Java 17 stable) together added Java's clearest improvements to data modeling in over a decade. Records replace the boilerplate of value classes; sealed classes restrict inheritance; the combination provides effective sum types with pattern matching support.

This page is about how each works, when to use them, and the patterns that have stuck.

## Records

A record is an immutable data class with automatically-generated boilerplate:

```java
public record Point(int x, int y) {}
```

This generates:
- A constructor `Point(int x, int y)`
- Accessor methods `x()` and `y()` (note: not `getX()` — record style)
- `equals()`, `hashCode()`, `toString()`
- Implements `Record` interface

The fields are `final`. The record is shallowly immutable.

### When records fit

- Data Transfer Objects (DTOs)
- Value objects in domain modeling
- Tuples (named, typed multi-value returns)
- Immutable configuration containers
- Event payloads

### When records don't fit

- Mutable objects (records are final-fields; cannot have setters)
- Objects with significant behavior beyond data access
- Inheritance hierarchies (records are implicitly final)
- Objects requiring null fields with setter access

### Custom validation in records

Compact constructor for validation:

```java
public record Email(String address) {
    public Email {
        if (address == null || !address.contains("@")) {
            throw new IllegalArgumentException("Invalid email");
        }
    }
}
```

### Records and serialization

Records work well with most serialization libraries (Jackson, Gson). The record syntax is recognized; field names are derived from accessor names.

For JPA: records cannot be JPA entities (entities require non-final fields and no-arg constructors). Use records as DTOs, with conversion to entities at the boundary.

## Sealed classes and interfaces

A sealed class or interface restricts which classes can extend it:

```java
public sealed interface Shape permits Circle, Rectangle, Triangle {}

public record Circle(double radius) implements Shape {}
public record Rectangle(double width, double height) implements Shape {}
public record Triangle(double base, double height) implements Shape {}
```

The `permits` clause names exactly the allowed subclasses. They must be in the same package or module.

### Why sealed matters

Without sealed:
- Anyone can subclass; the type hierarchy is open-ended
- Pattern matching cannot be exhaustive

With sealed:
- The compiler knows all possible subtypes
- Pattern matching can be exhaustively checked at compile time
- API contracts are clearer (the document lists the cases)

### Combining sealed + records: sum types

```java
public sealed interface Result<T, E> {
    record Ok<T, E>(T value) implements Result<T, E> {}
    record Err<T, E>(E error) implements Result<T, E> {}
}

// Usage with pattern matching
Result<Integer, String> result = parse(input);
switch (result) {
    case Result.Ok<Integer, String>(Integer value) -> useValue(value);
    case Result.Err<Integer, String>(String error) -> handleError(error);
}
```

This is Java's version of Rust's `Result` or Haskell's `Either` — explicit success/failure types with exhaustive handling at compile time.

## Patterns that have stuck

### Domain modeling with sealed + records

Domain types as a sealed hierarchy of records:

```java
public sealed interface OrderState
    permits Pending, Confirmed, Shipped, Delivered, Cancelled {}

public record Pending(Instant createdAt) implements OrderState {}
public record Confirmed(Instant confirmedAt) implements OrderState {}
public record Shipped(Instant shippedAt, String trackingNumber) implements OrderState {}
public record Delivered(Instant deliveredAt) implements OrderState {}
public record Cancelled(Instant cancelledAt, String reason) implements OrderState {}
```

The state machine is encoded in the type system. Pattern matching is exhaustive; adding a state forces the compiler to flag all switches.

### DTO records for serialization boundaries

Replacing handwritten DTO classes with records. Less code, less risk of forgetting equals/hashCode, simpler review.

### Immutable configuration containers

```java
public record DatabaseConfig(String url, String username, int maxConnections) {}
```

Configuration that cannot be mutated; validation in compact constructor; clean serialization.

### Result types

Replacing exception-based error handling at internal boundaries:

```java
public sealed interface ValidationResult {
    record Valid<T>(T value) implements ValidationResult {}
    record Invalid(List<String> errors) implements ValidationResult {}
}
```

Less ceremony than exceptions; explicit handling required.

## Common failure patterns

- **Treating records as JPA entities.** They aren't; conversion is needed at persistence boundaries.
- **Mutable wrappers over record fields.** Defeats the immutability.
- **Adding too much behavior to records.** Records are data; complex behavior usually belongs elsewhere.
- **Deep inheritance under sealed.** Sealed works for shallow hierarchies; deep nesting becomes confusing.
- **Forgetting the package/module restriction on sealed permits.** Permitted subclasses must be in the same package or module.

## Further Reading

- [JavaTwentyOneFeatures](JavaTwentyOneFeatures) — The broader 21 feature set
- [JavaCollectionsFramework](JavaCollectionsFramework) — Pairing records with collections
- [ImmutableDataPatterns](ImmutableDataPatterns) — Why immutable defaults matter
- [JavaStreamsAndFunctionalProgramming](JavaStreamsAndFunctionalProgramming) — Records in functional pipelines
- [Design Patterns Hub](DesignPatternsHub) — Patterns expressible with sealed + records
- [Java Hub](JavaHub) — Cluster index
