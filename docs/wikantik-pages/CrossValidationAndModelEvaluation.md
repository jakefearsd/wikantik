---
canonical_id: 01KQ0P44P45NFRF9WQ1YMRD7J4
title: Cross-Validation and Model Evaluation
type: article
cluster: machine-learning
status: active
date: '2026-04-26'
summary: How to evaluate ML models honestly — cross-validation strategies, the right
  metrics for the task, common pitfalls (data leakage, optimistic estimates), and
  what production reality requires beyond benchmark numbers.
tags:
- evaluation
- cross-validation
- machine-learning
- metrics
- model-selection
related:
- ModelSelection
- ModelSelectionEfficiency
- TreeBasedModels
hubs:
- MLHub
---
# Cross-Validation and Model Evaluation

Model evaluation is what separates "I have a model" from "I have a model I trust." Most ML failures in production trace to evaluation that didn't reflect reality.

This page covers honest evaluation.

## The basic idea

You have data. You want to know how a model trained on it will perform on new, unseen data.

Solution: hold out some data. Train on the rest. Evaluate on the holdout.

But: a single holdout gives noisy estimates. Cross-validation averages over multiple holdouts.

## Train/validation/test split

Three sets:
- **Training**: fit the model
- **Validation**: tune hyperparameters
- **Test**: final unbiased evaluation

Common splits: 60/20/20, 70/15/15, 80/10/10.

Critical: the test set must be touched only once, after all decisions are final.

## K-fold cross-validation

Split data into K folds. Train K times, each time holding out a different fold.

Average the K performances. Standard K = 5 or 10.

Pros:
- Lower variance estimate than single holdout
- Uses all data for evaluation
- All data used for training (across folds)

Cons:
- K times the compute
- Harder to track which model is "the" model

Use cross-validation for model selection; train final model on all training data.

## Stratified K-fold

For classification: ensure each fold has roughly the same class distribution as the whole.

Critical for imbalanced data.

## Time-series cross-validation

For time-series: never train on future data.

Walk-forward validation:
- Train on [1, t], evaluate on t+1
- Train on [1, t+1], evaluate on t+2
- ...

Or: expanding window vs sliding window.

Random K-fold on time-series is one of the most common evaluation mistakes.

## Group K-fold

When data has groups (patients, users, sessions): keep all data from one group in the same fold.

Otherwise the model can "memorize" group features.

## Leave-one-out CV

K = N (one example per fold). Used for small datasets where you can't spare validation data.

Computationally expensive. High variance estimate.

## Metrics

The metric must match the business goal.

### Classification

- **Accuracy**: % correct. Misleading on imbalanced data.
- **Precision**: of predicted positives, how many are right
- **Recall**: of actual positives, how many we found
- **F1**: harmonic mean of precision and recall
- **AUC-ROC**: discrimination across thresholds
- **AUC-PR**: better for imbalanced data
- **Log loss**: penalizes confident wrong predictions

For imbalanced data: prefer precision/recall/F1 over accuracy.

### Regression

- **MAE**: mean absolute error
- **MSE / RMSE**: penalize large errors more
- **MAPE**: percentage error (problems near zero)
- **R²**: variance explained

The right choice depends on what errors cost.

### Ranking

- **NDCG**: normalized discounted cumulative gain
- **MRR**: mean reciprocal rank
- **MAP**: mean average precision

For search and recommendation systems.

### Probability calibration

A model that says "80% confidence" should be right 80% of the time.

Most ML models aren't calibrated by default. Calibration plots show the gap.

Tools: Platt scaling, isotonic regression.

## Data leakage

When information from the test set "leaks" into training.

Subtle examples:
- Normalizing features using whole-dataset statistics
- Imputing missing values using whole-dataset statistics
- Feature engineering using future data
- Duplicate or near-duplicate examples in train and test

Symptom: optimistic CV scores; production performance is worse.

Prevention: do all preprocessing inside the CV loop. Use pipelines.

## Bootstrap

Sample with replacement to estimate uncertainty.

For each bootstrap sample:
- Train model
- Evaluate on out-of-bag samples

Gives confidence intervals on metrics.

## Statistical significance

Two models differ by 0.3% AUC. Is one actually better?

Statistical tests:
- McNemar's test (paired classification)
- Permutation tests
- Bootstrap confidence intervals

Often the answer is "no, the difference is within noise."

## Production evaluation gap

Even careful CV underestimates production challenges:

### Distribution shift

Training data may not represent production distribution.

### Concept drift

Distributions change over time. Model degrades.

### Selection bias

If the model affects what data you collect, future data is biased.

### Feedback loops

Recommender systems train on data shaped by the previous model.

These need monitoring beyond initial evaluation.

## Common failure patterns

### Reusing the test set

Once you've looked at the test set, it's contaminated. You'll subtly steer toward it.

### Optimizing for the wrong metric

Accuracy when business cares about precision. F1 when calibration matters.

### Insufficient sample size

With 100 examples, AUC has huge confidence intervals. Small differences are noise.

### Comparing models on different splits

Use the same CV folds for fair comparison.

### Cross-validation on time-series

Random shuffling time-series breaks temporal structure.

### Ignoring cost of errors

False negative ≠ false positive in many domains. Use cost-sensitive evaluation.

## Practical workflow

1. Set aside a final test set immediately
2. Use CV on the rest for model selection
3. Choose metrics matching the business goal
4. Make all preprocessing leak-proof
5. Check distribution alignment between CV and production
6. Evaluate final model on test set once
7. Monitor production performance

## Beyond accuracy

Production models also need:
- **Latency**: meets SLA?
- **Throughput**: enough QPS?
- **Cost**: within budget?
- **Robustness**: graceful degradation?
- **Fairness**: across protected groups?
- **Interpretability**: when needed?

A 0.1% accuracy gain that doubles latency may be a regression.

## Further Reading

- [ModelSelection](ModelSelection) — Choosing among models
- [ModelSelectionEfficiency](ModelSelectionEfficiency) — Efficiency tradeoffs
- [TreeBasedModels](TreeBasedModels) — Practical models
- [ML Hub](MLHub) — Cluster index
