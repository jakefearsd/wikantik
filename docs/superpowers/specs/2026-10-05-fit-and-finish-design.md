# Fit-and-Finish + Code-Truth Documentation Pass — Design

**Date:** 2026-10-05 · **Status:** approved design, pre-plan · **Baseline:** `main` @ 2.4.53-SNAPSHOT (`ca4edeca60`)

## 1. Intent

The codebase is mature and is about to see little development for one to two months. Goal: leave it in a
state that needs no attention during the lull, and make the GitHub-visible documentation (README, root
`.md` files, `docs/`) accurate *as verified against the code* for two audiences — **users** (people who read
and edit the wiki) and **admins** (people who install, configure, operate, secure and integrate it) — with a
smaller developer section.

**Success criteria**
- No known defect is left open that could bite during the lull; every open GitHub issue is either fixed,
  closed-as-fixed, or explicitly deferred with a reason recorded on the issue.
- The project's own rules hold across main code (no swallowed exceptions, no deprecated dead code, no
  undefined CSS tokens used by the UI).
- Every doc under `docs/{user,admin,developer}/` makes only claims that were checked against source
  (`web.xml`, `ini/wikantik.properties`, `bin/`, migrations, SPA routes, tool registries).
- An index at `docs/README.md` lets an admin or a user find the right doc in one click; README is a front
  door (~150 lines), not a manual.
- Full gate green: `bin/run-tests.sh --parallel 4`, `-Pcoverage`, `pmd:check -Pcomplexity-gate`, ESLint,
  vitest, and a markdown link check with zero broken relative links.

**Decisions taken in brainstorming (user-approved 2026-10-05)**
- Code scope: hygiene + known defects (not hygiene-only; no UX walk-through beyond the dark-theme check).
- Docs layout: audience folders + index; historical docs to `docs/archive/`.
- Shipped default pages: rewrite English, prune obsolete pages, drop es/ru/zh_CN.
- Root clutter: the keep/move/delete table in §3.10 is approved as written.
- #62 (`mcp_content` scope tier) is **out of scope** — it is an L-sized new feature touching every admin
  MCP call, not a defect; wrong risk profile for a pre-lull pass.
- Production-touching steps (prod page push, prod KG re-materialisation, patch release) are **confirmed
  with the user at the time**, not pre-authorised.

## 2. Recon findings that shape the design

- **Security audit 2026-08-19 is fully closed in code.** #63 (KG entities from restricted pages leaking to
  the anonymous ontology) and #64 (connector credential not bound to endpoint host) were both fixed by
  `8d1b6a8c98` (shipped v2.4.26) but the issues were never closed. Residuals: #64 compares host only (not
  scheme/port); #63 needs a prod KG re-materialisation because legacy `kg_nodes` rows with null
  `source_page` now fail closed (hidden from the public ontology).
- **Shipped default pages are served from `docs/wikantik-pages/`, not the `wikantik-wikipages` jar.**
  `bin/deploy-local.sh` serves that directory and the Dockerfile copies it to `/var/wikantik/pages/`. The
  `wikantik-wikipages-en` jar is on the WAR classpath only so `DefaultSystemPageRegistry` can enumerate
  *system page names* (anchor: `About.md`; every sibling `*.md` becomes a write-protected system page).
  The prod page store is a third, divergent corpus. A help-page change therefore touches the en jar, the
  `docs/wikantik-pages/` copy, and prod.
- es/ru are Maven modules referenced only by `wikantik-bom`; zh_CN is not even a module. Nothing consumes
  the `build-wikipages-zips` output despite `src/site/markdown/index.md` claiming it is extracted on first
  deploy.
- 71 docs mix live guides, runbooks and historical design notes; ~16 are historical; README is 691 lines
  and its doc index mis-describes several docs; there are real coverage gaps (no user guide for the editor,
  search, graph viewers; no admin-panel, security, MCP-onboarding, derived-pages, drift or insights guide).

## 3. Workstream A — Code fit-and-finish

Behaviour-preserving except the explicit fixes. Fixes are test-first (project TDD rule).

