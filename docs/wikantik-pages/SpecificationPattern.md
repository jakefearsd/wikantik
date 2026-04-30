---
canonical_id: 01KQ0P44WSNAHQSBHZXYCXQEBY
title: Specification Pattern
type: article
cluster: design-patterns
status: active
date: '2026-04-26'
summary: The Specification pattern for composable predicates — when domain rules are
  better expressed as objects than scattered booleans, and the practical implementations
  in modern Java.
tags:
- specification-pattern
- design-patterns
- composable-predicates
- domain-rules
related:
- FactoryPattern
- RepositoryPattern
- FunctionalProgrammingPrinciples
hubs:
- DesignPatternsHub
---
# Specification Pattern

A Specification is an object that encapsulates a single boolean predicate over a domain object. Specifications can be combined (and, or, not) into composite predicates. The pattern is most useful when domain rules are non-trivial and need to be queried, composed, or persisted.

## The basic form

```java
public interface Specification<T> {
    boolean isSatisfiedBy(T candidate);
}

public class HighValueOrderSpec implements Specification<Order> {
    private final BigDecimal threshold;

    public HighValueOrderSpec(BigDecimal threshold) {
        this.threshold = threshold;
    }

    @Override
    public boolean isSatisfiedBy(Order order) {
        return order.amount().compareTo(threshold) > 0;
    }
}
```

Use:
```java
Specification<Order> highValue = new HighValueOrderSpec(new BigDecimal("1000"));
if (highValue.isSatisfiedBy(order)) { /* ... */ }
```

## Composition

The point: specifications combine.

```java
public interface Specification<T> {
    boolean isSatisfiedBy(T candidate);

    default Specification<T> and(Specification<T> other) {
        return c -> this.isSatisfiedBy(c) && other.isSatisfiedBy(c);
    }

    default Specification<T> or(Specification<T> other) {
        return c -> this.isSatisfiedBy(c) || other.isSatisfiedBy(c);
    }

    default Specification<T> not() {
        return c -> !this.isSatisfiedBy(c);
    }
}
```

Now:
```java
Specification<Order> filter = new HighValueOrderSpec(new BigDecimal("1000"))
    .and(new RecentOrderSpec(Duration.ofDays(30)))
    .and(new CustomerInGoodStandingSpec().not());
```

The combined specification reads as a domain rule.

## When Specifications earn their place

### Multiple use sites for the same rule

If "high-value order" appears in 5 places — fraud detection, reporting, notifications, audit — make it a Specification. Future changes happen in one place.

### Composed rules with explosion of combinations

Without Specifications, a method that takes "active, high-value, has shipped" might be 4 booleans (active? high? shipped? other?). With Specifications, each is a named object; combinations are explicit.

### Rules that need to be queried, not just evaluated

Specifications can sometimes generate database queries (Spring Data has a `Specification<T>` for this exact purpose):

```java
public Specification<Order> highValue(BigDecimal threshold) {
    return (root, query, cb) -> cb.gt(root.get("amount"), threshold);
}
```

This builds a JPA Criteria query. Repositories filter by Specification, building SQL dynamically.

### Domain-driven design

In DDD, complex domain rules expressed as Specifications match the domain language. Domain experts can read them.

## Modern alternatives

In a language with first-class functions (Java 8+), much of what Specification provides is `Predicate<T>`:

```java
Predicate<Order> highValue = order -> order.amount().compareTo(threshold) > 0;
Predicate<Order> recent = order -> order.date().isAfter(LocalDate.now().minusDays(30));
Predicate<Order> filter = highValue.and(recent);
```

`Predicate<T>` has `and`, `or`, `negate` built-in. For most cases, this is sufficient.

When Specification (the named class) beats `Predicate`:

- Need to inspect the predicate (e.g., for query generation)
- Need named, reusable rules with state
- Need to attach metadata (descriptions, error messages)

For pure boolean evaluation, `Predicate<T>` is simpler.

## Use with repositories

Spring Data's `Specification<T>` is the framework version. Repositories accept Specifications:

```java
public interface OrderRepository extends JpaRepository<Order, String>, JpaSpecificationExecutor<Order> {}

List<Order> highValueRecent = repository.findAll(highValue.and(recent));
```

The query is built dynamically from the composed specifications. Avoids repository methods that take many optional parameters.

## Common failure patterns

- **Specifications for trivial rules.** `IsNotNullSpec` is overkill.
- **Specification class for one-time use.** A `Predicate<T>` is fine.
- **Specifications that don't compose.** If you can't `and`/`or` them, the abstraction adds nothing.
- **Specifications hiding important domain semantics.** A spec named `BusinessRule47` is not self-documenting.

## Further Reading

- [FactoryPattern](FactoryPattern) — Construct specifications via factories
- [RepositoryPattern](RepositoryPattern) — Repositories accept specifications
- [FunctionalProgrammingPrinciples](FunctionalProgrammingPrinciples) — Predicate-based alternative
- [DesignPatterns Hub](DesignPatternsHub) — Cluster index
