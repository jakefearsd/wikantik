# Wikantik React SPA

The `wikantik-frontend` module implements Wikantik's user interface as a React
18 single-page application. It is built with Vite as part of the standard
Maven build, bundled into the WAR, and served at the web root — there is no
separate hosting, no CORS configuration, and no legacy JSP templates alongside
it.

## Status

Fully implemented. The SPA covers reading, inline editing, search, change
history, similar-pages discovery, attachment management, comments/mentions,
blogs, dark mode, the admin panel (users, content, security, knowledge, API
keys, KG policy, retrieval quality, page ownership, audit), and the graph
viewers.

## Architecture

| Concern | Choice |
|---------|--------|
| Framework | React 18 (`react`, `react-dom`) |
| Routing | React Router v7 (`react-router-dom ^7.16`) |
| Build | Vite 5 (`@vitejs/plugin-react`) |
| Editor | CodeMirror 6 (`@uiw/react-codemirror` + `@codemirror/lang-markdown`) |
| Markdown rendering | `react-markdown` + `remark-gfm`, `remark-math`, `rehype-katex`, `rehype-highlight` |
| Graph visualisation | `cytoscape` + `cytoscape-cose-bilkent` via `react-cytoscapejs` |
| Testing | Vitest + Testing Library + `happy-dom` |
| Styling | Hand-written CSS with a token-based design system (no framework) |
| State | React state + hooks; no external store library |

The build runs automatically during `mvn package` — `frontend-maven-plugin`
installs Node and npm, runs `npm ci` and `npm run build`, and the resulting
`dist/` is copied into the WAR.

## Routing

The SPA uses client-side routing for all interactive views. Deep links are
handled by `SpaRoutingFilter` (in `wikantik-rest`), which forwards recognised
SPA paths to `/index.html` so React Router can take over.

`SpaRoutingFilter` matches two categories of path:

- **Prefix routes** (anything under): `/wiki/`, `/edit/`, `/diff/`, `/admin/`, `/blog/`
- **Exact routes**: `/admin`, `/search`, `/page-graph`, `/knowledge-graph`,
  `/preferences`, `/reset-password`, `/blog`, `/login`, `/me/mentions`

The full route table from `main.jsx`:

| Route | Component | Notes |
|-------|-----------|-------|
| `/` | `<Navigate to="/wiki/Main" replace />` | No home page — always redirects |
| `/wiki/:name` | `PageView` | Article reader (eager-loaded) |
| `/wiki` | `<Navigate to="/wiki/Main" replace />` | Bare /wiki redirect |
| `/edit/blog/:username/:pageName` | `BlogEditor` | Blog post editor |
| `/edit/:name` | `PageEditor` | Wiki page editor |
| `/diff/:name` | `DiffViewer` | Side-by-side version diff |
| `/search` | `SearchResultsPage` | Full-text + faceted search |
| `/page-graph` | `PageGraphView` | Page Graph (wikilink edges) viewer |
| `/knowledge-graph` | `KnowledgeGraphView` | KG entity/edge viewer |
| `/preferences` | `UserPreferencesPage` | Personal settings |
| `/me/mentions` | `MentionsPage` | @-mention inbox |
| `/reset-password` | `ResetPasswordPage` | Password reset flow |
| `/login` | `LoginPage` | Login (dual-registered in web.xml + SPA_EXACT) |
| `/blog` | `BlogDiscovery` | Blog directory |
| `/blog/create` | `CreateBlog` | Create new blog |
| `/blog/:username/new` | `NewBlogEntry` | New blog post |
| `/blog/:username/Blog` | `BlogHome` | Author's blog home |
| `/blog/:username/:entryName` | `BlogEntry` | Individual blog post |
| `/admin` (index) | `OverviewDashboard` | Admin overview |
| `/admin/users` | `AdminUsersPage` | User management |
| `/admin/content` | `AdminContentPage` | Content & index tools |
| `/admin/security` | `AdminSecurityPage` | Groups + policy grants |
| `/admin/knowledge-graph` | `AdminKnowledgePage` | KG curation |
| `/admin/apikeys` | `AdminApiKeysPage` | API key management |
| `/admin/retrieval-quality` | `AdminRetrievalQualityPage` | Retrieval experiment harness |
| `/admin/kg-policy` | `AdminKgPolicyPage` | KG inclusion policy rules |
| `/admin/kg-policy/explain` | `AdminKgPolicyExplain` | Per-page policy explanation |
| `/admin/kg-policy/pending` | `AdminKgPolicyPending` | Pages with pending review |
| `/admin/kg-policy/bootstrap` | `AdminKgPolicyBootstrap` | Bulk bootstrap tooling |
| `/admin/page-ownership` | `AdminPageOwnershipPage` | Page-owner assignment |
| `/admin/audit` | `AdminAuditPage` | Tamper-evident audit log |

