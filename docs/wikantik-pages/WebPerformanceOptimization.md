---
title: Web Performance Optimization
type: article
cluster: frontend
status: active
date: '2026-04-25'
tags:
- web-performance
- core-web-vitals
- lcp
- inp
- bundle-size
summary: The 2026 web performance discipline — Core Web Vitals (LCP, INP, CLS),
  the techniques that move them, and the levers that earn their complexity.
related:
- ReactBestPractices
- DesignSystems
- SinglePageApplicationArchitecture
hubs:
- Frontend Hub
---
# Web Performance Optimization

Web performance is "how fast does the page feel." Real users don't time loads with stopwatches; they form impressions of speed in the first 200ms and decide whether to stay around. Performance work is about influencing those impressions efficiently.

Google's Core Web Vitals (LCP, INP, CLS) standardised what "felt fast" actually meant by 2020; they've evolved with INP replacing FID in 2024. As of 2026, this is still the operational target.

## Core Web Vitals

| Metric | What it measures | Target | What hurts it |
|---|---|---|---|
| **LCP** (Largest Contentful Paint) | When the biggest visible element appears | < 2.5s | Slow server, large unoptimised images, render-blocking JS/CSS |
| **INP** (Interaction to Next Paint) | Latency from user action to next render | < 200ms | Long-running JS, layout thrash, blocking the main thread |
| **CLS** (Cumulative Layout Shift) | Visual stability | < 0.1 | Images without dimensions, fonts loading late, ads inserting |

These three correlate with revenue, engagement, and SEO ranking. They're worth the engineering attention.

## LCP: making the page appear fast

LCP measures when the largest above-the-fold element rendered. Optimising it:

### Server-side render the critical content

Server-rendered HTML arrives faster than client-rendered HTML waits for JS to execute. For most pages, SSR is the difference between LCP < 2s and LCP > 4s.

Frameworks: Next.js App Router, Remix, SvelteKit. All ship server-rendered HTML for initial loads.

### Optimise images

Images are usually the LCP element on content sites. Optimisations:

- **`<img loading="lazy">`** for below-the-fold; `loading="eager"` for above.
- **Responsive images**: `srcset` and `sizes` so mobile gets smaller files.
- **Modern formats**: AVIF for photos (40-50% smaller than JPEG); WebP fallback.
- **Image CDN** (Cloudflare Images, imgix, Imgproxy) — auto-resizes per-device.
- **Pre-generate critical images** at build time; serve cached.

### Preload key resources

```html
<link rel="preload" href="/fonts/main.woff2" as="font" type="font/woff2" crossorigin>
<link rel="preconnect" href="https://api.example.com">
```

Tells the browser "fetch this; don't wait for the parser to discover it."

### Reduce render-blocking resources

CSS in `<head>` blocks rendering. Inline critical CSS; defer the rest:

```html
<style>/* critical above-the-fold styles */</style>
<link rel="stylesheet" href="non-critical.css" media="print" onload="this.media='all'">
```

Synchronous JS blocks parsing. Use `async` / `defer` on `<script>` tags. For React, ship the framework code at the very top so hydration can start.

### Cache aggressively

Static assets: `Cache-Control: public, max-age=31536000, immutable` with hashed filenames. Once cached, never re-fetched.

HTML: `Cache-Control: public, max-age=0, must-revalidate` plus an `ETag`. Lets the browser cache the document but re-validate on each visit.

CDN in front of everything reduces TTFB dramatically.

## INP: keeping interactions responsive

INP measures how long after a user action (click, type, tap) the next frame paints. Most modern web apps fail INP because they're doing too much JS work on user interaction.

### Don't block the main thread

JavaScript runs on the main thread. Long-running JS prevents the browser from responding to clicks. The hard rule: any single task should be under ~50ms.

When a task is necessarily long:

- **Break it up**: process in chunks with `requestIdleCallback` or `scheduler.postTask`.
- **Move to a Web Worker** for genuinely CPU-bound work.
- **Use `startTransition`** in React for non-urgent updates.

### Avoid layout thrashing

Reading layout properties (`offsetWidth`, `getBoundingClientRect`) and then writing styles forces synchronous reflow. Loop of read-write-read-write = layout thrash. Batch reads, then writes.

### Defer non-critical work

When the user clicks, do the minimum to update the UI; defer logging, analytics, and side effects:

```javascript
function onClick() {
  // immediate UI update
  setOpen(true);
  
  // deferred non-critical
  queueMicrotask(() => analytics.track('opened', ...));
}
```

### Reduce JS bundle size

Less JS = faster parse + execute. See bundle size below.

## CLS: avoiding layout shifts

CLS measures unexpected movements. Caused by:

- Images / videos without specified dimensions.
- Fonts that load and re-flow text.
- Ads injected late.
- Banner notifications appearing at the top.

Fixes:

