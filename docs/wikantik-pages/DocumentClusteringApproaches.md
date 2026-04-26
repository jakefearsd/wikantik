---
title: Document Clustering Approaches
type: article
cluster: agentic-ai
status: active
date: '2026-04-25'
tags:
- clustering
- nlp
- topic-modeling
- embeddings
- bertopic
summary: Embedding + clustering as the modern way to surface topics from a
  document corpus — UMAP + HDBSCAN, BERTopic, and the alternatives that still
  fit (LDA for highly-structured corpora).
related:
- ClusteringAlgorithms
- EmbeddingsVectorDB
- KnowledgeGraphCompletion
- HybridRetrieval
hubs:
- AgenticAi Hub
---
# Document Clustering Approaches

Document clustering is "find groups of semantically similar documents." It's an unsupervised analytics task with several practical applications: topic discovery in a corpus, content deduplication, recommendation by similarity, exploratory analysis of new datasets.

Pre-2020, this was primarily Latent Dirichlet Allocation (LDA) and other probabilistic topic models. Post-2020, embedding-based approaches (BERTopic, Top2Vec) decisively beat LDA on most tasks.

## The modern pattern

Three steps:

1. **Embed** each document into a dense vector. Use a sentence transformer (e.g., `all-MiniLM-L6-v2`, `BGE-base`, OpenAI's `text-embedding-3-small`).
2. **Reduce dimensionality** of the embeddings. UMAP from 384/768 dims down to 5-15. Helps clustering algorithms; removes noise.
3. **Cluster** the reduced embeddings. HDBSCAN is the modern default (handles variable-density clusters; doesn't require choosing K).

```
documents → SentenceTransformer → embeddings (384d)
         → UMAP (10d) → HDBSCAN → cluster labels
```

Each step has alternatives but this combination is robust. Used in BERTopic, Top2Vec, several commercial tools.

## Why each step

### Embeddings (vs bag-of-words)

Bag-of-words / TF-IDF clustering treats "automobile" and "car" as different. Embeddings treat them as nearly identical. For semantic clustering, embeddings dominate.

Choice of embedding model matters somewhat:

- **General-purpose** sentence embedding (BGE, MiniLM, SBERT) — good default for English text.
- **Multilingual** for mixed-language corpora.
- **Domain-specific** — fine-tuned on similar text — wins margins on specialised corpora (legal, medical).

The 768-dim general-purpose models are usually fine. Don't over-engineer.

### UMAP (vs PCA, t-SNE)

UMAP preserves both local and global structure better than t-SNE; runs faster on large data than PCA-then-t-SNE. Hyperparameters (`n_neighbors`, `min_dist`) affect output:

- `n_neighbors=15` (default) typical.
- `min_dist=0.0` for cluster-friendly output.

UMAP is non-deterministic by default. Set `random_state` for reproducibility.

PCA is faster but only captures linear structure; usually worse for clustering. t-SNE optimises for visualisation, not clustering substrate.

### HDBSCAN (vs K-means, agglomerative)

HDBSCAN doesn't need you to choose K. It finds variable-density clusters; points in low-density regions get labelled as noise (label -1). This matches real corpora where some documents fit obvious clusters and others are outliers.

- `min_cluster_size` is the key hyperparameter — minimum number of points to form a cluster. Tune.
- `min_samples` — affects noise classification. Lower = fewer noise points.

K-means requires K and produces equally-sized spherical clusters; rarely matches real document distributions. Use HDBSCAN unless you have a specific reason.

## Tools

- **BERTopic** — Python; pipeline of the above plus topic representation. Most popular ready-to-use.
- **Top2Vec** — similar; alternative implementation.
- **Hugging Face `sentence-transformers`** — embedding model library.
- **`umap-learn`** — UMAP.
- **`hdbscan`** — HDBSCAN.
- **`scikit-learn`** — alternative clustering algorithms (K-means, agglomerative, DBSCAN, spectral).

For most teams, BERTopic + a good sentence embedding model is the right starting point. Custom pipelines when you need specific control.

## Topic representation

After clustering, you typically want to *describe* each cluster — "what's this cluster about?"

Approaches:

- **TF-IDF on cluster vs corpus** — words that are over-represented in the cluster. BERTopic's default.
- **c-TF-IDF** (BERTopic-specific) — class-based TF-IDF; more cluster-friendly.
- **Most-similar documents to centroid** — shows representative examples.
- **LLM summarisation** — feed top documents into an LLM and ask for a topic name. Modern; high quality.

For human-readable topic dashboards, LLM summarisation produces noticeably better names than TF-IDF keyword extraction.

## When LDA still fits

LDA assumes documents are mixtures of topics, where topics are distributions over words. The probabilistic framing is mathematically clean.

LDA still fits when:

- **Documents have stable, well-defined topical structure** (academic papers in fixed disciplines).
- **You need explicit topic-document distributions** for downstream probabilistic reasoning.
- **Vocabulary is the right unit of analysis** (specialised technical text where word choice carries strong signal).

LDA loses to embedding-based methods when:

- Documents discuss topics implicitly (chat logs, customer support tickets).
- Vocabulary is varied or noisy (informal text, multilingual).
- You want to handle short documents (LDA needs many words per document; embeddings work on single sentences).

For most modern corpora — web text, customer interactions, internal documents — embedding-based wins.

## Hierarchical clustering

For "explore the topic structure" use cases, hierarchical clustering produces a tree of clusters. BERTopic supports this out of the box; agglomerative clustering in scikit-learn handles general cases.

Visualisation: dendrograms, treemaps, sunburst charts. Useful for understanding "what are the high-level themes; what are the subthemes."

## Online / streaming clustering

For corpora that grow over time, you often want to assign new documents to existing clusters without re-running everything.

Pattern: cluster periodically; for new documents between cluster runs, find the nearest existing centroid and assign there. Handles incremental data; loses some quality (clusters don't reform around new centroids).

For high-velocity streaming, online clustering algorithms (online K-means, BIRCH) update incrementally. Trade-off: more complex; worse quality than periodic full reclustering.

## Failure modes

- **Wrong embedding model.** A general-purpose model on highly specialised text produces shallow clusters. Try domain-tuned.
- **Skipping UMAP.** Clustering raw 768-dim embeddings often produces noisy or all-noise output. UMAP helps.
- **Too-small `min_cluster_size`.** Lots of tiny clusters; loses the big picture. Increase.
- **Too-large `min_cluster_size`.** Everything gets lumped into a few clusters; lose detail. Decrease.
- **Treating noise as signal.** HDBSCAN's noise labels are real — those documents don't fit any cluster well. Don't force them.
- **Static clusters on dynamic data.** Re-cluster periodically; a year-old cluster definition will miss new topics.

## Evaluation

Clustering is unsupervised; "correct" is fuzzy. Useful metrics:

- **Silhouette score** — average separation; higher better.
- **Davies-Bouldin index** — lower better.
- **Number of noise points** (HDBSCAN-specific) — too many = `min_cluster_size` too high or embedding wrong.
- **Manual inspection** — sample documents from each cluster; do they cohere? This is the actual quality test.

For production: small labelled set (a human grouped 100-500 documents) lets you compute purity / NMI against your clustering. Most useful single eval.

## A pragmatic pipeline

For a new clustering task:

1. **Sample 1k-10k documents** from your corpus.
2. **Embed** with `sentence-transformers/all-MiniLM-L6-v2` for English. Or domain-specific.
3. **UMAP** to 10 dims, `n_neighbors=15`, `min_dist=0.0`, fixed `random_state`.
4. **HDBSCAN** with `min_cluster_size=15` initial; tune to corpus.
5. **Manual review** of clusters. Adjust `min_cluster_size`, embedding model.
6. **LLM-name** the clusters from representative documents.
7. **Run on full corpus** once the parameters are right.

A day's work; usually produces useful insight.

## Further reading

- [ClusteringAlgorithms] — broader clustering context
- [EmbeddingsVectorDB] — embedding model selection
- [KnowledgeGraphCompletion] — when relations matter beyond clusters
- [HybridRetrieval] — clusters as a retrieval-side feature
