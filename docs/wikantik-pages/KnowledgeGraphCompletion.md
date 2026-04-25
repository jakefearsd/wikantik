---
canonical_id: 01KQ12YDVE8863TEYGXSS7ESS3
title: Knowledge Graph Completion
type: article
cluster: agentic-ai
status: active
date: '2026-04-25'
tags:
- knowledge-graph
- knowledge-graph-completion
- embeddings
- graph-neural-networks
- entity-resolution
summary: Predicting missing facts in a knowledge graph — embedding-based methods,
  LLM-assisted extraction, and the evaluation traps.
related:
- KnowledgeGraphVsRelationalDatabase
- FederatedKnowledgeGraphs
- EntityResolutionTechniques
- GraphRAG
- RagImplementationPatterns
hubs:
- AgenticAi Hub
---
# Knowledge Graph Completion

A knowledge graph (KG) holds facts as `(subject, relation, object)` triples: `(Berlin, capital_of, Germany)`, `(GPT-4, made_by, OpenAI)`. Completion is the task of finding the triples that *should* be there but aren't — facts you haven't extracted yet, or facts implied by what you have.

In 2026 the field has two distinct camps: classical embedding-based completion (TransE, RotatE, ComplEx) that's been the academic standard for a decade, and LLM-assisted extraction that's become the production default since ~2023. They solve different problems and a serious knowledge-graph system uses both.

## What "completion" actually means

Two related but distinct tasks:

1. **Link prediction** — given `(subject, relation, ?)` or `(?, relation, object)`, predict the missing entity. "Who is the CEO of OpenAI?" → predict given `(OpenAI, ceo_of, ?)`.
2. **Triple classification** — given a candidate triple, decide whether it's true. "Is `(Anthropic, ceo_of, Dario Amodei)` true?"

The first is generative; the second is discriminative. Many algorithms do one and not the other; production systems usually need both.

A third thing that often gets called completion but isn't: **entity resolution** (deciding that `Anthropic` and `Anthropic PBC` refer to the same node). That's a separate, important problem; see [EntityResolutionTechniques].

## Embedding-based completion

The classical approach: learn a vector for each entity and each relation, in a shared space where true triples score higher than false ones.

Family by scoring function:

- **TransE** — `||subject + relation - object|| ≈ 0`. Simple, interpretable, fails on 1-to-many relations (like `directed_by_person → many films`).
- **DistMult** — `subject ⊙ relation · object`. Bilinear; symmetric in subject/object, so can't model asymmetric relations well.
- **ComplEx** — DistMult in complex space. Fixes asymmetry. Strong baseline.
- **RotatE** — relations as rotations in complex space. Generalises TransE; handles symmetry, antisymmetry, inversion, composition.
- **ConvE / ConvKB** — convolutional architectures over reshape entity embeddings. Stronger but slower.
- **CompGCN, R-GCN, KGE-GNN family** — graph neural networks that propagate information across the graph during training.

For production: ComplEx or RotatE are strong defaults. CompGCN if your graph has rich neighbourhood structure. The marginal gains across these is small; the big wins come from data quality and entity resolution, not the model.

Cost: training scales with `|E| × |R|` for negative sampling; tractable on graphs with millions of entities, painful past tens of millions.

## LLM-assisted completion

LLMs do something fundamentally different: they read text and emit triples. The flow is:

```
unstructured text → LLM with extraction prompt → candidate triples → 
  entity resolution → schema validation → KG insert
```

Strengths:

- **Coverage.** An LLM extracts hundreds of triples from a paragraph that an embedding model couldn't predict because they weren't in the training graph.
- **Open-vocabulary.** New entities and relations don't require retraining.
- **Reasoning.** The LLM can infer triples that aren't stated explicitly ("X is the CEO; CEO is a leadership role; therefore X has leadership role").

Weaknesses:

- **Hallucination.** LLMs invent plausible-sounding facts. Mitigation: ground in source text, require citation, post-validate.
- **Cost.** Extracting from millions of documents is expensive. Mitigation: prefilter for relevance; use cheaper models for extraction and stronger models for verification.
- **Inconsistency.** The same entity gets normalised differently across runs. Entity resolution becomes a first-class problem.

Practical extraction prompt:

