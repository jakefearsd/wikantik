---
title: Document Clustering Approaches
type: article
tags:
- mathbf
- cluster
- text
summary: Document Clustering Approaches with K-Means Welcome.
auto-generated: true
---
# Document Clustering Approaches with K-Means

Welcome. If you are reading this, you are likely already familiar with the basic concepts of [Natural Language Processing](NaturalLanguageProcessing) (NLP) and unsupervised [machine learning](MachineLearning). You understand that raw text is inherently messy, high-dimensional, and context-dependent. The goal of document clustering is not merely to group documents; it is to impose a mathematically rigorous structure onto the amorphous chaos of human communication, allowing us to discover latent themes, topical boundaries, and underlying semantic relationships within a corpus.

This tutorial is not a "how-to" guide for a junior data analyst. It is a comprehensive, deeply technical exploration of the methodology, mathematical underpinnings, practical pitfalls, and advanced extensions of using K-Means clustering on textual data, suitable for expert software engineers and data scientists engaged in serious research.

---

## Ⅰ. Introduction: The Problem Space and Methodological Overview

### 1.1 The Challenge of Unstructured Data
Text data resides in a high-dimensional, sparse, and non-Euclidean space. Unlike structured data (e.g., tabular records where features are orthogonal and independent), the features in text—words—are highly correlated, context-dependent, and subject to synonymy (different words meaning the same thing) and polysemy (one word having multiple meanings).

Clustering, at its core, is an unsupervised technique designed to partition a dataset $\mathbf{X} = \{\mathbf{x}_1, \mathbf{x}_2, \dots, \mathbf{x}_N\}$ into $K$ distinct, non-overlapping subsets (clusters) $\mathbf{C} = \{C_1, C_2, \dots, C_K\}$, such that documents within the same cluster are maximally similar to each other, and dissimilar to documents in other clusters.

### 1.2 K-Means: The Foundational Algorithm
K-Means is an iterative, centroid-based clustering algorithm. Its objective is to minimize the within-cluster sum of squares (WCSS), also known as inertia. Given $K$ clusters, the algorithm seeks to find $K$ centroids $\{\boldsymbol{\mu}_1, \boldsymbol{\mu}_2, \dots, \boldsymbol{\mu}_K\}$ that best represent the data points.

The iterative process is deceptively simple:
1. **Initialization:** Select $K$ initial centroids $\boldsymbol{\mu}^{(0)}_k$.
2. **Assignment Step (E-Step):** Assign every data point $\mathbf{x}_i$ to the nearest centroid $\boldsymbol{\mu}_k$ based on a chosen distance metric $d(\mathbf{x}_i, \boldsymbol{\mu}_k)$.
$$ \text{Cluster}(i) = \arg\min_{k=1}^K d(\mathbf{x}_i, \boldsymbol{\mu}_k) $$
3. **Update Step (M-Step):** Recalculate the centroid $\boldsymbol{\mu}_k$ for each cluster by taking the mean of all assigned points:
$$ \boldsymbol{\mu}_k^{(t+1)} = \frac{1}{|C_k|} \sum_{\mathbf{x}_i \in C_k} \mathbf{x}_i $$
4. **Convergence:** Repeat Steps 2 and 3 until the centroids stabilize (i.e., the change in centroids or the WCSS falls below a predefined tolerance threshold $\epsilon$).

### 1.3 The Critical Bottleneck: Vector Representation
The success of K-Means hinges entirely on the quality of the input vectors $\mathbf{X}$. If the vectors do not accurately capture the semantic or topical essence of the documents, the resulting clusters will be meaningless, regardless of how perfectly the K-Means algorithm executes.

This tutorial will systematically explore the evolution of vectorization techniques, moving from classical frequency counts to modern contextual embeddings, while maintaining K-Means as the primary clustering engine.

---

## Ⅱ. The Vectorization Pipeline: Transforming Text into $\mathbb{R}^D$

The transformation from a corpus $\mathcal{D} = \{d_1, d_2, \dots, d_N\}$ of documents into a feature matrix $\mathbf{X} \in \mathbb{R}^{N \times D}$ is arguably the most critical, and often most under-discussed, part of the entire pipeline.

