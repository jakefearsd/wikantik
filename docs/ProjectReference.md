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

Monitoring is handled by the external **jakemon** stack — a Grafana Alloy agent on each host pushing metrics and logs to a central Prometheus + Loki + Grafana on host **docker2**. The wikantik container exposes `/metrics`, which jakemon scrapes. There is no in-repo observability stack.

## Load testing

`bin/loadtest.sh <smoke|load|stress>` runs the k6 harness in `loadtest/`
against an instrumented set of endpoints. `--verify` scrapes `/metrics`
before and after and fails if a target dashboard panel did not move;
`--writes` adds an authenticated edit/delete cycle. k6 remote-writes its
own metrics into jakemon's central Prometheus (`192.168.0.5:9090`) so
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
build). Pre-release checklist: a green `bin/run-tests.sh --all` (the full gate —
unit + all default IT modules — plus the opt-in Authentik SCIM full-loop, which
lives outside the per-commit gate; the release is its scheduled checkpoint —
~80s warm, first run per machine pulls the 1.1GB Authentik image).
Once `release.yml` is green, `deploy-release.sh` swaps the image on
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
bind mount, independent of container lifecycle — so the page tree
survives an image swap and `deploy` never carries content.

**The checkout and prod are different corpora, not two copies.** Prod holds
pages the repo does not have. `pages-push` writes the repo's tree onto the
remote but does not reconcile the two, and `pages-pull` cannot: it fails
`Permission denied` on container-owned pages and silently returns a
*partial* corpus, which is worse than none — every unread page reads as
"missing from production". **Prod is authoritative for content; the checkout
is a mirror.** Derive corpus-wide plans from the live index (`list_clusters`,
`list_pages_by_filter`), never from the repo, and use
`CorpusDivergenceCli` (`wikantik-extract-cli`) to measure the gap — it exits
**2** when it refuses because the snapshot it was given was incomplete.

## Cloud deployment (AWS/GCP)

Single-VM Terraform reference deployment per cloud (`deploy/aws/`,
`deploy/gcp/`), sharing one cloud-init template
(`deploy/cloud-init/cloud-init.yaml.tftpl`) and the `docker-compose.cloud.yml`
overlay (GHCR image pull, Caddy/cloudflared ingress profiles, optional CPU
embedding sidecar, cost-bounded `wikantik.genai.mode` ceiling +
`wikantik.knowledge.enabled` tiers). Pull-based updates via
`deploy/bin/wikantik-update.sh` (installed on the VM by cloud-init) or
`bin/remote.sh deploy --pull TAG` (`REMOTE_ENV_FILE` override for a second
target). docker1 is untouched by any of this — every new property/env var
defaults to docker1's current behavior. Operator guide:
[CloudDeployment.md](CloudDeployment.md); module READMEs:
[deploy/aws/README.md](../deploy/aws/README.md),
[deploy/gcp/README.md](../deploy/gcp/README.md); decision record + phased
plan: [superpowers/plans/2026-07-16-aws-gcp-deployment-readiness.md](superpowers/plans/2026-07-16-aws-gcp-deployment-readiness.md).
Shipped 2026-07-16 (2.3.7); a real `terraform apply` against a live
AWS/GCP account had not been run as of that release — see the module
READMEs' "Validation status" notes.

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

### Code-health site

A published Maven site aggregating coverage (unit+IT), module coupling, PMD/CPD,
SpotBugs, tests, tech-debt, and dependency health, with per-module drill-down.

- Generate: `bin/site.sh` (full unit+IT) or `bin/site.sh --unit-only` (fast).
  Two phases (coverage build → `mvn site site:stage`), run via `bin/agent-build.sh`.
  Output: `target/staging/index.html`.
