---
canonical_id: 01KQ0P44RDC80WCMCM2CPGT77R
title: JDBC Best Practices
type: article
cluster: java
status: active
date: '2026-04-26'
summary: Working with JDBC directly — connection management, prepared statements,
  batching, transaction handling, and the patterns that make raw JDBC sustainable
  even when JPA is also available.
tags:
- java
- jdbc
- database
- sql
- connection-pool
related:
- JpaAndHibernatePatterns
- SpringBootFundamentals
- DatabaseConnectionSecurity
- TaxonomyDesignPrinciples
hubs:
- JavaHub
---
# JDBC Best Practices

JDBC is Java's lowest-level database API. Higher-level tools (JPA, Spring Data) sit on top of it. Despite the prevalence of ORMs, raw JDBC remains useful for: performance-critical paths, complex queries the ORM doesn't express well, batch operations, and integration tests.

This page covers the patterns that make raw JDBC code sustainable.

## Connection management

JDBC connections are expensive. Open, close per operation produces production-grade scalability problems quickly.

### Always use a connection pool

Production code should use a connection pool (HikariCP is the standard; Spring Boot uses it by default). The pool keeps a fixed number of connections open and lends them out on request.

Direct JDBC code should never do `DriverManager.getConnection()` per request — that opens a new connection every call.

### Try-with-resources

```java
try (Connection conn = dataSource.getConnection();
     PreparedStatement stmt = conn.prepareStatement(sql);
     ResultSet rs = stmt.executeQuery()) {

    while (rs.next()) {
        // process row
    }
}
```

Resources close automatically. Replaces the verbose try-finally pattern. Always use this; never manually close JDBC resources.

## Prepared statements

```java
// Wrong: SQL injection vulnerable
String sql = "SELECT * FROM orders WHERE customer_id = '" + customerId + "'";

// Right: parameterized
String sql = "SELECT * FROM orders WHERE customer_id = ?";
try (PreparedStatement stmt = conn.prepareStatement(sql)) {
    stmt.setString(1, customerId);
    try (ResultSet rs = stmt.executeQuery()) {
        // ...
    }
}
```

Parameterized queries:
- Prevent SQL injection
- Enable database query plan caching
- Faster on repeated execution

Always use prepared statements. Hand-built SQL strings are a vulnerability waiting to happen.

## Batching

For bulk inserts or updates:

```java
String sql = "INSERT INTO orders (id, amount) VALUES (?, ?)";
try (PreparedStatement stmt = conn.prepareStatement(sql)) {
    for (Order order : orders) {
        stmt.setString(1, order.id());
        stmt.setBigDecimal(2, order.amount());
        stmt.addBatch();
    }
    stmt.executeBatch();
}
```

Dramatic speedup vs. individual INSERTs — often 10-100× for typical workloads. The exact speedup depends on the database and network.

For PostgreSQL specifically, additional batching options:
- `reWriteBatchedInserts=true` connection parameter
- `INSERT ... ON CONFLICT` with multi-row VALUES

## Transactions

```java
conn.setAutoCommit(false);
try {
    // multiple statements
    insertOrder(conn, order);
    insertOrderItems(conn, items);
    conn.commit();
} catch (Exception e) {
    conn.rollback();
    throw e;
}
```

Transaction management at the JDBC level is verbose; framework-level transaction management (Spring's `@Transactional`) is usually preferred for application code.

For batch operations, transactions are essential — committing every row defeats batching.

## Result set handling

```java
try (ResultSet rs = stmt.executeQuery()) {
    while (rs.next()) {
        String id = rs.getString("id");
        BigDecimal amount = rs.getBigDecimal("amount");
        OrderStatus status = OrderStatus.valueOf(rs.getString("status"));
        // ...
    }
}
```

Notes:
- Get by column name, not index, for clarity
- `rs.next()` returns false at end; the loop must handle empty results
- Type-safe getters (`getString`, `getInt`, `getBigDecimal`) match the database type

For null handling, primitives are tricky:

```java
int count = rs.getInt("count");
if (rs.wasNull()) {
    // count was actually NULL in the database
}
```

Or use `getObject(...)` and check for null directly.

## Mapping ResultSet to objects

Three approaches:

### Manual

```java
private Order mapRow(ResultSet rs) throws SQLException {
    return new Order(
        rs.getString("id"),
        rs.getBigDecimal("amount"),
        OrderStatus.valueOf(rs.getString("status"))
    );
}
```

Verbose but explicit. Errors are clear.

### Spring's JdbcTemplate

```java
List<Order> orders = jdbcTemplate.query(sql, args, this::mapRow);
```

Reduces the connection management boilerplate. Still requires manual mapping.

### Reflection-based libraries

JOOQ, jOOQ, MyBatis, etc. Generate or reflect-map; less code, more magic. Trade-offs depend on library.

## Specific patterns

### Pagination

```sql
SELECT * FROM orders
ORDER BY created_at, id
LIMIT 100 OFFSET 200
```

For most cases, OFFSET is fine. For deep pagination, cursor-based pagination is faster:

```sql
SELECT * FROM orders
WHERE (created_at, id) > (?, ?)
ORDER BY created_at, id
LIMIT 100
```

### Streaming large result sets

For result sets too large to load into memory, use `setFetchSize` and stream:

```java
stmt.setFetchSize(1000);
try (ResultSet rs = stmt.executeQuery()) {
    while (rs.next()) {
        // process row, don't accumulate
    }
}
```

The driver fetches rows in batches; memory stays bounded.

### IN clauses with large lists

```java
// Bad: SQL with many parameters fails
String sql = "WHERE id IN (?, ?, ?, ..., ?)"; // up to 100s of placeholders

// Better: temp table or array parameter (database-specific)
```

PostgreSQL supports array parameters: `WHERE id = ANY(?::uuid[])`. Other databases vary.

### Database time and locale

`PreparedStatement.setTimestamp()` and `ResultSet.getTimestamp()` interact with timezone conversion. Be deliberate about which timezone the database stores in (usually UTC) and how the application converts.

## Common failure patterns

- **Not using prepared statements.** SQL injection, plus performance.
- **Connection leaks.** Connections not returned to pool. Try-with-resources prevents this.
- **N+1 queries.** A loop of `SELECT` calls is much slower than one batched query.
- **Catching SQLException without context.** The exception class is not very informative; wrap with context.
- **Bulk operations without batching.** Slow at scale.
- **Mixing transaction management styles.** JDBC-level and framework-level transactions can conflict.

## Further Reading

- [JpaAndHibernatePatterns](JpaAndHibernatePatterns) — ORM that sits on top of JDBC
- [SpringBootFundamentals](SpringBootFundamentals) — Spring's data access conventions
- [DatabaseConnectionSecurity](DatabaseConnectionSecurity) — Connection security practices
- [Java Hub](JavaHub) — Cluster index
