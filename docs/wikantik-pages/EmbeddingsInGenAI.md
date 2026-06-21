---
status: active
date: '2026-04-24'
summary: Technical analysis of vector embeddings for Large Language Models, covering
  similarity metrics, vector quantization (PQ/SQ), and Matryoshka dimensionality.
tags:
- generative-ai
- embeddings
- nlp
- semantic-search
- vector-math
type: article
auto-generated: false
cluster: agentic-ai
canonical_id: 01KQ0P44Q3M85P40GPRDYZZP5S
title: Embeddings in Gen AI
---
# Embeddings: The Geometry of Meaning

An "Embedding" is a dense, high-dimensional vector representation ($\mathbb{R}^d$) that captures semantic relationships in a continuous space. For Retrieval-Augmented Generation (RAG), the choice of embedding model and distance metric is as critical as the LLM itself.

## 1. Distance Metrics: Cosine vs. Inner Product

The performance of your vector search depends on matching the search metric to the model's training objective.

- **Cosine Similarity:** Measures the angle between vectors. Robust to variations in text length but slower to calculate than dot product.

$$
\text{sim}(A, B) = \frac{A \cdot B}{\|A\| \|B\|}
$$
- **Inner Product (Dot Product):** Measures both angle and magnitude. Often used for models trained with contrastive learning. If vectors are normalized to unit length, Dot Product is mathematically equivalent to Cosine Similarity and significantly faster to compute.
- **Euclidean Distance (L2):** Measures the straight-line distance. Sensitive to vector magnitude; less common for pure text search but useful in multi-modal contexts.

## 2. Vector Quantization (Compression)

Storing raw `float32` vectors is memory-intensive (e.g., 1 million 1536-dim vectors take ~6GB). Databases use quantization to reduce this footprint:

- **Scalar Quantization (SQ):** Maps floating point values to a smaller set (e.g., `int8`). Reduces memory by 4x with minimal accuracy loss (~1-2%).
- **Product Quantization (PQ):** Segments the vector into sub-vectors and clusters them into "codes." This can achieve 10x-50x compression but significantly impacts recall.
- **Binary Quantization:** Reduces each dimension to a single bit (0 or 1). Extreme compression, best used as a first-pass filter before a high-precision re-ranking step.

## 3. Matryoshka Embeddings (Truncation)

Recent models (like OpenAI `text-embedding-3-large` or `nomic-embed-text`) are trained with **Matryoshka Representation Learning**. This allows you to truncate the vector dimensions (e.g., from 3072 down to 256) without a catastrophic loss in performance.

### Concrete Impact: Accuracy vs. Dimensions
| Dimensions | MTEB Score (Retrieval) | Storage (1M vectors) |
|---|---|---|
| 3072 | 54.9 | 12.0 GB |
| 1024 | 54.1 | 4.0 GB |
| 256 | 52.0 | 1.0 GB |

**Practical Rule:** Use 256 or 512 dimensions for initial retrieval to save memory, then use the full vector (if needed) only for the top 50 results.

## 4. The Embedding Pipeline

1.  **Chunking:** Split text into semantic blocks (e.g., 512 tokens with 50-token overlap).
2.  **Normalization:** Most models require input to be normalized (unit length) for dot product search.
3.  **Storage:** Save in a [vector database](EmbeddingsVectorDB) using HNSW or IVF indexing.

**Concrete Tip:** Always use a dedicated embedding model (e.g., `BAAI/bge-m3` or `nomic-embed-text`) rather than trying to extract hidden states from a generative model like Llama. Generative models are optimized for prediction, not vector space alignment.
