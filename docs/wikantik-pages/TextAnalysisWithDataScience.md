---
date: 2024-05-16T00:00:00Z
summary: A technical exploration of text representation techniques, contrasting sparse
  TF-IDF vectors with dense Transformer embeddings and detailing the RRF algorithm
  for hybrid retrieval.
auto-generated: false
type: article
tags:
- nlp
- embeddings
- tf-idf
- hybrid-search
- rrf
cluster: machine-learning
canonical_id: 01KQ0P44XKXR318NY4K5P5KGVP
title: Text Analysis with Data Science
hubs:
- AnomalyDetectionTechniques Hub
---
# Text Representation: Sparse vs. Dense

The fundamental challenge in text analysis is transforming discrete linguistic tokens into numerical vectors that capture semantic relationships.

## 1. Sparse Vectors: TF-IDF and BM25

Sparse representations rely on exact keyword matches.
*   **TF-IDF (Term Frequency-Inverse Document Frequency):** Weighs words by their frequency in a document relative to their rarity across the corpus.
*   **BM25:** A more robust evolution of TF-IDF used in engines like Elasticsearch. It adds non-linear term frequency saturation and document length normalization.

**Pros:** Extremely fast, mathematically interpretable, excellent for domain-specific jargon.
**Cons:** Fails on synonyms (the "Lexical Gap"). A query for "puppy" will not find a document containing "dog."

## 2. Dense Vectors: Transformer Embeddings

Dense representations (e.g., BERT, Ada-002) map text into a continuous high-dimensional vector space ($\mathbb{R}^d$).
*   **Mechanism:** Deep neural networks learn to place semantically similar phrases (e.g., "how to bake" and "oven instructions") in close proximity in the vector space.

**Pros:** Handles semantic similarity, polysemy, and multilingual search.
**Cons:** Computationally expensive (requires GPUs), prone to "hallucinating" relevance on irrelevant exact matches.

## 3. Hybrid Search and RRF (Reciprocal Rank Fusion)

The current state-of-the-art in information retrieval is **Hybrid Search**: combining sparse (lexical) and dense (semantic) results to get the best of both worlds.

### 3.1 The RRF Algorithm
**Reciprocal Rank Fusion (RRF)** is a standard way to merge the ranked lists from two disparate scoring systems without needing to normalize their raw scores.

The RRF score for a document $d$ is calculated as:

$$
score(d) = \sum_{r \in R} \frac{1}{k + rank(d, r)}
$$

*   **$R$:** The set of rankers (e.g., `{BM25, Dense}`).
*   **$rank(d, r)$:** The position of document $d$ in the results of ranker $r$.
*   **$k$:** A constant (usually 60) that mitigates the impact of low-ranked documents.

**Why RRF works:** It rewards documents that appear consistently high in both lists, while penalizing those that only appear at the bottom of one. It is robust to outliers and requires zero parameter tuning compared to weighted sums.

## 4. Evaluation Metrics
*   **NDCG (Normalized Discounted Cumulative Gain):** Measures the quality of the ranking based on the relevance of the results.
*   **Recall@K:** The percentage of relevant documents found in the top $K$ results.
