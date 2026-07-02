# Public API Rate Limiting — Design

**Date:** 2026-07-02 · **Status:** approved (user, 2026-07-02)

## Problem

The public HTTP surface has no in-app rate limiting. MCP endpoints have `McpRateLimiter`
and page saves have the spam limiter, but `/api/*` has none — and the costliest endpoint
is anonymous-callable: `/api/bundle?q=` runs a query embedding (Ollama inference) plus
dense retrieval per request. `BackpressureFilter` (503 at ~390 concurrent in-flight) is
overload protection, not per-client fairness: one client looping `/api/bundle` never
approaches the concurrency cap while pinning the single-host inference tier. Cloudflare
can edge-limit, but `docker1:8080` bypasses CF and the app should defend itself.

## Decision

One servlet filter, two tiers, keyed per client IP (`getRemoteAddr()` — already correct
behind Cloudflare via the `RemoteIpValve` with `remoteIpHeader="CF-Connecting-IP"` in
`docker/config/server.xml` and the local deploy template).

| Tier | Paths | Per-client | Global |
|---|---|---|---|
| default | `/api/*`, `/id/*`, `/export/*` | 25 req/s | none (BackpressureFilter bounds aggregate) |
| expensive | `/api/bundle`, `/api/search`, `/sparql` | 3 req/s | 10 req/s |

- On limit: **429** + `Retry-After: 1`, a `SECURITY` log line, and a
  `wikantik_ratelimit_rejected_total{tier=...}` micrometer counter (mirrors
  `BackpressureFilter`'s metric pattern).
- **Exemptions:** loopback addresses by default (`InetAddress.isLoopbackAddress()`,
  covers `127.0.0.0/8` and `::1` — keeps `bin/eval/sweep-bm25-fusion.py` and local ops
  full-speed), plus an optional CIDR allowlist; exact path `/api/health` is skipped so
  monitoring can never be limited.
- **Config:** env vars with sane in-code defaults, matching `BackpressureFilter`'s idiom:
  `WIKANTIK_RATELIMIT_DEFAULT_PERCLIENT` (25), `WIKANTIK_RATELIMIT_EXPENSIVE_PERCLIENT` (3),
  `WIKANTIK_RATELIMIT_EXPENSIVE_GLOBAL` (10), `WIKANTIK_RATELIMIT_EXPENSIVE_PATHS`
  (CSV prefix list), `WIKANTIK_RATELIMIT_EXEMPT_CIDRS` (CSV, IPv4 CIDRs). A limit of 0
  disables that bucket. **Ships default-on.**
- **Out of scope:** `/wiki/*` SSR pages (429s to Googlebot would hurt SEO — CF +
  backpressure cover that surface); MCP endpoints (keep their existing limiter/config);
  `/tools/*` (API-key gated, low cost).

## Placement

- **`SlidingWindowRateLimiter`** (the former `McpRateLimiter`, verbatim mechanics —
  caffeine-cached per-client 1-second sliding windows + optional global bucket) moves to
  `wikantik-http` (`com.wikantik.http.ratelimit`). `wikantik-mcp-core` gains a
  `wikantik-http` dependency and uses it directly; `McpRateLimiter` is deleted (no
  duplicate logic block). DAG stays clean: http depends only on api/util/event.
- **`RateLimitFilter`** lives in `wikantik-observability` beside `BackpressureFilter` —
  that module has micrometer, the env-var config idiom, and `InternalNetworkFilter`'s
  IPv4 `CidrRange` matcher (extracted to a shared package-private class rather than
  duplicated). This deviates from the sketch's "beside the security filters in
  wikantik-http" for concrete reasons: http has neither micrometer nor caffeine today,
  and metrics were part of the approved behavior.
- **web.xml:** registered after `RequestMetricsFilter` (429s are measured) and before
  `BasicAuthFilter` (reject before any auth/DB work). URL patterns: `/api/*`, `/id/*`,
  `/export/*`, `/sparql`.

## Testing

- Unit: `SlidingWindowRateLimiterTest` (moved from admin-mcp with the class),
  `RateLimitFilterTest` — tier resolution, 429 + Retry-After semantics, loopback and
  CIDR exemption, `/api/health` skip, zero-disables, metric increment.
- Wire-level: a dedicated 429 IT is **impossible by design** — IT clients connect from
  loopback, which is exempt. The 429 path is fully unit-tested at the servlet contract;
  the entire existing REST IT suite acts as the no-false-positive regression guard
  (any spurious limiting reds the suite).

## Ops notes

- Prod tuning goes through `.env` → container env (docker1 already passes env through).
- Cloudflare edge rate rules remain a recommended *additional* layer, not a substitute.
