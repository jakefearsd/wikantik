---
title: Pagination Strategies
type: article
tags:
- pagin
- index
- record
summary: When designing an API endpoint that must handle potentially petabytes of
  records, the naive approach to limiting results is a recipe for catastrophic performance
  degradation.
auto-generated: true
---
# The Triad of Data Retrieval

For those of us who spend our professional lives wrestling with the mechanics of data access layers, pagination is not merely a UI concern; it is a fundamental architectural constraint that dictates scalability, performance characteristics, and the very integrity of the data contract between the service and the client. When designing an API endpoint that must handle potentially petabytes of records, the naive approach to limiting results is a recipe for catastrophic performance degradation.

This tutorial is not for the novice who merely needs to know `LIMIT` and `OFFSET`. We are addressing experts—architects, senior backend engineers, and database performance specialists—who require a granular, theoretical understanding of the trade-offs inherent in the three dominant pagination paradigms: Offset-based, Cursor-based, and Keyset-based retrieval.

Our goal is to move beyond the superficial "use Keyset" advice and instead build a robust, nuanced model for selecting the optimal retrieval mechanism based on the specific constraints of the underlying data model, indexing strategy, and required consistency guarantees.

***

## 1. Introduction: The Problem Space of Large Datasets

At its core, pagination is the mechanism by which a system presents a potentially infinite stream of results in manageable, finite chunks. When dealing with relational databases, the efficiency of retrieving the *N*th record, or the *N*th *batch* of records, is paramount.

The evolution of pagination strategies reflects a continuous battle against the inherent complexity of database query execution plans. Early methods were simple but fundamentally flawed under load. Modern solutions attempt to mimic the efficiency of sequential memory access (like iterating through an array) using the highly optimized, yet often opaque, mechanisms of SQL.

We will analyze these three strategies by examining their underlying SQL mechanics, their performance profiles under load, their resilience to data modification (writes), and their suitability for various data access patterns.

***

## 2. Strategy I: Offset-Based Pagination (The Antiquated Approach)

The most straightforward, and arguably the most academically simple, method is the use of `LIMIT` and `OFFSET`. This strategy is intuitive for developers because it maps directly to the concept of "skip $X$ records and take the next $Y$ records."

### 2.1. Mechanism Breakdown

In standard SQL dialects (including PostgreSQL, MySQL, etc.), the syntax is straightforward:

```sql
SELECT *
FROM large_table
ORDER BY primary_key
LIMIT 10 OFFSET 20; -- Skip the first 20 rows, return the next 10.
```

Here, `LIMIT` defines the page size ($Y$), and `OFFSET` defines the starting point ($X \times Y$).

### 2.2. The Theoretical Flaw: The Cost of Skipping

While this approach works perfectly for small datasets (e.g., retrieving the first 100 records), its performance profile degrades catastrophically as the `OFFSET` value increases. This degradation is not a theoretical concern; it is a measurable, resource-intensive operation on the database engine.

**The Core Issue: Physical Row Skipping.**

When you execute `LIMIT 10 OFFSET 1000000`, the database engine *must* perform the following steps:

1.  **Scan:** It must execute the `ORDER BY` clause across the entire dataset (or at least up to the 1,000,010th row).
2.  **Materialize:** It must physically read, process, and discard the first 1,000,000 rows.
3.  **Return:** Only after this massive I/O and CPU overhead is incurred does it finally return the 10 rows requested.

The database is not smart enough to say, "Since you asked for the 1,000,001st row, I will just jump there." It must process the intervening data to guarantee the ordering is correct. This process scales linearly with the `OFFSET` value, leading to $O(N)$ time complexity relative to the offset, where $N$ is the offset value.

### 2.3. Edge Cases and Data Integrity Concerns

Beyond performance, offset pagination suffers from severe data integrity issues when writes occur concurrently with reads.

**The "Phantom Row" Problem:**
Imagine a user is on Page 5, which starts at record ID 501. Before they click "Next," another user deletes record ID 502, or inserts a record with an ID that falls between 501 and 510.

