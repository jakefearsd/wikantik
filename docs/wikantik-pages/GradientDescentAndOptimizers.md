---
canonical_id: 01KQ12YDV1FC565JEW2X6HJA23
title: Gradient Descent And Optimizers
type: article
cluster: machine-learning
status: active
date: '2026-04-25'
tags:
- gradient-descent
- adam
- sgd
- optimizers
- deep-learning
summary: SGD, momentum, Adam, AdamW, Lion, and the practical optimisation choices
  for training neural networks — including the learning-rate schedules that
  decide whether your model learns at all.
related:
- DeepLearningFundamentals
- LinearAlgebra
- NeuralNetworkArchitectures
- LLMFineTuning
hubs:
- MachineLearning Hub
---
# Gradient Descent and Optimizers

Training a neural network is gradient descent over a loss landscape. The objective is simple; the practical decisions are what separate "trains" from "doesn't train" — learning rates, weight decay, momentum, batch sizes, schedules. This page is the working set, with the choices that pay off in practice.

## Vanilla gradient descent

Update rule:

```
θ ← θ - η ∇L(θ)
```

Where `η` is the learning rate, `∇L(θ)` is the gradient of the loss.

Simple. Almost never used directly for neural network training; useful as the conceptual base.

## Stochastic gradient descent (SGD)

Compute the gradient on a *batch* of samples instead of the full dataset. Take a step. Repeat.

```
for each batch B in dataset:
    g = ∇L(θ; B)
    θ ← θ - η g
```

Why batches:

- **Computational** — full-dataset gradients are intractable for big datasets.
- **Statistical** — noise in mini-batch gradients acts as a regulariser.

Batch size is a hyperparameter. For neural networks: 32-256 typical for small models on CPU/single-GPU; 512-4096 for distributed training; "global batch size" up to millions of tokens for frontier-scale LLM training.

Larger batches: faster wall-clock, but worse optimisation per step. Smaller batches: more noise, often better generalisation. The "linear scaling rule" says doubling batch size should approximately double the learning rate; works as a heuristic up to limits.

## Momentum

Plain SGD oscillates in directions of high curvature; momentum dampens that.

```
v ← βv + ∇L(θ)
θ ← θ - η v
```

Where `β ≈ 0.9` typical. The velocity `v` accumulates gradient direction; small consistent gradients build up; oscillating gradients cancel.

Almost universal in modern training; "SGD" usually means "SGD with momentum."

## Adam

Adaptive learning rates per parameter, using running averages of first and second moments of the gradient.

```
m ← β1 m + (1-β1) g
v ← β2 v + (1-β2) g²
m̂ ← m / (1 - β1^t)
v̂ ← v / (1 - β2^t)
θ ← θ - η m̂ / (sqrt(v̂) + ε)
```

Defaults: `β1=0.9`, `β2=0.999`, `ε=1e-8`. `η` typically 1e-3 or 1e-4 for transformer-class models.

Why Adam dominated:

- **Adaptive** — parameters with consistently large gradients get smaller effective learning rates; vice versa.
- **Robust** — works on a wide range of problems with default hyperparameters.

Critique:

- Generalises slightly worse than tuned SGD on some tasks (notably image classification with ResNets).
- Memory overhead is 2× parameters (you store `m` and `v` for each).

For most modern deep learning work, Adam (or AdamW) is the default. SGD with momentum is preferred only when you have a specific reason and budget to tune.

## AdamW

Adam with weight decay decoupled from the gradient update.

```
θ ← θ - η m̂ / (sqrt(v̂) + ε) - η λ θ
```

The added term `η λ θ` is the weight decay. In Adam, weight decay was implemented via L2 regularisation (added to the loss) which interacts badly with the adaptive scaling. AdamW makes it decoupled, behaving as a true regulariser.

For transformer training (and most modern deep learning), AdamW is the default. The original Adam is rarely the right choice anymore.

## Lion

Recently proposed (Google, 2023). Uses sign of the gradient, simpler update rule:

```
m ← β1 m + (1-β1) g
update = sign(β2 m + (1-β2) g)
θ ← θ - η update - η λ θ
```

Pros: half the memory of AdamW (only `m`, not `m` and `v`). Sometimes faster wall-clock; often comparable quality.

Cons: less well-tuned defaults; smaller learning rates needed (~1/3 of AdamW); less mature literature.

Worth trying for large-scale training where memory matters. Production deployments are catching up.

## Other optimizers

- **RMSprop** — Adam without first-moment tracking. Predates Adam; rarely the right choice now.
- **Adagrad** — adaptive learning rates that decrease over time. Suited for sparse data; rarely competitive in deep learning.
- **L-BFGS** — second-order methods. Excellent for full-batch optimisation; impractical for stochastic neural network training.
- **Shampoo** — second-order method that's been showing up recently for some training. Memory-heavy; competitive on specific benchmarks.

