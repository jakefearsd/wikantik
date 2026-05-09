---
cluster: design-patterns
canonical_id: 01KQ0P44X0812TY4262DRAY5ZV
title: "Strategy Pattern: Behavioral Injection"
type: article
tags:
- strategy-pattern
- design-patterns
- behavioral-patterns
- lambdas
- functional-interfaces
summary: Decoupling algorithms from their context. This guide covers traditional class-based strategies, modern lambda-based injection, and the Enum Strategy pattern for type-safe behavioral selection.
related:
- DesignPatternsOverview
- FactoryPattern
- JavaStreamsAndFunctionalProgramming
auto-generated: false
date: '2026-05-22'
---

# Strategy Pattern: Behavioral Injection

The **Strategy Pattern** (or Policy Pattern) enables a class to select an algorithm's behavior at runtime. It defines a family of algorithms, encapsulates each one, and makes them interchangeable, allowing the algorithm to vary independently from the clients that use it.

## I. The Lambda Evolution
In modern Java, creating a full class hierarchy for every strategy is often overkill. Using **Functional Interfaces** and **Lambdas**, we can inject behavior directly into the context.

### Concrete Example: Functional Tax Calculator
```java
public class CheckoutService {
    // Strategy defined as a functional interface
    private ToDoubleBiFunction<Double, String> taxStrategy;

    public void setTaxStrategy(ToDoubleBiFunction<Double, String> strategy) {
        this.taxStrategy = strategy;
    }

    public double calculateTotal(double amount, String country) {
        double tax = taxStrategy.applyAsDouble(amount, country);
        return amount + tax;
    }
}

// Usage
service.setTaxStrategy((amt, c) -> c.equals("EU") ? amt * 0.21 : amt * 0.05);
```

## II. The Enum Strategy Pattern
For a fixed set of algorithms, an **Enum** can act as both the registry and the implementation provider. This is highly performant and type-safe.

```java
public enum DiscountPolicy {
    RETAIL {
        @Override public double apply(double price) { return price; }
    },
    VIP {
        @Override public double apply(double price) { return price * 0.80; }
    },
    LIQUIDATION {
        @Override public double apply(double price) { return price * 0.50; }
    };

    public abstract double apply(double price);
}
```

## III. Strategy vs. State
*   **Strategy:** Behavior is pluggable and usually stateless. The client "uses" a strategy.
*   **State:** Behavior depends on internal state transitions. The object "becomes" its state.

## IV. Technical Integrity

1.  **Avoid Strategy Proliferation:** Do not create a strategy for every minor `if` statement. The pattern is intended for substantial behavioral variations.
2.  **Performance:** Functional dispatch via lambdas is highly optimized by the JVM (often inlined), but the indirection does have a minor cost compared to a static `if/else`. Use it when flexibility is required, not as a default for all logic.
3.  **Context Passing:** A strategy often needs data from the `Context`. Prefer passing only the required data (as in the `TaxCalculator` above) rather than passing the whole `Context` object to avoid tight coupling.

---
**See Also:**
- [Design Patterns Hub](DesignPatternsHub) — Architectural index.
- [Factory Pattern](FactoryPattern) — Often used to select and return the correct Strategy.
- [Functional Programming in Java](JavaStreamsAndFunctionalProgramming) — Bedrock for lambda-based strategies.
