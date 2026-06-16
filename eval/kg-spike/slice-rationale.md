# KG-rerank trial slice — A0 headroom validation

Slice = relational questions with >=1 gold MISSED @12 at boost=0 (objective headroom).
Reachability deferred to post-re-extraction (mentions=0 at baseline).

| q | query | gold page | section | hit@12 | in slice |
|---|---|---|---|---|---|
| r01 | how does Wikantik hybrid retrieval dec | HybridRetrieval | Wiring | ✗ MISS | yes |
| r01 | how does Wikantik hybrid retrieval dec | HybridRetrieval | Fail-closed behaviour | ✓ | yes |
| r02 | what configuration enables the Knowled | KnowledgeGraphRerank | Configuration | ✓ | yes |
| r02 | what configuration enables the Knowled | HybridRetrieval | Configuration | ✗ MISS | yes |
| r05 | how do you add a page to the Knowledge | KgInclusionPolicy | The decision model | ✓ | yes |
| r05 | how do you add a page to the Knowledge | KgInclusionPolicy | Agent curation path | ✗ MISS | yes |
| r06 | how does chunking strategy affect RAG  | RagImplementationPatterns | The second biggest: chunking strat | ✓ | yes |
| r06 | how does chunking strategy affect RAG  | AiPoweredSearch | Stage by stage | ✗ MISS | yes |
| r07 | what metrics does the retrieval experi | RetrievalExperimentHarness | 1. What gets compared | ✗ MISS | yes |
| r07 | what metrics does the retrieval experi | RetrievalExperimentHarness | 3. One-shot run | ✓ | yes |
| r08 | how does canary deployment differ from | CanaryDeployments | 1. Traffic Splitting Mechanisms | ✗ MISS | yes |
| r08 | how does canary deployment differ from | BlueGreenDeployments | When canary wins | ✓ | yes |
| r10 | how does graph RAG differ from standar | GraphRAG | What graph RAG adds | ✗ MISS | yes |
| r10 | how does graph RAG differ from standar | GraphRAG | Architectures | ✗ MISS | yes |