All `/admin/*` routes are nested under `AdminLayout` (lazy-loaded) and guarded
server-side by `AdminAuthFilter` (requires `AllPermission`).

Graph viewer routes get a full-bleed `app-content-full` layout; editor and
admin routes use `app-content-wide`; wiki article routes use the narrow reading
column.

The REST API lives under `/api/` and admin endpoints under `/admin/`. Two MCP
servers — `/wikantik-admin-mcp` (25 write/analytics tools) and `/knowledge-mcp`
(16 read-only retrieval + KG tools) — plus the OpenAPI tool server at `/tools/*`
are all serviced by separate backend modules and are not part of the SPA.

## Project Layout

```
wikantik-frontend/
├── package.json
├── vite.config.js
├── index.html
├── public/
└── src/
    ├── main.jsx                 Entry point + route table
    ├── App.jsx                  Layout shell (Outlet + sidebar context-swap)
    ├── api/
    │   └── client.js            Fetch wrapper for /api/*; dispatches wikantik:version-mismatch
    ├── components/
    │   ├── PageView.jsx         Article rendering (react-markdown pipeline)
    │   ├── PageEditor.jsx       Inline editor (CodeMirror 6)
    │   ├── Sidebar.jsx          Cluster-grouped navigation (reader)
    │   ├── SearchOverlay.jsx    Cmd+K search
    │   ├── MetadataPanel.jsx    Frontmatter chips
    │   ├── ChangeNotesPanel.jsx History + diffs
    │   ├── SimilarPagesPanel.jsx
    │   ├── AttachmentPanel.jsx
    │   ├── BacklinksPanel.jsx   "Referenced by" backlinks (GET /api/backlinks/{name})
    │   ├── CommentsDrawer.jsx   Threaded comments + @-mention composer
    │   ├── DiffViewer.jsx
    │   ├── MentionsPage.jsx     @-mention inbox (/me/mentions)
    │   ├── PersonalZone.jsx     Personal zone widget
    │   ├── BlogDiscovery.jsx / BlogHome.jsx / BlogEntry.jsx /
    │   │   BlogEditor.jsx / CreateBlog.jsx / NewBlogEntry.jsx
    │   ├── LoginPage.jsx / LoginForm.jsx / SsoLoginButton.jsx
    │   ├── UserPreferencesPage.jsx / ResetPasswordPage.jsx
    │   ├── TableOfContents.jsx / Breadcrumbs.jsx
    │   ├── admin/               Admin panel pages + forms (see below)
    │   ├── pagegraph/           Page Graph viewer (lazy-loaded)
    │   ├── kgraph/              Knowledge Graph viewer (lazy-loaded)
    │   └── ui/                  Shared primitive components (see below)
    ├── hooks/                   ~16 custom hooks (see below)
    ├── utils/                   ~22 utility modules (see below)
    └── styles/
        ├── globals.css          Reset, CSS variables, theme tokens
        ├── article.css
        └── admin.css
```

## Markdown Rendering Pipeline

Article bodies are rendered client-side with `react-markdown` and the
following plugin chain:

- `remark-gfm` — tables, strikethrough, task lists
- `remark-math` — recognise `$…$` and `$$…$$`
- `rehype-katex` — typeset math via KaTeX (CSS and fonts bundled from the
  npm package, not a CDN)
- `rehype-highlight` — syntax-highlight fenced code blocks
- A custom `remarkAttachments` plugin — rewrites attachment links so they
  resolve relative to the current page

See [MathematicalNotation.md](MathematicalNotation.md) for the full math
syntax reference.

## Graph Viewers

Wikantik ships two distinct, lazy-loaded graph viewers in the SPA. **Don't
conflate them** — they reflect two separate subsystems (see
[PageGraphVsKnowledgeGraph](wikantik-pages/PageGraphVsKnowledgeGraph.md)
for the long form):

### `/page-graph` — Page Graph viewer

Real wikilink edges between pages. Nodes are pages; edges are wikilinks
parsed from page bodies, with co-resident structural information
(`canonical_id`, `cluster`) layered in. Hubs (nodes above a configurable
degree threshold) are highlighted; anomalies (restricted pages, orphans)
can be toggled into view. Components live under
`wikantik-frontend/src/components/pagegraph/`:

