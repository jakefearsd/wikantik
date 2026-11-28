# CLAUDE.md

Rules and map only, ≤ 30 KB: this file loads into every session. Status, history and how-it-works go to docs/developer/ProjectReference.md.

<!-- SIMPLE-AGILITY-AGENT-HEADER-START -->
## Simple Agility Architectural Context
This repository is part of the **Simple Agility** small-business ecosystem:
- **Architecture Hub:** Refer to `SimpleAgilityStackHub` on Wikantik (`https://wiki.wikantik.com/SimpleAgilityStackHub`).
- **Architectural Decisions:** Consult `SimpleAgilityDecisionLog` before changing cross-repo APIs, logging, or telemetry.
- **Active Initiatives & Coordination:** Check `SimpleAgilityAgentCoordination` before starting multi-repo work.
- **Session start:** a UserPromptSubmit hook (`clients/claude-code/briefing-hook.sh`) injects the prod `wikantik-development` briefing once per session. Call `get_briefing(clusters=["wikantik-development"], prompt=…)` on the knowledge MCP yourself only when that block is missing or the task needs another cluster (SA-13).
- **Role Boundary:** Optimize local product logic, but never break cross-product contracts or telemetry conventions.
- **Record decisions:** when a change binds another repository (an API or event shape, a published port, a login or grant another product uses, a schema or view another product reads, a log or label name), add a new `SA-<n>` entry to `SimpleAgilityDecisionLog` (Decision, Why, Consequences) in the same piece of work, and link it from the normative page. Supersede, never rewrite.
- **Field report:** at the end of substantive work, file one field report with `file_agent_report` on the Wikantik knowledge MCP (skill `agent-field-report`, canonical text on `SimpleAgilityAgentFieldReportSkill`; `SA-22`).
<!-- SIMPLE-AGILITY-AGENT-HEADER-END -->

Wikantik, **jakemon** (`~/source/jakemon`, the observability plane) and **Roller** are Simple Agility components. Contracts live on the wiki: `SimpleAgilityObservabilityPlane` (what jakemon owns; the "do not build this in your backend" list), `SimpleAgilityTelemetryContract`, `SimpleAgilityFeedbackLoopPattern`, `SimpleAgilityOnCallAutomation`. Read **`SimpleAgilityRoadmap`** before going looking for a capability: contracts state intent, the roadmap states what is built.
- Read the plane page before adding any metrics/log/event store, poller, alerting, or SLO computation — those belong to jakemon. Apply its **"rule that decides where data lives"** first: telemetry emitted for *observation* goes to the plane, but data that is a *functional input* to Wikantik's behavior stays a product table — `retrieval_query_log`, `briefing_log`, `content_change_log`, `search_visibility_snapshot` and `/admin/audit` are correct as they are and must not be "consolidated" into Loki. Content Intelligence (`wikantik-insights`) is the reference implementation of the loop pattern.
- Conformance is machine-checked: `bin/simple-agility-conformance.sh --base-url http://localhost:8080 --prefix wikantik`

The preferred development approach is test-driven development: put a test in place to demonstrate a
defect before repairing it, so we know the test actually detects the issue.

- Sole developer on this repo — work directly on main, no feature branches or PRs.
- Never swallow exceptions with empty catch blocks — always log at least a `LOG.warn()` with context and message.
- Don't use plan mode for trivial tasks (git add, single commands) — just do them directly.

## Superpowers skills — when they apply

Superpowers skills are first-class here; use them fully for substantive work. This section sets the
*trigger boundary* and **overrides** the "even a 1% chance → you MUST invoke a skill before any
response" rule in the `using-superpowers` skill (that framework's own instruction-priority order puts
this file first).

**Invoke the matching skill for substantive work:**
- New feature, behavior change, or new component → `brainstorming` first; then `writing-plans` if it's multi-step.
- Implementing any feature or bugfix → `test-driven-development` (a test that fails first, per the TDD note above).
- Any bug, test failure, or unexpected behavior → `systematic-debugging` before proposing a fix.
- Finishing substantive work → `verification-before-completion`, and `requesting-code-review` before declaring it done.

