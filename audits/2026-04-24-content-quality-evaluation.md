# Wiki Content Quality Evaluation — 2026-04-24

**Corpus:** 987 markdown pages under `docs/wikantik-pages/`, ~2.44M words total (avg ~2,470 words/page).
**Declared scope:** technology, science, mathematics, history, agentic coding.
**Headline finding:** 771 of 987 pages (78%) carry `auto-generated: true` in frontmatter. The wiki's single biggest quality problem is **voice-homogenized AI slop** — legitimate topics hidden under a formulaic, pompous register that makes them both unpleasant to read and hard to trust.

---

## Part 1 — How I evaluated

I extracted per-page metrics (word count, heading count, frontmatter presence, `auto-generated` flag, type, tags, link density) across all 987 pages, then classified each page into one of six buckets:

| Bucket | Count | Notes |
|---|---:|---|
| tech_or_other (default in-scope) | 690 | CS/data/AI/science — treat as signal, most need rewrite |
| off-scope (lifestyle) | 160 | VanLife, Airbnb, retirement, immigration, travel, hobby crafts |
| agentic AI | 87 | Your stated area of aggressive focus |
| JSPWiki system | 40 | Menus, CSS themes, help pages, test fixtures |
| hub pages | 10 | Structural indexes — keep and fatten |

Spot reads of ~20 pages across every bucket confirmed the mechanical classification. I read at full depth: `AbstractAlgebra`, `ActorModel`, `AgentLoops`, `AgenticArchitecture`, `VanLifeCooking`, `AmericanCoinageInThe1900s`, `BackpackingGuide`, `BerlinDuringTheColdWar`, `Aesthetics`, `AdapterPattern`, `CategoryTheory`, `NavalHistory`, `AcceleratingAiLearning`, `WorldWarOneMarkets`, `2026IranWar`, `SupplyChainPlanning`, `ApprovalRequiredForUserProfiles`, `DockerSetup`, `WarehouseAutomationHub`, `BlogEditorSplitView`.

### The smoking gun — auto-gen voice fingerprint

Every auto-generated page shares an identical rhetorical template:

1. **Pompous second-person opening** — "Welcome. If you are reading this, you are not looking for a refresher…"
2. **Target-audience disclaimer** — "for the seasoned researcher / principal architect / expert practitioner"
3. **Forced systems-engineering framing** on topics that don't need it (VanLifeCooking: "integrated thermodynamic and logistical subsystem"; BackpackingGuide: "deploying a self-contained, resilient, mobile operational unit")
4. **Roman-numeral section hierarchy** (I., II., III.) regardless of topic
5. **Gratuitous LaTeX formulas** inserted to signal rigor (`$P_{req} = P_{cook} + P_{refrig} + ...$` in a van-cooking article)
6. **Meta-jargon stuffing** — "paradigm collapse", "methodological stack", "monetary semiotics", "reintegration manifold"
7. **No concrete examples**, no cited sources, no real data, no opinions you can disagree with

By contrast, the **non-auto-generated** pages (like `AcceleratingAiLearning`, `WorldWarOneMarkets`, `2026IranWar`) are concrete, actionable, opinionated, and sourced. These are the editorial baseline you should hold the rest of the wiki to.

---

## Part 2 — Top 100 pages to delete, stack-ranked

Ordering rationale: most-obviously-useless machinery first (rank 1–34), then off-scope lifestyle content grouped by theme (rank 35–100). Within a theme I ranked shortest pages higher — shorter auto-gen is cheaper to rewrite if you change your mind, but longer auto-gen on off-scope topics just wastes more storage confirming the topic shouldn't exist.

### Tier 1: JSPWiki machinery, test fixtures, CSS theme relics (1–34)

These are artifacts of the original JSPWiki fork. None of them are content; they are plumbing that shouldn't live in `docs/wikantik-pages/`. Move what you need to `wikantik-war/src/main/config/` or delete outright.

