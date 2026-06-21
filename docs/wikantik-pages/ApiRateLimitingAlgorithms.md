---
auto-generated: false
type: article
status: active
cluster: software-architecture
date: '2026-04-26'
title: API Rate Limiting Algorithms
hubs:
- SoftwareArchitectureHub
tags:
- rate-limiting
- token-bucket
- algorithms
- api-design
- redis
summary: Technical deep-dive into rate limiting algorithms (Token Bucket, Sliding
  Window) and atomic implementation strategies using Redis Lua.
canonical_id: 01KQ12YDS4PEG0YNE5737WDGJA
---

Rate limiting protects APIs from resource exhaustion and abuse by enforcing a ceiling on the number of requests a caller can make within a specified temporal window.

## Core Algorithms

### 1. Token Bucket
The bucket has a fixed capacity $C$. Tokens are added at a constant rate$r$per second. Each request consumes one token. If the bucket is empty, the request is rejected.
- **Math:**$\text{tokens} = \min(C, \text{tokens}_{old} + (t_{now} - t_{last}) \times r)$- **Benefit:** Allows for bursts (up to capacity$C$) while maintaining an average rate$r$. The standard for high-performance APIs (AWS, Stripe).

### 2. Leaky Bucket
Requests enter a FIFO queue and are processed at a constant rate. If the queue is full, new requests are dropped.
- **Benefit:** Smooths traffic bursts into a steady stream (Traffic Shaping).

### 3. Fixed Window Counter
Counts requests in discrete time intervals (e.g., 1-minute blocks).
- **Flaw:** The "Boundary Spike." A user can send their full quota at the end of window$N$and again at the start of window$N+1$, doubling the allowed rate in a short burst.

### 4. Sliding Window Counter
A hybrid approach that weights the count of the previous window by the overlap fraction.
- **Math:**$\text{count} = \text{current\_window\_count} + \text{previous\_window\_count} \times (1 - \text{fraction\_of\_current\_window\_elapsed})$- **Benefit:** Eliminates boundary spikes with minimal memory overhead ($O(1)$).

## Atomic Implementation (Redis + Lua)

Rate limiting must be atomic to prevent race conditions in distributed systems. Using a Lua script ensures the "Read-Modify-Write" cycle is indivisible.

**Token Bucket Lua Snippet:**
```lua
local key = KEYS[1]
local rate = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens = tonumber(bucket[1]) or capacity
local last_refill = tonumber(bucket[2]) or now

local delta = math.max(0, now - last_refill)
tokens = math.min(capacity, tokens + (delta * rate))

if tokens < 1 then
    return 0
else
    tokens = tokens - 1
    redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)
    return 1
end
```

## Comparison Table

| Algorithm | Precision | Bursts Allowed | Memory | Use Case |
|---|---|---|---|---|
| **Fixed Window** | Low | No | Low | Soft quotas |
| **Sliding Log** | High | Yes | High | Strict accuracy |
| **Sliding Counter**| Medium | Yes | Low | Default high-volume |
| **Token Bucket** | High | Yes | Low | Tiered API access |

## Response Headers (RFC 6585)
Always return standard headers to allow clients to self-throttle:
```http
HTTP/1.1 429 Too Many Requests
Retry-After: 30
X-RateLimit-Limit: 1000
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1735340000
```

## Operational Strategies
- **Client Identification:** Use `API-Key` or `JWT.sub` for authenticated users; fallback to `X-Forwarded-For` (IP) for anonymous traffic.
- **Tiered Limiting:** Assign buckets based on customer tier ($C_{enterprise} > C_{free}$).
- **Global vs. Local:** Use local in-memory limiters for$P99$ latency, but synchronize to a central Redis store periodically to prevent distributed under-counting.
