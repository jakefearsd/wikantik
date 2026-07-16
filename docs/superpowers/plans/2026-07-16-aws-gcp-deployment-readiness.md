# AWS/GCP Deployment Readiness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Phases 2 and 3 are infrastructure work — each task still ends with a verifiable deliverable, but "test" there means a smoke/validation command, not JUnit.

**Goal:** Make Wikantik deployable by a third party on AWS and GCP as easily as it deploys to docker1 today, with a cost-conscious flag architecture that can turn off all chat-LLM inference (keeping embeddings) or the entire Knowledge Graph feature set — without changing docker1's behavior in any way.

**Architecture:** Single-VM docker-compose reference deployment per cloud (minimal Terraform + shared cloud-init), pulling the existing GHCR image with a read token. App-side, a new `wikantik.genai.mode` property acts as an enforcement *ceiling* over the existing per-feature LLM flags, and a new `wikantik.knowledge.enabled` flag gates construction/registration of the whole KG subsystem, surfaced to the React frontend via a new `GET /api/capabilities` endpoint. Embeddings in cloud come from a CPU-only Ollama sidecar; full-KG extraction in cloud uses the existing Claude API backend.

**Tech Stack:** Java 25 / Tomcat 11 (unchanged), Docker Compose profiles, Caddy (Let's Encrypt) or cloudflared sidecars, Terraform (AWS: EC2 + EBS/DLM + SSM Parameter Store; GCP: GCE + PD snapshots + Secret Manager), GHCR, Ollama (CPU, embeddings only), Anthropic API (full-KG tier).

## Decision Record (grilling session 2026-07-16)

| # | Decision | Choice |
|---|----------|--------|
| 1 | Audience | **(b)-lite**: reference deployment per cloud that works for a stranger (no access to LAN/NAS/inference host); Jake uses the same path |
| 2 | Compute shape | **Single VM + existing compose stack** (EC2/GCE). No ECS/Cloud Run/stateless-first work — app is single-instance by design (filesystem pages, in-JVM locks, in-RAM HNSW, no leader election) |
| 3 | Database | **Bundled `pgvector/pgvector:pg18` container is the default**; RDS/Cloud SQL is a documented, *tested* variant (needs non-superuser migration path + `sslmode=require`) |
| 4 | Embeddings | **CPU-only Ollama sidecar** on the VM serving `qwen3-embedding:0.6b` (compose profile); **BM25-only** (`wikantik.search.hybrid.enabled=false`) documented as the zero-inference floor tier |
| 5 | Flag design | **`wikantik.genai.mode = full \| embeddings-only \| none`** as enforcement ceiling + **`wikantik.knowledge.enabled`** subsystem flag + **`GET /api/capabilities`** for frontend gating. Defaults (`full`, `true`) preserve docker1 exactly |
| 6 | Cloud full-KG tier | **Claude API backend** (`wikantik.knowledge.extractor.backend=claude`) is the documented cloud tier; close the page-extractor wiring gap; BYO-Ollama endpoint stays supported |
| 7 | Ingress/TLS | **Caddy sidecar default** (Let's Encrypt), **cloudflared sidecar as supported profile**; `RemoteIpValve` header parameterized (default stays `CF-Connecting-IP`) |
| 8 | IaC | **Minimal Terraform per cloud** (`deploy/aws/`, `deploy/gcp/`) sharing one cloud-init template. Single VM, firewall, disk, static IP, snapshot schedule. No VPC-building ambitions |
| 9 | Image distribution | **GHCR stays private; customers get read tokens.** Cloud compose references `image:` tags; pull-based updates with health-checked rollback; `remote.sh` gains additive `deploy --pull` mode |
| 10 | Backups | **Terraform disk-snapshot schedules in v1**; S3/GCS bucket push from the backup sidecar is a fast-follow. jakemon textfile mounts become optional |
| 11 | Secrets | **Thin secret-manager fetch**: Terraform writes secrets to SSM Parameter Store / GCP Secret Manager; cloud-init fetches into `.env` at boot via instance IAM role. App stays env-var-driven |
| 12 | Sequencing | **Phase 1 flags → Phase 2 AWS → Phase 3 GCP** |
| 13 | Legacy CI (`ci-cd.yml`, `staging-deploy.yml`) | **Keep untouched** — out of scope |
| 14 | Image seed content | **Keep current baking** of `docs/wikantik-pages/` into the image — out of scope |

## Global Constraints

- **docker1 must be untouched by default.** Every new property defaults to current behavior: `wikantik.genai.mode=full`, `wikantik.knowledge.enabled=true`, RemoteIp header default `CF-Connecting-IP`, entrypoint additions all conditional on new env vars being set. `docker-compose.yml` / `docker-compose.prod.yml` semantics unchanged (additive-only edits).
- Java 25+, Tomcat 11.0.22 (pinned in lockstep between `Dockerfile` and `bin/deploy-local.sh:228`).
- TDD for all Phase 1 app code (failing test first). Full gate before any prod-code commit: `mvn clean install -Pintegration-tests -fae` (never `-T` for ITs).
- Every schema change ships a numbered idempotent migration under `bin/db/migrations/` (none are anticipated by this plan — flag work is properties-only).
- No data backfills in versioned migrations.
- "The bare word *graph* is a code smell": always Page Graph / Knowledge Graph / `kg_*`.
- New Maven modules (none planned) would need `mockito-core` test-scope.
- Commit style: 1–3 lines, stage files by name (no `git add -A`).

## Current-State Findings (verified 2026-07-16)

Facts the tasks below rely on; re-verify line numbers before editing, but these were spot-checked:

- **No unified LLM-off switch.** Chat-LLM paths and their gates: entity extraction (`EntityExtractorConfig.enabled()` = backend != `disabled`; wired in `KnowledgeWiringHelper.wireEntityExtraction`, which no-ops at line ~227 when disabled), KG judge (`KgJudgeConfig.enabled()`, consumed at `KnowledgeSubsystemFactory.java:137`), bundle LLM reranker (`RerankerConfig` via `wikantik.bundle.reranker.enabled`, default false, **plus a second activation path**: `wikantik.bundle.rerank.chain` containing the `llm` token, parsed in `BundleServiceWiring.java:213–242`), query decomposition (`BundleDecompositionConfig`, default false).
- **Embeddings master switch already exists**: `wikantik.search.hybrid.enabled` (`EmbeddingConfig.PROP_ENABLED`, `EmbeddingConfig.java:50`, code default false / shipped ini true). Off ⇒ `SearchWiringHelper.wireHybridRetrieval` is a no-op and search falls back to BM25 (the one path with a real circuit breaker + runbook `HandlingEmbeddingServiceOutages.md`).
- **No KG-wide switch**: `KnowledgeSubsystemFactory.create()` (`KnowledgeSubsystemFactory.java:103`) always constructs `DefaultKnowledgeGraphService`, `KgMaterializationService`, hub services, `KgCurationOps`, `NodeMentionSimilarity`. `KnowledgeMcpInitializer` registers the 6 KG tools whenever `kgService != null` (always). React frontend (`Sidebar.jsx`, `AdminSidebar.jsx`, `AdminKnowledgePage.jsx`) shows KG surfaces unconditionally; **no capability/config endpoint exists** in wikantik-rest.
- **Chunker is a shared prerequisite**: `ChunkProjector` (`wikantik.chunker.enabled`, `KnowledgeSubsystemFactory.java:240`) feeds BOTH the embedding pipeline and entity extraction. KG-off must NOT disable it.
- **All inference defaults point at `http://inference.jakefear.com:11434`** — baked into Java defaults in 6+ config classes and repeated in `ini/wikantik.properties` (~lines 1227, 1303, 1422, 1502). `docker/entrypoint.sh` has **no env var** for the embedding base-url or extractor backend today.
- **Entrypoint secret gaps**: `wikantik.scim.token` (read as JVM system property / web.xml init-param only — SCIM is deny-all in the shipped container) and `wikantik.connectors.crypto.key` (absent ⇒ credential store fail-closed-disabled) are wired through no deployment path.
- **Claude backends exist** (`ClaudeEntityExtractor`, `ClaudeProposalJudge`, selected by backend=`claude` + `ANTHROPIC_API_KEY`) but the admin batch/bootstrap **page** extractor is wired only for the Ollama backend in `KnowledgeWiringHelper` (~lines 262–269).
- **Ingress coupling**: `docker/config/server.xml:52` hardcodes `RemoteIpValve remoteIpHeader="CF-Connecting-IP"`; cloudflared itself runs as an OS daemon outside the repo.
- **`remote.sh deploy` ships images via `docker save | ssh docker load`** (LAN assumption). `release.yml` already publishes multi-arch `ghcr.io/jakefearsd/wikantik:{version,latest}` on tag push. `deploy-release.sh` pulls GHCR locally then still save/loads.
- **Backup sidecar** (`docker/backup/`) is portable, but `docker-compose.prod.yml` mounts jakemon's `/var/lib/jakemon/textfile` and publishes Postgres on `DB_HOST_BIND=172.17.0.1` — both docker1-topology-specific.
- **LLM activity log** (`/admin/llm-activity`) records embed/extract/judge calls but NOT reranker/decomposition calls.
- **Startup**: `lucene-hnsw` dense backend rebuilds the in-RAM HNSW index synchronously inside servlet-context startup on *every* boot (drives the 90s `start_period` in prod compose); health = `GET /api/health`, restricted with `/metrics` to loopback+RFC1918 by `InternalNetworkFilter`.
- **Migrations run on every container start** via entrypoint → `/opt/wikantik/db/migrate.sh`; the runner has no advisory lock (fine single-VM; noted for the backlog).

---

# Phase 1 — Cost-control flag architecture (app code, ships to docker1 harmlessly)

Each task below follows TDD: write the failing test, watch it fail, implement, watch it pass, commit. Use `mvn test -pl <module> -Dtest=ClassName` per task; run `mvn test-compile` after any signature change; one full build at phase end.

### Task 1.1: `GenAiMode` type + property parsing

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/config/GenAiMode.java`
- Test: `wikantik-api/src/test/java/com/wikantik/api/config/GenAiModeTest.java`

**Interfaces:**
- Produces: `enum GenAiMode { FULL, EMBEDDINGS_ONLY, NONE }` with:
  - `public static final String PROP = "wikantik.genai.mode";`
  - `public static GenAiMode fromProperties( java.util.Properties props )` — reads `PROP`, trims/lowercases, maps `"full"`→FULL, `"embeddings-only"`→EMBEDDINGS_ONLY, `"none"`→NONE; **null/blank/unrecognized → FULL with a `LOG.warn` naming the bad value** (fail-open to current behavior; never fail closed on a typo — an operator who wants enforcement will see the warn).
  - `public boolean allowsChatInference()` — true only for FULL.
  - `public boolean allowsEmbeddings()` — true for FULL and EMBEDDINGS_ONLY.

- [ ] Write `GenAiModeTest` covering: absent property → FULL; each valid value; garbage value → FULL; case/whitespace tolerance; `allowsChatInference`/`allowsEmbeddings` truth table. Run, verify it fails (class missing).
- [ ] Implement the enum. Run test → PASS.
- [ ] Add the property to `wikantik-main/src/main/resources/ini/wikantik.properties` near the other knowledge-graph properties (~line 1290), commented out, with a doc comment explaining ceiling semantics and the three values.
- [ ] Commit: `feat(config): GenAiMode ceiling enum (wikantik.genai.mode)`

### Task 1.2: Enforce the ceiling at every chat-LLM wiring point

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/EntityExtractorConfig.java` (the `fromProperties`/`enabled()` seam)
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/judge/KgJudgeConfig.java` (or its actual package — locate via `grep -rn "class KgJudgeConfig"`)
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleServiceWiring.java` (`selectReranker` + `buildChain`, ~lines 191–242: when ceiling denies chat inference, skip the `llm` stage with `LOG.warn` and never construct `LlmSectionReranker`, regardless of `wikantik.bundle.reranker.enabled` or an `llm` token in `wikantik.bundle.rerank.chain`)
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleDecompositionConfig.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/search/embedding/EmbeddingConfig.java` (`enabled` = existing flag AND `mode.allowsEmbeddings()`)
- Tests: one focused test class per config (`EntityExtractorConfigModeTest`, `KgJudgeConfigModeTest`, `BundleServiceWiringModeTest`, `BundleDecompositionConfigModeTest`, `EmbeddingConfigModeTest`) in the matching test packages

**Interfaces:**
- Consumes: `GenAiMode.fromProperties` (Task 1.1)
- Produces: each config's existing `enabled()`/factory contract is unchanged in signature; only the computed value now respects the ceiling. Pattern: resolve `GenAiMode` once inside each `fromProperties(props)` and AND it in — do NOT scatter runtime mode checks; the ceiling is applied where configs are *built* so downstream wiring stays untouched.

- [ ] For each of the five seams: write a failing test asserting (a) mode absent → behavior identical to today (regression guard), (b) `embeddings-only` → chat feature disabled even when its individual flag says enabled, (c) `none` → embeddings also disabled, (d) explicit individual-flag-off is still off under mode=`full`.
- [ ] For `BundleServiceWiring`, the test must cover **both** activation paths: `wikantik.bundle.reranker.enabled=true` and `wikantik.bundle.rerank.chain=llm,metadata-boost` — under `embeddings-only`, neither may produce an `LlmSectionReranker` (assert on the returned chain composition; the chain should still contain the non-LLM stages).
- [ ] Implement, run each test class, run `mvn test-compile -pl wikantik-main` (signature-adjacent changes), then the five classes together.
- [ ] Commit: `feat(config): genai.mode ceiling enforced at extractor/judge/reranker/decomposition/embedding wiring`

### Task 1.3: `wikantik.knowledge.enabled` — KG subsystem master flag

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeSubsystemFactory.java` (`create()`, line ~103)
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeWiringHelper.java` (`wireEntityExtraction`, `wireKgPolicyAndContent` — skip when KG off)
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java` (KG tool registration block, ~lines 107–156)
- Modify: the admin-mcp initializer (`com.wikantik.mcp.McpServerInitializer` in wikantik-admin-mcp) — skip KG write/read tools (`propose_knowledge`, `curate_nodes`, `curate_edges`, `query_nodes`, `search_knowledge`, `list_orphaned_kg_nodes`, proposal tools) when KG off
- Modify: wikantik-rest KG-serving resources — `/admin/knowledge-graph/*`, `/api/page-knowledge/*` → 503 JSON citing `wikantik.knowledge.enabled` (follow the existing `AdminExtractionResource` 503 pattern exactly; use `RestServletBase.sendError()`, never raw `response.sendError()`)
- Tests: `KnowledgeSubsystemFactoryFlagTest` (wikantik-main), MCP registration unit tests in the two MCP modules, REST 503 tests in wikantik-rest

**Interfaces:**
- Produces: `KnowledgeSubsystem.Services` where KG-specific services (`kgService`, materialization, hub services, curation ops, judge, `NodeMentionSimilarity`) are **null/absent** when the flag is false, while **`ChunkProjector` and everything the embedding pipeline needs remain constructed** (chunks feed dense retrieval independently of KG — this is the load-bearing boundary).
- Semantics: `knowledge.enabled=false` implies no entity extraction wiring regardless of `wikantik.knowledge.extractor.backend`. Ontology stays governed by its own `wikantik.ontology.enabled`; with KG off its **entity** projectors must no-op gracefully (page/cluster/tag concept graphs still work) — verify `OntologyRebuildCoordinator` handles a null `kgService` (fix with a null-guard + `LOG.warn` if not).

- [ ] Failing test: factory with `wikantik.knowledge.enabled=false` returns Services with null KG services but a live `ChunkProjector`; with the property absent, everything is built (regression guard).
- [ ] Failing tests: each MCP initializer registers zero KG tools when off (and the exact current counts when on — pin the numbers: knowledge-mcp loses `discover_schema`, `query_nodes`, `get_node`, `traverse`, `search_knowledge`, `find_similar`; retrieval/page/spine tools stay).
- [ ] Failing tests: KG REST endpoints return 503 JSON naming the flag (Mockito unit level; per project convention MCP/security surface changes also need a wire-level IT — add one Cargo IT asserting a KG tool call refuses cleanly when the flag is off, payload citing the reason).
- [ ] Implement factory skip + null-tolerant wiring. Check every consumer of the now-nullable services (`grep -rn "kgService\|curationOps\|hubDiscovery"` across wikantik-main/rest/knowledge/admin-mcp) — each must either null-guard or live behind the flag.
- [ ] Run: targeted test classes, then `mvn test-compile` on touched modules.
- [ ] Commit: `feat(knowledge): wikantik.knowledge.enabled subsystem flag — KG off leaves chunking/embeddings intact`

### Task 1.4: `GET /api/capabilities` + frontend gating

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/CapabilitiesResource.java` (register alongside the other 25 Resource classes; public read, no auth — capability booleans are not secrets, and the SPA needs them pre-login)
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` (servlet mapping if the REST layer needs it — mirror an existing public endpoint like `/api/changes`)
- Modify: `wikantik-frontend/src/**` — `Sidebar.jsx` (KG nav entries ~lines 145, 209), `AdminSidebar.jsx` (~line 28), and the KG admin routes: fetch capabilities once in the existing app-level context/provider, hide KG nav + show a "feature disabled" panel on direct KG-route navigation
- Tests: `CapabilitiesResourceTest` (wikantik-rest), vitest specs for conditional nav rendering, one Selenide IT asserting the KG nav is absent when the flag is off

**Interfaces:**
- Produces JSON: `{ "knowledgeGraph": bool, "hybridSearch": bool, "genaiMode": "full|embeddings-only|none", "ontology": bool, "connectors": bool, "citations": bool }` — sourced from the same config classes at request time (cheap; no caching subtleties). Frontend consumes `knowledgeGraph` now; the rest are for future gating so the endpoint doesn't need version churn.

- [ ] Failing REST test: flag combinations produce the right JSON; endpoint reachable anonymously.
- [ ] Failing vitest: sidebar omits KG entries when `knowledgeGraph:false` (mock the fetch; re-run the file alone if vitest concurrency flakes — known issue).
- [ ] Implement endpoint + frontend context. Remember SPA-route rules only apply to *new routes* (none added here).
- [ ] Selenide IT with the flag off (new IT needs the custom-properties test overlay — see `reference_it_shared_test_resources` / IT module conventions).
- [ ] Commit: `feat(rest,frontend): /api/capabilities + KG UI gating on wikantik.knowledge.enabled`

### Task 1.5: Claude page-extractor wiring gap

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeWiringHelper.java` (~lines 262–269 — page extractor currently wired only when backend=`ollama`)
- Test: extend `KnowledgeWiringHelper`'s existing wiring tests

**Interfaces:**
- Consumes: existing `ClaudePageExtractor` (verify class name/package via `grep -rn "class ClaudePageExtractor"` — research says it exists for the CLI; if it lives in wikantik-extract-cli it must be moved/mirrored into wikantik-main, which changes this task's size — check first).
- Produces: `/admin/knowledge-graph/extract-mentions` batch extraction functional under backend=`claude`, so the cloud full tier can backfill an existing corpus.

- [ ] Verify where the Claude page extractor lives; if CLI-only, decide move vs. thin adapter before writing the test (flag to reviewer in the task PR/commit message).
- [ ] Failing test: with backend=`claude` + API key present, `wireEntityExtraction` wires a page extractor (today it wires none).
- [ ] Implement, test, commit: `feat(knowledge): wire Claude page extractor for admin batch extraction`

### Task 1.6: Entrypoint env-var gaps (secrets + inference endpoints + new flags)

**Files:**
- Modify: `docker/entrypoint.sh` (properties heredoc + conditional blocks, ~lines 73–260)
- Modify: `.env.example` (document every new var)
- Test: `bin/container.sh smoke-test` locally (test overlay compose) — assert rendered `wikantik-custom.properties` contains/omits blocks as vars are set/unset (a small bats-style or grep-based shell assertion script under `docker/` is fine; match the repo's existing script-testing idiom if one exists)

**Interfaces (all conditional — unset ⇒ rendered output byte-identical to today):**
- `WIKANTIK_GENAI_MODE` → `wikantik.genai.mode`
- `WIKANTIK_KNOWLEDGE_ENABLED` → `wikantik.knowledge.enabled`
- `WIKANTIK_EMBEDDING_BASE_URL` → `wikantik.search.embedding.base-url` (unblocks pointing at the CPU sidecar; today the `inference.jakefear.com` default is unreachable-from-cloud and unoverridable via env)
- `WIKANTIK_EXTRACTOR_BACKEND` → `wikantik.knowledge.extractor.backend`; `ANTHROPIC_API_KEY` passthrough documented (JVM env var read by the Anthropic SDK — verify how `ClaudeEntityExtractor` sources it and wire accordingly)
- `WIKANTIK_SCIM_TOKEN` → wire to wherever `wikantik.scim.token` is actually read (system property: append `-Dwikantik.scim.token=...` to `CATALINA_OPTS` in the entrypoint, or properties file if `PropertyReader`'s env fallback covers it — `PropertyReader.java:100–159` reads system properties/env for `wikantik.*` keys; **verify precedence first**, then pick the simplest working path and document it)
- `WIKANTIK_CONNECTORS_CRYPTO_KEY` → `wikantik.connectors.crypto.key`
- `PROXY_REMOTE_IP_HEADER` → see Task 2.1 (declared here so `.env.example` documents the full surface once)

- [ ] Add conditional blocks mirroring the existing SSO/SMTP pattern; keep header docs (lines 18–65) current.
- [ ] Local verification: run the test-overlay stack twice (vars unset / all set), grep the rendered properties inside the container for presence/absence. Record exact commands in the commit message.
- [ ] Commit: `feat(docker): entrypoint env vars for genai mode, KG flag, embedding URL, SCIM token, connector crypto key`

### Task 1.7: LLM-activity observability parity + tier documentation

**Files:**
- Modify: the reranker/decomposition call paths to record into the existing LLM activity log (mirror `RecordingEntityExtractor` pattern — locate via `grep -rn "RecordingEmbeddingClient"`), so `/admin/llm-activity` proves "embeddings-only means zero chat calls"
- Create: `docs/CostTiers.md` — the operator-facing tier table:
  - **core** (`genai.mode=none`, `knowledge.enabled=false`, `hybrid.enabled=false`): BM25 search, BM25-chunk bundle, zero inference infra
  - **search** (`genai.mode=embeddings-only`, `knowledge.enabled=false` or `true`-for-manual-curation, hybrid on): dense+BM25 hybrid, dense bundle, CPU sidecar only
  - **knowledge** (`genai.mode=full`, backend=`claude` + key, or BYO Ollama): full KG extraction/judge
  - Exact `.env` preset block per tier; note that `wikantik.bundle.*` LLM levers stay off-by-default and the ceiling protects against accidental re-enable
- Tests: unit test that a recorded reranker call lands in the activity log

- [ ] TDD the recording wrappers; write `docs/CostTiers.md`; commit: `feat(observability): reranker/decomposition in llm-activity log + cost-tier docs`

### Task 1.8: Phase 1 gate

- [ ] `mvn clean install -T 1C -DskipITs` (drop `-T 1C` if provider flakes appear — known issue), then full `mvn clean install -Pintegration-tests -fae`.
- [ ] Manual smoke on local Tomcat: default properties → behavior unchanged; `embeddings-only` overlay → `/admin/llm-activity` shows embeds only; `knowledge.enabled=false` → KG nav gone, KG MCP tools absent from `tools/list`, chunking+search still work.
- [ ] Commit any test fixes; this phase can ship to docker1 with the next routine release (defaults = no behavior change).

---

# Phase 2 — AWS reference deployment

Ships in `deploy/` (new top-level dir) + additive compose/config changes. No JUnit here; each task ends with a runnable validation.

### Task 2.1: Parameterize the RemoteIp header

**Files:**
- Modify: `docker/config/server.xml:52` — `remoteIpHeader="${wikantik.proxy.remoteIpHeader}"` (Tomcat substitutes `${...}` from system properties in server.xml)
- Modify: `docker/entrypoint.sh` — append `-Dwikantik.proxy.remoteIpHeader=${PROXY_REMOTE_IP_HEADER:-CF-Connecting-IP}` to `CATALINA_OPTS`
- Validate: run the test compose stack with the var unset → access log/client IP behavior identical (default preserved); set `PROXY_REMOTE_IP_HEADER=X-Forwarded-For` → header honored (curl with spoofed header from a private address, observe remote IP in access log)

- [ ] Implement + validate both settings; commit: `feat(docker): parameterize RemoteIpValve header (default CF-Connecting-IP)`

### Task 2.2: `docker-compose.cloud.yml` overlay + Caddy/cloudflared/ollama profiles

**Files:**
- Create: `docker-compose.cloud.yml` — overlay applied as `-f docker-compose.yml -f docker-compose.cloud.yml`:
  - `wikantik`: `image: ${WIKANTIK_IMAGE:?set to ghcr.io/jakefearsd/wikantik:<tag>}` (overrides `build:`), `restart: always`, memory limit `${WIKANTIK_MEM_LIMIT:-2G}`
  - `db`: same pgvector image; **no host-port publish** (drop the `DB_HOST_BIND` publish — jakemon-specific)
  - `backup` sidecar included, but the textfile mount targets a named volume by default (`BACKUP_TEXTFILE_DIR` optional)
  - `caddy` service (`profiles: ["caddy"]`): `caddy:2-alpine`, ports 80/443, mounts `deploy/config/Caddyfile` (uses `{$WIKANTIK_DOMAIN}`, `reverse_proxy wikantik:8080`), named volumes for certs; wikantik env gets `PROXY_REMOTE_IP_HEADER=X-Forwarded-For`
  - `cloudflared` service (`profiles: ["cloudflared"]`): `cloudflare/cloudflared`, `tunnel run --token ${CLOUDFLARE_TUNNEL_TOKEN}`; header default stays CF
  - `ollama-embed` service (`profiles: ["embeddings"]`): `ollama/ollama`, named model volume, post-start `ollama pull ${WIKANTIK_EMBEDDING_MODEL_TAG:-qwen3-embedding:0.6b}`, no GPU; wikantik gets `WIKANTIK_EMBEDDING_BASE_URL=http://ollama-embed:11434`
- Create: `deploy/config/Caddyfile`
- Validate: `docker compose -f docker-compose.yml -f docker-compose.cloud.yml --profile caddy --profile embeddings config` renders; bring the stack up locally with a GHCR-pulled image (read token), `curl -k https://localhost/api/health` via Caddy internal cert mode, and confirm an embed round-trip against the sidecar (`/admin/llm-activity`)

- [ ] Build overlay + Caddyfile; validate; document profile matrix in the file header; commit: `feat(deploy): cloud compose overlay — caddy/cloudflared/embeddings profiles, GHCR image ref`

### Task 2.3: Terraform module — `deploy/aws/`

**Files:**
- Create: `deploy/aws/{main.tf,variables.tf,outputs.tf,README.md}`, shared template `deploy/cloud-init/cloud-init.yaml.tftpl`
- Resources (deliberately minimal, default VPC): `aws_instance` (default `t3.large` for the embeddings profile / documented `t3.medium` floor; Ubuntu LTS AMI data source; IMDSv2 required), `aws_ebs_volume`+attachment for `/srv/wikantik` (gp3, `WIKANTIK_PAGES_DIR` + backups live here), `aws_dlm_lifecycle_policy` (daily snapshots, `var.snapshot_retention_days` default 14), security group (22 restricted to `var.admin_cidr`, 80/443 open; nothing else), `aws_eip`, optional `aws_route53_record` (`count = var.route53_zone_id == "" ? 0 : 1`), `aws_ssm_parameter` (SecureString) for each secret from `var.secrets` (map, sensitive), IAM role/instance-profile with a policy scoped to exactly those parameter ARNs, user-data = templated cloud-init
- Cloud-init template (shared with GCP — cloud-specific bits injected as template vars: `secret_fetch_cmd`, `data_device`): install docker + compose plugin; format/mount the data disk; write compose files + Caddyfile + `.env.static` (non-secret vars) via `write_files`; fetch secrets (`aws ssm get-parameter --with-decryption`) → append to `.env`; `docker login ghcr.io -u $GHCR_USER --password-stdin` with the read token (itself an SSM secret); install `wikantik-update.sh` (Task 2.4); `docker compose ... --profile caddy --profile embeddings up -d`
- Variables: region, instance_type, domain, admin_cidr, ghcr_token, image tag, tier preset (`core|search|knowledge` → writes the matching flag env vars from `docs/CostTiers.md`), secrets map, route53_zone_id, snapshot_retention_days
- Validate: `terraform init && terraform validate && terraform plan` clean; then a real `apply` into your AWS account → wiki reachable over HTTPS, health green, login works, search works; `terraform destroy` clean

- [ ] Author module + cloud-init; apply/verify/destroy for real (record the validation transcript in the README); commit: `feat(deploy): AWS single-VM Terraform reference deployment`

### Task 2.4: VM update script + `remote.sh deploy --pull`

**Files:**
- Create: `deploy/bin/wikantik-update.sh` (installed by cloud-init): `docker login` (cached), `docker pull $IMAGE`, retag current → `wikantik:rollback`, `compose up -d`, poll `http://localhost:8080/api/health` every 3s to `HEALTH_TIMEOUT`, auto-rollback on failure — port the exact 9-step discipline from `bin/remote.sh deploy` minus build/save/load
- Modify: `bin/remote.sh` — additive `deploy --pull <tag>` mode: skip local build + save/load; instead remote-side `docker pull ghcr.io/...:<tag> && docker tag ... wikantik:latest`, then the existing lock/health/rollback flow. Default `deploy` behavior byte-identical
- Validate: `wikantik-update.sh` on the AWS VM upgrading between two tags with a deliberate failure injected (bad tag) to prove rollback; `remote.sh deploy --pull` against the AWS VM using a second `remote.env`-style file (e.g. `REMOTE_ENV_FILE=remote-aws.env bin/remote.sh ...` — add optional `REMOTE_ENV_FILE` override, default `remote.env`, so docker1 flow is untouched)

- [ ] Implement, validate on the live AWS VM, commit: `feat(deploy): pull-based updates — VM update script + remote.sh --pull mode + REMOTE_ENV_FILE override`

### Task 2.5: RDS variant

**Files:**
- Modify: `deploy/aws/` — `var.database = "bundled" | "rds"`; when `rds`: `aws_db_instance` (postgres 16/17, `db.t4g.micro` default, pgvector via `CREATE EXTENSION`), security group db←vm only, compose profile omits the `db` service (compose: put `db` behind `profiles: ["bundled-db"]` in the cloud overlay), `.env` points `POSTGRES_HOST` at the RDS endpoint
- Modify: `docker/entrypoint.sh` — optional `POSTGRES_JDBC_PARAMS` env appended to the JDBC URL (`?sslmode=require`); pass equivalent `PGSSLMODE` to `migrate.sh` invocations
- Verify: the migration chain against a non-superuser master user — RDS master can `CREATE EXTENSION vector` (allowlisted) but is not superuser; run `install-fresh.sh`-equivalent bootstrap + all 50 migrations against a scratch RDS; wire the existing `bin/db/create-migrate-user.sh` role-separation into the runbook (not the entrypoint — one-time bootstrap step documented in `deploy/aws/README.md`)
- Validate: full stack against RDS — boot, save a page, search, backup sidecar `pg_dump` over the wire

- [ ] Implement + validate against a real scratch RDS; document; commit: `feat(deploy): RDS variant — sslmode support, bundled-db profile, non-superuser migration runbook`

### Task 2.6: AWS runbook + phase gate

- [ ] Write `docs/CloudDeploymentAws.md`: prerequisites (AWS account, GHCR read token from you, domain), `terraform apply` walkthrough, tier selection, update/rollback, restore-from-snapshot, restore-to-fresh-VM via existing `restore.sh`, cost table (t3.medium ~$30/mo floor vs t3.large ~$60/mo with embeddings; EBS/EIP/snapshots line items; RDS delta)
- [ ] End-to-end acceptance: fresh `terraform apply` from the README alone (pretend to be the stranger), all three tiers smoke-checked (`core`, `search`, `knowledge` with a real `ANTHROPIC_API_KEY` extraction round-trip)
- [ ] Full IT reactor still green (`mvn clean install -Pintegration-tests -fae`) since entrypoint/compose edits ride along with app code; docker1 routine release afterwards proves no-damage
- [ ] Commit: `docs: AWS cloud deployment runbook`

---

# Phase 3 — GCP reference deployment

### Task 3.1: Terraform module — `deploy/gcp/`

**Files:**
- Create: `deploy/gcp/{main.tf,variables.tf,outputs.tf,README.md}` reusing `deploy/cloud-init/cloud-init.yaml.tftpl`
- Resources: `google_compute_instance` (`e2-standard-2` default / `e2-medium` floor; Ubuntu LTS), attached `google_compute_disk` + `google_compute_resource_policy` snapshot schedule, firewall rules (22/admin_cidr, 80/443), static external IP, `google_secret_manager_secret`(+versions) per secret, service account with `secretAccessor` on exactly those secrets, optional `google_dns_record_set`
- Cloud-init: same template; `secret_fetch_cmd` = `gcloud secrets versions access latest --secret=...` (install google-cloud-cli in cloud-init, or use the metadata-token curl pattern to avoid the SDK — pick the lighter one that works headless)
- Validate: real `apply` → HTTPS wiki up, tiers smoke-checked, `destroy` clean

- [ ] Author, apply/verify/destroy, commit: `feat(deploy): GCP single-VM Terraform reference deployment`

### Task 3.2: Cloud SQL variant + GCP runbook

- [ ] `var.database = "cloudsql"`: `google_sql_database_instance` (Postgres 16/17, pgvector supported), private-IP-or-authorized-network choice kept simple (authorized network = VM's static IP; document the tradeoff), same non-superuser migration runbook as RDS
- [ ] `docs/CloudDeploymentGcp.md` mirroring the AWS runbook + cost table (e2-medium ~$25/mo floor, e2-standard-2 ~$49/mo, Cloud SQL delta)
- [ ] Parity check: a table in both runbooks mapping every AWS concept to its GCP twin (SSM↔Secret Manager, DLM↔resource policy, EIP↔static IP, ALB-note↔GCLB-note)
- [ ] Commit: `feat(deploy): Cloud SQL variant + GCP runbook`

---

# Phase 4 — Fast-follow backlog (explicitly out of scope now)

Recorded so they don't get relitigated:

1. **Object-storage backup push** (S3/GCS sync from the backup sidecar + lifecycle rules) — the decided fast-follow.
2. **Advisory lock around `migrate.sh`** (`pg_advisory_lock`) — harmless single-VM, prerequisite for any future multi-instance story.
3. **Managed embedding API backends** (Bedrock/Vertex `TextEmbeddingClient`) — only if a customer demands no-sidecar embeddings.
4. **Multi-instance statelessness track** (JDBC page provider, `pgvector` dense backend as cloud default, distributed sessions, scheduler leader election) — large; revisit only with a real scaling need. Note: flipping `wikantik.search.dense.backend=pgvector` on cloud VMs is already possible today and removes the boot-time HNSW rebuild; consider it during Phase 2 validation if cold-start times annoy.
5. **Legacy CI retirement** (`ci-cd.yml`, `staging-deploy.yml`) — user chose keep-for-now.
6. **Seed-content split** (stock `wikantik-wikipages` vs personal corpus in the image) — user chose keep-as-is; revisit if/when a real third party onboards.
7. **SCIM/SSO cloud validation** — SSO env vars already flow through the entrypoint; validate Google OIDC redirect URIs on a cloud domain when first needed.

## Self-Review Notes

- Spec coverage: every grilling decision (1–14) maps to a task or the Phase 4 backlog; decisions 13/14 are deliberate no-ops recorded in the table.
- The two consciously deferred verification items: exact line numbers in modify-targets must be re-checked at execution time (files evolve), and Task 1.5 has an explicit verify-before-test step because the Claude page extractor's location is unconfirmed.
- Type consistency: `GenAiMode` API (`fromProperties`, `allowsChatInference`, `allowsEmbeddings`) is used identically in Tasks 1.1/1.2/1.4; env var names introduced in Task 1.6 are the same ones consumed by Tasks 2.2/2.3.