- **`width` and `height` attributes** on images / videos. Browser reserves space.
- **`aspect-ratio` CSS** for responsive media.
- **`font-display: optional` or `swap`** with similar-metric fallbacks; consider `size-adjust` to align fallback metrics.
- **Reserve space** for late-loading content (skeleton placeholders).
- **Don't inject content above existing content** post-load; insert below or in modals.

Most CLS issues are dimension-attribute issues. Fix those first.

## Bundle size

For SPAs and React apps specifically, JS bundle size is a major lever.

Track:

- **Total JS shipped per route** (parse + execute scales with size).
- **Critical-path JS** (what's needed before LCP).
- **Per-vendor breakdown** (which libraries are biggest).

Tactics:

### Code splitting

```typescript
const AdminPanel = React.lazy(() => import('./AdminPanel'));
```

Routes / heavy features load only when navigated to. Webpack / Vite handle this with dynamic imports.

### Tree shaking

Unused exports get eliminated at build time. Use ES modules; avoid `import * as` patterns; check that your dependencies are tree-shake-friendly (some popular libraries aren't).

### Replace heavy dependencies

Common offenders:

- **Moment.js** (60KB+) → date-fns or dayjs (5-10KB).
- **Lodash full** (70KB) → cherry-pick from `lodash-es`.
- **Material-UI everything** → import individual components.
- **jQuery** in 2026 — usually not needed; remove.

A `webpack-bundle-analyzer` (or Vite equivalent) report shows the targets.

### Minification + compression

Standard. Brotli over gzip for ~15% better compression. CDN handles this; verify it's on.

### Server Components for SSR

React Server Components don't ship JS for non-interactive UI. For most content sites, this drops the bundle dramatically.

## Network

- **HTTP/2 or HTTP/3** — multiplexed; better than HTTP/1.1. Servers and CDNs default to it.
- **CDN** — for everyone. Cloudflare (free tier exists), Fastly, AWS CloudFront, Vercel / Netlify (built in).
- **Edge functions** — run server logic close to users. Reduces TTFB for SSR.
- **Connection coalescing** — preconnect / preload key origins.

## Database / API performance

Page speed often bottlenecks on the API. Frontend optimisation can't help if the API takes 3 seconds.

- **Database query optimisation** — see [DatabasePerformanceMonitoring].
- **Caching** — Redis, edge cache, browser cache.
- **GraphQL / REST design** — avoid N+1; prefer batched / efficient endpoints.
- **Streaming responses** — show partial content as it's available.

For most user-facing applications in 2026, server time accounts for 30-60% of the total time-to-LCP. Optimising it pays.

## Measurement

You cannot optimise what you don't measure. Two layers:

### Synthetic monitoring

- **Lighthouse CI** — runs in CI; catches regressions before deploy.
- **WebPageTest** — more thorough; runs scenarios.
- **PageSpeed Insights** — combines lab data + field data.

These run on controlled machines / networks. Useful for relative comparisons; less useful for predicting real-user experience.

### Real User Monitoring (RUM)

- **Web Vitals JS library** — emits LCP / INP / CLS; you ship to your analytics.
- **Cloudflare RUM, SpeedCurve, New Relic Browser** — managed RUM.
- **Google CrUX** — public dataset of real-world Core Web Vitals; useful for benchmarking.

RUM tells you what real users experience. If lab tests look great but RUM is bad, real-world conditions (slow networks, low-end devices, browser variance) are revealing themselves.

For production apps, both. Synthetic catches regressions; RUM tells you whether the site actually feels fast.

## Anti-patterns

- **Optimising what's not slow.** Profile first; don't pre-optimise.
- **Premature SPA architecture.** A static site with a sprinkling of JS often beats a fully-SPA architecture for content-heavy use cases.
- **Aggressive lazy-loading of above-the-fold content.** Lazy-loaded LCP image fails the LCP target.
- **Heavy frameworks for simple sites.** A landing page doesn't need React + 200KB of JS.
- **Ignoring server time.** Front-end can be perfect; if the API is slow, the page is slow.

## A pragmatic optimisation playbook

For a site with poor performance:

1. **Measure** with Lighthouse + RUM. Identify which Vital is failing.
2. **For LCP**: check image sizes; check server response time; check render-blocking resources.
3. **For INP**: profile main-thread work on common interactions; identify long tasks.
4. **For CLS**: identify which elements shift; add dimensions / reservations.
5. **Set budgets** in CI (Lighthouse CI) so regressions don't ship.
6. **Track in production** with Web Vitals + your analytics; iterate.

Most sites can hit Core Web Vitals targets with a week of focused work. Continuous discipline keeps them there.

## Further reading

- [ReactBestPractices] — React-specific performance
- [DesignSystems] — components reusable across products without bloat
- [SinglePageApplicationArchitecture] — broader SPA architecture
