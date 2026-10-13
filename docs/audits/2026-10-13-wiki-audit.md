# Wiki Audit — 2026-10-13 (accuracy and omissions, Wikantik admin/dev content)

**Scope:** every production page about Wikantik itself — the `wikantik-development`, `wikantik-platform`
(+ `rag-service-roadmap`) and `agent-cookbook` clusters, the bundled help pages, and Wikantik pages
filed in other clusters (Docker, SSO, observability, backup incident runbooks, content-pipeline).
Every Wikantik-specific claim was checked against the code at release 2.4.56 (main 2.4.57-SNAPSHOT).

**Pages audited:** 151 | **Edited on prod:** 136 | **New pages:** 27 | **Left as accurate or generic:** 13 |
**Needs a human:** 3 (TermsOfService, PrivacyPolicy, README)

Method: ten parallel audit agents, each verifying against code / `ini/wikantik.properties` / web.xml /
migrations / `bin/` and editing prod through the admin MCP `update_page` (optimistic locking); repo
mirrors in `docs/wikantik-pages/` were then synced byte-for-byte to prod for every page touched
(95 files). Demand check D1 (`list_retrieval_queries`, 90 days, avg results ≤ 1) found no real
under-served queries.

## Headline findings

1. **An account named `wiki-content-agent-test` rewrote 584 prod pages on 2026-08-15/16.** In the Wikantik
   clusters it did real damage:
   - Nine agent-cookbook runbooks lost `type: runbook` and their `runbook:` frontmatter block (where the
     steps live). **Restored** from git HEAD and re-verified: FindingTheRightMcpTool, CitingAWikiPage,
     DebuggingFailingIntegrationTests, ExploringAModulesApiSurface, WhatToDoWhenANonExistentFunctionIsCited,
     PlanningAMigrationChange, InterpretingHybridRetrievalMetrics, RunningTheRetrievalQualityHarness,
     HandlingEmbeddingServiceOutages.
   - Short Wikantik pages were inflated with invented features. **Rewritten from code:** AdminSecurityUi
     (RBAC grid, OAuth token management), DatabaseBackedPermissions (PostgreSQL RLS, OPA), UserProfileBio
     (Markdown bios, profile endpoints), AttachmentManagement (thumbnails, `attachment:` syntax),
     KnowledgeAdminUi (D3/SPARQL workbench), GraphProjector, McpAuditTools, McpIntegrationTestFix,
     FrontmatterConventions, MarkdownLinks, CurationTest, AnsweringRestApiQuestions, ContentProgramPolicy,
     WikiAuditSkill, ADR-001 (Neo4j/gRPC/cost figures), WikiToMarkdownConverter (non-existent AST pipeline),
     ContinuousIntegration (Liquibase, nightly CI), ContentPipelineHub + 3 members.
   - The ~530 non-Wikantik pages it touched were **not** reviewed.
2. **Security defect found and fixed (code):** SCIM could write the `Admin` group (`PUT`/`PATCH`/`DELETE
   /scim/v2/Groups/Admin`), so the SCIM token holder could make anyone an administrator or remove every
   administrator. Now refused with 403 and audited DENIED (commit `6d20532a0d`). **Not deployed.**
3. **Data-integrity defect found and fixed (code):** approving a KG `new-edge` proposal reset existing
   endpoint nodes to `concept` and wiped their provenance/properties (commit `9edc32c0be`). The
   `new-node` path has the same overwrite and needs a design decision before the ~19.6k-proposal replay.
4. **Prod pages carried long-stale operational facts:** `jspwiki.*` keys, the removed XML user store,
   `{SSHA}` hashing, Java 11/17 and Tomcat 9 minimums, `/Wikantik` context path, `-Dmaven.test.skip`,
   MySQL, hand-written DDL, `/observability/metrics`, nomic-768 embeddings, `inmemory` dense default,
   admin MCP "27 tools" (29), "50 tools total" (52), the deleted KG rerank as current, the retired
   GraphProjector as current, a deleted `hubs:` field, an invented "local ONNX fallback encoder".

## New pages (27)

The generated **WikantikConfigurationReference** had never been published (the generator also had a
`$`-pairing bug that rendered ~20 lines as math — fixed, commit `44bdf572e3`). The 26 operator/developer
guides in `docs/admin/` and `docs/developer/` with no wiki page were published as wiki pages after
verification against code (17 errors found in the repo guides along the way, fixed in both places):

| Area | Pages |
|---|---|
| Security & identity | WikantikSecurityGuide, WikantikSingleSignOn, WikantikScimProvisioning, WikantikApiKeys, WikantikAuditLog, WikantikPageOwnership, WikantikAdminPanel |
| Content & knowledge ops | WikantikConnectors, WikantikDerivedPagesAndIngest, WikantikOntologyManagement, WikantikDriftDashboard, WikantikHubDiscovery, WikantikContentIntelligence, WikantikRagContextBundle, WikantikSeoAndCrawling, WikantikIndexingSupport |
| Operations & development | WikantikOperations, WikantikGettingStarted, WikantikBackupAndRecovery, WikantikDatabaseMigrations, WikantikCloudDeployment, WikantikCloudCostTiers, WikantikTestingGuide, WikantikCodeQuality, WikantikFrontendArchitecture, WikantikLoadTesting, WikantikConfigurationReference |

