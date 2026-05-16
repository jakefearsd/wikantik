# JSPWiki Development News

A log of recent development activity on the JSPWiki project.

---

## May 2026

**2026-05-16** — build: bump main to 2.0.1-SNAPSHOT post-release

**2026-05-16** — ci: make ci-cd, codeql, and staging-deploy manual-only

**2026-05-16** — content: log release build fix in News

**2026-05-16** — ci: fix release build — skip test execution, not test compilation

**2026-05-16** — content: log 2.0.0 release in News

**2026-05-16** — release: 2.0.0

**2026-05-16** — content: log corpus expansion commit in News

**2026-05-16** — docs: comprehensive high-fidelity expansion of technical and mathematical corpus

**2026-05-16** — content: log TableOfContents plugin retirement commits in News

**2026-05-16** — content: log recent development activity in News

**2026-05-16** — test(toc): assert real anchor structure and no-headings case

**2026-05-16** — refactor(toc): delete unreachable TableOfContents plugin and heading-listener code

**2026-05-16** — test(toc): cover numbered=yes and title entity-escaping; align param source

**2026-05-16** — feat(toc): honor title and numbered params in TableOfContents markup

**2026-05-16** — docs(toc): implementation plan — TableOfContents plugin retirement

**2026-05-16** — docs(toc): design — retire dead TableOfContents plugin, wire TOC params

**2026-05-16** — build(spotbugs): document LlmCall reserved-token-field suppression

**2026-05-16** — refactor(rest): remove vestigial engineOverride test-seam scaffolding

**2026-05-16** — style: remove 16 unused imports across 5 modules

**2026-05-16** — fix(kg): log real chunk count in extraction startup line

**2026-05-15** — perf(kg): cache compiled mention patterns in MentionAttributor

**2026-05-15** — test(api): cover FrontmatterWriter, PageType, StructuralFilter

**2026-05-15** — fix(mcp): handle null id elements in inspect/review proposals tools

**2026-05-15** — test(api): cover six untested wikantik-api helper classes

**2026-05-15** — fix(admin): forward offset param in listProposalsFiltered

**2026-05-15** — feat(llm-activity): subsystem/status filter chips and colored subsystem badge

**2026-05-15** — feat(llm-activity): add LLM Activity tab to the admin Knowledge page

**2026-05-15** — fix(llm-activity): align LlmActivityTab with admin CSS vocabulary

**2026-05-15** — feat(llm-activity): LlmActivityTab component

**2026-05-15** — feat(llm-activity): getLlmActivity API client method

**2026-05-15** — feat(llm-activity): map /admin/llm-activity servlet

**2026-05-15** — fix(llm-activity): log warnings on bad query parameters

**2026-05-15** — feat(llm-activity): GET /admin/llm-activity snapshot endpoint

**2026-05-15** — feat(llm-activity): install recording decorators during subsystem wiring

**2026-05-15** — feat(llm-activity): recording decorator for TextEmbeddingClient

**2026-05-15** — feat(llm-activity): recording decorator for KgProposalJudgeService

**2026-05-15** — feat(llm-activity): recording decorator for EntityExtractor

**2026-05-15** — feat(llm-activity): static holder for the activity log

**2026-05-15** — test(llm-activity): assert in-flight records survive count-cap eviction

**2026-05-15** — feat(llm-activity): in-memory ring buffer for LLM calls

**2026-05-15** — feat(llm-activity): value types for the LLM activity log

**2026-05-15** — docs(plan): LLM activity view implementation plan

**2026-05-15** — docs(spec): LLM activity view design

**2026-05-15** — content: log recent commits in News

**2026-05-15** — fix(admin): remove orphaned "Sync frontmatter to graph" button

**2026-05-15** — content: log latest commit in News, normalize ToolUse frontmatter

**2026-05-15** — content: add hub pages, new wiki articles, and refresh existing pages

**2026-05-15** — fix(mcp): expand curate_nodes tool description with shape + node_type regex

**2026-05-15** — fix(kg): revert pre-flight vocab check; translate DB CHECK violation instead

**2026-05-15** — fix(spine): detect rename collision in UPDATE branch of PageCanonicalIdsDao

**2026-05-15** — fix(kg): pre-validate relationship_type against closed vocab

**2026-05-15** — fix(pages): quote PaxosAndRaft title (unquoted colon broke YAML)

**2026-05-15** — fix(logs): catalina.out repairs — Vector API, drift detector, edge tool description

**2026-05-15** — fix(logs): catalina.out noise reduction — yaml line/col + cached instructions

**2026-05-15** — fix(kg): translate kg_edges FK violations into clean per-op errors

**2026-05-15** — fix(spine): suppress page_verification FK violations when canonical_id is stale

**2026-05-15** — test(it): awaitAdminReady polling to eliminate post-login session race

**2026-05-14** — docs(decomp): replace XXXXXX placeholder with Phase 12 commit hash 73ef2c24d

**2026-05-14** — refactor(decomp): Phase 12 — KnowledgeSubsystemBridge consistency fix

**2026-05-14** — docs(spec+plan): Phase 12 revision 2 — scope down to Knowledge asymmetry fix

**2026-05-14** — docs(plans): Phase 12 — subsystem bridge retirement implementation plan

**2026-05-14** — docs(specs): Phase 12 — subsystem bridge retirement design

**2026-05-14** — chore: gitignore *.skill (local CLI skill bundles)

**2026-05-14** — chore: gitignore .gemini/ (local Gemini CLI workspace)

**2026-05-14** — docs(content): expand wiki pages — distributed-systems patterns + finance + retrieval

**2026-05-14** — refactor: cross-cutting code-quality pass driven by 72h audit

**2026-05-14** — feat(mcp): list_orphaned_kg_nodes admin tool for degree-0 KG triage

**2026-05-14** — fix(deploy): backup-pull latest-snapshot discovery; harden pages-push --mirror preview

**2026-05-14** — docs: note bin/remote.sh in CLAUDE.md; final shellcheck pass

**2026-05-14** — feat(deploy): status subcommand for at-a-glance remote health

**2026-05-14** — feat(deploy): backup-trigger, backup-pull, restore subcommands

**2026-05-14** — feat(deploy): pages-push (no --delete default) and pages-pull

**2026-05-14** — feat(deploy): rollback subcommand

**2026-05-14** — feat(deploy): deploy subcommand with tag-rollback, image stream, health-poll, auto-rollback

**2026-05-14** — feat(deploy): bootstrap subcommand for first-time remote setup

**2026-05-14** — feat(deploy): pass-through subcommands (up/down/restart/logs/shell/psql/migrate)

**2026-05-14** — fix(deploy): quote remote lockfile path; document ssh-options duplication

**2026-05-14** — feat(deploy): internal helpers (_ssh, _rsync, _run, _acquire_deploy_lock)

**2026-05-14** — fix(deploy): keep cd $REPO_ROOT; correct test setup

**2026-05-14** — feat(deploy): bin/remote.sh skeleton with --help and env loading

**2026-05-14** — feat(deploy): add remote.env template and gitignore the live file

**2026-05-14** — feat(deploy): bind-mount pages dir in prod compose overlay

**2026-05-14** — docs: spec + plan for remote container admin tooling

**2026-05-14** — test(it): wire-level IT for mixed page/entity edge refusal

**2026-05-14** — fix(kg): explicit refusal when mixed page/entity edge guard fires

**2026-05-14** — feat(kg): allow 'generalizes' as a relationship_type (V030)

**2026-05-14** — feat(ui): KG viewer dropdown to filter by edge endpoint-class

**2026-05-14** — test(it): wire-level visibility IT proves admin-bypass on query_nodes + search_knowledge

**2026-05-14** — feat(kg): node_type vocabulary regex at upsertNode + propose_knowledge

**2026-05-14** — docs(kg): document admin-bypass on read paths + bump tool count to 24

**2026-05-14** — chore(ops): one-shot script to clean legacy node_type pollution

**2026-05-14** — feat(kg): wire admin-bypass on REST + curator MCP read paths

**2026-05-14** — feat(mcp): register admin-bypass query_nodes + search_knowledge on /wikantik-admin-mcp

**2026-05-14** — fix(mcp): curate_{nodes,edges}.upsert returns helpful error on nested shape

**2026-05-14** — fix(kg): refuse mergeNodes when source or target UUID is missing

**2026-05-14** — feat(kg): admin-bypass overloads on KgNodeRepository + KnowledgeGraphService

**2026-05-14** — feat(kg): KgInclusionFilter admin-bypass accessor + bypass fragments

**2026-05-14** — docs(plans): KG curation operability implementation plan

**2026-05-14** — docs(specs): KG curation operability design

