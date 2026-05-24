# Changelog

All notable changes to Wikantik are recorded here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