When the system recalculates the next page using a simple offset, the resulting set of 10 records might:
1.  Skip the intended record entirely (if it was deleted).
2.  Contain a record that was previously on Page 6 (if the deletion caused a re-indexing or re-ordering that the offset calculation didn't account for).

In essence, the offset provides a *positional* guarantee, but the data itself is mutable, rendering the position unreliable.

***

## 3. Strategy II: Cursor-Based Pagination (The Pointer Concept)

Cursor-based pagination represents a significant conceptual leap over offset pagination. It shifts the focus from *position* (the $N$-th row) to *identity* (the specific record that marks the boundary).

### 3.1. Defining the Cursor

A cursor, in its purest form, is a unique, opaque pointer to a specific point in the result set. It is not merely the primary key; it is often a composite value derived from the sorting criteria that uniquely identifies the boundary between the last retrieved item and the next desired item.

The core principle is: **Instead of telling the database *how many* records to skip, you tell it *where* to start.**

### 3.2. The Mechanism: Boundary Identification

The implementation relies on the fact that the database can efficiently locate records based on indexed values. If you sort by `(timestamp, id)`, the cursor must encode enough information to reconstruct the starting point for the next query.

If the last record retrieved was `(timestamp_L, id_L)`, the next query must ask for records that are strictly *greater* than this boundary point.

**Conceptual Pseudocode:**

```
// Assume sorting by (created_at DESC, id DESC)
LAST_CURSOR = {created_at: '2023-10-26T10:00:00Z', id: 12345};

SELECT *
FROM large_table
WHERE (created_at < :LAST_CURSOR.created_at)
   OR (created_at = :LAST_CURSOR.created_at AND id < :LAST_CURSOR.id)
ORDER BY created_at DESC, id DESC
LIMIT 10;
```

### 3.3. Cursor vs. Keyset: A Crucial Distinction

This is where many resources conflate the terms, and an expert must draw a sharp line.

*   **Cursor (General Concept):** The abstract idea of a pointer to a location. It is the *interface* presented to the client.
*   **Keyset (Specific Implementation):** The concrete, optimized SQL pattern used to *implement* that pointer using indexed column values.

A cursor *can* be implemented using an offset (if the dataset is small), but when scaling, the cursor *must* be implemented using the Keyset pattern to maintain performance. Keyset pagination is, therefore, the industry-standard, high-performance realization of cursor-based pagination in relational databases.

### 3.4. Advantages of Cursor-Based Retrieval

1.  **Performance Stability:** The query execution time remains $O(\log N + K)$, where $N$ is the total dataset size (due to index lookups) and $K$ is the page size. It is independent of the page number requested.
2.  **Resilience to Writes:** Because the query filters on specific, immutable values (`WHERE column > value`), the insertion or deletion of records *before* the cursor point does not affect the validity of the boundary condition.

***

## 4. Strategy III: Keyset Pagination (The Optimized Standard)

Keyset pagination is the most robust, performant, and theoretically sound method for paginating large, ordered datasets within a relational database context. It is not just an alternative; it is the *optimized evolution* of cursor-based thinking applied directly to SQL indexing capabilities.

### 4.1. The Theoretical Foundation: Compound Indexing

The efficiency of Keyset pagination hinges entirely on the ability of the database query planner to utilize a **composite index** that matches the exact ordering criteria of the query.

If you sort by `(A, B, C)`, the database expects an index structured as `INDEX(A, B, C)`. The Keyset logic then translates the boundary point into a set of inequality comparisons that allow the engine to perform a highly efficient index seek, rather than a full table scan.

### 4.2. The Logic of Compound Comparison

When sorting by multiple columns, the comparison must be done lexicographically, column by column, from left to right, just as the database engine processes the index.

Let's assume we are paginating a `products` table sorted by `(category_id, created_at, product_id)` in ascending order.

*   **Last Retrieved Record (The Boundary):** $R_{last} = (C_{last}, T_{last}, P_{last})$
*   **Goal:** Find the next set of records $R_{next}$ such that $R_{next} > R_{last}$.

The SQL `WHERE` clause must translate this "greater than" logic into explicit, ordered comparisons:

```sql
WHERE
    -- 1. The primary sort column must be strictly greater
    category_id > :C_last
    OR
    -- 2. If the primary column matches, the secondary column must be greater
    (category_id = :C_last AND created_at > :T_last)
    OR
    -- 3. If the first two columns match, the final column must be greater
    (category_id = :C_last AND created_at = :T_last AND product_id > :P_last)
```

This structure is critical. It is not a simple `WHERE (col1 > X OR col2 > Y)`. It is a cascading series of `OR` conditions that perfectly mirror the lexicographical ordering defined by the index.

### 4.3. Handling Descending Order

The logic extends seamlessly to descending order, simply by flipping the comparison operators (`<` instead of `>`) and ensuring the boundary values are correctly passed.

If sorting `(A DESC, B DESC)`:
The next record must satisfy:
$$ (A < A_{last}) \text{ OR } (A = A_{last} \text{ AND } B < B_{last}) $$

### 4.4. The Performance Guarantee: Index Seek

When the database planner sees this structure, and assuming the composite index `(A, B, C)` exists, it does not scan the table. It uses the index structure itself to jump directly to the first record that satisfies the complex `WHERE` clause, achieving near $O(1)$ retrieval time relative to the dataset size, making it exceptionally fast even for deep pagination.

***

## 5. Comparative Analysis

To solidify the understanding, we must compare these strategies across several critical dimensions. This comparison is where the true architectural decision-making occurs.

| Feature | Offset-Based (`LIMIT/OFFSET`) | Cursor-Based (General) | Keyset-Based (Optimized) |
| :--- | :--- | :--- | :--- |
| **Mechanism** | Positional skipping (Count-based) | Boundary identification (Pointer-based) | Compound, indexed comparison (Value-based) |
| **SQL Syntax** | `LIMIT X OFFSET Y` | `WHERE primary_key > ?` (Simple) | Complex `WHERE` clause using `OR` logic |
| **Time Complexity (Deep Page)** | $O(N)$ (Linear in offset) | $O(\log N + K)$ (If simple PK used) | $O(\log N + K)$ (Index Seek) |
| **Write Resilience** | Very Poor (Prone to skipping/duplicates) | Good (If PK is used) | Excellent (Filters on specific values) |
| **Indexing Requirement** | None (But requires `ORDER BY` scan) | Requires index on the primary sort column. | **Requires a composite index** matching the sort order exactly. |
| **Complexity for Dev** | Low | Medium | High (Requires careful query construction) |
| **Best Use Case** | Small, non-critical datasets; initial prototyping. | Simple, single-column sorting (e.g., by UUID). | **Large-scale, high-throughput, mission-critical APIs.** |

### 5.1. The Failure Modes Revisited: When Things Go Wrong

#### A. The "All Data" Scenario (The Edge Case of the Last Page)
When retrieving the final page, the client needs to know if there are *more* records available.

*   **Offset:** You must run a separate `COUNT(*)` query, which is expensive.
*   **Cursor/Keyset:** You must check if the query returned *any* results. If the result set is empty, you assume you are at the end. This is clean and efficient.

#### B. Sorting by Non-Unique Fields (The Tie-Breaker Problem)
This is the most common pitfall when moving from simple pagination to complex ones.

If you sort by `(date, name)` and two records share the same date and name, the database engine must decide which one comes first. If you only use `WHERE date > :D_last`, you might skip the entire batch of records that share the date `:D_last`.

**The Solution:** Always include a unique, monotonically increasing, and indexed column (like a UUID or an auto-incrementing primary key) as the *final* tie-breaker in your sort order and your Keyset logic.

If sorting by `(date, name)`:
1.  **Sort Order:** `ORDER BY date ASC, name ASC, id ASC`
2.  **Keyset Logic:** The `WHERE` clause must cascade through all three fields, ensuring that if the first two match the boundary, the third field must be greater than the boundary's third field.

### 5.2. Advanced Consideration: Pagination Across Joins

When pagination is required on a derived set of data (i.e., joining `Users` to `Orders` to `Products`), the complexity multiplies because the sorting criteria must be consistent across all joined tables, and the cursor must encode the boundary for *every* joined table involved in the sort.

If you paginate based on the join result set, the composite key must incorporate the primary keys of all tables used in the `JOIN` clause, ensuring that the boundary point uniquely identifies the row combination across the entire join structure. This requires meticulous planning of the `ORDER BY` clause to match the `WHERE` clause's logic perfectly.

***

## 6. Implementation Deep Dives and Architectural Patterns

For an expert audience, the discussion must pivot from *what* the SQL is to *how* the service layer should manage this complexity.

### 6.1. The Service Layer Contract

The API contract must abstract away the underlying pagination mechanism. The client should ideally only need to provide a `page_token` (the cursor/keyset string) and a `limit`.

**Bad Contract (Exposing implementation details):**
`GET /api/items?page=3&limit=20` (Implies Offset)

**Good Contract (Abstracting the pointer):**
`GET /api/items?limit=20&after=eyJpZCI6IjEyMzQ1IiwidHJhbnNmb3JtYXRlIjoiaWQiLCJjYWxsYm93IjoiaWQi`
*(Where the token is a base64-encoded JSON object containing the boundary values.)*

### 6.2. Database Specific Optimizations (PostgreSQL Focus)

Since PostgreSQL is frequently cited in modern high-scale applications, specific optimizations are warranted.

1.  **Index Types:** Ensure the composite index is created *exactly* in the order of the `ORDER BY` clause.
    ```sql
    CREATE INDEX idx_composite_pagination ON large_table (col_a, col_b, col_c);
    ```
2.  **Index Bloat and Vacuuming:** Remember that high write volume can cause index bloat. Regular maintenance (VACUUM FULL or appropriate background vacuuming) is necessary to ensure the index remains compact and the planner can utilize it efficiently.
3.  **Materialized Views:** If the pagination is based on complex aggregations or joins that are read-heavy but write-infrequent, consider using a Materialized View. You paginate against the MV, and then periodically refresh the MV. This shifts the computational cost from read-time to maintenance-time.

### 6.3. Handling Time-Series Data (The Temporal Edge Case)

When dealing with time-series data, the combination of time and sequence is paramount. If you are paginating logs or sensor readings, the primary sort key should almost always be a combination of **Time + Unique ID**.

If you only sort by `timestamp`, and two events happen at the exact same millisecond (a common occurrence in distributed systems), the database might arbitrarily order them, leading to non-deterministic pagination. The inclusion of a unique ID (like a UUID or sequence number) as the secondary sort key is non-negotiable for correctness.

***

## 7. Conclusion: Selecting the Right Tool for the Job

To summarize this exhaustive review for the expert practitioner:

1.  **Avoid Offset Pagination:** Unless the dataset size is guaranteed to be small (e.g., under 10,000 records) and performance is irrelevant, treat `LIMIT/OFFSET` as a historical curiosity. Its $O(N)$ scaling makes it unsuitable for production systems expecting high throughput or deep pagination.
2.  **Embrace Cursor/Keyset:** For any scalable, production-grade API endpoint dealing with ordered data, Keyset pagination is the definitive choice. It leverages the database's core strength—indexed lookups—to provide $O(\log N)$ performance stability regardless of the page depth.
3.  **The Golden Rule:** The Keyset logic must perfectly mirror the `ORDER BY` clause, and the composite index must exist on the exact columns listed in the `ORDER BY` clause, in that precise order.

Mastering pagination is less about knowing the syntax and more about understanding the underlying computational cost of data retrieval. By adopting the Keyset strategy, you are not just implementing a feature; you are architecting for predictable, linear scalability in the face of massive data volume.

If you find yourself debating between the three, the answer, for any system that matters, is almost certainly the Keyset approach. Now, go build something that doesn't choke on its own success.
