---
canonical_id: 01KQ0P44MZ8C2W84BXAB1R8FS0
title: CDN Architecture
type: article
cluster: cloud-platforms
status: active
date: '2026-04-26'
summary: Technical deep-dive into Content Delivery Network (CDN) mechanics, edge caching strategies, and origin shielding patterns.
auto-generated: false
tags:
- cdn
- edge-computing
- performance
- caching
- cloudfront
related:
- CloudStorageOptions
- AwsFundamentals
- HttpTwoAndHttpThree
- WebApplicationFirewalls
hubs:
- CloudPlatformsHub
---

A Content Delivery Network (CDN) is a distributed network of edge servers that cache and serve content to users based on geographic proximity, reducing latency and origin server load.

## The Edge Caching Model

When a user requests a resource, the CDN intercepts the request at the nearest **Point of Presence (PoP)**.
1.  **Cache Hit:** Resource is served from the PoP with sub-$20\text{ms}$ latency.
2.  **Cache Miss:** PoP fetches from the **Origin**, caches it, and then serves the user.

### Cache-Control Strategy

The CDN behavior is governed by HTTP response headers from the origin.

| Header Value | Effect | Use Case |
|---|---|---|
| `public, max-age=31536000, immutable` | Cache forever (1yr). Never revalidate. | Versioned assets (JS/CSS). |
| `public, s-maxage=3600, max-age=60` | Cache at Edge for 1hr, Browser for 1min. | Public API data. |
| `no-store, private` | Never cache. Pass through to origin. | Personalized dashboards. |

## Origin Shielding

**Origin Shield** is an intermediate caching layer between PoPs and the primary origin.
- **Topology:** `User --> Edge PoP (Many) --> Origin Shield (One) --> Origin`.
- **Benefit:** Prevents "Thundering Herd" misses from many PoPs reaching the origin simultaneously. It consolidates requests for the same stale resource into a single origin fetch.

## Cache Invalidation Patterns

1.  **TTL (Time-To-Live):** Automatic expiration. Low complexity, but introduces staleness.
2.  **Purge by URL:** Explicitly mark a resource as stale via API. High latency ($5\text{min}+$ propagation).
3.  **Cache-Busting (Recommended):** Fingerprint URLs with content hashes (e.g., `app.v1.2.js` $\rightarrow$ `app.v1.3.js`). Eliminates the need for manual invalidation.

## Edge Compute

Modern CDNs (Cloudflare Workers, Lambda@Edge) allow execution of code at the PoP.
- **Dynamic Routing:** A/B testing based on cookies.
- **Header Manipulation:** Stripping `Set-Cookie` from cacheable responses.
- **SEO Pre-rendering:** Generating HTML for bot crawlers at the edge.

## Security at the Edge

- **DDoS Mitigation:** CDNs act as a massive shock absorber for L3/L4 volumetric attacks.
- **WAF (Web Application Firewall):** Filtering SQLi/XSS at the edge.
- **Geo-Fencing:** Blocking traffic based on source country.

## Operational Checklist
- **[ ] Disable Caching on Set-Cookie:** Ensure responses with session cookies are never cached.
- **[ ] Configure Default Root Object:** Map `/` to `index.html`.
- **[ ] Enable HTTP/3:** Use the latest protocol for multiplexed stream performance.
- **[ ] Monitor Cache Hit Ratio (CHR):** Aim for $>90\%$ for static assets.
