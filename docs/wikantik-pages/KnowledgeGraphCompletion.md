---
canonical_id: 01KQ12YDVE8863TEYGXSS7ESS3
title: Knowledge Graph Completion
type: article
cluster: agentic-ai
status: active
date: '2026-05-15'
tags:
- knowledge-graph
- knowledge-graph-completion
- embeddings
- graph-neural-networks
- entity-resolution
auto-generated: false
summary: Engineering missing-link prediction in Knowledge Graphs via translational
  distance models (RotatE), bilinear forms (ComplEx), and LLM-augmented extraction
  pipelines.
related:
- KnowledgeGraphVsRelationalDatabase
- FederatedKnowledgeGraphs
- EntityResolutionTechniques
- GraphRAG
- RagImplementationPatterns
hubs:
- AgenticAiHub
---
# Knowledge Graph Completion

Knowledge Graph Completion (KGC) is the task of inferring missing triples `(s, r, o)` in a graph. In a production Wikantik instance, this isn't an academic exercise; it's the mechanism that turns a sparse set of extracted entities into a dense reasoning substrate for agents.

A complete KG allows an agent to answer "What is the security posture of the authentication service?" even if no single document explicitly links `AuthenticationService` to `OAuth2`.

## 1. The Geometry of Link Prediction

Link prediction assumes that entities and relations can be mapped to a continuous vector space where the truth of a triple is proportional to a score function $f_r(s, o)$.

### Translational Distance Models (TransE, RotatE)
In **TransE**, the relation is a translation vector:$\mathbf{s} + \mathbf{r} \approx \mathbf{o}$.
*   **Failure mode:** Cannot handle 1-to-N relations. If `(USA, has_state, NewYork)` and `(USA, has_state, California)`, TransE forces `NewYork` and `California` to the same vector.
*   **Production Fix (RotatE):** Maps entities to complex vectors$\mathbb{C}^d$and relations to rotations:$\mathbf{o} = \mathbf{s} \circ \mathbf{r}$, where$|\mathbf{r}_i| = 1$. This handles symmetry, antisymmetry, and inversion.

### Bilinear Models (ComplEx)
**ComplEx** uses the Hermitian dot product in complex space:$$f_r(s, o) = \text{Re}(\langle \mathbf{w}_r, \mathbf{e}_s, \bar{\mathbf{e}}_o \rangle)$$This is the state-of-the-art baseline for large-scale KGs because it scales linearly with entity count and captures asymmetric relations (e.g., `parent_of`) effectively.

## 2. LLM-Augmented Extraction (The Production Path)

While embedding models predict links from *existing* structure, LLMs extract links from *unstructured evidence*. In Wikantik, we use a verification loop:

```python
def verify_extracted_triple(subject, relation, obj, context_chunk):
    # 1. Structural Check
    if not kg.has_relation_type(relation):
        return False, "INVALID_RELATION"
    
    # 2. Embedding Consensus (using RotatE score)
    score = kg_embedding_model.score(subject, relation, obj)
    if score < THRESHOLD_ANOMALY:
        # If the model is shocked by this triple, require higher LLM confidence
        min_confidence = 0.95
    else:
        min_confidence = 0.70
        
    # 3. LLM Multi-Pass Verification
    return llm.verify(
        f"Does '{context_chunk}' prove ({subject}, {relation}, {obj})?",
        min_confidence=min_confidence
    )
```

## 3. Evaluation: Moving Beyond Hits@10

Standard academic benchmarks (FB15k-237) are often leaked into LLM training sets. For production KGC, you must measure:

| Metric | Why it matters | Calculation |
|---|---|---|
| **MRR (Mean Reciprocal Rank)** | Rewards the model for putting the truth at #1 vs #10. |$\frac{1}{|Q|} \sum_{i=1}^{|Q|} \frac{1}{rank_i}$|
| **Filtered Hits@1** | Strict accuracy. "Filtered" means we don't penalize the model for picking a *different* true triple that isn't the ground truth for this specific test case. | Count of true positives at rank 1 / total queries |
| **Relation-Specific Precision** | Some relations (e.g., `is_a`) are easier than others (`impacts`). |$\frac{TP}{TP + FP}$ per relation type |

**Critical Trap:** Beware of "Entity Leakage." If your training set contains `(A, part_of, B)` and your test set contains `(B, contains, A)`, a simple model will "predict" the link via inversion without understanding the semantics.

## 4. Implementation Checklist

1.  **Entity Resolution First:** If `OpenAI` and `OpenAI Inc.` are separate nodes, KGC will fail. Perform hard-string normalization and LLM-based fuzzy matching before training embeddings.
2.  **Negative Sampling:** To train, you need "false" triples. Generate these by corrupting true triples (replace `o` with a random entity `o'`). 
3.  **Graph Neural Networks (CompGCN):** If your graph has high-order dependencies (A affects B, B affects C, therefore A affects C), use a GCN layer to propagate features before the scoring function.
4.  **Schema Enforcement:** Never allow an LLM to invent a relation type. Use a `RelationRegistry` to map LLM-produced strings to canonical IDs.

## Further Reading
* [EntityResolutionTechniques](EntityResolutionTechniques)
* [GraphRAG](GraphRAG)
* [KnowledgeGraphVsRelationalDatabase](KnowledgeGraphVsRelationalDatabase)
