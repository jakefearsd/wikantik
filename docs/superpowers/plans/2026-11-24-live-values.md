# Live Values Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `{{wiki:<name>}}` tokens in a page body or frontmatter `summary` are served as current wiki facts on every output surface, while the stored source and every write path keep the raw token.

**Architecture:** A pure token expander in `wikantik-api` (no I/O) plus a `LiveValues` service in `wikantik-main` that computes the public-view values from the structural index, caches them per index revision and exposes a `generation()` hash. Substitution is applied on read at five shared points (render, SSR head, descriptors, frontmatter metadata cache, reader view) and at the named stragglers; persisted indexes store raw and serve expanded.

**Tech Stack:** Java 25, Maven, JUnit 5 + Mockito, PostgresTestDb, flexmark, React + vitest, Cargo/Selenide ITs.

**Spec:** `docs/superpowers/specs/2026-11-24-live-values-design.md`

## Global Constraints

- Token grammar: `{{wiki:<name>}}`, `{{wiki:<name>|round=<N>}}`, `{{wiki:<name>|round=<N>|else=<text>}}`; name `[a-z][a-z0-9./-]*`; args in any order.
- `round=N`: floor to a multiple of N, append `+` iff the true value exceeds the floor. 1142/100 → `1,100+`; 1100/100 → `1,100`.
- Numbers format with `,` grouping, `Locale.US`, never the viewer locale.
- Only `summary` expands in frontmatter. Body tokens inside fenced code, indented code and inline code spans never expand.
- Unknown name → verbatim. Known name with no wired source → `else` text, else empty string. Private cluster ≡ unknown cluster (verbatim).
- v1 vocabulary: `articles`, `pages`, `clusters`, `subclusters`, `cluster.<path>.pages`, `tools.knowledge`, `tools.admin`, `tools.openapi`, `version`. No `updated`.
- "Public" = a guest session may `view` the page (`AuthorizationManager.checkPermission`, which applies `PrivateClusterGate` + ACLs). `articles` = public, not `type: hub`, not `SystemPageRegistry.isSystemPage`. Derived and agent pages count. Multi-cluster pages count in each listed cluster; a hub is not a member of its own cluster.
- Values are computed per structural-index revision, never per request and never per viewer.
- Config key `wikantik.liveValues.enabled` (Boolean, default `true`): declared in `wikantik-main/src/main/resources/ini/wikantik.properties` first; `false` → every token served verbatim.
- No new `WikiEngine#getManager` callers (DecompositionArchTest R-2). Register with `engine.serviceRegistry().put( LiveValues.class, … )`; consumers receive it by constructor/deps, or via `serviceRegistry().get( LiveValues.class )` at an existing wiring point. Absent service → behave as disabled (verbatim).
- Write/edit surfaces read raw `getPureText` and never call `LiveValues`.
- Never swallow exceptions: a failed value computation logs `LOG.warn` with context and serves verbatim.

## Review Focus

1. **Editor round-trip through the SPA** — open a page whose summary is a token, save without change: stored summary is still the token (Task 10, Task 12 IT).
2. **Malformed tokens** — `{{wiki:}}`, `{{wiki:articles|round=0}}`, `{{wiki:articles|round=x}}`, unterminated `{{wiki:articles`, `{{wiki:articles|bogus=1}}`: served verbatim, no exception (Task 1).
3. **Code immunity in nested blocks** — tokens inside a tilde fence, a fence inside a list item or blockquote, and a double-backtick span stay verbatim (Task 1).
4. **Private-cluster probing** — `cluster.<private>.pages` renders byte-identical to `cluster.no-such.pages`, and private pages are absent from `articles`/`pages` (Task 2).
5. **Rich/WYSIWYG editor render** shows the raw token, not a value (Task 4).

---

