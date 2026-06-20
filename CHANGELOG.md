# Changelog

All notable changes to Wikantik are recorded here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Corpus math-syntax fixer.** `MathSyntaxFixCli` (with `MathSyntaxFixer`) batch-repairs the page
  corpus: it escapes prose-currency `$` and reformats single-line `$$ x $$` into blank-line-isolated
  display blocks. It mirrors `MathStructureValidator` exactly — escaping the opening `$` of a
  prose-flagged inline pair only when it is followed by a digit, iterating to a fixpoint — so
  number-led math (`$2^{256}$`, `$90^\circ$`) is never broken.

### Changed
- **Documentation refreshed for the current architecture.** `WikantikArchitecture` was rewritten as
  a deep architecture reference (the module reactor, the three-graph model, the retrieval stack, the
  knowledge + ontology layer, and the agent MCP surface, with strengths, an honest critique, and a
  roadmap) and the front page was reframed around the human + AI-agent value proposition. The README,
  the marketing site, and the linked docs now describe production retrieval as BM25 + dense fused
  with RRF (fail-closed); the Knowledge-Graph-aware rerank is shelved and off by default, having
  measured no net ranking lift. MCP tool counts were corrected throughout (knowledge-mcp 20 tools,
  admin-mcp 26).

### Fixed
- **Frontmatter save-warnings no longer leak across pages.** `FrontmatterWarningSink` was a flat
  per-thread slot, so a nested or concurrent save of a different page could clobber the outer page's
  warnings (surfacing a foreign "summary is 188 chars" warning on `update_page`). The stash is now
  keyed by page name, so `update_page`, `write_pages`, and `PageResource` each drain exactly their own.

## [2.0.21] - 2026-06-20

### Removed
- **Legacy property-file API keys removed.** The `mcp.access.keys` and `tools.access.keys`
  comma-separated property keys are gone — not deprecated. DB-minted keys (via `/admin/apikeys`)
  are now the only Bearer-token key source. The CIDR allowlist (`*.access.allowedCidrs`) and
  `*.access.allowUnrestricted` flags are unchanged. **Operators must have at least one DB-minted
  key before upgrading** or the endpoint fails closed with HTTP 503.

### Added
- **Retrieval-aware content authoring.** `verify_pages` gains a `retrieval_readiness` check
  (summary specificity, heading quality, cluster, title — the frontmatter levers prepended into
  chunk embeddings) and a new `list_retrieval_queries` admin-MCP tool over the real query log
  (V041) for finding under-served queries. The `wiki-content` skill now documents the
  author → static-lint → live-bundle-check verification loop.
- **Self-service API keys.** Logged-in users can list, generate, rotate, and revoke their own
  API keys from the **API Keys** section of `/preferences`, backed by a new ownership-enforced
  `/api/self/apikeys` resource. Keys are bound to the caller's own principal (no privilege
  escalation), secrets are shown once, and storage stays hashed-only — "recovery" is by reissue.
- **Last-login column in admin → Users.** A new `users.last_login` column (V042) is stamped on
  every successful authentication (form login, SSO, remember-me re-auth) and shown next to
  *Created*; accounts that have not authenticated since the column shipped render as an em dash.

### Fixed
- **`update_page` (admin-MCP) is merge-safe.** Metadata now merges onto the page's existing
  frontmatter instead of silently wiping it, and `content` is optional — omit it for a
  metadata-only edit that leaves the body untouched.
- **Embeddings self-heal on startup.** Stale or dropped chunk embeddings (from a bulk edit or a
  crash mid-re-embed) are reconciled at boot, so retrieval converges without a manual rebuild.

## [2.0.20] - 2026-06-19

### Added

