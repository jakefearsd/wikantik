---
canonical_id: 01KQ0P44RBMJ37JR1J1PXPDC57
title: "Java ADTs: Records and Sealed Classes"
type: article
cluster: java
status: active
date: '2026-05-22'
summary: A practitioner's guide to Algebraic Data Types (ADTs) in Java using Records and Sealed Classes. Covers sum types, product types, and exhaustive pattern matching.
tags:
- java
- records
- sealed-classes
- adt
- functional-programming
related:
- JavaTwentyOneFeatures
- JavaCollectionsFramework
- ImmutableDataPatterns
- DesignPatternsHub
auto-generated: false
---

# Java ADTs: Records and Sealed Classes

The combination of **Records** (Product Types) and **Sealed Classes** (Sum Types) allows Java to support formal **Algebraic Data Types (ADTs)**. This shift enables developers to move domain invariants into the type system, replacing runtime checks with compile-time safety.

## I. Product Types: Records
A Record is a **Product Type** because its state space is the product of its components.
*   **Immutability by Design:** Fields are `final`. Accessors are provided.
*   **Transparent Data:** Unlike traditional Beans, Records "are" their data. The JVM can optimize them more aggressively (e.g., potential stack allocation in future versions).

```java
// Product Type: State space = int * String
public record User(int id, String name) {}
```

## II. Sum Types: Sealed Classes
A Sealed class/interface is a **Sum Type** because its state space is the sum of its permitted subtypes.
*   **Restricted Hierarchy:** The `permits` clause closes the hierarchy.
*   **Exhaustiveness:** The compiler can verify that every possible subtype is handled in a `switch` expression.

## III. Concrete Pattern: The Domain State Machine

Encoding an order lifecycle as an ADT prevents invalid states (e.g., a "Cancelled" order having a "TrackingNumber").

### The Model
```java
public sealed interface OrderState 
    permits Created, Shipped, Delivered, Cancelled {}

public record Created(Instant timestamp) implements OrderState {}

public record Shipped(Instant timestamp, String trackingId) implements OrderState {}

public record Delivered(Instant timestamp, String signedBy) implements OrderState {}

public record Cancelled(Instant timestamp, String reason) implements OrderState {}
```

### Exhaustive Processing (Java 21)
```java
public String getDisplayStatus(OrderState state) {
    return switch (state) {
        case Created c -> "Order placed at " + c.timestamp();
        case Shipped s -> "In transit. Tracking: " + s.trackingId();
        case Delivered d -> "Delivered to " + d.signedBy();
        case Cancelled c -> "Cancelled: " + c.reason();
        // No 'default' needed! Adding a new state will break compilation here.
    };
}
```

## IV. Record Deconstruction and Guards

Java 21 allows deconstructing records directly in the `case` label, including **When Guards**.

```java
public void process(OrderState state) {
    switch (state) {
        case Shipped(var ts, var id) when id.startsWith("FEDEX") -> 
            trackViaFedex(id);
        case Shipped(var ts, var id) -> 
            trackGeneric(id);
        default -> {}
    }
}
```

## V. Strategic Guidelines

1.  **Prefer ADTs over the Visitor Pattern:** The Visitor Pattern was a workaround for the lack of sum types. Sealed + Switch is more readable and less boilerplate-intensive.
2.  **Compact Constructors for Invariants:** Use the compact constructor to enforce business rules.
    ```java
    public record PositiveAmount(double value) {
        public PositiveAmount {
            if (value <= 0) throw new IllegalArgumentException();
        }
    }
    ```
3.  **Records are NOT Entities:** Do not use Records as JPA Entities. JPA requires a no-arg constructor and mutable fields. Use Records for DTOs and value objects.

---
**See Also:**
- [Java 21 Features](JavaTwentyOneFeatures) — Broader context on pattern matching.
- [Immutable Data Patterns](ImmutableDataPatterns) — Why ADTs are the foundation of safe systems.
- [Design Patterns Hub](DesignPatternsHub) — Modernizing GOF patterns with ADTs.
