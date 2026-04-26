---
title: Clustering Algorithms
type: article
cluster: machine-learning
status: active
date: '2026-04-25'
tags:
- clustering
- kmeans
- dbscan
- hdbscan
- gmm
summary: K-means, DBSCAN, HDBSCAN, hierarchical, Gaussian mixtures, spectral —
  what each assumes, where each fails, and the heuristics for picking.
related:
- DocumentClusteringApproaches
- LinearAlgebra
- BayesianReasoning
hubs:
- MachineLearning Hub
---
# Clustering Algorithms

Clustering finds groups of similar items in unlabelled data. The algorithms make different assumptions about what "groups" look like; picking the wrong one produces strange results. This page is the working set, with the assumptions and failure modes of each.

## K-means

The classic. Pick K; iteratively assign points to the nearest centroid; recompute centroids.

```
init centroids randomly
repeat:
    assign each point to nearest centroid
    update each centroid to mean of its points
until convergence
```

Assumes:
- Spherical clusters of roughly equal size.
- Clusters defined by Euclidean distance from a centroid.
- You know K in advance.

Strengths: fast (O(NKI)); simple; understood; widely-implemented.

Weaknesses: 

- Bad on non-spherical clusters (elongated, U-shaped, nested).
- Bad on clusters of very different sizes.
- Sensitive to outliers (centroids shift toward them).
- K-means++ initialisation is critical; random init produces local minima.

Use K-means when: clusters are roughly spherical and you have a reasonable K.

Don't use when: clusters have variable density, shape, or size; outliers matter; you can't choose K.

## K-medoids (PAM)

Like K-means but cluster centres are actual data points (medoids), not centroids. More robust to outliers.

Slower than K-means. Useful when your distance metric isn't Euclidean (you can use any distance) or when outliers are a problem.

## Hierarchical clustering (agglomerative)

Start with each point as its own cluster; iteratively merge nearest clusters. Produces a dendrogram (tree).

```
each point is a cluster
repeat:
    find the two nearest clusters
    merge them
until one cluster
```

The "nearest" depends on linkage:

- **Single** — minimum distance between clusters. Produces stringy clusters.
- **Complete** — maximum distance. Compact clusters.
- **Average** — mean distance. Compromise.
- **Ward** — minimises within-cluster variance. Often produces best clusters; computationally similar to K-means.

Strengths: no need to choose K up front (cut the dendrogram at any level); produces a hierarchy that's interpretable.

Weaknesses: O(N²) memory; O(N³) time naively. Doesn't scale beyond ~10k-100k points.

Use when: you want hierarchy; data isn't huge.

## DBSCAN

Density-based. A cluster is a region where points have many neighbours within distance `eps`. Points in low-density regions are noise.

Two parameters: `eps` (distance threshold) and `min_samples` (minimum density).

Strengths:
- Finds non-spherical clusters.
- Handles noise / outliers explicitly.
- No need to choose K.

Weaknesses:
- Sensitive to `eps`. Wrong choice produces all-noise or one-huge-cluster.
- Can't handle clusters of very different densities.
- O(N²) without indexing; with kd-tree / ball tree, O(N log N).

Use when: clusters have similar density; you don't know K; outliers are real.

## HDBSCAN

Hierarchical DBSCAN. Builds a hierarchy then extracts clusters at varying density. Solves DBSCAN's "single eps" problem.

Strengths:
- Handles variable density.
- Doesn't need `eps`.
- Robust noise detection.
- Stable across hyperparameter ranges.

Weaknesses:
- More complex than DBSCAN.
- Slower than K-means but much smarter on real data.

Use when: real-world clustering, especially on embeddings. The default for document clustering, customer segmentation, anomaly detection. See [DocumentClusteringApproaches].

## Gaussian Mixture Models (GMM)

Assume data is generated from K Gaussian distributions with different means and covariances; learn the parameters.

Strengths:
- Soft clustering — each point has probabilities for each cluster.
- Handles elliptical clusters (not just spherical).
- Probabilistic framing — useful for uncertainty quantification.

Weaknesses:
- Need to choose K.
- Slower than K-means.
- Failure on non-Gaussian data (clusters with funny shapes).

Use when: clusters are roughly elliptical; soft assignment matters; you can choose K with cross-validation or BIC.