| # | Item | Scope | Size |
|---|---|---|---|
| A1 | **Swallowed exceptions** | ~52 catch sites in main code that are empty/comment-only or bare return/continue. Unexpected-failure sites (`KnowledgeMcpInitializer`, `RestServletBase`, `FrontmatterValidateResource`, `ConnectorConfigService`, `Preferences`, `RenderingSubsystemFactory`, `SearchSubsystemFactory`, `CookieAuthenticationLoginModule`, `ExpectedCtrCurveParser`, IT `McpTestClient`) get `LOG.warn` with context. Expected-parse sites (`NumberFormatException`, `DateTimeParseException`, `NoSuchPrincipalException` …) get `LOG.debug` + a one-line comment stating why the fallback is correct. Log levels must not turn expected bad input into WARN noise. | M |
| A2 | **Undefined CSS tokens** | 15 custom properties used but never defined (`--surface`, `--surface-secondary`, `--border-color`, `--color-border`, `--color-muted`, `--color-text-muted`, `--color-surface`, `--color-surface-alt`, `--color-bg-subtle`, `--bg-hover`, `--bg-base`, `--bg-warning-subtle`, `--accent-soft`, `--radius`, `--shadow-lg`). Preferred fix: replace each use with the existing canonical token; define an alias only where a component family relies on the name. Both themes. Verified in a browser in light + dark. | S–M |
| A3 | **Deprecated code + warnings** | Remove `SSOAutoProvisionService.provisionIfNeeded(String, UserProfile)` after migrating its 6 test call sites; clear test-source deprecation warnings (`PageCanonicalIdsDaoTest`, `JDBCUserDatabaseTest`, `GroupPermissionTest`, `SystemPageRegistryTest`, `LuceneMissingPageSweepObservabilityTest`); pin `properties-maven-plugin` / `exec-maven-plugin` versions in the 5 IT poms (in pluginManagement). | S |
| A4 | **Inherited FIXME/TODO triage** | Fix the real ones: `AttachmentServlet` deletes the partially-uploaded file on failure; `VersioningFileProvider` logs its swallowed errors; `AdminAgentGradeAuditServlet`/resource pages beyond 1000; resolve the stale comment at `ContextualDiffProviderTest:168`. Delete stale JSPWiki-era FIXME/TODO comments whose concern no longer applies (each deletion is a judgement — when in doubt keep it and convert to an accurate comment). | S–M |
| A5 | **PMD complexity burn-down** | Timeboxed. Order: ExcessiveParameterList-only entries via parameter objects (`AsyncEntityExtractionListener`, `KnowledgeWiringHelper`); then GodClass splits with a clear seam, starting with `LuceneBm25ChunkIndex` (#55), then `McpToolUtils`, `ClassUtil`, `ToolsOpenApiServlet`. Each removed entry comes out of `build-support/pmd-complexity-baseline.properties` (entries only ever leave). No behaviour change; existing tests must pass unmodified except for constructor plumbing. | M–L |
| A6 | **Coverage floors** | Add tests then raise floors (never above 0.90) for the lowest modules: `wikantik-api` (0.71), `wikantik-jdbc` (0.80), `wikantik-ingest` (0.81), `wikantik-connectors` / `wikantik-extract-cli` (0.83). Floor = new measured level rounded down. Tests must assert behaviour (no vacuous async guards). | M–L |
| A7 | **Security close-out** | #64 hardening: credential binding compares scheme + host + port (test first in `ConnectorConfigServiceTest`). Close #63 and #64 on GitHub citing `8d1b6a8c98`/v2.4.26; #63 closes only after the prod re-materialisation (D2) or with that step recorded on the issue. | S |
| A8 | **#47 small items** | Entrypoint regression guard, image version pinning, `CapabilitiesProvider` rejection vitest, stale `CF-Connecting-IP` javadoc. Issue updated with what landed; remaining items stay. | S |
| A9 | **Dependency sweep** | Re-run the OSV.dev + versions-plugin sweep (baseline 2026-09-22); take patch/minor bumps that pass the gate; log in `dependency-upgrade-log.md`. Known traps: apache parent 39, libthrift 0.24 (held). | S |
| A10 | **Root clutter** | Table below. | S |
| A11 | **Issue hygiene** | #49 (SSOLoginIT flake) and #44–#46 (cloud, blocked on billable credentials) get a comment recording why they are deferred; no code. | XS |

### 3.10 Root clutter (approved)

| Action | Items |
|---|---|
| **Delete** | `.asf.yaml`, `KEYS`, `UPGRADING`, `ReleaseNotes`, `Jenkinsfile`, `fulltestsuite.sh`, `mcd`, `deep-research-architect.skill`, `tools/` (`refactor_m_prefix.py`), `testfiles/`, `wikantik-wikipages/MarketRecoveryCoefficients.md` (only after confirming a copy exists in `docs/wikantik-pages/`; otherwise move it there), `wikantik-wikipages/.corepages` |
| **Untrack** | `.gemini/` (`git rm --cached`; already gitignored, content duplicated in `docs/superpowers/deep-research-architect/`) |
| **Move** | `IndexingSupport.md` → `docs/admin/`; `AGENT.md` → `docs/agents/math-authoring.md`; `audits/*` → `docs/audits/`; `mcp_call.sh`, `bkupcmd.sh` → `bin/`; `scripts/wiki2markdown.py` (+ test) → `bin/` (drop `scripts/`); `articles_to_create.csv` → `docs/`; `docs/adrs/001-*` → `docs/adr/` (renumber to the legacy slot, merge README, delete `docs/adrs/`) |
| **Keep** | `GEMINI.md`, `ROADMAP.md`, `clients/`, `eval/`, `loadtest/`, `marketing/`, `src/` (Maven site sources), `remote.env.example`, `osv-scanner.toml`, `.claude/skills/` (un-ignore in `.gitignore` to match reality), `docs/clusters/`, `docs/agents/` |

Every move updates its referrers (CLAUDE.md, README, ProjectReference, specs/plans, scripts).

## 4. Workstream B — Code-truth documentation

### 4.1 Layout

```
README.md                    front door (~150 lines)
docs/README.md               index: User · Admin · Developer · Reference · Archive
docs/user/                   reading, editing, search, clusters, citations, comments, personal zone …
docs/admin/                  install, configure, operate, secure, integrate
docs/admin/templates/        PrivacyPolicy, TermsOfService (operator templates)
docs/developer/              building, testing, architecture, code quality, CI, load testing
docs/archive/                historical design notes, each with a "Historical — not current behaviour" banner
docs/ConfigurationReference.md   stays (generated; path hard-coded in bin/config-reference.sh + ConfigReferenceRegressionTest)
docs/adr/  docs/agents/  docs/superpowers/  docs/wikantik-pages/  docs/audits/  docs/clusters/  docs/brand/  docs/marketing/   unchanged
```

### 4.2 Placement of existing docs

| Destination | Docs |
|---|---|
| `user/` | CommentsAndMentions, Frontmatter, MarkdownLinks (→ extended into Linking: `[text](Page)`, `[[wikilink]]`, `![[embed]]`), MathematicalNotation, PersonalZone |
| `admin/` | ApiKeys, AuditLog, AwsAccountSetup, AzureAccountSetup, GcpAccountSetup, BackupAndRecovery, CloudDeployment, Connectors, CostTiers, DatabaseMigrations (merge of DatabaseUpdates + ProductionDBWorkflow), DockerDeployment (absorbs production-container-architecture), GettingStartedGuide, HubDiscovery, IndexRebuild, IndexingSupport, KgInclusionPolicy, LoggingConfig, OntologyManagement, PageOwnership, PostgreSQL (merge of PostgreSQLLocalDeployment + DevelopingWithPostgresql + RelationalUserDatabase), RagContextBundle, RetrievalQuality, ScimProvisioning, SendingEmailFromTheWiki, SeoAndCrawling (session-record half trimmed; absorbs the live findings of Sitemap/SitemapOptimization), SingleSignOn, WikantikOperations (absorbs ObservabilityDesign + the admin runbooks from ProjectReference), content-feedback-loop stays in `agents/` |
| `developer/` | Building (MvnCheatSheet rewritten, non-duplicative of CLAUDE.md), CodeQuality (numbers refreshed to the current ratchets), ci-cd (renamed from ci-cd-step-by-step), LoadTesting, ScalingCharacterization (dated study, kept as perf reference), NewUI → FrontendArchitecture, ProjectReference (design-doc status half), dependency-upgrade-log (update `build-support/versions-stable-rules.xml` + `.github/dependabot.yml` refs) |
| `archive/` | ArchitectureCritique, FullOAuth, OAuthImplementation, full_rebrand_project, jsp-dead-code-catalog, KnowledgeGraphRerank, migration-1.0-to-1.1, PerformanceEvaluation, RefactorToPatterns, research_history, semantic_wiki_thoughts, Sitemap, SitemapOptimization, complete_markdown_migration |

Merged-away files are deleted (git history keeps them); no redirect stubs. Relative links one level deeper
(`../bin`, `../deploy`, `../loadtest`) are rewritten.

### 4.3 Code-truth verification (every doc in user/admin/developer)

For each doc, each concrete claim is checked against its source of truth and fixed or removed:

| Claim type | Source of truth |
|---|---|
| HTTP paths, servlet names | `wikantik-war/src/main/webapp/WEB-INF/web.xml`, `SpaRoutingFilter.SPA_EXACT`, SPA routes in `wikantik-frontend/src/main.jsx` |
| Property keys + defaults | `ini/wikantik.properties` (+ `wikantik-mcp.properties`, `wikantik-tools.properties`) |
| Scripts + flags | `bin/*.sh --help` |
| Schema, migration numbers | `bin/db/migrations/` |
| MCP tool lists/counts | `McpProtocolIT.EXPECTED_TOOLS`, `wikantik-mcp-instructions.txt`, `KnowledgeMcpInitializer`, `McpServerInitializer` |
| Connector types | `ConnectorConfigCodec.UI_TYPES` |
| Versions (Java, Tomcat, PG, Node) | root `pom.xml`, `Dockerfile`, `bin/deploy-local.sh`, `docker-compose.yml`, `wikantik-frontend/package.json` |

Volatile numbers (tool counts, servlet counts) are stated in exactly one place per audience and elsewhere
linked, not repeated — repetition is how README and CLAUDE.md drifted (35 vs 36 `/api` servlets).
Known drift to fix: ApiKeys "known gap" (V058 exists), AzureAccountSetup (no `deploy/azure` — say so),
ProductionDBWorkflow PENDING items, README index mis-descriptions, CodeQuality's June numbers.

### 4.4 New docs (gaps)

- **User:** `Editing.md` (editor, toolbar + shortcuts, live preview, wikilink completion, embeds, @mentions,
  unlinked-mention rail, attachments, page templates, drafts, structured frontmatter editor), `Search.md`
  (quick switcher/command palette, full-text search, facets, what query syntax actually works),
  `Reading.md` (TOC, breadcrumbs, backlinks, link previews, similar pages, history/diff, derived-page
  banner, Page Graph vs Knowledge Graph viewers), `ClustersAndHubs.md` (author view of cluster
  declaration, multi-membership, sub-clusters), `Citations.md` (`cite://` markup, staleness),
  `ObsidianImportExport.md`, `AccountAndLogin.md` (login, SSO, reset/forced change password, API keys
  for users).
- **Admin:** `AdminPanel.md` (tour of every `/admin` section with what each is for), `Security.md`
  (consolidated: authn options, policy grants, groups, page ACLs, API-key scopes, rate limiting, SSRF
  egress, render-cache viewer isolation, bootstrap admin, JDBCPlugin), `McpAgents.md` (connecting agents to
  both MCP endpoints + `/tools/*`: keys, scopes, sessions, tool catalogue by link), `DerivedPagesAndIngest.md`
  (`POST /api/ingest`, `bin/ingest-documents.sh`, reflow, `/admin/derived/*`), `DriftDashboard.md`,
  `ContentIntelligence.md` (`/admin/insights/*`).
- **Developer:** `Architecture.md` (module map + dependency rules, from README/CLAUDE.md), `Testing.md`
  (unit/IT/Postgres test rules, run-tests.sh, flake handling).

CONTRIBUTING.md is updated to point at `docs/developer/`.

### 4.5 README

Keep: tagline, badges (verified), "What is Wikantik", condensed key capabilities (one line each, linking
to docs), "Why Wikantik" comparison table, Mermaid architecture diagram (re-verified), a ≤10-line quick
start, links to `docs/README.md` sections, ROADMAP, CHANGELOG, license. Move out: pgvector OS install →
`admin/PostgreSQL.md`; Docker/quick-start detail → GettingStartedGuide/DockerDeployment; module table →
`developer/Architecture.md`; scaling section → ScalingCharacterization; security list →
`admin/Security.md`; MCP integration → `admin/McpAgents.md`; building → `developer/Building.md`.

### 4.6 Shipped default wiki pages

| Action | Pages (en) |
|---|---|
| **Delete** | LeftMenu, LeftMenuFooter, MoreMenu, TitleBox, RecentArticlesTemplate (after confirming the manager tolerates a missing template), CSSRibbon, CSSStripedText, CSSThemeCleanBlue, CSSThemeDark, CSSBackgroundPatterns, CSSInstagramFilters, CSSPrettifyThemePrism, CSSPrettifyThemeTomorrowNightBlue, ApprovalRequiredForUserProfiles, CopyrightNotice, WikiWiki, EditFindAndReplaceHelp (unless the CodeMirror editor exposes find/replace — then rewrite), InstallationTips, Community |
| **Rewrite** (Markdown, current React UI) | About (stays: registry anchor), Main, EditPageHelp, TextFormattingRules, SearchPageHelp, LoginHelp, SystemInfo (every `[{$var}]` verified to resolve), OneMinuteWiki, WikiName (or fold into TextFormattingRules), PageAlias (around frontmatter `aliases:`) |
| **Keep, light touch** | PageIndex, RecentChanges, FullRecentChanges, UnusedPages, UndefinedPages, RejectedMessage (verify `$message` var), WikiEtiquette, SandBox |

- Each change lands in both `wikantik-wikipages/en/src/main/resources/` and `docs/wikantik-pages/`
  (preserving the latter's frontmatter: `cluster`, `canonical_id`). Rewritten help pages link to the GitHub
  docs for depth rather than duplicating them.
- Remove `es`, `ru` modules (`wikantik-wikipages/pom.xml`, `wikantik-bom/pom.xml`), delete `es/`, `ru/`,
  `zh_CN/`; remove the unused `build-wikipages-zips` profile + `assembly/zip.xml` + stale site `index.md`
  claims + RAT excludes that referenced them.
- Fix the dependent ITs: `McpSystemPageProtectionIT` sample system page (`LeftMenu` → a surviving system
  page), `SitemapIT:114-117` assertions; keep `MemoryProfiling`'s page dir valid.
- Prod page store: deletions + rewrites pushed via MCP **only with user confirmation at the time** (D1).

## 5. Sequencing

1. **Phase 0 — paths first.** A10 root clutter + B layout moves/merges-as-moves + link rewrites, one
   commit per concern, link check green. Everything after this phase edits files at their final paths.
2. **Phase 1 — parallel.** Workstream A items (A1–A9, A11) and Workstream B content (4.3 verification,
   4.4 new docs, 4.5 README, 4.6 wiki pages) run as independent subagent task groups, partitioned so no
   two groups edit the same file. Code tasks are TDD; doc tasks each end with a claim-verification table in
   the task report.
3. **Phase 2 — gate + review.** Full gate (§1), browser pass (dark theme + rewritten help pages on the
   local deployment), a doc-drift spot audit by a fresh agent that re-derives a sample of claims from
   source, then `requesting-code-review` over the whole range.
4. **Phase 3 — production (each confirmed with the user).** D1 push wiki-page changes to prod; D2 KG
   re-materialisation, then confirm `/sparql` entity count recovered and restricted entities stay
   excluded, then close #63; D3 patch release via `bin/cut-release.sh`.

## 6. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Link rot from the move | Phase 0 runs a relative-link checker over every tracked `.md` (excluding `docs/wikantik-pages/`, then separately grep the corpus for `docs/` links); must be zero. |
| A1 log-level noise in prod | Expected-input sites log at DEBUG; WARN only for genuinely unexpected failures. |
| A5 refactors changing behaviour | Pure structural moves; existing tests unchanged; complexity-gate + full IT gate. |
| Deleting system pages changes `DefaultSystemPageRegistry` membership | Intended; ITs adjusted; About.md anchor preserved. |
| Subagent fabrication of doc claims | Each doc task reports its claim→source table; Phase 2 independent re-derivation sample. |
| Prod corpus divergence | Prod help-page edits planned from the live index (`list_pages_by_filter`), never assumed equal to the repo copy. |

## 7. Out of scope

#62 `mcp_content` tier; #44–#46 cloud tasks (need billable credentials); #49 (needs the next failure log);
a full UX walk-through beyond the dark-theme check; translations; publishing the GitHub docs into the
wiki corpus; pruning old `eval/` spike data.
