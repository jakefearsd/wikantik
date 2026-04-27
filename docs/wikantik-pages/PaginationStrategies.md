---
canonical_id: 01KQ0P44TE1M3ATEEZ45SVW4BM
title: Pagination Strategies
type: article
cluster: web-services-and-apis
status: active
date: '2026-04-26'
summary: Offset vs. cursor pagination, the ordering stability problem, and the patterns
  that hold up at scale — plus the specific edge cases that catch most pagination
  implementations.
tags:
- pagination
- api-design
- cursor
- offset
- ordering
related:
- ApiProtocolComparison
- BatchApiDesign
- GraphQlFundamentals
hubs:
- WebServicesAndApis Hub
---
# Pagination Strategies

Pagination is the API problem that looks easy until it isn't. The naive `offset` and `limit` work for small datasets; they break in specific ways at scale. Cursor-based pagination is more robust but adds complexity. The right choice depends on the use case.

This page is about the patterns and the edge cases.

## Offset/limit (the obvious approach)

```http
GET /api/orders?offset=100&limit=50
```

The server skips the first 100 results and returns the next 50.

### When it works

- Small to moderate datasets
- Static data (no inserts during pagination)
- Page-style UI (page 1, 2, 3...)

### When it breaks

#### Performance

For deep pages, the database must skip many rows:

```sql
SELECT * FROM orders ORDER BY created_at LIMIT 50 OFFSET 100000;
```

The database scans 100,050 rows to return 50. Cost is linear in offset.

#### Skipped or duplicated items

If items are inserted/deleted between page fetches, items shift:

- Page 1 returns items 1-50
- Item is inserted at position 1
- Page 2 returns items 51-100, but the original item 51 is now item 52 — appears on page 1 AND page 2 (or item 50 is skipped)

For mutable data, offset pagination is unstable.

## Cursor-based pagination

```http
GET /api/orders?after=eyJjcmVhdGVkX2F0Ijp...&limit=50
```

The cursor encodes "where you left off" — typically the sort key of the last item plus tie-breakers.

### Mechanics

```sql
SELECT * FROM orders
WHERE (created_at, id) > (?, ?)
ORDER BY created_at, id
LIMIT 50;
```

The condition is "after" the cursor. No skip; database uses index directly. O(log n) regardless of page depth.

### Cursor encoding

Typical: opaque base64-encoded JSON with the sort key:

```
eyJjcmVhdGVkX2F0IjoiMjAyNi0wNC0yNlQxMjowMDowMFoiLCJpZCI6ImFiYyJ9

Decoded: { "created_at": "2026-04-26T12:00:00Z", "id": "abc" }
```

Opaque to the client; server defines the format.

### When it works

- Stable for inserts/deletes (cursor is a position, not an offset)
- O(log n) at any depth
- Forward-only by default; bidirectional with both before/after cursors

### When it has costs

- Cannot jump to "page 50" — must paginate through
- More complex to implement
- Cursors can become invalid if the underlying sort changes

## Page-based (the API-friendly form)

```http
GET /api/orders?page=5&per_page=50
```

Equivalent to offset/limit (offset = page * per_page) but more familiar to clients. Same performance issues at depth.

For UI that shows "page 1, 2, 3..." this is what most users expect.

## The ordering problem

Pagination requires a stable order. Ambiguous order produces nondeterministic results:

```sql
-- Bad: ties are resolved nondeterministically
SELECT * FROM orders ORDER BY created_at LIMIT 50 OFFSET 100;
```

Two orders with the same `created_at` may appear in different orders across calls. Pagination breaks.

Always include a tie-breaker:

```sql
SELECT * FROM orders ORDER BY created_at, id LIMIT 50 OFFSET 100;
```

`id` (assumed unique) breaks ties. Pagination is now deterministic.

## Total counts

Including total count in the response is expensive:

```sql
SELECT COUNT(*) FROM orders WHERE ...;
```

For large tables, this is slow. Common compromises:

- **Approximate counts**: faster; "about 47,000 results"
- **Counts only on first page**: subsequent pages omit
- **No counts**: just "has more / no more" indicator

For UIs that show "Showing 50 of 1,234,567 results," consider whether the count is actually useful at scale.

## Specific patterns

### Relay-style cursor pagination

Used by GitHub, Shopify, others. Standard structure:

```graphql
type OrderConnection {
    edges: [OrderEdge!]!
    pageInfo: PageInfo!
    totalCount: Int  # optional
}

type OrderEdge {
    node: Order!
    cursor: String!
}

type PageInfo {
    hasNextPage: Boolean!
    hasPreviousPage: Boolean!
    startCursor: String
    endCursor: String
}
```

Bidirectional, cursor-based, with explicit page info. The standard for GraphQL pagination.

### Keyset pagination (SQL idiom)

Same idea as cursor; "keyset" is the term in SQL contexts:

```sql
WHERE (created_at, id) > (?, ?)
```

Uses index efficiently; no offset.

### Hybrid

Some APIs offer both: cursor for default, page-based as opt-in. Lets clients choose based on their UI.

## Common failure patterns

- **Offset for deep pagination.** Performance degrades; data inconsistency.
- **No tie-breaker in ORDER BY.** Nondeterministic; pagination broken.
- **Cursor encoded as the database row ID.** Tightly couples API to internal representation; cursors break on migration.
- **Cursors that include sensitive data.** A base64 cursor is opaque-looking but trivially decoded.
- **Different sort orders per page.** Cursor only valid for a specific sort.
- **Total counts on every page.** Expensive; often unnecessary.

## A reasonable default

For new APIs:
- Cursor-based pagination as primary
- Optional total count (with documented cost)
- Stable, indexed sort with tie-breakers
- Opaque cursor format the server controls

For legacy / migration:
- Keep page-based for UI compatibility
- Add cursor support for new use cases (large data, exports)

## Further Reading

- [ApiProtocolComparison](ApiProtocolComparison) — Pagination across protocols
- [BatchApiDesign](BatchApiDesign) — Bulk-write analog
- [GraphQlFundamentals](GraphQlFundamentals) — Pagination in GraphQL
- [WebServicesAndApis Hub](WebServicesAndApis+Hub) — Cluster index