| # | File | Words | Why delete |
|---:|---|---:|---|
| 1 | TitleBox.md | 4 | JSPWiki template fragment |
| 2 | TestPage.md | 5 | Placeholder |
| 3 | CopyrightNotice.md | 6 | JSPWiki legal boilerplate |
| 4 | MoreMenu.md | 8 | JSPWiki menu template |
| 5 | RecentArticlesTemplate.md | 11 | Template |
| 6 | PageIndex.md | 16 | Auto-generated page index |
| 7 | SandBox.md | 16 | Test sandbox |
| 8 | FullRecentChanges.md | 19 | Auto-generated list |
| 9 | UnusedPages.md | 19 | Auto-generated list |
| 10 | UndefinedPages.md | 21 | Auto-generated list |
| 11 | LeftMenu.md | 26 | JSPWiki menu template |
| 12 | RejectedMessage.md | 26 | JSPWiki system message |
| 13 | WikiWiki.md | 35 | Historic JSPWiki About-the-wiki blurb |
| 14 | RecentChanges.md | 41 | Auto-generated list |
| 15 | SemanticHub.md | 41 | Test stub |
| 16 | SemanticArticle.md | 42 | Test stub |
| 17 | JanneJalkanen.md | 45 | Original JSPWiki author page; unrelated to your project |
| 18 | PlainPage.md | 46 | Test stub |
| 19 | ApprovalRequiredForUserProfiles.md | 55 | System message, not content |
| 20 | TestPrecedence.md | 71 | Test fixture |
| 21 | MathTest.md | 80 | Math rendering smoke test |
| 22 | Avatar.md | 85 | System message |
| 23 | UserProfileBio.md | 94 | Placeholder |
| 24 | TestStubConversion.md | 140 | Test fixture |
| 25 | TestMarkdown.md | 143 | Test fixture |
| 26 | CSSStripedText.md | 25 | JSPWiki theme snippet |
| 27 | CSSRibbon.md | 75 | JSPWiki theme snippet |
| 28 | CSSInstagramFilters.md | 141 | JSPWiki theme snippet |
| 29 | CSSPrettifyThemeTomorrowNightBlue.md | 178 | JSPWiki theme snippet |
| 30 | CSSPrettifyThemePrism.md | 235 | JSPWiki theme snippet |
| 31 | CSSThemeCleanBlue.md | 342 | JSPWiki theme snippet |
| 32 | CSSBackgroundPatterns.md | 539 | JSPWiki theme snippet |
| 33 | CSSThemeDark.md | 1320 | JSPWiki theme snippet |
| 34 | DockerSetup.md | 5 | Frontmatter-only placeholder |

### Tier 2: JSPWiki help pages (35–49)

These help the *reader* navigate the old JSPWiki UI. Your React SPA has its own help; these are dead weight.

| # | File | Words | Why delete |
|---:|---|---:|---|
| 35 | SupplyChainPlanning.md | 64 | Literal "I don't really have content" placeholder |
| 36 | WikiName.md | 158 | JSPWiki naming convention doc |
| 37 | OneMinuteWiki.md | 172 | JSPWiki intro page |
| 38 | PageAlias.md | 193 | JSPWiki syntax help |
| 39 | EditFindAndReplaceHelp.md | 245 | JSPWiki editor help |
| 40 | EditPageHelp.md | 258 | JSPWiki editor help |
| 41 | FrontmatterConventions.md | 297 | Internal dev doc, should live in `docs/adrs/` |
| 42 | MarkdownLinks.md | 309 | Markdown syntax cheat-sheet |
| 43 | InstallationTips.md | 335 | JSPWiki install guide (legacy) |
| 44 | Community.md | 338 | Generic community-guidelines page |
| 45 | SearchPageHelp.md | 378 | JSPWiki UI help |
| 46 | LoginHelp.md | 392 | JSPWiki UI help |
| 47 | JspwikiDeployment.md | 466 | Legacy deploy doc, superseded by CLAUDE.md |
| 48 | WikiHistory.md | 479 | Old JSPWiki origin story |
| 49 | WikiEtiquette.md | 497 | JSPWiki community etiquette page |

### Tier 3: Van life / Airbnb / short-term-rental content (50–83)

Largest off-scope cluster. Every one of these is auto-generated, 2,500–3,300 words, written in the bloated systems-engineering register. Deleting them removes ~90,000 words of content that bears no relation to your stated topics.

