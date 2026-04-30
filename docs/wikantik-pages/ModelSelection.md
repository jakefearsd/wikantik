---
canonical_id: 01KQ0P44SKEQQNNRAVBND6CNGG
title: Model Selection
type: article
cluster: machine-learning
status: active
date: '2026-04-26'
summary: How to choose among candidate ML models — algorithm selection, hyperparameter
  tuning strategies, the bias-variance tradeoff in practice, and the realistic
  constraints (cost, latency, interpretability) that often matter more than accuracy.
tags:
- model-selection
- machine-learning
- hyperparameter-tuning
- bias-variance
related:
- CrossValidationAndModelEvaluation
- ModelSelectionEfficiency
- TreeBasedModels
hubs:
- MLHub
---
# Model Selection

You have a problem and many possible models. Which to choose?

Model selection has two dimensions:
1. Choosing the algorithm/architecture
2. Choosing hyperparameters within an algorithm

Both involve evaluation and tradeoffs.

## Choosing the algorithm

### Match to problem characteristics

- **Tabular data, mixed features**: gradient boosting (XGBoost, LightGBM, CatBoost) is usually best
- **Lots of structured data, simple features**: linear models surprisingly competitive
- **Images**: CNNs or vision transformers
- **Text**: transformer-based models
- **Time-series**: depends on data — gradient boosting often beats specialized models on shorter series; LSTM/transformer for longer
- **Tabular with very few examples (~100s)**: linear models, gradient boosting with regularization

### Match to constraints

- **Latency budget**: simpler models faster
- **Memory budget**: small models or quantization
- **Interpretability requirement**: linear models, decision trees, GAMs
- **Lots of labeled data**: deep learning
- **Limited labeled data**: pretrained models, simpler architectures
- **Online learning required**: some algorithms support, others don't

### Match to team

- Skills you have
- Tooling already in place
- Maintenance burden

A model nobody can debug is a liability.

## Establishing baselines

Before complex models, establish baselines:

1. **Random / majority class**: lowest bar
2. **Simple heuristic**: domain-knowledge baseline
3. **Linear model**: classical baseline
4. **Boosting (XGBoost)**: strong baseline for tabular

If complex models don't beat boosting, you don't need them.

## Hyperparameter tuning

Once you've chosen an algorithm, find good hyperparameters.

### Grid search

Try all combinations of a discrete grid. Exhaustive; expensive for many parameters.

### Random search

Sample randomly from parameter ranges. Often better than grid for high-dim search spaces.

### Bayesian optimization

Use a probabilistic model of the objective to choose next parameters. More efficient.

Tools: Optuna, scikit-optimize, Hyperopt.

### Population-based / evolutionary

Maintain a population of configurations; evolve.

Used for neural architecture search.

### Hyperband / ASHA

Allocate budget adaptively. Stop bad runs early.

Effective for deep learning where each run is expensive.

## Bias-variance tradeoff

Two sources of error:

**Bias**: model can't represent the true relationship. Underfitting.

**Variance**: model is sensitive to training data. Overfitting.

Total error = bias² + variance + irreducible noise.

Symptoms:
- High training error → high bias (underfit) → use bigger model
- Low training error, high test error → high variance (overfit) → regularize, more data, simpler model
- High training and test error → high bias

Most modern models have flexibility to fit training data perfectly. Variance control becomes the main concern.

## Regularization

Reduce variance:

### L1 / L2 penalty

Add weight magnitude term to loss. Smaller weights → simpler model.

### Dropout

Randomly zero activations during training. Forces redundancy.

### Early stopping

Stop training when validation loss starts increasing.

### Data augmentation

More effective data without more labels.

### Ensembling

Multiple models; average predictions. Reduces variance.

## Ensembles

Combining models often beats any single model.

### Bagging

Train models on bootstrap samples; average. Random Forest is bagged trees.

### Boosting

Train models sequentially, each correcting the previous. Gradient boosting.

### Stacking

Train meta-model on predictions of base models.

Ensembles add cost. For production, may not be worth it.

## Selection criterion

Choose the metric that matches the business goal:
- Accuracy / F1 / AUC: classification quality
- MAE / RMSE: regression
- NDCG / MRR: ranking
- Calibration: probability quality
- Latency, cost: production constraints

Often combine: maximize accuracy subject to latency budget.

## Multi-objective selection

Real model selection has multiple criteria:
- Accuracy
- Latency
- Cost
- Interpretability
- Fairness
- Memory

Pareto front: models not strictly dominated. Choose from the front based on priorities.

## Cross-validation for selection

Cross-validate each candidate. Choose by CV performance.

Pitfall: with many candidates, the best CV score is optimistic. Use a held-out test set after selection.

## When to stop

Diminishing returns: each new model gives less improvement.

Set a budget (time, compute). When you hit it, ship the best so far.

Perfect is the enemy of deployed.

## Common failure patterns

### Skipping baselines

Going straight to deep learning when XGBoost would suffice.

### Tuning on test set

Hyperparameters tuned on test data → optimistic estimates.

### Single split

One train/val/test split is high variance. Use CV.

### Comparing on different data

Different splits, different preprocessing → invalid comparison.

### Optimizing for the wrong metric

Validation accuracy when business cares about precision.

### Ignoring production constraints

Best CV model that doesn't meet latency budget is useless.

### Over-tuning

Diminishing returns; model overfit to validation set.

## A practical recipe

1. Define the metric and constraints
2. Establish baselines (simple model)
3. Try 2-3 strong candidates with default parameters
4. Pick the best; tune hyperparameters
5. Evaluate on test set
6. Profile production characteristics
7. Ship

Resist over-engineering. Most ML wins come from data and feature work, not model selection.

## Further Reading

- [CrossValidationAndModelEvaluation](CrossValidationAndModelEvaluation) — Evaluation methods
- [ModelSelectionEfficiency](ModelSelectionEfficiency) — Efficiency considerations
- [TreeBasedModels](TreeBasedModels) — A common strong baseline
- [ML Hub](MLHub) — Cluster index