- **Retrieval query logging.** Every retrieval query across `/api/bundle`, `/api/search`,
  `assemble_bundle` (knowledge-mcp) and `search_wiki` (`/tools`) is captured to a new
  `retrieval_query_log` table — query text, inferred actor (`human` | `agent` | `unknown`),
  source surface, and result count — to ground the section-recall eval corpus in real traffic.
  Human vs agent is inferred from surface + auth (MCP/tools are agent by construction; `/api` is
  `human` when session-authed, `agent` when `Authorization`-header'd, else `unknown`). Writes are
  asynchronous and fail-open — a logging failure never slows or breaks retrieval — and capture
  query text only (never results), so restricted content never enters the log. Default ON via
  `wikantik.querylog.enabled` (migration `V041`).

### Removed

- **Lexical-injection retrieval stack + superseded eval spikes.** The shelved code-symbol
  lexical-injection path (`LexicalInjectionSource`, `InjectionConfig`, `SymbolDetector`, the
  `wikantik.bundle.inject.*` knobs) was removed after the retrieval-experiment review found the base
  hybrid already handles ~88% of identifier queries (revive from git history if real agent traffic
  shows code-symbol queries). Twelve measured-and-rejected one-shot eval spike scripts were also
  deleted; their verdicts are banked in `eval/bundle-corpus/baseline-notes.md`.

### Fixed

- **Two benign startup ERROR logs eliminated.** (1) A malformed wikilink with a whitespace-only
  target (e.g. illustrative `[ ]( )` syntax inside a code span, which the regex scanner still
  matched) produced a blank page reference and logged `Illegal page name: ''` during reference-graph
  init — `MarkdownLinkScanner.findLocalLinks` now drops blank link targets, and the `related:`
  frontmatter scan filters whitespace-only entries. (2) A non-absolute `mcp.instructions.file` is now
  ignored quietly (the override must be an absolute path) instead of logging an ERROR before the
  (correct) fallback to the bundled instructions; a genuinely-unreadable absolute path now logs WARN.

## [2.0.19] - 2026-06-16

### Added

- **Derived pages — document ingestion (RAG-as-a-Service Phase 2).** PDFs, office documents
  (docx/pptx/xlsx), and text/markdown files become first-class wiki pages: the source binary is
  retained as an attachment, the body is **extracted via Apache Tika** (XHTML→markdown), and the
  page carries `derived_from` provenance frontmatter — riding every existing rail (search,
  embeddings, Knowledge Graph, ontology, chunking, the context bundle). The pure extractor lives in
  a new **`wikantik-ingest`** module (isolates the heavy PDFBox/POI dependencies). Ingestion is
  idempotent (filename-named page, update-in-place, source-SHA dedup); the body is **machine-owned
  and regenerable** (ADR-0004) — human edits are at-own-risk, version history is the recovery path.
  Surfaces: **`POST /api/ingest`** (multipart upload, `createPages`-gated), a batch
  **`IngestDocumentsCli`** (HTTP client over `/api/ingest`), and **`/admin/derived/{reflow,status}`**
  — reflow re-extracts from the retained source, clobbering the body while preserving
  body-independent curation (tags, verification, KG). A frontend "ingest as derived page" action +
  an editor "machine-owned body" banner. New code in `com.wikantik.ingest.*` (module) and
  `com.wikantik.derived.*` (wikantik-main). Config: `wikantik.citations.enabled` unaffected; Tika 3.3.0.

- **Citation edges + self-healing grounding (RAG-as-a-Service Phase 3).** Claims grounded in
  other pages are written as inline `cite://` body markup —
  `[claim](cite://<canonical_id>/<Heading Path> "verbatim span")` — and parsed at save into a
  derived, re-derivable `citations` table (migration `V040`). Each citation is version-pinned
  and span-hashed; staleness is **graded** and **span-level**: `current` → the cited span is
  still present in the target section, `stale` → the span drifted (content changed / heading
  moved), `target_missing` → the target page is gone (rename-safe via `canonical_id` liveness).
  Version drift alone is ignored (churn is the steady state). A `WikiEventListener` reconciles a
  page's outbound citations and re-grades inbound citations on every save/rename/delete, and a
  full `reconcileAll()` rides the ontology-rebuild cadence as the completeness safety net.
- **Bidirectional stale-citation surfaces.** `GET /admin/drift/citations` (outbound + inbound +
  status counts), the read-only **`list_stale_citations`** tool on `/knowledge-mcp`, and a
  `stale_citations` field on the `/api/pages/for-agent/{id}` projection — so agents and humans
  work the same self-healing curation queue. Rendered `cite://` links resolve to the target
  page; staleness is never shown to anonymous readers. Config: `wikantik.citations.enabled`
  (default true; no-op without a datasource). New code in `com.wikantik.citation`.

### Security

- **Page authorship is now derived from the authenticated session, not caller input.** `POST /api/ingest`,
  `PUT /api/pages`, and `/admin/derived/reflow` record the author as the session principal; a caller-supplied
  `author` (query param or request body) is ignored. Closes an authorship-spoofing / audit-forgery gap.
- **Document-upload surface hardening (derived pages).** Upload filenames are sanitized into a safe page name
  via the same `MarkupParser.cleanLink` the attachment store uses — preventing path traversal and arbitrary-page
  overwrite (ingest now refuses to overwrite a non-derived page). Tika extraction is bounded by a write limit +
  timeout so a malicious upload cannot OOM or hang the server. A failed attachment store rolls back a
  newly-created page (no orphans). The batch CLI uses HTTP Basic auth (the only scheme `/api/*` accepts).

## [2.0.18] - 2026-06-14

### Added

- **RAG-as-a-Service context bundle.** `GET /api/bundle?q=…` (REST) and the
  `assemble_bundle` tool on `/knowledge-mcp` return an assembled **context bundle** —
  a ranked, de-duplicated, **version-pinned-cited** set of wiki sections for a query,
  for grounding an agent. It does **not** synthesize an answer. Each section carries a
  `CitationHandle` (canonical_id + page version + span SHA-256). Backed by the new
  `com.wikantik.knowledge.bundle` layer.
- **Bundle-quality evaluation harness** — a frozen, section-level gold corpus
  (`eval/bundle-corpus/`) plus a deterministic recall / precision@K / citation-faithfulness
  runner, wired as a pre-merge gate.
- **Contextual document embeddings.** Each chunk is embedded with its page context
  (`Page: {title} | Cluster: {cluster} | Section: {heading-path}` + summary, from
  frontmatter) prepended — the largest single retrieval-recall lever (global
  section-recall@12 ≈ 0.60 → 0.74). The query side keeps its instruction prefix.
- **Configurable chunking knobs** — `wikantik.chunker.fragment_floor_tokens` (default 24)
  and `wikantik.chunker.overlap_tokens` (default 40); bundle knobs
  `wikantik.bundle.{dense.enabled, dense.top_k, sections_per_page, reranker.enabled}`.

### Changed

- **Bundle retrieval is global dense-chunk by default** — the top-K chunks across the
  whole corpus, grouped to sections, instead of a page-gated shortlist. It realizes the
  retrieval ceiling that the page pre-select was capping (realized bundle recall@12
  0.50 → 0.71 across the release's retrieval work).
- **Knowledge-graph extraction defaults to `gemma4-graph:12b`** with `think:false` sent
  on every Ollama extraction/judge request (a reasoning trace breaks structured-JSON
  extraction and is 10–20× slower).
- **KG rerank stays off** (`graph.boost = 0.0`) pending a Phase-4 fair trial on relational
  questions — replacing a stale `boost=0` "TEMP DIAGNOSTIC" override with an explicit state.

### Fixed

- **Chunker heading fidelity.** The chunker's merge-forward logic let a short preamble
  carry the *first* section's `heading_path` onto later sections' content, so early /
  first-`##` sections were unfindable by their own heading **and their citations were
  mis-anchored**. Each chunk's `heading_path` now matches the section its content came
  from. (Requires a content rebuild to take effect on existing pages.)

## [2.0.17] - 2026-06-12

### Added

- **Breadcrumb is now a clickable navigation-history trail.** The reader
  breadcrumb shows the last 3 distinct pages visited in the tab (oldest → newest);
  prior pages link to `/wiki/{slug}` and the current page is the last entry. Backed
  by a per-tab `usePageTrail` hook (sessionStorage — survives refresh, fresh per
  tab, works for anonymous readers). The SEO `BreadcrumbList` JSON-LD emitted
  server-side stays hierarchical and is intentionally unaffected.
- **Knowledge MCP `sparql_query` gains an optional `format: "compact"`** that
  returns token-dense flat `{var: value}` rows for `SELECT` queries.
- **`list_tags` / `list_clusters` are paginated** (`limit` default 50, `offset`,
  with `count` / `returned` / `hasMore` in the response).

### Changed

- **MCP page-identifier parameters converge on `slug` / `slugs`** across the admin
  and knowledge tool surfaces. Legacy `pageName` / `pageNames` (and guessable
  `name` / `page`) are still accepted as aliases via shared `pageSlug` /
  `pageSlugs` accessors, but only `slug` / `slugs` are advertised in the schemas;
  a convergence-guard test keeps it that way.
- **Stricter math validation on save.** Single-line or text-glued `$$ … $$`
  display math (which the parser mis-renders) is now a blocking **ERROR**, and
  prose inside inline `$…$` (e.g. an unescaped currency `$`) raises an advisory
  **WARNING**, both false-positive-guarded.
- **`ping_search_engines` is annotated as not read-only** (`readOnlyHint=false`,
  `openWorldHint=true`).

### Fixed

- **Blocked-save errors on non-common frontmatter fields are now visible.** The
  editor auto-expands the "More fields" disclosure when a field inside it has a
  validation error, so a 422-blocked save always shows its reason (regression from
  the 2.0.16 density redesign, which hid those inline errors inside the collapsed
  section).
- **Further corpus formula-rendering repairs** — isolated single-line `$$` display
  math across additional pages and resolved the remaining currency-`$` / glued-`$$`
  defects surfaced by the production render audit.

## [2.0.16] - 2026-06-12

### Added

- **Math (LaTeX) validation on save.** A KaTeX-oracle-derived LaTeX syntax linter
  (`MathValidationPageFilter`) runs on every page save, flagging malformed or
  un-isolated display math as blocking **errors** or advisory **warnings**. The
  violations are surfaced inline in the editor (`MathValidationSummary`, with
  click-to-jump), returned as structured `ContentViolation`s from the REST save
  path (`PageResource`), and enforced on the admin MCP write tools
  (`write_pages` / `update_page`).

### Changed

- **Structured frontmatter editor is far denser.** Fields are split into an
  always-open **Common** block (title/type/status/summary/tags/cluster) and a
  collapsible **More fields** disclosure (whose summary shows a `(N set)` count of
  populated fields); the read-only derived fields (canonical_id/confidence/
  agent_hints) move to a compact muted meta strip; every field is an inline
  `[label][control]` row. The default footprint shrinks ~65% (≈700–900px → ≈475px).
- **Page-scoped Knowledge Graph tab densified.** Tighter rows, smaller embedded
  type-selects and provenance badges, relation rows aligned compact-left, and a
  compact empty-state so empty Entities/Relations sections no longer reserve ~128px
  of padding (all-empty tab ≈561px → ≈228px).
- **Database application role defaults to `wikantik`**, and deploy-time migrations
  run as the owning role.

### Fixed

- **Corpus-wide math rendering repair** — isolated inline-glued display-math
  delimiters across 102 pages, and CRLF is normalized before math validation.
- **`bin/redeploy.sh` migration step.** It hardcoded a nonexistent `migrate` DB
  role with no password and never sourced `.env`, so the migration step failed and
  aborted every local redeploy before Tomcat started. It now sources `.env` and
  runs migrations as the app role (`POSTGRES_USER`, default `wikantik`) with a
  `postgres` superuser fallback, mirroring `deploy-local.sh`.

## [2.0.15] - 2026-06-11

### Added

- **First-login forced password change.** A fresh install seeds exactly one
  account — `admin` / `admin123` — flagged so the first login *requires* choosing
  a new password before anything else works. The general-purpose
  `users.password_must_change` flag (migration V039) is also raised when an
  administrator sets or resets a user's password and when the password-reset email
  issues a temporary one; it is cleared when the user changes their own password
  (NIST-validated, so the seeded default cannot be re-chosen). A
  `MustChangePasswordFilter` gates `/api/*` and `/admin/*` with
  `403 PASSWORD_CHANGE_REQUIRED` for flagged sessions — exempting only the auth
  surface needed to fix the situation — and the SPA routes flagged users to a
  forced `/change-password` screen (at login and mid-session).
- **Getting Started guide** (`docs/GettingStartedGuide.md`) walking first-time
  deployers through both the Docker Compose and bare-metal Tomcat paths, with a
  first-build pitfalls table.
- **First-start banners** in `deploy-local.sh` and the container entrypoint that
  print the initial credentials and the forced-change notice on a fresh database.

### Changed

- **Single canonical admin seed across both install paths.** The container init
  SQL (`docker/db/001-init.sql`) is reduced to enabling `pgvector`; schema and the
  admin seed now come exclusively from the numbered migrations via `migrate.sh`.
  This removes the long-standing split where a fresh container seeded a *different*
  admin password (`admin`) than the migrations (`admin123`). `seed-users.sql` is
  now admin-only and insert-if-absent, so a changed password survives every
  redeploy; personal/dev accounts move to a gitignored `seed-users.local.sql`.
- **`install-fresh.sh` fails fast on missing secrets.** It no longer defaults
  `DB_APP_PASSWORD`, and requires either `DB_MIGRATE_PASSWORD` or an explicit
  `--no-migrate-role` opt-out — surfacing the schema-ownership decision up front
  instead of as a failing ALTER migration later. Docs (`CLAUDE.md`, README, Docker
  and PostgreSQL deployment guides) were aligned to the single `admin / admin123`
  forced-change credential story and a consistent `wikantik` local DB user.

### Security

- No Wikantik install runs with a known default credential past first login: the
  seeded admin must set a password before any gated API call succeeds, and
  `DELETE /api/auth/*` (account self-deletion) stays gated for flagged sessions so
  a hijacked first-login session cannot delete the account in lieu of changing the
  password.

### Fixed

- **Scheduled off-box backups never ran (day-1 bug).** BusyBox `crond` in the
  backup sidecar silently ignored the bind-mounted crontab because the
  git-checkout file is group-writable and not root-owned; every snapshot since the
  feature shipped was a manual trigger. The compose entrypoint now stages the
  crontab to `/etc/crontabs/root` as `root:root 0600` before starting `crond`, and
  the proven `/proc/1/environ` env-import in `backup.sh` (cron strips the compose
  environment) is now committed.

## [2.0.14] - 2026-06-10

### Added

- **Live validation in the structured frontmatter editor.** Debounced, race-safe,
  fail-open validation against `/api/frontmatter/validate` surfaces errors and
  warnings inline as you edit. Save is disabled while blocking (ERROR-severity)
  issues exist; advisory warnings never block. Runbook sub-field violations now
  render against the matching control, with a validation-summary strip
  (counts + jump-to-field). The editor CSS was rebased onto the app's design
  tokens with a denser two-column grid.
- **Synonym-aware entity-type classification.** Both extractors resolve common
  LLM type synonyms (`database`/`framework`/`library`/`tool`/`service`/… →
  `technology`, plus organization/person/place/event/… synonyms) via a shared
  `EntityTypeVocabulary.TYPE_ALIASES`, instead of collapsing them to `concept`
  (chunk extractor) or dropping them (page extractor) — the source of mis-typed
  technologies under the `wk:implements` SHACL shape.

### Fixed

- **Audit writer dropped every batch under a least-privilege DB role.**
  `JdbcAuditRepository.ensurePartition` ran `CREATE TABLE … PARTITION OF` on every
  append; that DDL fails with `permission denied for schema public` for an app role
  with `USAGE` but not `CREATE` on schema `public` (the correct posture, and the
  PostgreSQL 15+ default), even when the partition already exists. It now checks
  existence first via a privilege-free `to_regclass` lookup.
- **Runbook frontmatter validator rejected legitimate `related_tools` entries.**
  `/admin/*` REST surfaces and kebab-case CLI tool names (`kg-policy`, `kg-extract`)
  are now accepted; a runbook validation failure returns a structured HTTP 422
  (field-addressable violations) instead of an opaque error, for all clients.

## [2.0.13] - 2026-06-08

### Fixed

- **MCP `update_page` returned a stale, unusable content hash.** The
  `newContentHash` was computed from a tool-side *reconstruction* of the submitted
  text, but the save-time `StructuralSpinePageFilter` rewrites the persisted bytes
  afterwards (canonical_id injection, frontmatter/date/line-ending normalization).
  The returned hash therefore drifted from what `read_page` reports, so an agent
  that chained a second edit using it hit a false `hash mismatch`. `update_page`
  now hashes the **actual persisted text** (re-read via `getPureText` — the same
  source `read_page` and the optimistic-lock check use), so the returned hash is
  authoritative and directly chainable. Tool docs corrected: content hashes are
  bare lowercase hex (no `sha256:` prefix) and come from `read_page`.

### Changed

- **Audit event listener** refactored to table-driven dispatch (declarative lookup
  maps replace the security/page-event `switch` blocks); behavior unchanged. Adds
  unit coverage for the audit mappings and for ten previously untested
  `wikantik-api` value/exception classes.
- **IndexNow verification key** rotated (ownership re-verification); the new key is
  served at the web root.

## [2.0.12] - 2026-06-07

A focused SEO / crawlability / rendering release. The headline fix: Google was
classifying every `/wiki/` page as a **Soft 404**, so none of them ranked.

### Added

- **SEO: social/sharing images.** Every page emits `og:image` + `twitter:image`
  (`twitter:card=summary_large_image`) — a per-page frontmatter `image:` when set, else a
  bundled 1200×630 default (`og-default.png`) — so links unfurl with a card on
  Slack/X/LinkedIn and qualify for Google Discover.
- **SEO: sitelinks search box.** The homepage emits `WebSite` + `SearchAction` JSON-LD so
  Google can show a search box for branded queries.
- **SEO: fuller SERP snippets.** `<meta name="robots" content="max-image-preview:large,
  max-snippet:-1, max-video-preview:-1">` on every page (does not affect indexability).
- **IndexNow.** `ping_search_engines` can now notify Bing/Yandex: `WIKANTIK_INDEXNOW_API_KEY`
  is wired through the container entrypoint to `wikantik.indexnow.apiKey`, with the key
  served as a static verification file at the web root.
- **SSR data island.** `SpaRoutingFilter` injects `window.__WIKANTIK_PAGE__` (the page's
  rendered HTML + metadata) so the React reader paints content immediately instead of
  refetching `/api/pages` — and crawlers' JS renderers never see an empty "Loading…" DOM.
- **Footer build version.** The site footer now shows the running build version.

### Fixed

- **Soft 404 across the wiki.** The reader refetched content from
  `/api/pages/{name}?render=true`, but `robots.txt` blocked all of `/api/`, so Google's
  renderer fetched nothing and saw an empty page. `robots.txt` now `Allow: /api/pages/`
  (ordered before the broad `Disallow: /api/`); the JSON stays `X-Robots-Tag: noindex`.
- **HTTP 404 for missing pages.** `/wiki/{nonexistent}` now returns HTTP 404 (with the SPA
  shell body so React still renders its NotFound view) instead of 200, so search engines
  drop dead URLs instead of soft-404'ing them.
- **baseURL for request-less tools.** `ping_search_engines` and `preview_structured_data`
  now read the configured `wikantik.baseURL` instead of the empty ROOT-context servlet
  context path, so they build absolute sitemap / IndexNow / structured-data URLs.

### Changed

- **Duplicate-content guards.** `/wiki/{slug}?format=md|json` responses are now
  `X-Robots-Tag: noindex` + `Link: rel="canonical"` to the HTML page.
- **robots.txt** blocks thin / auth-only routes: `/page-graph`, `/knowledge-graph`,
  `/login`, `/me/mentions`.
- **Crawlable no-JS body.** `SpaRoutingFilter` injects the full rendered HTML into `#root`
  (rendered once, reused by the data island) so non-JS crawlers get real content rather
  than a raw-text fallback.
- **Reduced layout shift (CLS).** The Similar-Pages and Backlinks panels render below the
  article instead of above it, so their async load no longer pushes the LCP element down.
- **Richer publisher metadata.** Article / CollectionPage JSON-LD `Organization` publisher
  now includes `url` + `logo`.

## [2.0.11] - 2026-06-06

### Added

- **AdSense `ads.txt`.** Added the Google authorized-sellers record
  (`google.com, pub-5083997587716933, DIRECT, f08c47fec0942fa0`) at the root of both
  served domains so AdSense can verify ad ownership: `wikantik-war/src/main/webapp/ads.txt`
  (served verbatim at `https://wiki.wikantik.com/ads.txt` — `SpaRoutingFilter` passes any
  `.`-bearing, non-`.html` path through to the default servlet, so it isn't swallowed by
  SPA routing) and `marketing/ads.txt` (served at `https://www.wikantik.com/ads.txt`; the
  apex `wikantik.com/ads.txt` reaches it via the existing CF apex→www redirect). A
  build-time guard (`AdsTxtTest`) pins the exact publisher line and fails closed on HTML
  contamination.

- **test:** opt-in parallel IT execution. `bin/run-tests.sh --it --parallel N` (or the
  `IT_PARALLELISM` env var) runs all four IT modules in a single `-T N` reactor; each
  module now reserves its own free TCP ports (Postgres, Cargo servlet + RMI + AJP,
  OIDC/SAML mock servers) via build-helper and uses a per-module-unique pgvector container
  name, so they no longer collide on the shared `55432`/`18080`/`8205`/`8009` ports.
  Module configs (`jdbc.url`, OIDC `discoveryUri`, `wikantik.baseURL`) were parameterised
  on the reserved ports. Default behaviour is unchanged (sequential, one module at a
  time); `--parallel 4` cuts the IT phase from ~3.5 min to ~1.5 min.

- **test:** parallelize an audited cluster of read-only browser ITs (JUnit-5 `@Execution(CONCURRENT)`) to trim custom-jdbc wall-clock.

- **Audit log retention purge.** `bin/db/audit-retention.sh` (scheduled monthly via a
  systemd timer, `bin/db/audit-retention-install-timer.sh`) pre-creates upcoming
  `audit_log` partitions and **archives-then-drops** partitions older than a
  configurable window (default **7 years**): each over-age partition is `pg_dump`ed to
  the off-box-backed archive directory and verified before it is dropped. Runs as the
  privileged `migrate` role (the app role stays INSERT/SELECT-only); the hash chain
  re-anchors on the oldest surviving row with no application-code change. A `< 1`-month
  guardrail and `--dry-run`/`--status` flags guard against accidental mass-drops.
- **SCIM 2.0 group provisioning** (`/scim/v2/Groups`). An IdP can sync group
  membership (which drives Wikantik ACLs / policy grants) via SCIM: create / read /
  list+filter (by `displayName`) / `PUT` / `PATCH` (member add, remove via
  `members[value eq "<uid>"]`, replace) / hard `DELETE`. Member changes flow through
  the audited `GroupManager` path. **Hard invariant: SCIM Groups never grant the
  Wikantik `Admin` role** — groups and the role table are separate stores, enforced by
  an integration-test assertion. `externalId` is keyed on `displayName` (not persisted).
- **SCIM 2.0 user provisioning** (`/scim/v2/Users` + discovery endpoints). An IdP
  (Okta/Entra) can automate onboarding and offboarding via bearer-authed SCIM. All
  deactivate/reactivate flows go through one unified, audited `UserLifecycleService`
  (shared by the admin UI and SCIM): `active:false` and `DELETE` both soft-decommission
  via the existing indefinite-lock mechanism (the user row is retained so audit and
  page-ownership references stay intact), `active:true` reactivates. SCIM-created users
  reconcile with SSO via the `sso.subject` marker (fail-closed on a non-SSO name
  collision). New `wikantik-scim` module. See
  [ScimProvisioningDesign](docs/wikantik-pages/ScimProvisioningDesign.md).

### Changed

- **Unified modal dialog styling.** The rename and delete dialogs now match the New
  Article / Login dialog family: centered display-font heading, muted field labels,
  bordered inputs, and a right-aligned action row. Extracted shared `.form-input` and
  `.field-label` classes and promoted `.modal-actions` into `globals.css` so reader-side
  dialogs pick them up regardless of route; `Modal` now honours a `style` prop, and the
  `search-dialog` double-padding was removed. The Graph/Edge explorer filter inputs gain
  real styling (the `.form-input` class was previously undefined).

### Fixed

- **Integration-test gate restored.** The post-2.0.10 dynamic-ports refactor left
  `wikantik-selenide-tests` without the `build-helper` re-declaration (so its Cargo
  servlet port resolved to empty → "Invalid port number"), and the SCIM sample fixtures
  were committed under `wikantik-it-test-rest` rather than the shared selenide
  test-resources directory every IT module reads from (so `ScimVendorPayloadIT` failed
  with "missing fixture"). Both fixed; the full IT reactor is green again.

- **Audit hash chain now verifies for events with a `detail` payload.** `detail` was
  stored as JSONB, which PostgreSQL reformats on read, so the verify-time rehash no
  longer matched the insert-time hash — any audited event carrying `detail` (e.g.
  `page.rename`, SCIM `user.deactivate`) broke `verifyChain`. `detail` is now stored as
  TEXT (exact round-trip; migration V037). Also fixed `page.rename` events never being
  audited (the listener was not registered against the `PageRenamer`).

- **Tamper-evident audit log.** A compliance-first, append-only audit trail
  capturing authentication/authorization (login, logout, session expiry, access
  denied), content changes (page save/delete/rename), admin/security-config
  actions (policy-grant changes, user enable/disable, API-key issuance), and
  opt-in sensitive page reads (frontmatter `audit_reads: true` or a configured
  cluster set, default off). Records are written by a single async writer under a
  PostgreSQL advisory lock and chained with SHA-256 (each row hashes the previous
  row's hash), so any edit or deletion of history is detectable. The `audit_log`
  table is month-partitioned and `INSERT`/`SELECT`-only to the app role
  (`UPDATE`/`DELETE` revoked). New admin surface: `GET /admin/audit` (filterable),
  `GET /admin/audit/verify` (chain integrity), `GET /admin/audit/export?format=csv`,
  and an **Audit** tab in the admin panel. Dropped-entry count is exposed as the
  `wikantik_audit_dropped_total` gauge. See
  [AuditLogDesign](docs/wikantik-pages/AuditLogDesign.md).

## [2.0.10] - 2026-06-02

### Changed

- **Side-by-side editor overhaul.** The source and preview panes now share one
  fixed-height region and each scroll internally (the preview no longer clipped
  early while the source grew). Scrolling/typing in either pane keeps the other
  aligned (bidirectional, frontmatter-zone-aware), and clicking a block in the
  preview jumps + centers the editor caret on that block's source line. The
  stripped frontmatter is shown as a compact collapsible card atop the preview.

### Fixed

- **Theme toggle now updates the editor pane immediately.** `useDarkMode` held
  per-instance state, so toggling in the sidebar left the editor's CodeMirror on
  its old theme until a refresh; all consumers now share one store.

### Dependencies

- npm: react-markdown 9→10, react-router-dom 6→7, plus minor/patch bumps
  (cytoscape, happy-dom, katex). React 19 and the vite 8 / vitest 4 toolchain
  deferred (real migrations).
- Maven GA minor/patch: junit 6.1.0, selenide 7.16.2, pac4j 6.5.3,
  anthropic-java 2.35.0, jaxen 2.0.5, jaxb-runtime 4.0.9, plus surefire,
  maven-dependency, and sonar plugins.

## [2.0.9] - 2026-06-02

### Changed

- **Sidebar cluster navigation** is consolidated under a single collapsible
  **"Browse Clusters"** section (collapsed by default) rather than a long stack
  of per-cluster sections; the active page's cluster still auto-expands inside.
  Its header matches the other section titles, top-level nav titles now use full
  text colour, and the expanded cluster tree has tighter spacing.

### Security

- **HTTP response-splitting guard** on the SPA `Location` redirects in
  `SpaRoutingFilter` — request-derived path/query fragments are rejected if they
  contain CR/LF before being written to the redirect header.

### Fixed

- SpotBugs `WMI_WRONG_MAP_ITERATOR` in `WatchDog`, `AbstractFileProvider`, and
  `DefaultKnowledgeGraphService` (iterate `entrySet()` instead of `keySet()` + `get()`).

### Internal

- Cut `PageForAgentResource.toJson` from an NPath complexity of ~110k to
  straight-line by extracting per-section JSON builders (wire format unchanged).
- Removed duplication flagged by CPD: shared `KgProposalRepository` filter clause,
  an `AbstractSpamStrategy` base for the spam helpers, a shared `ProposalVerdictParser`
  for the Claude/Ollama judges, and `denseRanking` lifted into `ExperimentHarness`.

## [2.0.8] - 2026-06-01

### Added

- **Backlinks panel.** The reader page view now shows a "Referenced by" panel
  listing the pages that link to the current page (backed by `/api/backlinks`).
- **Collapsible cluster tree** in the reader sidebar — clusters are now
  expandable/collapsible sections (state persisted), with the active page's
  cluster auto-expanded and an "Uncategorized" bucket for clusterless pages.
- **Search faceting.** The search results page gained a filter rail
  (topic / author / tag / modified-date) that narrows results client-side.
- **Editor insert helpers.** Table and fenced-code-block toolbar buttons, plus
  `[[`-triggered autocomplete that inserts internal `[Name](Name)` wiki links
  from the page list.

### Fixed

- **Sidebar cluster grouping.** `/api/pages` returned a cluster for only the
  first 100 pages (the structural-index query silently capped at 100), so the
  sidebar dumped most pages into "Uncategorized". It now reads the full sitemap
  projection — cluster coverage went from 100 to 1161 of 1204 pages.

## [2.0.7] - 2026-05-31

## [2.0.6] - 2026-05-30

## [2.0.5] - 2026-05-25

### Fixed

- **Search-engine indexing.** `robots.txt` advertised the sitemap on the wrong
  host (`wiki.jakefear.com`), so cross-domain rules made Google ignore it;
  it now points at `https://wiki.wikantik.com/sitemap.xml`. Every wiki page
  served the generic `<title>Wikantik</title>` — pages now emit a unique
  `<title>` from their frontmatter `title:` (falling back to the page name),
  and that readable title also flows into `og:title`/`twitter:title` instead of
  the raw page slug. Operator guide: [docs/SeoAndCrawling.md](docs/SeoAndCrawling.md).

## [2.0.4] - 2026-05-25

### Added

- Site footer on reader pages (including the home page) linking to the privacy
  policy and terms of service, satisfying OAuth-provider home-page requirements
  for SSO app verification.

## [2.0.3] - 2026-05-24

### Added

- **Single Sign-On (OIDC + SAML 2.0 via pac4j).** Google OIDC is live in
  production. Configurable through `wikantik.sso.*` (and `WIKANTIK_SSO_*` env
  vars in containers); full operator reference in
  [docs/SingleSignOn.md](docs/SingleSignOn.md). Includes a `/login` SPA route
  that surfaces SSO `?error=` codes, and public privacy/terms pages for
  provider onboarding.
- **Self-service account deletion** — `DELETE /api/auth/profile` with a
  preferences-page UI, a lockout-safe last-admin guard, and cascade cleanup of
  the user's group memberships and API keys.
- **Admin UI refresh** — grouped context-swap sidebar, a sectioned Overview
  dashboard landing page, and hybrid table density.
- **Off-box backup & recovery** — pull-model NAS backups, per-tier Prometheus
  textfile metrics, restore-drill verification, and `bin/dr-restore.sh` for
  one-command disaster recovery to a fresh host.

### Security

- SSO identity binding keys on the immutable `wikantik.sso.identityClaim`
  (default `sub`); auto-provisioned profiles carry an `sso.subject` marker and
  a name collision with a non-SSO local account **fails closed**. Successful
  SSO login rotates the HTTP session (fixation defense); IdP claims are
  sanitised (multi-valued normalised to first scalar, blank/control-character
  login names rejected).
- Anonymous and stateless API-key requests no longer create `HttpSession`s
  (fixes a session-leak regression).

## [2.0.2] - 2026-05-22

### Added

- **Lucene HNSW dense-retrieval backend** (`wikantik.search.dense.backend=lucene-hnsw`),
  now the production default — an in-process approximate-nearest-neighbour index
  (RAM `ByteBuffersDirectory`, rebuilt on boot from `content_chunk_embeddings`)
  that replaces the brute-force vector scan. Tunable via
  `wikantik.search.dense.lucene.{m,ef_construction,ef_search}`; rollback is a
  one-line flip to `inmemory`. Recall held within 0.02 nDCG@5 of brute force.

### Performance

- Dense retrieval no longer scans every chunk per query (had been ~60% of search
  CPU); the HNSW index visits a few hundred candidates, with chunk metadata read
  via Lucene DocValues (no per-hit stored-field decompression).
- Eliminated DB connection-pool exhaustion under load by caching the per-request
  lookups that drained it — API-key verification, user lookup by login name, and
  Knowledge Graph mention joins (short-TTL Caffeine; auth caches evict on
  revoke/mutation). At a 1200-VU search-heavy load this moved the server from
  congestion collapse (233 RPS, p95 11 s) to healthy (825 RPS, p95 361 ms).
- Removed per-request shared-lock contention on the request hot path: `Collator`
  (principal sort in every authz check), `TimeZone` (JSON date serialization),
  and `SecureRandom` (request-correlation IDs) are no longer re-acquired per
  request.
- Cut three search read-path costs: Lucene stored-field over-read when the
  highlighter is off, the wiki-syntax heuristic on every page GET, and a
  per-query regex recompile in Knowledge Graph entity resolution.

### Changed

- Backpressure admission cap (`WIKANTIK_MAX_INFLIGHT_REQUESTS`) default lowered
  700 → **390**: it must sit below Tomcat `maxThreads` (400) to take effect — the
  filter holds permits on worker threads, so the old default could never fire.
- DBCP `maxWaitMillis` 10 s → 5 s — fail faster now that the connection pool is
  no longer the bottleneck.

## [2.0.1] - 2026-05-17

## [2.0.0] - 2026-05-16

### Added — Admin UI

- `AdminTable` + selection-bar primitives with server-driven pagination,
  server-driven filtering, and a uniform bulk-action surface across all
  admin views.
- Bulk-action verticals: API Keys (revoke), Users (lock / unlock / delete),
  Knowledge Graph Proposals (approve / reject).
- Knowledge Graph Proposals admin: typed `Details` renderer (no more raw
  JSON), clickable `Machine` reasoning column with timestamps, machine-rejected
  filter, "Reject (no reason)" one-click speed action.
- Knowledge Graph Viewer reader route at `/knowledge-graph` (mirrors the
  Page Graph viewer): tier filter, node-type colours, provenance/status
  badges, large-graph warning.
- Knowledge Graph inclusion policy (cluster-primary), with admin dashboard
  at `/admin/kg-policy/*`, `bin/kg-policy.sh` CLI, and `kg_include` page
  frontmatter override.
- Verification metadata in frontmatter (`verified_at`, `verified_by`,
  `confidence`, `audience`) plus operator triage at `/admin/verification`.
- Runbook page type (`type: runbook`) with a six-key schema, save-time
  validation, and graceful read-time degradation.

### Added — Agent-facing surface

- 25 write/analytics MCP tools on `/wikantik-admin-mcp`.
- 16 read-only retrieval / Knowledge Graph traversal / structural-spine /
  agent-projection MCP tools on `/knowledge-mcp`.
- 2 OpenAPI 3.1 tools on `/tools/*` for OpenWebUI-compatible non-MCP clients.
- Worked input/output examples on every MCP / OpenAPI tool schema for
  reliable first-call success.
- `GET /api/pages/for-agent/{canonical_id}` token-budgeted page projection
  (summary, key facts, headings, recent changes, MCP tool hints, verification
  state) backed by a 1h / 5K-entry cache.
- Retrieval-quality CI (`DefaultRetrievalQualityRunner`): nightly nDCG@5/@10,
  Recall@20, MRR across BM25 / HYBRID / HYBRID_GRAPH, persisted to
  `retrieval_runs`, exposed at `/admin/retrieval-quality` and as Prometheus
  gauges.

### Added — Retrieval / search

- Hybrid retrieval pipeline: BM25 + dense (pgvector) + Knowledge Graph-aware
  rerank, with fail-closed BM25 fallback.
- Per-page entity-extraction pipeline (`bin/kg-extract.sh`) feeding the
  Knowledge Graph proposal queue with deduplicated, evidence-grounded
  extractions.
- LLM judge for proposal verdicts with per-proposal-type prompt branching
  (edge vs. node) and a pre-flight validator that synthesises explicit
  abstain reasons for malformed proposals.

### Added — Page Graph subsystem

- Structural-spine save-time enforcement: pages saved without `canonical_id`
  get one auto-assigned (toggle: `wikantik.structural_spine.enforcement.enabled`).
- Generated `Main.md` from `Main.pins.yaml` (hand-edits now revert on
  regeneration).
- `/api/structure/*` REST surface and matching MCP tools.
- Operator triage at `/admin/page-graph/conflicts`.

### Added — Deployment

- Tomcat 11.0.22 with hands-off `bin/deploy-local.sh` upgrade flow
  (snapshot, restore, template materialisation).
- Container-first deploy path: `Dockerfile`, `docker/entrypoint.sh`,
  `docker-compose.{yml,dev,prod,test}.yml` with `pgvector/pgvector:pg17`,
  automatic migration runner, env-injected DB password,
  `<CookieProcessor sameSiteCookies="strict"/>`, single canonical Tomcat-conf
  source-of-truth, stdout dual-write logging.
- `bin/db/migrate.sh` idempotent migration runner with `schema_migrations`
  ledger; runs automatically on every container start and every
  `deploy-local.sh` invocation.
- `bin/redeploy.sh` fast iteration helper: shutdown → rotate `catalina.out` →
  swap WAR → startup, no template / migration / secrets work.

### Added — Security

- AdminAuthFilter passes SPA navigation through to the React shell so the
  SPA's own login flow handles unauthenticated state.
- SPA `wikantik:auth-required` event: 401/403 mid-session triggers an
  auth-state refresh so stale-session clicks fail visibly.
- Database-backed policy grants (`policy_grants` table) and database-backed
  groups, both manageable from `/admin/security`.
- `<CookieProcessor sameSiteCookies="strict"/>` on both bare-metal and
  container deploys.
- NIST 800-63B password validation with common-password blocklist.
- `ObjectInputFilter` whitelists on every `ObjectInputStream` usage.

### Changed

- Frontend bundle structure: admin pages, editors, blog, and graph
  viewers are now lazy-loaded; only the reader hot path stays in the main
  chunk.
- `WikiEngine` initialisation refactored across 11 phases — typed subsystem
  accessors, factory-driven wiring, registry deletion. `getManager(Class)`
  removed from the public `Engine` interface; callers use typed subsystem
  reads.
- Search: `LuceneSearchProvider` decomposed (1251 → 724 LOC) into a facade
  plus three helpers; Lucene helpers wired into the Search subsystem.
- Typed `relations:` frontmatter removed (2026-05-02). Use `get_outbound_links`
  / `get_backlinks` on `/wikantik-admin-mcp` for Page Graph traversal instead.

### Fixed

- 66 pages with malformed YAML frontmatter (60 orphaned `relations:` children
  from the typed-relations removal, 6 unquoted-colon titles) — frontmatter now
  parses for the entire corpus.
- Knowledge Graph judge was sending edge-shaped prompts for new-node
  proposals, causing 99.93% of new-node proposals to abstain. Fix: branch the
  system prompt by proposal type and add a pre-flight validator for required
  fields.
- `/admin/knowledge-graph/proposals` machine-rejected filter actually loads
  rejected proposals.
- `JSESSIONID` no longer hard-codes `Secure` (broke local HTTP development);
  the cookie's secure flag is set by the container's session config.
- 15 integration tests in `wikantik-it-test-rest` were silently skipped
  due to an explicit failsafe whitelist; now discovered via `**/*IT.java`.
- Pagination on `/admin/knowledge-graph/proposals` is now stable across pages
  (id-DESC tiebreak; `count_proposals_filtered` mirrors the list filter).

### Operations

- Backup sidecar tested end-to-end: `pg_dump` + `pages.tar.gz` with SHA-256
  checksums, tiered retention, full restore round-trip verified
  (1079 pages + 25 migrations preserved across a wipe + restore).
- `docs/DockerDeployment.md` covers external services (Postgres, Ollama,
  SMTP), bare-metal ↔ container coexistence on alternate ports, and the
  one-time bare-metal-to-container migration procedure.