| # | File | Words | Bucket |
|---:|---|---:|---|
| 50 | SeasonalAirbnbVanLifeStrategy.md | 2393 | vanlife |
| 51 | MidlifeVanLife.md | 2465 | vanlife |
| 52 | SnowbirdVanLife.md | 2485 | vanlife |
| 53 | ChasingWeatherAcrossUS.md | 2518 | vanlife |
| 54 | VanLifeCoffeeSetups.md | 2531 | vanlife |
| 55 | BoondockingBLMSouthwest.md | 2549 | vanlife |
| 56 | CamperVsVan.md | 2571 | vanlife |
| 57 | VanLifeTipsAndTricks.md | 2580 | vanlife |
| 58 | VanLifeCostAnalysis.md | 2619 | vanlife |
| 59 | CookingForTwoInAVan.md | 2643 | vanlife |
| 60 | DogCareOnTheRoadVanLife.md | 2675 | vanlife |
| 61 | AirbnbVanLifeFinancials.md | 2718 | vanlife |
| 62 | DogSafetyHotColdVanLife.md | 2718 | vanlife |
| 63 | DayHikingFromVanBaseCamp.md | 2732 | vanlife |
| 64 | RealVanLife.md | 2733 | vanlife |
| 65 | HomeToAirbnbVanLife.md | 2734 | vanlife |
| 66 | VanLifePermanentAddress.md | 2737 | vanlife |
| 67 | VanLifeCooking.md | 2748 | vanlife |
| 68 | ComfortableVanSeating.md | 2788 | vanlife |
| 69 | VanKitchenGear.md | 2804 | vanlife |
| 70 | VanLifeMattressGuide.md | 2813 | vanlife |
| 71 | VanFitnessRoutines.md | 2873 | vanlife |
| 72 | WinterVanLifeGear.md | 2905 | vanlife |
| 73 | EastCoastVanLife.md | 2908 | vanlife |
| 74 | VanLifeCityCamping.md | 2929 | vanlife |
| 75 | EndingVanLifeAirbnbTransition.md | 2964 | vanlife |
| 76 | VanLifeOVerview.md | 3192 | vanlife (note typo in filename) |
| 77 | OffRoadingVanLife.md | 3336 | vanlife |
| 78 | ShortTermRentalLaws.md | 2627 | airbnb |
| 79 | PricingAirbnbForTravel.md | 2728 | airbnb |
| 80 | AirbnbTaxImplications.md | 2733 | airbnb |
| 81 | StoringBelongingsForAirbnb.md | 2859 | airbnb |
| 82 | PrepHouseForAirbnb.md | 2942 | airbnb |
| 83 | CoHostWhileTraveling.md | 3362 | airbnb |

### Tier 4: Retirement & personal-finance (84–100)

Second-largest off-scope cluster. These read like a boilerplate personal-finance blog, disguised with your wiki's bloated academic voice.

| # | File | Words | Bucket |
|---:|---|---:|---|
| 84 | DownsizingInRetirement+Hub.md | 33 | personal-finance hub (near-empty) |
| 85 | LowCostIndexFundInvesting+Hub.md | 33 | personal-finance hub (near-empty) |
| 86 | IndexFundInvestingForEarlyRetirement.md | 547 | personal-finance |
| 87 | ExpenseRatiosAndTheirEffectOnCompounding.md | 854 | personal-finance |
| 88 | AssetAllocation.md | 869 | personal-finance |
| 89 | LowCostIndexFundInvesting.md | 871 | personal-finance |
| 90 | RetirementPlanningGuide.md | 880 | personal-finance |
| 91 | TaxBenefitsOfRetirementAccounts.md | 906 | personal-finance |
| 92 | MaximizingRetirementAccountContributions.md | 927 | personal-finance |
| 93 | RetirementAccountWithdrawalRules.md | 1061 | personal-finance |
| 94 | TaxPlanningForRetirementAccountWithdrawals.md | 1089 | personal-finance |
| 95 | RothConversionStrategy.md | 1154 | personal-finance |
| 96 | RetirementWithdrawalSequencing.md | 1214 | personal-finance |
| 97 | EuRetirementSavingsGuide.md | 1266 | personal-finance |
| 98 | RothConversionLadder.md | 1284 | personal-finance |
| 99 | GermanRetirementSystem.md | 1400 | personal-finance |
| 100 | SocialSecurityClaimingStrategy.md | 1429 | personal-finance |

