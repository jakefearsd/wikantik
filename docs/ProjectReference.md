# Wikantik Project Reference

Operational runbooks and detailed subsystem status moved out of `CLAUDE.md` to keep that file
focused on rules + the architecture map. Nothing here is per-turn guidance — consult it when you
are actually deploying, load-testing, running the entity extractor, or working on one of the
design-doc subsystems.

## Container deployment

For container-based deployment (recommended for production), use
`bin/container.sh` — a top-level wrapper around `docker compose` that
drives build / up / down / logs / shell / psql / migrate / backup /
restore / smoke-test against the canonical service set:

```bash
bin/container.sh --help                         # subcommand list
bin/container.sh build                          # build the image
bin/container.sh up -d                          # start the dev stack
bin/container.sh logs -f                        # tail wikantik
bin/container.sh psql -- -c '\dt'               # list DB tables
bin/container.sh -e prod up -d                  # production stack with backup sidecar
bin/container.sh smoke-test                     # ephemeral up/health/down on test ports
```

Environments: `dev` (default), `prod`, `test`, `base`. Each subcommand
also accepts `--help`. Underlying compose files at the repo root
(`docker-compose{,.dev,.prod,.test}.yml`) and the runtime entrypoint at
`docker/entrypoint.sh` are still the source of truth — `bin/container.sh`
is just an ergonomic facade.

Monitoring is handled by the external **jakemon** stack — a Grafana Alloy agent on each host pushing metrics and logs to a central Prometheus + Loki + Grafana on host `inference`. The wikantik container exposes `/metrics`, which jakemon scrapes. There is no in-repo observability stack.

## Load testing

`bin/loadtest.sh <smoke|load|stress>` runs the k6 harness in `loadtest/`
against an instrumented set of endpoints. `--verify` scrapes `/metrics`
before and after and fails if a target dashboard panel did not move;
`--writes` adds an authenticated edit/delete cycle. k6 remote-writes its
own metrics into jakemon's central Prometheus (`192.168.0.10:9090`) so
offered load and host response share a timeline. See `loadtest/README.md`.

**Container deployment gotchas** (learned from the first docker1 deploy,
2026-05-16):
- **PostgreSQL image major version is coupled to the volume mount path.**
  The `db` service runs `pgvector/pgvector:pg18`. The pg18+ Docker images
  store data under a version-specific subdir and require the volume at
  `/var/lib/postgresql` — mounting the old `/var/lib/postgresql/data`
  makes the image refuse to start. Bumping the pg major version means
  re-checking that mount path. Keep the container pg major version in
  step with the local dev Postgres: a `pg_dump` restores forward across
  versions, not backward.
- **The deploying OS user must be in the `docker` group** on the target
  host. `bin/remote.sh bootstrap` only checks that the `docker` binary
  exists, not that the daemon socket is reachable — so it can pass while
  the actual deploy later fails with a `docker.sock` permission error.
- **Initialising the DB from an existing dump is a manual sequence**, not
  something `remote.sh deploy` does (it runs a full `up -d`, and the app
  entrypoint then migrates an empty schema). Bring up `db` alone, restore
  the dump (`DROP SCHEMA public CASCADE` first to clear the image's seed
  schema), then start `wikantik`. **To stand a fresh host up *from a backup*,
  use `bin/dr-restore.sh <host>`** — it automates this whole sequence (image +
  verified snapshot transfer → `db` → `restore.sh` → `wikantik` → smoke test),
  pulling the image from GHCR by default so it works even if the prod host is
  gone. See [docs/BackupAndRecovery.md](BackupAndRecovery.md) §5.1.

## Remote container deployment over ssh

`bin/remote.sh` is the single entry point for deploying and administering
Wikantik on a remote host over ssh. It wraps `bin/container.sh` on the
remote and adds image transfer (`docker save | ssh 'docker load'`), pages
rsync, and a deploy lock. Configuration lives in `remote.env` at the repo
root (copy from `remote.env.example`; gitignored). Every state-changing
subcommand accepts `--dry-run`.

```bash
bin/remote.sh --help                          # subcommand list
bin/remote.sh bootstrap                       # first-time remote setup
bin/remote.sh deploy                          # local build → ssh push → up -d → health-poll
bin/remote.sh status                          # container ps + health + disk
bin/remote.sh pages-push docs/wikantik-pages  # rsync pages to remote (no --delete by default)
bin/remote.sh rollback                        # re-promote :rollback image
```

