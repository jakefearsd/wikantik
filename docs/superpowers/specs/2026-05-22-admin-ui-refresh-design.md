# Admin UI Refresh — Design

**Date:** 2026-05-22
**Status:** Approved (brainstorm complete; pending implementation plan)
**Scope:** Information architecture, visual direction, and interaction flows for the
admin area. **Not** new admin capabilities — this is a re-presentation of what
already exists, plus one aggregation endpoint to power the new Overview dashboard.

---

## Goal

Replace the organically-grown admin area (a flat 7-item top-nav, KG-dominated and
uneven in grain, with no landing surface) with a coherent, grouped, mode-switching
admin experience that reads as part of Wikantik but is built for the row-scanning
and form-filling that admin work actually is.

## Background — the problems being fixed

The admin area today (`wikantik-frontend/src/components/admin/`, ~8,480 LOC):

- **Flat top-nav of 7 items** (`AdminLayout.jsx`): Users, Content, Security,
  Knowledge Graph, API Keys, Retrieval, KG Policy. It crowds at 7 and nesting is
  invisible.
- **KG sprawl & uneven grain.** Three of the seven items (Knowledge Graph with 8
  in-page tabs, KG Policy with 4 routes, Retrieval) are all the same subsystem,
  while Users / API Keys are single pages.
- **No landing / no at-a-glance health.** The index route redirects straight to
  Users. There is no place that answers "is the system OK / what needs me?"
- **Reader sidebar always present.** `App.jsx` renders the reader `Sidebar`
  (page tree, search, clusters) on every route including `/admin/*`, so an admin
  sees reader navigation on the left *and* admin top-nav inside the content.
- **Reader-grade visual density.** The editorial system (Playfair / Source Serif,
  generous whitespace) is lovely for articles but spacious for dense control-panel
  data.

---

## Design decisions

Each was resolved via visual mockups during brainstorming. The chosen option is
recorded; rejected options are noted for context.

### 1. Navigation model — grouped left sidebar + dashboard landing

**Chosen:** A grouped left sidebar (domain groups, hierarchy always visible) with a
status **dashboard as the landing/Overview**.
*Rejected:* two-tier top nav (keeps fighting width as KG grows); pure dashboard
hub-and-spoke (most build, navigation becomes secondary).

### 2. Rail behavior — context swap (one rail, mode-switched)

