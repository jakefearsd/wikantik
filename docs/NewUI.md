# Wikantik React SPA

The `wikantik-frontend` module implements Wikantik's user interface as a React
18 single-page application. It is built with Vite as part of the standard
Maven build, bundled into the WAR, and served at the web root — there is no
separate hosting, no CORS configuration, and no legacy JSP templates alongside
it.

## Status

Fully implemented. The SPA covers reading, inline editing, search, change
history, similar-pages discovery, attachment management, blogs, dark mode, the
admin panel (users, content, security, knowledge), and the knowledge graph
visualiser.

## Architecture

| Concern | Choice |
|---------|--------|
| Framework | React 18 (`react`, `react-dom`) |
| Routing | React Router v6 (`react-router-dom`) |
| Build | Vite 5 (`@vitejs/plugin-react`) |
| Markdown rendering | `react-markdown` + `remark-gfm`, `remark-math`, `rehype-katex`, `rehype-highlight` |
| Graph visualisation | `cytoscape` + `cytoscape-cose-bilkent` via `react-cytoscapejs` |
| Testing | Vitest + Testing Library + `happy-dom` |
| Styling | Hand-written CSS with a token-based design system (no framework) |
| State | React state + hooks; no external store library |

The build runs automatically during `mvn package` — `frontend-maven-plugin`
installs Node and npm, runs `npm ci` and `npm run build`, and the resulting
`dist/` is copied into the WAR.

## Routing

The SPA is served from `/` and uses client-side routing for all interactive
views. Deep links are handled by `SpaRoutingFilter` (in `wikantik-http`), which
forwards SPA paths to `/index.html` so the React router can take over.

| Route | Purpose |
|-------|---------|
| `/` | Home — recent pages, clusters, entry points |
| `/wiki/{PageName}` | Article reader |
| `/edit/{PageName}` | Inline editor (markdown source + live preview) |
| `/search` | Search results (full-text + frontmatter facets) |
| `/graph` | Knowledge graph visualiser |
| `/admin/*` | Admin panel (users, content, security, knowledge) |

The REST API lives under `/api/` and the MCP server under `/mcp/` — both are
serviced by `wikantik-rest` / `wikantik-mcp` rather than the SPA.

## Project Layout