**Routine release upgrades** use two wrappers that capture the happy-path
command sequences as a single `bash` run each:

```bash
bin/cut-release.sh X.Y.Z       # version bump + CHANGELOG + tag + push → triggers release.yml
bin/deploy-release.sh X.Y.Z    # pull the published image → bin/remote.sh deploy --skip-build
```

`cut-release.sh` cuts the release (run a green build first — it does not
build); once `release.yml` is green, `deploy-release.sh` swaps the image on
the remote. The DB (named volume `repo_pgdata`) and pages (host bind mount)
persist across the swap, and the container entrypoint applies any pending
schema migrations on start — so an upgrade is just an image swap. The
**first** deploy is the exception: it initialises the DB (restore from a
`pg_dump`) and the page tree. Full procedure in
[docs/DockerDeployment.md](DockerDeployment.md).

`remote.env` carries the ssh/host config; the prod container config is a
gitignored `.env.prod` at the repo root (`remote.sh` ships it to the remote
as `.env`, preferring it over the dev `.env`).

Prod content lives at `${WIKANTIK_PAGES_DIR}` on the remote host as a
bind mount — so `rsync` is the source of truth for the page tree,
independent of container lifecycle.

## `bin/` script conventions

- Every script under `bin/` and `docker/` responds to `-h` / `--help`
  with its own header docstring. Use it.
- For scripts that pass through to a Java jar (`bin/kg-extract.sh`,
  `bin/kg-judge-experiment.sh`, `bin/kg-policy.sh`,
  `bin/kg-chunker-stats.sh`), the bash `--help` shows wrapper-level docs
  without triggering a build. Pass `--jar-help` to forward through and
  see the jar's full flag list.
- Credentials are read at runtime from `test.properties` (web logins) and
  `tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml` (DB password). No
  bin/ script embeds secrets.

## Running the entity extractor

`bin/kg-extract.sh` runs the per-page entity-extraction pipeline against the
local PostgreSQL via the deployed ROOT.xml. Defaults — `gemma4-assist:latest`
at concurrency 2, no judge — produce ~200–500 deduplicated, evidence-grounded
proposals in ~3.6 hours over a 1000-page corpus.

Routine usage:
```bash
bin/kg-extract.sh --max-pages 50 --dry-run --report reports/smoke.json   # smoke
bin/kg-extract.sh --report reports/extract-$(date +%Y%m%d).json          # full run
```

If the pending-proposal queue gets unwieldy and a clean restart is the right
call, snapshot pending proposals first, then wipe:

```bash
PGPASSWORD=… pg_dump -h localhost -U jspwiki -d jspwiki \
    --data-only --table=kg_proposals --column-inserts \
    --where="status = 'pending'" \
    > backups/kg_proposals_pending_$(date +%Y%m%d).sql

PGPASSWORD=… psql -h localhost -U jspwiki -d jspwiki -c \
    "DELETE FROM kg_proposals WHERE status = 'pending';"
```

Per the no-data-in-migrations rule, wipes are never landed in `Vxxx`
migrations — they are documented one-shots run by the operator.

## Active Design Documents — detailed status

Slim "read before touching" pointers live in `CLAUDE.md`. The detailed "what shipped" status for
each subsystem is recorded here.

### Structural Spine — [StructuralSpineDesign.md](wikantik-pages/StructuralSpineDesign.md)

Machine-queryable structural index for the wiki (clusters, tags, canonical IDs, `/api/structure/*`,
matching MCP tools, generated `Main.md`, save-time enforcement). Sub-area of the **Page Graph**
subsystem. All four phases implemented. Note: the typed `relations:` frontmatter mechanism was
removed 2026-05-02.

**`Main.md` is generated.** Edit `docs/wikantik-pages/Main.pins.yaml` instead, then run `mvn package -pl wikantik-extract-cli -am -DskipTests -q && java -cp wikantik-extract-cli/target/wikantik-extract-cli.jar com.wikantik.extractcli.GenerateMainPageCli docs/wikantik-pages --write`. Hand-edits to `Main.md` will be reverted by the next regeneration and will fail `MainPageRegressionTest` on CI.

**Save-time enforcement is on.** `StructuralSpinePageFilter` runs in `preSave`: pages saved without `canonical_id` get one auto-assigned and injected into frontmatter. Toggle with `wikantik.structural_spine.enforcement.enabled=false` (default `true`). Operators triage lingering issues at `GET /admin/page-graph/conflicts`. (The `relations:` field validation was removed 2026-05-02 when typed relations were dropped.)

