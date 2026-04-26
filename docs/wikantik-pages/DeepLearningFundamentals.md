---
title: Deep Learning Fundamentals
type: article
cluster: machine-learning
status: active
date: '2026-04-25'
tags:
- deep-learning
- neural-networks
- backpropagation
- training
summary: Forward pass, backpropagation, the loss landscape, why deep networks
  work — the conceptual foundation in plain language, with the engineering
  reasons each ingredient is the way it is.
related:
- NeuralNetworkArchitectures
- GradientDescentAndOptimizers
- LinearAlgebra
- BayesianReasoning
hubs:
- MachineLearning Hub
---
# Deep Learning Fundamentals

Deep learning is fitting parameters of a layered, differentiable function so that it minimises a loss on a dataset. Everything else — backpropagation, optimisers, architectures, regularisation — is engineering on top of that core idea.

This page is what an engineer should know before training their first neural network, and what they should remember when training their hundredth.

## The forward pass

A neural network is a chain of layers, each computing some function of its input plus learned parameters:

```
y = f_n(W_n, ... f_2(W_2, f_1(W_1, x)) ...)
```

Each layer typically does:

1. A linear transformation: `Wx + b`.
2. A non-linearity: ReLU, GeLU, sigmoid, tanh.

Without the non-linearity, stacked linear layers collapse to a single linear layer (linearity is closed under composition). The non-linearity is what lets deep networks represent functions that aren't linearly separable.

## The loss function

A scalar that measures how wrong the network's prediction is for a given input/output pair. Standard choices:

- **Cross-entropy** for classification. Minimised when predicted distribution matches true distribution.
- **Mean squared error** for regression.
- **Binary cross-entropy** for binary classification.
- **Softmax + cross-entropy** combined for multi-class.

The loss is what we minimise. Picking the right loss is more important than people assume; mismatched loss to task produces strange results.

## Backpropagation

The chain rule applied to the loss as a function of the parameters. Working backwards from the loss:

- Compute the gradient of the loss with respect to the final layer's output.
- Use the chain rule to get the gradient with respect to the final layer's parameters AND the gradient with respect to its input (which is the previous layer's output).
- Repeat for each layer.

Conceptually: each layer "knows" how its parameters and inputs contributed to the final loss. We update parameters to reduce that contribution.

Mechanically: every operation in the forward pass needs a corresponding "backward" rule that computes gradients. PyTorch / JAX / TensorFlow handle this automatically (autograd / autodiff) — you write the forward pass; the backward pass is derived.

## Why depth helps

The universal approximation theorem says a single hidden layer (with enough units) can approximate any function. So why deep?

- **Depth represents hierarchical features more efficiently.** Composing simple features layer by layer requires far fewer parameters than building everything at once.
- **Inductive biases.** A 50-layer ConvNet implicitly encodes "objects are made of parts which are made of textures." A single-layer network would need to learn all of that.
- **Empirically, deeper wins** on most tasks given enough data and compute.

The "deep" in deep learning isn't ideological; it's empirical.

## The loss landscape, briefly

The loss as a function of parameters is the loss landscape. Training is moving through this landscape, descending toward low-loss regions.

For deep networks:

