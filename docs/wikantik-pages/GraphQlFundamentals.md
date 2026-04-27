---
canonical_id: 01KQ0P44QSZSY9VWFA3MN53S29
title: GraphQL Fundamentals
type: article
cluster: web-services-and-apis
status: active
date: '2026-04-26'
summary: How GraphQL works — schema, queries, mutations, subscriptions, the N+1 problem
  and DataLoader, and the trade-offs that determine when GraphQL is the right tool.
tags:
- graphql
- api
- schema
- queries
- dataloader
related:
- ApiProtocolComparison
- HateoasAndHypermediaApis
- PaginationStrategies
- IdempotencyPatterns
hubs:
- WebServicesAndApis Hub
---
# GraphQL Fundamentals

GraphQL is a query language and runtime for APIs. The client specifies what data it needs; the server returns exactly that. A single endpoint replaces dozens of REST endpoints. The schema is first-class; tooling is excellent. The trade-off: more complex than REST.

This page is about how GraphQL works in practice and when to use it.

## Schema

A GraphQL schema defines types and their fields:

```graphql
type Order {
    id: ID!
    amount: Float!
    status: OrderStatus!
    customer: Customer!
    items: [OrderItem!]!
}

enum OrderStatus {
    PENDING
    SHIPPED
    DELIVERED
    CANCELLED
}

type Query {
    order(id: ID!): Order
    orders(status: OrderStatus, limit: Int = 50): [Order!]!
}

type Mutation {
    createOrder(input: CreateOrderInput!): Order!
    cancelOrder(id: ID!): Order!
}
```

The schema is the contract. Clients query it; servers implement it.

## Queries

Clients request specific fields:

```graphql
query {
    order(id: "abc") {
        id
        amount
        customer {
            name
            email
        }
    }
}
```

Response contains only the requested fields. No over-fetching.

### Aliases

Same field, different arguments:

```graphql
query {
    pending: orders(status: PENDING) { id }
    shipped: orders(status: SHIPPED) { id }
}
```

### Fragments

Reusable field selections:

```graphql
fragment OrderSummary on Order {
    id
    amount
    status
}

query {
    order(id: "1") { ...OrderSummary }
    orders { ...OrderSummary }
}
```

### Variables

Parameterized queries (the right way; not string interpolation):

```graphql
query GetOrder($id: ID!) {
    order(id: $id) { id amount }
}
```

## Mutations

Operations that modify data:

```graphql
mutation {
    createOrder(input: { customerId: "abc", amount: 100.00 }) {
        id
        status
    }
}
```

Same schema/query mechanics as queries; convention separates them so it's clear what modifies state.

## Subscriptions

Real-time data via WebSocket (typically):

```graphql
subscription {
    orderStatusChanged(orderId: "abc") {
        id
        status
    }
}
```

The connection stays open; the server pushes events. See [WebSocketPatterns](WebSocketPatterns).

## The N+1 problem

GraphQL's flexibility creates a server-side performance issue. Consider:

```graphql
query {
    orders {
        id
        customer { name }
    }
}
```

Naive resolution:
1. Fetch all orders (1 query)
2. For each order, fetch its customer (N queries)

Total: N+1 queries.

### DataLoader

The standard solution. DataLoader batches requests within a single tick of the event loop:

```javascript
const customerLoader = new DataLoader(async (ids) => {
    const customers = await db.customers.findByIds(ids);
    return ids.map(id => customers.find(c => c.id === id));
});

// In resolver:
customer: (order) => customerLoader.load(order.customerId)
```

Each `load()` call queues the ID; at the end of the tick, all queued IDs are fetched in one query. N+1 becomes 2.

DataLoader is essential for any non-trivial GraphQL server.

## Authorization

Field-level authorization is a real complication. Each field can have different permissions; the resolver enforces them.

Two patterns:

- **Per-field directives**: `@auth(role: ADMIN)` annotations on schema fields
- **Resolver-level checks**: each resolver verifies permissions explicitly

For complex authorization, schema-level approaches help; for simple cases, resolver checks are clearest.

## Pagination

Three styles, in order of decreasing convenience and increasing scalability:

### Offset/limit

```graphql
orders(limit: 50, offset: 100): [Order!]!
```

Simple but inefficient at deep pages.

### Cursor-based

```graphql
orders(first: 50, after: "cursor123"): OrderConnection!

type OrderConnection {
    edges: [OrderEdge!]!
    pageInfo: PageInfo!
}
```

Relay-style. Faster for deep pagination; preserves order under inserts.

### Page-based

```graphql
orders(page: 5, perPage: 50): [Order!]!
```

Familiar; less efficient than cursor-based.

See [PaginationStrategies](PaginationStrategies).

## When GraphQL wins

- Mobile and web clients with different data needs
- Complex nested data fetching
- Multiple frontends sharing a backend
- Strong typing and schema-driven workflows

## When REST wins

- Simple CRUD apps
- Public APIs with diverse client capabilities
- HTTP caching is critical
- Team unfamiliar with GraphQL

See [ApiProtocolComparison](ApiProtocolComparison).

## Common failure patterns

- **No DataLoader.** N+1 queries kill performance.
- **Field-level authorization in many places.** Centralize via directives or middleware.
- **Returning all fields by default.** GraphQL's value is selective fetching; resolvers should respect that.
- **Mutating in queries.** Mutations should be in `Mutation`, not as side-effecting `Query` resolvers.
- **Schema evolution as breaking changes.** Add fields freely; deprecate before removing; never remove without warning.
- **Client-side caching neglect.** Apollo Client and similar provide normalized caching; use it.

## Further Reading

- [ApiProtocolComparison](ApiProtocolComparison) — REST vs. GraphQL
- [HateoasAndHypermediaApis](HateoasAndHypermediaApis) — REST hypermedia approach
- [PaginationStrategies](PaginationStrategies) — Pagination in GraphQL
- [IdempotencyPatterns](IdempotencyPatterns) — Idempotency for mutations
- [WebServicesAndApis Hub](WebServicesAndApis+Hub) — Cluster index