### Task 1: Pure token expander (wikantik-api)

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/livevalues/LiveValueExpander.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/livevalues/LiveValues.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/livevalues/LiveValueSource.java`
- Test: `wikantik-api/src/test/java/com/wikantik/api/livevalues/LiveValueExpanderTest.java`

**Interfaces:**
- Produces:
  - `final class LiveValueExpander` — `static String expandText( String text, Function<String, LiveValueExpander.Resolution> resolve )`, `static String expandBody( String markdown, Function<String, Resolution> resolve )`, `static Set<String> tokenNames( String text )` (names of all well-formed tokens, body code included — for validation), `static boolean hasToken( String text )`.
  - `sealed interface Resolution permits Value, Text, Unwired, Unknown` — `record Value( long number )`, `record Text( String text )` (used for `version`; `round` is ignored on it), `record Unwired()`, `record Unknown()`.
  - `interface LiveValues` — `String expandText( String text )`, `String expandBody( String markdown )`, `String generation()`, `Map<String,String> renderedValues()` (name → exact unrounded display string, for the admin view/tests), `void register( LiveValueSource source )`.
  - `interface LiveValueSource` — `Map<String, Resolution> values()` (called on recompute; keys are full names like `tools.admin`), `Set<String> declaredNames()` (names this source owns even when it currently returns nothing).
  - Known-but-unwired names are declared by a static `LiveValueExpander.BUILT_IN_NAMES = Set.of("articles","pages","clusters","subclusters","tools.knowledge","tools.admin","tools.openapi","version")` plus the `cluster.` prefix.

- [ ] **Step 1: Write failing tests** in `LiveValueExpanderTest`, with a resolver backed by a map:
  - `roundsDownAndMarksPlus`: articles=1142, `{{wiki:articles|round=100}}` → `1,100+`; articles=1100 → `1,100`; no round → `1,142`.
  - `textValueVerbatim`: `{{wiki:version}}` with `Text("2.8.4")` → `2.8.4`.
  - `unknownStaysVerbatim`: `{{wiki:nope}}` → `{{wiki:nope}}`.
  - `unwiredUsesElseOrEmpty`: `{{wiki:tools.admin|else=several}}` → `several`; `{{wiki:tools.admin}}` → ``.
  - `malformedStaysVerbatim` (Review Focus 2): each of `{{wiki:}}`, `{{wiki:articles|round=0}}`, `{{wiki:articles|round=x}}`, `{{wiki:articles`, `{{wiki:articles|bogus=1}}` returned unchanged.
  - `bodySkipsCode` (Review Focus 3): token in a ``` fence, a `~~~` fence, a fence inside `- ` list item, a fence inside `> ` blockquote, a 4-space indented block, `` `x` `` and ``` ``x {{wiki:articles}} x`` ``` all verbatim; same token in a paragraph after them expands.
  - `argsAnyOrder`: `{{wiki:articles|else=n/a|round=100}}` == `{{wiki:articles|round=100|else=n/a}}`.
  - `tokenNamesFindsAll`: returns `{articles, cluster.agentic-ai/rag.pages}` for a text holding both.
- [ ] **Step 2: Run** `mvn test -pl wikantik-api -Dtest=LiveValueExpanderTest -q` — expect compile failure / FAIL.
- [ ] **Step 3: Implement.** One regex `\{\{wiki:([a-z][a-z0-9./-]*)((?:\|[a-z]+=[^|}]*)*)\}\}` for text. `expandBody` is a line-based code-aware scan (track fence open/close by marker char + length, allowing container prefixes `>`/list indentation; indented code = 4+ spaces outside a list continuation after a blank line; inline code spans by matching backtick run lengths) and applies `expandText` only to non-code segments. Formatting via `NumberFormat.getIntegerInstance( Locale.US )`.
- [ ] **Step 4: Run** the test — PASS.
- [ ] **Step 5: Commit** `feat(livevalues): pure {{wiki:…}} token expander`.

### Task 2: `DefaultLiveValues` service + corpus values + config key

**Files:**
- Modify: `wikantik-api/src/main/java/com/wikantik/api/pagegraph/StructuralIndexService.java` (add `long revision()` to `StructuralProjectionSnapshot`)
- Modify: `wikantik-main/src/main/java/com/wikantik/pagegraph/spine/DefaultStructuralIndexService.java` (bump an `AtomicLong revision` in `rebuild`, `onPageSaved`, `onPageRenamed`, `onPageDeleted`; expose in `snapshot()`)
- Create: `wikantik-main/src/main/java/com/wikantik/livevalues/DefaultLiveValues.java`
- Create: `wikantik-main/src/main/java/com/wikantik/livevalues/CorpusLiveValueSource.java`
- Modify: the wikantik-main wiring that constructs `DefaultStructuralIndexService` (register `DefaultLiveValues` into `engine.serviceRegistry()` right after it)
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties` (declare `wikantik.liveValues.enabled = true`, `Type: Boolean`, description)
- Test: `wikantik-main/src/test/java/com/wikantik/livevalues/DefaultLiveValuesTest.java`, `CorpusLiveValueSourceTest.java`

**Interfaces:**
- Consumes: Task 1 types; `StructuralIndexService`, `SystemPageRegistry.isSystemPage(String)`, `AuthorizationManager.checkPermission(Session, Permission)` with a guest session.
- Produces: `DefaultLiveValues( StructuralIndexService index, boolean enabled )` implementing `LiveValues`; recompute is lazy, guarded by `snapshot().revision()`, synchronized, and calls every registered source. `generation()` = SHA-256 hex of the sorted `name=renderedValue` map (exact values; any change in an exact value changes generation — rounding stability is achieved by caches validating the *expanded text*, Task 4). `CorpusLiveValueSource( StructuralIndexService, SystemPageRegistry, Predicate<String> isPublic )` declaring `articles, pages, clusters, subclusters, version` and the `cluster.` family.

- [ ] **Step 1: Failing tests.**
  - `CorpusLiveValueSourceTest` with a mocked index (descriptors: 3 articles, 1 hub, 1 system page, 1 page in private cluster `secret`, 1 derived page, 1 page with `clusters: [a, b]`): `articles`=4, `pages`=6 (excludes the private one), `cluster.a.pages` counts the multi-cluster page and excludes the hub, `cluster.secret.pages` resolves `Unknown` exactly like `cluster.nosuch.pages` (Review Focus 4), `version` = `Release.getVersionString()`.
  - `DefaultLiveValuesTest`: recomputes only when `revision()` changes (verify source called once across two expands at same revision, twice after bump); `enabled=false` → `expandText("{{wiki:articles}}")` returns it verbatim; a source throwing → that source's names resolve `Unwired`, a WARN is logged, other names still expand.
  - Extend an existing `DefaultStructuralIndexService` test: `revision()` strictly increases after `onPageSaved`.
- [ ] **Step 2: Run** `mvn test -pl wikantik-main -Dtest='CorpusLiveValueSourceTest,DefaultLiveValuesTest,DefaultStructuralIndexService*Test' -q` — FAIL.
- [ ] **Step 3: Implement** as specified; the `isPublic` predicate is built at wiring time from a guest `Session` and `PagePermission(name, "view")`.
- [ ] **Step 4: Run** the tests plus `-Dtest=ConfigSurfaceDriftTest` — PASS.
- [ ] **Step 5: Commit** `feat(livevalues): corpus values service, index revision, kill switch`.

### Task 3: Tool-count sources

**Files:**
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java`
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpServerInitializer.java`
- Modify: the wikantik-tools initializer that builds its tool list (grep `SearchWikiTool` construction)
- Tests: one `*LiveValueSourceTest` per module asserting the registered source returns the module's tool-list size under its name.

**Interfaces:**
- Consumes: `LiveValues.register`, `LiveValueSource`.
- Produces: names `tools.knowledge`, `tools.admin`, `tools.openapi`, each `Value( toolsBuilt.size() )`, registered after the tool list is built. Fetch `LiveValues` from `serviceRegistry()` at that initializer; absent → skip registration (DEBUG log).

- [ ] **Step 1: Failing tests** (each: build the initializer's tools with its existing test fixtures, capture the registered source, assert `values().get("tools.knowledge")` equals the size of the tool list the initializer registered with the MCP server).
- [ ] **Step 2: Run** `mvn test -pl wikantik-knowledge,wikantik-admin-mcp,wikantik-tools -Dtest='*LiveValueSourceTest' -Dsurefire.failIfNoSpecifiedTests=false -q` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(livevalues): MCP and tools endpoints publish their tool counts`.

### Task 4: Body render path + render caches

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/render/DisplayText.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/render/DefaultRenderingManager.java` (`getRenderedDocument` ~287, `cachedOrRenderedHtml` ~431, `renderEmbedBody`)
- Test: `wikantik-main/src/test/java/com/wikantik/render/LiveValuesRenderTest.java`

**Interfaces:**
- Consumes: `LiveValues` (constructor-injected into `DefaultRenderingManager` via its existing wiring; null → no expansion).
- Produces: display renders parse `liveValues.expandBody( AclDirectiveText.strip( text ) )`; WYSIWYG renders and `justParse` parses do not expand. Both caches validate entries against the hash of the **expanded display text** (not the raw `pagedata`).

- [ ] **Step 1: Failing tests** (use the render-cache test seam — swap `RenderingManager` via `setManager` as existing render-cache tests do):
  - `pageViewShowsValue`: body `We have {{wiki:articles|round=100}} articles` renders `1,100+`.
  - `cacheHitWhileRoundedValueUnchanged`: articles 1142 → render; articles 1150 → second render is a cache hit (count parser invocations).
  - `cacheMissWhenRoundedValueMoves`: articles 1199 → 1201 → re-rendered and shows `1,200+`.
  - `wysiwygShowsRawToken` (Review Focus 5).
  - `codeFenceTokenNotExpanded` end-to-end.
  - `transcludedPageExpandsAgainstSameSnapshot` (`InsertPage` of a token page).
- [ ] **Step 2: Run** `mvn test -pl wikantik-main -Dtest=LiveValuesRenderTest -q` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** plus existing `-Dtest='*RenderingManager*Test,HtmlViewCache*Test'` — PASS.
- [ ] **Step 5: Commit** `feat(livevalues): expand tokens in display renders; caches validate expanded text`.

### Task 5: SSR head, island and structured-data preview

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java` (~629–650: expand `summary` in the one parsed frontmatter feeding `PageSeoModel`, `renderBodyFragment`, `buildPageIsland`)
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/PreviewStructuredDataTool.java` (route summary through the same expansion)
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/SpaRoutingFilterLiveValuesTest.java`, extend `PreviewStructuredDataToolTest`

**Interfaces:**
- Consumes: `LiveValues.expandText`.
- Produces: a static helper `ParsedPage LiveFrontmatter.expandSummary( ParsedPage parsed, LiveValues lv )` in `wikantik-main/src/main/java/com/wikantik/livevalues/LiveFrontmatter.java` returning a copy with `summary` replaced (other fields untouched); reused by Tasks 6–9.

- [ ] **Step 1: Failing tests:** SSR of a page with `summary: 'Over {{wiki:articles|round=100}} articles on software and maths for people and agents'` has `<meta name="description" content="Over 1,100+ …">`, the same text in `og:description`, JSON-LD `description` and the island JSON; no `{{wiki:` anywhere in the response. Preview tool output carries the expanded description.
- [ ] **Step 2: Run** `mvn test -pl wikantik-rest -Dtest=SpaRoutingFilterLiveValuesTest -q` (and the admin-mcp test) — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(livevalues): SSR head, island and structured-data preview`.

### Task 6: Structural descriptors (serving side)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/livevalues/LiveValuesStructuralIndexService.java` (decorator implementing `StructuralIndexService`; every method returning `PageDescriptor`s, `ClusterSummary`/`ClusterDetails` or `Sitemap` returns copies with `summary` expanded; everything else delegates)
- Modify: the wiring that hands `StructuralIndexService` to REST resources, knowledge tools (`KnowledgeToolDeps`), for-agent projection, briefings, insights and ontology — they receive the decorator; the event listener keeps calling `onPageSaved` etc. on the concrete `DefaultStructuralIndexService`.
- Test: `wikantik-main/src/test/java/com/wikantik/livevalues/LiveValuesStructuralIndexServiceTest.java`

**Interfaces:**
- Consumes: `LiveValues`, `StructuralIndexService`.
- Produces: `LiveValuesStructuralIndexService( StructuralIndexService delegate, LiveValues lv )`. Write paths (`rename_cluster`, `ClusterRenameService`, validators) keep the undecorated instance.

- [ ] **Step 1: Failing tests:** `getByCanonicalId`, `listPagesByFilter`, `listPagesByType`, `getCluster`, `sitemap` return expanded summaries; `snapshot()`, `conflicts()`, `health()` delegate untouched; a descriptor without a token is returned as the same instance (no copy).
- [ ] **Step 2: Run** `mvn test -pl wikantik-main -Dtest=LiveValuesStructuralIndexServiceTest -q` — FAIL.
- [ ] **Step 3: Implement**, then re-point the reader-facing wiring.
- [ ] **Step 4: Run** plus `mvn test -pl wikantik-knowledge,wikantik-rest -q -Dtest='*Structure*Test,*PageById*Test,*ForAgent*Test,GetPageNeighborsTool*Test'` — PASS.
- [ ] **Step 5: Commit** `feat(livevalues): expand descriptor summaries on the serving side`.

### Task 7: Frontmatter metadata cache read side

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/search/FrontmatterMetadataCache.java` (expand `summary` on read, not in `loadAndParse`)
- Test: extend `FrontmatterMetadataCacheTest`

- [ ] **Step 1: Failing test:** two reads at different `LiveValues` revisions return different expanded summaries while `loadAndParse` ran once.
- [ ] **Step 2: Run** `mvn test -pl wikantik-main -Dtest=FrontmatterMetadataCacheTest -q` — FAIL.
- [ ] **Step 3: Implement.** Confirm every caller is reader-facing (`DefaultContextRetrievalService`, `PageListEngine`, `SearchWiringHelper` embedding context, briefing items); if any write path reads it, give that path a raw accessor and note it in the commit.
- [ ] **Step 4: Run** plus `-Dtest='DefaultContextRetrievalService*Test,PageListEngine*Test'` in their modules — PASS.
- [ ] **Step 5: Commit** `feat(livevalues): expand summaries served from the frontmatter cache`.

### Task 8: Reader view and stragglers in wikantik-main

**Files (each gets `expandBody` on reader body text and `expandText`/`LiveFrontmatter.expandSummary` on summary):**
- `content/AtomFeedServlet.java` (`<summary>`), `content/DefaultRecentArticlesManager.java` (excerpt), `knowledge/agent/DefaultForAgentProjectionService.java` (body ~278/287), `knowledge/agent/AgentHintsDeriver.java`, `knowledge/bundle/BundleServiceWiring.java` (~224), `preview/PageExcerpts.java`, `export/ExportZipBuilder.java`, `plugin/HubSetPlugin.java`
- Test: `wikantik-main/src/test/java/com/wikantik/livevalues/MainReaderSurfacesLiveValuesTest.java` (one test per file above)

**Interfaces:** consumes `LiveValues` (constructor/deps). `ReaderText` and `AclDirectiveText` are **not** modified — expansion is a separate call after them, because `FrontmatterDefaultsFilter` shares `ReaderText.derivedBody` on a write path.

- [ ] **Step 1: Failing tests:** for each surface, a fixture page with tokens in summary and body yields output containing `1,100+` and no `{{wiki:`. Plus `frontmatterDefaultsFilterKeepsToken`: saving a token page through `FrontmatterDefaultsFilter` stores the token unchanged.
- [ ] **Step 2: Run** `mvn test -pl wikantik-main -Dtest=MainReaderSurfacesLiveValuesTest -q` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(livevalues): expand on wikantik-main reader surfaces`.

### Task 9: Reader view in REST, knowledge, tools, admin-MCP resources

**Files:**
- `wikantik-rest/.../WikiPageFormatFilter.java` (md body and json summary/body)
- `wikantik-knowledge/.../mcp/ReadPagesTool.java` (~170), briefing `ItemAssembler.java` body (~276) and `MarkdownBriefingRenderer` inputs
- `wikantik-tools/.../GetPageTool.java`, `ResultShaper.java` (~149)
- `wikantik-admin-mcp/.../resources/WikiResources.java`
- Tests: one `*LiveValuesTest` per module, same assertion shape as Task 8; plus `readPageToolReturnsRawToken` in admin-mcp (the authoring `read_page` stays raw).

- [ ] **Step 1: Failing tests.**
- [ ] **Step 2: Run** `mvn test -pl wikantik-rest,wikantik-knowledge,wikantik-tools,wikantik-admin-mcp -Dtest='*LiveValuesTest,ReadPageTool*Test' -Dsurefire.failIfNoSpecifiedTests=false -q` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(livevalues): expand on REST, knowledge, tools and MCP-resource reader surfaces`.

### Task 10: Persisted indexes — store raw, serve expanded

**Files:**
- `wikantik-main/.../search/subsystem/lucene/DefaultLuceneIndexer.java` (analysed body/summary built from expanded text; stored `summary` field keeps raw)
- `wikantik-main/.../search/embedding/EmbeddingTextBuilder.java` / `SearchWiringHelper.java` (embedding input expanded)
- `wikantik-main/.../knowledge/chunking/ContentChunker.java` (chunk text stored raw — assert, don't change)
- Result builders that return chunk text or stored summaries: the dense, hybrid and BM25 result shapers and bundle assembly in `com.wikantik.knowledge.bundle` / `wikantik-knowledge` retrieval — enumerate with `grep -rn "chunk.*text\|getStoredSummary\|\.get( *\"summary\" *)"` and wrap each with `expandText`/`expandBody`.
- Test: `wikantik-main/src/test/java/com/wikantik/livevalues/IndexLiveValuesTest.java`, plus a bundle test in wikantik-knowledge.

- [ ] **Step 1: Failing tests:** indexed analysed text contains `1,100+` (searching "1,100" finds the page); stored summary field holds the token; chunk rows hold the token; `assemble_bundle`/`retrieve_context` output for the page has `1,100+` and no `{{wiki:`.
- [ ] **Step 2: Run** the tests — FAIL.
- [ ] **Step 3: Implement.** No reindex is triggered by a value change.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(livevalues): indexes store raw tokens, results serve expanded`.

### Task 11: `/api/pages` resolved block + SPA reader view

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/PageReadHandler.java` — `content` and `metadata` stay raw; add `resolved: { summary: <expanded> }` (omit when summary absent); `contentHtml` already expanded via Task 4.
- Modify: `wikantik-frontend/src/components/PageMeta.jsx`, `MetadataPanel.jsx` and any reader component showing `summary` (grep `metadata.summary`/`metadata?.summary` under `src/`, excluding editor components) to prefer `page.resolved?.summary ?? metadata.summary`.
- Tests: `PageReadHandlerLiveValuesTest` (raw `content`/`metadata`, expanded `resolved.summary`); vitest in `PageMeta.test.jsx` / `MetadataPanel.test.jsx` (shows resolved value; falls back to raw when `resolved` absent); an editor test asserting the editor's frontmatter field shows the token.

- [ ] **Step 1: Failing tests.**
- [ ] **Step 2: Run** `mvn test -pl wikantik-rest -Dtest=PageReadHandlerLiveValuesTest -q` and `cd wikantik-frontend && npx vitest run src/components/PageMeta.test.jsx src/components/MetadataPanel.test.jsx` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS; also `npx eslint src`.
- [ ] **Step 5: Commit** `feat(livevalues): resolved summary on /api/pages; SPA reader shows it`.

### Task 12: Validation, drift report, write-path bake-in tests

**Files:**
- Modify: `wikantik-api/.../frontmatter/schema/FrontmatterSchema.java` — summary-length WARNING measures `summaryExpander.apply(summary)` where the validator takes an optional `UnaryOperator<String> summaryExpander` (identity when absent); new WARNINGs: unknown token name, malformed `round`, `{{wiki:` in a frontmatter field other than `summary`.
- Modify: the REST and MCP save-path validator call sites to pass `liveValues::expandText`.
- Modify: `wikantik-rest/.../AdminDriftResource.java` — list pages carrying unknown token names (via `LiveValueExpander.tokenNames` against the known set).
- Tests: `FrontmatterSchemaLiveValuesTest`; `AdminDriftResource` test; bake-in round-trip unit tests for `UpdatePageTool`, `WritePagesTool`, `MarkPageVerifiedTool`, `VerifyPagesTool`, `FrontmatterBackfillAdminHandlers`, page rename (`RenameMove`), `ClusterRenameService`, `PageSaveHandler`: read → save leaves summary and body tokens byte-identical.

- [ ] **Step 1: Failing tests** (validator: a 40-char raw summary that expands to 60 chars → no length warning; unknown name → WARNING naming it; saves still succeed).
- [ ] **Step 2: Run** them across `wikantik-api,wikantik-rest,wikantik-admin-mcp,wikantik-main` — FAIL where behaviour is new; bake-in tests should already pass (they pin behaviour).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(livevalues): save-time warnings, drift listing, bake-in tests`.

### Task 13: Cross-surface leak + bake-in IT

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/LiveValuesIT.java`

- [ ] **Step 1: Write the IT.** Create via MCP `write_pages` a page with `summary: 'Live values fixture page that states {{wiki:articles}} articles across the wiki corpus'` and body tokens (one in a paragraph, one in a fence). Assert the value text and no `{{wiki:` outside the fence on: SSR `/wiki/<page>` (head + body + island), `/api/pages/<page>` (`contentHtml`, `resolved.summary`), `?format=md`, `?format=json`, Atom feed, `/api/structure`, `/api/pages/by-id`, `/api/pages/for-agent`, knowledge `get_page`, `read_pages`, `get_page_by_id`, `retrieve_context`, `/tools` `get_page` and `search_wiki`. Assert raw token on admin `read_page` and `/api/pages` `content`/`metadata`. Bake-in (Review Focus 1): GET `/api/pages`, PUT the same `content` back as the SPA editor does, MCP `update_page` with an unrelated body edit, `mark_page_verified` — re-read raw: tokens intact.
- [ ] **Step 2: Run** `bin/run-tests.sh --module rest` (via `bin/agent-build.sh start it -- bin/run-tests.sh --module rest`, poll `status`) — PASS (tasks 1–12 already landed; if a surface leaks, fix it in the owning task's file and add a unit test there).
- [ ] **Step 3: Commit** `test(livevalues): cross-surface leak and bake-in IT`.

### Task 14: Content rollout (repo) + full gate

**Files:**
- Modify: `docs/wikantik-pages/Main.pins.yaml` — `summary: 'A technical knowledge wiki for human reading and AI agent retrieval: {{wiki:articles|round=100}} articles on software, AI, math and engineering, with MCP tools.'`; intro sentence `It holds {{wiki:articles|round=100}} articles … grouped into {{wiki:clusters}} topic clusters, …`.
- Regenerate `docs/wikantik-pages/Main.md` with `GenerateMainPageCli --write`.
- Modify: `docs/wikantik-pages/Sitemap.md` — replace `1,171 pages` / `1,193 pages` with `{{wiki:pages}}` tokens.
- Test: `GenerateMainPageCliTest.tokensPassThroughVerbatim`.

- [ ] **Step 1: Failing test** `tokensPassThroughVerbatim` (pins with a token → generated Main.md contains it byte-for-byte). Run `mvn test -pl wikantik-extract-cli -Dtest=GenerateMainPageCliTest -q` — it may already pass; if it does, keep it as a pin.
- [ ] **Step 2: Edit pins + Sitemap, regenerate Main.md;** `mvn test -pl wikantik-extract-cli -Dtest=MainPageRegressionTest -q` — PASS.
- [ ] **Step 3: Full gate:** `bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4`; poll `status gate` until SUCCESS.
- [ ] **Step 4: Commit** `content: Main and Sitemap state counts via live values`.
- [ ] **Step 5: Hand off prod steps to the owner** (not agent-executable): release + deploy; then push `Main` (system-pages procedure, preserving prod CRLF/footers) and `Sitemap` via MCP; verify SSR `/wiki/Main` meta description shows the live count.