- Publish: `! bin/deploy-marketing.sh` (interactive sudo on host `cloudflare`)
  ships the marketing site **and** the code-health site in one run — they share
  a host and an nginx docroot, and both privileged copies happen inside a
  single `ssh -t`, so sudo prompts once. → https://wikantik.com/site/ .
  Excluded from indexing via `marketing/robots.txt`.
  - `--build-site` runs `bin/site.sh` first; `--marketing-only` / `--site-only`
    (or the `bin/deploy-site.sh` shim) publish one half.
  - **`--build-site` cannot finish inside an agent Bash call.** A full site build
    is 30+ minutes and agent Bash is hard-capped at ~10 minutes — no script can
    raise that cap on itself, so run it from a real terminal. If it *is* killed,
    `bin/site.sh`'s phases are detached via `bin/agent-build.sh` (setsid) and
    **keep running**. Re-running `--build-site` then refuses rather than starting
    a second concurrent Maven build over the same working tree (which would
    corrupt surefire state): poll `bin/agent-build.sh status sitecov|siteit|sitegen`,
    then publish with a plain `bin/deploy-marketing.sh` once `target/staging/`
    exists. The refusal only triggers on a genuinely live build — stale
    `.build-logs` entries report SUCCESS/KILLED, not RUNNING.
  - `SITE_WAIT_BUDGET` (default 600, raised to 1800 by `--build-site`) is a
    **poll interval, not a deadline** — `bin/site.sh` loops until the build
    actually ends. Raising it only makes the "still running" line rarer.
  - If `target/staging/` was never built, the combined run publishes marketing,
    prints a loud SKIPPED notice, and still exits 0 — a missing local build must
    not block a marketing deploy, and the `/site` already on the host is left
    intact. `--site-only` treats the same condition as a hard error, since that
    is the thing you explicitly asked for.
  - **Landmine:** the marketing half copies with `cp -r`, *not* `rsync --delete`,
    because the code-health site lives inside the same docroot at `/site`. Do
    not "improve" it to `rsync --delete` without excluding `/site`, or a
    marketing deploy will silently wipe the code-health site.
- Requires graphviz (`dot`) on the generating box for the coupling SVG (optional;
  falls back to a linked `.dot`).
- Design: `docs/superpowers/specs/2026-07-23-code-health-site-design.md`.

#### Ad-hoc PMD/SpotBugs runs: go through Maven

Run `mvn pmd:pmd` / `mvn spotbugs:spotbugs` (after a build, so `target/classes`
exists). Do **not** drive the PMD CLI directly unless you also pass
`--aux-classpath` — several rules need type resolution, and without it the report
is wrong in *both* directions:

- **False positives.** `InvalidLogMessageFormat` flags all ~84 uses of the standard
  `LOG.error("x: {}", e.getMessage(), e)` idiom, because it cannot see that the
  trailing argument is a `Throwable` (which binds to the stack trace, not to a
  `{}`). Through Maven this rule reports **zero**. `CloseResource` likewise
  mistakes `org.apache.jena.rdf.model.Statement` for `java.sql.Statement`.
- **False negatives.** `CheckResultSet`, `UnusedAssignment` and several
  `CompareObjectsWithEquals` cases do not fire at all without type resolution.

A 2026-07-25 sweep hit both: a bare CLI run reported 84 phantom log-format
violations while missing findings the Maven run surfaces. Reach for the CLI only
when you need a ruleset the poms do not configure, and give it `--aux-classpath`.

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

**Tool-description examples are in.** Every MCP tool on `/wikantik-admin-mcp` (29) and `/knowledge-mcp` (21), plus both OpenAPI tools on `/tools/*` (2), now ships with at least one worked input/output example in its schema. On the MCP servers, examples land per-property on `inputSchema.properties.<name>` and as a top-level `examples` array on `outputSchema` (the SDK's `JsonSchema` record can't carry top-level extras; `outputSchema` is a free Map). On the OpenAPI tool server, examples use OpenAPI 3.1's `example` keyword on request/response content and on parameter objects. The canonical specimen — `search_knowledge` — matches the design doc's hand-written example verbatim. Agents seeing concrete payloads make first-call success more reliable than reasoning from type schemas alone.

### Hybrid Retrieval — [HybridRetrieval.md](wikantik-pages/HybridRetrieval.md)

Implemented. BM25 + dense, fused with RRF, with fail-closed BM25 fallback. The Knowledge Graph-aware rerank step was **deleted in 2026-07**: a 2026-06-16 ceiling experiment measured no net ranking lift even with a Claude-quality KG, and the shipped dense-chunk bundle never invoked it at all. Evidence and verdict: `eval/kg-spike/A1-findings.md`. The `hybrid_graph` / `hybrid_graph_weighted` retrieval modes survive only as wire-compatible labels for historical `retrieval_runs` rows.

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

### Cluster Declaration — [ClusterDeclarationDesign.md](wikantik-pages/ClusterDeclarationDesign.md)

