---
canonical_id: 01KRQJ720J8NH33YNC8E83PE8H
type: article
tags:
- search
- evaluation
- mrr
- recall
- benchmarking
- testing
title: 'Evaluating Retrieval Quality: From Vibes to MRR'
relations:
- type: extension_of
  target_id: IndustrialSearchSystems
- type: component_of
  target_id: 01KQ0P44PK0X731A1NYX3X5B9V
summary: Technical guide to measuring search engine performance. Explains the core
  metrics of Recall@K, Mean Reciprocal Rank (MRR), and how to build a rigorous 'Gold
  Set' for retrieval benchmarking.
status: active
date: '2026-05-15'
cluster: wikantik-development
---

# Evaluating Retrieval Quality: From Vibes to MRR

Search quality is notoriously difficult to measure because "relevance" is subjective. Without a rigorous evaluation harness, every change to a search algorithm is just a "vibe check." Industrial systems use a **Gold Set** of queries to produce deterministic metrics.

## 1. The Core Metrics

### A. Recall@K
The percentage of queries for which the "ideal" document appears in the top $K$ results.
*   **Why it matters**: In a wiki or RAG system, if the answer isn't in the top 5 (Recall@5), the agent or human likely won't find it.

### B. Mean Reciprocal Rank (MRR)
MRR rewards the system for putting the correct answer as high as possible.

$$
\text{MRR} = \frac{1}{|Q|} \sum_{i=1}^{|Q|} \frac{1}{\text{rank}_i}
$$

Where $\text{rank}_i$ is the position of the first relevant document for query $i$.
*   **Intuition**: Getting the answer at rank 1 is twice as good as getting it at rank 2.

## 2. Building a "Gold Set"
A retrieval evaluation set (e.g., `eval/retrieval-queries.csv`) consists of triples: `(query, ideal_page, category)`.

### Quality Categories:
*   **Direct**: "Ollama Setup" $\rightarrow$ `OllamaSetup`. (Testing lexical accuracy).
*   **Synonym Drift**: "Running wiki in a container" $\rightarrow$ `WikantikOnDocker`. (Testing semantic/vector accuracy).
*   **Indirect/Hard**: "How do I deploy locally?" $\rightarrow$ `JspwikiDeployment`. (Testing reasoning and conceptual mapping).

## 3. The Continuous Evaluation Loop (CI/CD)
Search evaluation should be an automated part of the build pipeline, not a manual task.
1.  **Baseline**: Run the evaluation set and commit the results (Recall@5, MRR).
2.  **Experiment**: Modify a weight or add a reranking step.
3.  **Validate**: Run the evaluation again. If MRR drops, the "improvement" is a regression.

---
**External Deep Dive:**
- [Evaluation Measures for IR (Wikipedia)](https://en.wikipedia.org/wiki/Evaluation_measures_(information_retrieval)) — Deep dive into MAP and NDCG.
- [Mean Reciprocal Rank (Wikipedia)](https://en.wikipedia.org/wiki/Mean_reciprocal_rank) — Formal properties of MRR.

**See Also:**
- [Wikantik Search Refinement](WikantikSearchRefinement) — How to run the `search-eval` tool.
- [Industrial Search Systems](IndustrialSearchSystems) — The underlying architecture.