### Tier 4b: Nearby deletions you should also consider (bonus, still off-scope)

There are at least 127 additional off-scope pages I rank as clear deletes — 50+ more personal-finance pages, 12+ immigration-legal-process pages, 14 cooking/hobby/craft pages, 7 niche supply-chain pages, 5 finance-conflict crossover pages. These didn't make the top-100 cut only because 100 was the limit you asked for. The full set is stored in `temp/audit/final_deletions.tsv` (120 rows).

---

## Part 3 — Top 100 pages to rewrite, stack-ranked

Ranking rationale: all 100 are **in-scope, auto-generated, substantial (>1,500 words)**, and would be valuable with a genuine rewrite. I ordered by `words + headings × 100` as a rough proxy for "how many well-structured sections already exist that a human rewrite can build on." Top picks are articles where the bones are acceptable but the voice and concreteness need replacement.

A quick legend for the topic column below:

- **AGT** — agentic coding / LLM / RAG / prompt engineering (your stated focus area)
- **AI** — non-agentic AI/ML fundamentals
- **CS** — core CS, data structures, algorithms, patterns, distributed systems
- **DB** — databases, storage, observability
- **SEC** — security, auth, cryptography
- **MATH** — mathematics / quantitative foundations
- **ENG** — general software engineering / platforms

