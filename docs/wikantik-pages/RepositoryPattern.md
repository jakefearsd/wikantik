---
canonical_id: 01KQ0P44VGYBZRN98HE0Z09YW2
title: Repository Pattern
type: article
cluster: design-patterns
status: active
date: '2026-04-26'
summary: The Repository pattern as data-access abstraction — what it does, where it
  earns its place vs. where ORMs already provide it, and the practical trade-offs
  in modern Java/Spring code.
tags:
- repository-pattern
- design-patterns
- data-access
- domain-driven-design
related:
- FactoryPattern
- SpecificationPattern
- JpaAndHibernatePatterns
- JdbcBestPractices
- SpringBootFundamentals
hubs:
- DesignPatternsHub
---
# Repository Pattern

The Repository pattern abstracts data access. Domain code asks the repository for objects ("find order by ID"); the repository handles the database details. The domain layer doesn't know about SQL, JPA, or any specific data store.

The pattern was canonical in Domain-Driven Design. In modern Java with Spring Data, much of the work is done for you. The remaining question: when do you write your own repositories, and when do you stop and use the framework's?

## The original pattern

```java
public interface OrderRepository {
    Optional<Order> findById(String id);
    List<Order> findByStatus(OrderStatus status);
    void save(Order order);
    void delete(Order order);
}

public class JpaOrderRepository implements OrderRepository {
    private final EntityManager em;
    /* ... implementation ... */
}
```

Domain code uses the interface; the implementation lives in the persistence layer. Tests can substitute a fake.

## Spring Data JPA's version

```java
public interface OrderRepository extends JpaRepository<Order, String> {
    List<Order> findByStatus(OrderStatus status);
}
```

Spring generates the implementation at runtime. Method names are parsed; query is constructed. You write the interface; the framework provides the rest.

This is the common case. For typical CRUD plus simple finders, this is enough.

## When to write your own

Spring Data covers ~80% of repository needs. The cases where explicit implementations help:

### Complex queries

```java
public interface OrderRepository {
    List<Order> findOrdersWithItemTotalGreaterThan(BigDecimal threshold);
}
```

The method name parser doesn't handle this; either `@Query` annotation or custom implementation is needed.

### Performance-critical paths

For hot paths where you want explicit control, custom JDBC or jOOQ inside a repository implementation.

### Cross-store data assembly

If a "repository" pulls from multiple stores (database + cache + external API), the abstraction is doing real work.

### Aggregate-rooted operations

In DDD, the repository operates on aggregate roots, not arbitrary entities. The repository enforces aggregate boundaries — only certain operations are permitted.

## When the pattern is overkill

For trivial CRUD, the repository is just a wrapper over Spring Data. The abstraction adds nothing:

```java
// You're not really hiding anything
public class OrderService {
    private final OrderRepository repository;

    public Order findById(String id) {
        return repository.findById(id).orElseThrow();
    }
}
```

If the service does nothing the repository doesn't already do, the service is ceremony.

## Repository vs. Active Record

Two competing patterns:

- **Repository**: domain object is plain; repository handles persistence.
- **Active Record**: domain object knows how to save itself (`order.save()`).

Active Record (Rails-style) couples the domain to persistence. Repository decouples. In modern Java, Repository is dominant.

## Common failure patterns

- **Repository per entity, no real abstraction.** Each repository is a thin wrapper; the pattern adds nothing.
- **Generic Repository<T> for everything.** Over-abstraction; specific operations belong on specific repositories.
- **Repository methods that leak persistence concerns.** `findByJpqlQuery()` defeats the purpose.
- **Skipping repositories entirely in DDD codebases.** Domain code with persistence concerns mixed in.

## Further Reading

- [FactoryPattern](FactoryPattern) — Often paired with repositories
- [SpecificationPattern](SpecificationPattern) — Composable queries through repositories
- [JpaAndHibernatePatterns](JpaAndHibernatePatterns) — JPA implementation
- [JdbcBestPractices](JdbcBestPractices) — Lower-level alternative
- [SpringBootFundamentals](SpringBootFundamentals) — Spring Data conventions
- [DesignPatterns Hub](DesignPatternsHub) — Cluster index
