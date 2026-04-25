---
canonical_id: 01KQ12YDS4PEG0YNE5737WDGJA
title: Api Rate Limiting Algorithms
type: article
cluster: software-architecture
status: active
date: '2026-04-25'
tags:
- rate-limiting
- token-bucket
- leaky-bucket
- sliding-window
- api-design
summary: Token bucket, leaky bucket, fixed window, sliding window — what each
  guarantees, where each fails, and the implementation hooks (Redis, in-memory,
  cluster-wide) that decide whether your rate limiting actually works.
related:
- ApiSecurityPatterns
- ApiDesignBestPractices
- DistributedComputingAlgorithms
hubs:
- SoftwareArchitecture Hub
---
# API Rate Limiting Algorithms

Rate limiting is "how many requests can a caller make per unit time." It looks simple. The implementations are surprisingly subtle, and getting it wrong shows up as either the rate limit being too easy to game or being so strict that legitimate users hit limits they shouldn't.

The four canonical algorithms each make a different trade-off. Pick deliberately.

## Fixed window

Count requests in 1-second (or 1-minute, etc.) windows. Reset at the boundary.

```
At each request:
  current_count = redis.incr("rate:user_42:" + current_minute)
  if current_count > limit: reject
```

**Pros:** simple, cheap, one Redis op per request, intuitive.

**Cons:** the boundary problem. A user can send `limit` requests at second 59 of minute N and another `limit` requests at second 0 of minute N+1 — twice the limit in a one-second span.

**When to use:** soft, generous limits where the boundary spike doesn't matter ("100 calls per hour, but if you do 200 in two seconds across the boundary, it's OK").

## Sliding window log

Store every request timestamp; count timestamps in the last window.

```
At each request:
  redis.zadd("rate:user_42", now, request_id)
  redis.zremrangebyscore("rate:user_42", 0, now - window)
  count = redis.zcard("rate:user_42")
  if count > limit: reject
```

**Pros:** exact. No boundary problem.

**Cons:** memory-heavy at high RPS (one entry per request), more Redis ops per request.

**When to use:** very precise limits at moderate volume (few hundred RPS per user max).

## Sliding window counter

Hybrid: maintain counts for two adjacent windows; weight by overlap.

```
count = (count[previous_minute] * overlap_fraction) + count[current_minute]
```

If you're 30 seconds into a minute, weight the previous minute's count by 0.5 and add the current minute's count.

**Pros:** approximates exact sliding-window with fixed-window memory.

**Cons:** slightly less accurate than the log approach. Approximation is acceptable for most use cases.

**When to use:** the default for high-volume APIs. Cheap, accurate enough, no boundary problem. Most production rate limiters land here.

## Token bucket

Each user has a bucket of `capacity` tokens that refills at `rate` per second. Each request consumes a token. Empty bucket = reject.

```
Each request:
  tokens, last_refill = redis.hgetall("rate:user_42")
  elapsed = now - last_refill
  tokens = min(capacity, tokens + elapsed * rate)
  if tokens < 1: reject
  tokens -= 1
  redis.hset("rate:user_42", tokens=tokens, last_refill=now)
```

**Pros:** allows bursts up to `capacity` while enforcing average rate of `rate`. Matches what users actually want — "I should be able to send a quick burst, then a steady stream." Well-suited for bursty real workloads.

**Cons:** stateful; needs atomicity (Lua script in Redis to avoid race conditions).

**When to use:** the right default for most user-facing APIs. AWS, Stripe, GitHub all use variants.

## Leaky bucket

Same idea as token bucket from a different framing: requests fill a bucket at variable rate; the bucket drains at fixed rate. Overflow = reject.

In practice, leaky bucket and token bucket are equivalent in their typical implementations. Leaky-bucket is sometimes implemented as an actual FIFO queue of requests, processed at the leak rate — which is more like *traffic shaping* than rate limiting. If you care about smoothing bursts (not just rejecting them), this is the algorithm.

## Distributed concerns

A rate limiter has to work across however many app servers handle the user's traffic. Two failure modes:

**Per-instance counters undercount.** If the user hits load balancer node A 10 times and node B 10 times, neither sees 20. Limit not enforced.

