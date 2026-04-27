---
canonical_id: 01KQ0P44Y12PW18T0PM0N8QV9M
title: Tree-Based Models
type: article
cluster: machine-learning
status: active
date: '2026-04-26'
summary: Decision trees, random forests, and gradient boosting — the family of models
  that quietly powers most production ML on tabular data, and the practical reasons
  to reach for them before deep learning.
tags:
- tree-based-models
- machine-learning
- gradient-boosting
- random-forest
- xgboost
related:
- ModelSelection
- CrossValidationAndModelEvaluation
- ModelSelectionEfficiency
hubs:
- ML Hub
---
# Tree-Based Models

For tabular data, tree-based models — particularly gradient boosting — are usually the right answer. Modern gradient boosting libraries (XGBoost, LightGBM, CatBoost) win Kaggle competitions, power production systems, and outperform deep learning on most tabular tasks.

Knowing when and how to use tree-based models is essential ML knowledge.

## Decision trees

Recursively split data on features to predict outcome.

A tree is a series of if-else questions:
- "Is age < 30?"
  - Yes: "Is income > 50K?"
    - Yes: predict class A
    - No: predict class B
  - No: ...

Splits are chosen to maximize information gain (or minimize impurity).

### Pros

- Highly interpretable
- Handle mixed data types
- No feature scaling needed
- Capture non-linear relationships
- Handle missing values naturally

### Cons

- Single trees overfit
- Poor extrapolation
- Sensitive to small data changes

## Random forests

Many trees trained on bootstrap samples; predictions averaged.

Each tree:
- Trained on random subset of data
- Considers random subset of features at each split

Result: less variance than single tree, often less bias.

### Pros

- Strong out-of-the-box performance
- Hard to overfit
- Few hyperparameters
- Parallelizable training
- Out-of-bag error estimation (free CV)

### Cons

- Less interpretable than single tree
- Larger models
- Slower inference than single tree

Random Forests are an excellent strong baseline.

## Gradient boosting

Build trees sequentially, each correcting errors of the previous.

Mathematical framing: each tree fits the gradient of the loss on previous predictions.

### XGBoost

The original popularizer. Highly optimized; many features.

### LightGBM

Microsoft's implementation. Often faster than XGBoost; comparable quality.

Uses leaf-wise growth (vs depth-wise).

### CatBoost

Yandex's implementation. Native categorical handling, ordered boosting.

Often best for categorical-heavy data.

### Practical defaults

- LightGBM: usually fastest, strong default
- XGBoost: well-known, mature, GPU support
- CatBoost: when many categorical features

All three give similar quality with reasonable tuning.

## Why tree-based wins on tabular

Reasons deep learning struggles on tabular:

1. **Tabular data is heterogeneous**: mix of numeric, categorical, ordinal, with different scales. Trees handle naturally.

2. **Non-smooth target functions**: real-world rules are step-like ("if age > 65 AND income < X then..."). Trees represent these directly.

3. **Less data per task**: deep learning needs lots of data. Tabular tasks often have 10K-1M rows, where trees excel.

4. **Engineered features matter**: domain knowledge encoded as features works well with trees.

5. **Robustness to outliers**: trees aren't affected by extreme values like neural networks.

This is why financial, healthcare, and e-commerce ML systems run on gradient boosting.

## Hyperparameters

Common tunables:

### Tree-level

- **Max depth**: typically 3-8. Deeper = more capacity, more overfitting risk.
- **Min samples per leaf**: regularization
- **Number of leaves** (LightGBM): more direct control

### Boosting

- **Number of trees**: 100-10000. Use early stopping.
- **Learning rate**: 0.01-0.3. Lower = better quality, more trees needed.
- **Subsample**: row sampling per tree (regularization)
- **Column subsample**: feature sampling per tree

### Regularization

- **L1, L2 penalties**: on leaf weights
- **Min gain to split**: don't split if gain too small

### Default starting point

- 1000 trees with early stopping on validation
- Learning rate 0.05
- Max depth 6
- Subsample 0.8
- Column subsample 0.8

Tune from there.

## Categorical features

### One-hot encoding

Convert categories to binary columns. Standard.

### Ordinal encoding

Map categories to integers. Use only when order is meaningful.

### Target encoding

Replace category with mean target value. Risk of leakage; needs cross-validation.

### Native categorical

CatBoost and LightGBM handle categoricals natively. Often outperforms manual encoding.

## Feature importance

Trees naturally produce feature importance:
- Frequency: how often a feature is used in splits
- Gain: average improvement when used
- Permutation importance: change in performance when feature shuffled

Useful for:
- Feature selection
- Model debugging
- Stakeholder communication

Caveat: importance scores can be misleading with correlated features.

## SHAP values

Per-prediction feature attribution. Tells you why a specific prediction was made.

For tree-based models, SHAP can be computed exactly and efficiently.

Use for:
- Model interpretation
- Regulatory compliance
- Debugging surprising predictions

## When to use deep learning instead

Deep learning may beat trees when:
- Very large datasets (10M+ rows)
- High-dimensional features (text, images)
- Strong feature interactions hard to engineer
- Sequential structure (time-series, RNNs)
- Transfer learning matters

For most tabular ML in industry: trees are still the right answer.

## Common workflows

### Kaggle / competition

1. Quick LightGBM baseline
2. Feature engineering
3. Hyperparameter tuning
4. Stacking/blending multiple models

### Production

1. Train LightGBM with reasonable defaults
2. Establish baseline
3. Iterate on features
4. Tune hyperparameters once
5. Deploy
6. Monitor and retrain

## Common failure patterns

### Overfitting on small validation sets

Tune on a single random split; overfit to it. Use CV.

### Target leakage

Features that wouldn't be available at prediction time. Common with engineered features.

### Insufficient data for the chosen depth

Deep trees on small data overfit. Reduce depth or get more data.

### Wrong objective

Regression objective when classification is the task. Subtle but happens.

### Ignoring class imbalance

Majority class dominates. Use class weights, scale_pos_weight, or focal loss variants.

### Not using early stopping

Without early stopping, you have to manually tune number of trees.

### Inconsistent training/inference preprocessing

Different feature engineering pipelines drift. Use a shared pipeline.

## Practical advice

1. Start with LightGBM defaults
2. Get a baseline working end-to-end before tuning
3. Focus on feature engineering more than hyperparameters
4. Use early stopping
5. Use CV for tuning
6. Compare to simpler baselines (logistic regression)
7. SHAP for understanding predictions

Tree-based models are reliable, fast, and accurate. They should be the default for tabular data unless proven otherwise.

## Further Reading

- [ModelSelection](ModelSelection) — General selection
- [CrossValidationAndModelEvaluation](CrossValidationAndModelEvaluation) — Evaluation
- [ModelSelectionEfficiency](ModelSelectionEfficiency) — Efficiency tradeoffs
- [ML Hub](ML+Hub) — Cluster index