**Skip the ceremony — just do the task — for:** git operations (add/commit/push), a single-file edit
with a clear spec, grep/glob/read lookups, answering questions about existing code, config/doc tweaks,
and anything finishable in 1–2 tool calls. Don't invoke a skill, don't narrate, don't open plan mode.

**When a task crosses the line** (a "quick edit" that turns out to need design, or a one-liner that
uncovers a bug), escalate to the matching skill the moment it stops being mechanical.

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java (JDK) | 25+ | `java -version` |
| Maven | 3.9+ (floor 3.5, `requireMavenVersion`) | `mvn -version` |
| Node.js + npm | 20.19+ (or 22.12+) | Vite 8 (Rolldown). The WAR build runs `npm install` + `vite build` |
| PostgreSQL | 15+ | Local deployment. **DB unit tests run against a real pgvector container** (`PostgresTestDb`, via Docker), migrations applied — no H2 schema. Without Docker they skip locally; CI passes `-Dtests.requireDocker=true` so an absent daemon **fails** the run |
| Docker (+ compose) | any recent | IT phase (per-module pgvector + the shared CPU-ollama embedder on 11435, or an external one via `IT_EMBED_URL` in env or `.env`; here `http://docker2.lan:11434`; no local Ollama) and `deploy-local.sh` (dev embedder on 11434; opt out `WIKANTIK_LOCAL_EMBEDDINGS=false`). First run pulls the ~600 MB `qwen3-embedding:0.6b` model: a download, not a hang. |
| Graphviz (`dot`) | any recent | Optional; only the `bin/site.sh` coupling SVG (else the raw `.dot` is linked). |

## Development Commands

### Build Commands
```bash
mvn clean install                 # standard build (tests + Apache RAT check)
mvn clean install -DskipTests     # faster; not for final checks
mvn clean install -T 1C -DskipITs # parallel; unit tests only
```

Use -DskipTests, never -Dmaven.test.skip: the latter skips the test-jars wikantik-tools and the ITs depend on.

Pass `-Dwikantik.surefire.forkCount=1` (default `0.5C`) to force a sequential unit run when debugging cross-test interaction.

### Long builds — subagent protocol