```
wikantik-frontend/
├── package.json
├── vite.config.js
├── index.html
├── public/
└── src/
    ├── main.jsx                 Entry point
    ├── App.jsx                  Routes + layout shell
    ├── api/
    │   └── client.js            Fetch wrapper for /api/*
    ├── components/
    │   ├── PageView.jsx         Article rendering (react-markdown pipeline)
    │   ├── PageEditor.jsx       Inline editor
    │   ├── Sidebar.jsx          Cluster-grouped navigation
    │   ├── SearchOverlay.jsx    Cmd+K search
    │   ├── MetadataPanel.jsx    Frontmatter chips
    │   ├── ChangeNotesPanel.jsx History + diffs
    │   ├── SimilarPagesPanel.jsx
    │   ├── AttachmentPanel.jsx
    │   ├── DiffViewer.jsx
    │   ├── BlogHome.jsx / BlogEntry.jsx / BlogEditor.jsx …
    │   ├── admin/               Admin panel pages + forms
    │   └── graph/               Knowledge graph viewer (see below)
    ├── hooks/
    │   ├── useApi.js            Fetch + auth header plumbing
    │   ├── useAuth.jsx          Login state, current user
    │   ├── useDarkMode.js       Theme toggle + persistence
    │   ├── useAttachments.js
    │   └── useEditorDrop.js
    ├── utils/
    │   ├── math.js              LaTeX helpers for the markdown pipeline
    │   ├── frontmatterUtils.js
    │   ├── pageUrl.js
    │   ├── slugUtils.js
    │   ├── attachmentNameValidator.js
    │   └── remarkAttachments.js Custom remark plugin for attachment links
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

## Knowledge Graph View

The `/graph` route renders an interactive, zoomable graph of page
relationships. Nodes are pages; edges are backlinks, frontmatter relations
(`related`, `cluster`), and cluster membership. Hubs (nodes above a
configurable degree threshold) are highlighted; anomalies (restricted pages,
orphans) can be toggled into view.

| Component | Role |
|-----------|------|
| `GraphView.jsx` | Top-level container — fetches snapshot, owns state |
| `GraphCanvas.jsx` | Cytoscape canvas with CoSE-Bilkent layout |
| `GraphToolbar.jsx` | Edge-type filters, anomaly toggle, refresh |
| `GraphZoomSlider.jsx` | Manual zoom control (semantic zoom) |
| `GraphLegend.jsx` | Hub threshold and edge-type legend |
| `GraphDetailsDrawer.jsx` | Selected-node details + incident edges |
| `GraphErrorBoundary.jsx` / `GraphErrorState.jsx` | Failure UI |
| `graph-data.js` | Converts the REST snapshot into Cytoscape elements |
| `graph-style.js` | Cytoscape stylesheet |

The snapshot comes from `GET /api/knowledge/graph` (served by
`KnowledgeGraphResource` in `wikantik-rest`, backed by
`DefaultKnowledgeGraphService` in `wikantik-knowledge`). A `?focus=PageName`
query parameter auto-centres and selects a node on load — useful for linking
to the graph from an article.

## Design System

The CSS design system is intentionally minimal and framework-free. Tokens live
in `src/styles/globals.css` and drive light and dark themes:

```css
:root {
  --bg-primary: #ffffff;
  --bg-secondary: #f9fafb;
  --text-primary: #111827;
  --text-secondary: #6b7280;
  --border: #e5e7eb;
  --accent: #059669;
  --font-body: Charter, 'Bitstream Charter', Cambria, serif;
  --font-heading: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  --font-code: 'SF Mono', Consolas, Menlo, monospace;
}

[data-theme="dark"] {
  --bg-primary: #0f0f0f;
  --bg-secondary: #171717;
  --text-primary: #f9fafb;
  --text-secondary: #9ca3af;
  --border: #374151;
  --accent: #10b981;
}
```

Typography follows a major-third scale; body text sits at 1.25rem with a 1.7
line height to favour long-form reading. A max content width of roughly 680px
keeps reading lines comfortable, widening to ~1000px for tables and code.

## Admin Panel

Accessed at `/admin/` and protected by `AdminAuthFilter` (requires
`AllPermission`). Tabs map to dedicated pages in `components/admin/`:

- **Users** — list, create, edit, delete; role assignment
- **Content** — orphaned pages, broken links, version purging, cache stats
- **Security** — groups, group membership, policy grants (the
  database-backed replacement for `wikantik.policy`)
- **Knowledge** — hub discovery, existing hubs, content embeddings, KG
  embeddings, proposal review queue

## Authentication

The SPA uses session cookies set by the standard Wikantik login flow. All
`/api/*` calls include the session cookie; ACLs and policy grants are
enforced server-side by `RestServletBase.checkPagePermission()`. There is no
token or JWT layer — the SPA is a thin client of the same session the JSP-era
wiki used to serve.

## Running the Frontend Standalone (Development)

For fast frontend iteration without a full Maven rebuild:

```bash
cd wikantik-frontend
npm install
npm run dev         # Vite dev server at http://localhost:5173/
```

Point the dev server at a running backend by setting the `VITE_API_BASE`
environment variable, or keep the default which proxies `/api/*` to
`http://localhost:8080/`. See `vite.config.js` for the proxy configuration.

Unit tests:

```bash
npm test            # vitest
```

## Production Build

The production build runs as part of `mvn package`. To build the frontend
directly:

```bash
cd wikantik-frontend
npm run build       # outputs to dist/
```

The `wikantik-war` module's `pom.xml` copies `dist/` into
`wikantik-war/target/Wikantik/` during the packaging phase — no manual copy
step is required.
