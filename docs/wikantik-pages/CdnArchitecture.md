---
canonical_id: 01KQ0P44MZ8C2W84BXAB1R8FS0
title: CDN Architecture
type: article
cluster: cloud-platforms
status: active
date: '2026-04-26'
summary: How CDNs work — edge caching, origin servers, cache invalidation, and the
  patterns for delivering both static and dynamic content with edge acceleration.
tags:
- cdn
- edge-computing
- cloudfront
- caching
- performance
related:
- CloudStorageOptions
- AwsFundamentals
- HttpTwoAndHttpThree
- WebApplicationFirewalls
hubs:
- CloudPlatformsHub
---
# CDN Architecture

A Content Delivery Network (CDN) caches content at edge locations close to users. The result: lower latency, less origin load, better resilience. The major CDNs (CloudFront, Cloudflare, Akamai, Fastly) work similarly; the differences are operational and pricing.

This page is about how CDNs work and the patterns for using them.

## The basic model

```
User → Edge cache (closest to user) → Origin (your servers)
```

When a user requests content:
1. Edge checks if it has the content cached and fresh
2. If yes: serves immediately (cache hit)
3. If no: fetches from origin, caches, serves

A high cache hit ratio means most requests are served from edge — fast and cheap.

## What benefits from CDN

### Static assets

Images, CSS, JavaScript, fonts. These are immutable for their lifetime; cache them aggressively. The hit ratio approaches 100%.

### Public API responses

Read-heavy public APIs benefit from edge caching. Product catalogs, pricing pages, public data feeds.

### Video and streaming

Video benefits enormously — massive files repeated to many viewers. Specialized streaming CDN features (HLS, DASH).

### Static websites

S3 + CloudFront is the standard pattern. Fast, cheap, scales infinitely.

### Software downloads

Large files; high concurrency. CDN distributes the load.

## What doesn't benefit (or benefits less)

### Personalized content

If every user gets different content, edge caching is harder. Patterns: cache shared parts; deliver personalized parts via API; assemble client-side.

### Real-time data

Stock tickers, live scores, current state. The TTL needs to be very short, reducing cache effectiveness.

### Heavy POST traffic

CDNs primarily accelerate GET. POSTs go through to origin (with some exceptions).

## Cache control

The CDN respects HTTP cache headers from origin:

```http
Cache-Control: public, max-age=31536000, immutable
```

- `public`: cacheable by CDN
- `max-age=31536000`: cache for 1 year
- `immutable`: never re-validate (the content can never change)

For mutable content:

```http
Cache-Control: public, max-age=300, s-maxage=3600
```

- Cache 5 minutes for browsers
- Cache 1 hour for CDN

The `s-maxage` is CDN-specific; useful when you want longer CDN caching than browser caching.

## Cache invalidation

When content changes, cached versions are stale. Three approaches:

### TTL expiry

Just wait for the cache to expire. Fine for non-critical content. Long TTLs amplify the staleness problem.

### Explicit invalidation

`POST /distribution/{id}/invalidations` (CloudFront equivalent). Tells the CDN to mark URLs stale.

Pros: immediate.
Cons: invalidation is slow (5-10 minutes at scale); some CDNs charge per invalidation.

### URL versioning (cache-busting)

Don't invalidate; use new URLs:

```
/static/main.abc123.css
/static/main.def456.css  (after change)
```

The URL itself changes; old URL is left in cache. Build tools (webpack, Vite) handle this. The recommended pattern for static assets.

## Origin patterns

### Single origin

Most apps. CDN points to one origin.

### Multi-origin

Different paths route to different origins. Path-based routing at edge. Useful for split deployments.

### Origin failover

Primary + secondary origin. CDN tries primary first; falls back to secondary on failure. For high availability.

### Origin shield

An intermediate cache between edge and origin. Reduces origin load when many edges miss simultaneously.

## Edge compute

Modern CDNs run code at edge:

- **CloudFront Functions**: small JS for header manipulation
- **Lambda@Edge**: Lambda functions at CloudFront edges
- **Cloudflare Workers**: V8 isolates at every edge
- **Fastly Compute**: WASM-based edge compute

Use cases:
- A/B testing at edge
- Authentication at edge
- Personalization assembly
- Edge-rendering for SEO

Edge compute is a real shift from "CDN as cache" to "CDN as platform."

## Security at the edge

CDNs typically include:
- DDoS protection (mitigation at edge before reaching origin)
- WAF (Web Application Firewall) — see [WebApplicationFirewalls](WebApplicationFirewalls)
- Rate limiting
- Bot detection
- Origin shielding (origin not directly accessible)

## CDN selection

| CDN | Strengths |
|-----|-----------|
| **CloudFront** | Tight AWS integration; pay-as-you-go |
| **Cloudflare** | Strong DDoS protection; generous free tier; edge compute |
| **Akamai** | Enterprise; comprehensive features |
| **Fastly** | Real-time logs; powerful VCL config; popular with media |
| **Bunny.net** | Cheap; simple |

For AWS shops, CloudFront is usually the default. For edge compute or aggressive security, Cloudflare. For ultra-customizable behavior, Fastly.

## Common failure patterns

- **No CDN for static assets.** Slower; more origin load.
- **Caching personalized content.** User A sees user B's data.
- **Long TTLs without invalidation strategy.** Stale content during emergencies.
- **No URL versioning.** Forced to invalidate constantly.
- **Origin still publicly accessible.** CDN bypass possible.
- **Caching cookies-aware content.** Privacy issues.

## Further Reading

- [CloudStorageOptions](CloudStorageOptions) — S3 + CloudFront patterns
- [AwsFundamentals](AwsFundamentals) — AWS context
- [HttpTwoAndHttpThree](HttpTwoAndHttpThree) — Protocol-level efficiency
- [WebApplicationFirewalls](WebApplicationFirewalls) — Security at CDN
- [CloudPlatforms Hub](CloudPlatformsHub) — Cluster index
