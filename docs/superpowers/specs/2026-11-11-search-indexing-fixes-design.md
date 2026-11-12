# Search indexing fixes — design

Date: 2026-11-11. Status: awaiting approval.

## Why

Google indexes little of `wiki.wikantik.com` and organic traffic is low. An external review of
the live site found five causes we can fix in code. Visitors arrive from the marketing site or a
blog post looking for technical detail about Wikantik; the general-topic articles both showcase
the wiki and should rank on their own.

What the review found, each confirmed against the live site on 2026-11-10/11:

1. `https://wiki.wikantik.com/` returns the bare app shell (`<title>Wikantik</title>`, "Loading…",
   no canonical, no description, no structured data). `/wiki/Main` is server-rendered properly.
2. After JavaScript runs, the app replaces the server's title "NoSQL Database Types - Wikantik"
   with "Wikantik: NoSqlDatabaseTypes".
3. Every server-rendered page carries a second `<h1>` ("Wikantik") and the line "JavaScript is
   required to use Wikantik".
4. About 60 pages describing the home network (host pages, 48 incident runbooks with LAN
   addresses and `.lan` names) are public and in the sitemap.
5. Sitemap dates are meaningless: of 1,457 URLs, 783 share 2026-10-24 and 295 share 2026-11-09,
   both from bulk frontmatter edits.

## Goals

- `/` serves the home page's real content with full head metadata, and is the canonical home URL.
- The browser title after JavaScript runs matches the server-rendered title.
- One `<h1>` per server-rendered page; no "JavaScript is required" text on pages that have content.
- Pages in named private clusters are invisible to anyone outside the allowed groups, on every
  surface, including pages created later.
- Sitemap `<lastmod>`, JSON-LD `dateModified` and the news sitemap reflect the last *substantial*
  change to the article text.

## Non-goals

- Dropping or noindexing weak clusters (declined by the owner).
- Hiding `stockroom`, `pressroom`, `wikantik-development`, `wikantik-platform` or `simple-agility`.
  They stay public. The private-cluster list will be tuned later, once the mechanism works.
- A `noindex` mechanism. Hiding is done by permissions only.
- An admin screen for the private-cluster settings.
- Search Console actions and backlink building (manual; listed under "After deploy").

## Anti-goals

- A hidden page reachable through any side door: search, `?format=md|json`, `/api/*`, the
  knowledge MCP, bundle, briefing, feeds, sitemap, news sitemap, SPARQL/`/id`/`/export`, link
  previews/embeds, "similar pages", backlinks lists, the Page Graph, recent changes, or a render
  cache serving an admin's view to an anonymous reader.
- A page-level rule that re-opens a page its cluster hides.
- A second, separate enforcement path per surface. One check, in the permission layer.
- jakemon's runbook publishing breaking.

## Part 1 — Private clusters

### Decided by the owner

- The base mechanism is a **cluster rule** in Wikantik, not something jakemon writes into pages.
- Start with `home-lab-infrastructure` and `remote-host-management`.
- Viewers: the **Admin group**, plus groups the owner explicitly adds later.
- A page-level rule may **only restrict further**; it can never widen access to a page its
  cluster hides.

### Design (builder's calls marked)

Two new settings in `ini/wikantik.properties` (declare first, per `ConfigSurfaceDriftTest`):

- `wikantik.access.privateClusters` — comma-separated cluster paths. Shipped default: blank
  (feature off). Prod sets the two clusters above in its custom properties. *(call: shipped
  default blank so a fresh install is unaffected.)*
- `wikantik.access.privateClusters.viewers` — comma-separated group names allowed in addition to
  Admin. Default blank. *(call: one global viewer list, not per cluster.)*

Semantics:

- A page is private when **any** of its cluster memberships (`ClusterPath.memberships`, scalar or
  list) equals a private cluster **or is a sub-cluster of one** (compare with `ClusterPath`, never
  `startsWith`). The hub page of a private cluster is private too. *(call: sub-clusters inherit.)*
- For a private page, **every page permission** (view, edit, comment, rename, delete, …) requires
  `AllPermission` (Admin) or membership in a viewer group. *(call: all actions, not view only —
  a page you can't see you can't edit.)*
- The effective access is the **intersection** of the cluster rule and the page's own
  `[{ALLOW …}]` rules. A page ACL naming `Anonymous` or `All` on a private page grants nothing
  beyond the cluster rule.
- A locked page (unreadable access rules) stays locked to Admin only, as today; viewer groups do
  not get in.

