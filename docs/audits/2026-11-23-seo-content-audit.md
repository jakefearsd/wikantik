# SEO Content Audit — 2026-11-23

Scope: the public production corpus at wiki.wikantik.com (1,090 sitemap URLs, fetched anonymously
as `?format=json`), live search data from jakemon (Prometheus `webmaster_*` on docker2.lan:9090),
and the admin MCP (`get_wiki_stats`, `list_clusters`, `get_broken_links`, `list_content_opportunities`,
`list_retrieval_queries`). The working files are in the session scratchpad and were not kept.

## Where things stand

| Signal | Value |
|---|---|
| Google indexed pages | **0** (715 "Crawled – currently not indexed", 251 "Discovered – not indexed", 47 stale 5xx, 74 unknown) |
| Google crawl activity | 10 URLs/7d, 119/30d (low; Google has deprioritised the host) |
| Bing indexed / impressions / clicks | 1,937 / 1,615 / 30 (average position 5.9) |
| Yandex indexed | 1,016 |
| Retrieval queries with ≤1 result (60d) | 0 (no internal search gaps to report) |
| Broken links / orphans | 2 / 0 |

**Technical SEO is in good shape.** One `<h1>` per page in the server-rendered HTML, a correct
canonical (including on `?_r=1` variants), robots meta, every page has a summary between 50 and 160
characters, tags, and a cluster, with no duplicate titles or summaries. The Google problem is a
**quality and trust verdict on the content, not a crawl or markup fault**: Google fetched the pages
and chose not to index them. Every recommendation below is aimed at that verdict.

## Findings, in priority order

### 1. Zero outbound citations on 94% of pages (critical for tech E-E-A-T)
1,020 of 1,089 pages have **no links to external sources**: no official docs, specs, RFCs, papers
or repositories. The median is 0. For technology content, this is the clearest gap between these
pages and the pages Google ranks: readers (and raters) expect "per the PostgreSQL docs…", the RFC
number, the GitHub repo or the paper. 64% of pages carry author `wiki-content-agent`, at a uniform
length (median about 1,650 words). Uniform, unsourced, agent-written pages at scale look like
Google's "scaled content" pattern.
**Action:** start with the pages that already get impressions (item 3), then work through the
technical clusters. Add 3–8 primary-source references per page, inline where each claim is made,
plus a short "References" section. Pair this with the D2 re-verification loop: check versions and
claims against the world, then `mark_page_verified`. **0 of 1,089 pages carry `verified_at` today.**

### 2. Thin, off-mission and internal-ops pages dilute the site's quality signal
Google judges site quality across the whole host. These areas are thin, off-topic for a technology
reader, or internal project paperwork:

| Area | Pages | Median words | Problem |
|---|---|---|---|
| software-projects | 4 | 37 | `AgentContextOperatorVoice`/`Mp3Resolver`/`Hud`: 30–39-word stubs |
| reselling | 7 | 131 | Stockroom listing rules (internal, not tech) |
| wealthview | 7 | 258 | Personal-project dev notes (`WealthviewDevelopmentWorkflow` is 30 words) |
| web-properties | 3 | 274 | Internal ops for your own sites |
| content-pipeline | 5 | 519 | Internal tooling |
| stockroom / pressroom | 12 / 7 | 904 / 707 | Internal phase plans and designs |
| wikantik-development | 104 | 1,055 | Design records, fix notes (`McpIntegrationTestFix`, `RemoveJspAndAppPrefix`, `ConstructorInjection`…) |
| fresh-food-supply-chain, philosophy, physics, life-sciences, mechanical, materials | 64 total | 419–1,058 | Off-mission for "technology work" and mostly thin |

**Action (pick per area):**
- **Internal-ops clusters** (reselling, stockroom, pressroom, wealthview, web-properties,
  content-pipeline, software-projects, and the non-public half of wikantik-development): make them
  **noindex** and drop them from the sitemap, or make them private. Agents keep MCP access; search
  engines stop counting them. The existing `privateClusters` mechanism already does the private
  option. A noindex-only cluster list would be a small new feature.
- **Off-mission science clusters:** decide whether to expand them to full depth or noindex them.
  Leaving them public and thin costs the whole host.
- **Thin stubs under 400 words** (64 pages, listed below): merge, expand or delete.

### 3. Pages with real demand need a targeted push (quick wins)
On Bing, impressions concentrate on a few pages, all in the top 10 with near-zero CTR:

