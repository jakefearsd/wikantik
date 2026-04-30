---
canonical_id: 01KQ0P44QVR6F9KSQ13K5XSVYP
title: HATEOAS and Hypermedia APIs
type: article
cluster: web-services-and-apis
status: active
date: '2026-04-26'
summary: Why HATEOAS sounded good in theory and rarely shipped in practice — what
  the constraint actually requires, the cases where hypermedia helps, and the modern
  tools (HTMX) that revived parts of the idea.
tags:
- hateoas
- hypermedia
- rest
- api-design
- htmx
related:
- ApiProtocolComparison
- GraphQlFundamentals
hubs:
- WebServicesAndApisHub
---
# HATEOAS and Hypermedia APIs

HATEOAS — Hypermedia As The Engine Of Application State — is the constraint that makes "REST" fully RESTful in Roy Fielding's original definition. The idea: API responses include links to next actions; clients follow links rather than constructing URLs from documentation.

In practice, HATEOAS rarely shipped. Most "REST" APIs in production are JSON-over-HTTP without hypermedia. This page explains what HATEOAS actually requires, why it didn't take off for traditional APIs, and where hypermedia is making a comeback.

## What HATEOAS actually requires

A HATEOAS response includes links:

```json
{
    "id": "order-123",
    "amount": 100.00,
    "status": "pending",
    "_links": {
        "self": { "href": "/orders/order-123" },
        "cancel": { "href": "/orders/order-123/cancel", "method": "POST" },
        "ship": { "href": "/orders/order-123/ship", "method": "POST" }
    }
}
```

The client examines available links to determine actions. If `cancel` is missing, the order can't be cancelled. The state of the resource determines what the client can do.

The promise: clients don't hard-code URLs; they navigate from a single entry point following links. Server can change URLs without breaking clients. New actions become available by appearing in responses.

## Why it didn't ship

Several reasons HATEOAS rarely became dominant:

### Clients didn't follow links

In practice, client developers read API documentation and construct URLs directly. HATEOAS-style navigation means writing code that interprets `_links` — more work for less control. Most clients prefer the documented approach.

### Tooling assumed direct URLs

Code generators, OpenAPI tooling, client SDK generators — all assume the URL is the contract. HATEOAS requires runtime discovery; the tooling story is weaker.

### The flexibility wasn't actually used

The promise of "the server can change URLs freely" wasn't exercised. Servers don't change URLs because clients depend on them, not because clients are link-following but because clients are URL-constructing. The flexibility HATEOAS offered didn't match real workflows.

### Documentation still required

Even with hypermedia, clients need to know what each link relation means. The documentation work isn't reduced; it's reorganized. The benefit didn't materialize.

## Where hypermedia is making a comeback

Hypermedia ideas have resurged in specific contexts:

### HTMX

HTMX is a JavaScript library that lets HTML attributes drive AJAX behavior. The server returns HTML; HTMX swaps it into the page. The "API" is the HTML response.

```html
<button hx-post="/orders/123/cancel" hx-swap="outerHTML">Cancel</button>
```

The server returns the new HTML for that section. Clients don't parse JSON and rebuild UI — they just swap server-rendered fragments. This is hypermedia in spirit: the server tells the client what to do via the response itself.

### Server-driven UI

Mobile apps and frontends that take rendering instructions from the server, not just data. Common in some apps (Instagram, Airbnb feeds) for rapid feature iteration.

### Hypermedia for AI agents

Recent work on AI agents using hypermedia: the agent reads what's available and chooses actions. Different from human-driven clients; the agent has no preconceived URL knowledge.

## Where pure HATEOAS still doesn't fit

For traditional API consumption (mobile app, frontend, third-party integration), the URL-as-contract model works fine and HATEOAS adds complexity without payoff.

For high-performance services, the verbose hypermedia format adds overhead.

For machine-to-machine integration, schemas (OpenAPI, gRPC, GraphQL) are more useful than runtime link discovery.

## A reasonable position

Most APIs should:
- Use REST conventions (HTTP semantics, resource URLs)
- Document URLs explicitly in OpenAPI or similar
- Skip HATEOAS unless there's a specific reason

Some interfaces benefit from hypermedia ideas:
- Server-rendered web apps (HTMX, Turbo)
- Interactive workflows where state determines available actions
- AI-agent interfaces

The HATEOAS-as-required-for-REST argument is technically correct but practically irrelevant. Most "REST" APIs that ship don't satisfy HATEOAS, and they're fine.

## Common failure patterns

- **Treating HATEOAS as the test of "true REST."** Most successful APIs don't satisfy it; they're still useful.
- **Adding `_links` to responses without clients using them.** Cargo cult.
- **Not adding hypermedia where it would help.** HTMX-style server-driven UI works well in narrow domains.

## Further Reading

- [ApiProtocolComparison](ApiProtocolComparison) — REST in context
- [GraphQlFundamentals](GraphQlFundamentals) — Schema-driven alternative
- [WebServicesAndApis Hub](WebServicesAndApisHub) — Cluster index