**Centralised counters become bottlenecks.** Every request waits on Redis. Adds latency; Redis becomes a single point of failure.

The middle path:

- **Centralised** for low-RPS cases (Redis with INCR or token-bucket Lua script).
- **Decentralised with reconciliation** for high-RPS — each instance has a local counter with a small local burst budget; periodically reconciles with a central counter. Approximate but scales.
- **Per-shard rate limiting** — partition users to shards, each shard owns its rate limits. Common at very large scale.

For most teams under 50K RPS total: Redis-backed with Lua is fine. Past that, decentralised approaches start earning their complexity.

## What to limit by

- **API key / user ID** — most common. Identifies the caller.
- **IP address** — fallback for anonymous traffic. Trickier with NAT, proxies, mobile carriers (many users behind one IP).
- **Endpoint** — different limits per endpoint (login is stricter than search).
- **Tier** — paid customers get higher limits than free.
- **Cost** — limit by an abstract "cost" instead of count. A simple GET costs 1; an expensive aggregation costs 10. Smooths out variable-cost endpoints.

Most production systems combine these — limit by `(user, endpoint, tier)` with multipliers.

## What to do when limited

The 429 response with helpful headers:

```
HTTP/1.1 429 Too Many Requests
Retry-After: 30
X-RateLimit-Limit: 1000
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1735340000
```

`Retry-After` is the contract. Seconds (or HTTP-date). Honest. Clients honour it.

For client SDKs, **honour `Retry-After` literally**. Don't add jitter beyond what the header says; don't exponential-backoff faster than the server requested. Naive exponential retries are how you DDoS your own service when the rate limiter is degraded.

## DDoS vs rate limiting: not the same problem

Rate limiting per legitimate user defends against legitimate-user abuse and accidental DOS. It doesn't defend against:

- High-volume distributed attacks from many IPs / synthetic accounts.
- Layer-3 / layer-4 floods (SYN, UDP, amp).
- Slow-loris and similar slowloris-style attacks.

For DDoS protection, you need WAF + CDN + provider-level mitigation (Cloudflare, AWS Shield, Akamai). Rate limiting in your application is the *last* layer, not the first.

## Common mistakes

**Limiting by IP only.** Mobile users behind NAT trip the limit collectively. Limit by authenticated user where possible; IP only as fallback.

**Hard-coded limits in the application.** Limits should be configurable and per-tenant. Hard-coded means changing them is a deploy.

**No way to raise a limit for a customer.** Some customer asks; they're a paying enterprise; you can't accommodate without a code change. Build in a tier system.

**Rejecting silently.** Drop the request without a 429. Clients have no idea what happened; they retry blindly. Always 429 with `Retry-After`.

**Counters that grow unboundedly.** Keys that include timestamps / IDs and never expire. Set TTLs.

**Counting before authorisation.** A rate limiter that runs after auth means unauthenticated requests don't hit it; abusers send unauthenticated requests freely. Limit at the edge.

## Practical reference

A reasonable starter for a public API:

- 60 requests/minute per anonymous IP (login, signup, public endpoints).
- 1000 requests/minute per authenticated free user.
- 10000 requests/minute per authenticated paid user.
- Per-endpoint multipliers: writes count 5×, reads count 1×.
- Token-bucket implementation in Redis with Lua script.
- 429 with Retry-After on rejection.
- Per-tier limits configurable via admin UI.

Tune from observed traffic; expect to adjust quarterly.

## Tools

- **Redis with Lua** — the standard substrate.
- **Envoy / Istio rate limiting** — at the proxy / mesh layer; no app code.
- **NGINX `limit_req` / `limit_conn`** — at the web server. Crude but effective.
- **Cloudflare / Fastly rate limiting** — edge layer; protects origin.
- **Kong / Tyk / Apigee** — API gateways with built-in rate limiting.
- **token-bucket libraries** in every language — for in-process limiting.

For most teams: edge layer (Cloudflare) for abuse protection, application layer (Redis token bucket) for fair-use enforcement. Both, not either.

## Further reading

- [ApiSecurityPatterns] — auth and authorisation alongside rate limiting
- [ApiDesignBestPractices] — broader API design context
- [DistributedComputingAlgorithms] — for the distributed-counter algorithms
