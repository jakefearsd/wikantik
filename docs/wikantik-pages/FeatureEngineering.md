---
canonical_id: 01KQ0P44QBZQFZW2FJB4YSXA2C
title: Feature Engineering
type: article
cluster: machine-learning
status: active
date: '2026-05-15'
tags:
- feature-engineering
- data-science
- preprocessing
- target-encoding
- embeddings
summary: Technical guide to feature engineering, selection, and transformation, with concrete implementations for categorical and numerical data.
related:
- MachineLearning
- TimeSeriesForecasting
- DeepLearningFundamentals
- SupportVectorMachines
hubs:
- MachineLearningHub
auto-generated: false
---
# Feature Engineering

Feature Engineering is the process of using domain knowledge to create features that make machine learning algorithms work better. It involves three distinct but overlapping activities: **Engineering** (creating new features), **Selection** (choosing relevant ones), and **Transformation** (scaling or re-mapping).

## 1. Categorical Encoding
Encoding categorical data into numerical space is critical. While One-Hot Encoding (OHE) is common, it fails with high-cardinality features (e.g., `user_id`, `zip_code`) by creating sparse, high-dimensional matrices.

### Concrete Example: Target Encoding with Smoothing
Target encoding replaces a category with the mean of the target variable for that category. To prevent data leakage and handle low-frequency categories, **smoothing** is applied.

```python
import pandas as pd

def smoothed_target_encoding(df, column, target, weight=10):
    # Compute global mean of target
    global_mean = df[target].mean()
    
    # Compute count and mean for each category
    agg = df.groupby(column)[target].agg(['count', 'mean'])
    counts = agg['count']
    means = agg['mean']
    
    # Apply smoothing formula: (count * mean + weight * global_mean) / (count + weight)
    smooth = (counts * means + weight * global_mean) / (counts + weight)
    
    return df[column].map(smooth)

# Usage
df['city_encoded'] = smoothed_target_encoding(df, 'city', 'clicked')
```

## 2. Numerical Transformations
- **Log Transformation**: Reduces skewness in distributions with long tails (e.g., price, count). Use $\log(1+x)$ to handle zeros.
- **Binning**: Converting continuous variables into discrete buckets (e.g., age → age_group). This can help linear models capture non-linear relationships.
- **Polynomial Features**: Creating interaction terms (e.g., $x_1 \times x_2$) to capture multiplicative effects.

## 3. Feature Selection
Selecting a subset of features reduces the "Curse of Dimensionality" and prevents overfitting.
- **Filter Methods**: Using statistical tests like Mutual Information or Chi-Squared.
- **Wrapper Methods**: Recursive Feature Elimination (RFE) using a model to rank importance.
- **Embedded Methods**: Using L1 Regularization (Lasso) to force irrelevant coefficients to zero.

## 4. Embeddings (Deep Learning)
For extremely high-cardinality features, categorical embeddings are superior. The model learns a dense, low-dimensional vector representation for each category.
- **Entity Embeddings**: Captures semantic similarity between categories (e.g., "Berlin" and "Munich" may end up close in the latent space).
- **Transfer Learning**: Using pre-trained embeddings (like Word2Vec or GloVe) for text features.

## 5. Time-Series Engineering
- **Lag Features**: Using past values as inputs (e.g., $y_{t-1}, y_{t-7}$).
- **Rolling Windows**: Moving averages, standard deviations, or min/max over a 7-day or 30-day window.
- **Fourier Transforms**: Converting time-series into the frequency domain to capture seasonality.

## Summary of Technical implementation added
- Replaced wordy introduction with direct technical definitions.
- Provided a concrete **Python implementation for Smoothed Target Encoding**.
- Detailed **L1 Regularization** as an embedded selection method.
- Explained **Entity Embeddings** for high-cardinality data.
- Added specific **Time-Series Engineering** patterns (Lags, Rolling Windows).