| Page | Bing impressions | Gap |
|---|---|---|
| **BendLanguage** | 382 (`bend language` at position 7.05, `bend 2…`/`bend2` queries) | **Doesn't mention Bend 2**, which launched in September 2026 (proof-checking, CPU/GPU, LAWS/PROOF, the Bender agent). The top query is literally Bend 2's launch tagline. Zero external links (no GitHub, no docs). |
| GoogleOrToolsInPractice | 155 (`or-tools cp-sat` at about 8) | Zero external links; add CP-SAT parameter specifics and links to the official docs |
| VectorIndexingInternals | 78 (`hnsw vs ivf`, `pq parameters`) | Title is 86 characters (truncated in results); zero refs (HNSW/FAISS papers) |
| LinkedDataAndTripleStores | 60 (`triple stores supporting rdf`) | Zero refs; add a comparison table of real stores |
| OpenSourceOptimizationSolvers, MilpFormulationPatterns, MipSolverBenchmarking, CommercialMipSolvers | 17–34 each | OR is a coherent demand cluster (Pyomo vs PuLP, MIPLIB/Mittelmann): cite benchmarks |
| ScikitLearnPipelines, JavaBuildToolComparison (`bazel vs maven`), IntervalTrees, HeartbeatAndLeasePatterns, IdempotentReceiver | 10–30 each | Add references, a comparison table and a decision rule |

**Action:** rewrite BendLanguage first: add a Bend 2 section and make it the title lead, link the
repos, and add an FAQ that answers the long-tail queries. Then do the next four. Ping IndexNow after
each.

### 4. About 20% of Bing impressions now land on 404s
Pages that earned impressions were removed from prod and now return 404: `BerlinDuringTheColdWar`
(121 impressions), `WorldWarTwoMarkets` (56), `PrussianIndustrialRevolution` (41),
`AdventureTravelPlanning`, `CoinGradingAndAuthentication`, `TaxEfficientBondInvestingCA`,
`HistoryOfTheFourPercentRule`, `GuardrailsSpendingStrategy`, `LowCostIndexFundInvesting`. If the
removal was deliberate (off-mission), serve **410 Gone** so the engines drop them quickly. If it
wasn't, restore them from `docs/wikantik-pages/` (git still has them).

**Defect:** `Main` (the home page) still links to the deleted `LowCostIndexFundInvesting`, because
`docs/wikantik-pages/Main.pins.yaml:165-167` still pins it. That's a broken link on the most
important page. Remove the pin and regenerate Main. (Second broken link: `EditPageHelp →
CollaborativeEditing`, a system page, low priority.)

### 5. Hub pages are thin link lists
65 hubs, median 493 words. Many are about 200–300 words: `NetworkingHub` 188, `CloudPlatformsHub`
196, `WebServicesAndApisHub` 216, `EngineeringLeadershipHub` 218, `InfrastructureSreHub` 223,
`AuthenticationAndAuthorizationHub` 237, `FrontendDevelopmentHub` 256, `JavaHub` 302,
`GenerativeAIHub` 317. Hubs emit `CollectionPage` and are the natural landing pages for head terms.
Eleven sub-cluster hubs hold **only themselves** (`JavaMemoryManagementHub` at 134 words,
`LogisticsIndustrialHub` 174, `FormalMethodsHub`, `LeanManufacturingPrinciplesHub`, …).
**Action:** turn the top 10 hubs into "start here" guides: learning path, decision table, what to
read first. Fold one-member sub-clusters back into their parent clusters.

### 6. Mass churn inflates sitemap `lastmod`
666 pages were re-saved by `wiki-content-agent` between 2026-11-20 and 2026-11-23. 600+ sitemap
URLs now carry `lastmod` dates from the last four days. If those edits were substantive, fine. If
they were cosmetic, Google learns to ignore this site's `lastmod`, which removes a recrawl lever
while 966 URLs wait for a quality re-evaluation. Confirm what that run changed. In future, make
cosmetic bulk edits skip the content-date bump.

### 7. Minor
- **News sitemap:** 339 entries carry `<news:news>` with `news:title` set to the CamelCase slug
  (`ServiceLevelAgreemen…`), not the page title. An evergreen wiki isn't a Google News publisher.
  Drop the news block, or at least emit the real title.
- **157 titles exceed 70 characters** and get truncated in search results (longest is 103:
  `ObsidianBlockReferencesDesign`). Shorten the ones with demand first.