| # | File | Words | Topic | Priority reason |
|---:|---|---:|---|---|
| 1 | AgenticWorkflowDesign.md | 3253 | AGT | Core to your focus; currently pompous-abstract |
| 2 | LLMFineTuning.md | 3367 | AGT | Evergreen how-to topic; needs real code examples |
| 3 | AiForDocumentation.md | 3293 | AGT | Practitioner-useful; needs concrete workflows |
| 4 | PaxosAndRaft.md | 3548 | CS | Classic distributed systems; rewrite with diagrams + failure cases |
| 5 | DocumentClusteringApproaches.md | 3342 | AI | Retrieval-relevant; needs benchmark numbers |
| 6 | SchemaRegistryAndEvolution.md | 3430 | DB | Event-driven systems staple; needs Confluent/Avro specifics |
| 7 | MicroservicesArchitecture.md | 3615 | CS | Canonical topic; currently generic — add your war stories |
| 8 | QuantumComputing.md | 3702 | MATH | Depth without handwaving would differentiate vs Wikipedia |
| 9 | DatabaseSharding.md | 3461 | DB | Needs concrete schemes (hash/range/directory) with tradeoffs |
| 10 | AiDataPrivacyAndCompliance.md | 3362 | AGT | Cite GDPR/CCPA articles; currently vague |
| 11 | SecurityIncidentResponse.md | 3296 | SEC | Needs runbook-style concreteness |
| 12 | DataObservability.md | 2993 | DB | Needs concrete tools (Monte Carlo, Bigeye, dbt tests) |
| 13 | ChaosEngineering.md | 3079 | ENG | Needs Netflix-Chaos-Monkey-style recipes |
| 14 | KnowledgeGraphCompletion.md | 3131 | AGT | Differentiator for your wiki's own knowledge-graph work |
| 15 | VectorDatabases.md | 3085 | AGT | Compare pgvector, Pinecone, Weaviate, Qdrant concretely |
| 16 | LlmEvaluationMetrics.md | 3367 | AGT | Concrete metrics: ROUGE/BLEU/G-Eval/LLM-as-judge |
| 17 | AgentTesting.md | 2955 | AGT | Central to your agentic focus — deserves a flagship rewrite |
| 18 | MultimodalEmbeddings.md | 3112 | AGT | Model-specific: CLIP, ImageBind, Nomic |
| 19 | ThreatModeling.md | 3694 | SEC | STRIDE/PASTA/LINDDUN walkthroughs |
| 20 | SslTlsDeepDive.md | 3553 | SEC | Concrete handshake walkthrough with wireshark captures |
| 21 | DatabaseIndexingStrategies.md | 3246 | DB | Needs EXPLAIN plans and B-tree vs GIN vs BRIN specifics |
| 22 | KnowledgeGraphVsRelationalDatabase.md | 3342 | AGT | Directly relevant to your own wiki architecture |
| 23 | DatabaseDesign.md | 3392 | DB | Canonical topic; needs normalization examples |
| 24 | AiObservabilityInProduction.md | 3090 | AGT | LangSmith/Langfuse/Arize specifics |
| 25 | ServiceLevelAgreements.md | 3181 | ENG | Google SRE SLO/SLI framework with math |
| 26 | AiAgentArchitectures.md | 3056 | AGT | ReAct, Reflexion, Plan-and-Execute, SWE-agent comparison |
| 27 | LlmTokenEconomicsAndPricing.md | 3153 | AGT | Real pricing tables, cache savings math |
| 28 | DatabasePerformanceMonitoring.md | 3350 | DB | pg_stat_statements, slow-query workflows |
| 29 | CalculusRefreshForCS.md | 3320 | MATH | Needs worked examples tied to ML/graphics |
| 30 | JavaStreamsAndFunctionalProgramming.md | 3201 | CS | Runnable code > abstract prose |
| 31 | MultiObjectiveOptimization.md | 3097 | MATH | Pareto fronts, concrete problems |
| 32 | RecurrentNeuralNetworks.md | 3097 | AI | Math + PyTorch/JAX snippets |
| 33 | AiPoweredSearch.md | 3380 | AGT | Hybrid retrieval, your wiki's own system is the case study |
| 34 | DataMeshArchitecture.md | 3279 | DB | Zhamak Dehghani's principles + concrete platform choices |
| 35 | TrieDataStructure.md | 3163 | CS | Implementation + use cases (autocomplete, IP routing) |
| 36 | ConcurrencyDistributed.md | 3235 | CS | Merge overlap with ConcurrencyPatterns (#66) |
| 37 | AgentPlanning.md | 3300 | AGT | Tree-of-Thought, LATS, MCTS-for-agents |
| 38 | ContainerOrchestration.md | 3398 | ENG | Kubernetes depth, not fluff |
| 39 | DomainDrivenDesign.md | 3390 | CS | Evans vocabulary with real bounded-context examples |
| 40 | ApiRateLimitingAlgorithms.md | 3180 | ENG | Token bucket / leaky bucket / sliding window with code |
| 41 | AiHallucinationMitigation.md | 2935 | AGT | Benchmarks + concrete techniques (RAG, constrained gen) |
| 42 | BlamelessPostMortems.md | 3132 | ENG | Template + real-world excerpt |
| 43 | PostgresqlAdvancedFeatures.md | 3128 | DB | LISTEN/NOTIFY, GIN, pgvector, partitioning |
| 44 | CompilerDesignBasics.md | 3317 | CS | Lex → parse → IR → codegen with a toy language |
| 45 | FederatedKnowledgeGraphs.md | 3015 | AGT | Directly relevant to multi-source KG work |
| 46 | CrdtDataStructures.md | 3206 | CS | G-counter, PN-counter, OR-Set with proofs |
| 47 | DatabaseDesignPatterns.md | 3401 | DB | Normalization patterns + anti-patterns |
| 48 | ResponsibleAiDeployment.md | 2901 | AGT | NIST AI RMF, EU AI Act mapping |
| 49 | ApacheSparkFundamentals.md | 3194 | DB | DAG + shuffle semantics with code |
| 50 | FunctionalAnalysis.md | 2985 | MATH | Banach/Hilbert spaces — currently pompous, can be rigorous |
| 51 | BayesianReasoning.md | 3184 | MATH | Worked posterior examples, PyMC snippets |
| 52 | JavaConcurrencyPatterns.md | 3362 | CS | CompletableFuture, virtual threads, structured concurrency |
| 53 | DatabaseMigrationStrategies.md | 3261 | DB | Expand/contract, dual-write, backfill patterns |
| 54 | NeuralNetworkArchitectures.md | 2947 | AI | Transformer/CNN/RNN/MoE comparison table |
| 55 | PrivacyPreservingLLM.md | 3146 | AGT | DP, federated, confidential compute specifics |
| 56 | LinearAlgebra.md | 2945 | MATH | Foundational; link heavily from ML pages |
| 57 | MultiModalAiApplications.md | 3023 | AGT | Vision+text agents with real use cases |
| 58 | GrpcFundamentals.md | 3221 | CS | Protobuf IDL + streaming patterns |
| 59 | DistributedComputingAlgorithms.md | 3018 | CS | Vector clocks, gossip, quorum — concrete |
| 60 | AgentMemory.md | 2811 | AGT | Short/long-term, episodic, semantic — with storage options |
| 61 | BloomFilters.md | 3008 | CS | Math derivation + implementation |
| 62 | DistributedTracing.md | 3005 | ENG | OpenTelemetry examples |
| 63 | GradientDescentAndOptimizers.md | 3200 | AI | SGD → Adam → Lion with animations/plots |
| 64 | NoSqlDatabaseTypes.md | 3287 | DB | Wide-column, document, graph, KV — when each wins |
| 65 | GraphAlgorithmsDeepDive.md | 3077 | CS | BFS/DFS/Dijkstra/A*/max-flow with runtime analysis |
| 66 | ConcurrencyPatterns.md | 3368 | CS | Consider merging with #36 |
| 67 | ApacheKafkaFundamentals.md | 3058 | DB | Partitions, consumer groups, exactly-once |
| 68 | RagImplementationPatterns.md | 3050 | AGT | Naive/advanced/modular RAG with your own harness data |
| 69 | CqrsPattern.md | 3240 | CS | Real event store + projection example |
| 70 | AiPairProgramming.md | 3071 | AGT | Cursor/Copilot/Claude Code comparison |
| 71 | LastMileDeliveryOptimization.md | 2871 | ENG | Arguable off-scope; rewrite only if you care |
| 72 | MarkovChainFundamentals.md | 3152 | MATH | Transition matrices + stationary distributions |
| 73 | DarkLaunchPatterns.md | 2940 | ENG | Feature flags, shadow traffic |
| 74 | ClusteringAlgorithms.md | 3031 | AI | K-means/DBSCAN/HDBSCAN with visualizations |
| 75 | WebPerformanceOptimization.md | 3116 | ENG | Core Web Vitals, LCP/INP/CLS |
| 76 | AiMemoryAndPersistence.md | 3114 | AGT | Vector + graph memory architectures |
| 77 | AiForSoftwareTesting.md | 2994 | AGT | Concrete tools (Copilot, Testim) |
| 78 | AbstractAlgebra.md | 3188 | MATH | Keep math; strip pomposity |
| 79 | OauthAndOidcDeepDive.md | 3079 | SEC | Concrete flows with JWTs, PKCE |
| 80 | AgentReasoning.md | 3166 | AGT | CoT, ToT, self-consistency benchmarks |
| 81 | DomainAndIntegrationEvents.md | 2856 | CS | Distinction matters; needs examples |
| 82 | TypeSystemsComparison.md | 3055 | CS | Nominal/structural, dependent types, refinement |
| 83 | GraphDatabaseFundamentals.md | 3048 | DB | Neo4j/JanusGraph/TigerGraph with Cypher |
| 84 | ConvolutionalNeuralNetworks.md | 3144 | AI | Filters/strides/receptive fields with code |
| 85 | EventDrivenArchitecture.md | 3138 | CS | Event sourcing vs messaging distinction |
| 86 | JsonbInPostgresql.md | 2926 | DB | GIN index, query operators, perf notes |
| 87 | NetworkOptimization.md | 3415 | ENG | TCP tuning, HTTP/2, HTTP/3 |
| 88 | RedisPatterns.md | 2899 | DB | Rate limit, session, pub/sub, streams |
| 89 | OpenSourceLLMs.md | 2988 | AGT | Llama / Mistral / Qwen / DeepSeek comparison |
| 90 | ObserverPattern.md | 3072 | CS | Minimal example + when to prefer signals/reactive |
| 91 | DecoratorPattern.md | 2958 | CS | Python decorator vs GoF decorator, middleware analogue |
| 92 | DesignPatternsOverview.md | 3357 | CS | Hub page connecting individual pattern pages |
| 93 | ModelQuantization.md | 3056 | AI | GGUF/GPTQ/AWQ with perf-vs-quality curves |
| 94 | CrossFunctionalTeamCollaboration.md | 2950 | ENG | Borderline; rewrite only if team-topic stays |
| 95 | AiEvaluationAndBenchmarks.md | 2746 | AGT | MMLU/HumanEval/SWE-bench/LMArena concretely |
| 96 | NumberTheory.md | 3036 | MATH | Hook it to cryptography pages |
| 97 | AgentObservability.md | 3030 | AGT | Trace + eval unified — your own system is the example |
| 98 | EmbeddingsVectorDB.md | 2930 | AGT | Pair with #15 |
| 99 | EncryptionFundamentals.md | 3083 | SEC | Symmetric/asymmetric with real algorithm list |
| 100 | BlueGreenDeployments.md | 2682 | ENG | Concrete AWS/Kubernetes examples |

Eight honorable mentions that just missed the 100 cut: `FineTuningLargeLanguageModels`, `ReactiveProgramming`, `SingletonPatternAndAlternatives`, `AgentLoops`, `DatabasePartitioning`, `ContainerSecurity`, `OntologyDesignPatterns`, `AgenticArchitecture`.

---

## Part 4 — A guide to making the content dramatically better

Everything below follows from one observation: **the default production path of this wiki is "prompt an LLM → paste output → add frontmatter."** That path works at scale but costs you every editorial property that makes a wiki worth reading.

### 4.1 Kill the auto-generated voice

Every single auto-generated page shares these tells. Adopt the opposite as a style guide:

| Slop pattern | Replace with |
|---|---|
| "Welcome. If you are reading this, you are not looking for a refresher…" | A first sentence that states the most interesting fact the reader will learn |
| "Target audience: seasoned researcher" | No target-audience section; the reader self-selects |
| "For those of us who have wrestled with…" | A specific war story, one paragraph, with concrete detail |
| "This tutorial is not for novices" | Nothing. If your content is good, difficulty is self-evident |
| Roman numerals (I., II., III.) | Flat `##` headings with plain nouns |
| `$P_{req} = P_{cook} + P_{refrig}$` inside a cooking article | Math only where it earns its keep (algorithm complexity, ML, crypto) |
| "Systemic reintegration manifold" | Plain nouns that a colleague would actually say |
| Zero examples, zero sources | At least one worked example, at least one primary-source link |

Rule of thumb: if a sentence could appear verbatim in an article on any other topic, delete it.

### 4.2 Invert the content ratio

Today the wiki is ~78% auto-generated, ~22% hand-written. Invert that. Auto-generated content should be the **scaffold**, not the delivered product. The delivered product should be **your** opinions, **your** data, **your** examples — with AI drafts feeding the skeleton you fill in.

A pragmatic workflow:

1. **Generate a draft** with your existing prompts — same as today.
2. **Strip it to its outline** — headings and topic sentences only. 80% deletion is normal.
3. **Add one concrete example per section** — code snippet, diagram, real incident, measured number, cited paper.
4. **Add one opinion per page** — a position a reasonable expert could disagree with. That's what makes it a wiki and not a Wikipedia mirror.
5. **Add a "See also" that links across clusters** — the current pages link within clusters only. Cross-cluster links are where the wiki compounds.

You already have a template for what this looks like: `AcceleratingAiLearning.md`. Re-read it and notice what it does that the auto-gen pages don't: concrete weekly cadences, a named "Learning Paradox", a table of *specific* learning traps with specific alternatives, a "Measuring Your Progress" section with five named signals. None of that would survive in the slop template.

### 4.3 Treat `auto-generated: true` as a technical-debt flag, not a feature

Make `auto-generated: true` a **WIP marker**, not a published-state marker. A page with this flag is a draft; once you do a real editorial pass, flip it off. Build the admin UI around that signal:

- Dashboard card: "547 pages still flagged auto-generated — sorted by view count / inbound links / topic importance."
- Editor badge: show a "draft" banner in the reader for any page with the flag.
- Retrieval weighting: down-rank pages with the flag in hybrid search (your `HybridRetrieval` already supports this — feed it as a feature).
- Scheduled audit: refuse to surface agent-facing content (`/api/pages/{id}/for-agent` per `AgentGradeContentDesign`) from any page that still carries the flag.

This single change realigns the entire wiki's incentives: "auto-generated" stops being a neutral origin label and starts being a backlog signal.

### 4.4 Ruthless scope pruning

Your stated scope is tech / science / math / history / agentic coding. The wiki currently hosts:

- 160+ lifestyle / personal-finance / travel / immigration pages
- 40 JSPWiki plumbing pages
- 20+ internal dev docs that should live in `docs/adrs/` or inline in source
- 10+ legacy JSPWiki help pages

Total off-scope volume: ~230 pages, ~22% of the wiki. Delete them and the effective quality of the remaining 77% goes up because:

1. Search recall is no longer diluted by vanlife content ranking alongside `AgentLoops`.
2. Topic clusters tighten, so your cluster-based navigation works correctly.
3. The retrieval evaluation (`RetrievalExperimentHarness`) stops being distracted by irrelevant candidates.
4. Anyone reading the wiki knows what it is about.

This is also the cheapest quality improvement available. Deletion is O(1) per page; rewriting is weeks.

### 4.5 Build around your strengths

The content where you currently shine:

- **Your own project** (`HybridRetrieval`, `AgentGradeContentDesign`, `RetrievalExperimentHarness`, `StructuralSpineDesign`, `IndexingSupport`) — these are opinionated, specific, source-of-truth.
- **Recent events / intelligence summaries** — `2026IranWar`, `WorldWarOneMarkets`, `WorldWarTwoMarkets` are sourced and factual.
- **Practitioner guides** with your voice — `AcceleratingAiLearning`.

Use these as the **reference voice** for every rewrite. Pass two of the best through your prompt as few-shot examples before you generate any new article. The current prompt is producing the "I. Foundational Paradigms" template; a new prompt with your own clean pages as exemplars will produce something closer to your own voice.

### 4.6 A concrete, sequenced plan

A practical order of operations for the next four weeks:

| Week | Work | Output |
|---|---|---|
| 1 | Delete the 100 pages in Part 2. Spot-check 10 near-miss pages (the "nearby deletions" callout) and delete if correct. | ~100 pages gone, ~300K words of dead weight removed. |
| 2 | Rewrite the prompt. Use `AcceleratingAiLearning` and `WorldWarOneMarkets` as few-shot exemplars. Add style constraints: no roman numerals, no target-audience section, no gratuitous math, opening sentence = most interesting fact. | A new prompt template checked into the repo. |
| 3 | Regenerate the top 10 of the rewrite list (#1–#10) with the new prompt. Compare side-by-side with old. Revise prompt if needed. | 10 flagship rewrites, new prompt validated. |
| 4 | Bulk-regenerate #11–#50 with the validated prompt. Hand-edit each to add one example and one opinion. Flip `auto-generated: false`. | 50 pages at a new quality baseline; establishes a reviewable pattern for the remaining 50. |

After that, the remaining ~500 in-scope auto-gen pages become a steady backlog — 5 per week at 30 minutes each is ~2 years of occasional polishing, which is fine because the high-traffic ones will already have been fixed.

### 4.7 Measurable signals to track

Don't re-run this audit by eye next quarter — the quality goals should be measurable in the admin UI:

- **% of pages with `auto-generated: true`** — current 78%, goal < 20% within 6 months.
- **Average page ending with a concrete example** — grep for `` ``` ``, tables, images per page. Currently low; aim for ≥1 per page.
- **% of pages with a "See also" cross-cluster link** — today most only link within cluster.
- **% of pages with at least one external citation** — I saw almost none in my sample.
- **Retrieval harness score delta after rewrite batches** — your `RetrievalExperimentHarness` can quantify whether rewrites actually improve agent/reader utility.

---

## Appendix — where to find the raw data

- `temp/audit/metrics3.tsv` — per-page words/headings/frontmatter/auto/type/tags/links for all 987 pages
- `temp/audit/final_deletions.tsv` — 120 ranked deletion candidates (top 100 in this report + 20 near-miss)
- `temp/audit/final_rewrites.tsv` — 120 ranked rewrite candidates (top 100 in this report + 20 near-miss)
- `temp/audit/tags.tsv` — per-page tags (mostly junk — stemmed auto-tags, worth cleaning)
- `temp/audit/bucket_*.txt` — early classification buckets for sanity-checking
