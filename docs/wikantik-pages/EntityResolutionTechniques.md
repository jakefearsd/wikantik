---
cluster: machine-learning
canonical_id: 01KQ0P44Q5E9YD8KC2K91KTZYK
title: Entity Resolution Techniques
type: article
tags:
- machine-learning
- data-quality
- deduplication
- matching
- record-linkage
status: active
date: 2025-05-15
summary: Technical analysis of Entity Resolution (ER) for data unification. Covers blocking strategies, Locality-Sensitive Hashing (LSH), and Fellegi-Sunter modeling.
auto-generated: false
---

# Entity Resolution: Record Linkage and Deduplication

Entity Resolution (ER) is the task of identifying and merging records that refer to the same real-world entity across disparate datasets.

## 1. The Multi-Stage ER Pipeline

Comparing every record against every other ($O(N^2)$) is impossible for large datasets. ER systems use a hierarchical approach:
1.  **Standardization:** Normalizing names (e.g., "Corp." $\to$ "Corporation"), addresses, and phone numbers.
2.  **Blocking:** Partitioning the dataset into "blocks" using a shared key (e.g., Zip Code + first 3 letters of Last Name).
3.  **Matching:** Calculating detailed similarity scores within blocks.
4.  **Clustering:** Grouping matched pairs into single entities using algorithms like Connected Components or Hierarchical Clustering.

## 2. Advanced Indexing: Locality-Sensitive Hashing (LSH)

LSH is used to perform "Fuzzy Blocking" by hashing similar items into the same bucket with high probability.
*   **MinHash LSH:** Efficient for Jaccard similarity. Documents are converted to $k$-shingles, then MinHashed.
*   **Concrete Example:** To find duplicate customer records with a Jaccard similarity $> 0.8$, use MinHash with 100 permutations and a band size of 5. This reduces the search space by $1000\times$ compared to a full scan.

## 3. Probabilistic Matching: Fellegi-Sunter

The Fellegi-Sunter model assigns weights to field agreements based on their uniqueness.
*   **m-probability:** Probability that a field matches given the records are a MATCH (high for accurate fields).
*   **u-probability:** Probability that a field matches given the records are a NON-MATCH (low for unique fields like SSN).
*   **Concrete Logic:** A match on "Last Name = Smith" provides low evidence (high $u$), while a match on "Social Security Number" provides high evidence (extremely low $u$).

## 4. Machine Learning for ER

Modern ER utilizes **Siamese Networks** to learn dense embeddings of records.
*   **Architecture:** Two identical BERT-style encoders process Record A and Record B.
*   **Loss Function:** Triplet Loss or Contrastive Loss, ensuring that matched records have a high **Cosine Similarity** ($> 0.9$) while non-matches are pushed apart in the vector space.

---
**See Also:**
- [Normalization And Denormalization](NormalizationAndDenormalization) — Preparing data for ER.
- [Embeddings In Gen AI](EmbeddingsInGenAI) — Using vectors for semantic matching.
- [Knowledge Graph Construction Pipeline](KnowledgeGraphConstructionPipeline) — Integrating resolved entities into a graph.
