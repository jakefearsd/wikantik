---
canonical_id: 01KQ0P44VGYBZRN98HE0Z09YW2
title: "Data Access Abstraction: The Repository Pattern"
type: article
cluster: design-patterns
status: active
date: '2026-05-22'
summary: Mediating between the domain and data mapping layers. Explores the Repository as a collection-like interface and its role in enforcing Aggregate boundaries.
tags:
- repository-pattern
- design-patterns
- data-access
- domain-driven-design
related:
- FactoryPattern
- SpecificationPattern
- JpaAndHibernatePatterns
auto-generated: false
---

# Data Access Abstraction: The Repository Pattern

The **Repository Pattern** mediates between the domain and data mapping layers using a collection-like interface for accessing domain objects. It encapsulates the set of objects persisted in the data store and the operations performed over them, providing a more object-oriented view of the persistence layer.

## I. The Collection Analogy
A Repository should feel like a `List` or `Set` of entities. You shouldn't see `save()`, but rather `add()`. You shouldn't see `update()`, because the repository manages the lifecycle of the objects it has returned.

## II. Enforcing Aggregate Boundaries
In [Domain-Driven Design](DomainDrivenDesign), repositories are created only for **Aggregate Roots**.
*   **Correct:** `PageRepository` (Page is a root).
*   **Incorrect:** `RevisionRepository` (Revision is part of the Page aggregate; it should be accessed via `page.getRevisions()`).

## III. Concrete Example: Optimized JDBC Repository
Sometimes an ORM (like JPA) generates inefficient SQL for complex domain queries. A Repository allows you to drop down to custom JDBC while keeping the domain layer clean.

```java
public class JdbcWikiPageRepository implements PageRepository {
    private final JdbcTemplate jdbc;

    @Override
    public List<WikiPage> findRecent(int limit) {
        // Hand-tuned SQL for performance
        String sql = "SELECT p.* FROM pages p JOIN revisions r ON p.id = r.page_id " +
                     "WHERE r.date > NOW() - INTERVAL '7 days' LIMIT ?";
        
        return jdbc.query(sql, pageRowMapper, limit);
    }
}
```

## IV. Technical Integrity: Repository vs. DAO

| Feature | Data Access Object (DAO) | Repository |
| :--- | :--- | :--- |
| **Focus** | Table-centric (CRUD for a table) | Domain-centric (Collection of Entities) |
| **Language** | Persistence (SQL, ResultSets) | Domain (Entities, Specifications) |
| **Mapping** | Close to the DB schema | Translates to/from Domain Model |

## V. Strategic Guidelines

1.  **Don't Over-Abstract:** If your repository is just a 1:1 wrapper for `JpaRepository<User, String>`, you are adding ceremony without value. Use the framework directly until you need custom logic.
2.  **Specifications for Queries:** Instead of adding `findByXAndYAndZ` methods, use the [Specification Pattern](SpecificationPattern) to keep the repository interface lean and composable.
3.  **No Business Logic:** Repositories are for storage and retrieval only. They should never contain business rules or validation logic; that belongs in the Aggregate Root or a Domain Service.

---
**See Also:**
- [Domain Driven Design](DomainDrivenDesign) — Aggregates and Entities.
- [Specification Pattern](SpecificationPattern) — Composable query predicates.
- [JPA and Hibernate](JpaAndHibernatePatterns) — Common implementation technology.