- The landscape is **non-convex** — many local minima.
- Most local minima are nearly as good as the global minimum (one of the surprising findings of deep learning).
- **Saddle points** (gradient is zero but it's not a minimum) are more common than local minima in high dimensions; modern optimisers escape them.
- **Sharp vs flat minima** — sharp minima generalise worse than flat ones. Implicit bias toward flat minima is part of why deep networks generalise.

You don't need to understand the geometry deeply to train networks. You do need to know that "training loss went up after I changed the LR" usually means you stepped out of the basin you were in.

## What makes training work

The conditions for successful training:

- **A reasonable architecture.** Too small can't represent the function; too big might overfit or be hard to train.
- **A reasonable loss.** Aligned with what you actually care about.
- **A reasonable optimiser** with a reasonable learning rate. AdamW or SGD with momentum; tuned LR.
- **Reasonable initialisation.** Modern frameworks default to Xavier / Kaiming initialisation; fine for most cases.
- **Reasonable data.** Clean, balanced enough, normalised.
- **Reasonable regularisation.** Some combination of weight decay, dropout, data augmentation.

Each "reasonable" hides the work. If training isn't converging, one of these is wrong.

## Regularisation

Techniques to make networks generalise instead of memorising:

- **Weight decay** — penalises large weights. Almost universal; the canonical regulariser.
- **Dropout** — randomly drops activations during training. Forces the network not to rely on individual neurons. Less common in transformers (which often do without it); essential in older CNNs.
- **Data augmentation** — modify training inputs (rotations, crops, noise). Often more effective than architectural regularisation.
- **Early stopping** — stop training when validation loss stops improving. Cheap and effective.
- **Label smoothing** — train on soft targets instead of one-hot. Gentle regularisation; common in modern training.
- **Mixup / CutMix** — train on linear combinations of samples. Surprisingly effective.

The right amount of regularisation is task-dependent. Too much underfits; too little overfits. Track training and validation loss; if they diverge, you need more regularisation; if they're both high, you need less or more capacity.

## Overfitting and generalisation

Training loss low + test loss low = good model.
Training loss low + test loss high = overfit.
Training loss high + test loss high = underfit (architecture too small or training broken).
Training loss high + test loss low = bug; test set is somehow easier than training; investigate.

The art is reading these signals and adjusting (more data, more regularisation, more capacity, different architecture) accordingly.

## Why batch normalisation matters

Inside a deep network, activations at layer N depend on parameters of all earlier layers. If those parameters change during training, the distribution of inputs to layer N changes too — "internal covariate shift." Each layer is constantly chasing a moving target.

BatchNorm normalises activations to zero mean, unit variance, then learns a scale and shift. Stabilises the distribution; lets you train deeper networks at higher learning rates.

Variants: LayerNorm (across features per sample, used in transformers), GroupNorm (groups of channels, for small batch sizes), RMSNorm (LayerNorm without mean centering, increasingly common in LLMs).

Most deep architectures have some normalisation layer. The flavour matters less than its presence.

## Data is the bottleneck

A common path:

- "Why isn't my model good?" — usually a data problem (not enough, wrong distribution, mislabelled).
- "I tried a bigger model" — often makes overfitting worse without solving the data problem.
- "I tried a different architecture" — usually marginal compared to fixing the data.

Order of investments for a struggling deep learning project:

1. More and better data.
2. Better data preprocessing / augmentation.
3. Better loss function / training objective.
4. Better optimisation hyperparameters.
5. Different / bigger architecture.

Most teams default to #5 first. It's usually the lowest-leverage change.

## What you should know to read papers

To understand a typical deep learning paper:

- The architecture (what layers, what connectivity).
- The loss function (what's being optimised).
- The training data (size, distribution).
- The optimiser and schedule.
- The evaluation metric and baselines.
- The ablations (what each design choice contributes).

Most deep learning papers are about one or two of these; the rest is from prior work. Understanding which novel piece they're proposing is the key skill.

## What's not deep learning's strength

- **Tasks with little data.** Pretrained models help; from-scratch deep learning still loses to simpler methods.
- **Tasks where interpretability matters.** Explaining a deep network's decision is hard; explaining a decision tree is easy.
- **Tasks with hard constraints.** Deep networks output probabilities; if you need a hard guarantee ("never recommend a product user A blacklisted"), enforce in code, not in the network.
- **Tabular data**. Gradient boosted trees (XGBoost, LightGBM, CatBoost) frequently beat neural networks on tabular data and require less hyperparameter tuning.

## A starter pipeline

For someone training their first network:

1. **Pick a small, clean dataset** with a clear metric.
2. **Start with a known-good architecture** for that task. Don't design your own.
3. **Use a known-good optimiser** with the recommended hyperparameters. AdamW, lr=1e-4 to 1e-3.
4. **Track training and validation loss.** Plot. Look at them.
5. **Check what the model is doing**: print confusion matrix, look at misclassified examples, examine outputs.
6. **Iterate based on what you see.** If overfit, regularise; if underfit, more capacity / better data.

After this loop a few times, you have intuition. The intuition is what makes the engineering tractable.

## Further reading

- [NeuralNetworkArchitectures] — what to choose
- [GradientDescentAndOptimizers] — training discipline
- [LinearAlgebra] — the operations
- [BayesianReasoning] — regularisation as priors
