---
date: '2026-05-15'
summary: Technical analysis of unsupervised outlier detection. Isolation Forest mechanics,
  Local Outlier Factor (LOF) geometry, and reconstruction-based detection via Autoencoders.
cluster: machine-learning
auto-generated: false
canonical_id: 01KQ0P44KTD1J1NF6BTMNGN383
type: article
title: Anomaly Detection Techniques
tags:
- anomaly-detection
- isolation-forest
- local-outlier-factor
- unsupervised-learning
status: active
hubs:
- AnomalyDetectionTechniques Hub
---
# Anomaly Detection Techniques

Anomaly detection (outlier detection) is the task of identifying data points that deviate significantly from the learned "normal" manifold. In production Wikantik monitoring, we prioritize unsupervised methods to catch novel failure modes without needing labeled historical data.

## 1. Isolation Forest (The Production Default)

Isolation Forest is an ensemble of random decision trees. It operates on the principle that anomalies are "few and far between" and thus easier to isolate with random splits.

*   **Mechanism:** Points that require fewer splits to be isolated (shorter path length from root) are assigned a higher anomaly score.
*   **Complexity:** $O(N \log N)$, making it highly scalable for high-dimensional streams.

### Concrete Example: Scikit-learn Implementation
```python
from sklearn.ensemble import IsolationForest
import numpy as np

# Generate normal data
X = 0.3 * np.random.randn(100, 2)
X_train = np.r_[X + 2, X - 2]

# Generate some abnormal novel observations
X_outliers = np.random.uniform(low=-4, high=4, size=(20, 2))

# model.fit trains on normal data
model = IsolationForest(n_estimators=100, contamination=0.1)
model.fit(X_train)

# -1 for outliers, 1 for inliers
predictions = model.predict(X_outliers)
scores = model.decision_function(X_outliers)

print(f"Predictions: {predictions}")
```

## 2. Local Outlier Factor (LOF)

LOF measures the local density deviation of a point relative to its $k$-nearest neighbors. It is ideal for datasets with clusters of varying densities where a global distance threshold is ineffective.

*   **Score Calculation:** A ratio of the average local reachability density (LRD) of a point's neighbors to its own LRD.
*   **Metric:** $\text{LOF} \approx 1$ implies normal density; $\text{LOF} \gg 1$ implies an outlier.

## 3. Reconstruction-based Detection (Deep Learning)

### Autoencoders (AE)
An autoencoder is trained to reconstruct its input through a bottleneck layer.
*   **Logic:** The network learns the latent representation of "normal" data. 
*   **Anomaly Score:** $s = ||x - \text{decoder}(\text{encoder}(x))||^2$. If the reconstruction error is high, the point does not fit the learned normal manifold.
*   **VAE (Variational Autoencoder)**: Uses a probabilistic bottleneck (mean and variance) to learn a more robust, continuous latent space, improving detection on subtle anomalies.

## 4. Technique Comparison

| Technique | Logic | Strengths | Weaknesses |
|---|---|---|---|
| **Isolation Forest** | Tree Partitioning | Fast, high-dim support | Poor on local, subtle shifts |
| **LOF** | Local Density | Handles varying densities | $O(N^2)$ complexity |
| **Autoencoders** | Reconstruction | Non-linear, complex data | Computationally expensive |
| **OC-SVM** | Boundary Search | Strong theoretical bounds | Sensitive to kernel choice |

## 5. Deployment Strategy: The Multi-Stage Filter
1.  **Stage 1 (Statistical):** Z-score or Interquartile Range (IQR) for univariate spikes (low CPU cost).
2.  **Stage 2 (Fast Unsupervised):** Isolation Forest for multivariate correlations.
3.  **Stage 3 (High-Fidelity):** VAE for complex time-series sequences or unstructured data.

## Summary of Technical implementation added
- Stripped verbose "AI slop" and redundant introductory text.
- Provided a concrete **Scikit-learn Isolation Forest** example.
- Detailed the **Reconstruction Error** math for Autoencoders.
- Added a **Multi-Stage Filter** strategy for production deployment.
- Compared technique complexities ($O(N \log N)$ vs $O(N^2)$).