Where it lives: in the page-permission decision that every surface already goes through —
`DefaultAuthorizationManager.checkPermission` → `aclManager().getPermissions(page)`
(`DefaultAuthorizationManager.java:153–250`, `DefaultAclManager`). The builder chooses whether
the cluster rule is folded into the effective ACL or applied as a preceding gate, but must
satisfy:

- The parsed-ACL cache is keyed by (name, version, lastModified). The cluster comes from
  frontmatter, so a version bump already invalidates it; a **settings change** must not leave
  stale entries (settings are read at startup, so restart is acceptable — say so in the property
  description).
- `checkPermission` has a metadata-free path (line ~234) that builds a page only to read its
  ACL. The cluster rule must work there: read the cluster from the stored page text/frontmatter
  or the structural index, not from request-scoped page attributes.
- Source of truth for a page's clusters in this decision is the page's **stored frontmatter**
  (a frontmatter-only parse of the raw text — no flexmark body parse, the path is parse-free on
  purpose). The structural index is event-fed and may lag, so it may be used only as a cache
  that is never trusted for a page it has not yet seen at its current version: a brand-new
  private page must be private from its first save.
- Only cluster **membership** makes a page private. A public page that links to private pages
  (Main, a parent hub) is unaffected.
- Fail closed: if the cluster of a page cannot be determined (unreadable frontmatter), and the
  feature is on, treat the page as it is treated today (its own ACL) but log a WARN — *(call)*;
  do not throw.
- Guest-based surfaces (`PageViewGate` in the knowledge MCP, the ontology's
  anonymously-viewable materialization, `WikiPageFormatFilter`, `SitemapServlet` lines ~182–199)
  need no change if the rule is in the permission layer. **Prove it**, don't assume it: see tests.
- Ontology: pages that become private must leave the public TDB2 store. Confirm the nightly
  rebuild drops them and state in the runbook that a rebuild (or restart) is needed after
  changing the setting.
- Dense/BM25 indexes keep the chunks; retrieval filters by view permission at query time, as it
  does for ACL-restricted pages today. Confirm, don't re-index.
- Render caches: an Admin's render of a private page must not be served to others. Existing
  ACL-restricted pages already handle this; verify the cluster rule takes the same path.

Known and accepted consequences:

- jakemon's `bin/runbook-publish.py` writes through `/wikantik-admin-mcp`, which has no page
  permission checks, so publishing is unaffected.
- The knowledge MCP reads as guest, so agents stop seeing the runbooks there (including the
  Wikantik runbooks that are also in `wikantik-development`). The admin MCP can still read them.
- Public pages that link to a private page keep the link; it behaves like any link to a page the
  reader can't view (404 for them). No link rewriting.
- Anonymous requests for a private page get the same response as for any restricted page today
  (404 on `/wiki/*` and `?format=`), which is what gets them out of Google's index.

### Tests

- Unit: the rule itself — list-valued clusters, sub-cluster inheritance, intersection with a page
  ACL that tries to widen, viewer group, Admin, locked page, feature off.
- One **surface sweep** integration test (Cargo IT, per the "MCP/permission fixes need unit + IT"
  rule): create a page in a private cluster, then as anonymous assert it is absent from/denied by
  each surface in the anti-goal list, and as Admin assert it is present. This is the test that
  matters most.

## Part 2 — Home page at `/`

Finding: `SpaRoutingFilter` has a branch that 302-redirects `/` to a hard-coded `/wiki/Main`
(line ~216), and the filter **is** mapped to `/` in `web.xml`. Yet the prod origin
(`http://docker1:8080/`) answers `/` with 200 and the static shell, no redirect. The cause is
**not yet known** (likely the container resolving `/` to the default welcome file `index.html`
before/around the filter, so the path the filter sees is not `/`). First task of this part:
reproduce locally with a failing IT and find the cause (`systematic-debugging`); the fix
mechanism depends on it.

Decided by the owner: `/` serves the same content as `/wiki/Main`. Pass-through or redirect was
left to us; **call: pass-through**, with `/` as the canonical, because external links point at
the bare domain and it saves a hop.

- `GET /` returns the same server-rendered document as `/wiki/Main` (title, description,
  JSON-LD, body), with `<link rel="canonical" href="{baseUrl}/">` and matching `og:url`.
- `/wiki/Main` stays reachable (no redirect — *call*, keeps old links and the SPA route simple)
  but its canonical and `og:url` also point at `{baseUrl}/`.
- The sitemap lists `{baseUrl}/` and not `/wiki/Main`.
- The SPA treats `/` as the Main page without a client-side URL change to `/wiki/Main`; in-app
  "home" links (logo, breadcrumbs "Home") point at `/`. JSON-LD BreadcrumbList "Home" item uses `/`.