### Agent-Grade Content — [AgentGradeContentDesign.md](wikantik-pages/AgentGradeContentDesign.md)

Agent-grade content layer (`type: runbook`, verification metadata, `/api/pages/for-agent/{id}`
token-optimised projection, scheduled retrieval-quality CI (`DefaultRetrievalQualityRunner`), worked
tool-description examples). All six phases shipped 2026-04-25 — design is complete.

**Page verification is in.** Frontmatter accepts `verified_at`, `verified_by`, `confidence` (authoritative | provisional | stale — usually computed; author can pin), and `audience` (`humans` | `agents` | `[humans, agents]`). The structural index rebuild reads these and writes them through to `page_verification`. Confidence is computed from `verified_at` + the `trusted_authors` registry by `ConfidenceComputer` (90-day stale window, configurable via `wikantik.verification.stale_days`). Authors stamp pages via the `mark_page_verified` MCP tool on `/wikantik-admin-mcp`; operators triage at `GET /admin/verification?confidence=stale`.

**`/for-agent` projection is in.** `GET /api/pages/for-agent/{canonical_id}` and the matching `get_page_for_agent` MCP tool on `/knowledge-mcp` return a token-budgeted projection of any page: summary, key facts, headings outline, recent changes, MCP tool hints, and verification state — without the full markdown body. (The `outgoingRelations`/`incomingRelations` fields were removed 2026-05-02 when typed relations were dropped; use `get_outbound_links`/`get_backlinks` on `/wikantik-admin-mcp` for Page Graph traversal.) The service composes four extractors (`HeadingsOutlineExtractor`, `KeyFactsExtractor`, `RecentChangesAdapter`, `McpToolHintsResolver`) with per-field try/catch graceful degradation; failures surface on a `degraded` flag + `missing_fields` list rather than blowing the whole response. Memoised in `wikantik.forAgentCache` (1h TTL, 5K entries) by `(canonical_id, updated_at_millis)`. Response sizes flow into the `wikantik_for_agent_response_bytes` Prometheus histogram. URL deviation: design said `/api/pages/{id}/for-agent` but Servlet API can't tail-segment-pattern; current path mirrors `/api/pages/by-id/{id}`. The projection now also carries derived `agent_hints` — `prefer_tools` (ranked across the page and its cluster hub via `McpToolHintsResolver`) and `prefer_pages` (cluster hub + intra-cluster wikilink centrality, with a verified-authoritative bonus). When the projection's authored hub summary matches the generic "Index of pages on…" pattern, `HubSummarySynthesizer` overlays a Top-3 highlight at projection time and sets `summary_synthesized: true` (the page body is never modified). Both fields are computed at projection time — no author burden.

**Runbook page type is in.** Frontmatter accepting `type: runbook` plus a six-key `runbook:` block (`when_to_use`, `inputs`, `steps`, `pitfalls`, `related_tools`, `references`) — schema-validated by `FrontmatterRunbookValidator`, enforced at save time by `RunbookValidationPageFilter` (priority -1003, gated by `wikantik.runbook.enforcement.enabled`, default `true`). The `/for-agent` projection runs the same validator at read time so corpus drift is graceful — invalid runbooks land with `runbook: null` and `"runbook"` in `missing_fields` rather than poisoning the response. `references:` entries resolve to either canonical_ids (via the structural index) or page titles (via `PageManager.pageExists`); `related_tools:` entries match `/api|knowledge-mcp|wikantik-admin-mcp|tools/*` or a bare snake_case tool name. `RunbookBlock` (in `wikantik-api`) carries snake_case Java field names so default Gson serialisation matches the wire form without a per-instance naming policy.

**Retrieval-quality CI is in.** `DefaultRetrievalQualityRunner` (in `wikantik-main` under `com.wikantik.knowledge.eval`) executes the curated `core-agent-queries` query set (16 questions seeded from the agent-cookbook runbooks, plus one cross-cluster query) through `BM25`, `HYBRID`, and `HYBRID_GRAPH`, computes per-query nDCG@5/@10 + Recall@20 + MRR, persists aggregates to `retrieval_runs`, and publishes `wikantik_retrieval_ndcg_at_5` / `_at_10` / `_recall_at_20` / `_mrr` gauges keyed by `{set,mode}`. Schedule activates when `wikantik.retrieval.cron.enabled=true` (default; default hour `wikantik.retrieval.cron.hour_utc=3`). Operators triage at `GET /admin/retrieval-quality?limit=N` and trigger ad-hoc runs via `POST /admin/retrieval-quality/run` with `{"query_set_id":"...","mode":"..."}`. The runner depends on narrow `Retriever` / `CanonicalIdResolver` functional seams so `RetrievalQualitySmokeTest` (the pre-merge gate) can drive it deterministically without a live search stack. Threshold tuning is deferred — `nDCG@5 >= 0.5` is the smoke gate; production thresholds calibrate after two weeks of nightly runs.