## Spectral clustering

Build a similarity graph; compute eigenvectors of its Laplacian; cluster in eigenvector space.

Strengths:
- Handles arbitrary cluster shapes (gracefully clusters concentric circles, half-moons, etc.).
- Theoretically well-founded.

Weaknesses:
- O(N³) eigendecomposition. Doesn't scale.
- Sensitive to choice of similarity graph and number of components.

Use when: data has clear graph structure; small N; classical methods fail on the geometry.

## Mean-shift

Like K-means but each point shifts toward the local density mode. Number of clusters emerges from the data.

Strengths: no K; finds modes of any shape.
Weaknesses: O(N²); bandwidth parameter is sensitive; rarely the best choice.

## OPTICS

Density-based like DBSCAN; produces a reachability plot from which you can extract clusters at varying density.

Less popular than HDBSCAN now; HDBSCAN is the modern preferred choice.

## BIRCH

Streaming-friendly clustering. Builds a CF tree summary; clusters from that.

Use when: data is too large to hold in memory.

## Choosing an algorithm

A decision flow:

1. **Embedding-based clustering, real-world corpora?** UMAP + HDBSCAN.
2. **Spherical clusters, large data, fast result?** K-means.
3. **Want hierarchy?** Agglomerative.
4. **Want soft assignment / probabilistic?** GMM.
5. **Variable density, shape, outliers?** HDBSCAN.
6. **Strange geometric shapes (rings, half-moons)?** Spectral.
7. **Streaming data?** BIRCH or online K-means.

For most production work in 2026: K-means for fast simple cases, HDBSCAN for real data complexity. The rest are specialised.

## How to choose K

For algorithms requiring K:

- **Elbow method** — plot within-cluster variance vs K; pick the "elbow." Subjective.
- **Silhouette score** — measures cluster cohesion vs separation. Pick K that maximises.
- **Gap statistic** — compares to expected variance under null reference. More principled.
- **BIC / AIC** for GMM.

For clustering you can manually inspect: try a few K, look at the clusters, pick what looks right. Often the most useful approach.

## Distance / similarity matters

The choice of distance metric affects cluster shapes:

- **Euclidean (L2)** — default; sensitive to scale.
- **Cosine** — angle between vectors; standard for embeddings.
- **Manhattan (L1)** — robust to outliers in individual dimensions.
- **Hamming** — for binary vectors.
- **Custom** — whatever your domain's notion of similarity is.

For high-dimensional data (especially embeddings), cosine is usually the right pick. For low-dimensional numeric features, Euclidean.

Always normalise / standardise features for distance-based methods. Otherwise the dimension with the largest scale dominates.

## Failure modes

- **Curse of dimensionality.** All distances become similar in high dimensions; clustering loses signal. Reduce dimensionality first (UMAP, PCA).
- **Wrong assumptions.** Using K-means on non-spherical data; getting weird clusters; blaming the data when the algorithm is wrong.
- **Outliers eating clusters.** K-means is famously sensitive; use HDBSCAN or pre-remove outliers.
- **Ignoring noise.** Some points genuinely don't fit anywhere; HDBSCAN labels them; don't force them.
- **Not validating.** Numerical metrics (silhouette) only go so far; manual inspection of clusters is the real test.

## Evaluation

Internal validation (no labels):

- Silhouette coefficient.
- Davies-Bouldin.
- Calinski-Harabasz.

External validation (with ground-truth labels):

- Adjusted Rand Index.
- Normalised Mutual Information.
- Purity.

For production: have a small labelled set; compute external metrics; trust them more than internal ones.

## A pragmatic recipe

For a new clustering task:

1. **Visualise** the data first. Plot in 2D (PCA or UMAP). Eyeball the structure.
2. **Pick algorithm based on what you see**. Spherical blobs → K-means; complex shapes → HDBSCAN.
3. **Try with default parameters**. Inspect results manually.
4. **Tune incrementally**. One parameter at a time.
5. **Validate against domain experts** or labelled samples.

Most clustering tasks don't need fancy algorithms. They need sensible preprocessing, a reasonable algorithm, and human inspection of the output.

## Further reading

- [DocumentClusteringApproaches] — applied to text
- [LinearAlgebra] — distance / projection math
- [BayesianReasoning] — GMM and probabilistic clustering