```
Extract relations from the following text. Output JSON list of triples.
Schema: subject (string), relation (one of: ceo_of, founded, headquartered_in, ...), object (string).
Only emit triples directly supported by the text. Cite the source span for each.
If unsure, do not emit.

Text: {document}
```

Followed by validation (does the relation exist in the schema?), entity resolution (is this `Anthropic` already in the graph?), and confidence thresholding.

## Hybrid: where production systems live

Use embedding-based completion for **predicting facts within a known schema, given the structure of the graph**. Use LLM extraction for **discovering new facts from unstructured text**.

Flow:

1. LLM extracts candidate triples from new documents.
2. Entity resolution attaches them to existing nodes where possible.
3. Embedding model (trained on the existing graph) scores the candidate triples — high score = consistent with what we already know; low score = anomaly worth flagging for human review.
4. Verified triples added to the graph.
5. Embedding retrained periodically.

This wiki's own KG (see [KnowledgeGraphVsRelationalDatabase]) follows this rough pattern.

## Evaluation traps

Standard benchmarks (FB15k, WN18RR) are saturated and have known leakage between train and test sets — many models score artificially well. Newer benchmarks (CoDEx, OpenBG) are better but still imperfect.

For *your* KG, build a proper eval:

1. **Sample 100–500 known-true triples**, hide them, train without them.
2. **Sample 100–500 known-false triples** (close perturbations of true ones).
3. **Measure**: 
   - Hits@k (how often the true triple is in the top-k predictions).
   - MRR (mean reciprocal rank).
   - For triple classification: accuracy, precision/recall.

Re-run on every model change. Track over time.

For LLM extraction specifically, evaluate **precision** (of emitted triples, what fraction are correct) and **recall** (of true triples in the text, what fraction does the LLM find). Both need ground truth — usually a small human-labelled set.

## Common production problems

**Schema drift.** New relations appear in extraction without being added to the schema. Solution: schema is enforced at insert time; extraction prompt includes the current schema; new relation candidates queue for human review.

**Entity explosion.** Without good resolution, every spelling variant becomes a node. The graph grows but the data quality drops. Invest in [EntityResolutionTechniques] before scaling extraction.

**Stale embeddings.** Graph evolves; embeddings don't. Predictions get progressively worse. Schedule periodic retraining; trigger on graph-size or graph-edit thresholds.

**Wrong embeddings used for retrieval.** Some teams use KG embeddings for vector retrieval. They're trained for link prediction, not semantic similarity. Use a dedicated text embedding model for retrieval; keep KG embeddings for completion.

**LLM hallucination at scale.** A 1% hallucination rate × 1M extractions = 10,000 false triples in your KG. Validation gates are non-negotiable: source citation required, schema enforced, sample-audit human-review.

## When KG completion is overkill

- **Small structured datasets.** A relational database with a few well-designed tables doesn't need embeddings or LLM extraction. SQL queries do.
- **No reasoning needs.** If your application doesn't traverse relations to answer questions, the KG itself isn't earning its keep, let alone completion.
- **Static knowledge.** Wikipedia infoboxes don't change much; download them.

KG completion earns its keep when (a) you have an evolving, semi-structured corpus, (b) you need the graph for reasoning or retrieval, and (c) extraction quality matters enough to invest in evaluation.

## Tools

- **PyKEEN** — Python library for embedding-based KGE; comprehensive model zoo; good for research.
- **DGL-KE / TorchKGE** — PyTorch-native implementations.
- **LangChain LLMGraphTransformer / LlamaIndex KnowledgeGraphIndex** — LLM-based extraction pipelines.
- **GraphRAG (Microsoft)** — combined KG construction + retrieval over the KG.
- **Neo4j with embedding integration** — production graph storage with KG embedding extensions.
- **DBpedia Spotlight, REBEL** — older but still useful relation-extraction tools.

For a team starting in 2026: LangChain extraction → Neo4j or Postgres → PyKEEN for completion → custom eval harness. That stack covers most needs.

## Further reading

- [KnowledgeGraphVsRelationalDatabase] — when graphs are the right substrate
- [FederatedKnowledgeGraphs] — multi-source KGs
- [EntityResolutionTechniques] — the prerequisite
- [GraphRAG] — combining KG with RAG
- [RagImplementationPatterns] — alternative when retrieval is enough
