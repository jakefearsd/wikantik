---
canonical_id: 01KQEKGD9Z0VQ5MQP77NSZSNSP
title: Document Clustering Approaches
type: article
cluster: agentic-ai
status: active
date: '2026-05-24'
tags:
- clustering
- nlp
- topic-modeling
- embeddings
- bertopic
- hdbscan
summary: Practical workflow for unsupervised topic discovery in large corpora using embeddings, UMAP dimensionality reduction, and HDBSCAN clustering.
auto-generated: false
---
# Document Clustering Approaches

Document clustering surfaces latent themes in unstructured text without manual labeling. In the age of LLMs, clustering is primarily used for **Topic Discovery**, **Data Pruning** (removing redundant training samples), and **Semantic Navigation**.

## The Modern NLP Pipeline: BERTopic Pattern

The most robust architecture for clustering documents involves a four-step pipeline:

1. **Embedding:** Convert text into dense vectors (e.g., `all-MiniLM-L6-v2` or `BGE-base`).
2. **Dimensionality Reduction (UMAP):** High-dimensional vector space is sparse and prone to the "curse of dimensionality." UMAP reduces 768 dimensions to 5-10 while preserving local structure.
3. **Clustering (HDBSCAN):** Unlike K-Means, HDBSCAN does not require you to pre-define the number of clusters. It identifies dense "islands" of points and labels outliers as noise.
4. **Representation (c-TF-IDF):** Extract keywords from each cluster to give it a human-readable name.

```python
from bertopic import BERTopic
from umap import UMAP
from hdbscan import HDBSCAN

# Configure for density-based clustering
umap_model = UMAP(n_neighbors=15, n_components=5, min_dist=0.0, metric='cosine')
hdbscan_model = HDBSCAN(min_cluster_size=10, metric='euclidean', prediction_data=True)

topic_model = BERTopic(
  umap_model=umap_model, 
  hdbscan_model=hdbscan_model,
  calculate_probabilities=True
)

topics, probs = topic_model.fit_transform(my_documents)
```

## Comparison of Algorithms

| Algorithm | Pros | Cons | Best for |
|---|---|---|---|
| **K-Means** | Fast, simple. | Must pick $K$; assumes spherical clusters. | Balanced, well-known datasets. |
| **HDBSCAN** | Handles outliers; no $K$ needed. | Computationally intensive for >1M rows. | Noisy, real-world text. |
| **LDA** | Probabilistic; interprets well. | Struggles with short text; needs heavy tuning. | Old-school "bag of words" corpora. |

## Handling Outliers (The -1 Label)

A common mistake in clustering is forcing every document into a cluster. HDBSCAN assigns a `-1` label to documents that are too distant from any dense neighborhood. 

**Practitioner Strategy:** If >30% of your data is labeled -1, your embedding model is likely too general or your `min_cluster_size` is too high. Do not force-cluster outliers; they are often the most valuable signal for "emerging topics" or "garbage data."

## Evaluation: Beyond Visualizing

Don't just look at a 2D scatter plot. Use quantitative metrics:

- **Coherence Score:** Do the top words in a cluster actually make sense together?
- **Silhouette Score:** How well-separated are the clusters in vector space?
- **Stability:** If you re-run the clustering on a 90% sample, do the same clusters emerge?

## Advanced Case: Clustering for RAG
Use clustering to build a "Table of Contents" for a RAG system. Instead of searching a flat list of 1 million chunks, the agent can first identify the relevant **Cluster Centroid**, then search only the documents within that cluster. This reduces noise and improves retrieval speed.

## Further Reading
- [[EmbeddingsVectorDB]] — Choosing the right model for step 1.
- [[KnowledgeGraphCompletion]] — Turning clusters into structured entities.
- [[AnomalyDetectionTechniques]] — Detecting the -1 "Noise" points.