For 2026 neural network training, AdamW is the default; SGD with momentum if you specifically need it; Lion for memory-constrained large training; everything else is niche.

## Learning rate schedules

A constant learning rate works poorly. Almost every successful training run has a schedule.

### Warmup + decay

The pattern that won:

1. **Warmup** — increase learning rate linearly from 0 to the target over the first ~5% of training. Without this, large models early in training make catastrophically large updates.
2. **Decay** — decrease the learning rate over the rest of training. Cosine decay is the most common choice; linear decay also works.

```
# Cosine schedule with warmup
if step < warmup_steps:
    lr = max_lr * step / warmup_steps
else:
    progress = (step - warmup_steps) / (total_steps - warmup_steps)
    lr = max_lr * 0.5 * (1 + cos(π * progress))
```

This pattern is standard in transformer pretraining. For fine-tuning, similar shape with a smaller `max_lr`.

### Cyclical and one-cycle

Some workloads benefit from cyclical schedules — a single up-down peak per training run, or repeated cycles. Less common in language model training; sometimes useful for vision.

### LR finding

For a new model, run a short pilot with the LR ramping from 1e-7 to 1 over ~100 steps. Plot loss vs LR. The right LR is roughly where the loss is steeply decreasing but not yet exploding.

This gives you a starting point. From there, you can tune more carefully if it matters.

## Practical hyperparameter starting points

For a new transformer-class model:

```
optimizer:        AdamW
learning_rate:    1e-4 to 3e-4 (training); 1e-5 to 5e-5 (fine-tuning)
weight_decay:     0.1 (training); 0.01 (fine-tuning)
β1:               0.9
β2:               0.95 to 0.999 (lower β2 is better for noisy training)
ε:                1e-8
warmup_steps:     1-5% of total steps
schedule:         cosine decay to 10% of max LR
gradient_clip:    1.0 (norm)
batch_size:       as large as memory allows
```

These defaults will not be optimal but will train. Starting points to tune from.

## Gradient clipping

Norm-based clipping rescales the gradient if its global norm exceeds a threshold:

```
g_norm = ||g||
if g_norm > threshold:
    g = g * (threshold / g_norm)
```

Threshold of 1.0 is typical. Prevents single huge gradients from blowing up the optimiser state. Especially important for transformer training and RL.

## Mixed precision

FP32 weights, FP16 (or BF16) activations. Memory roughly halved, training speed roughly doubled on modern GPUs. With loss scaling (or BF16 native, no scaling needed), accuracy is essentially unchanged.

Standard practice for any non-trivial deep learning training in 2026. PyTorch's `torch.cuda.amp` or `torch.autocast` handles it.

For large-model training, also consider:

- **Gradient accumulation** — accumulate gradients over N micro-batches; update once. Simulates larger effective batch sizes.
- **Activation checkpointing** — recompute activations during backward instead of storing them. Trades compute for memory.
- **ZeRO / FSDP** — shard optimiser state and gradients across GPUs. Essential for training models that don't fit on one GPU.

## Common failure modes

**Loss explodes.** Learning rate too high; gradient norm not clipped; numerical instability in mixed precision. First thing to try: lower LR by 3-10×; add or tighten gradient clipping.

**Loss flat.** Learning rate too low; warmup too short; broken data pipeline (loss is constant because labels are constant). Inspect data; print samples; verify gradient norms are non-zero.

**Loss oscillates.** Batch size too small; LR too high; β2 too high (Adam taking too-aggressive steps from a noisy estimate). Try smaller LR, larger batch, lower β2.

**Loss decreases then increases.** You're past the optimal LR; cosine decay should be helping. Try lower LR; verify your schedule is actually decaying.

**Different runs give very different results.** High sensitivity to seed indicates a fragile setup; longer warmup, gradient clipping, smaller LR all help.

## Diagnostics worth tracking

During training, log every step or every N steps:

- Training loss.
- Learning rate.
- Gradient norm.
- Validation loss (every N steps).
- Sample outputs (qualitative, every M steps).
- Per-parameter-group statistics if you've grouped (e.g. embeddings vs body).

Sudden discontinuities in any of these are usually bugs (data pipeline switched batches, loss reset, NaN). Catch early.

## Further reading

- [DeepLearningFundamentals] — the broader context
- [LinearAlgebra] — what the matrix operations are
- [NeuralNetworkArchitectures] — what gets optimised
- [LLMFineTuning] — applied optimiser choices for LLM training