| Component | Role |
|-----------|------|
| `PageGraphView.jsx` | Top-level container — fetches snapshot, owns state |
| `GraphCanvas.jsx` | Cytoscape canvas with CoSE-Bilkent layout |
| `GraphToolbar.jsx` | Edge-type filters, anomaly toggle, refresh |
| `GraphZoomSlider.jsx` | Manual zoom control (semantic zoom) |
| `GraphLegend.jsx` | Hub threshold and edge-type legend |
| `GraphDetailsDrawer.jsx` | Selected-node details + incident edges |
| `FilterPanel.jsx` | Per-cluster / tag filtering |
| `GraphErrorBoundary.jsx` / `GraphErrorState.jsx` | Failure UI |
| `graph-data.js` | Converts the REST snapshot into Cytoscape elements |
| `graph-style.js` | Cytoscape stylesheet |

Snapshot comes from `PageGraphSnapshotResource` in `wikantik-rest`,
backed by the Page Graph subsystem (`com.wikantik.pagegraph.*`). A
`?focus=PageName` query parameter auto-centres and selects a node on
load — useful for linking to the graph from an article.

### `/knowledge-graph` — Knowledge Graph viewer

Reader-facing visualisation of the LLM-extracted entity graph. Nodes are
typed entities; edges are co-mention or typed relations between them.
Includes tier filter, node-type colours, provenance/status badges, and a
large-graph warning gate that prompts before rendering big subgraphs.
Components under `wikantik-frontend/src/components/kgraph/`:

| Component | Role |
|-----------|------|
| `KnowledgeGraphView.jsx` | Top-level container |
| `KgGraphToolbar.jsx` | Tier filter, node-type filter, refresh |
| `KgGraphLegend.jsx` | Node-type colour key |
| `KgGraphDetailsDrawer.jsx` | Selected-node provenance + incident edges |
| `KgErrorState.jsx` | Failure UI |
| `kg-graph-data.js` | Converts the REST snapshot into Cytoscape elements |
| `kg-graph-style.js` | Cytoscape stylesheet |

Snapshot comes from `KnowledgeGraphResource` in `wikantik-rest`, backed
by `DefaultKnowledgeGraphService` in `wikantik-knowledge`.

## Design System

The CSS design system is intentionally minimal and framework-free. Tokens live
in `src/styles/globals.css` and drive light and dark themes. The full `:root`
block (light theme):

```css
:root {
  /* Typography */
  --font-display: 'Playfair Display', Georgia, 'Times New Roman', serif;
  --font-body: 'Source Serif 4', Georgia, serif;
  --font-ui: 'DM Sans', -apple-system, BlinkMacSystemFont, sans-serif;
  --font-mono: 'JetBrains Mono', 'Fira Code', 'Cascadia Code', monospace;

  /* Light theme */
  --bg: #FEFCF8;
  --bg-elevated: #FFFFFF;
  --bg-sidebar: #F7F4EE;
  --text: #2D2D2D;
  --text-secondary: #6B6560;
  --text-muted: #9B9590;
  --accent: #C45D3E;           /* terracotta */
  --accent-hover: #A8442A;
  --sage: #7A8B6F;
  --sage-light: #E8EDE4;
  --border: #E8E4DC;
  --border-strong: #D0C8BC;
  --code-bg: #F3F0EA;

  /* Semantic state */
  --success: #2a8d2a;
  --danger: #b13a3a;
  --warning: #b8860b;

  /* Layout */
  --sidebar-width: 280px;
  --content-max-width: 1470px;
  --content-max-width-wide: 1600px;
  --toc-width: 200px;
}

[data-theme="dark"] {
  --bg: #1A1A1E;
  --bg-elevated: #222226;
  --bg-sidebar: #18181C;
  --text: #E8E4DC;
  --text-secondary: #A8A09A;
  --accent: #E07050;           /* lighter terracotta for dark bg */
  --accent-hover: #F08060;
  /* … */
}
```

`body` uses `var(--font-ui)` (DM Sans). Long-form article text uses
`var(--font-body)` (Source Serif 4) applied in `article.css`. The
`--content-max-width` of 1470px is for the main reading/article layout; admin
and wide contexts use `--content-max-width-wide: 1600px`.

## Comments and Mentions

`CommentsDrawer` renders threaded comment threads anchored to page text
selections. It mounts inside `PageView` alongside `BacklinksPanel`. The
`@`-mention composer is powered by `useMentionPicker` + `MentionPicker`.
Unread mention counts are tracked by `useUnreadMentions`. The personal
mention inbox lives at `/me/mentions` (`MentionsPage`).

For the full feature spec see [docs/CommentsAndMentions.md](CommentsAndMentions.md).