### 2.1 Baseline Approach: Bag-of-Words (BoW)
The simplest approach is to construct a vocabulary $\mathcal{V}$ from the entire corpus. Each document $d_i$ is then represented by a vector $\mathbf{x}_i$ of dimension $D = |\mathcal{V}|$, where each dimension $j$ corresponds to a word $w_j \in \mathcal{V}$. The value $x_{ij}$ is simply the count of word $w_j$ in document $d_i$.

**Limitations:**
1. **Sparsity:** The resulting matrix $\mathbf{X}$ is overwhelmingly sparse, leading to computational inefficiencies and potential numerical instability if not handled by specialized sparse matrix libraries (e.g., SciPy's CSR format).
2. **Lack of Weighting:** It treats all words equally. A common word like "the" contributes the same weight as a rare, topic-defining term like "quantum entanglement."

### 2.2 Improvement 1: Term Frequency-Inverse Document Frequency (TF-IDF)
TF-IDF is the industry standard improvement over raw BoW. It weights word occurrences by their *informativeness* across the entire corpus.

The TF-IDF score for a word $w_j$ in document $d_i$ is defined as:
$$ \text{TF-IDF}(w_j, d_i) = \text{TF}(w_j, d_i) \times \text{IDF}(w_j, \mathcal{D}) $$

Where:
1. **Term Frequency (TF):** Measures how often $w_j$ appears in $d_i$. A common formulation is:
$$ \text{TF}(w_j, d_i) = \frac{\text{Count}(w_j, d_i)}{|d_i|} $$
(Normalization by document length prevents longer documents from artificially inflating scores.)

2. **Inverse Document Frequency (IDF):** Measures the rarity of $w_j$ across the corpus $\mathcal{D}$.
$$ \text{IDF}(w_j, \mathcal{D}) = \log \left( \frac{N}{1 + \text{DF}(w_j)} \right) $$
Where $N$ is the total number of documents, and $\text{DF}(w_j)$ is the Document Frequency (the number of documents containing $w_j$). The $+1$ in the denominator is a common smoothing technique to prevent division by zero or overly aggressive weighting for very rare words.

**Implementation Note:** When using scikit-learn's `TfidfVectorizer`, the process handles the vocabulary building, TF calculation, and IDF scaling automatically, which is why it remains the default starting point for many academic examples [5].

### 2.3 Improvement 2: Advanced Semantic Embeddings (The Modern Necessity)
While TF-IDF excels at capturing *term importance* (what words are unique to a topic), it fails spectacularly at capturing *semantic similarity* (that "automobile" is close to "car," even if the corpus never uses the exact word).

For state-of-the-art research, one must move beyond count-based models to dense, continuous vector representations derived from deep learning models.

#### A. Word Embeddings (Word2Vec, GloVe)
These models map words into a lower-dimensional space (e.g., $\mathbb{R}^{100}$ or $\mathbb{R}^{300}$) such that the geometric distance between vectors approximates the semantic distance between words.

*   **Process:** Instead of creating a document vector by summing or averaging word vectors (which is a crude approximation), advanced techniques use the entire document context to generate a single, fixed-size document embedding.
*   **Aggregation:** Common aggregation methods include:
    *   **Averaging:** $\mathbf{x}_i = \frac{1}{|d_i|} \sum_{w_j \in d_i} \mathbf{v}_{w_j}$ (Simple, but loses word order).
    *   **Weighted Averaging:** Weighting by TF-IDF scores before averaging.

#### B. Contextual Embeddings (BERT, RoBERTa, etc.)
These are the current gold standard. Unlike Word2Vec, which assigns a single vector $\mathbf{v}_w$ to a word $w$ regardless of context, Transformer-based models generate *contextual* embeddings $\mathbf{v}_{w, \text{context}}$.

If a document is processed through a pre-trained BERT model, the output for every token is a high-dimensional vector that encodes its meaning *within that specific sentence*.

**Document Vector Generation with Transformers:**
1.  Tokenize the document $d_i$.
2.  Pass the tokens through the BERT encoder to obtain sequence embeddings $\{\mathbf{e}_1, \mathbf{e}_2, \dots, \mathbf{e}_{L}\}$.
3.  The final document vector $\mathbf{x}_i$ is often derived by taking the embedding corresponding to the `[CLS]` token, as this token is specifically trained to aggregate the meaning of the entire sequence.

**Why this matters for K-Means:** When using BERT embeddings, the input matrix $\mathbf{X}$ is already semantically rich, dense, and captures nuance far beyond what TF-IDF can manage. K-Means then clusters these dense semantic points in the embedding space.

---

## Ⅲ. K-Means Mechanics and Optimization

Since the vectorization step provides the input $\mathbf{X}$, we now focus on the mechanics of the clustering algorithm itself.

### 3.1 The Distance Metric: Euclidean vs. Cosine
The choice of distance metric $d(\mathbf{x}_i, \boldsymbol{\mu}_k)$ is paramount.

1.  **Euclidean Distance ($L_2$ Norm):**
    $$ d(\mathbf{x}_i, \boldsymbol{\mu}_k) = \sqrt{\sum_{j=1}^D (x_{ij} - \mu_{kj})^2} $$
    This is the standard distance used in basic K-Means implementations. It assumes that the absolute magnitude of the feature difference matters.

2.  **Cosine Similarity (and Distance):**
    Cosine similarity measures the cosine of the angle between two vectors, $\mathbf{x}_i$ and $\boldsymbol{\mu}_k$.
    $$ \text{Similarity}(\mathbf{x}_i, \boldsymbol{\mu}_k) = \frac{\mathbf{x}_i \cdot \boldsymbol{\mu}_k}{\|\mathbf{x}_i\| \|\boldsymbol{\mu}_k\|} $$
    The corresponding distance metric is $d_{\text{cosine}} = 1 - \text{Similarity}$.

**When to use which?**
*   **TF-IDF/Count Vectors (Sparse, Magnitude Matters):** Euclidean distance can sometimes be misleading because the magnitude of the vector (total word count or total TF-IDF weight) can dominate the angular relationship.
*   **Semantic Embeddings (Dense, Direction Matters):** Cosine distance is overwhelmingly preferred. In embedding space, the *direction* of the vector (the semantic relationship) is far more important than its *magnitude*. Using cosine distance normalizes the vectors, effectively focusing the distance calculation purely on the angular separation.

### 3.2 Initialization Strategies: Avoiding Poor Local Minima
The standard K-Means algorithm is highly susceptible to poor initial centroid placement, leading it to converge to a local minimum rather than the global optimum.

1.  **Random Initialization:** Selecting $K$ points uniformly at random from $\mathbf{X}$. (Generally discouraged for research.)
2.  **K-Means++:** This is the established best practice. It selects the first centroid randomly, and subsequent centroids are chosen with a probability proportional to the square of the distance from the nearest existing centroid. This ensures that initial centroids are spread out across the data manifold, leading to faster convergence and better final cluster quality.

### 3.3 Computational Complexity and Scalability
For a dataset of $N$ documents, $D$ dimensions, and $K$ clusters:
*   **Time Complexity (Per Iteration):** $O(N \cdot K \cdot D)$.
*   **Total Complexity:** $O(\text{Iterations} \cdot N \cdot K \cdot D)$.

When $N$ or $D$ is massive (as is common in large corpora), this complexity becomes prohibitive.

**Mitigation Strategies:**
1.  **Mini-Batch K-Means:** Instead of using the entire dataset $\mathbf{X}$ in each iteration, Mini-Batch K-Means processes small, random subsets (mini-batches) of the data. This drastically reduces the memory footprint and speeds up convergence for massive datasets, often with only a minor loss in accuracy. This is crucial for production-scale NLP pipelines.
2.  **Dimensionality Reduction (See Section Ⅴ):** Reducing $D$ via PCA or SVD directly reduces the complexity factor $D$, yielding massive computational savings.

---

## Ⅳ. The Complete Workflow: A Practical, Scalable Blueprint

We synthesize the previous sections into a robust, multi-stage pipeline.

### 4.1 Stage 1: Preprocessing and Cleaning (The Hygiene Phase)
The goal is to standardize the text input.

1.  **Tokenization:** Splitting text into meaningful units (tokens).
2.  **Normalization:** Converting all text to lowercase.
3.  **Noise Removal:** Filtering out HTML tags, URLs, and special characters.
4.  **Stop Word Removal:** Removing extremely common, low-information words (e.g., "a," "the," "is"). *Caution: For deep semantic models, this step should often be skipped or handled with extreme care, as stop words can sometimes provide necessary structural context.*
5.  **Lemmatization/Stemming:** Reducing words to their base form (e.g., "running" $\rightarrow$ "run"). Lemmatization (using dictionary knowledge) is generally preferred over stemming (heuristic chopping) for research quality.

### 4.2 Stage 2: Vectorization (The Feature Engineering Phase)
The choice here dictates the entire outcome.

**Option A: Classical (TF-IDF)**
Use `TfidfVectorizer` on the preprocessed corpus. The resulting matrix $\mathbf{X}_{\text{tfidf}}$ is sparse.

**Option B: Modern (Embeddings)**
1.  Load a pre-trained model (e.g., BERT).
2.  Process the entire corpus in batches, generating contextual embeddings.
3.  Aggregate these embeddings (e.g., using the `[CLS]` token output) to form the dense matrix $\mathbf{X}_{\text{embed}}$.

### 4.3 Stage 3: Dimensionality Reduction (The Compression Phase)
If $\mathbf{X}$ is extremely high-dimensional (e.g., $D > 10,000$ from TF-IDF, or $D=768$ from BERT), clustering can suffer from the "Curse of Dimensionality," where distance metrics become less meaningful as dimensions increase.

We apply linear dimensionality reduction techniques:

1.  **Principal Component Analysis (PCA):** A linear technique that finds the directions (principal components) in the data that maximize variance.
    $$ \mathbf{X}_{\text{reduced}} = \mathbf{X} \cdot \mathbf{W} $$
    Where $\mathbf{W}$ contains the top $d$ eigenvectors corresponding to the largest eigenvalues.
2.  **Truncated Singular Value Decomposition (Truncated SVD):** This is the preferred method when dealing with **sparse, count-based matrices** (like TF-IDF). SVD is mathematically equivalent to PCA when applied to the covariance matrix, but it is optimized for sparse [data structures](DataStructures).

The output $\mathbf{X}_{\text{final}}$ is the $N \times d$ matrix, where $d \ll D$.

### 4.4 Stage 4: Clustering (The Grouping Phase)
Apply K-Means to the reduced, dense feature space $\mathbf{X}_{\text{final}}$.

```python
from sklearn.cluster import KMeans
import numpy as np

# Assume X_final is the processed, reduced feature matrix (N x d)
K = 10  # The number of clusters we hypothesize

# Use K-Means++ initialization for robustness
kmeans = KMeans(n_clusters=K, init='k-means++', random_state=42, n_init='auto')

# Fit the model and predict cluster labels
cluster_labels = kmeans.fit_predict(X_final)

# cluster_labels is an array of size N, where each element is the cluster ID (0 to K-1)
```

### 4.5 Stage 5: Interpretation and Validation (The Scientific Rigor Phase)
A cluster assignment is meaningless without validation.

1.  **Elbow Method (Determining $K$):** Plotting the WCSS against varying $K$. The "elbow" point suggests the optimal $K$ where the marginal gain in WCSS reduction sharply diminishes.
2.  **Silhouette Score:** Measures how similar an object is to its own cluster compared to other clusters. Scores range from $-1$ (poor clustering) to $+1$ (dense, well-separated clustering).
    $$ s(\mathbf{x}_i) = \frac{b(\mathbf{x}_i) - a(\mathbf{x}_i)}{\max(a(\mathbf{x}_i), b(\mathbf{x}_i))} $$
    Where $a(\mathbf{x}_i)$ is the average distance from $\mathbf{x}_i$ to any other point in its cluster, and $b(\mathbf{x}_i)$ is the minimum average distance from $\mathbf{x}_i$ to any point in a *different* cluster. Maximizing the average silhouette score across all points is the goal.

---

## Ⅴ. Advanced Methodological Deep Dives (Research Extensions)

For expert research, relying solely on standard K-Means with TF-IDF is akin to using a slide rule when you should be using a quantum computer. We must explore the limitations and the advanced alternatives.

### 5.1 Gaussian Mixture Models (GMM) vs. K-Means
K-Means operates under the rigid assumption that clusters are spherical and equally sized, defined solely by their mean (centroid). This is rarely true in real-world text data.

**GMM** relaxes this assumption. Instead of assigning a point definitively to one cluster, GMM models the probability that a point belongs to *each* cluster, assuming the data within each cluster follows a multivariate Gaussian distribution.

The model estimates parameters ($\boldsymbol{\mu}_k$, $\boldsymbol{\Sigma}_k$, $\pi_k$) for each cluster $k$:
*   $\boldsymbol{\mu}_k$: The mean vector (centroid).
*   $\boldsymbol{\Sigma}_k$: The covariance matrix (captures the shape and orientation of the cluster).
*   $\pi_k$: The mixing weight (the prior probability of a point belonging to cluster $k$).

**The Expectation-Maximization (EM) Algorithm:** GMMs are typically fitted using the EM algorithm, which iteratively estimates the parameters:
1.  **E-Step (Expectation):** Calculate the *responsibilities* $\gamma_{ik}$, which is the probability that point $\mathbf{x}_i$ belongs to cluster $k$, given the current parameters.
$$ \gamma_{ik} = \frac{\pi_k \mathcal{N}(\mathbf{x}_i | \boldsymbol{\mu}_k, \boldsymbol{\Sigma}_k)}{\sum_{j=1}^K \pi_j \mathcal{N}(\mathbf{x}_i | \boldsymbol{\mu}_j, \boldsymbol{\Sigma}_j)} $$
2.  **M-Step (Maximization):** Update the parameters using the responsibilities:
$$ \boldsymbol{\mu}_k^{\text{new}} = \frac{\sum_{i=1}^N \gamma_{ik} \mathbf{x}_i}{\sum_{i=1}^N \gamma_{ik}} $$
$$ \boldsymbol{\Sigma}_k^{\text{new}} = \frac{\sum_{i=1}^N \gamma_{ik} (\mathbf{x}_i - \boldsymbol{\mu}_k^{\text{new}})(\mathbf{x}_i - \boldsymbol{\mu}_k^{\text{new}})^T}{\sum_{i=1}^N \gamma_{ik}} $$

**When to choose GMM over K-Means:** When you suspect clusters are elliptical, elongated, or have varying variances. The covariance matrix $\boldsymbol{\Sigma}_k$ allows the model to capture this shape information, providing a much richer model of the underlying data distribution.

### 5.2 Bayesian Vectorization Approaches (The Theoretical Leap)
Sources [4] and [6] point toward Bayesian Vectorizers (BV-KMeans). This represents a shift from purely geometric clustering to a probabilistic modeling framework.

In traditional K-Means, the assignment is hard: $\mathbf{x}_i$ *belongs* to $C_k$. In a Bayesian framework, we are estimating the *posterior probability* $P(C_k | \mathbf{x}_i, \text{Data})$.

A Bayesian approach often integrates the clustering process with the vector representation itself. Instead of just using TF-IDF, a Bayesian model might estimate the underlying topic distribution $\theta_k$ for each cluster $k$ using Dirichlet Process Mixture Models (DPMMs) or related techniques.

**Conceptual BV-KMeans:**
1.  **Prior Assumption:** Assume that the word counts/weights in a document are drawn from a mixture of topic distributions.
2.  **Inference:** Instead of calculating the distance to a fixed centroid $\boldsymbol{\mu}_k$, the model calculates the likelihood of observing the document's word counts given the parameters of the topic distribution for cluster $k$.
3.  **Advantage:** This framework is inherently more robust to noise and can provide a principled way to estimate the *number* of clusters ($K$) without relying solely on heuristic methods like the Elbow method, as the model structure itself can be adapted (e.g., using a Dirichlet Process prior).

### 5.3 Addressing High Dimensionality: Beyond PCA
While PCA/SVD are excellent for linear dimensionality reduction, they assume the underlying structure is linear. Textual data, especially when derived from embeddings, is often non-linear.

**Manifold Learning Techniques:**
For advanced research, consider non-linear techniques to map the high-dimensional space $\mathbb{R}^D$ onto a lower-dimensional manifold $\mathbb{R}^d$ while preserving local neighborhood structure:
*   **t-SNE (t-distributed Stochastic Neighbor Embedding):** Excellent for *visualization* (reducing to 2D or 3D) because it focuses on preserving local neighborhood relationships. However, it is notoriously poor for preserving global structure, making it unreliable for direct input into K-Means.
*   **UMAP (Uniform Manifold Approximation and Projection):** A more modern alternative to t-SNE. UMAP is generally faster and better at preserving both local and global structure, making it a more viable candidate for pre-processing data intended for clustering algorithms.

**Workflow Recommendation:** For clustering, the sequence should be: **Embeddings $\rightarrow$ UMAP $\rightarrow$ K-Means**.

---

## Ⅵ. Edge Cases, Pitfalls, and Expert Considerations

A truly expert understanding requires knowing when the standard tools *fail*.

### 6.1 The Curse of Dimensionality Revisited
The curse of dimensionality dictates that as $D$ increases, the volume of the feature space increases so rapidly that the available data points become increasingly sparse relative to the space volume. In such a space, the distance between any two points tends to converge, meaning the concept of "nearest neighbor" loses its meaning.

**Mitigation Summary:**
1.  **Dimensionality Reduction:** Mandatory if $D$ is very large ($>10,000$).
2.  **Feature Selection:** If using TF-IDF, explicitly selecting the top $M$ most informative features (e.g., using $\chi^2$ tests or mutual information scores) can prune noise before dimensionality reduction.

### 6.2 Handling Class Imbalance in Clustering
If your corpus naturally contains a few highly distinct, rare documents (outliers) that represent a unique topic, K-Means might struggle.

*   **Outlier Detection Pre-step:** Before clustering, run an outlier detection algorithm (e.g., Isolation Forest or Local Outlier Factor (LOF)) on the feature vectors.
*   **Strategy:** Either cluster the core, "normal" data points and treat the detected outliers as a separate, potentially unclustered, "Anomaly" cluster, or use a density-based method like DBSCAN which is inherently designed to identify and isolate noise points.

### 6.3 Choosing $K$: The Subjectivity Problem
The selection of $K$ is the single most subjective parameter. While the Elbow Method and Silhouette Score provide quantitative guidance, they are not infallible.

**Expert Protocol:**
1.  **Hypothesis-Driven $K$:** If the research question implies $K$ (e.g., "We expect three primary political viewpoints"), use that $K$ first.
2.  **Iterative Testing:** Run the clustering process for a plausible range of $K$ (e.g., $K=5$ to $K=20$).
3.  **Domain Expert Review:** The final selection of $K$ *must* be validated by domain experts. If $K=12$ yields 12 clusters, but the domain expert can only articulate 10 distinct themes, then $K=10$ is the scientifically appropriate choice, even if the Silhouette Score suggests $K=12$.

### 6.4 Computational Considerations for Sparse vs. Dense Data
When implementing the pipeline, always respect the data type:

*   **Sparse Data (TF-IDF):** Use `scipy.sparse` matrices. PCA/SVD implementations must utilize the sparse matrix versions (e.g., `scipy.sparse.linalg.svds`).
*   **Dense Data (Embeddings):** Use standard NumPy arrays. Mini-Batch K-Means is highly efficient here.

---

## Ⅶ. Conclusion: Synthesis and Future Directions

Document clustering with K-Means, when executed correctly, remains a powerful, computationally tractable, and highly interpretable method for exploratory data analysis in NLP. The journey from raw text to meaningful clusters is not a single algorithm but a sophisticated, multi-stage engineering pipeline.

The evolution of this field is clear:
$$\text{BoW} \xrightarrow{\text{Weighting}} \text{TF-IDF} \xrightarrow{\text{Dimensionality Reduction}} \text{K-Means} \xrightarrow{\text{Semantic Context}} \text{Embeddings} \xrightarrow{\text{Advanced Modeling}} \text{GMM/Bayesian Methods}$$

For the expert practitioner, the key takeaways are:
1.  **Never treat K-Means as the end goal.** It is the clustering engine applied to the best possible feature representation.
2.  **Prioritize Semantic Context:** For modern research, the use of contextual embeddings (BERT, etc.) followed by UMAP and K-Means (or GMM) vastly outperforms classical TF-IDF methods.
3.  **Model Selection is Parametric:** Be prepared to justify your choice of distance metric (Cosine for embeddings, Euclidean for normalized counts) and your choice of clustering model (K-Means for simplicity/speed, GMM for shape awareness).

Mastering this workflow requires not just knowing the syntax of `sklearn`, but understanding the underlying statistical assumptions of each component—the sparsity assumptions of TF-IDF, the Gaussian assumptions of GMM, and the manifold assumptions of UMAP.

If you follow this rigorous, multi-layered approach, your clustering results will move beyond mere academic exercises and become genuinely insightful tools for knowledge discovery. Now, go cluster something meaningful.