- **Only 3 pages have an FAQ section.** FAQ blocks match the question-shaped Bing queries seen here
  ("what is the builder pattern role in fluent apis", "primary goal of java's security model").
- **Google-side owner steps** (still owed per the 2026-11-12 notes): check Manual actions, run
  "Validate fix" on the 47 5xx URLs, request indexing for the about 10 strongest pages after they're
  improved, and build backlinks from www, the blog and the READMEs.

## Thin pages (<400 words, 64)

30 AgentContextOperatorVoice · 30 WealthviewDevelopmentWorkflow · 35 AgentContextMp3Resolver ·
39 AgentContextHud · 88 ResellingCollectibles · 89 FoodSafetyInDistribution · 102 ResellingTools ·
116 ColdWarTechnologyRace · 126 ResellingLots · 130 ImmutableTraceabilityAndGovernance ·
131 ResellingElectronics · 132 BlockchainTechHub · 134 JavaMemoryManagementHub · 136 WealthviewHub ·
141 SoftwareProjectsHub · 165 BearingMechanics · 172 RenderedFencesDesign · 174 LogisticsIndustrialHub ·
180 PressroomW3tPlugins · 182 MechanicalEngineeringHub · 188 NetworkingHub · 191 RemoveJspAndAppPrefix ·
196 CloudPlatformsHub · 206 ConstructorInjection · 210 TheLocalWikiBloggerSites · 216 WebServicesAndApisHub ·
218 EngineeringLeadershipHub · 221 WealthviewDeployment · 223 InfrastructureSreHub · 229 PhilosophyHub ·
237 AuthenticationAndAuthorizationHub · 242 AIInfrastructureHub · 242 McpIntegrationTestFix ·
248 CounterfeitDetectionPhysics · 249 BackwardsCompatibilityStrategiesHub · 249 LeanManufacturingPrinciplesHub ·
256 FrontendDevelopmentHub · 257 EngineeringDisciplineHub · 258 WealthviewDataModel · 274 MarketingSiteOperations ·
282 WebPropertiesHub · 292 FreshFoodWasteScience · 299 UserProfileBio · 300 DataSciencePracticeHub ·
302 JavaHub · 312 ResellingBooks · 317 GenerativeAIHub · 331 NewUI · 334 LivePreviewDailyNotesDesign ·
342 QuantitativeFinanceResearchHub · 346 PulleySystems · 348 ColdChainLogistics ·
361 ProjectCommunicationStrategies · 371 InventoryManagementStrategies · 374 WarehouseAutomationReturnOnInvestment ·
376 PhysicalChemistry · 379 ObsidianVaultImportDesign · 382 PressroomBlogPlatform ·
383 FintechDataIngestionBlueprint · 385 LongRunningProjects · 386 NonStandardAnalysisFinance ·
389 SupplyChainResilience · 397 NativeWikilinksDesign · 399 StandardOperatingProcedures

(Word counts exclude code blocks. Hub counts include link text.)

## Recommended tasks

1. [ ] Remove the `LowCostIndexFundInvesting` pin from `Main.pins.yaml` and regenerate Main (defect, minutes).
2. [ ] Rewrite `BendLanguage` around Bend 2 with sources and an FAQ, then ping IndexNow (biggest single demand signal).
3. [ ] Decide noindex or private for the internal-ops clusters: reselling, stockroom, pressroom, wealthview, web-properties, content-pipeline, software-projects, and the dev-record part of wikantik-development.
4. [ ] Decide expand or noindex for the off-mission science clusters.
5. [ ] Serve 410 for the deliberately removed history and finance pages, or restore them.
6. [ ] Add a references section and re-verify (`mark_page_verified`) on the demand pages: OR-Tools, VectorIndexingInternals, LinkedDataAndTripleStores, the OR solver set, ScikitLearnPipelines, JavaBuildToolComparison.
7. [ ] Run a citation and verification programme across the technical clusters, largest first: distributed-systems, agentic-ai, devops-sre, databases, generative-ai.
8. [ ] Expand the top 10 hubs into "start here" guides, and fold one-member sub-clusters into their parents.
9. [ ] Confirm what the 2026-11-20..23 bulk re-save changed, and keep cosmetic bulk edits from bumping content dates.
10. [ ] Fix or drop the news-sitemap block, and shorten titles over 70 characters on pages with demand.
11. [ ] Owner, in GSC: Manual actions check, Validate fix, Request Indexing on the improved pages, backlinks.
