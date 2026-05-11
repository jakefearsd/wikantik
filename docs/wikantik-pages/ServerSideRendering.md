---
summary: When SSR is right vs. CSR vs. SSG vs. ISR — the modern rendering landscape,
  the trade-offs each pattern makes, and the cases where each fits.
date: '2026-04-26'
cluster: frontend-development
related:
- ProgressiveWebApps
- WebComponents
- ModernBundlersAndBuildTools
- CdnArchitecture
canonical_id: 01KQ0P44W9ND8RXB81QG5QXDJ8
type: article
title: Server-Side Rendering
tags:
- ssr
- server-side-rendering
- frontend
- nextjs
- rendering
status: active
hubs:
- FrontendDevelopmentHub
- WebComponents Hub
---
# Server-Side Rendering

The rendering landscape for modern web apps has gotten complicated. SSR, CSR, SSG, ISR — multiple acronyms with overlapping meanings. The choice affects performance, SEO, complexity, and operational footprint.

This page covers the patterns and when each fits.

## The patterns

### CSR (Client-Side Rendering)

The browser receives a minimal HTML shell and JavaScript. JS executes; renders the page.

```html
<!-- The HTML the browser receives -->
<div id="root"></div>
<script src="app.js"></script>
```

The user sees a blank page until JS loads and runs.

**Pros**: simple deployment (static files); rich interactivity; good for SPAs.
**Cons**: slow first paint; bad SEO without help; bad on slow devices.

### SSR (Server-Side Rendering)

The server runs the app for each request; produces HTML; sends it to the browser. The browser renders immediately.

```html
<!-- The HTML the browser receives -->
<div id="root">
    <h1>Welcome</h1>
    <p>Already-rendered content...</p>
</div>
<script src="app.js"></script>
```

After hydration, the JS takes over for interactivity.

**Pros**: fast first paint; good SEO; works without JS.
**Cons**: server cost (every request runs the app); harder to deploy; complexity.

### SSG (Static Site Generation)

At build time, render every page to HTML. Deploy as static files. CDN-friendly.

**Pros**: fastest possible serving; trivial deployment; massively scalable.
**Cons**: rebuild on every content change; not for personalized content.

### ISR (Incremental Static Regeneration)

A Next.js innovation: SSG with periodic re-rendering. Pages are static but regenerate every N seconds or on demand.

**Pros**: SSG benefits + reasonable freshness.
**Cons**: complexity; requires specific framework support.

### Streaming SSR

The server sends HTML in chunks as it's rendered, rather than waiting for the whole page.

```
Initial HTML (header, layout) → 
Component A streams → 
Component B streams → 
...
```

**Pros**: fast first paint even for slow components.
**Cons**: more complex; framework support varies.

### Server Components (React/Next 13+)

Components that run only on the server; the result is included in the HTML. Mixed with client components for interactivity.

**Pros**: server work doesn't ship JS to client; data fetching happens server-side.
**Cons**: new mental model; framework-specific.

## When each fits

### Pure CSR

- **Internal apps** where SEO doesn't matter; users tolerate the loading state
- **Highly interactive apps** like editors, dashboards
- **Mobile-style apps** where the shell is fixed and content is dynamic

### SSR

- **Public web apps** where SEO matters and content varies per request
- **E-commerce** with personalization
- **News sites** with frequent content updates

### SSG

- **Marketing sites, blogs, documentation** — content that changes occasionally
- **Maximum performance** with content that's known at build time

### ISR

- **Content-heavy sites** that need both freshness and static-site speed
- **Hybrid use cases** where SSG is mostly right but some pages need updates

### Server Components / Streaming SSR

- **Modern Next.js apps** taking advantage of these features
- **Pages with mixed static and dynamic content**

## The framework landscape

### Next.js

The dominant React framework. Supports SSR, SSG, ISR, server components. The default for new React apps.

### Nuxt

Vue equivalent. Similar patterns.

### SvelteKit

Svelte equivalent.

### Astro

Static-first; partial hydration. "Ship less JS" philosophy.

### Remix

Web-standards-focused; emphasizes loaders for data, actions for mutations.

### Old SPA frameworks (Create React App)

Pure CSR. Falling out of favor for new apps.

## The trade-offs

### Performance

SSR/SSG faster first paint. CSR slower first paint but maybe faster subsequent navigation.

### SEO

SSR/SSG bots see real content. CSR sometimes works (Google renders JS) but unreliable.

### Operational complexity

SSR needs a server runtime. SSG just static files. CSR also static (for shell).

### Cost

SSR costs per request. SSG one-time build cost. CSR static serving.

### Development

CSR is simplest dev experience. SSR adds server-side complexity. Modern frameworks (Next.js) hide much of this.

## A reasonable default

For most new public-facing web apps:
- **Static marketing site**: SSG (Astro, Next.js export, or simpler tools)
- **Content site with some dynamic content**: ISR or SSG with client-side fetching
- **App-like experience with personalization**: SSR or modern frameworks with server components
- **Internal app**: CSR is fine

The decision matters less than it used to. Modern frameworks support multiple patterns; you can mix them per page.

## Common failure patterns

- **CSR for content sites.** Bad SEO; slow first paint; lose users.
- **SSR for highly-interactive apps.** Heavy server cost; CSR was right.
- **SSG for personalized content.** Builds huge or wrong.
- **Mixing patterns without understanding.** Each page works differently; complexity.
- **Heavy hydration cost.** SSR's HTML loads; then JS hydration takes seconds. Worse than CSR for some users.

## Further Reading

- [ProgressiveWebApps](ProgressiveWebApps) — Adjacent web app pattern
- [WebComponents](WebComponents) — Component models
- [ModernBundlersAndBuildTools](ModernBundlersAndBuildTools) — Build infrastructure
- [CdnArchitecture](CdnArchitecture) — Where SSG/ISR fit
- [FrontendDevelopment Hub](FrontendDevelopmentHub) — Cluster index
