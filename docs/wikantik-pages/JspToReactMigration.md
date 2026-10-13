---
status: active
type: article
tags:
- jsp
- react
- migration
- frontend
- strangler-fig
- micro-frontends
- architecture
canonical_id: 01KQ0P44REFP9483SXP4P9S0FG
related:
- WikantikDevelopment
- AdminSecurityUi
- DatabaseBackedPermissions
date: '2026-03-28'
kg_include: true
audience:
- humans
cluster: frontend-development
title: 'JSP to React Migration Architecture: The Strangler Fig Pattern, REST Decoupling,
  and Hybrid Micro-Frontends'
enables:
- AdminSecurityUi
- KnowledgeAdminUi
summary: 'Incremental JSP to React migration guide: Strangler Fig routing, headless
  REST decoupling, reverse-proxy cutovers, and hybrid React islands.'
---
# JSP to React Migration Architecture: The Strangler Fig Pattern, REST Decoupling, and Hybrid Micro-Frontends

> **Status (2026-10-13):** Wikantik's own JSP-to-React migration is complete. The next section records what was actually done; the rest of this page is general guidance, not a description of Wikantik's deployment.

## How Wikantik Did It

The legacy JSP UI was tightly coupled to the Java backend. Every page view was a full server round-trip, templates were scattered across JSP files with embedded Java scriptlets, and a new UI feature meant changing both Java controllers and JSP templates. Agents working through MCP also needed a clean REST API rather than server-rendered HTML.

Wikantik skipped the long multi-phase strangler described below and cut over in days:

- **2026-03-24:** the `wikantik-rest` module added a REST/JSON API (page CRUD, search, authentication, admin operations).
- **2026-03-25:** a Vite-built React SPA was bundled into the WAR and served under `/app/` beside the JSP UI.
- **2026-03-28:** a feature-parity design spec for the remaining screens.
- **2026-03-29:** the JSP pages, templates and legacy JavaScript (68+ files) were deleted.
- **Afterwards:** the `/app/` prefix was dropped, so the SPA is served from the root context.

Where the pieces live today:

- `wikantik-rest`: the REST endpoints under `/api/*` and `/admin/*`, and `SpaRoutingFilter`, which redirects `/` to `/wiki/Main` and serves the React shell for routes such as `/wiki/*`, `/edit/*`, `/search`, `/page-graph`, `/knowledge-graph`, `/admin/*` and `/login`.
- `wikantik-frontend`: the React application, built with Vite and routed client-side with React Router.
- `wikantik-war`: the WAR build runs the frontend build and packages the result.
- `wikantik-http`: servlet filters for CSRF, CORS and the security headers.

No `.jsp` files remain in the repository.

Migrating legacy JavaServer Pages (JSP) monoliths to modern Single-Page Application (SPA) frameworks like React is a critical modernization initiative for enterprise web systems. Monolithic server-side rendering (SSR) via JSP tightly couples presentation HTML, Java business logic, and session state, creating high development friction and preventing modern client-side UX.

This guide provides the architectural blueprint for incrementally migrating enterprise JSP applications to React using the **Strangler Fig Pattern**, RESTful API decoupling, reverse-proxy routing, and hybrid micro-frontend mounting.

---

## 1. Quick-Reference: Legacy JSP vs. Modern React Architecture

```
+-------------------------------------------------------------------------------+
|                       JSP MONOLITH vs. REACT SPA ARCHITECTURE                 |
+-------------------------------------------------------------------------------+
| Dimension           | Legacy JavaServer Pages (JSP)| Modern React Single-Page App |
+---------------------+------------------------------+--------------------------+
| Rendering Model     | Server-Side HTML Rendering   | Client-Side Virtual DOM  |
| Data Transport      | In-memory Servlet Attributes | Decoupled JSON REST/gRPC |
| State Management    | HTTP ServletSession (Server) | Client Context / Redux   |
| Deployment Model    | Bundled WAR/EAR in Tomcat    | Static CDN + Headless API|
| Developer Velocity  | Heavy Tomcat server restarts | Instant HMR (< 50 ms)    |
+---------------------+------------------------------+--------------------------+
```

---

## 2. Migration Strategy: The Strangler Fig Pattern

Attempting a "Big Bang" complete rewrite of a large enterprise JSP application carries high failure rates. The **Strangler Fig Pattern** enables zero-downtime, incremental page-by-page replacement:

```
Incremental Strangler Fig Routing Architecture:
                          [ Client Browser / Mobile ]
                                       |
                                       v
                     [ Edge Reverse Proxy / Nginx / Cloudflare ]
                                       |
                   +-------------------+-------------------+
                   | (Path: /app/*)                        | (Path: /legacy/*)
                   v                                       v
        [ Modern React SPA ]                      [ Legacy JSP Monolith ]
        - Static CDN Hosting                      - Tomcat / Jetty Server
        - React Router v6                         - Taglibs & Scriptlets
                   |                                       |
                   +-------------------+-------------------+
                                       |
                                       v
                    [ Core Business Logic & PostgreSQL DB ]
```

---

## 3. Technical Migration Phases

```
+-------------------------------------------------------------------------------+
|                       JSP TO REACT MIGRATION PHASES                           |
+-------------------------------------------------------------------------------+
| Phase 1: API Surface Extraction                                               |
| - Convert internal JSP Java scriptlets and JSTL tags into JSON REST endpoints |
| - Replace `HttpServletRequest.setAttribute()` with `@RestController` DTOs     |
|                                                                               |
| Phase 2: Hybrid In-Page Island Mounting                                       |
| - Mount isolated React components inside legacy JSP DOM containers (`<div>`)  |
| - Pass server-rendered session data via `data-*` HTML attributes or JSON      |
|                                                                               |
| Phase 3: Route-Level Reverse Proxy Cutover                                    |
| - Deploy full React SPA shell; Nginx routes migrated URLs to React static CDN |
|                                                                               |
| Phase 4: Full Monolith Decommissioning                                        |
| - Remove JSP servlets and taglib libraries, leaving pure headless REST backend|
+-------------------------------------------------------------------------------+
```

### In-Page Hybrid React Island Mounting Pattern

```html
<!-- Legacy JSP Page (view_order.jsp) -->
<div id="react-order-summary-root"
     data-order-id="${order.id}"
     data-api-token="${sessionScope.userToken}">
</div>
<script src="/static/bundles/order-summary.bundle.js"></script>
```

```tsx
// React Island Entrypoint (order-summary.tsx)
import { createRoot } from 'react-dom/client';
import OrderSummary from './OrderSummary';

const container = document.getElementById('react-order-summary-root');
if (container) {
  const orderId = container.getAttribute('data-order-id')!;
  const apiToken = container.getAttribute('data-api-token')!;
  const root = createRoot(container);
  root.render(<OrderSummary orderId={orderId} apiToken={apiToken} />);
}
```

---

## References

1. Fowler, M. (2004). *StranglerFigApplication*. MartinFowler.com.
2. Geers, M. (2020). *Micro Frontends in Action*. Manning Publications.
3. React Core Team. (2024). *React Documentation: Add React to an Existing Project*. React.dev.