**2026-05-14** — test(it): wire-level coverage for /tools/* access filter

**2026-05-14** — test(it): wire-level coverage for /api/changes RAG / SEO sync feed

**2026-05-14** — test(it): wire-level coverage for previously-untested /admin endpoints

**2026-05-14** — test(it): wire-level coverage for /knowledge-mcp read tools

**2026-05-14** — test(it): wire-level coverage for /wikantik-admin-mcp read + write tools

**2026-05-13** — fix(mcp): collapse instruction-file lookup to property + bundled resource

**2026-05-13** — test(mcp): document and verify IPv6 CIDR support

**2026-05-13** — fix(mcp): Caffeine-backed rate limiter with bounded eviction

**2026-05-13** — fix(mcp): fail-closed returns discriminating 503 body + Retry-After

**2026-05-13** — docs(mcp): breadcrumb for Phase 9 KnowledgeSubsystemBridge retirement

**2026-05-13** — docs(plans): MCP infrastructure hardening implementation plan

**2026-05-13** — docs(specs): MCP infrastructure hardening design

**2026-05-13** — test(it): cover already-reviewed per-id error in review_proposals

**2026-05-13** — fix(kg): refuse re-review of already-reviewed proposals per design spec

**2026-05-13** — test(it): broaden KG curation MCP coverage to §6 edge cases

**2026-05-13** — fix(it): scroll modal Confirm into view before clicking in EdgeCurationBrowserIT

**2026-05-13** — fix(it): point KG curation IT at actual Cargo security log path

**2026-05-13** — docs(mcp-instructions): describe inspect/review/curate KG curation tools

**2026-05-13** — fix(kg-curation): return null cleanly for missing proposal id in approve/reject

**2026-05-13** — chore(build): drop stale styles/static reference from war packaging

**2026-05-13** — test(it): wire-level Cargo IT for KG curation MCP tools

**2026-05-13** — feat(kg-curation): surface kg_excluded_pages warning on approve

**2026-05-13** — refactor(kg-curation): route REST through KgCurationOps + ProposalConflictFlags

**2026-05-13** — docs: bump /wikantik-admin-mcp tool count + cross-link from KgInclusionPolicy

**2026-05-13** — test(it): expect KG curation tools in MCP tool list

**2026-05-13** — feat(mcp): register inspect/review/curate-edges/curate-nodes tools

**2026-05-13** — fix(kg-curation): grammar in review_proposals message + relocate ProposalConflictFlagsTest

**2026-05-13** — feat(mcp): curate_edges bulk heterogeneous edge curation tool

**2026-05-13** — feat(mcp): inspect_proposals deep-dive bulk read tool

**2026-05-13** — feat(mcp): curate_nodes bulk heterogeneous node curation tool

**2026-05-13** — fix(kg-curation): align ProposalConflictFlags blank-name guard + McpAudit bulk-log prefix

**2026-05-13** — feat(mcp): bulk-limit config (default 50) + logBulkWrite audit

**2026-05-13** — feat(kg-curation): shared ProposalConflictFlags helper

**2026-05-13** — feat(kg-curation): node ops on facade (upsert/delete/merge)

**2026-05-13** — feat(kg-curation): edge ops on facade (upsert/confirm/delete/delete-and-reject)

**2026-05-13** — feat(kg-curation): move edge-approval frontmatter write-back into facade

**2026-05-13** — feat(kg-curation): KgCurationOps facade with proposal review methods

**2026-05-13** — docs(plans): KG curation on MCP implementation plan

**2026-05-13** — docs(specs): KG curation on MCP design

**2026-05-12** — test(it): scope EdgeCurationBrowserIT Confirm click to the modal

**2026-05-12** — feat(kg-admin): pending-proposal breakdown and clarity pass on the schema header

**2026-05-12** — feat(node-explorer): show mention chunks and edge-deletion impact

**2026-05-12** — docs(content): refresh News.md with today's edge-curation work

**2026-05-12** — feat(edge-curation): one-click Confirm to elevate edges to human-curated

**2026-05-12** — feat(edge-curation): proposal-page fallback for unattributed concept nodes

**2026-05-12** — feat(edge-curation): render mention chunks as Markdown with entity highlight

**2026-05-12** — feat(edge-curation): source/target mention panel for disambiguation

**2026-05-12** — fix(kg-admin): edge/node lookup uses ID, not name, so slashes don't 400

**2026-05-11** — docs(content): wiki content updates

**2026-05-11** — chore(dev): mcd shorthand now redeploys instead of full deploy

**2026-05-11** — feat(retrieval): provenance-weighted KG rerank probe + closed rel-type vocab

**2026-05-11** — fix(it): EdgeCurationBrowserIT clicks source-name button, not <tr>

**2026-05-11** — feat(admin): Node Explorer adopts AdminTable + fixes delete reload bug

**2026-05-11** — fix(search): render Markdown in search-result snippets

**2026-05-11** — feat(kg): code-level guard rejects mixed page/entity edges

**2026-05-11** — feat(kg): Edge Explorer endpoint-kind filter (pages / entities / both)

**2026-05-11** — feat(frontend): EdgeExplorer adopts AdminTable for bulk edits

**2026-05-11** — fix(frontend): consistent form styling in edge curation modals

**2026-05-11** — fix(frontend): use defined CSS variables for edge curation modals

**2026-05-11** — fix(kg): expose V027 vocabulary in schema + rework Selenide IT

**2026-05-11** — test(selenide): end-to-end Edge Explorer create + delete-and-reject

**2026-05-11** — test(it): wire-level IT for admin edge curation endpoints

**2026-05-11** — feat(frontend): wire EdgeExplorer to create/edit/delete/reject/bulk-delete

**2026-05-11** — feat(frontend): EdgeFormModal shared between Create and Edit

**2026-05-11** — feat(frontend): client wrappers for edge curation endpoints

**2026-05-11** — feat(admin): edge curation endpoints on AdminKnowledgeResource

**2026-05-11** — feat(kg): KnowledgeGraphService methods for edge curation v0

**2026-05-11** — test(kg): regression-lock upsertEdge tier/proposal stamping

**2026-05-11** — feat(kg): bulk delete + delete-and-reject on KgEdgeRepository

**2026-05-11** — feat(kg): KgEdgeRepository.countEdgesWithFilter

**2026-05-11** — feat(kg): KgEdgeAuditRepository for append-only edge mutation audit

**2026-05-11** — feat(kg): add HUMAN_CURATED provenance for admin-UI edge writes

**2026-05-11** — db(V028): kg_edge_audit append-only audit table

**2026-05-11** — docs(plan): KG edge curation v0 implementation plan

**2026-05-11** — docs(spec): KG edge curation v0 design

**2026-05-10** — docs(retrieval): clarify KG-rerank uses untyped co-mention proximity

**2026-05-10** — docs(retrieval): clean up corrupt frontmatter on WikantikSearchAndRetrieval.md

**2026-05-10** — docs+memory: agent-grade Phase 7 + page/spine consumer notes

**2026-05-10** — docs: bump /knowledge-mcp count to 16 + describe agent_hints surface

**2026-05-10** — test(agent-hints): IT for /for-agent fields, read_pages batch, audit endpoint

**2026-05-10** — test(agent-hints): wire-shape assertions for new projection fields

**2026-05-10** — feat(agent-hints): Prometheus counters for derivation, hub overlay, read_pages

**2026-05-10** — feat(agent-grade): wire /admin/agent-grade-audit into REST application

**2026-05-10** — test(agent-grade): add explicit coverage for stale_verification flag

**2026-05-10** — feat(agent-grade): add /admin/agent-grade-audit weak-signal report

**2026-05-10** — feat(agent-batch): register read_pages on /knowledge-mcp

**2026-05-10** — feat(agent-batch): add read_pages MCP tool — batched markdown reads

**2026-05-10** — feat(agent-hints): wire AgentHintsDeriver + HubSummarySynthesizer into projection

**2026-05-10** — feat(agent-hints): AgentHintsDeriver prefer_pages — cluster-centrality ranking

**2026-05-10** — docs(spec): tie-break is insertion order, not alphabetical

**2026-05-10** — feat(agent-hints): AgentHintsDeriver skeleton + prefer_tools derivation

**2026-05-10** — refactor(agent-hints): use String.join for top-3 title concatenation

**2026-05-10** — feat(agent-hints): add HubSummarySynthesizer for projection-time hub overlay

**2026-05-10** — feat(agent-hints): extend ForAgentProjection with agentHints + summarySynthesized

**2026-05-10** — docs(agent-hints): document why PreferredPage.role defaults instead of throws

**2026-05-10** — feat(agent-hints): add AgentHintsBlock and PreferredPage records

**2026-05-10** — docs(plan): implementation plan for derived agent hints + read_pages

**2026-05-10** — docs(spec): repair components table layout in agent-hints spec

**2026-05-10** — docs(spec): derived agent hints + read_pages batch tool design

**2026-05-10** — docs: overhaul mathematics cluster with exhaustive depth and spatial intuition

**2026-05-09** — fix(kg-judge): properties file pinned timeout to 30s, defeating Java's 120s default

**2026-05-09** — chore: gitignore /TODO.md (personal working file)

**2026-05-09** — release: add release-on-tag workflow + ROADMAP.md

**2026-05-09** — chore(github): add issue + pull-request templates

**2026-05-09** — docs(readme): badges + Why-Wikantik comparison + Mermaid architecture diagram

**2026-05-09** — docs(community): add CONTRIBUTING.md, SECURITY.md, CODE_OF_CONDUCT.md, FUNDING.yml

**2026-05-09** — docs: add definitive WikantikOperations.md handbook

**2026-05-09** — content: blockchain-tech cluster + Tomcat 11 wiki-page mirror + News trim

**2026-05-09** — docs: drop legacy root-level ChangeLog.md + duplicate mvn_cheat-sheet.md

**2026-05-09** — docs(readme): link 5 high-value docs that were previously orphaned

**2026-05-09** — docs: bring README.md + every linked doc up to date with the codebase

**2026-05-09** — fix(scripts): reset_node_judge_verdicts --help works without PGPASSWORD

**2026-05-09** — add bin/container.sh — top-level wrapper around docker compose

**2026-05-09** — docs(scripts): standardise --help across every bin/ + docker/ shell script

**2026-05-09** — fix(it): finish the pages.haddock → pages.spa import migration

**2026-05-09** — cleanup(it-pages): rename pages.haddock package + HaddockPage interface

**2026-05-09** — cleanup(legacy-css-docs): drop haddock-dark.css + fossil JSPWiki docs

**2026-05-09** — cleanup(plain-editor): drop the dead JSPWiki PlainEditor JS pipeline

**2026-05-09** — content: drop CSSBackgroundGradients from the wikipages baseline

**2026-05-09** — db: track V019 migration that was hidden by the broken bin gitignore

**2026-05-09** — release-prep: SPA 401/403 handling + admin code-split + CHANGELOG + redeploy helper

**2026-05-09** — content(yaml): repair 66 pages with broken frontmatter + bulk drift

**2026-05-09** — admin-ui(proposals): add "Reject (no reason)" speed path

**2026-05-09** — content: bulk page maintenance — programming languages + assorted

**2026-05-09** — fit(deploy): clean up the seed-users warning + .env.example clarity

**2026-05-09** — content: bulk page maintenance — data eng / cybersec / cloud clusters

**2026-05-09** — docs(deploy): D13/D14 — data migration + external services

**2026-05-09** — fix(deploy): env-substitute DB password in Wikantik-context.xml.template (C10)

**2026-05-09** — fix(container): apply CookieProcessor sameSiteCookies=strict (A3)

**2026-05-09** — fix(container): align container deploy with bare-metal — drift fixes A1–A4

**2026-05-09** — content: bulk page maintenance — design/coins/immigration/food clusters

**2026-05-09** — fix(deploy): hands-off Tomcat upgrade flow + 2 newly-templated conf files

**2026-05-09** — content: bulk page maintenance — math/ops/PM cluster cleanup

**2026-05-09** — test(kg): IT for proposal pagination contract + judge guard tests

**2026-05-09** — fix(it): auto-discover all *IT.java in wikantik-it-test-rest failsafe

**2026-05-09** — admin-ui: server-driven pagination on AdminTable + ProposalReviewQueue

**2026-05-09** — fix(kg): pagination contract for /admin/knowledge-graph/proposals

**2026-05-09** — content: bulk page maintenance — title casing + frontmatter cleanup

**2026-05-09** — content: add LangChain / LangGraph trio (agentic-ai cluster)

**2026-05-09** — content(news): backfill May 9 commit log

**2026-05-09** — fix(auth): AdminAuthFilter passes SPA navigation through to the React shell

**2026-05-09** — admin-ui: fix Machine-rejected filter to actually load the rejected set

**2026-05-09** — admin-ui: timestamp on each judge-rationale line

**2026-05-09** — kg-judge: branch system prompt by proposal type + pre-flight validator

**2026-05-09** — admin-ui: typed proposal Details + clickable Machine reasoning

**2026-05-09** — content: wiki-page maintenance — frontmatter cleanup + News log + 2 new pages

**2026-05-09** — admin-ui: drop duplicate AdminTable CSS

**2026-05-09** — admin-ui: widen admin layout + allow cell wrap

**2026-05-09** — plan: AdminTable V1 close-out (Phase 2D) + V2 backlog

**2026-05-09** — admin-ui: KG Proposals bulk-action vertical (Phase 2C)

**2026-05-09** — admin-ui: fix Phase 2A AdminApiKeysPage bulk-revoke happy-path test

**2026-05-09** — admin-ui: Users bulk-action vertical (Phase 2B)

**2026-05-09** — admin-ui: API Keys bulk-revoke vertical (Phase 2A)

**2026-05-09** — admin-ui: AdminTable coverage tooling + branch-coverage fills

**2026-05-09** — admin-ui: AdminTable + selection-bar primitives (Ckpt 1)

**2026-05-09** — plan: AdminTable + bulk-action pattern (V1)

**2026-05-08** — phase 11.5: second static-analysis pass — real bugs + filter tightening + CPD top dedup

**2026-05-08** — fix: pre-release defect sweep — log-noise and observability gaps from manual smoke

**2026-05-08** — content: programming language evolution (1950-2026) & math-physics spine

**2026-05-08** — phase 11 ckpt 8: close-out — static analysis cleanup complete

**2026-05-08** — phase 11 ckpt 7: PMD lint sweep (continued)

**2026-05-08** — phase 11 ckpt 7: PMD lint sweep

**2026-05-08** — phase 11 ckpt 6: split top 3 god classes (HubOverview / DefaultKgService / BootstrapEntityExtractionIndexer)

**2026-05-08** — refactor(decomp): Phase 11 Ckpt 5 — bridges delegate to factory.create

**2026-05-08** — phase 11 ckpt 4: sweep PMD CloseResource sites

**2026-05-08** — phase 11 ckpt 3: fix priority-1 + SECURITY + MT_CORRECTNESS SpotBugs findings

**2026-05-08** — phase 11 ckpt 2: SpotBugs filter for record-exposes-component noise

**2026-05-08** — phase 11 ckpt 1: replace WikiEngine.setManager switch with class→writer map

**2026-05-08** — plan: phase 11 static-analysis cleanup — 8 checkpoints

**2026-05-08** — phase 10 ckpt C: close-out — all 10 phases complete

**2026-05-08** — phase 10 ckpt A2: delete WikiEngine.managers map; reads/writes go to typed fields

**2026-05-08** — fix: setManager must not eagerly rebuild typed snapshots during boot

**2026-05-08** — phase 10 ckpt A1: per-class typed backing fields on WikiEngine

**2026-05-08** — phase 10 ckpt B: decompose WikiContext (821 LOC) into scoped subobjects

**2026-05-08** — plan: phase 10 registry deletion + WikiContext decomposition

**2026-05-08** — phase 9 ckpt 6: close-out — partial completion, registry deletion deferred

**2026-05-08** — phase 9 ckpt 4d-iii: delete bridge registry-fallback paths

**2026-05-08** — phase 9 ckpt 4d-ii: plumb wiring-helper getManager reads as method parameters

**2026-05-08** — phase 9 ckpt 4d-i: typed registerX setters on WikiEngine; migrate setManager writes

**2026-05-08** — phase 9 ckpt 4c-fix: assign knowledgeSubsystem snapshot AFTER wiring helpers

**2026-05-08** — phase 9 ckpt 4c: slim WikiEngine.initialize by relocating wiring onto factories

**2026-05-08** — phase 9 ckpt 4b: delete getManager(Class) from Engine interface

**2026-05-08** — phase 9 ckpt 4a: bridges cast Engine to WikiEngine before getManager fallback

**2026-05-08** — phase 9 ckpt 5 (partial): add WikiSubsystemsTestFactory

**2026-05-08** — phase 9 ckpt 3.6: migrate Default*Manager cross-manager calls to bridges

**2026-05-08** — phase 9 ckpt 3.5: expand Core/Auth Services + migrate residual 14 callers

**2026-05-08** — phase 9 ckpt 3: bulk migrate ~11 wikantik-main callers to typed subsystem accessors

**2026-05-07** — phase 9 ckpt 2: place NewsPageGenerator + CachingManager on existing Services

**2026-05-07** — phase 9 ckpt 1: scaffold PageGraphSubsystem (Deps + Services + factory + bridge)

**2026-05-07** — plan: phase 9 WikiEngine simplification + registry deletion — 6 checkpoints

**2026-05-07** — phase 8 ckpt 2: close-out — Phase 8 complete, metrics captured

**2026-05-07** — phase 8 ckpt 1.5 follow-up: WikiEngine.setManager invalidates expanded subsystem snapshots

**2026-05-07** — phase 8 ckpt 1.5: expand Knowledge/Page Services + finish ~70 caller migration

**2026-05-07** — phase 8 ckpt 1: migrate 7 getManager callers in REST + knowledge

**2026-05-07** — plan: phase 8 ApiSubsystem cleanup — REST + MCP + tools + observability migration

**2026-05-07** — phase 7 ckpt 5: close-out — Phase 7 complete, metrics captured

**2026-05-07** — phase 7 ckpt 4: wire Lucene helpers + LuceneMlt into Services + Knowledge

**2026-05-07** — phase 7 ckpt 3: decompose LuceneSearchProvider 1251 -> 724 LOC facade + 3 helpers

**2026-05-07** — phase 7 ckpt 2: migrate 9 SearchManager getManager callers to SearchSubsystem

**2026-05-07** — phase 7 ckpt 1: scaffold SearchSubsystem (Deps + Services + factory + bridge)

**2026-05-07** — plan: phase 7 SearchSubsystem extraction + LuceneSearchProvider decomposition

**2026-05-07** — phase-6 ckpt-5: close-out — Phase 6 complete, metrics captured

**2026-05-07** — phase-6 ckpt-4: wire SpamFilter helpers into RenderingSubsystem.Services

**2026-05-07** — phase-6 ckpt-3: decompose SpamFilter (1003 -> 339 LOC facade)

**2026-05-07** — phase-6 ckpt-2: migrate ~29 rendering-manager getManager callers to RenderingSubsystem

**2026-05-07** — phase-6 ckpt-1: scaffold RenderingSubsystem (rendering + plugin + filter + diff)

**2026-05-07** — plan: phase 6 RenderingSubsystem extraction + SpamFilter decomposition

**2026-05-07** — phase-5 ckpt-7: close-out — Phase 5 complete, metrics captured

**2026-05-07** — phase-5 ckpt-6: KnowledgeSubsystem.Deps adopts PageSubsystem.Services

**2026-05-07** — phase-5 ckpt-5: migrate AttachmentManager + PageRenamer callers to PageSubsystem

**2026-05-07** — phase-5 ckpt-4: migrate ~91 PageManager getManager callers to PageSubsystem

**2026-05-06** — phase-5 ckpt-3: decompose DefaultPageManager (784 -> 297 LOC facade)

**2026-05-06** — phase-5 ckpt-2: lift PageProvider chain construction into PageSubsystemFactory

**2026-05-06** — phase-5 ckpt-1: scaffold PageSubsystem (PageManager + Attachment + Renamer + Save + Provider)

**2026-05-06** — plan: phase 5 PageSubsystem extraction + DefaultPageManager decomposition

**2026-05-06** — phase-4 ckpt-4-6: close-out — Phase 4 complete, metrics captured

**2026-05-06** — phase-4 ckpt-3: decompose SecurityVerifier (802 -> 278 LOC facade)

**2026-05-06** — phase-4 ckpt-2: migrate 34 auth getManager callers to AuthSubsystem

**2026-05-06** — phase-4 ckpt-1: scaffold AuthSubsystem (auth managers + apiKeys + verifier)

**2026-05-06** — plan: phase 4 AuthSubsystem extraction

**2026-05-06** — phase-3 ckpt-6: close-out — Phase 3 complete, metrics captured

**2026-05-06** — phase-3 ckpt-5: delete the JdbcKnowledgeRepository facade

**2026-05-06** — refactor(persistence): Ckpt 4 — migrate production consumers off JdbcKnowledgeRepository facade

**2026-05-06** — phase-3 ckpt-3: decompose JdbcKnowledgeRepository (1561 -> 318 LOC)

**2026-05-06** — phase-3 ckpt-2: route repository construction through PersistenceSubsystem

**2026-05-06** — phase-3 ckpt-1: scaffold PersistenceSubsystem (DataSource + 13 repos)

**2026-05-06** — plan: phase 3 PersistenceSubsystem extraction

**2026-05-06** — phase-2 ckpt-7: close-out — Phase 2 complete, metrics captured

**2026-05-06** — phase-2 ckpt-6: typed CoreSubsystem accessor + getManager fallback

**2026-05-06** — phase-2 ckpt-5: KnowledgeSubsystem.Deps adopts CoreSubsystem.Services

**2026-05-06** — phase-2 ckpt-4: migrate fireEvent callsites to typed WikiEventBus

**2026-05-06** — phase-2 ckpt-3: migrate engine.getWikiProperties() callers to CoreSubsystem

**2026-05-06** — phase-2 ckpt-2: migrate 22 leaf-manager getManager callers to CoreSubsystem

**2026-05-06** — phase-2 ckpt-1: scaffold CoreSubsystem (typed properties, event bus, leaf services)

**2026-05-06** — docs(decomposition): phase 2 implementation plan — CoreSubsystem

**2026-05-05** — chore(decomposition): phase 1 close-out — metrics + summary

**2026-05-05** — refactor(decomposition): delete KG bridge registrations (phase 1, ckpt 7)

**2026-05-05** — refactor(decomposition): migrate the last 8 KG getManager callers (phase 1, ckpts 4-6)

**2026-05-05** — refactor(decomposition): migrate wikantik-rest KG callers (phase 1, ckpt 3)

**2026-05-05** — test(decomposition): KnowledgeSubsystem isolation test (phase 1, ckpt 2)

**2026-05-05** — refactor(decomposition): KnowledgeSubsystem scaffolding (phase 1, ckpt 1)

**2026-05-05** — docs(decomposition): phase 1 implementation plan — KnowledgeSubsystem

**2026-05-05** — build(it): move cargo IT default port off 8080 -> 18080

**2026-05-05** — test(arch): adopt ArchUnit + capture decomposition baseline (phase 0)

**2026-05-05** — docs(decomposition): design spec for wikantik-main subsystem decomposition

**2026-05-05** — content(hubs): topical hub indexes + content expansions

**2026-05-05** — content(engineering): leadership, architecture practice, domain blueprints

**2026-05-05** — content(wikantik): platform architecture, development docs, conventions

**2026-05-05** — content(cs-math): foundational CS and math reference pages

**2026-05-05** — content(history): Berlin and Portuguese history clusters

**2026-05-05** — chore(main-page): regenerate Main.md to include WikantikPlatformHub + WikantikDevelopment

**2026-05-04** — fix(kg-extraction): swallow chunk_entity_mentions FK violation on stale chunk

**2026-05-04** — test: drive @Disabled and skip-by-assumption count to zero

**2026-05-04** — feat(kg-judge): per-proposal read-timeout tracking + admin review surface

**2026-05-04** — fix(test): re-enable ReleaseTest.testNewer6/testOlder6 at version 1.0.0

**2026-05-04** — refactor(kg-judge): self-healing on transient unavailability — no DB writes, no recovery script

**2026-05-04** — fix(test): add keep_alive arg to AdminKnowledgeResourceJudgeStatusTest

**2026-05-04** — chore(db-oneshot): script to reset judge timeout-abstains

**2026-05-04** — fix(kg-judge,extractor): set Ollama keep_alive=30m + raise judge timeout 30s -> 120s

**2026-05-03** — fix(web): drop hardcoded Secure flag on JSESSIONID cookie

**2026-05-03** — build: remove project.build.outputTimestamp pin

**2026-05-03** — log(admin-auth): WARN with session/principal context when 403 fires

**2026-05-03** — fix(kg-materialize): null-guard policy-excluded nodes; demote CSRF rejections to WARN

**2026-05-03** — refactor: collapse defensive-try duplication, dedupe KG fetch path, fix FQN imports

**2026-05-03** — feat(mcp): include latestContent + currentVersion in update_page hash-mismatch response

**2026-05-03** — security(mcp): strict page-name validator + YAML loader hardening

**2026-05-03** — log(rename_page): WikiException is client-class — WARN without stack trace

**2026-05-03** — log(search): demote query-parse failures from ERROR to WARN; drop stack trace

**2026-05-03** — config(kg-judge): default endpoint/model to inference.jakefear.com / gemma4-assist:latest

**2026-05-03** — fix(versioning): skip OLD/ rename when source has no version history

**2026-05-03** — fix(shutdown): WikiBackgroundThread breaks out on interrupt; delete buggy shutdown test

**2026-05-03** — build: make JaCoCo coverage opt-in via -Pcoverage profile

**2026-05-03** — fix(shutdown): clean webapp shutdown — close runners, join background threads, clear ThreadLocals

**2026-05-03** — docs(wikantik-pages): global de-slopping and graph connectivity improvements

**2026-05-03** — fix(structural-index): handle slug/canonical_id mismatch gracefully + reconcile data

**2026-05-03** — fix(kg-judge): extract leading JSON object from LLM responses with tool-call/prose tails

**2026-05-03** — fix(kg-judge): also catch PoolClosedException at runner outer scope

**2026-05-03** — docs(kg-view): implementation plan for the Knowledge Graph viewer

**2026-05-03** — fix(kg-it): tolerate SPA basename + empty KG DB in viewer IT

**2026-05-03** — test(kg-view): Selenide IT for /knowledge-graph route + tier dropdown

**2026-05-03** — feat(kg-view): large-graph node-count warning (Phase 6)

**2026-05-03** — feat(kg-view): tier badge gold border for human-validated nodes (Phase 5)

**2026-05-02** — feat(kg-view): provenance and status styling (Phase 4)

**2026-05-02** — feat(kg-view): node-type colour coding + KG drawer (Phase 3)

**2026-05-02** — feat(kg-view): tier dropdown + URL sync + legend tier counts (Phase 2)

**2026-05-02** — feat(kg-api): add tier to SnapshotNode + populate from KgNode

**2026-05-02** — fix(kg-judge): downgrade pool-closed shutdown noise to DEBUG via PoolClosedException

**2026-05-02** — chore(sidebar): drop redundant 'Page Graph vs Knowledge Graph' link

**2026-05-02** — feat(kg-view): /knowledge-graph SPA route mirroring /page-graph (Phase 1)

**2026-05-02** — feat(kg-judge): live progress + status endpoint; fix endpoint config fallback

**2026-05-02** — chore(docs): backfill News.md + ignore reports/ + RESUME.md

**2026-05-02** — fix(kg-it): correct snapshot URL to /api/knowledge/graph

**2026-05-02** — chore(kg-mcp): update SearchKnowledgeToolTest stubs for 4-arg searchKnowledge

**2026-05-02** — chore(kg-mcp): update TraverseToolTest stubs for 4-arg traverseByCoMention

**2026-05-02** — test(kg-it): end-to-end smoke for staged validation lifecycle

**2026-05-02** — feat(kg-judge): bin/kg-judge.sh CLI + wikantik.properties config

**2026-05-02** — feat(kg-frontend): machine verdict column + Judge Now action

**2026-05-02** — chore(kg-mcp): stub-fix T3 record-widening cascade in wikantik-knowledge tests

**2026-05-02** — feat(kg-mcp): min_tier on search_knowledge + traverse_by_co_mention

**2026-05-02** — feat(kg-rest): admin judge run + per-proposal judge + reviews + filters

**2026-05-02** — feat(kg-rest): min_tier query param on /api/knowledge-graph/snapshot

**2026-05-02** — feat(kg-judge): wire judge service + materialisation + runner

**2026-05-02** — feat(kg-svc): judgeNow synchronous single-proposal trigger

**2026-05-02** — feat(kg-svc): approveProposal/rejectProposal materialise + audit

**2026-05-02** — feat(kg-svc): tier-aware reads with default Tier.MACHINE

**2026-05-02** — feat(kg-judge): JudgeRunner background task with max_attempts cap

**2026-05-02** — feat(kg-judge): DefaultKgProposalJudgeService Ollama implementation

**2026-05-02** — feat(kg-judge): KgProposalJudgeService interface + KgJudgeConfig

**2026-05-02** — feat(kg-judge): KgMaterializationService.promoteToHuman + retract

**2026-05-02** — feat(kg-judge): KgMaterializationService.materializeMachine + provenance-aware upserts

**2026-05-02** — feat(kg-repo): applyMachineVerdict + applyHumanVerdict; clearAll truncates reviews

**2026-05-02** — feat(kg-repo): recordReview + listReviews + getProposalsForJudging

**2026-05-02** — feat(kg-repo): tier-aware searchNodes overload

**2026-05-02** — chore(kg): stub-fix T3 record-widening cascade in test + main

**2026-05-02** — feat(kg-repo): tier-aware getAllNodes/getAllEdges + row-mapper updates

**2026-05-02** — feat(kg-api): widen KgProposal/KgNode/KgEdge with tier + provenance

**2026-05-02** — feat(kg-api): Tier, JudgeVerdict, KgProposalReview records

**2026-05-02** — feat(kg): V024 staged-validation schema (tier + audit)

**2026-05-02** — docs(plan): KG staged validation implementation plan

**2026-05-02** — docs(spec): default min_tier to 'machine' on KG read paths

**2026-05-02** — docs(spec): KG staged validation design (machine + human tiers)

**2026-05-02** — test(it,pagegraph): assert /api/page-graph/snapshot renders seeded wikilinks

**2026-05-02** — feat(frontend,pagegraph): point /page-graph view at the wikilink endpoint

**2026-05-02** — feat(pagegraph): serve Page Graph snapshot from wikilinks, not KG entities

**2026-05-02** — fix(frontend): App.jsx isGraphRoute check now matches /page-graph

**2026-05-02** — fix(spa): register /page-graph filter mapping; update Selenide ITs

**2026-05-02** — docs: add PageGraphVsKnowledgeGraph explainer; link from sidebar and views

**2026-05-02** — docs: qualify 'graph' refs in HybridRetrieval and AgentGradeContentDesign

**2026-05-02** — docs(StructuralSpineDesign): rewrite for narrower spine; relations: grammar removed

**2026-05-02** — docs(README): add Page Graph vs Knowledge Graph note; sweep ambiguous refs

**2026-05-02** — docs(CLAUDE.md): add Page Graph vs Knowledge Graph section; sweep ambiguous 'graph' refs

**2026-05-02** — knowledge-mcp: qualify tool descriptions to say 'Knowledge Graph'

**2026-05-02** — admin-mcp: qualify tool descriptions to say 'Page Graph'

**2026-05-02** — rename: /admin/knowledge backend mounts + client paths → /admin/knowledge-graph

**2026-05-02** — frontend(admin): nav 'Knowledge' → 'Knowledge Graph'; route /admin/knowledge → /admin/knowledge-graph

**2026-05-02** — frontend: sidebar 'Knowledge Graph' (page-link view) → 'Page Graph' at /page-graph

**2026-05-02** — frontend: rename components/graph → components/pagegraph; GraphView → PageGraphView

**2026-05-02** — rest: move /admin/structural-conflicts → /admin/page-graph/conflicts

**2026-05-02** — rest: rename /graph → /page-graph SPA route + 301 redirect

**2026-05-02** — refactor: rename com.wikantik.api.structure → com.wikantik.api.pagegraph

**2026-05-02** — refactor: move com.wikantik.knowledge.structure → com.wikantik.pagegraph.spine

**2026-05-02** — refactor: move com.wikantik.references → com.wikantik.pagegraph.references

**2026-05-02** — db: V023 drop page_relations table (relations: mechanism removed)

**2026-05-02** — frontend(graph): collapse typed-edge palette to single page-link color

**2026-05-02** — api: delete relation grammar types

**2026-05-02** — spine: drop relation collections from StructuralProjection

**2026-05-02** — test: update GetPageForAgentToolTest for new ForAgentProjection signature

**2026-05-02** — spine: purge remaining relations handling from DefaultStructuralIndexService

**2026-05-02** — spine: drop relation methods from StructuralIndexService

**2026-05-02** — test: update PageForAgentResourceTest for new ForAgentProjection signature

**2026-05-02** — for-agent: drop outgoing/incomingRelations fields (relations: deprecated)

**2026-05-02** — spine: drop relations: validation branch from StructuralSpinePageFilter

**2026-05-02** — docs: remove stale PageRelationsResource reference from API client comment

**2026-05-02** — rest: remove PageRelationsResource (frontmatter relations: deprecated)

**2026-05-02** — knowledge-mcp: scrub traverse_relations from instructions and tool_hints

**2026-05-02** — knowledge-mcp: remove TraverseRelationsTool (frontmatter relations: deprecated)

**2026-05-02** — docs: strip relations: examples from McpIntegration and TextFormattingRules

**2026-05-02** — docs(spec/plan): correct Task 1 scope — body examples, not frontmatter

**2026-05-02** — docs(plan): page graph vs knowledge graph implementation plan

**2026-05-02** — docs(spec): page graph = wikilinks only; remove frontmatter relations:

**2026-05-02** — docs(spec): page graph vs knowledge graph separation design

**2026-05-02** — admin: widen layout 20% and stop KG-Policy tab + Clear button from clipping

**2026-05-02** — extraction: flip default --judge-model from qwen3.5:9b to gemma4-assist

**2026-05-02** — docs: add RunningTheJudgeExperimentHarness runbook page

**2026-05-01** — cli: add JudgeExperimentCli + bin/kg-judge-experiment.sh

**2026-05-01** — extraction: add ClaudeProposalJudge (gated by allow_claude property)

**2026-05-01** — extraction: add OllamaProposalJudge (opt-in; fail-open)

**2026-05-01** — kg: model-key the kg_node_embeddings cache (V022) + DELETE-based wipe

**2026-05-01** — extract-cli: fix Instant serialization + collapse node embeddings to qwen3

**2026-05-01** — docs(claude.md): document the new extractor + wipe-then-fill workflow

**2026-05-01** — cli: split chunker-stats into ChunkerStatsCli + bin/kg-chunker-stats.sh

**2026-05-01** — bin: update kg-extract.sh banner and flags for page-extraction pipeline

**2026-05-01** — cli: rewire BootstrapExtractionCli for the page-extraction pipeline

**2026-05-01** — test: extend AdminExtractionResourceTest Status fixtures for the new fields

**2026-05-01** — test: PG-gated end-to-end + idempotency tests for the page extraction pipeline

**2026-05-01** — extraction: fix support_count=1 hardcoded on first kg_proposals insert

**2026-05-01** — extraction: refactor BootstrapEntityExtractionIndexer to drive page pipeline

**2026-05-01** — extraction: add NoOpProposalJudge (production default)

**2026-05-01** — extraction: add MentionAttributor (deterministic whole-word match)

**2026-05-01** — extraction: add OllamaPageExtractor (per-page, format=json)

**2026-05-01** — embedding: add KgNodeEmbeddingService with content-hash skip

**2026-05-01** — embedding: add KgNodeEmbeddingRepository for kg_node_embeddings cache

**2026-05-01** — docs(news): backfill 2026-04-30 entries for spine + MCP repair work

**2026-05-01** — docs(wiki): normalize ReformationEraInBerlin frontmatter

**2026-05-01** — content: warn on classpath system pages unreachable via PageManager

**2026-05-01** — extraction: add ProposalUpserter with JSONB support-merge upsert

**2026-05-01** — extraction: add ProposalConsolidator with display-name vote + signature dedup

**2026-05-01** — extraction: add PageExtractionResponseParser with banned-name + cap + grounding

**2026-05-01** — extraction: add PageExtractionPromptBuilder with frozen system prompt

**2026-05-01** — extraction: add EvidenceGroundingVerifier (substring + len + NFC check)

**2026-05-01** — api: add PageExtractor + ProposalJudge interfaces with JudgeContext

**2026-05-01** — api: add Page + ExtractedEntity/Relation + PageExtractionResult

**2026-05-01** — api(NodeSignature): preserve identifier punctuation; trim after collapse

**2026-05-01** — api: add ConsolidatedProposal + SupportEvidence + sealed Verdict

**2026-05-01** — api: add NodeSignature + EdgeSignature with NFC + predicate-synonym normalization

**2026-05-01** — db(V021): add kg_node_embeddings cache table

**2026-05-01** — db(V020): add kg_proposals signature column + partial unique index

**2026-05-01** — docs(plans): add KG extraction redesign implementation plan

**2026-05-01** — docs(specs): add KG extraction redesign spec


---

## April 2026

**2026-04-30** — content: backfill canonical_id on 57 articles missing it

**2026-04-30** — content: repair MCP write damage on five pages + DB cleanup

**2026-04-30** — spine+mcp: preserve canonical_id across MCP write paths

**2026-04-30** — docs(wiki): fix unquoted colons in titles and finalize structural spine injection

**2026-04-30** — docs(superpowers): archive completed plan + spec docs

**2026-04-30** — docs(gemini): add article-authoring + structural-spine section

**2026-04-30** — rest(json): emit Date as UTC ISO-8601 regardless of JVM default timezone

**2026-04-30** — kg(cleanup): drop retired ComplEx + TF-IDF embedding tables

**2026-04-30** — mcp(frontmatter): two-layer normalization + validation guard

**2026-04-30** — mcp(admin): sync instructions, system-page write protection, drift guards

**2026-04-30** — docs(wiki): improve article metadata and migrate coin guide to markdown

**2026-04-30** — docs(wiki): repair NameOfArticle.md and update internal links

**2026-04-30** — docs(wiki): hub-naming pass + article quality cleanup

**2026-04-29** — build(deps): docker-maven-plugin 0.46.0 → 0.48.1 for Docker 29 API compatibility

**2026-04-28** — config(template): document LLM/embedding/extractor keys

**2026-04-28** — docs(news): backfill April 2026 development log entries

**2026-04-28** — admin(extract): wire ExtractionTab into AdminKnowledgePage

**2026-04-28** — admin(extract): Vitest coverage for ExtractionTab

**2026-04-28** — admin(extract): ExtractionTab UI — status tracker + start/force/cancel

**2026-04-28** — admin(extract): add knowledge.{get,start,cancel}Extraction REST clients

**2026-04-28** — admin(extract): surface configured extractor backend in status payload

**2026-04-28** — docs(plan): extraction admin UI implementation plan

**2026-04-28** — docs(spec): extraction admin UI — design

**2026-04-28** — docs(news): backfill April 2026 development log

**2026-04-28** — docs(wiki): repair bare [PageName] references to valid [PageName]() links

**2026-04-28** — fix(test): scope KG policy assertions past filter-option / page-count collisions

**2026-04-28** — admin(kgpolicy): hide reconciliation panel once nothing is RUNNING/QUEUED

**2026-04-28** — admin(rebuild): EMBEDDING phase + helper text + readable destructive buttons

**2026-04-28** — build: force-add bin/ files the gitignore was silently swallowing

**2026-04-28** — chore: gitignore .keys + spec doc whitespace tidy

**2026-04-28** — fix(it): graphView anonymous test matches D27 public-graph contract

**2026-04-28** — fix(mcp): set structuredContent on responses (SDK 1.1.1 strict)

**2026-04-27** — docs(generated): regenerate Main.md with KgInclusionPolicy entry

**2026-04-27** — kgpolicy(it): admin REST smoke test

**2026-04-27** — docs(CLAUDE.md): note KG inclusion policy

**2026-04-27** — docs: link KgInclusionPolicy from KG admin guide; News entry

**2026-04-27** — docs: KgInclusionPolicy admin runbook

**2026-04-27** — kgpolicy(frontend): /admin/kg-policy routes + nav link

**2026-04-27** — kgpolicy(frontend): explain | pending | bootstrap views

**2026-04-27** — kgpolicy(frontend): AdminKgPolicyPage dashboard component

**2026-04-27** — kgpolicy(frontend): admin.kgPolicy API client methods

**2026-04-27** — kgpolicy: configuration knobs in wikantik.properties

**2026-04-27** — kgpolicy(bin): kg-policy.sh launcher matching kg-extract.sh pattern

**2026-04-27** — kgpolicy(cli): KgPolicyCli read + destructive subcommands

**2026-04-27** — kgpolicy(rest): map AdminKgPolicyResource at /admin/kg-policy/*

**2026-04-27** — kgpolicy(rest): PUT/DELETE/POST /admin/kg-policy endpoints

**2026-04-27** — kgpolicy(rest): GET endpoints for /admin/kg-policy

**2026-04-27** — kgpolicy: backfill system-page exclusions at startup

**2026-04-27** — kgpolicy: integration test for kg_excluded_pages read filter

**2026-04-27** — kgpolicy: filter excluded pages from NodeMentionSimilarity

**2026-04-27** — kgpolicy: filter excluded pages from MentionIndex

**2026-04-27** — kgpolicy: filter excluded pages from QueryEntityResolver

**2026-04-27** — kgpolicy: filter excluded pages from InMemoryGraphNeighborIndex

**2026-04-27** — kgpolicy: filter excluded pages from JdbcKnowledgeRepository

**2026-04-27** — kgpolicy: KgInclusionFilter SQL fragment helper

**2026-04-27** — kgpolicy: AsyncEntityExtractionListener filters excluded chunks; thread repo into pipeline

**2026-04-27** — kgpolicy: BootstrapEntityExtractionIndexer skips excluded pages

**2026-04-27** — kgpolicy: wire DefaultKgInclusionPolicy + ReconciliationJobRunner into WikiEngine

**2026-04-27** — kgpolicy: ReconciliationJobRunner with per-cluster status tracking

**2026-04-27** — kgpolicy: project kg_include into PageDescriptor; reader impl

**2026-04-27** — kgpolicy: DefaultKgInclusionPolicy with caching + reason precedence

**2026-04-27** — kgpolicy: StructuralSpinePageFilter validates kg_include frontmatter

**2026-04-27** — kgpolicy: KgExcludedPagesRepository with reason-precedence upsert

**2026-04-27** — kgpolicy: KgClusterPolicyRepository (policy + audit DAO)

**2026-04-27** — kgpolicy: KgInclusionPolicy interface

**2026-04-27** — kgpolicy: ClusterActionTest + clarify PolicyAuditEntry.newAction

**2026-04-27** — kgpolicy: API enums and record types

**2026-04-27** — db(V018): kg_cluster_policy, kg_policy_audit, kg_excluded_pages

**2026-04-27** — docs(plan): KG inclusion policy implementation plan

**2026-04-27** — docs(spec): KG inclusion/exclusion policy design

**2026-04-27** — content: collapse singleton/typo clusters — 42 clean clusters

**2026-04-27** — content: assign cluster to all 408 unclustered pages — 100% coverage

**2026-04-27** — chore: normalize CRLF → LF across all tracked text files

**2026-04-27** — build: drop defectimages/ — keep triage screenshots out of git

**2026-04-27** — build: gitignore Python caches, .claude state, RESUME, temp/, org/

**2026-04-27** — ops: defect screenshots + dev helper scripts

**2026-04-27** — content: bulk wiki rewrite — SE corpus, hubs, retirement, finance, agentic skills

**2026-04-27** — content: rewrite 47 science/math/ML/CS orphan pages as substantive content

**2026-04-26** — docs(rebuild): cover prefilter, chunker tuning, stats modes, lineage

**2026-04-26** — feat(scripts,docs): --purge-kg full-destructive option for kg-rebuild

**2026-04-26** — feat(scripts): periodic progress lines during long rebuild phases

**2026-04-26** — rename(scripts,docs): runextractor → kg-extract, full-rebuild → kg-rebuild

**2026-04-26** — feat(scripts,docs): full-rebuild orchestration + operator runbook

**2026-04-26** — feat(extraction): tag chunk_entity_mentions.extractor with Ollama model name

**2026-04-26** — feat(extract-cli): --max-pages cap for end-to-end smoke tests

**2026-04-26** — feat(extract-cli): --chunker-stats-only for in-memory chunker sweeps

**2026-04-26** — feat(extract-cli): --stats-only mode for prefilter sizing without LLM calls

**2026-04-26** — feat(extraction): broaden proper-noun regex + add too-short prefilter rule

**2026-04-26** — feat(scripts): polish runextractor banner and exit-summary feedback

**2026-04-26** — revert(scripts): drop bun/TypeScript runextractor — bash version is the one we use

**2026-04-26** — feat(extraction): raise CONCURRENCY_MAX from 4 to 10 for small-model runs

**2026-04-26** — refactor(frontend,api): auto-unwrap single-key {data:…} envelopes in request()

**2026-04-26** — refactor(frontend,admin): extract AdminPage shell for loading/error short-circuit

**2026-04-26** — fix(admin-mcp): anchor WikiEventSubscriptionBridge + register on FilterManager

**2026-04-26** — perf(structure): make onPageSaved/onPageDeleted properly incremental

**2026-04-26** — docs(gemini): retarget GEMINI.md at tactical refactoring + minor features

**2026-04-26** — refactor(frontend,test): post-review cleanups for retrieval-quality page

**2026-04-26** — feat(scripts): bun version of runextractor for the entity-extractor CLI

**2026-04-26** — docs(wikantik-pages): clustered + curated content refresh

**2026-04-26** — fix(knowledge,structure): keep StructuralIndexEventListener strongly referenced

**2026-04-26** — fix(knowledge,structure): register StructuralIndexEventListener on the right event source

**2026-04-26** — feat(frontend,admin): wire AdminRetrievalQualityPage route + nav link

**2026-04-26** — feat(frontend,admin): AdminRetrievalQualityPage (Phase 5b)

**2026-04-26** — feat(frontend,admin): dependency-free Sparkline SVG component

**2026-04-26** — feat(frontend,api): admin client for retrieval-quality runs

**2026-04-26** — docs(plans,cookbook): post-Phase-6 follow-up plan + Editorial/ongoing

**2026-04-26** — fix(extract-cli): reject prefilter sub-flags without --prefilter master switch

**2026-04-25** — test(rest): extend AdminExtractionResourceTest Status fixtures with skip fields

**2026-04-25** — refactor(engine): hoist bootstrap prefilter to a local for readability

**2026-04-25** — feat(extraction): expose prefilter via CLI flags and engine wiring

**2026-04-25** — fix(knowledge,test): SearchKnowledgeTool wire-JSON smoke uses Gson, not Jackson

**2026-04-25** — test(extraction): pin skip_no_proper_noun explicitly in prefilter listener test

**2026-04-25** — feat(extraction): apply prefilter inside save-time listener loop

**2026-04-25** — docs(agent-grade): retrospective Phase 6 plan + CLAUDE.md status flip

**2026-04-25** — fix(extract-cli): include skipped chunks in progress percentage

**2026-04-25** — feat(extraction): wire prefilter into bootstrap indexer + CLI status

**2026-04-25** — feat(tools): AG-Phase 6 worked examples on the OpenAPI tool server

**2026-04-25** — feat(knowledge-mcp): AG-Phase 6 worked examples on every knowledge tool

**2026-04-25** — docs(extraction): fix stale regex reference in prefilter test comment

**2026-04-25** — docs(extraction): correct prefilter proper-noun regex in spec

**2026-04-25** — feat(extraction): ChunkExtractionPrefilter with code/proper-noun predicates

**2026-04-25** — feat(admin-mcp): AG-Phase 6 worked examples on every admin tool

**2026-04-25** — feat(extraction): add prefilter flags to EntityExtractorConfig

**2026-04-25** — docs+feat(admin-mcp): AG-Phase 6 prospective plan + GetBacklinksTool examples

**2026-04-25** — docs(extraction): chunk pre-filter implementation plan

**2026-04-25** — docs(extraction): chunk pre-filter design spec

**2026-04-25** — docs: AG-Phase 5 retrospective + CLAUDE.md status update

**2026-04-25** — feat(db): V017 seed core-agent-queries retrieval query set

**2026-04-25** — test(eval): RetrievalQualitySmokeTest pre-merge gate

**2026-04-25** — feat(engine): wire RetrievalQualityRunner into initKnowledgeGraph

**2026-04-25** — feat(rest): /admin/retrieval-quality endpoint

**2026-04-25** — feat(knowledge,eval): DefaultRetrievalQualityRunner

**2026-04-25** — feat(knowledge,eval): RetrievalQualityMetrics

**2026-04-25** — feat(knowledge,eval): RetrievalQualityDao

**2026-04-25** — feat(knowledge,eval): RetrievalMetricsCalculator (nDCG/Recall/MRR)

**2026-04-25** — feat(api,eval): RetrievalMode, RetrievalRunResult, RetrievalQualityRunner

**2026-04-25** — feat(db): V016 retrieval-quality tables

**2026-04-25** — docs(main): surface AgentCookbook on front page + quote summary

**2026-04-25** — docs(cookbook): seed 15 agent-cookbook runbooks + AgentCookbook hub

**2026-04-25** — fix(rest): JSON error pages for container-level errors (D10)

**2026-04-25** — fix(structural-index): persist canonical_id of just-saved page (D6)

**2026-04-25** — chore(db,docs): D17,D28 — dedup user_profiles migration + update tool counts in CLAUDE.md

**2026-04-25** — fix(knowledge-mcp): D13,D29,D30 — accept dual arg names, document scope, fix find_similar message

**2026-04-25** — fix(admin-mcp): D11,D12,D16,D18 — sanitize errors, log instructions drift, filter broken links, validate baseURL

**2026-04-25** — fix(rest): D5,D7,D8,D19-D27,D31 — input validation, sanitized errors, structured diff, public KG

**2026-04-25** — fix(http): D3 — CsrfProtectionFilter exempts Bearer-authenticated requests

**2026-04-25** — fix(auth,knowledge,providers): D2,D4,D9,D27 — login throttle reset, chunk upsert, attachment rename, public KG

**2026-04-25** — fix(observability): D1 — searchIndex health probes PageManager, not unregistered PageProvider

**2026-04-25** — docs(plans,agent): Phase 3 retrospective ledger + CLAUDE.md update

**2026-04-25** — feat(knowledge,agent): /for-agent populates runbook for type:runbook pages

**2026-04-25** — feat(engine,agent): register RunbookValidationPageFilter at -1003

**2026-04-25** — feat(knowledge,agent): RunbookValidationPageFilter for save-time enforcement

**2026-04-25** — feat(knowledge,agent): FrontmatterRunbookValidator (Phase 3 schema)

**2026-04-25** — feat(api,agent): RunbookBlock value type for Phase 3

**2026-04-25** — docs(plans,agent): Phase 2 retrospective ledger + CLAUDE.md update

**2026-04-25** — feat(knowledge-mcp,agent): get_page_for_agent MCP tool on /knowledge-mcp

**2026-04-25** — feat(rest,agent): /api/pages/for-agent/{canonical_id} endpoint

**2026-04-25** — feat(knowledge,agent): DefaultForAgentProjectionService + WikiEngine wiring

**2026-04-25** — feat(cache,agent): wikantik.forAgentCache alias + ForAgentMetrics histogram

**2026-04-25** — feat(knowledge,agent): McpToolHintsResolver for /for-agent projection

**2026-04-25** — feat(knowledge,agent): RecentChangesAdapter for /for-agent projection

**2026-04-25** — feat(knowledge,agent): KeyFactsExtractor with frontmatter + body heuristic

**2026-04-25** — feat(knowledge,agent): HeadingsOutlineExtractor for /for-agent projection

**2026-04-25** — feat(api,agent): ForAgentProjection record + service interface

**2026-04-25** — fix(it-tests): green up integration test suite

**2026-04-25** — docs: Agent-Grade Content Phase 1 plan + CLAUDE.md note

**2026-04-25** — feat(rest): /admin/verification endpoint

**2026-04-25** — feat(admin-mcp): mark_page_verified tool

**2026-04-25** — feat(knowledge,engine): rebuild() persists verification with computed confidence

**2026-04-25** — feat(api,knowledge): verificationOf() on StructuralIndexService

**2026-04-25** — feat(knowledge): ConfidenceComputer rule engine

**2026-04-25** — feat(knowledge): TrustedAuthorsDao with cached read path

**2026-04-25** — feat(knowledge): PageVerificationDao with H2-tested CRUD

**2026-04-25** — feat(api): verification value types (Confidence, Audience, Verification)

**2026-04-25** — db: V014 migration adds page_verification and trusted_authors

**2026-04-25** — docs: Phase 4 plan + CLAUDE.md note for save-time structural enforcement

**2026-04-25** — feat(rest): /admin/structural-conflicts endpoint

**2026-04-25** — feat(engine): wire StructuralSpinePageFilter into the filter chain

**2026-04-25** — feat(knowledge): StructuralSpinePageFilter for save-time enforcement

**2026-04-25** — feat(api,knowledge): surface structural conflicts from rebuild

**2026-04-25** — feat(admin-mcp): re-introduce read_page + delete_pages on /wikantik-admin-mcp

**2026-04-25** — docs(plan): Structural Spine Phase 3 plan + regression gate + CLAUDE.md note

**2026-04-25** — data: migrate Main.md to generated form (Phase 3 of structural spine)

**2026-04-25** — feat(extract-cli): GenerateMainPageCli (--check / --write)

**2026-04-25** — feat(extract-cli): Main.md Mustache template + MainPageRenderer

**2026-04-25** — feat(extract-cli): MainPageDataLoader (offline pins+frontmatter join)

**2026-04-25** — feat(extract-cli): PinsParser for Main.pins.yaml

**2026-04-25** — feat(extract-cli): PinsConfig + MainPageData value types

**2026-04-25** — build: add jmustache 1.16 for Main.md generator (Phase 3 of structural spine)

**2026-04-25** — docs(plan): Structural Spine Phase 2 implementation record

**2026-04-25** — test(it): /api/relations/{id} integration coverage

**2026-04-25** — feat(knowledge-mcp): register traverse_relations tool

**2026-04-25** — feat(knowledge-mcp): traverse_relations tool

**2026-04-25** — feat(rest): /api/relations/{canonical_id} endpoint

**2026-04-25** — feat(knowledge): rebuild() parses + persists relations from frontmatter

**2026-04-25** — feat(knowledge): FrontmatterRelationValidator (warn-only for Phase 2)

**2026-04-25** — feat(knowledge): relation graph in StructuralProjection + service surface

**2026-04-25** — feat(knowledge): PageRelationsDao for typed page-relation edges

**2026-04-25** — feat(api): relation value types for structural-spine Phase 2

**2026-04-25** — data: restore canonical_id on 46 pages rewritten by external process

**2026-04-24** — test(it): structural-spine IT coverage in RestApiIT

**2026-04-24** — feat(rest): /api/health/structural-index endpoint

**2026-04-24** — feat(observability): Prometheus metrics for the structural index

**2026-04-24** — feat(knowledge-mcp): register structural-spine tools in KnowledgeMcpInitializer

**2026-04-24** — feat(knowledge-mcp): list_clusters, list_tags, list_pages_by_filter, get_page_by_id

**2026-04-24** — feat(rest): PageByIdResource for canonical_id lookups

**2026-04-24** — feat(rest): StructureResource at /api/structure/*

**2026-04-24** — data: backfill canonical_id ULID into all 934 wiki pages (Phase 1 of structural spine)

**2026-04-24** — fix(extract-cli): use FrontmatterParser for canonical_id presence check

**2026-04-24** — feat(extract-cli): AssignCanonicalIdsCli — ULID backfill tool

**2026-04-24** — feat(engine): register StructuralIndexService and move structure package to wikantik-main

**2026-04-24** — feat(knowledge): StructuralIndexEventListener wires WikiPageEvents to rebuild

**2026-04-24** — feat(knowledge): DefaultStructuralIndexService with observe-only rebuild

**2026-04-24** — feat(knowledge): StructuralProjection immutable snapshot + builder

**2026-04-24** — feat(knowledge): PageCanonicalIdsDao for canonical_id + slug history

**2026-04-24** — feat(api): StructuralIndexService interface

**2026-04-24** — feat(api): structural-index value types

**2026-04-24** — db: V013 migration adds page_canonical_ids, page_slug_history, page_relations

**2026-04-24** — build: use ${ulid-creator.version} property in wikantik-bom BOM entry

**2026-04-24** — build: add ulid-creator 5.2.3 for canonical page IDs

**2026-04-24** — docs(plan): Structural Spine Phase 1 implementation plan

**2026-04-24** — docs(design): StructuralSpineDesign and AgentGradeContentDesign

**2026-04-24** — docs: reconcile CLAUDE.md / README.md / GEMINI.md / connected docs with actual module layout and MCP surface

**2026-04-24** — test(rest): lift wikantik-rest unit coverage from 67.7% to 80.7%

**2026-04-24** — fix(mcp): repair IT suite after MCP surface rename + GraphProjector retirement

**2026-04-24** — refactor(mcp): dedupe MCP initializer bootstrap + arg helpers, log all caught exceptions

**2026-04-24** — fix(knowledge): relatedPages populated from chunk_entity_mentions

**2026-04-24** — fix(mcp): close /knowledge-mcp auth gap, unblock admin-mcp, raise rate limits

**2026-04-24** — refactor(knowledge): extract buildRetrievedPage + split chunk shaping

**2026-04-24** — chore(spec): mark cycle 6 complete — GraphProjector retired; redesign done

**2026-04-24** — feat(db): V012 — purge links_to edges written by retired GraphProjector

**2026-04-24** — docs(knowledge): drop stale GraphProjector references from javadoc/tests

**2026-04-24** — chore(knowledge): delete FrontmatterRelationshipDetector (unused after GraphProjector retirement)

**2026-04-24** — chore(knowledge): delete GraphProjector + its test

**2026-04-24** — chore(admin): remove /admin/knowledge/project-all endpoint (GraphProjector retired)

**2026-04-24** — refactor(knowledge): unwire GraphProjector from WikiEngine + factory

**2026-04-24** — docs(plan): cycle 6 plan — GraphProjector retirement (final cycle)

**2026-04-24** — chore(spec): mark cycle 5 complete — tool-server shape parity

**2026-04-24** — feat(tools): OpenAPI schema — contributingChunks + relatedPages on SearchResult

**2026-04-24** — feat(tools): search_wiki emits contributingChunks + relatedPages arrays

**2026-04-24** — docs(plan): cycle 5 plan — tool-server shape parity with MCP

**2026-04-24** — chore(spec): mark cycle 4 complete — admin-mcp rename + writes

**2026-04-24** — chore(admin-mcp): delete 9 absorbed tools — capabilities moved to /knowledge-mcp

**2026-04-24** — refactor(admin-mcp): register write_pages/update_page, drop absorbed tools

**2026-04-24** — feat(admin-mcp): add update_page tool with optimistic locking

**2026-04-24** — feat(admin-mcp): add write_pages batch-create tool

**2026-04-24** — refactor(admin-mcp): move endpoint /mcp to /wikantik-admin-mcp

**2026-04-24** — refactor(build): rename wikantik-mcp module to wikantik-admin-mcp

**2026-04-24** — docs(plan): cycle 4 plan — admin-mcp rename + write tools

**2026-04-24** — chore(spec): mark cycle 3 complete — KG tools on mention-based graph

**2026-04-24** — feat(knowledge-mcp): wire MentionIndex into rebacked KG tools

**2026-04-24** — feat(knowledge-mcp): discover_schema reports mention-coverage stats

**2026-04-24** — feat(knowledge-mcp): traverse tool walks co-mention graph

**2026-04-24** — feat(knowledge-mcp): filter query_nodes to mention-covered nodes

**2026-04-24** — feat(knowledge-mcp): filter search_knowledge to mention-covered nodes

**2026-04-24** — feat(knowledge): add traverseByCoMention to KnowledgeGraphService

**2026-04-24** — feat(knowledge): add MentionIndex read-only utility

**2026-04-24** — docs(plan): cycle 3 plan — reback KG tools onto mention-based graph

**2026-04-24** — chore(spec): mark cycle 2 complete — /knowledge-mcp 4 new tools

**2026-04-24** — feat(knowledge-mcp): register 4 new context tools alongside KG tools

**2026-04-24** — feat(knowledge-mcp): add list_metadata_values tool

**2026-04-24** — feat(knowledge-mcp): add list_pages tool

**2026-04-24** — feat(knowledge-mcp): add get_page tool

**2026-04-24** — feat(knowledge-mcp): add retrieve_context tool

**2026-04-24** — docs(plan): cycle 2 plan — 4 new /knowledge-mcp tools

**2026-04-24** — chore(spec): mark cycle 1 complete — ContextRetrievalService extracted

**2026-04-24** — refactor(tools): route SearchWikiTool through ContextRetrievalService

**2026-04-24** — refactor(rest): route /api/search through ContextRetrievalService

**2026-04-24** — test(knowledge): e2e integration test over seeded postgres corpus

**2026-04-24** — feat(knowledge): register ContextRetrievalService as engine manager

**2026-04-24** — feat(knowledge): populate relatedPages via NodeMentionSimilarity

**2026-04-24** — feat(knowledge): populate contributingChunks on retrieved pages

**2026-04-24** — feat(knowledge): implement retrieve() — pages only (chunks/related pending)

**2026-04-24** — feat(knowledge): implement ContextRetrievalService.listPages()

**2026-04-24** — feat(knowledge): implement ContextRetrievalService.listMetadataValues()

**2026-04-24** — fix(plan+test): RAT header on records test; patch plan for getAllPages

**2026-04-24** — fix(knowledge-test): RAT headers, mock caching, lastModified assertion

**2026-04-24** — feat(knowledge): implement ContextRetrievalService.getPage()

**2026-04-24** — feat(knowledge): scaffold DefaultContextRetrievalService

**2026-04-24** — feat(api): add ContextRetrievalService interface

**2026-04-24** — feat(api): add RetrievedPage/RetrievalResult/PageList records

**2026-04-24** — feat(api): add PageListFilter/ContextQuery records

**2026-04-24** — feat(api): add RetrievedChunk/RelatedPage/MetadataValue records

**2026-04-24** — docs(plan): cycle 1 implementation plan — ContextRetrievalService extraction

**2026-04-24** — docs(plan): agent MCP surface redesign — RAG-first consumption MCP + admin split

**2026-04-23** — docs(wiki): fill in RetrievalExperimentHarness with real data + full evolution

**2026-04-23** — docs(wiki): capture April 2026 KG extractor benchmarks on wiki

**2026-04-23** — feat(extract-cli): standalone entity-extractor CLI for Tomcat-less batch runs

**2026-04-23** — docs(kg): knowledge-graph-aware rerank configuration and tuning guide

**2026-04-23** — feat(kg-rag): phase 1-3 uplift — unified embeddings, extractor pipeline, graph-aware rerank

**2026-04-22** — docs(plan): KG uplift for RAG — unified embeddings, extractor, graph-aware rerank

**2026-04-22** — refactor(complexity): extract helpers in ReferredPagesPlugin + ImportContentTool

**2026-04-22** — chore(pmd): mechanical batch + singleton suppressions — 47 → 21 violations

**2026-04-22** — chore(pmd): clear AvoidStringBufferField — 59 → 47 violations

**2026-04-22** — chore(pmd): second-pass sweep — 401 → 59 violations (85% reduction)

**2026-04-22** — chore(pmd): cleanup sweep — 1,888 → 401 PMD violations (79% reduction)

**2026-04-22** — chore(build): scoped PMD ruleset — high-signal rules only

**2026-04-22** — chore(quality): PMD + SpotBugs sweep — empty catches, locale, stack traces, CPD

**2026-04-21** — chore(quality): clear remaining SpotBugs MALICIOUS_CODE / BAD_PRACTICE findings

**2026-04-21** — chore(quality): SpotBugs BAD_PRACTICE cleanup — low-risk subset

**2026-04-21** — fix(concurrency): clear 24/25 SpotBugs MT_CORRECTNESS findings

**2026-04-21** — chore(deps): minor/patch upgrades + SpotBugs 4.9.8.3; deploy-local covers MCP props and stale JDBC jars

**2026-04-20** — fix(security): enforce view ACLs on tool get_page; reject unknown principals on API-key mint

**2026-04-20** — chore(deps): upgrade Testcontainers 1.20.4 → 1.21.4

**2026-04-20** — docs(wiki): News.md roll-up for v1.1.6; retire HygieneOnLongTrips

**2026-04-20** — feat(tools): OpenAPI 3.1 tool server for OpenWebUI and other MCP-less LLM clients

**2026-04-20** — feat(search): hybrid retrieval perf pass — parallelized embedding, incremental index, heading-aware context

**2026-04-20** — feat(auth): unified API-key admin for MCP and OpenAPI tool-server access

**2026-04-19** — chore(release): v1.1.6 — hybrid retrieval, MCP access hardening, admin content ops

**2026-04-19** — docs(wiki): update News.md

**2026-04-19** — feat(search): retrieval experiment harness and eval reports

**2026-04-19** — feat(search): Ollama embedding client and model registry

**2026-04-18** — test(hybrid): InMemoryChunkVectorIndex — cold-start, reload, multi-model, dim/arg guards

**2026-04-18** — feat(hybrid): InMemoryChunkVectorIndex for dense top-k over content_chunk_embeddings

**2026-04-18** — Merge phase 4: QueryEmbedder with cache, timeout, circuit breaker

**2026-04-18** — Merge phase 3: hybrid retrieval core (PageAggregation, HybridFuser, DenseRetriever)

**2026-04-18** — Merge phase 1: dense-embedding data layer (V009, EmbeddingIndexService, async listener)

**2026-04-18** — feat(hybrid): QueryEmbedder wraps embedding client with cache, timeout, breaker

**2026-04-18** — feat(hybrid): hand-rolled CLOSED/OPEN/HALF_OPEN circuit breaker

**2026-04-18** — feat(hybrid): QueryEmbedderConfig record, CircuitState enum, metrics snapshot

**2026-04-18** — build(hybrid): fix Caffeine groupId to com.github.ben-manes.caffeine

**2026-04-18** — feat(search): async page-save listener for incremental embedding reindex

**2026-04-18** — feat(admin): wire EmbeddingIndexService hook into rebuild pipeline

**2026-04-18** — feat(search): add HybridConfig with defaults matching the winning experiment

**2026-04-18** — feat(search): add DenseRetriever and placeholder ChunkVectorIndex interface

**2026-04-18** — feat(search): add HybridFuser for weighted RRF of BM25 and dense lists

**2026-04-18** — feat(search): add PageAggregation + PageAggregator for hybrid retrieval

**2026-04-18** — feat(search): EmbeddingIndexService — production data layer for chunk embeddings

**2026-04-18** — feat(db): V009 content_chunk_embeddings — dense-vector projection of chunks

**2026-04-18** — build(hybrid): stub TextEmbeddingClient + EmbeddingKind for Phase 1

**2026-04-18** — build(hybrid): add Caffeine dep and TextEmbeddingClient stub for Phase 4

**2026-04-18** — docs(search): hybrid retrieval rollout plan — models, hardware, TEI setup

**2026-04-18** — docs(wiki): update News.md

**2026-04-18** — refactor(rest): replace imperative verb handlers with declarative Resource dispatch in AdminKnowledgeResource

**2026-04-18** — refactor(ui): extract ValidationType enum, replace switch in InputValidator

**2026-04-18** — refactor(attachment): extract UploadFormParser and UploadFormData from AttachmentServlet

**2026-04-18** — docs(wiki): repair heading markup damaged by automated conversion

**2026-04-18** — feat(tools): bin/search-eval — standalone retrieval-quality evaluator

**2026-04-18** — fix(test): guard TestEngine.emptyWikiDir against deleting real corpora

**2026-04-18** — fix(markdown): preserve `[text](#anchor)` as same-page fragment link

**2026-04-18** — docs(wiki): add Wikantik Search Refinement — eval-set iteration guide

**2026-04-17** — feat(search): retrieval evaluation harness with BM25 baseline

**2026-04-17** — feat(admin): chunk inspector tab for ad-hoc chunker inspection

**2026-04-17** — refactor(auth): extract PermissionFilter as a reusable ACL decision point

**2026-04-17** — docs(chunking): reconcile spec with mergeForwardTokens implementation

**2026-04-17** — fix(db): install-fresh bootstraps migrate role and transfers ownership

**2026-04-17** — fix(admin): justify LOG.error calls in rebuild service

**2026-04-17** — feat(frontend): wire Index Status tab; remove legacy reindex button

**2026-04-17** — feat(frontend): IndexStatusTab with polling, stat cards, rebuild

**2026-04-17** — feat(frontend): api.admin.getIndexStatus and rebuildIndexes

**2026-04-17** — fix(chunking): wire production MeterRegistry to chunker and rebuild

**2026-04-17** — feat(chunking): Prometheus metrics for chunker and rebuild

**2026-04-17** — feat(rest): GET /admin/content/index-status + POST /admin/content/rebuild-indexes

**2026-04-17** — feat(admin): wire ContentIndexRebuildService into the engine

**2026-04-17** — feat(admin): rebuild run loop with system-page handling

**2026-04-17** — feat(admin): ContentIndexRebuildService state machine scaffold

**2026-04-17** — feat(chunking): save-time ChunkProjector PageFilter

**2026-04-17** — feat(chunking): ContentChunkRepository with diff apply and stats

**2026-04-17** — feat(chunking): ChunkDiff classifies inserts, updates, deletes

**2026-04-17** — refactor(chunking): explicit mergeForwardTokens Config field

**2026-04-17** — feat(chunking): token budget, atomic blocks, merge-forward

**2026-04-17** — fix(chunking): preserve full heading title across inline markup

**2026-04-17** — feat(chunking): heading-aware splitting with heading_path

**2026-04-17** — feat(chunking): Chunk record and minimal ContentChunker

**2026-04-17** — feat(db): add kg_content_chunks table (V008)

**2026-04-17** — fix(search): complete system-page filter across all entry points

**2026-04-17** — feat(search): filter system pages from Lucene indexing

**2026-04-17** — docs(chunking): content chunking spec and plan

**2026-04-16** — feat(search): field boosts, recency decay, search metrics, queue depth

**2026-04-16** — docs(spec): production db deployment workflow plan

**2026-04-16** — feat(rest): support wildcard origins in cors allowedOrigins

**2026-04-16** — fix(it): clean up stale pgvector containers before docker:start

**2026-04-16** — refactor(infra): move db scripts and deploy-local.sh to bin/

**2026-04-14** — refactor(jdbc): remove HSQLDB enum value and all stale references

**2026-04-14** — chore(it): replace password-looking literal with filter variable

**2026-04-14** — fix(ui,it,sso): re-enable HubDiscovery ITs and quiet expected SSO errors

**2026-04-14** — refactor(it): replace HSQLDB with PostgreSQL+pgvector across all tests

**2026-04-13** — docs(spec): design for HSQLDB removal, PG+pgvector everywhere in ITs

**2026-04-13** — feat(sso): end-to-end OIDC IT + fix post-callback session translation

**2026-04-13** — Update OAuthImplementation.md

**2026-04-13** — docs(readme): document pgvector prerequisite + install instructions

**2026-04-13** — feat(logging): add INFO audit trail for admin actions

**2026-04-13** — test(knowledge): cover snapshot cache invalidation + tag-filter edge cases

**2026-04-13** — fix(graph): backbone +1 hop respects hub-only set, not accumulating set

**2026-04-13** — fix(graph): bidi merge no longer flags same-direction duplicates

**2026-04-13** — refactor(graph): extract log-scale zoom math + add slider tests

**2026-04-13** — docs: wiki content updates and hub/indexing plans

**2026-04-13** — fix(graph): finer zoom slider steps and narrower usable range

**2026-04-13** — fix(graph): log-scale the zoom slider so small moves aren't huge zooms

**2026-04-13** — fix(graph): stack FilterPanel and toolbar above canvas instead of overlay

**2026-04-12** — fix(it): use label-based selector for +1 hop checkbox in GraphFilterViewsIT

**2026-04-12** — test(it): Selenide IT covering graph filter presets and URL sync

**2026-04-12** — feat(graph): wire FilterPanel + URL sync into GraphView

**2026-04-12** — feat(graph): URL sync for filter state (parse/serialize)

**2026-04-12** — feat(graph): FilterPanel UI with presets, contextual controls, chips

**2026-04-12** — feat(graph): Cytoscape classes for hidden/faded filter states

**2026-04-12** — feat(graph): pure applyFilters engine for client-side view modes

**2026-04-12** — feat(graph): filter state model with presets and orthogonal controls

**2026-04-12** — feat(knowledge): expose cluster/tags/status in SnapshotNode DTO

**2026-04-12** — docs(plan): graph filter views implementation plan

**2026-04-12** — docs(spec): graph filter views design

**2026-04-12** — docs: refresh README and linked docs; add MathematicalNotation

**2026-04-12** — feat(math): LaTeX math expression rendering via Flexmark GitLab ext + KaTeX

**2026-04-12** — feat(frontend): semantic zoom and parallel edge merging for knowledge graph

**2026-04-12** — fix(frontend): sub-context deployment fixes, IT test repairs, and full-width graph layout

**2026-04-12** — test(it): add 6 Selenide ITs for knowledge graph visualization

**2026-04-12** — fix(routing): add /graph to SPA routing filter and web.xml

**2026-04-12** — feat(frontend): wire /graph route and sidebar link

**2026-04-12** — feat(frontend): add GraphCanvas and GraphView state owner

**2026-04-12** — feat(frontend): add GraphLegend, GraphToolbar, GraphDetailsDrawer with TDD tests

**2026-04-12** — feat(frontend): add graph API client, error/loading components

**2026-04-12** — feat(frontend): add cytoscape stylesheet and graph CSS

**2026-04-12** — feat(frontend): add graph-data.js transform with TDD tests

**2026-04-12** — chore(frontend): add cytoscape + testing deps, switch test env to happy-dom

**2026-04-12** — feat(rest): add KnowledgeGraphResource servlet at /api/knowledge/graph

**2026-04-12** — feat(knowledge): implement snapshotGraph with caching and ACL redaction

**2026-04-12** — feat(knowledge): add getAllNodes() to JdbcKnowledgeRepository

**2026-04-12** — feat(knowledge): add GraphRoleClassifier with TDD tests

**2026-04-12** — feat(api): add GraphSnapshot records and snapshotGraph interface method

**2026-04-12** — fix(ui): label "relations" as "relationship types" in KG embeddings tab

**2026-04-12** — fix(knowledge): gradient clipping and relation normalization in ComplEx

**2026-04-12** — feat(knowledge): track dismissed hub proposals to prevent rediscovery

**2026-04-12** — feat(frontend+it): React SPA testability and integration test modernization

**2026-04-12** — content(wikantik-pages): bulk content refresh across ~802 pages

**2026-04-12** — plan: knowledge graph visualization implementation (19 tasks, TDD)

**2026-04-11** — docs(specs): knowledge graph visualization design

**2026-04-11** — content(wikantik-pages): bulk heading flourish cleanup across ~793 pages

**2026-04-11** — refactor(util): simplify escapeHTMLEntities with switch + helpers

**2026-04-11** — refactor(rest): collapse JSON/page preamble boilerplate

**2026-04-11** — refactor(plugin): flatten InsertPage.execute with guard clauses

**2026-04-11** — refactor(hub): extract algorithmic steps in HubOverviewService

**2026-04-11** — test(war): guard robots.txt Sitemap: directive against regressions

**2026-04-11** — test(sitemap): assert <lastmod> equals Page.getLastModified() date

**2026-04-11** — feat(seo): emit dateModified in page JSON-LD from Page.getLastModified

**2026-04-11** — fix(rest): encode URLs and handle errors in ChangesResource

**2026-04-11** — feat(rest): add /api/changes?since= feed for OpenWebUI sync script

**2026-04-11** — fix(rest): harden WikiPageFormatFilter date parsing and image links

**2026-04-11** — feat(rest): serve /wiki/{slug}?format=md|json for RAG and SEO indexing

**2026-04-11** — security(csrf): allow same-origin requests regardless of whitelist

**2026-04-11** — security(web): set HttpOnly/Secure on session cookie (L3)

**2026-04-11** — security(rest): attachment rename requires edit permission (L2)

**2026-04-11** — security(auth): bootstrap admin override must expire and log ERROR (L1)

**2026-04-11** — security(rest): whitelist CORS origins instead of sending wildcard

**2026-04-11** — security(csrf): extend protection to PUT/DELETE/PATCH with Origin check

**2026-04-11** — security(mcp): restrict PingSearchEnginesTool URLs to configured base

**2026-04-11** — security(auth): constant-time compare for legacy {SHA} password path

**2026-04-11** — security(markdown): sanitize HTML output and block javascript: URIs

**2026-04-11** — security(attach): block active-content uploads and force safe disposition

**2026-04-11** — security(mcp): audit-log every write operation

**2026-04-11** — security(mcp): fail closed when no keys or CIDRs configured

**2026-04-11** — test: fix integration test suite auth gaps

**2026-04-10** — test: HubOverviewAdminIT selenide flow

**2026-04-10** — test: HubOverviewAdminPage selenide page object

**2026-04-10** — feat: mount ExistingHubsPanel in HubDiscoveryTab

**2026-04-10** — feat: ExistingHubsPanel container component

**2026-04-10** — feat: ExistingHubDrilldown presentational component

**2026-04-10** — feat: api.knowledge methods for existing hubs panel

**2026-04-10** — feat: POST /admin/knowledge/hub-discovery/hubs/{name}/remove-member

**2026-04-10** — feat: GET /admin/knowledge/hub-discovery/hubs/{name} drilldown

**2026-04-10** — feat: GET /admin/knowledge/hub-discovery/hubs

**2026-04-10** — feat: RemoveHubMemberRequest DTO

**2026-04-10** — feat: register HubOverviewService with Lucene MLT seam

**2026-04-10** — feat: wire HubOverviewService in KnowledgeGraphServiceFactory

**2026-04-10** — feat: HubOverviewService.removeMember happy path

**2026-04-10** — test: Lucene exception falls back to empty MLT list

**2026-04-10** — test: orphan hub drilldown reads from KG only

**2026-04-10** — test: loadDrilldown returns null for unknown hub

**2026-04-10** — feat: HubOverviewService.loadDrilldown happy path

**2026-04-10** — test: near-miss threshold counting

**2026-04-10** — test: inbound link counting excludes hub and same-hub members

**2026-04-10** — test: NaN coherence hubs sort last

**2026-04-10** — test: HubOverviewService empty model returns empty list

**2026-04-10** — feat: HubOverviewService.listHubOverviews happy path

**2026-04-10** — feat: HubOverviewService skeleton with records and builder

**2026-04-10** — style: use short type names in LuceneSearchProvider.moreLikeThis

**2026-04-10** — feat: LuceneSearchProvider.moreLikeThis adapter for hub overview

**2026-04-10** — feat: hub overview config properties

**2026-04-10** — feat: HubOverviewException for hub-overview service failures

**2026-04-10** — docs: existing-hubs-panel design spec

**2026-04-10** — content: hub discovery pages and member backrefs

**2026-04-10** — fix: knowledge-graph admin page links

**2026-04-10** — test: selenide its for hub discovery admin ui

**2026-04-10** — test: RestSeedHelper for selenide its

**2026-04-10** — test: HubDiscoveryAdminPage selenide page object

**2026-04-10** — feat: hub discovery admin tab

**2026-04-10** — feat: HubDiscoveryCard react component

**2026-04-10** — feat: hub-discovery api client methods

**2026-04-10** — feat: wire AdminHubDiscoveryResource servlet mapping

**2026-04-10** — feat: AdminHubDiscoveryResource with DTO-based accept endpoint

**2026-04-10** — feat: hub discovery default properties

**2026-04-10** — feat: wire HubDiscoveryService through KnowledgeGraphServiceFactory

**2026-04-09** — fix: correct accept concurrency doc comment and add missing-id accept test

**2026-04-09** — feat: HubDiscoveryService accept/dismiss with test doubles

**2026-04-09** — test: empty/tiny/exemplar edge cases for hub discovery

**2026-04-09** — fix: use printf format for coherence value in hub discovery log

**2026-04-09** — fix: correct HubDiscoveryService property names and constant visibility

**2026-04-09** — feat: HubDiscoveryService.generateClusterProposals

**2026-04-09** — feat: exception types for hub discovery

**2026-04-09** — feat: HubDiscoveryRepository with Testcontainers coverage

**2026-04-09** — fix: wire minPts through to Tribuo HDBSCAN and log training errors

**2026-04-09** — fix: replace Smile DBSCAN approximation with Tribuo HDBSCAN

**2026-04-09** — feat: SmileHdbscanClusterer wrapper with unit tests

**2026-04-09** — feat: V006 hub_discovery_proposals schema

**2026-04-09** — build: add smile-core dependency for HDBSCAN clustering

**2026-04-09** — docs: add hub discovery (cluster-based) design spec

**2026-04-09** — refactor: extract KnowledgeGraphServiceFactory from WikiEngine

**2026-04-09** — refactor: replace HubProposalService constructors with a Builder

**2026-04-09** — refactor: address SpotBugs findings in recent hub-proposal code

**2026-04-09** — refactor: split HubProposalService + AdminKnowledgeResource by step

**2026-04-09** — fix: prevent deploy-local.sh from prompting on catalina.out rotation

**2026-04-09** — fix: make hub proposals actually generate and display

**2026-04-09** — content: backfill default frontmatter on existing wiki pages

**2026-04-09** — feat: versioned database migration system

**2026-04-09** — fix: make Backfill Frontmatter admin action actually work

**2026-04-09** — fix: gate FrontmatterDefaultsFilter with wikantik.frontmatter.autoDefaults property

**2026-04-09** — feat: add HubProposalService with centroid + percentile algorithm

**2026-04-09** — feat: add Hub Proposals tab, backfill button, and register HubProposalRepository

**2026-04-09** — feat: add REST endpoints and API client for hub proposals, backfill, and sync

**2026-04-09** — feat: add HubSetPlugin for rendering Hub member lists

**2026-04-09** — feat: add HubProposalRepository for hub proposals and centroids

**2026-04-09** — feat: register FrontmatterDefaultsFilter and HubSyncFilter in WikiEngine

**2026-04-09** — feat: add HubSyncFilter for bidirectional Hub membership sync

**2026-04-09** — feat: add FrontmatterDefaultsFilter for auto-generating defaults on save

**2026-04-09** — feat: add auto-generated to PROPERTY_ONLY_KEYS, verify hubs becomes relationship

**2026-04-09** — feat: add SummaryExtractor for heuristic sentence selection

**2026-04-09** — feat: add TagExtractor for TF-IDF keyword extraction

**2026-04-09** — feat: add TitleDeriver for page name to human title conversion

**2026-04-09** — feat: add DDL for hub_centroids and hub_proposals tables

**2026-04-09** — docs: add Hub membership and default frontmatter implementation plan

**2026-04-09** — docs: add Hub membership and default frontmatter design spec

**2026-04-08** — feat: server-side pagination for admin embeddings and graph explorer

**2026-04-08** — feat: replace MCP content CRUD with export/import workflow

**2026-04-08** — feat: multi-attempt stale-asset recovery, hardened no-cache headers, KG merge renames frontmatter

**2026-04-08** — fix: add detailed logging to content embeddings retrain flow

**2026-04-07** — fix: return friendly HTML page for unauthorized admin browser navigation

**2026-04-07** — feat: exclude system pages from KG, add Clear All for knowledge graph

**2026-04-07** — feat: create links_to KG edges from body links, list pages without frontmatter

**2026-04-06** — feat: split Embeddings tab into KG Embeddings and Content Embeddings

**2026-04-06** — refactor: migrate all database tests to PostgreSQL Testcontainers, remove H2/HSQLDB

**2026-04-06** — fix: use Cache-Control no-store for SPA entry point to fix stale reload

**2026-04-05** — build: configure mvn clean to remove frontend build output

**2026-04-05** — refactor: merge wikantik-bootstrap module into wikantik-main

**2026-04-05** — refactor: extract parseIntParam to RestServletBase, remove duplication

**2026-04-05** — refactor: remove DSL wrapper classes, expose SPI directly through Wiki

**2026-04-05** — refactor: remove filter backward-compatibility layer (BasePageFilter, FilterSupportOperations)

**2026-04-05** — fix: remove duplicate allowHTML property (conflicting values)

**2026-04-05** — refactor: delete unused CustomXMLOutputProcessor and FastSearch

**2026-04-05** — docs: update auto-generated News page

**2026-04-05** — refactor: consolidate PageCommand, WikiCommand, GroupCommand, RedirectCommand into GenericCommand

**2026-04-05** — refactor: migrate remaining deprecated imports to wikantik-api

**2026-04-05** — fix: skip Markdown checkbox syntax in link scanner, improve pageExists error message

**2026-04-05** — chore: rotate catalina.out on redeploy

**2026-04-05** — refactor: remove 13 deprecated shims and dead code between wikantik-main and wikantik-api

**2026-04-05** — fix: inline column names in JDBCUserDatabaseTest after constant removal

**2026-04-05** — docs: remove column-mapping property examples

**2026-04-05** — refactor: remove column-mapping properties from all config files

**2026-04-05** — refactor: hardcode JDBCUserDatabase SQL, remove column-mapping properties

**2026-04-05** — refactor: hardcode JDBCGroupDatabase SQL, remove column-mapping properties

**2026-04-05** — plan: hardcode JDBC SQL implementation plan

**2026-04-05** — spec: hardcode JDBC SQL statements, remove column-mapping properties

**2026-04-05** — fix: keep file-based policy fallback for test environments without JNDI

**2026-04-05** — docs: update all references to use single wikantik.datasource

**2026-04-05** — refactor: test web.xml and IT pom use single jdbc/WikiDatabase

**2026-04-05** — refactor: config templates use single jdbc/WikiDatabase datasource

**2026-04-05** — refactor: properties files use single wikantik.datasource

**2026-04-05** — refactor: DefaultAuthorizationManager always uses database policy via wikantik.datasource

**2026-04-05** — refactor: WikiEngine.initKnowledgeGraph reads wikantik.datasource

**2026-04-05** — refactor: ObservabilityLifecycleExtension reads wikantik.datasource

**2026-04-05** — refactor: JDBCGroupDatabase uses shared wikantik.datasource property

**2026-04-05** — refactor: JDBCUserDatabase uses shared wikantik.datasource property

**2026-04-05** — feat: add PROP_DATASOURCE and DEFAULT_DATASOURCE to AbstractJDBCDatabase

**2026-04-05** — plan: datasource consolidation implementation plan

**2026-04-05** — docs: add datasource consolidation design spec

**2026-04-05** — feat: add non-blocking toast banner for version mismatch on redeployment

**2026-04-05** — feat: detect server version mismatch in API client and dispatch event

**2026-04-05** — feat: add inline auto-recovery script for stale asset load failures

**2026-04-05** — feat: add Vite build-version plugin — emits build-version.txt and __BUILD_VERSION__ constant

**2026-04-05** — feat: add BuildVersionFilter to set X-Build-Version header on API responses

**2026-04-05** — refactor: move asset cache headers to CacheHeaderFilter, remove dead code from SpaRoutingFilter

**2026-04-05** — feat: add CacheHeaderFilter for immutable hashed assets and no-cache index.html

**2026-04-05** — docs: add stale-asset protection implementation plan

**2026-04-05** — docs: add stale-asset protection design spec

**2026-04-05** — feat: knowledge graph admin UI, MCP simplification, and wiki content updates

**2026-04-05** — refactor: reduce duplication and cyclomatic complexity across REST and main modules

**2026-04-05** — refactor: consolidate Command hierarchy into GenericCommand (~170 lines saved)

**2026-04-05** — fix: WikiContext.getRoutePath() was returning contentTemplate instead of routePath

**2026-04-05** — refactor: remove dead AJAX framework (~1600 lines)

**2026-04-05** — docs: clean up JSP references in javadoc, comments, and CSS selectors

**2026-04-05** — refactor: remove dead getForwardPage() from URLConstructor hierarchy

**2026-04-05** — refactor: rename PropertyReader JSP constants and log4j2 config naming

**2026-04-05** — refactor(auth): rename JSP cookie names with legacy fallback

**2026-04-05** — chore: remove JSP artifacts from test web.xml and WebContainerAuthorizer

**2026-04-05** — fix: replace Login.jsp redirects with /login in SSO servlets

**2026-04-05** — refactor: replace JSP URL patterns with clean route paths in ContextEnum

**2026-04-05** — chore: remove legacy JavaScript, wro4j Haddock build, and dead styles

**2026-04-05** — chore: remove Jakarta JSP dependencies and jspc-maven-plugin

**2026-04-05** — chore: delete dead JSP tag library descriptors and stale WEB-INF

**2026-04-05** — fix(knowledge): harden RelationshipsPlugin output encoding

**2026-04-05** — docs(knowledge): backfill 9 wiki pages for features with specs/plans but no wiki representation

**2026-04-05** — docs(knowledge): update cluster pages with deployed status and richer relationships

**2026-04-05** — docs(knowledge): decompose KnowledgeGraphCore into 5 focused sub-feature pages

**2026-04-05** — feat(knowledge): add RelationshipsPlugin for in-page graph navigation

**2026-04-05** — feat(knowledge): add status filter dropdown to Node Explorer

**2026-04-05** — feat(knowledge): add status filter parameter to node list endpoint

**2026-04-05** — feat(knowledge): add distinct status values to schema discovery

**2026-04-05** — feat(knowledge): add status property filter to node queries

**2026-04-05** — docs: add knowledge graph dogfooding implementation plan

**2026-04-05** — docs: add KnowledgeGraphDogfooding wiki page for active design work

**2026-04-05** — docs: add knowledge graph dogfooding design spec

**2026-04-04** — feat(knowledge): add bulk project-all endpoint to seed graph from existing pages

**2026-04-04** — fix(knowledge): integrate knowledge graph into WikiEngine and fix production issues

**2026-04-04** — fix(knowledge): downgrade JdbcKnowledgeRepository LOG.error to LOG.warn

**2026-04-04** — feat(knowledge): implement frontmatter write-back on proposal approval

**2026-04-04** — feat(knowledge): add wikantik-knowledge to WAR packaging and deployment bootstrap

**2026-04-04** — docs(knowledge): document knowledge-admin role plan in AdminKnowledgeResource

**2026-04-04** — feat(knowledge): add graph explorer and node detail UI components

**2026-04-04** — feat(knowledge): add admin knowledge page with proposal review queue

**2026-04-04** — feat(knowledge): add admin knowledge API client and routing

**2026-04-04** — feat(knowledge): add admin REST endpoints for knowledge graph management

**2026-04-04** — feat(knowledge): add proposal tools to authoring MCP — propose, list proposals, list rejections

**2026-04-04** — feat(knowledge): add KnowledgeMcpInitializer — separate MCP endpoint at /knowledge-mcp

**2026-04-04** — feat(knowledge): add 5 consumption MCP tools — discover, query, traverse, get, search

**2026-04-04** — feat(knowledge): add wikantik-knowledge module skeleton

**2026-04-04** — feat(knowledge): add GraphProjector for frontmatter-to-graph synchronization

**2026-04-04** — feat(knowledge): add DefaultKnowledgeGraphService with BFS traversal and proposal logic

**2026-04-04** — feat(knowledge): add frontmatter relationship detector with convention-based rules

**2026-04-04** — fix(knowledge): fix direction validation, null guard, and error handling in JdbcKnowledgeRepository

**2026-04-04** — feat(knowledge): add JDBC repository for knowledge graph with full test coverage

**2026-04-04** — feat(knowledge): add KnowledgeGraphService interface and supporting types

**2026-04-04** — feat(knowledge): add data model records for knowledge graph

**2026-04-04** — feat(knowledge): add PostgreSQL and H2 DDL for knowledge graph tables

**2026-04-04** — docs: add knowledge core implementation plan — 19 tasks with TDD steps

**2026-04-04** — docs: add knowledge core design spec

**2026-04-04** — docs: update wiki content — news log, blog entries, and page edits

**2026-04-04** — fix(blog): sanitize topic names to prevent double-dot filenames

**2026-04-04** — fix(editor): strip frontmatter from preview pane and fix literal \u2026 in UI

**2026-04-04** — fix(attachments): use full attachment name for URL generation and remove legacy info icon

**2026-04-04** — feat(attachments): add attachment management UI with drag-and-drop, server-side image rendering, and robust filename validation

**2026-04-03** — docs: add attachment management design spec

**2026-04-03** — fix(cache): correct render cache eviction to use real page version in keys

**2026-04-03** — feat(blog): add side-by-side editor with live preview for new blog entries, blog system enhancements, and wiki content updates

**2026-04-03** — feat(user): add bio textarea to admin user form modal

**2026-04-03** — feat(user): add bio textarea to user preferences page

**2026-04-03** — feat(user): expose bio field in auth and admin REST endpoints

**2026-04-03** — feat(user): map bio column in JDBCUserDatabase with round-trip test

**2026-04-03** — feat(user): add bio field to UserProfile interface and DefaultUserProfile

**2026-04-03** — feat(user): add bio column to users table DDL

**2026-04-03** — fix(blog): use /blog/ prefix in plugin links instead of /wiki/blog/

**2026-04-03** — feat(blog): add blog editor with dedicated route and API endpoint

**2026-04-03** — fix(blog): use correct auth field loginPrincipal for owner checks

**2026-04-03** — fix(blog): use blog API for BlogHome instead of generic page API

**2026-04-03** — fix(blog): downgrade LOG.error to LOG.warn in blog plugins

**2026-04-03** — feat(blog): add React frontend components and routes for blog feature

**2026-04-03** — feat(blog): register BlogResource servlet and add /blog/ SPA routing

**2026-04-03** — fix(blog): replace fragile INVALID sentinel check with null check in BlogResource doDelete

**2026-04-03** — feat(blog): add BlogResource REST API for blog operations

**2026-04-03** — feat(blog): add BlogListing, LatestArticle, and ArticleListing plugins

**2026-04-03** — feat(blog): implement DefaultBlogManager with full lifecycle management

**2026-04-03** — feat(blog): add blog template, classmapping, and WikiEngine registration

**2026-04-03** — feat(blog): store version history inside blog user directories

**2026-04-03** — feat(blog): add blog-aware name resolution to AbstractFileProvider

**2026-04-03** — feat(blog): add BlogManager interface, BlogInfo record, and BlogAlreadyExistsException

**2026-04-03** — Add blog feature implementation plan

**2026-04-03** — Add blog feature design spec

**2026-04-02** — Widen editor layout to use available screen width

**2026-04-02** — Fix inverted wiki heading level mapping in converter

**2026-04-02** — Add bulk wiki-to-markdown conversion script

**2026-04-02** — Add wiki-to-markdown converter, markup syntax detection, and SPA cache headers

**2026-04-01** — Move URL routes into URL constructors and remove VAR_REACT_URL_BASE indirection

**2026-04-01** — Add hourly news page refresh from GitHub with admin on-demand trigger


---

## March 2026

**2026-03-31** — Fix SpaRoutingFilter tests and React URL base path for page rendering

**2026-03-31** — Fix LuceneSearchProvider null list and update News with recent activity

**2026-03-31** — Fix SpaRoutingFilter intercepting admin REST API requests

**2026-03-31** — Fix admin API requests intercepted by SpaRoutingFilter

**2026-03-30** — Move test passwords from Java source to properties file

**2026-03-30** — Add tests for ui, parser, references, and remaining gaps

**2026-03-30** — Add tests for search, diff, and variables coverage gaps

**2026-03-30** — Add tests for attachment and filters coverage gaps

**2026-03-30** — Add tests for auth core and SSO coverage gaps

**2026-03-30** — Add tests for auth.login and auth.user coverage gaps

**2026-03-30** — Add tests for remaining plugin coverage gaps

**2026-03-30** — Add tests for JDBCPlugin, DefaultPluginManager, and AbstractReferralPlugin

**2026-03-30** — Refactor WikiContext to inject CommandResolver, add tests for com.wikantik package

**2026-03-30** — Add error-path and edge-case tests for file-based providers

**2026-03-30** — Add comprehensive tests for provider decorator classes

**2026-03-30** — Remove Windows filesystem device-name escaping from AbstractFileProvider

**2026-03-29** — Consolidate duplicated makeURL parameter handling into DefaultURLConstructor

**2026-03-29** — Extract AbstractLinkState base class to consolidate 6 duplicate constructors

**2026-03-29** — Extract WikiSession event handlers into focused methods (CC 24 -> dispatch + helpers)

**2026-03-29** — Extract validateProfile into focused validation methods (CC 29 -> 4 methods)

**2026-03-29** — Consolidate duplicated makeURL parameter handling into DefaultURLConstructor

**2026-03-29** — Fix duplicate WikiServletFilter entry in LogLevelEnforcementTest allowlist

**2026-03-29** — Rename getJSP() to getRoutePath() across Command interface and implementations

**2026-03-29** — Clean up JSP remnants: SecurityVerifier, WikiJSPFilter rename, stale javadoc

**2026-03-29** — Add unit tests for PluginContent, DefaultUserManager, DefaultAclManager

**2026-03-29** — Refactor WikiSession to constructor injection, add CI tests

**2026-03-29** — Refactor LuceneSearchProvider to constructor injection, add CI tests

**2026-03-29** — Refactor auth managers to constructor injection, add CI tests

**2026-03-29** — Add unit tests for WatchDog and ShortURLConstructor

**2026-03-29** — Fix flaky parallel test assertion in DefaultPageManagerCITest

**2026-03-29** — Add unit tests for modules package (WikiModuleInfo, InternalModule)

**2026-03-29** — Refactor DefaultDifferenceManager to constructor injection, add unit tests

**2026-03-29** — Refactor DefaultVariableManager to constructor injection, add CI tests

**2026-03-29** — Refactor WikiAjaxDispatcherServlet for testability, add unit tests

**2026-03-29** — Refactor 4 core managers to constructor injection, add 237 unit tests

**2026-03-29** — Refactor AttachmentServlet to constructor injection + add 23 unit tests

**2026-03-29** — Refactor SecurityVerifier to constructor injection + add 36 unit tests

**2026-03-29** — Refactor SpamFilter to injectable dependencies + add 40 unit tests

**2026-03-29** — Add MockEngineBuilder test utility for mock Engine creation

**2026-03-29** — Add implementation plan: testability via MockEngineBuilder + constructor injection

**2026-03-29** — Remove JSP API remnants, add 241 unit tests for wikantik-main

**2026-03-29** — Update robots.txt: remove JSP references, add React SPA paths

**2026-03-29** — Remove /app from rendered wiki link base URL

**2026-03-29** — Remove accidentally committed socket files and temp artifacts, update .gitignore

**2026-03-29** — Fix WAR build: remove jslint plugin referencing deleted scripts/, remove stale changeSessionId test

**2026-03-29** — Clean up web.xml: remove JSP filters, servlets, and legacy config

**2026-03-29** — Fix compilation after JSP UI/form/tag class deletion

**2026-03-29** — Delete JSP pages, templates, and legacy JavaScript (68+ files)

**2026-03-29** — Move React SPA from /app/ to WAR root, change basename to /

**2026-03-29** — Add implementation plan: remove JSP UI and /app/ prefix (7 tasks)

**2026-03-29** — Add design spec: remove JSP UI and /app/ context path prefix

**2026-03-29** — Fix link interception to handle /wiki/ links without /app/ prefix

**2026-03-29** — Clarify migration script needs to be copied to production server first

**2026-03-29** — Fix backup command: use sudo -u postgres for peer authentication

**2026-03-29** — Add properties file rename migration step to 1.0→1.1 guide

**2026-03-29** — Fix migration guide and script: database name is jspwiki, not wikantik

**2026-03-29** — Add 1.0→1.1 migration guide and production database migration script

**2026-03-29** — Increase HTTP session timeout from 10 to 60 minutes

**2026-03-29** — Make username the Preferences link, remove separate Preferences button

**2026-03-29** — Intercept internal wiki links for React Router navigation

**2026-03-29** — Improve 403 error message in UserFormModal to suggest re-login

**2026-03-29** — Add autocomplete attributes to login form for browser credential suggestions

**2026-03-29** — Make tags in MetadataPanel clickable search links too

**2026-03-29** — Make tags clickable search links for cross-article navigation

**2026-03-28** — Fix diff viewer: API returns 'diff' field, not 'diffHtml'

**2026-03-28** — Re-fetch page data when auth state changes to update permission buttons

**2026-03-28** — Add explicit credentials: 'same-origin' to fetch calls

**2026-03-28** — Revert changeSessionId() — breaks SessionMonitor session tracking

**2026-03-28** — Fix login broken by session fixation prevention: re-register WikiSession after changeSessionId()

**2026-03-28** — Add JSP dead code catalog: 228 files identified for removal after React migration

**2026-03-28** — Add user preferences and lost password recovery

**2026-03-28** — Add comments system: REST endpoint + React panel

**2026-03-28** — Add page rename: REST endpoint + React modal

**2026-03-28** — Add diff/version comparison viewer to React SPA

**2026-03-28** — Add conflict resolution modal to page editor

**2026-03-28** — Add page delete button with confirmation modal to React SPA

**2026-03-28** — Add implementation plan: JSP to React migration (8 tasks)

**2026-03-28** — Add design spec: JSP to React migration — complete UI feature parity

**2026-03-28** — Add comprehensive plugin test coverage: 8 plugins, 74 new tests

**2026-03-28** — Improve wikantik-main test coverage: auth, render, groups, JDBC

**2026-03-28** — Extract invalidateCaches() in PageDirectoryWatcher to eliminate duplication

**2026-03-28** — Push wikantik-rest toward 80%: restricted group names, nonexistent groups, version purge with history, bulk delete verification, search frontmatter

**2026-03-28** — Improve wikantik-rest test coverage with meaningful assertions

**2026-03-28** — Improve wikantik-util test coverage with meaningful assertions

**2026-03-28** — Improve wikantik-mcp test coverage from 74.6% to 81.6% with meaningful assertions

**2026-03-28** — Add CacheInfo tests and EhcacheCachingManager coverage improvements

**2026-03-28** — Add WikiEvent base class and WikiEventManager coverage tests

**2026-03-28** — Add AdminUserResource and AdminContentResource tests — wikantik-rest coverage improvement

**2026-03-28** — Add tests for FormUtil, XhtmlUtil, XHTML, PropertiesUtils — wikantik-util coverage improvement

**2026-03-28** — Add event module tests: WikiSecurityEvent, WikiPageEvent, WikiEngineEvent, WikiPageRenameEvent

**2026-03-28** — Add SpaRoutingFilter tests: static asset passthrough, SPA route forwarding

**2026-03-28** — Add AdminAuthFilter tests: OPTIONS passthrough, 403 for non-admin, valid JSON response

**2026-03-28** — Add comprehensive tests for all 9 OWASP security header filters

**2026-03-28** — Add test coverage: search permissions, SessionMonitor, CSRF admin exemption, attachment ACLs, frontmatter edge cases, DatabasePolicy errors, version conflicts, concurrency, CORS, bootstrap

**2026-03-28** — Security hardening: CSP, session fixation, CORS restriction, error sanitization

**2026-03-28** — Refactor MCP tools: extract methods to reduce cyclomatic complexity

**2026-03-28** — Fix SpotBugs encoding, null-check, naming, and mutable-collection issues

**2026-03-28** — Fix SpotBugs encoding, null-check, naming, and mutable-collection issues

**2026-03-28** — Replace SessionMonitor WeakHashMap with ConcurrentHashMap for lock-free session lookup

**2026-03-28** — Optimize Lucene search: skip full permission check for pages without ACLs

**2026-03-28** — Update 6 docs to reflect current system capabilities

**2026-03-28** — Update README and CLAUDE.md to reflect current capabilities

**2026-03-28** — Scalability: increase cache capacity/TTL, replace synchronized with ReadWriteLock

**2026-03-28** — Include effective permissions in page API response, gate Edit button on actual permission

**2026-03-28** — Show Edit/Create buttons for all users, not just authenticated

**2026-03-28** — Fix AllPermission checkbox: style as a clear toggle control, not orphaned checkbox

**2026-03-28** — Fix policy grants table: align Manage column and constrain Actions width

**2026-03-28** — Fix CSRF filter blocking admin POST/PUT/DELETE requests

**2026-03-28** — Add Security tab to admin panel navigation and routing

**2026-03-28** — Add AdminSecurityPage with Groups and Policy Grants sub-sections

**2026-03-28** — Add PolicyGrantFormModal with context-sensitive action checkboxes

**2026-03-28** — Add GroupFormModal component for create/edit group

**2026-03-28** — Add implementation plan: admin security UI

**2026-03-28** — Add design spec: admin security UI for groups and policy grants

**2026-03-28** — Update config template and deploy script for database-backed permissions

**2026-03-28** — Add admin REST endpoints for groups and policy grants, integrate DatabasePolicy into DefaultAuthorizationManager

**2026-03-28** — Add DatabasePolicy: database-backed policy provider for authorization

**2026-03-28** — Guard Admin group: block deletion and empty-member saves

**2026-03-28** — Add DDL migration for policy_grants table with default seed data

**2026-03-28** — Add implementation plan: database-backed permissions

**2026-03-28** — Spec update: block removal of last Admin group member in code

**2026-03-28** — Add design spec: database-backed permissions and group management

**2026-03-27** — Security: add ObjectInputFilter whitelist to block unsafe deserialization

**2026-03-27** — Security: enforce ACL/permission checks on REST API endpoints

**2026-03-27** — Security: enforce ACL/permission checks on REST API endpoints

**2026-03-27** — Update News page with recent development entries

**2026-03-27** — Add NIST 800-63B password strength validation for user account creation

**2026-03-27** — Security hardening: IP-restrict observability endpoints, sanitize health error messages, fix async filter support

**2026-03-27** — Add wikantik-observability module: health checks, Prometheus metrics, structured logging, request correlation

**2026-03-27** — Admin panel: user management, content management, and infrastructure improvements

**2026-03-26** — Article authoring UX: new article wizard, frontmatter editing, change history

**2026-03-26** — Filter system pages from all referral plugins — finally done!

**2026-03-26** — Update News page with recent development entries + remove test artifacts

**2026-03-26** — Add search index rebuild guide for local and Docker deployments

**2026-03-26** — Clickable metadata chips with Lucene frontmatter indexing

**2026-03-26** — Sidebar UX: move sign-in + theme toggle between logo and search, remove Pages section

**2026-03-25** — Fix React SPA link routing for plugin-rendered content

**2026-03-25** — Mobile sidebar UX, dev seed, and CLAUDE.md docs

**2026-03-25** — Remove legacy JSPWiki artifacts superseded by Markdown migration

**2026-03-25** — Update React sidebar to match JSP LeftMenu navigation structure

**2026-03-25** — Bundle React SPA into WAR — served at /app/ from same Tomcat

**2026-03-25** — Add React frontend — editorial magazine aesthetic with full read/write

**2026-03-25** — REST API: add render option, version retrieval, metadata PATCH

**2026-03-25** — REST API: add attachments, diff, recent changes, outbound links endpoints

**2026-03-25** — Add REST API integration tests -- Cargo-based HTTP validation

**2026-03-24** — REST API Phases 2-3: search, history, backlinks, login/logout

**2026-03-24** — Add wikantik-rest module — REST/JSON API for alternative frontends (Phase 1)

**2026-03-24** — Upgrade Selenide from 7.12.1 to 7.13.0

**2026-03-24** — Revert "Merge pull request #20 from jakefearsd/dependabot/maven/selenide.version-7.15.0"

**2026-03-24** — Merge pull request #20 from jakefearsd/dependabot/maven/selenide.version-7.15.0

**2026-03-24** — Merge pull request #17 from jakefearsd/dependabot/maven/pac4j.version-6.3.3

**2026-03-24** — Merge pull request #19 from jakefearsd/dependabot/maven/commons-net-commons-net-3.13.0

**2026-03-24** — Merge pull request #18 from jakefearsd/dependabot/maven/org.apache.maven.plugins-maven-pmd-plugin-3.28.0

**2026-03-24** — Bump org.apache.maven.plugins:maven-pmd-plugin from 3.26.0 to 3.28.0

**2026-03-24** — Rename single-letter local variables to descriptive names across 33 files

**2026-03-24** — Add Maven site generation — reports, coverage, and per-module navigation

**2026-03-24** — Fix encapsulation violations in wikantik-mcp — use Engine interface, remove SitemapServlet import

**2026-03-24** — Add wikantik-cache-memcached module — distributed cache adapter

**2026-03-24** — Convert 124 remaining LOG string concatenations to parameterized placeholders

**2026-03-24** — Improve logging: convert string concatenation to parameterized placeholders in 10 files

**2026-03-24** — Add MCP audit tools and wiki audit skill design specs

**2026-03-23** — Improve readability: rename single-letter vars, use log placeholders, cleanup

**2026-03-23** — Update ADR-001 to reflect completed implementation + add production architecture doc

**2026-03-23** — Move PageSaveHelper, SaveOptions, VersionConflictException to wikantik-api

**2026-03-23** — Move FrontmatterParser, FrontmatterWriter, ParsedPage, MarkdownLinkScanner to wikantik-api

**2026-03-23** — Convert 11 more MCP tests from TestEngine to stubs

**2026-03-23** — Decouple 13 MCP tool constructors from WikiEngine

**2026-03-23** — Add StubPageManagerTest and test stub conversion plan

**2026-03-23** — Extract 8 manager interfaces from wikantik-main to wikantik-api (ADR-001)

**2026-03-23** — Fix test-jar configuration so stubs are available to MCP tests

**2026-03-23** — test: create StubReferenceManager and convert 7 MEDIUM MCP tests from TestEngine to stubs

**2026-03-23** — test: convert 11 MCP tool tests from TestEngine to StubPageManager

**2026-03-23** — Bump selenide.version from 7.12.1 to 7.15.0

**2026-03-23** — Bump commons-net:commons-net from 3.12.0 to 3.13.0

**2026-03-23** — Bump pac4j.version from 6.3.1 to 6.3.3

**2026-03-22** — Add ADR-001: Extract manager interfaces to wikantik-api

**2026-03-22** — Apply 6 Gang of Four design patterns across core and MCP modules

**2026-03-22** — Sync DB init scripts to staging before deploy

**2026-03-22** — Fix Dockerfile: replace non-existent wikantik-markdown with wikantik-http

**2026-03-22** — Update News page content

**2026-03-22** — Merge pull request #15 from jakefearsd/dependabot/maven/org.codelibs-nekohtml-3.0.3

**2026-03-22** — Merge pull request #16 from jakefearsd/dependabot/maven/mcp-sdk.version-1.1.0

**2026-03-22** — Merge pull request #14 from jakefearsd/dependabot/maven/org.apache.maven.plugins-maven-war-plugin-3.5.1

**2026-03-22** — Merge pull request #13 from jakefearsd/dependabot/maven/org.apache.maven.plugins-maven-dependency-plugin-3.10.0

**2026-03-22** — Add staging deploy workflow for local Docker deployment

**2026-03-22** — Replace full Markdown render with regex link extraction in ReferenceManager

**2026-03-22** — Add git pull step to CI/CD and step-by-step setup guide

**2026-03-22** — Add Docker containerization: Dockerfile, Compose, backup system

**2026-03-22** — Update wiki content: new articles, metadata improvements, and fixes

**2026-03-22** — Update skills, audit reports, and gitignore

**2026-03-22** — Replace legacy CI with self-hosted runner CI/CD pipeline

**2026-03-22** — Add Atom feed servlet and update sitemap with news extension

**2026-03-22** — Add MCP audit, SEO, and cluster management tools with tests

**2026-03-22** — Add test coverage for MetadataOperations, RecentArticlesServlet

**2026-03-22** — Extract page validation checks into Strategy pattern (GoF)

**2026-03-22** — Remove dead JSPWiki syntax handling — Markdown is the only parser

**2026-03-22** — Optimize string handling: hoist Pattern.compile, eliminate regex in hot path

**2026-03-22** — Fix mailto link classification, code block heading detection, and redundant splits

**2026-03-22** — Fix wiki variable rendering when variable is sole child of paragraph

**2026-03-21** — Fix corrupted page and add MCP write guard against serialized JSON responses

**2026-03-21** — Fix concurrency bugs, null safety, dead code, and encoding issues from SpotBugs/PMD audit

**2026-03-21** — Reduce test suite runtime: consolidate duplicates, optimize JDBC tests, remove 3 IT modules

**2026-03-21** — Performance, MCP hardening, and IT test quality improvements

**2026-03-20** — Fix canonical URLs to be fully qualified and harden SemanticWebIT for Cargo

**2026-03-20** — Clean up remaining legacy wiki-syntax references after Markdown migration

**2026-03-20** — Complete Markdown migration: remove legacy wiki-syntax parser and collapse modules

**2026-03-19** — Fix SystemInfo page: proper Markdown tables and updated variable names

**2026-03-19** — Migrate to Markdown-only rendering as the default parser

**2026-03-19** — Reduce duplication in MCP tools and JDBC database classes

**2026-03-19** — Update News page content

**2026-03-19** — Replace Apache feather logo with wikαntik text logo

**2026-03-19** — Rebrand Phase 9: documentation cleanup and final sweep

**2026-03-19** — Rebrand Phase 8: Docker, deployment, and infrastructure

**2026-03-19** — Rebrand Phase 7: visual assets and UI text

**2026-03-19** — Rebrand Phase 6: Java package rename org.apache.wiki → com.wikantik

**2026-03-19** — Rebrand Phase 5: module directory renames and Maven coordinates

**2026-03-19** — Rebrand Phases 1-4: identity, config prefix, resource renames, taglib URI

**2026-03-19** — Remove .mcp.json — leaked API key

**2026-03-19** — Bump mcp-sdk.version from 1.0.0 to 1.1.0

**2026-03-19** — Bump org.codelibs:nekohtml from 2.1.3 to 3.0.3

**2026-03-19** — Bump org.apache.maven.plugins:maven-war-plugin from 3.4.0 to 3.5.1

**2026-03-19** — Bump org.apache.maven.plugins:maven-dependency-plugin

**2026-03-18** — Add warehouse-automation cluster pages to jspwiki-pages

**2026-03-18** — Merge pull request #12 from jakefearsd/dependabot/maven/org.apache.maven.plugins-maven-resources-plugin-3.5.0

**2026-03-18** — Point wiki-article-cluster skill at production MCP endpoint

**2026-03-18** — Merge pull request #10 from jakefearsd/dependabot/maven/org.apache.maven.plugins-maven-jar-plugin-3.5.0

**2026-03-18** — Merge pull request #9 from jakefearsd/dependabot/maven/jakarta.xml.bind-jakarta.xml.bind-api-4.0.5

**2026-03-18** — Merge pull request #6 from jakefearsd/dependabot/maven/mockito.version-5.21.0

**2026-03-18** — Fix MCP transport type for Claude Code compatibility

**2026-03-18** — Add rebrand project plan and update research history

**2026-03-18** — Bump version to 3.1.2: sync Release.java with Maven POM version

**2026-03-18** — Force MCP write_page to always save as Markdown, never .txt

**2026-03-18** — Fix Operations Research articles: rename .txt to .md for Markdown rendering

**2026-03-18** — Fix Main.md: restore Technology cluster links and separate Operations Research section

**2026-03-18** — Add Operations Research cluster and update existing wiki pages

**2026-03-17** — Add Mathematical Foundations of Machine Learning to Technology cluster

**2026-03-17** — Add Embedded AI on Limited Hardware article to Technology cluster

**2026-03-17** — Add Technology cluster articles: LLMs, Future of ML, Foundational Algorithms

**2026-03-17** — Update News.md with latest commit entry

**2026-03-16** — Fix NewsPageGenerator breaking unit tests and add MCP markupSyntax test coverage

**2026-03-16** — Add first-deployment tests for NewsPageGenerator and fix --no-pager bug

**2026-03-16** — Bump org.apache.maven.plugins:maven-resources-plugin from 3.4.0 to 3.5.0

**2026-03-16** — Add NewsPageGenerator to auto-generate News.md from git history

**2026-03-15** — Add News page with development log from last 2 months

**2026-03-15** — Add version-controlled wiki pages: 113 pages including 22 new articles

**2026-03-15** — Move wiki pages to docs/jspwiki-pages/ for version control

**2026-03-15** — Add markupSyntax parameter to write_page MCP tool and fix file extension handling

**2026-03-15** — Bump version to 3.1.1-SNAPSHOT for next development cycle

**2026-03-15** — Release 3.1.0: bump version across all modules

**2026-03-15** — Fix MCP backlinks, sync ReferenceManager init, update IT tests

**2026-03-15** — Bump MCP server version to 2.0.0 with production polish

**2026-03-15** — Move skill to .claude/, update retirement docs, fix table styling and markdown test

**2026-03-15** — Harden MCP endpoint security: multi-key auth, rate limiting, audit logging

**2026-03-15** — Bump mockito.version from 5.20.0 to 5.21.0

**2026-03-15** — Add Monte Carlo retirement planning article to cluster

**2026-03-15** — Update Main wiki page, upgrade 9 dependencies (all tests pass)

**2026-03-14** — Add cluster identifiers to article metadata and export 48 cluster pages

**2026-03-14** — Restructure README with docs/ navigation index and fix stale content

**2026-03-14** — Update wiki-article-cluster skill for new MCP tools and publish 2 articles

**2026-03-14** — Simplify MCP interface: fix Markdown link tracking, add PageSaveHelper, new MCP tools

**2026-03-14** — Add FundamentalsOfProgramming article and document MCP improvement observations

**2026-03-14** — Add Linux for Windows Users cluster (8 pages) and document in research history

**2026-03-14** — Add Spousal Green Card Guide cluster (8 pages) and document in research history

**2026-03-14** — Refine wiki-article-cluster skill based on 4 clusters of real usage

**2026-03-14** — Add semantic wiki design notes and superpowers plan archive

**2026-03-14** — Add research history for personal finance article cluster

**2026-03-14** — Add MCP attachment tools, new prompts, and wire all Phase 2 features (Phase 3)

**2026-03-14** — Add MCP page rename, locking, and completion providers (Phase 2)

**2026-03-14** — Add MCP link graph and wiki health tools (Phase 1)

**2026-03-14** — Add research history documenting MCP article publishing workflow

**2026-03-14** — Add MCP API helper script for manual tool calls

**2026-03-14** — Fix Markdown link ordering bug and remove incompatible MCP outputSchema

**2026-03-14** — MCP API redesign: metadata merge, author attribution, resources, prompts, and 4 new tools

**2026-03-13** — Fix 17 SpotBugs defects: mutable statics, NPE guards, and dead code

**2026-03-13** — Fix 14 high-priority PMD defects: overridable constructors, unsealed classes, null return

**2026-03-13** — Fix 3 code quality defects: null-return APIs, overridable constructor call, ignored return value

**2026-03-13** — Extract McpToolUtils to reduce boilerplate across all MCP tool classes

**2026-03-13** — Remove redundant textToHTML from save path and refactor AbstractReferralPlugin

**2026-03-13** — Add PMD plugin and fix 3 high-priority defects it identified

**2026-03-13** — Fix 4 SpotBugs correctness defects with regression tests

**2026-03-13** — Add centralized SystemPageRegistry, AliasPlugin, and filter system pages from listings

**2026-03-13** — Add MCP access control filter, restore deleted plugins, fix IndexPlugin sort bug

**2026-03-13** — Move frontmatter classes to jspwiki-main, strip frontmatter in Markdown renderer, and fix MCP pages saving as wiki syntax instead of Markdown

**2026-03-13** — Fix MCP async servlet support by enabling async on all wildcard filters

**2026-03-13** — Restore RecentArticles plugin and tests removed in f4ec85056

**2026-03-12** — Fix MCP integration tests, improve test runtime, and enforce failure reporting

**2026-03-12** — Add 60-test MCP integration test suite exercising full protocol stack

**2026-03-12** — Add MCP server module for AI-powered wiki interaction

**2026-03-11** — Fix flaky test suite: Lucene directory race, WikiEventManager global shutdown, parallel test isolation

**2026-03-11** — Remove accidentally committed files (cloudflare config, vim swap)

**2026-03-11** — Remove ~46K lines of legacy code: WYSIWYG, workflow engine, portable dist, low-value plugins, excess i18n

**2026-03-10** — Send admin email notification on new user registration

**2026-03-10** — Add .worktrees to .gitignore


---

## February 2026

**2026-02-18** — Add commented-out Google Workspace OIDC example to SSO configuration

**2026-02-18** — Bump org.apache.maven.plugins:maven-jar-plugin from 3.4.2 to 3.5.0

**2026-02-18** — Bump jakarta.xml.bind:jakarta.xml.bind-api from 4.0.4 to 4.0.5

**2026-02-17** — Align logging-mailhandler with Jakarta Mail 2.x namespace

**2026-02-17** — Add Single Sign-On (SSO) support via OIDC and SAML using pac4j 6.3.1

**2026-02-16** — Implement PageProvider.getAllChangedSince() and push date filtering to the data source

**2026-02-16** — Add WatchService-based filesystem watcher for external content publishing


---

## December 2025

**2025-12-24** — Add backward compatibility for legacy Outcome field names

**2025-12-22** — Merge branch 'master' of https://github.com/jakefearsd/jspwiki

**2025-12-22** — Add workflow approval support for wiki group management

**2025-12-22** — Add workflow approval support for wiki group management

**2025-12-14** — Fix Outcome deserialization causing NPE on startup

**2025-12-14** — Fix incorrect and unclear FIXME comments

**2025-12-14** — Remove unused plainUris field and PROP_PLAINURIS constant

**2025-12-14** — Remove unused templateExists() method from TemplateManager

**2025-12-14** — Remove Hungarian notation (c_ prefix) from static fields

**2025-12-14** — Remove Hungarian notation (m_ prefix) from WikiEngine

**2025-12-14** — Remove Hungarian notation (m_ prefix) from WikiSession

**2025-12-14** — Remove Hungarian notation (m_ prefix) from WikiPage and WikiContext

**2025-12-14** — Remove Hungarian notation (m_ prefix) from DefaultReferenceManager

**2025-12-14** — Remove Hungarian notation (m_ prefix) from WatchDog

**2025-12-14** — Remove Hungarian notation (m_ prefix) from WikiBackgroundThread

**2025-12-14** — Remove Hungarian notation (m_ prefix) from misc utility classes

**2025-12-13** — Remove Hungarian notation (m_ prefix) from tags package

**2025-12-13** — Remove Hungarian notation (m_ prefix) from parser package

**2025-12-13** — Remove Hungarian notation (m_ prefix) from ui package

**2025-12-13** — Remove Hungarian notation (m_ prefix) from workflow package

**2025-12-13** — Remove Hungarian notation (m_ prefix) from auth package

**2025-12-13** — Remove Hungarian notation from auth/authorize package

**2025-12-13** — Remove Hungarian notation from ui/admin package

**2025-12-13** — Remove Hungarian notation from auth/permissions package

**2025-12-13** — Remove Hungarian notation from auth/acl package

**2025-12-13** — Remove Hungarian notation from management package

**2025-12-13** — Remove Hungarian notation from plugin package

**2025-12-13** — Remove Hungarian notation from attachment package

**2025-12-13** — Remove Hungarian notation from providers package

**2025-12-13** — Remove Hungarian notation from url package

**2025-12-13** — Remove Hungarian notation from render package

**2025-12-13** — Remove Hungarian notation from search package

**2025-12-13** — Added class I forgot to include from an earlier refactor.

**2025-12-13** — Remove Hungarian notation from diff package

**2025-12-13** — Remove Hungarian notation from pages package

**2025-12-13** — Remove Hungarian notation from content package

**2025-12-13** — Remove Hungarian notation from references package

**2025-12-13** — Remove Hungarian notation from filters, modules, and ui/progress packages

**2025-12-13** — Remove Hungarian notation from auth/user and tasks packages

**2025-12-13** — Remove Hungarian notation from ajax and variables packages

**2025-12-13** — Remove Hungarian notation from forms package

**2025-12-13** — Remove Hungarian notation (m_ prefix) from field names - first increment

**2025-12-12** — Reduce cyclomatic complexity in high-complexity methods

**2025-12-11** — Apply JDK 21 records and sealed classes modernizations

**2025-12-11** — Apply JDK 21 modernizations and fix authorization for non-JSPWiki permissions

**2025-12-11** — Modernize Java APIs: StringBuffer, Vector, SimpleDateFormat, and logging

**2025-12-11** — Modernize deprecated Java APIs and improve JDK 21 compatibility

**2025-12-11** — Add JDBCPlugin for executing SQL queries and displaying results as HTML tables

**2025-12-09** — Add TTL-based refresh for CachingProvider and RecentArticlesTemplate

**2025-12-09** — Add RecentArticles plugin and REST API for displaying recent wiki articles

**2025-12-08** — Document critical integration test parallelism constraint

**2025-12-08** — Simplify WikiEngine initialization and improve code clarity

**2025-12-08** — Optimize WikiEngine startup with parallel initialization and fix JNDI context issue

**2025-12-08** — Prepare for next development iteration 3.0.7-SNAPSHOT

**2025-12-08** — Release 3.0.6

**2025-12-07** — Fix race condition in CachingProvider.getAllPages() causing incomplete sitemap

**2025-12-07** — Add Cloudflare access logging support to Tomcat configuration

**2025-12-07** — Fix startup race condition in authorization manager

**2025-12-07** — Prepare for next development iteration 3.0.6-SNAPSHOT

**2025-12-07** — Release 3.0.5

**2025-12-07** — Update SLF4J binding from 1.x to 2.x for compatibility

**2025-12-07** — Remove Log4j 1.x bridge dependency and add external logging configuration

**2025-12-03** — Add 60-second cache TTL to enable filesystem-based article updates

**2025-12-03** — Apply Gang of Four design patterns for improved architecture

**2025-12-02** — Release 3.0.2

**2025-12-02** — Fix flaky LuceneSearchProviderTest.testGetIndexedPageNamesReturnsMultiplePages

**2025-12-02** — Optimize page metadata and ACL handling for better performance

**2025-12-02** — Add SEO improvements for better search engine indexing

**2025-12-01** — Fix Preferences reloading properties on every HTTP request

**2025-12-01** — Add missing page detection to Lucene indexer and clean up build

**2025-12-01** — Add docs copying to deploy-local.sh

**2025-12-01** — Rename docs to WikiCase format for wiki compatibility

**2025-12-01** — Add tests for new pages appearing in UnusedPages

**2025-12-01** — Fix UnusedPages not listing newly added pages

**2025-12-01** — Add email configuration documentation


---

## November 2025

**2025-11-30** — Add PostgreSQL local deployment support and fix empty search query error

**2025-11-30** — Longer term planning documents updated.

**2025-11-30** — Update PostgreSQL DDL for compatibility with PostgreSQL 15-18

**2025-11-30** — Update favicons with revised JF monogram design

**2025-11-30** — Bump version to 3.0.1

**2025-11-30** — Fix favicon paths to use absolute URLs via wiki:Link tag

**2025-11-29** — Add JF monogram source SVG to docs

**2025-11-29** — Replace favicons with JF monogram branding

**2025-11-29** — Improve custom properties file discovery with better logging

**2025-11-29** — Add INFO logging for SitemapServlet base URL configuration

**2025-11-29** — Fix jspwiki-portable build phase ordering (MDEP-98)

**2025-11-29** — Add configurable base URL for sitemap generation

**2025-11-29** — Release JSPWiki 3.0.0

**2025-11-29** — Add comprehensive observability system design document

**2025-11-29** — Add robots.txt for search engine crawler guidance

**2025-11-29** — Optimize sitemap.xml for Google: remove ignored fields, add image extension

**2025-11-29** — Creating a plan for OAuth implementation, that along with the sitemap.xml functionality should be the biggest wins for unlocking ease of use for this site.

**2025-11-28** — Remove deprecated Creole wiki markup support

**2025-11-28** — Remove incomplete Finnish (fi) localization

**2025-11-27** — Modernize instanceof checks with pattern matching (Java 16+)

**2025-11-27** — Modernize local variable declarations with var keyword

**2025-11-27** — Override jspc-maven-plugin to use Tomcat 11 jasper

**2025-11-27** — Implement Tier 1 performance optimizations

**2025-11-27** — Update stable dependency versions

**2025-11-27** — Fix HttpMockFactory to support servlet context attributes

**2025-11-27** — Migrate from EhCache 2.10.9.2 to EhCache 3.10.8

**2025-11-27** — Upgrade Lucene from 9.12.3 to 10.1.0

**2025-11-27** — Upgrade minimum Java version from 17 to 21

**2025-11-27** — Update minor dependency versions

**2025-11-26** — Refactor XML database classes to use shared XmlDomUtil utility

**2025-11-26** — Refactor JSPWikiMarkupParser by extracting handler classes

**2025-11-26** — Add comprehensive test coverage for JSPWikiMarkupParser

**2025-11-26** — Add edge case tests for XMLUserDatabase and JDBCUserDatabase

**2025-11-26** — Modernize Date/Time API usage in CalendarTag and CreoleToJSPWikiTranslator

**2025-11-25** — Updated several simple dependencies.

**2025-11-24** — Remove Apache ORO dependency from pom.xml files

**2025-11-24** — Replace Apache ORO regex library with Java built-in regex

**2025-11-24** — Modernize legacy collections and remove deprecated constructors

**2025-11-23** — Update dependencies: commons-lang3 3.20.0, commons-io 2.21.0, Selenide 7.12.0

**2025-11-23** — Fix PropertyReader warning for Log4j2 lookup syntax

**2025-11-23** — Fix flaky AnonymousViewIT.anonymousReaderView test

**2025-11-23** — Update Log4j2 to 2.25.2 and Lucene to 9.12.3

**2025-11-23** — Remove Chinese (zh_CN) wiki pages module

**2025-11-23** — Add SitemapServlet configuration to all IT test modules

**2025-11-23** — Remove optional search providers and deprecated WikiEngine accessors

**2025-11-23** — Remove Chinese (zh_CN) language support

**2025-11-23** — Remove orphaned RPC/Atom/RSS references from codebase

**2025-11-23** — Remove legacy 210 template to simplify codebase

**2025-11-23** — Remove XML-RPC, 2.10 Adapters, and RSS/Atom/Weblog features

**2025-11-23** — New feature description that needs refinement to be completed.

**2025-11-23** — Implement SitemapServlet for search engine indexing

**2025-11-23** — Updated this war to drive a local deployment for integration testing.

**2025-11-23** — Updated the plan for sitemap to include changes for how URLs are now generated.

**2025-11-23** — Add documentation for JSPWiki Markdown internal link syntax

**2025-11-23** — This is a simple update to keep testing running after failure so that my coding agents get more test failure feedback when something does go wrong.

**2025-11-23** — This is the plan for the sitemap implementation that I will try executing with help from Claude Code.

**2025-11-23** — Updating tests to make them more robust in the face latency for integration tests.  This is all to help use the ShortViewURLConstructor to make URLs cleaner in the build up to adding a proper sitemap function to the wiki for search indexing.

**2025-11-22** — Fix ACL deprecation warnings by migrating to new API interfaces

**2025-11-22** — Fix deprecation warnings across multiple modules

**2025-11-22** — Fix flaky integration tests in Selenide page objects

**2025-11-22** — Fix deprecation warnings in TextUtil

**2025-11-22** — Fix deprecation warning in ClassUtil

**2025-11-22** — Fix deprecation warnings in comparator classes

**2025-11-22** — Fix flaky integration test in searchFor method

**2025-11-22** — Fix flaky integration test in clickOnShowReaderView

**2025-11-22** — Upgrade Cargo Maven plugin to use Tomcat 11.0.14

**2025-11-22** — Add local configuration and documentation files

**2025-11-22** — This update adds the ability to prefer Markdown pages when they are present on the disk, but lacks a good way of adding the links. Rendering is tested and seems to work well both in automation and manually by downloading Google Docs as Markdown and pasting them onto the file system they are correctly rendered by the wiki.