## Blog

Blog routes all live under `/blog/*`. `BlogDiscovery` lists all author blogs;
`BlogHome` is an author's blog index; `BlogEntry` renders a single post;
`BlogEditor` is the post editor (also reachable via `/edit/blog/:username/:pageName`);
`CreateBlog` and `NewBlogEntry` handle blog and post creation. The hook
`useMyBlog` manages the current user's blog state.

For the full feature spec see [docs/Blog.md](Blog.md).

## Personal Zone

`PersonalZone` (`/components/PersonalZone.jsx`) surfaces the current user's
recently-edited pages, drafts, and other personal signals. Related hooks:
`useMyPages`, `useRecentlyViewed`, `useDraft`, `useDrafts`.

For the full feature spec see [docs/PersonalZone.md](PersonalZone.md).

## Update Toast

`App.jsx` listens for the custom DOM event `wikantik:version-mismatch`,
dispatched by `api/client.js` when the server's `X-Build-Version` response
header (set by `BuildVersionFilter` in `wikantik-rest`) differs from the
embedded `__BUILD_VERSION__` constant (stamped at Vite build time via
`vite.config.js`'s `buildVersionPlugin`). When a mismatch is detected the app
renders a non-blocking "A new version is available — Reload" toast. The toast
can be dismissed per session; the session flag is cleared once the new bundle
loads successfully.

## Admin Panel

Accessed at `/admin` and protected server-side by `AdminAuthFilter` (requires
`AllPermission`). `AdminSidebar` replaces the reader `Sidebar` via a context
swap in `App.jsx` whenever `location.pathname.startsWith('/admin')`.

### AdminSidebar navigation

Links are grouped into four sections:

**People & Access**
- Users (`/admin/users`)
- Security (`/admin/security`)
- API Keys (`/admin/apikeys`) — see [docs/ApiKeys.md](ApiKeys.md)

**Content**
- Content & Index (`/admin/content`)
- Page Ownership (`/admin/page-ownership`) — see [docs/PageOwnership.md](PageOwnership.md)

**Knowledge & Search**
- Knowledge Graph (`/admin/knowledge-graph`)
- KG Policy (`/admin/kg-policy`) — see [docs/KgInclusionPolicy.md](KgInclusionPolicy.md)
- Retrieval Quality (`/admin/retrieval-quality`) — see [docs/RetrievalQuality.md](RetrievalQuality.md)

**Observability**
- Audit (`/admin/audit`) — see [docs/AuditLog.md](AuditLog.md)

Plus an **Overview** index link at `/admin` (exact match).

### AdminContentPage tabs

`AdminContentPage` uses a tab bar defined as:

```
Dashboard | Orphaned Pages | Broken Links | Versions | Chunk Inspector | Index Status
```

- **Dashboard** — page count, orphan count, broken link count, cache statistics table, and a "Flush All Caches" action.
- **Orphaned Pages** — pages with no inbound links; bulk-delete with checkbox selection.
- **Broken Links** — missing target pages and their referrers.
- **Versions** — look up a page by name, inspect its version list, purge old versions keeping N latest.
- **Chunk Inspector** — (`ChunkInspectorTab`) inspect embedding chunks per page.
- **Index Status** — (`IndexStatusTab`) search index health and rebuild trigger.

### AdminKnowledgePage tabs

`AdminKnowledgePage` uses a tab bar defined as:

```
Proposals | Extraction | Node Explorer | Edge Explorer |
Content Embeddings | Hub Proposals | Hub Discovery | LLM Activity
```

- **Proposals** (`ProposalReviewQueue`) — review pending KG node/edge proposals; approve or reject individually.
- **Extraction** (`ExtractionTab`) — run or cancel the LLM entity extractor; watch live progress.
- **Node Explorer** (`GraphExplorer`) — browse/search all KG nodes by type or status; inspect properties; project frontmatter.
- **Edge Explorer** (`EdgeExplorer`) — browse/search all KG edges by relationship type; inspect provenance.
- **Content Embeddings** (`ContentEmbeddingsTab`) — inspect the shared Ollama mention-centroid index; bulk-backfill missing frontmatter.
- **Hub Proposals** (`HubProposalsTab`) — generate/manage hub membership proposals; approve/reject individually, in bulk, or by percentile threshold.
- **Hub Discovery** (`HubDiscoveryTab`) — cluster-based hub discovery; accept/dismiss proposal cards; manage existing hub memberships.
- **LLM Activity** (`LlmActivityTab`) — live in-memory view of recent/in-flight LLM calls (extraction, proposal judging, embeddings); last ~1 hour only, cleared on restart.

## Shared UI Components (`components/ui/`)

A set of reusable primitives used across both the reader and admin panel:

| Component | Purpose |
|-----------|---------|
| `Badge.jsx` | Inline status/label badge |
| `Card.jsx` | Content card container |
| `Chip.jsx` | Removable filter chip |
| `EmptyState.jsx` | Zero-results placeholder with icon |
| `Icon.jsx` | Icon wrapper |
| `Modal.jsx` | Accessible modal dialog |
| `Skeleton.jsx` | Loading skeleton placeholder |
| `Spinner.jsx` | Loading spinner |
| `ToastProvider.jsx` | Toast context + renderer (used by `useToast`) |

## Hooks (`src/hooks/`)

~16 custom hooks (each paired with a `.test` file). Grouped by concern:

- **Auth & session**: `useAuth.jsx` (login state, current user via `AuthProvider` context)
- **Theme**: `useDarkMode.js` (toggle + localStorage persistence)
- **API / data fetching**: `useApi.js` (fetch + auth header plumbing), `useAttachments.js`, `useMyPages.js`, `useMyBlog.js`
- **Editor**: `useEditorDrop.js` (drag-and-drop into editor), `useDraft.js` / `useDrafts.js` (auto-save draft management)
- **Comments / mentions**: `useMentionPicker.js` (debounced `@`-mention autocomplete with caret tracking), `useUnreadMentions.js`
- **Navigation / UX**: `useGlobalHotkeys.js` (Cmd+K search trigger), `useScrollSpy.js` (ToC active heading), `useScrollLock.js` (modal body lock), `useFocusTrap.js` (modal focus management), `useDocumentTitle.js`
- **History**: `useRecentSearches.js`, `useRecentlyViewed.js`
- **Toast**: `useToast.js` (consumes `ToastProvider` context)

## Utils (`src/utils/`)

~22 utility modules (each paired with a `.test` file). Grouped by concern:

- **Markdown / rendering**: `remarkAttachments.js` (custom remark plugin for attachment links), `math.js` (LaTeX helpers), `rehypeSourceLine.js`, `headingAnchors.js`, `headings.js`, `codeCopy.js`, `highlight.js`, `markdownFormat.js`
- **Page / URL**: `pageUrl.js`, `slugUtils.js`, `frontmatterUtils.js`, `readingTime.js`
- **Comments**: `commentAnchor.js`, `commentHighlight.js`, `caretCoords.js`
- **Search**: `searchFacets.js`
- **Editor**: `wikiLinkComplete.js`, `scrollSync.js` (editor/preview scroll sync), `draftKeys.js`
- **Attachments**: `attachmentNameValidator.js`
- **Time**: `datetime.js`

## Authentication

The SPA uses session cookies set by the standard Wikantik login flow. All
`/api/*` calls include the session cookie; ACLs and policy grants are
enforced server-side by `RestServletBase.checkPagePermission()`. There is no
token or JWT layer — the SPA is a thin client of the same session the wiki
uses server-side.

Google SSO is available at the login page via `SsoLoginButton` when the
server is configured with `wikantik.sso.*` properties.

## Running the Frontend Standalone (Development)

For fast frontend iteration without a full Maven rebuild:

```bash
cd wikantik-frontend
npm install
npm run dev         # Vite dev server at http://localhost:5173/
```

The Vite dev server proxies three path prefixes to `http://localhost:8080`
(hard-coded in `vite.config.js` — there is no `VITE_API_BASE` environment
variable):

| Proxy path | Target |
|-----------|--------|
| `/api` | `http://localhost:8080` |
| `/attach` | `http://localhost:8080` |
| `/admin` | `http://localhost:8080` |

A running backend instance on port 8080 is required for most functionality.

Unit tests:

```bash
npm test            # vitest run (single pass)
npm run test:watch  # vitest watch mode
```

Note: Vitest concurrency can cause false failures in some hook tests — if a
test file fails in a full run, re-run it in isolation before chasing the
failure.

## Production Build

The production build runs as part of `mvn package`. To build the frontend
directly:

```bash
cd wikantik-frontend
npm run build       # outputs to dist/
```

The `vite.config.js` `buildVersionPlugin` stamps a `build-version.txt` asset
and a `<meta name="build-version">` tag into `index.html` at build time;
`BuildVersionFilter` (in `wikantik-rest`) reads this file and echoes the
version in `X-Build-Version` response headers so the update toast can detect
stale bundles.

The `wikantik-war` module's `pom.xml` copies `dist/` into the WAR during the
packaging phase — no manual copy step is required.
