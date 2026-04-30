---
canonical_id: 01KQ0P44P1P00XJ2ZA3A81T8NM
title: CORS Deep Dive
type: article
cluster: networking
status: active
date: '2026-04-26'
summary: How CORS actually works — the same-origin policy, preflight requests, the
  headers that matter, and the configuration patterns that work in production.
tags:
- cors
- same-origin-policy
- web-security
- http
- networking
related:
- HttpTwoAndHttpThree
- WebServicesAndApisHub
- WebApplicationFirewalls
hubs:
- NetworkingHub
---
# CORS Deep Dive

Cross-Origin Resource Sharing (CORS) is the browser mechanism that controls when JavaScript on one origin can access resources on another. It's confusing because the protections are mostly browser-imposed (servers usually allow everything; browsers block).

This page is about how CORS actually works and the configuration patterns.

## The same-origin policy

By default, browsers prevent JavaScript on `https://app.example.com` from making requests to `https://api.example.com`. The "origin" is scheme + host + port.

The protection is for users, not servers. The point: a malicious page can't read your bank's data even if you're logged in.

## Why CORS exists

Without CORS, every cross-origin XHR/fetch would be blocked. Modern web apps make many cross-origin requests legitimately (calling APIs, embedding fonts, loading images). CORS is the mechanism for the server to opt into allowing specific cross-origin requests.

## How CORS works

### Simple requests

GET, HEAD, or POST with simple content types (form-data, text/plain) and no special headers can be sent immediately. The browser includes:

```
Origin: https://app.example.com
```

The server responds with:

```
Access-Control-Allow-Origin: https://app.example.com
```

(Or `*` for any origin, with limitations.)

If the response has the right header, the browser lets JS read it. If not, the browser blocks JS access (the request was sent and the response received, but JS can't read it).

### Preflight requests

For requests that aren't simple (DELETE, PUT, custom headers, JSON content type), the browser sends an OPTIONS preflight request first:

```http
OPTIONS /api/orders
Origin: https://app.example.com
Access-Control-Request-Method: DELETE
Access-Control-Request-Headers: Authorization
```

Server responds:

```http
HTTP/1.1 204 No Content
Access-Control-Allow-Origin: https://app.example.com
Access-Control-Allow-Methods: GET, POST, DELETE
Access-Control-Allow-Headers: Authorization, Content-Type
Access-Control-Max-Age: 3600
```

The browser then sends the actual request.

The preflight is cached per the Max-Age; subsequent requests skip it.

## The major headers

### `Access-Control-Allow-Origin`

The origin that's allowed. Either a specific origin or `*` (any).

`*` doesn't work with credentials (cookies, auth headers). For authenticated cross-origin requests, you must specify the origin.

### `Access-Control-Allow-Credentials`

If `true`, cookies and auth headers can be sent. Requires specific Allow-Origin (not `*`).

### `Access-Control-Allow-Methods`

Methods the resource supports. For preflight responses.

### `Access-Control-Allow-Headers`

Headers the request can include. For preflight responses.

### `Access-Control-Expose-Headers`

Headers JS can read. By default, only a subset of response headers are visible to JS; this header expands the set.

### `Access-Control-Max-Age`

How long preflight result is cached. Longer means fewer preflights.

## Patterns that work

### API gateway with explicit allowed origins

```
Allow-Origin: comes from a list — production-app.example.com, staging.example.com, localhost:3000 for dev
```

Each request's Origin header is checked against the list; matching origin is echoed back. Not in list: no header, browser blocks.

This is the right pattern for production APIs.

### Wildcard for public resources

```
Access-Control-Allow-Origin: *
```

For genuinely public APIs (read-only, no credentials needed), wildcard is fine.

### Subdomain handling

If you control multiple subdomains:

```
*.example.com → all subdomains allowed
```

Implemented by checking the Origin header; not by literal wildcard in the header value (browsers don't support wildcards in Allow-Origin beyond `*`).

## Common configuration errors

### Returning multiple Allow-Origin headers

Browsers reject. Specify one origin (echoed from request) or `*`.

### Wildcard with credentials

```
Access-Control-Allow-Origin: *
Access-Control-Allow-Credentials: true
```

Browser rejects. Use specific origin.

### Forgetting OPTIONS

The preflight needs a 200/204 response. If the API returns 401 for OPTIONS (because not authenticated), CORS fails.

Configure the framework to short-circuit OPTIONS to a CORS-aware handler before auth.

### Cache headers ignored

Different origins might have different CORS rules. If responses are cached, the browser might return the wrong CORS headers. Use `Vary: Origin` to inform caches.

## CORS isn't security

A common misunderstanding: CORS protects the server. It doesn't.

CORS protects users from malicious scripts in their browser reading data they shouldn't. The server still receives every request; the browser is what blocks JS access to responses.

Server-side authentication and authorization are still required. CORS is in addition to, not instead of.

## CORS in different scenarios

### Browser → public API

Standard CORS. Origin specific or wildcard.

### Browser → authenticated API

Specific origin; allow-credentials true; cookies or auth headers.

### Server → server

CORS doesn't apply. Servers don't enforce same-origin policy. CORS is browser-only.

### Mobile apps → API

Mobile apps don't have a browser; CORS doesn't apply. The app's HTTP client just makes the request.

### CLI tools → API

Same — no CORS.

## Common failure patterns

- **Setting `Access-Control-Allow-Origin: *` with credentials.** Doesn't work; pick specific origin.
- **OPTIONS not handled.** Preflight fails; actual request never happens.
- **Caching CORS responses without Vary.** Wrong headers cached.
- **Treating CORS as security.** It's not; server-side auth still needed.
- **Adding CORS in code path-by-path.** Centralize at middleware/gateway level.
- **Different CORS for different endpoints.** Maintenance nightmare; standardize.

## Further Reading

- [HttpTwoAndHttpThree](HttpTwoAndHttpThree) — Underlying HTTP
- [Web Services and APIs Hub](WebServicesAndApisHub) — APIs that use CORS
- [WebApplicationFirewalls](WebApplicationFirewalls) — Edge layer
- [Networking Hub](NetworkingHub) — Cluster index
