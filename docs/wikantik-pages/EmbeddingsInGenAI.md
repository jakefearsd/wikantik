---
cluster: generative-ai
canonical_id: 01KQ0P44Q3M85P40GPRDYZZP5S
title: Embeddings in Gen AI
type: article
tags:
- generative-ai
- embeddings
- nlp
- semantic-search
- vector-math
status: active
date: 2025-05-15
summary: Technical analysis of vector embeddings for Large Language Models. Covers cosine similarity, dimensionality, and embedding model selection.
auto-generated: false
---

# Embeddings: The Geometry of Meaning

An "Embedding" is a dense, high-dimensional vector representation of a discrete object (word, sentence, or image) that captures its semantic relationships in a continuous space.

## 1. Vector Space and Similarity

Embeddings map symbols into a space $\mathbb{R}^d$. The "meaning" of a word is defined by its position relative to others.
*   **Cosine Similarity:** The most common metric for comparing text embeddings. It measures the angle between two vectors, ranging from -1 to 1.
    $$\text{sim}(A, B) = \frac{A \cdot B}{\|A\| \|B\|}$$
*   **Interpretation:** A similarity of 0.95 between "king" and "queen" indicates high semantic proximity, regardless of the words' literal character overlap.

## 2. Technical Specifications: Dimensionality

The dimensionality ($d$) of an embedding determines its representational power and its computational cost.
*   **Small Models:** 384–768 dimensions (e.g., `all-MiniLM-L6-v2`). Fast, suitable for local CPU inference.
*   **Large Models:** 1536–4096 dimensions (e.g., OpenAI `text-embedding-3-small`). Captured deep nuance but require high-performance vector stores and longer inference times.

## 3. The Embedding Pipeline

1.  **Normalization:** Converting text to lowercase, removing excessive whitespace.
2.  **Inference:** Passing the text through an Encoder model (e.g., BERT or CLIP).
3.  **Storage:** Saving the resulting float array in a vector database ([EmbeddingsVectorDB](EmbeddingsVectorDB)).
4.  **Concrete Tip:** When using embeddings for search, you **must** use the exact same model for indexing the documents and for encoding the user query. Using different models results in mismatched vector spaces and 0% accuracy.

## 4. Multi-Modal Embeddings

Advanced models (like CLIP) can embed images and text into the *same* vector space.
*   **Concrete Use Case:** Searching a database of photos for "a sunset over mountains." The text query is converted to a vector, which is then compared against the vectors of the images. The highest similarity results are returned.

---
**See Also:**
- [Embeddings Vector DB](EmbeddingsVectorDB) — Storing and querying vectors.
- [Context Window Management](ContextWindowManagement) — Pruning the input space.
- [Natural Language Processing](NaturalLanguageProcessing) — The linguistic foundation.
