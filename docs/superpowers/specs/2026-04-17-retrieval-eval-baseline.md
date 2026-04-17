# Retrieval Evaluation Baseline — 2026-04-17

Pre-embedding Lucene BM25 baseline for the full `docs/wikantik-pages/`
corpus (~986 markdown pages), measured against the v0 seed query set at
`wikantik-main/src/test/resources/retrieval-eval/queries.csv` (40 queries
across seven categories: `synonym-drift`, `indirect`, `multi-word-concept`,
`specific`, `general`, `business-process`, `hard`).

Reproduce via `com.wikantik.search.RetrievalEvalTest` (remove the
`@Disabled` annotation locally; the test writes a fresh copy of this
report to `wikantik-main/target/retrieval-eval-report.txt` and stdout).

This is the reference point for every future retrieval change (hybrid
BM25 + vector, cross-encoder reranker, graph expansion, query rewriting,
etc.). Expect to re-run and diff the report.

---

```
Retrieval evaluation — Lucene BM25 baseline
Date: 2026-04-17T21:55:22.742551096Z
Queries: 40

recall@5  = 0.550
recall@20 = 0.775
MRR       = 0.524

Per-category breakdown:
  category                 n  recall@5 recall@20    MRR
  business-process         5     0.400     0.400  0.261
  general                  5     0.200     0.800  0.277
  hard                     5     0.400     0.600  0.331
  indirect                 8     0.500     0.625  0.462
  multi-word-concept       5     0.800     1.000  0.711
  specific                 5     0.800     1.000  0.829
  synonym-drift            7     0.714     1.000  0.743

rank   @20?    cat       ideal_page                                query
-----  ------  --------  ----------------------------------------  -----------------------------------------------------------
135    no      indirect  JspwikiDeployment                         how do I deploy the wiki locally
13     yes     synonym…  EmbeddingsVectorDB                        which embedding model should we pick for local RAG
1      yes     specific  OllamaSetup                               Ollama installation
1      yes     synonym…  WikantikOnDocker                          running Wikantik in a container
7      yes     indirect  PostgreSQLLocalDeployment                 setting up postgres on my laptop
1      yes     specific  CorsDeepDive                              cors preflight handling
2      yes     multi-w…  FullTextSearchInPostgresql                full text search in relational database
1      yes     multi-w…  LocalRAG                                  local retrieval augmented generation
1      yes     synonym…  BlueGreenDeployments                      blue green release strategy
2      yes     indirect  CanaryDeployments                         gradual rollout with traffic shifting
7      yes     specific  OAuthImplementation                       oauth authorization code flow
39     no      indirect  FullOAuth                                 single sign on with identity providers
1      yes     synonym…  ObjectStoragePatterns                     storage patterns for files
4      yes     busines…  SupplyChainSecurity                       how do we protect against supply chain attacks
27     no      busines…  CustomerRelationshipManagement            onboarding a new client
1      yes     busines…  SprintPlanning                            how to plan a sprint
193    no      busines…  IncidentManagement                        handling a production outage
66     no      busines…  ProductRoadmapping                        roadmap planning process
1      yes     specific  CodeReviewPractices                       code review best practices
1      yes     multi-w…  AdminSecurityUi                           admin ui for security settings
1      yes     indirect  DatabaseBackedPermissions                 role based access permissions stored in the database
—      no      hard      KubernetesBasics                          k8s
1      yes     hard      CiCdPipelines                             ci cd
1      yes     multi-w…  DatabaseBackupStrategies                  backup strategy for the database
1      yes     synonym…  DatabaseMigrationStrategies               schema migration approach
8      yes     synonym…  AiPoweredSearch                           semantic search with embeddings
13     yes     general   GraphRAG                                  knowledge graph retrieval
1      yes     indirect  MultimodalEmbeddings                      images and text embeddings together
47     no      indirect  AgentMemory                               how agents remember things
18     yes     multi-w…  AgentObservability                        evaluating and monitoring llm agents in production
1      yes     specific  AgentPromptEngineering                    prompt engineering for agents
1      yes     general   TestDrivenDevelopmentGuide                test driven development
1      yes     synonym…  TechnicalWritingGuide                     writing technical docs
8      yes     general   DockerSetup                               setting up docker for development
32     no      hard      ArtificialIntelligence                    ai
2      yes     hard      JavaMemoryManagement                      jvm gc
24     no      general   AgenticArchitecture                       overview of agent architectures
7      yes     general   OkrsAndGoalSetting                        objectives and key results
1      yes     indirect  OkrsAndGoalSetting                        setting goals for the quarter
8      yes     hard      BusinessMetricsAndKpis                    measuring what matters
```

## Observations

- `specific` and `multi-word-concept` categories already rank the ideal
  page at 1 most of the time — BM25 handles exact or near-exact phrase
  queries well.
- `business-process` is the weakest category (recall@20 = 0.400). Natural
  language phrasings like "handling a production outage" or "onboarding a
  new client" don't match page titles on the literal token level.
- The single `k8s` abbreviation query returns no match at all (rank = `—`,
  i.e. not in the full result list). BM25 over titles + body gives zero
  evidence for the unexpanded alias.
- Nine queries miss the top-20 entirely — these are the headline targets
  for the next iteration (hybrid embeddings, synonym expansion, or a
  curated alias/redirect table).
