---
canonical_id: 01KQ0P44WSNAHQSBHZXYCXQEBY
title: "Composable Predicates: The Specification Pattern"
type: article
cluster: design-patterns
status: active
date: '2026-05-22'
summary: Encapsulating business rules as composable predicates. This guide explores the Specification pattern for dynamic query building and validation logic.
tags:
- specification-pattern
- design-patterns
- composable-predicates
- jpa-criteria
- domain-rules
related:
- FactoryPattern
- RepositoryPattern
- FunctionalProgrammingPrinciples
auto-generated: false
---

# Composable Predicates: The Specification Pattern

The **Specification Pattern** is used to encapsulate a business rule as a single, reusable boolean predicate. These specifications can be combined using logical operators (AND, OR, NOT) to build complex, dynamic business rules or database queries.

## I. The Problem: Method Explosion
Without specifications, repositories often end up with a "Method Explosion":
`findByStatus`, `findByStatusAndType`, `findByStatusAndTypeAndDate`...

The Specification pattern solves this by allowing the client to pass a single, composed predicate to a generic `findAll(Specification spec)` method.

## II. Type-Safe Composition
Specifications are essentially objects that implement a "matches" or "isSatisfiedBy" method.

```java
public interface Specification<T> {
    boolean isSatisfiedBy(T candidate);

    default Specification<T> and(Specification<T> other) {
        return candidate -> this.isSatisfiedBy(candidate) && other.isSatisfiedBy(candidate);
    }
}
```

## III. Concrete Example: Dynamic Search with JPA Criteria
In a Spring Data environment, we use `org.springframework.data.jpa.domain.Specification` to map domain rules directly to SQL.

```java
public class PageSpecs {
    public static Specification<WikiPage> isType(String type) {
        return (root, query, cb) -> cb.equal(root.get("type"), type);
    }

    public static Specification<WikiPage> inCluster(String cluster) {
        return (root, query, cb) -> cb.equal(root.get("cluster"), cluster);
    }
}

// Usage in Service
Specification<WikiPage> search = PageSpecs.isType("article")
                                    .and(PageSpecs.inCluster("java"));
List<WikiPage> results = repository.findAll(search);
```

## IV. Technical Integrity and Use Cases

1.  **Validation:** Use Specifications to validate domain objects before saving.
    `if (!orderSpecs.canShip().isSatisfiedBy(order)) { throw ... }`
2.  **Selection:** Filter in-memory collections using the same logic used for database queries.
3.  **Refactor for Reusability:** If a rule like "Is Authoritative" appears in search, sidebar logic, and API filters, it **must** be a Specification to ensure consistency across the system.
4.  **Performance:** Be careful with the JPA Criteria API. It is powerful but can generate inefficient SQL if predicates are nested too deeply or if joins are not managed correctly.

---
**See Also:**
- [Repository Pattern](RepositoryPattern) — Consuming specifications.
- [Functional Programming Principles](FunctionalProgrammingPrinciples) — The theoretical foundation of predicates.
- [Design Patterns Hub](DesignPatternsHub) — Architectural pattern index.