**Chosen (A1):** The single left rail shows **admin** navigation while in
`/admin/*` and reverts to the reader page-tree elsewhere. A "← Back to wiki" link
sits at the top of the admin rail. The reader sidebar is never deleted — it is
simply not shown while administering. The reader sidebar's existing "Admin" section
(visible to admins) is the door into this mode.
*Rejected:* two simultaneous rails (eats ~220px, cluttered); reader-rail + admin
top-nav (doesn't deliver the grouped IA chosen above).

Rationale: the standard "leave the workspace, enter settings" pattern (GitHub
repo→settings, VS Code, Stripe). You are either doing admin work or using the wiki.

### 3. Information architecture — grouping

The sidebar groups (top to bottom):

| Group | Items | Source pages today |
|---|---|---|
| *(top)* | **Overview** | new |
| **People & Access** | Users · Security · API Keys | `AdminUsersPage` · `AdminSecurityPage` · `AdminApiKeysPage` |
| **Content** | Content & Index | `AdminContentPage` |
| **Knowledge & Search** | Knowledge Graph · KG Policy · Retrieval Quality | `AdminKnowledgePage` · `AdminKgPolicyPage` · `AdminRetrievalQualityPage` |

**Knowledge Graph keeps its 8 tabs as in-page tabs** (Proposals, Extraction, Node
Explorer, Edge Explorer, Content Embeddings, Hub Proposals, Hub Discovery, LLM
Activity), restyled to the new density. Each tab keeps a deep-linkable URL (query
param or sub-route) but does **not** expand into the sidebar.
*Rejected:* expanding all 8 tabs as sidebar sub-items (dominates the rail, buries
the other groups).

### 4. Visual direction — Hybrid (editorial chrome, tool-grade data)

**Chosen:** Keep the warm palette, terracotta accent, and **serif page titles**
(Playfair) for brand continuity; switch **data regions** (tables, forms, metric
cards) to the UI font (DM Sans) with compact rows and uppercase column labels.
*Rejected:* full editorial (too spacious / "document" feel for a control panel);
utilitarian neutral console (sheds the warmth, feels like a bolted-on app).

Concretely, admin gets a density layer over the existing tokens:
- Page titles: `--font-display` (Playfair), ~20px.
- Table / form / card text: `--font-ui` (DM Sans), 13px.
- Table cell padding: `8px 16px` (vs editorial `13px 16px`); column headers
  uppercase 11px, `--text-muted`.
- Palette, accent, badges, light/dark tokens: unchanged from `globals.css`.

### 5. Overview dashboard — one sectioned page, 14 cards

**Chosen (A):** A single Overview page with two bands. Nothing hidden; the top band
stays glanceable.
*Rejected:* splitting the diagnostic band onto a separate System Metrics nav item.

**Status & action band (8 cards)** — "is it OK / what needs me?"

| Card | Shows | Data source |
|---|---|---|
| Health | status, version, uptime | `GET /api/health`, `/api/health/db`; `getVersion()` |
| Load | in-flight vs 390 cap, shed count | `wikantik_backpressure.inflight` / `.permits_max` / `.rejected_total` |
| KG proposals | pending count → review queue | `KnowledgeGraphService.countProposals(status=pending)` |
| Retrieval | latest nDCG@5 + sparkline | `retrieval_runs` (latest aggregate) |
| LLM activity | in-flight, calls in window, error %, per-subsystem, tokens, cache occupancy | `LlmActivityLog.snapshot(...)` (in-memory cache) |
| Search & index | indexable/total, Lucene docs+queue, HNSW vector count, chunk token stats, embeddings %, embedder cache hit %, breaker state, missing-chunk warning | `IndexStatusSnapshot` + `wikantik.search.hybrid.vector_index.size` |
| Users | user count, active API keys, locked count | users table; `api_keys` table |
| Recent | short feed of recent admin/LLM events | assembled from `LlmActivityLog` recent entries + proposal action log |

**System metrics band (6 cards, dimmed, below)** — diagnostic detail

| Card | Shows | Data source |
|---|---|---|
| Knowledge Graph size | nodes, edges, stub nodes, degree-0 orphans | `countNodes()`, `countEdges()`, `list_orphaned_kg_nodes` |
| Extractor pipeline | requests, triples emitted, failures, p50/p95 latency | `wikantik_kg_extractor_*` |
| KG judge | pending queue depth, timeouts, short-circuits | `wikantik.kg_judge.timeouts` / `.short_circuit_total`; judge queue |
| Render cache | hit rate, entries vs 10K cap, evictions | `wikantik_cache.hits` / `.misses` / `.evictions` / `.size` |
| Auth activity | logins 24h, failed logins, locked accounts | `wikantik.auth.logins`; users table |
| Agent surface | avg `/for-agent` bytes, hub-summary synthesis count, hint derivation failures | `wikantik_for_agent_response_bytes`, `wikantik_hub_summary_synthesis_total`, `wikantik_agent_hints_derivation_failures_total` |

Cards link into their owning section. The page polls for the live cards (Load, LLM
activity) on a modest interval (15–30s), matching the existing `LlmActivityTab`
poll pattern.

*Deferred (offered, not selected):* Content quality (verification mix), retrieval
all-modes expansion, attachments.

---

## Architecture

### Backend — one aggregation endpoint

Most dashboard data lives in in-process Micrometer registries and assorted DB
counts, not in REST JSON. Parsing the Prometheus text exposition in the browser is
brittle, so the dashboard is backed by a single new aggregation endpoint.

- **`AdminOverviewResource`** (new, `wikantik-rest`, under `AdminAuthFilter`):
  `GET /admin/overview` → an `OverviewSnapshot` envelope with one sub-object per
  card.
- **`OverviewSnapshot`** record composing the 14 card payloads. Each card section
  is assembled by a small, independently-testable collector that reads its own
  source (registry gauge, DB count, or existing snapshot service).
- **Graceful per-card degradation**, following the established `/for-agent`
  projection pattern: a failing collector yields a `null`/empty card section and
  adds its key to a top-level `degraded` list, rather than failing the whole
  response. Every collector failure logs `LOG.warn` with context (never a silent
  empty catch).

This is data aggregation for a re-presentation surface — it adds no new admin
capability and mutates nothing.

### Frontend components

- **`AdminSidebar`** (new) — the grouped, context-swap rail: "← Back to wiki",
  Overview, then the four groups. Renders active state via `NavLink`. Rendered by
  `App.jsx` in the rail slot **instead of** the reader `<Sidebar>` when
  `isAdminRoute` is true (the existing flag at `App.jsx:13`).
- **`AdminLayout`** (modified) — drops the top-nav header; becomes the content
  shell that gates on `Admin` role and renders `<Outlet/>`.
- **`PageHeader`** (new, shared) — serif page title + secondary description +
  right-aligned actions slot. Adopted by every admin page for a uniform header.
- **`AdminPage`** (existing, kept) — retains the loading/error short-circuit;
  composes with `PageHeader`. A shared `EmptyState` standardizes empty lists.
- **`OverviewDashboard`** (new) — fetches `/admin/overview`, renders a
  `StatusBand` (8 `MetricCard`s) and a `SystemMetricsBand` (6 dimmed `MetricCard`s),
  polls the live cards, and renders degraded cards with a muted "unavailable" state.
- **`MetricCard`** (new) — label, big value, meta line, optional sparkline
  (reuse existing `Sparkline.jsx`), optional CTA link into a section.
- **Admin density layer** — added to `wikantik-frontend/src/styles/admin.css`:
  table/form/card density overrides + `PageHeader` styles, all on existing tokens.

### Routing

`main.jsx` admin routes gain `index → Overview` (replacing the redirect to Users).
KG tab deep-links preserved. No reader routes change.

---

## Interaction & state standards

Applied uniformly so pages stop drifting:

- **Page scaffold:** every admin page = `PageHeader` (title + description +
  primary action) followed by content. No bespoke per-page headers.
- **Loading / error / empty:** loading and error via `AdminPage`; empty lists via
  the shared `EmptyState` (icon + one-line explanation + optional action).
- **Tables:** `AdminTable` + `SelectionBar` + `BulkActionMenu` are kept as-is
  functionally; restyled to the hybrid density. Filters and bulk actions unchanged.
- **Dashboard polling:** live cards refresh on a 15–30s interval; static cards
  load once per visit.

---

## Testing

- **Frontend (Vitest):**
  - `AdminSidebar` — renders groups, active link state, "← Back to wiki", and that
    `App` swaps reader↔admin rail on `isAdminRoute`.
  - `OverviewDashboard` — renders both bands, a degraded card renders its
    unavailable state, CTA links target the right routes.
  - `MetricCard`, `PageHeader` — render contract (value/meta/actions/sparkline).
- **Backend:**
  - `AdminOverviewResource` unit test — assembles the snapshot; a throwing
    collector degrades just its card and populates `degraded` (per-card try/catch).
  - Wire-level IT (Cargo, `wikantik-it-tests`) — `GET /admin/overview` returns 200
    with the envelope for an authenticated admin; 401/403 for non-admin via
    `AdminAuthFilter`.
- Gate the prod-code commit on the full IT reactor (`mvn clean install
  -Pintegration-tests -fae`), not just targeted runs.

---

## Out of scope (YAGNI)

- No new admin capabilities, tools, or controls — re-presentation only.
- The three unselected dashboard cards (content quality, retrieval all-modes,
  attachments).
- No change to KG tab internals beyond the hybrid restyle.
- No mobile-specific admin redesign (keep it responsive, don't rework it).
- No change to the reader site's editorial system.
