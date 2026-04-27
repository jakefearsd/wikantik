---
canonical_id: 01KQ0P44TVBE770R5QS2YNS9FM
title: Progressive Web Apps
type: article
cluster: frontend-development
status: active
date: '2026-04-26'
summary: What PWAs actually are — service workers, manifests, offline support, push
  notifications — and the cases where the PWA pattern fits vs. where native apps
  remain the answer.
tags:
- pwa
- progressive-web-apps
- service-workers
- offline
- frontend
related:
- ServerSideRendering
- WebComponents
- FormHandlingAndValidation
hubs:
- FrontendDevelopment Hub
---
# Progressive Web Apps

A Progressive Web App (PWA) is a web app with native-app-like features: offline support, push notifications, installable on home screen, background sync. Built using web standards: service workers, manifests, web APIs.

The PWA promise was that one codebase replaces native apps. The reality is more nuanced.

## The core technologies

### Service workers

A JavaScript worker that runs separately from your page. It can intercept network requests, cache responses, work offline.

```javascript
// Register
navigator.serviceWorker.register('/sw.js');

// In sw.js
self.addEventListener('fetch', (event) => {
    event.respondWith(
        caches.match(event.request)
            .then(response => response || fetch(event.request))
    );
});
```

Service workers enable:
- Offline support (cached responses when network is unavailable)
- Background sync (queue requests; send when online)
- Push notifications

### Web App Manifest

A JSON file describing the app:

```json
{
    "name": "My App",
    "short_name": "MyApp",
    "start_url": "/",
    "display": "standalone",
    "icons": [...]
}
```

The browser uses this for "install to home screen" — the app gets its own icon and runs in a standalone window.

### Cache API

Programmatic control over what's cached. Used inside service workers.

### Push API

For server-to-client push notifications via a push service (Firebase Cloud Messaging, etc.).

## What PWAs do well

### Offline support

Cached resources serve when network is down. For content-reading use cases (news, docs), this is significant.

### Reliable performance

Cached assets load instantly. Less network dependency.

### Installable

Users can add to home screen; the app feels more committed than a website.

### Rich web APIs

Background sync, push notifications, file system access, camera, geolocation — increasingly available via web APIs.

## What PWAs don't do (or do imperfectly)

### Match native performance

Heavy graphics (games, video editing), tight integration with OS features. Native is still ahead for these.

### iOS feature parity

Apple has historically been slow to adopt PWA features. Push notifications on iOS Safari only arrived recently. Some features still missing.

### App store distribution

Most users find apps via App Store / Play Store. PWAs aren't there by default (though some stores accept them).

### Deep OS integration

Native app developer experience for things like share extensions, widgets, watch apps.

## The honest assessment

PWAs work well for:
- Content sites that benefit from offline reading
- Productivity apps
- Mobile-friendly versions of web apps
- Internal tools

PWAs work less well for:
- Games
- Video/audio editing
- iOS-first audiences
- Apps that need deep OS integration

For many use cases, "make the website work great offline" is sufficient. For others, native or React Native is the answer.

## The Service Worker patterns

### Cache-first

Try cache; fall back to network.

```javascript
caches.match(request)
    .then(cached => cached || fetch(request))
```

For static assets that rarely change.

### Network-first

Try network; fall back to cache.

```javascript
fetch(request).catch(() => caches.match(request))
```

For data that should be fresh; cache as fallback.

### Stale-while-revalidate

Serve cached; update cache in background.

```javascript
caches.match(request).then(cached => {
    fetch(request).then(fresh => cache.put(request, fresh));
    return cached || fetch(request);
});
```

Best of both for most resources.

### Cache only

Serve from cache; never go to network. For truly static content.

### Network only

Always go to network. For requests that must be fresh.

## Implementation considerations

### Service worker debugging

Service workers can be tricky to debug. They cache aggressively; old service workers persist. DevTools have specific service worker panels.

### Update strategies

When a new service worker is published, the old one is still running for existing tabs. Until tabs close, users see old behavior. Strategies:
- Force reload on update
- Notify user; let them refresh
- Wait for natural tab close

### Storage limits

Browsers limit cache storage (varies by browser; tens to hundreds of MB). Plan caching strategy accordingly.

### HTTPS required

Service workers require HTTPS (or localhost). Production PWAs need TLS.

## Tools

### Workbox

Google library for service worker patterns. Reduces boilerplate.

### PWA frameworks

Next.js, Nuxt, SvelteKit — all have PWA plugins or built-in support.

### Lighthouse

Browser tool; audits PWA conformance. Useful for checking compliance.

## Common failure patterns

- **Aggressive caching breaking the app.** Updates don't reach users.
- **No service worker update strategy.** Old code persists forever.
- **Pretending PWA = native.** Some users will need native; PWA isn't always enough.
- **Heavy framework on small content site.** Bundle size hurts the offline-load benefit.
- **Push notifications used badly.** Spam erodes trust.

## Further Reading

- [ServerSideRendering](ServerSideRendering) — Adjacent rendering pattern
- [WebComponents](WebComponents) — Component model
- [FormHandlingAndValidation](FormHandlingAndValidation) — UI input
- [FrontendDevelopment Hub](FrontendDevelopment+Hub) — Cluster index