**Tool-description examples are in.** Every MCP tool on `/wikantik-admin-mcp` (25) and `/knowledge-mcp` (16), plus both OpenAPI tools on `/tools/*` (2), now ships with at least one worked input/output example in its schema. On the MCP servers, examples land per-property on `inputSchema.properties.<name>` and as a top-level `examples` array on `outputSchema` (the SDK's `JsonSchema` record can't carry top-level extras; `outputSchema` is a free Map). On the OpenAPI tool server, examples use OpenAPI 3.1's `example` keyword on request/response content and on parameter objects. The canonical specimen — `search_knowledge` — matches the design doc's hand-written example verbatim. Agents seeing concrete payloads make first-call success more reliable than reasoning from type schemas alone.

### Hybrid Retrieval — [HybridRetrieval.md](wikantik-pages/HybridRetrieval.md)

Implemented. BM25 + dense + Knowledge Graph-aware rerank with fail-closed BM25 fallback.

Dense backend is selectable via `wikantik.search.dense.backend = inmemory | pgvector | lucene-hnsw`.
**`lucene-hnsw` is the docker1 production default** — an in-process Lucene HNSW
ANN index (RAM `ByteBuffersDirectory`, rebuilt on boot, metadata read via
DocValues not stored fields) that replaced the brute-force `inmemory` scan
(~60% of search CPU). Knobs: `wikantik.search.dense.lucene.{m=16,ef_construction=64,ef_search=100}`.
`pgvector` (server-side HNSW on `content_chunk_embeddings.embedding`, V032)
is for split-DB topologies; `inmemory` (exact brute force) is the rollback.

**Performance & concurrency tuning** (the 2026-05-22 scaling campaign): the
per-request DB-connection tax and a chain of shared-lock hotspots — not CPU —
were the real ceiling under load. Removed via short-TTL caches (API-key verify,
user lookup, KG mentions) and hoisting shared JDK objects off the per-request
path (`Collator`, `TimeZone`, `SecureRandom`). The backpressure semaphore
(`WIKANTIK_MAX_INFLIGHT_REQUESTS`, default **390**) **must sit below Tomcat
`maxThreads`=400** or it can never fire. Operator reference:
[WikantikOperations.md § 1.5](WikantikOperations.md#15-performance--concurrency-tuning);
full diagnostic chain: [ScalingCharacterization.md § 14](ScalingCharacterization.md).
Diagnose concurrency stalls with thread dumps (`jcmd 1 Thread.print`) + host CPU
from Prometheus — high latency + moderate CPU means blocking, not compute.

### Other subsystems

- **[PageGraphVsKnowledgeGraph.md](wikantik-pages/PageGraphVsKnowledgeGraph.md)** — Canonical explainer distinguishing the Page Graph (wikilink edges) from the Knowledge Graph (LLM-extracted entities). Reference this before touching either subsystem.
- **[RetrievalExperimentHarness.md](wikantik-pages/RetrievalExperimentHarness.md)** — Implemented but not yet scheduled; targeted by `AgentGradeContentDesign.md` for CI integration.
- **[IndexingSupport.md](../IndexingSupport.md)** — Implemented. Raw content + change feed + sitemap for RAG ingestion and SEO.
- **KG inclusion policy** — Cluster-primary KG inclusion/exclusion policy with admin dashboard, CLI, and frontmatter override. Implemented 2026-04-27. New `kg_cluster_policy` / `kg_policy_audit` / `kg_excluded_pages` tables; admin surface at `/admin/kg-policy/*`; `bin/kg-policy.sh` CLI. Default-exclude. System pages now also filtered out of the KG extraction pipeline (latent bug fix bundled in). Page-level override via `kg_include: true|false` frontmatter, validated at save time. See [KgInclusionPolicy](wikantik-pages/KgInclusionPolicy.md) for the operator guide.
- **Derived agent hints** — Derived `agent_hints` projection field (no author burden), hub summary overlay, `read_pages` batch MCP tool, `/admin/agent-grade-audit` weak-signal report. Implemented 2026-05-10.