Any Maven invocation that can exceed ~5 minutes (full unit build, IT reactor) MUST be
run through `bin/agent-build.sh` — never as a bare foreground call (agent Bash calls
are killed at ~10 minutes) and never via bare `nohup mvn -q … &` (with `-q` a finished
build's log is indistinguishable from a crashed one):

```bash
bin/agent-build.sh start unit -- mvn clean install -DskipITs
bin/agent-build.sh status unit     # instant, one line: RUNNING | SUCCESS exit=0 | FAILED exit=N (+ log tail) | KILLED
bin/agent-build.sh wait unit 540   # bounded block ≤540s; exit 0=success 1=failed 2=still running
bin/agent-build.sh tail unit 30    # inspect the log
```

The script detaches the build into its own session (survives harness process-group
kills), unsets `WIKANTIK_*` env vars in the child, and appends an `EXIT=<code>`
sentinel to the log so completion is never ambiguous. Poll by calling `status` (or
bounded `wait`) repeatedly until the build terminates. **Never end your turn to "wait
for a notification"** — detached builds are not harness-tracked; nothing resumes an
idle agent. Keep polling until you can report SUCCESS or FAILED.

### Manual Testing Credentials

Admin `testbot` (role `Admin`, local database); credentials in `test.properties` (gitignored; `test.user.login`, `test.user.password`). Use it for browser, REST, curl (`curl -u testbot:<pw> -X POST http://localhost:8080/admin/content/rebuild-indexes`). Recreate after a database reset: see
[ProjectReference](docs/developer/ProjectReference.md#recreating-the-testbot-account).

### Testing Commands and Integration Test Parallelism
```bash
bin/run-tests.sh --parallel 4          # CANONICAL pre-commit gate (~6-8 min warm): unit phase, CI static checks, 4-wide parallel IT phase
bin/run-tests.sh --all                 # RELEASE gate: adds the opt-in suites; npm audit becomes fatal (~15 min)
bin/run-tests.sh                       # fallback: sequential IT phase (e.g. diagnosing a cross-module issue)
bin/run-tests.sh --module dense        # dense IT module alone
mvn clean install -T 1C -DskipITs      # unit tests only; parallel is fine here
```

The gate REQUIRES DOCKER (watch the shared embedder with `docker logs -f $(docker ps -qf name=wikantik-embed-test)`). Its static checks: PMD ratchet, npm audit (fails only under `--all`), eslint, vitest `--coverage`.

Parallel IT execution is supported ONLY through `bin/run-tests.sh --parallel N`: its single
reactor gives every IT module a build-helper-reserved free port set and a uniquely-named pgvector
container. Do NOT bolt `-T` onto a raw `mvn clean install -Pintegration-tests` invocation. run-tests.sh
also serializes runs per checkout via a lock; concurrent Maven builds on one working tree corrupt
each other's surefire state. The opt-in Authentik scim-fullloop suite always runs sequentially and is
never folded into `--parallel`.

The shared embedder is the one resource NOT per-module: run-tests.sh starts a single container on
11435 for the whole IT phase. Only `wikantik-it-test-dense` uses it. Invoking that module's failsafe
run directly (bare `mvn -pl …`) skips that setup and the suite fails at `@BeforeAll` with the compose
command it wanted — start it by hand or use `bin/run-tests.sh --module dense`.

### Local Deployment (Tomcat 11)

Local Tomcat lives at `tomcat/tomcat-11` (gitignored, created by `deploy-local.sh`). Use it for running and testing — do not use Cargo. ROOT context, serving `docs/wikantik-pages/`.

Config (gitignored; templates in `wikantik-war/src/main/config/tomcat/`, applied by `deploy-local.sh`): `tomcat/tomcat-11/lib/wikantik-custom.properties`, `tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml`.

First-time setup on a fresh clone, and running migrations by hand (e.g. against production): `local-setup` skill.

#### Adding a schema change

**Every commit that changes the database schema MUST also add a numbered
migration** under `bin/db/migrations/`. Pick the
next `V<NNN>__description.sql`, write idempotent DDL (`CREATE TABLE IF NOT EXISTS`,
`ADD COLUMN IF NOT EXISTS`, `INSERT … ON CONFLICT DO NOTHING`), use the
`:app_user` psql variable for grants, and verify the migration is a no-op
when re-applied. See `bin/db/migrations/README.md`
for the full convention. Never edit a migration after it has been applied
in production — fix mistakes with a follow-up migration.

#### Routine redeploy (after first-time setup)

```bash
mvn clean install -DskipTests -T 1C
bin/redeploy.sh    # shutdown + rotate catalina.out + swap WAR + startup
```

`bin/redeploy.sh` runs `bin/db/migrate.sh` (idempotent, pending `V*.sql` only, as the app role from `.env`) but does **not** re-run template materialisation, secrets validation, Tomcat-upgrade snapshot logic, or dev-user seeding. For first-time setup, Tomcat upgrades, or anything touching `wikantik-custom.properties` / `ROOT.xml`, run `bin/deploy-local.sh`.

### Code Quality
```bash
# Apache RAT — informational only, red everywhere; a red run is not a regression to chase.
mvn apache-rat:check

# CI ratchet: fails on any NEW PMD design-rule violation not in build-support/pmd-complexity-baseline.properties (entries only ever come out)
mvn pmd:check -Pcomplexity-gate

# Per-module line-coverage ratchet (JaCoCo): floors pinned per pom, only ever raised, capped at 0.90.
mvn clean install -Pcoverage -DskipITs
```

Code-health site: `bin/site.sh`. Publishing it and the marketing site is `bin/deploy-marketing.sh` — it prompts for sudo, so run it with `!`; flags in [WikantikOperations](docs/admin/WikantikOperations.md) and [ProjectReference](docs/developer/ProjectReference.md#code-health-site-and-static-analysis).

Line coverage above 90% is a goal for this team: imperfect, but the one we pursue.

> Container/remote deployment and load testing runbooks: [docs/admin/WikantikOperations.md](docs/admin/WikantikOperations.md);
> design-doc status: [ProjectReference](docs/developer/ProjectReference.md).
> **Agent shells are blocked** from `bin/deploy-release.sh`, writes on docker1, and installing
> timers/cron. Hand the user the exact command to run with `!`, then verify yourself (health, `/api/capabilities`, `bin/smoke-wiki.sh http://docker1:8080`).

## Architecture Overview

### Page Graph (and the removed Knowledge Graph)

- **Page Graph is the only graph.** Edges are real page-to-page wikilinks parsed from page bodies, nothing else. Co-resident: `canonical_id` (rename-stable id) and `cluster:` (hub membership). Code `com.wikantik.pagegraph.*`, `com.wikantik.api.pagegraph`.
- **Knowledge Graph — REMOVED.** Do not reintroduce it without new evidence; don't write `kg_include` frontmatter. Renaming the KG-named leftovers is **deferred by decision**; don't start it unasked. Still live despite the names: `kg_content_chunks`/`content_chunk_embeddings` (dense retrieval), the `wikantik-knowledge` module and `/knowledge-mcp` endpoint (retrieval + structure tools), `com.wikantik.knowledge.*` (chunking, bundle, briefing, agent projection, eval, query log).
- **Citation edges.** Derived third edge type: inline `cite://` claims, parsed at save into `citations`. Code `com.wikantik.citation.*`; surfaces `/admin/drift/citations`, `list_stale_citations`, for-agent `stale_citations`.
- Naming: the bare word "graph" is a code smell. Always say "Page Graph" (or `pagegraph` in identifiers).

### Module Structure

Full descriptions: [ProjectReference](docs/developer/ProjectReference.md#detail-moved-from-claudemd-2026-11-28).

- **wikantik-api** (contracts), **wikantik-main** (implementation; Obsidian importer `com.wikantik.importer`), **wikantik-http** (servlet filters: CSRF, CORS, CSP, SPA routing, `/wiki/{slug}?format=md|json`), **wikantik-rest** (`/api/*`, `/admin/*`), **wikantik-frontend** (React SPA, Vite), **wikantik-tools** (OpenAPI tool server `/tools/*`), **wikantik-it-tests** (Cargo-launched ITs).
- **wikantik-jdbc**: **the one way to touch the database.** `com.wikantik.jdbc.Jdbc` is the only data-access primitive (`JdbcSupport` is the `extends` convenience); `inTransaction` rolls back on any `Throwable`. Enforced by `JdbcAccessArchTest` (J-1; sole carve-out `JDBCPlugin`). **Adding a repository:** hold a `Jdbc`, write the migration first, test against `PostgresTestDb`; a multi-statement write gets a rollback test. ADR-0010.
- **wikantik-cache-memcached**: opt-in, **not wired into the WAR** (override the `CachingManager` mapping in `classmappings.xml`, set `wikantik.cache.memcached.servers`).
- **wikantik-mcp-core**: shared MCP substrate (`McpTool`/`McpToolUtils`/`McpAudit`, access filter); hosts no tools.
- **wikantik-admin-mcp**: `/wikantik-admin-mcp` (24 tools). See `com.wikantik.mcp.McpServerInitializer`.
- **wikantik-knowledge**: knowledge MCP `/knowledge-mcp` (17 tools: 16 read-only plus `file_agent_report`, the one write, SA-22; `get_briefing` ADR-0011). Ontology expansion behind `wikantik.search.ontologyExpansion.enabled` (default off). See `com.wikantik.knowledge.mcp.KnowledgeMcpInitializer`.
- **wikantik-scim**: SCIM 2.0 at `/scim/v2/*`; SCIM Groups never grant Admin. Rules in `wikantik-scim/CLAUDE.md`.
- **wikantik-extract-cli**: offline CLIs (`ChunkerStatsCli`, `CorpusDivergenceCli`, `GenerateMainPageCli`, `GenerateConfigReferenceCli`, `IngestDocumentsCli`).
- **wikantik-ingest**: Tika extraction for **derived pages** (frontmatter `derived_from`; body machine-owned, ADR-0004). Rules in `wikantik-ingest/CLAUDE.md`.
- **wikantik-plantuml**: server-side drawing for ` ```plantuml ` fences. `plantuml-mit` is **test-scope** in wikantik-main/-rest only and must never become compile scope. Rules in `wikantik-plantuml/CLAUDE.md`.
- **wikantik-ontology**: RDF/OWL (Jena, TDB2); **kept by decision**. Rules in `wikantik-ontology/CLAUDE.md`.
- **wikantik-connectors**: connectors syncing into derived pages via `SyncOrchestrator` (`poll()` never throws — load-bearing). Rules in `wikantik-connectors/CLAUDE.md`.
- **wikantik-agent-reports**: field reports (SA-22); depends on neither `wikantik-api` nor `wikantik-main`. Every MCP call also writes one `mcp_tool_called` ECS event (`McpCallEvent`, SA-21). Contract: wiki `SimpleAgilityAgentActivity`.
- **wikantik-insights** (Content Intelligence): depends on neither `wikantik-api` nor `wikantik-main`; the reference feedback-loop implementation. Rules in `wikantik-insights/CLAUDE.md`.
- **wikantik-work** (work management): depends on neither `wikantik-api` nor `wikantik-main`; every caller goes through `WorkService`; ships dark behind `wikantik.work.enabled` (default `false`). Invariants in `wikantik-work/CLAUDE.md`; reference by area in `docs/developer/work/`.
- **wikantik-work-mcp**: key-only endpoint `/work-mcp`; tools call `WorkService` only. Rules in `wikantik-work-mcp/CLAUDE.md`.

Table of every agent-facing surface (endpoint, module, protocol, auth): [ProjectReference](docs/developer/ProjectReference.md#agent-facing-surface-summary).

### Rendering Pipeline

- **Rendered fences** (` ```mermaid `, ` ```plantuml `; the `example` modifier keeps either as code): `com.wikantik.markdown.extensions.fences`, validated server- and client-side against one fixture (`wikantik-frontend/src/utils/__fixtures__/renderedFences.json`). PlantUML is server-drawn: `ServerFenceResolver` splices the drawing **once, at the outermost `textToHTML`**, so render caches never hold drawings. New renderer: `RenderedFenceRenderer` Javadoc. Corpus checkers: `bin/check-plantuml.sh`, `check-mermaid.mjs`.

### Security Model

- Page-level ACLs are inline `[{ALLOW view Admin}]` in page content. All `/api/*` endpoints check ACLs via `RestServletBase.checkPagePermission()`. `/admin/*` is protected by `AdminAuthFilter` (requires `AllPermission`).
- **Private clusters** — `wikantik.access.privateClusters` (+ `.viewers`) makes every page in a listed cluster (or sub-cluster) Admin/viewer-group only, for all actions. One gate, `PrivateClusterGate`, inside `DefaultAuthorizationManager`; a page ACL can only narrow further. The admin MCP bypasses it by design. Settings need a restart plus `POST /admin/ontology/rebuild`; runbook in WikantikOperations section 1.12.
- `JDBCPlugin` (page-authored SQL against the wiki datasource) ships **disabled by default** behind `wikantik.plugin.jdbc.enabled` (checked before even the admin gate) — enable it only if you accept that a high-privilege DB role executes editor-supplied SQL.
- SSRF egress policy on connector fetches — `EgressGuard` (`wikantik-connectors`) rejects non-http(s) schemes and hosts resolving to loopback/private/link-local/multicast/IPv6-ULA addresses, re-validated on every redirect hop; `wikantik.connectors.egress.allowPrivate=true` opts a deliberate internal crawl back in.
- Render caches skip viewer-dependent output — a render that consults the caller's session/ACLs (e.g. ACL-aware `InsertPage`, `$username`) must flag itself via `Context.VAR_VIEWER_SENSITIVE`, which excludes it from the principal-less document/HTML caches.
- `ObjectInputFilter` whitelists on all `ObjectInputStream` usage.
- MCP API-key scopes (`ApiKeyService.Scope`): `mcp_read` (`/knowledge-mcp` only) ⊂ `mcp_admin` (the full admin MCP). Legacy `mcp` is an alias for `mcp_admin`, WARNed by `LegacyScopeUseLog`. Admin keys stay fully privileged (#62, won't-fix). The `work` scope (`/api/work/*` and `/work-mcp`) matches only itself and `all`: a `work` key is useless on the other MCP endpoints and `/tools/*`, and no other scope reaches work. A `work` key acts as its owner, never an agent: work agents carry their own token (`wka_…`, verified by the work module through `AgentTokenAuth`). A credential request (key or agent token) on `/api/work/*` or `/work-mcp` carries no principal and never becomes a session (`WorkApiKeyFilter`, `AgentTokenAuth.isCredentialRequest`).
- Public identity is the **display name** (`users.full_name`, unique, `ProfileNameRules`). Login names show only to their owner and admin surfaces — resolve them through `PublicIdentity`; authorship is stored as the login (`AuthorKeys`). Strip `[{ALLOW …}]` for reader surfaces only via `com.wikantik.auth.acl.AclDirectiveText` — never call `AclDirectives` directly.
- SSO binds on `wikantik.sso.identityClaim` (default `sub`) and never adopts a non-SSO local account of the same name (fails closed without the `sso.subject` marker). The SAML `/sso/callback` is CSRF-exempt by design; login rotates the session; failures redirect to `/login?error=` (dual-registered SPA route).

### Important Configuration

- **`ini/wikantik.properties` is the complete reference.** Every `wikantik.*` key the code reads is declared there, uncommented, with its explicit default, a description and a `Type:` line (`mcp.*` → `wikantik-mcp.properties`, `tools.*` → `wikantik-tools.properties`). `ConfigSurfaceDriftTest` fails the build on any drift — the violation kinds are in [ProjectReference](docs/developer/ProjectReference.md#configsurfacedrifttest-violation-kinds).
- **Adding a key:** declare it in the file first (section, description, `Type:`, explicit default), then read it in code with the same default. If the default is derived, leave the value blank, add `Blank means:`, and make the reader `isBlank()`-safe.
- **Docs are generated:** `bin/config-reference.sh --write` regenerates `docs/ConfigurationReference.md` and the wiki page `WikantikConfigurationReference`; `ConfigReferenceRegressionTest` fails when they are stale. Never hand-edit them.
- Overrides: `wikantik-custom.properties` (container lib or WEB-INF), cascade files, `-D` system properties. Precedence is documented in the generated reference.
- Security policy: `policy_grants` table (database-backed) or `WEB-INF/wikantik.policy` (file-based fallback). Schema: `bin/db/migrations/V*.sql` only.

### Active Design Documents

Read the linked design doc before touching its subsystem. Status: [ProjectReference](docs/developer/ProjectReference.md#active-design-documents--detailed-status).

- **[StructuralSpineDesign.md](docs/wikantik-pages/StructuralSpineDesign.md)** — `Main.md` is **generated**: edit `docs/wikantik-pages/Main.pins.yaml` (`MainPageRegressionTest` reverts hand-edits). Save-time `canonical_id` enforcement is on (`StructuralSpinePageFilter`).
- **[AgentGradeContentDesign.md](docs/wikantik-pages/AgentGradeContentDesign.md)**, **[PageGraphVsKnowledgeGraph.md](docs/wikantik-pages/PageGraphVsKnowledgeGraph.md)**.
- **[HybridRetrieval.md](docs/wikantik-pages/HybridRetrieval.md)** — BM25 + dense (RRF), fail-closed to BM25; backend `wikantik.search.dense.backend = inmemory | pgvector | lucene-hnsw` (**docker1: `lucene-hnsw`**). **Wikantik never calls a chat model** — `wikantik.genai.mode` is `full|none` (`embeddings-only` is a legacy alias for `full`). **Measured dead, don't retry:** LLM reranker and query decomposition (removed 2.5.15), HyDE, doc2query, KG graph rerank. **Op gotcha:** `inmemory`/`lucene-hnsw` reload after a bulk re-embed only if the run **succeeds**; a failed run leaves hybrid silently BM25-only — tell: bundle `topSimilarity: -1.0`, `confidence: unknown`.
- **[ClusterDeclarationDesign.md](docs/wikantik-pages/ClusterDeclarationDesign.md)** — a cluster exists iff exactly one page has `type: hub` + scalar `cluster: <path>`; sub-clusters are `parent/child`, **one level, permanently** (limit lives only in `CLUSTER_SLUG_PATTERN`; ADR-0009). Rules:
  - Compare clusters only via `ClusterPath` — **never `startsWith`**. `ClusterPath.memberships(raw)` resolves scalar-vs-list: non-hub pages may list several clusters (`PageDescriptor.cluster()` is the primary, `clusters()` all); a list-valued hub is an ERROR.
  - The one blocking rule (duplicate declaration → 422 in `StructuralSpinePageFilter`) ships **dark**: `wikantik.cluster_declaration.enforcement.enabled=false`. Everything else is a WARNING on `/admin/drift`. `hubs:` and `HubSyncFilter` are deleted; hub `hasPart` is derived from membership.
  - `rename_cluster` / `POST /admin/clusters/rename` rewrites `cluster:` only; unconfirmed = plan, not error; refuses a target another hub declares. MCP tools register **unconditionally** — a new tool must be added to `wikantik-mcp-instructions.txt` **and** `McpProtocolIT.EXPECTED_TOOLS` (else `InstructionsRegistryDriftTest`/`McpInstructionsDriftIT`/`McpProtocolIT` fail).
  - `ClusterStatus.jsx` (PageMeta) is client-side only, gated on `page.permissions.edit`; reads `hub_declared` (Gson omits a null `hub_slug`).
  - **Corpus divergence (read before ANY corpus-wide content work):** `docs/wikantik-pages/` and the prod page store are different corpora — **prod is authoritative, the checkout is a mirror**. Plan from the live index (`list_clusters`/`list_pages_by_filter`), never the repo. Prod pages carry prod-only `**Cluster Hub:**` footers and CRLF line endings — preserve both when pushing repo text (three-way merge from the last release tag, not a blind overwrite). Measure the gap with `CorpusDivergenceCli` (exit 2 = refused, incomplete snapshot; 1 = divergence under `--check`).
- **[RetrievalExperimentHarness.md](docs/wikantik-pages/RetrievalExperimentHarness.md)**, **[IndexingSupport.md](docs/admin/IndexingSupport.md)**.
- **[Work management design](docs/superpowers/specs/2026-11-15-work-management-design.md)** — the plans' "Decisions" sections record what the owner settled where it is silent.
- **[RAG-as-a-Service design](docs/superpowers/specs/2026-06-13-rag-as-a-service-and-knowledge-base-design.md)** — assemble a ranked, de-duplicated, version-pinned-**cited** context bundle, NEVER a synthesized answer (ADR-0001). Logic `com.wikantik.knowledge.bundle.*`; `GET /api/bundle` + `assemble_bundle`. `bundleAssemblyService` is wired at the `WikiEngine.patchContextRetrievalService` seam — keep `BundleServiceWiring` off the `getManager` allow-list (DecompositionArchTest R-2). ADRs `docs/adr/0001`–`0007`, glossary `CONTEXT.md`.
- **[Structured page curation](docs/superpowers/specs/2026-06-08-structured-page-curation-design.md)** — the server-authoritative `FrontmatterSchema` validates every save path (REST + MCP). **Gotcha:** field-value checks are WARNINGS (only malformed YAML returns 422), so legacy corpus pages still save; SnakeYAML pre-parses `date`/`audience`.

### Testing Approach

- **Database tests**: `@RequiresPostgres` + `PostgresTestDb.createDataSource()`; call `PostgresTestDb.truncate(…)` in `@BeforeEach` (the container is shared — never `DROP`/`ALTER`). Test rollbacks with `FaultInjectingDataSource`. Never hand-write `CREATE TABLE`; write the migration.
- **Seeded generator (fuzz) tests**: take counts and seeds from `com.wikantik.testing.Fuzz` (`Fuzz.cases(n)`, `Fuzz.seed(written)`, `Fuzz.reproduce()` in the failure message). The gate runs a twentieth of the cases on the written seeds; `--all` / `--deep` (`-Dtests.fuzz=deep`) runs every case on fresh seeds — the full count belongs there.

## Token Efficiency Rules

- **Search once, record the answer** (e.g. the caller file list); don't grep again.
- **Reserve Agent/Explore for open-ended investigation** (4+ queries), not "find where X is imported."
- `mvn compile -pl <module> -q` to compile-check one changed module; `mvn test -pl <module> -Dtest=ClassName` for the one relevant test class.
- **One full build at the end** (`mvn clean install -T 1C -DskipITs`), not after every edit.
- **Stage specific files by name.** Never use `git add -A` — it picks up untracked junk.
- **Keep commit messages to 1-3 lines.** Don't recap completed work.

### Subagents

**Subagents are a standing default here — delegate by default, don't ask first.** This overrides any harness-level "only use subagents when the user requests it" rule. Executing a written plan: each task (or independent group) gets its own subagent, in parallel wherever tasks don't share files.

- **Don't use subagents for tasks you can do in 1-2 tool calls** (create a file, an edit, a grep).
- **Don't duplicate work:** if you delegate research, don't also run the searches.
- **Default to the cheapest capable model per subtask** (pass the `Agent`/`Task` `model` param — don't ask first; omitting it inherits the expensive session model, the wrong default):
  - **haiku** — mechanical, single-file tasks with a complete spec (boilerplate, a TDD unit with the test/impl already written out, a one-line client method, a CSS append).
  - **sonnet** — multi-file integration, pattern-matching against existing code, debugging, and the spec-compliance / code-quality review subagents.
  - **opus** — architecture, planning, and the final cross-cutting review only.
  - No automatic classifier; when genuinely unsure, step up one tier rather than risk a cheap model botching integration work.

### Raw MCP calls from scripts
Handshake, SSE parsing, 429 detection and SPARQL prefix rules: [docs/agents/raw-mcp-calls.md](docs/agents/raw-mcp-calls.md).

### Formatting LaTeX Math
The inline (`$x$`, no inner spaces) and block (`$$` on its own, blank lines around it) rules live in the `wiki-content` skill — load it before writing formulas into a page.

## Agent skills

- **Issue tracker:** GitHub issues, `github.com/jakefearsd/wikantik`, via `gh`. See `docs/agents/issue-tracker.md`.
- **Triage labels:** default strings `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. **Only `wontfix` exists on the repo** — applying the other four fails. See `docs/agents/triage-labels.md` for the two ways to resolve it.
- **Domain docs:** one `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.