- `/wiki` and `/wiki/` 301 to `/`.
- Must work under a non-root context path (the IT modules run under one) — follow the filter's
  existing context-path handling. Caching headers/ETag as for `/wiki/Main`.
- The home page name comes from the existing `wikantik.frontPage` setting, not a hard-coded "Main".
- `/wiki/{frontPage}?format=md|json` keeps working unchanged. `/?format=…` is out of scope.
- The `<noscript>` change in Part 3 applies to `/` too (same server-rendered path).

## Part 3 — Titles and headings

- The browser title is `"{title} - Wikantik"` everywhere, where `{title}` is the page's
  frontmatter title (falling back to the page name), exactly as the server renders it. Change
  `useDocumentTitle.js` (and its callers), `PageEditor.jsx:922`
  ("Edit {title} - Wikantik" / "Create {name} - Wikantik"), search ("Search: {q} - Wikantik").
  The reader view must not change the title away from what the server sent for the same page.
- The home page title is whatever the server renders for it today.
- `wikantik-frontend/index.html`: the two `<h1>Wikantik</h1>` (loading fallback line 179,
  `<noscript>` line 191) become non-heading elements. On a server-rendered page the `<noscript>`
  "JavaScript is required" block must not appear (the content is already there); it remains on
  routes with no server-rendered content. Builder picks how (the SSR path already rewrites the
  shell).
- Out of scope: pages whose body `# heading` differs from the frontmatter title.

## Part 4 — Meaningful dates

Decided by the owner: only **larger** edits to the article text advance the date; this exists to
feed search engines.

- New per-page value **content-changed date**. Storage is the builder's call, with two
  constraints: it must not live in frontmatter (agents rewrite frontmatter), and if it is a table
  it needs a migration (DDL only) and goes through `Jdbc`.
- On save, compare the new body with the previous version's body after normalizing: strip
  frontmatter, CRLF→LF, collapse whitespace, drop a trailing `**Cluster Hub:**` footer line. The
  date advances when the word-level difference (added + removed words) is at least
  `min(5% of the previous word count, 100 words)`, **or** the set of headings gains a heading.
  Thresholds are settings (*call*: `wikantik.seo.contentChange.minPercent=5`,
  `wikantik.seo.contentChange.minWords=100`). A new page's date is its creation time.
- Small edits accumulate: compare against the body **as of the last recorded content-changed
  date**, not just the previous version, so ten small edits eventually count. *(call)*
- The baseline for the comparison is read from **version history at save time** (one extra
  version read per save is acceptable); do not store a second copy of page bodies in the
  database. Store only the date and the version number it refers to.
- A renamed heading counts as a gained heading. A renamed page keeps its date; a deleted page's
  row is dropped.
- Consumers switch from `page.getLastModified()` to this date: `SitemapServlet` `<lastmod>`
  (lines ~237–238), the news-sitemap two-day window (~246, ~378), JSON-LD `dateModified`
  (`JsonLdEmitter:109` via `SemanticHeadRenderer`), `og:updated_time`/`article:modified_time` if
  emitted. Not switched: Atom feed, `/api/changes`, recent changes, ETags — those report real
  saves. If the value is missing, fall back to `getLastModified()`.
- **Backfill** (not in a migration): an admin-triggered job or CLI walks each page's version
  history oldest→newest applying the same rule, and records the result. Idempotent, resumable,
  and bounded per page (*call*: if history is unavailable, use the oldest known version date
  rather than the file mtime of the latest bulk edit). On prod this must be handed to the owner
  to run.
- Performance: the sitemap must not read version history at request time; it reads the stored
  value in one query/lookup for all pages.

## Order and release

Four independent parts; suggested order by value and risk: Part 3 (small), Part 2, Part 4,
Part 1 (most security-sensitive, gets the final cross-cutting review). Parts may ship in one
release. Prod steps the owner runs: deploy, set the two properties, restart, trigger the
backfill, trigger an ontology rebuild.

## After deploy (owner, manual)

- Verify as anonymous: `/` has title/canonical/JSON-LD; an `Incident*` URL returns 404; the
  sitemap has neither `Incident*` nor host pages and shows varied dates.
- Search Console: "Validate fix" on the 5xx report; request indexing for `/` and the best 10–20
  articles; resubmit the sitemap.
- Backlinks: www.wikantik.com, the blog, GitHub READMEs → `https://wiki.wikantik.com/`.