All seven phases shipped 2026-08-15 (2.4.0), recorded as
[ADR-0009](adr/0009-cluster-taxonomy-is-frontmatter-projection-not-filesystem-hierarchy.md).
The hub page is the authoritative *declaration* of its cluster: a cluster exists iff
exactly one page carries `type: hub` plus a scalar `cluster: <path>`. No new
frontmatter fields — the pair the corpus already wrote became a declaration.
Directory-structured content storage was evaluated and **rejected**: a path in
frontmatter is data (validatable, re-projectable, multi-valued, revertable per page),
while a path in the filesystem would bind page identity, `page_slug_history`, `OLD/`,
`-att/`, URLs and wikilinks to one axis. Cluster nesting deeper than one level and any
filesystem-shaped corpus export are **permanently out of scope, not follow-ups**.

**Multi-membership is in.** `cluster:` is scalar-or-list on non-hub pages; the first
entry is **primary** and drives placement (breadcrumbs, JSON-LD
`articleSection`/`isPartOf`, sidebar, embedding prefix), so no tie-break field was
needed. Hubs stay scalar — a list-valued hub is a save-time 422 plus a
`MULTI_CLUSTER_HUB` drift conflict. `ClusterPath.memberships(raw)` in `wikantik-api`
is the single place scalar-vs-list is resolved, and `PageDescriptor.cluster()` is
re-derived from `clusters()` so the two cannot drift. Only four consumers genuinely
changed: projection (index under every membership, **de-dup the transitive lookup** or
a page naming both a parent and its own sub-cluster is counted twice), **KG inclusion
(fail-closed — an explicit EXCLUDE on ANY membership wins outright**, so a second
membership can never quietly pull a page into the Knowledge Graph), `rename_cluster`
(must rewrite list entries, never collapse a list to a scalar), and the ontology's
`dct:subject` (one per membership).

**Matching is segment-aware, never `startsWith`.** `ClusterPath` is the single
comparison point; a bare `startsWith` makes `machine-learning-ops` a false descendant
of `machine-learning`, silently merging two unrelated clusters. Membership is
transitive and resolved at query time, so re-parenting needs no reindex. Hub selection
ties break on lowest slug and the loser is reported (previously last-writer-wins over
unsorted `listFiles()` order, so `list_clusters` could name a different hub run to run
without ever reporting a conflict).

**Enforcement ships DARK.** The duplicate-declaration 422 in
`StructuralSpinePageFilter` is gated by
`wikantik.cluster_declaration.enforcement.enabled`, default **false**. Both corpora
verified clean in Phase 0, so the flip is safe — but enabling it against a corpus that
*does* contain duplicates makes the offending hub pages un-saveable. Check
`/admin/drift` first. Everything else is a WARNING feeding the burn-down:
`DUPLICATE_CLUSTER_DECLARATION`, `HEADLESS_CLUSTER`, `UNDECLARED_CLUSTER`,
`CLUSTERLESS_HUB`, `MULTI_CLUSTER_HUB`.

**Retired with it:** the `hubs:` frontmatter field (473 pages, 89 already
multi-membership, and absent from `FrontmatterSchema` entirely) and
`HubSyncFilter` are **deleted**; hub `hasPart` is now derived from real membership
with `related` retained only as a degraded-index fallback.

**Curation tooling.** `ClusterRenameService` behind
`POST /admin/clusters/rename?from=&to=[&confirm=true]` and the `rename_cluster` MCP
tool (admin-mcp 26 → **27**). **An unconfirmed call returns the plan — that is
success, not an error**: a bulk rewrite is exactly the operation whose blast radius a
curator should see first, and computing the plan writes nothing. A target another hub
already declares is refused (409 / MCP error naming the incumbent) *before* any write;
past that gate the sweep never aborts and reports failed pages by name. It rewrites
`cluster:` frontmatter only — no page names, `canonical_id`s or URLs move. The tool is
registered **unconditionally** (refusing at call time when the structural index is
absent) because `InstructionsRegistryDriftTest`, `McpInstructionsDriftIT` and
`McpProtocolIT.EXPECTED_TOOLS` require the advertised surface not to vary with wiring
— a new MCP tool must be added to `wikantik-mcp-instructions.txt` **and**
`McpProtocolIT.EXPECTED_TOOLS` or those three go red.

**Reader surface.** `ClusterStatus.jsx` in `PageMeta` renders four states, gated on
`page.permissions.edit` and **client-side only, never SSR** — a reader who cannot edit
sees exactly what they saw before, so the anonymous render path stays byte-identical,
SEO tuning is unconfounded, and edge caching is untouched. It reads `hub_declared`, not
the presence of `hub_slug`.

**Corpus divergence.** Phase 0b shipped `CorpusDivergenceCli` (`wikantik-extract-cli`),
which compares the repo corpus to a live wiki's `/api/structure/sitemap`. **Exit 2 =
refused because a snapshot was incomplete**; exit 1 = divergence under `--check`. First
prod run: 240 findings, 163 pages present only in prod. See the corpus warning under
*Remote container deployment* above before planning any corpus-wide change.