All 27 are linked from WikantikDevelopmentHub (sections VI and VII); `verify_pages` reports 0 broken
links, 0 metadata and 0 SEO issues.

## Per-cluster summary

| Area | Audited | Edited | Notes |
|---|---|---|---|
| Bundled help pages | 12 | 8 | 7 via `pages-push` (MCP refuses system pages), About via MCP; bundled sources in `wikantik-wikipages/en` corrected for fresh installs; retired `hubs:` removed from prod TextFormattingRules; non-existent `TableOfContents` and `HubSet` plugin names corrected |
| Operations / deploy / build | 14 | 12 | DevelopingWithPostgresql, WikantikOnDocker, PostgreSQLLocalDeployment, MvnCheatSheet, ContinuousIntegration, SearchIndexAndKnowledgeGraphRebuild (dead CLI flags), HostDocker1 |
| Security / auth | 10 | 8 | TermsOfService, PrivacyPolicy left for a human |
| MCP / agent surfaces | 13 | 13 | counts re-derived from `McpProtocolIT.EXPECTED_TOOLS` / `KnowledgeMcpInitializer` |
| Search / retrieval | 13 | 10 | `rrf.bm25-weight` 1.5, `mean_top_3`, `lucene-hnsw` default, real metric names |
| Knowledge Graph | 14 | 14 | `kg_proposals`, 4 provenance values + tier, judge step, KgInclusionPolicy CLI behaviour |
| Content model / authoring | 15 | 15 | FrontmatterSchema-accurate field rules; link prefixes that never existed removed |
| Architecture / dev practice | 21 | 17 | module list = root pom (24), timeline to 2.4.56 |
| Designs / hubs / roadmap | 22 | 22 | dated status notes; designs left intact; hubs rebuilt to link members |
| Off-cluster Wikantik pages | 17 | 17 | DockerDeployment, RelationalUserDatabase rewritten; Incident* backup runbooks corrected |

## Code and repo-doc changes (all on main)

- `6d20532a0d` security(scim): refuse every SCIM write to the Admin group.
- `9edc32c0be` fix(kg): new-edge approval never rewrites an existing endpoint node.
- `44bdf572e3` fix(config-ref): escape prose `$` in generated descriptions.
- `5cb8ad8495` MCP instructions match the real tool input schemas (`slug`/`slugs`, optional `content`,
  no `changeNote` on write tools); drift check gains `NON_TOOL_TOKENS`.
- `6faabaaf22` stale comments (backpressure default 390, DB_APP_USER default, HubSet usage, Tika 3.3.2).
- Repo guides corrected: Security, ApiKeys, AuditLog, SingleSignOn, ScimProvisioning, PageOwnership,
  AdminPanel, RagContextBundle, HubDiscovery, SeoAndCrawling, WikantikOperations, GettingStartedGuide,
  BackupAndRecovery, DatabaseMigrations, CloudDeployment, CostTiers, FrontendArchitecture, LoadTesting,
  Testing, PostgreSQL; CLAUDE.md testbot recipe (working CryptoUtil classpath + Admin group row).

## Needs a decision

1. **Release.** The SCIM Admin-group fix (security) and the KG edge fix are on main only; prod (2.4.56)
   is still exposed. The wiki's SCIM/Security guides describe the 2.4.56 behaviour until a release ships.
2. **KG `new-node` approval overwrites an existing node's type/provenance/properties** (tier is kept).
   Choose first-writer-wins or overwrite-only-machine-tier before running the proposal replay.
3. **TermsOfService / PrivacyPolicy** are live with template placeholders (`[Site Name]`, `[Email Address]`,
   …) and claims that do not match the product (GitHub/Facebook login, analytics cookies, CC BY-SA);
   Google's OAuth consent screen points at PrivacyPolicy.
4. **README** (GenAI tools page) documents a separate Python project (`~/source/genaitools`): delete, move,
   or label.
5. **Content-pipeline cluster** documents external repos (aipublisher, aidiscovery, blogger-agent) inside a
   Wikantik cluster.
6. **jakemon publishes the Incident\* backup runbooks** from `~/source/jakemon/docs/runbooks/`; the prod
   fixes (e.g. the wrong `/var/backups/wikantik/` path) revert on its next publish unless made there.
7. **Code behaviours documented but not fixed:** `MailUtil` standalone session ignores the documented
   STARTTLS/timeout defaults; `/admin/audit?actor=` can never match (`actor_id` never written); admin-side
   API-key revocation is not audited; audit CSV export capped at 1000 rows; `kg-rebuild.sh --reset-kg`
   also deletes human-approved nodes; `ai-reviewed` provenance has no writer; news-sitemap entries do not
   actually require tags; GHCR image answers anonymous pulls while docs call it private.
8. **Stale verification stamps** (`verified_by: gemini-cli-mcp-client`) remain on a few rewritten pages
   (FrontmatterConventions, CryptographyFundamentals, UserProfileBio).

## Not covered

The ~1,250 non-Wikantik pages (general-knowledge clusters), including ~530 written by the test account.
