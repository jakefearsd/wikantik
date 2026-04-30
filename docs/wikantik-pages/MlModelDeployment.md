---
canonical_id: 01KQ0P44SJ92AV14Z89S8V8H4Z
title: ML Model Deployment
type: article
cluster: machine-learning
status: active
date: '2026-04-26'
summary: The end-to-end process of deploying ML models to production — packaging,
  versioning, infrastructure, monitoring, rollback, and the organizational practices
  that make deployment routine instead of risky.
tags:
- deployment
- mlops
- machine-learning
- production
- versioning
related:
- InferenceServing
- CostEffectiveInference
- CrossValidationAndModelEvaluation
hubs:
- MLHub
---
# ML Model Deployment

Going from "trained model" to "model serving production traffic" involves more than uploading a file. Deployment touches packaging, versioning, infrastructure, monitoring, and team practices.

This page covers the full process.

## Why ML deployment is harder than software deployment

Software:
- Code is the artifact
- Behavior is deterministic
- Failures are usually obvious

ML:
- Code + weights + data are the artifact
- Behavior depends on data distribution
- Failures can be silent (degraded predictions, no exceptions)

This is why ML deployment needs ML-specific practices.

## Packaging a model

What needs to be deployed:
- **Weights** (the trained parameters)
- **Architecture** (model code)
- **Preprocessing** (feature engineering, tokenization)
- **Postprocessing** (output formatting)
- **Dependencies** (libraries, versions)
- **Metadata** (training data, metrics, hyperparameters)

Single Python files don't capture this. Use:
- Container images (Docker)
- Model registry tools (MLflow, Weights & Biases, Hugging Face Hub)
- Standardized formats (ONNX, TorchScript, TensorFlow SavedModel)

## Model registry

Centralized model storage. Tracks:
- Versions
- Lineage (training data, code, hyperparameters)
- Metrics
- Deployment status

Tools:
- MLflow
- Weights & Biases
- Hugging Face Hub
- SageMaker Model Registry
- Vertex AI Model Registry

A registry separates "model artifacts" from "code repos."

## Versioning

Three things to version together:
- Code (git commit)
- Model weights (model registry version)
- Data (dataset version, schema version)

For reproducibility, all three must align.

Schemes:
- Semantic (1.0.0, 1.0.1)
- Date-based (2026-04-26)
- Hash-based (commit + data hash)

Pick one and stick to it.

## Deployment patterns

### Real-time inference

Synchronous request/response. Used for:
- User-facing predictions
- API integrations
- Interactive systems

Latency-sensitive.

### Batch inference

Score large datasets offline.

Used for:
- Email targeting
- Daily/hourly scoring jobs
- Recommendation pre-computation

Throughput-sensitive; latency rarely matters.

### Streaming inference

Continuous data through model:
- Fraud detection on transactions
- Real-time content moderation

Backpressure and ordering matter.

### Embedded / edge

Model runs on user device. Different constraints (memory, power).

## Rollout strategies

### Big bang

Deploy new version, switch traffic. Risky for ML.

### Canary

Route small % to new version. Monitor. Expand if good.

Most teams should default to canary.

### Shadow

New version receives traffic but responses are discarded. Compare quality offline.

Doesn't risk users; doesn't validate behavior under real conditions.

### A/B test

Different users see different versions. Measure business metrics.

Requires statistical rigor.

### Multi-armed bandit

Dynamically route traffic based on observed performance.

Sophisticated; needed only when frequent retraining matters.

## Pre-deployment checks

Before any deployment:
- Model passes accuracy thresholds on held-out test set
- No data leakage in training
- Inference latency meets SLA
- Memory footprint within budget
- Edge cases tested
- Bias/fairness checks if applicable

Make these automated. Manual checks get skipped.

## Monitoring

### Operational metrics

- Latency
- Throughput
- Error rate
- Resource usage

These are software-deployment standard.

### ML-specific metrics

- Input distribution drift
- Prediction distribution drift
- Quality metrics where ground truth is available
- Confidence/calibration metrics
- Per-segment metrics (don't trust the average)

### Sample-based human review

Some ML failures are only detectable by humans. Sample outputs regularly.

### Alerts

Set thresholds. Alert on:
- Latency regression
- Quality regression
- Distribution shift
- Drop in coverage

## Rollback

Plan rollback before deployment.

Rollback artifacts:
- Previous model version available
- Quick switch mechanism (canary reversal)
- Tested rollback path

Time-to-rollback matters. Aim for minutes, not hours.

## Retraining cadence

Some models age:
- Recommender systems: hours/days
- Fraud detection: weeks
- Image classification: months/years

Decide:
- Manual retraining or automatic?
- Triggered by drift or scheduled?
- New version per retrain?

Automatic retraining + monitoring is the goal but adds complexity.

## Feature stores

For consistent feature engineering between training and serving:
- Feast (open source)
- Tecton, Hopsworks (managed)
- Custom built

Solves: training/serving skew where features computed differently.

Worth it when features are complex or shared across models.

## CI/CD for ML

Pipelines should:
- Run tests on code
- Validate data
- Train (or at least eval) the model
- Compare to baseline
- Deploy if quality clears bar
- Run deployment-time tests

Tools: Kubeflow, MLflow, Vertex AI Pipelines, GitHub Actions.

## Common failure patterns

### Training-serving skew

Features computed differently in training vs serving. Subtle quality regression.

Prevention: shared feature pipeline, integration tests.

### No baseline

Without a baseline model, you can't tell if changes help.

### Eval set rot

Test set used for hyperparameter tuning becomes contaminated. Need fresh holdout.

### No human eval

Some failures only humans can spot.

### Insufficient monitoring

Quality silently degrades. Discovered weeks later from business metrics.

### Skipping shadow / canary

Risk-aversion theater (lots of pre-deploy checks) doesn't substitute for real-traffic validation.

### One-time deployment thinking

Models need redeployment. Build for repeated deploys, not one-shot.

## Organizational concerns

### Who owns a deployed model?

ML team? Platform team? Application team?

Without clear ownership, models rot.

### On-call

Models in production need on-call coverage. Including ML-specific incidents (drift, quality drops).

### Documentation

Model cards: what does this model do, what data was it trained on, what are its limitations.

## Practical maturity model

1. **Manual**: ML engineer manually deploys on request
2. **Pipeline**: scripted deployment, manual quality gates
3. **CI/CD**: automated deployment, automated quality gates
4. **Continuous training**: automated retraining and deployment with monitoring

Most teams are at level 1-2. Reach level 3 before automating retraining.

## Further Reading

- [InferenceServing](InferenceServing) — Serving infrastructure
- [CostEffectiveInference](CostEffectiveInference) — Cost optimization
- [CrossValidationAndModelEvaluation](CrossValidationAndModelEvaluation) — Evaluation
- [ML Hub](MLHub) — Cluster index