### Other subsystems

- **[PageGraphVsKnowledgeGraph.md](wikantik-pages/PageGraphVsKnowledgeGraph.md)** — Canonical explainer distinguishing the Page Graph (wikilink edges) from the Knowledge Graph (LLM-extracted entities). Reference this before touching either subsystem.
- **[RetrievalExperimentHarness.md](wikantik-pages/RetrievalExperimentHarness.md)** — Implemented but not yet scheduled; targeted by `AgentGradeContentDesign.md` for CI integration.
- **[IndexingSupport.md](../IndexingSupport.md)** — Implemented. Raw content + change feed + sitemap for RAG ingestion and SEO.
- **KG inclusion policy** — Cluster-primary KG inclusion/exclusion policy with admin dashboard, CLI, and frontmatter override. Implemented 2026-04-27. New `kg_cluster_policy` / `kg_policy_audit` / `kg_excluded_pages` tables; admin surface at `/admin/kg-policy/*`; `bin/kg-policy.sh` CLI. Default-exclude. System pages now also filtered out of the KG extraction pipeline (latent bug fix bundled in). Page-level override via `kg_include: true|false` frontmatter, validated at save time. See [KgInclusionPolicy](wikantik-pages/KgInclusionPolicy.md) for the operator guide.
- **Derived agent hints** — Derived `agent_hints` projection field (no author burden), hub summary overlay, `read_pages` batch MCP tool, `/admin/agent-grade-audit` weak-signal report. Implemented 2026-05-10.
- **Content Intelligence (`wikantik-insights`)** — The measured content feedback loop, and the reference implementation of the Simple Agility feedback-loop pattern. jakemon ships search-visibility facts and its five detectors' opportunities into `POST /admin/insights/ingest`; Wikantik stores them (`search_visibility_snapshot` V050, `imported_opportunity` V056, `expected_ctr_curve` V057), runs its own native opportunity rules over them, tracks what changed (`content_change_log` V052, calibration V055) and measures the effect of each change, with per-opportunity snooze/seen state (V053/V054). Read surfaces: `/admin/insights/{acquisition,backlog,ingest}` and the `list_content_opportunities` + `snooze_opportunity` admin-MCP tools — both of which report `ctrCurveSource` (`imported:<as_of>` vs `builtin`) so a shipment going quiet is visible rather than merely inferable. Kill switch `wikantik.insights.enabled`; tuning under `wikantik.insights.*`. Note the deliberate split of ownership: these are *functional inputs* to Wikantik's own behavior, so they are product tables and must not be "consolidated" into the observability plane. Phase 0 (the visibility fact store) landed in the 2.4.4/2.4.5 line, the opportunity engine in 2.4.5, Phase 2 (loop closure — the engine gets a real consumer) in 2.4.6, and the imported CTR curve in 2.4.10. Design: `docs/superpowers/specs/2026-08-16-content-intelligence-design.md`. Agent-facing guidance: [docs/agents/content-feedback-loop.md](agents/content-feedback-loop.md).
- **Connector framework + admin UI** — Seven external-source connectors (filesystem, web crawler, sitemap, RSS/Atom, Google Drive OAuth2, GitHub, Confluence) syncing into derived pages, with admin-managed configs (`connector_configs`, hot-applied registry rebuild — no restart), per-connector sync scheduling, encrypted credential storage, and a full `/admin/connectors` UI (list/detail, guided add wizard, dry-run test, provenance marking on reader surfaces). Six types are admin-creatable (`ConnectorConfigCodec.UI_TYPES`); `filesystem` is properties-defined only by design (D9) because it reads arbitrary server-local paths. `SyncOrchestrator`'s contract that `poll()` never throws is load-bearing. `wikantik.connectors.enabled` is a kill switch defaulting **true**. Shipped 2026-07-15; live on prod (docker1) via 2.3.7, verified 2026-07-17. Design docs: `docs/superpowers/specs/2026-07-11-connector-framework-phase{1,2-runtime}-design.md`, `2026-07-11-{rss-atom-feed,sitemap,web-crawler}-connector-design.md`, `2026-07-11-connector-credential-encryption-design.md`, `2026-07-12-google-drive-connector-design.md`, `2026-07-14-github-confluence-connectors-design.md`, `2026-07-15-connector-admin-ui-design.md`. Operator/user-facing guide: [Connectors.md](Connectors.md).
