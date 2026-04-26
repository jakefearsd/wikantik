---
title: Recurrent Neural Networks
type: article
cluster: machine-learning
status: active
date: '2026-04-25'
tags:
- rnn
- lstm
- gru
- state-space-models
- sequence-modeling
summary: RNNs in 2026 — mostly historical for language, but the recurrent
  inductive bias is making a comeback through state-space models (Mamba, S4)
  for long-context tasks.
related:
- NeuralNetworkArchitectures
- DeepLearningFundamentals
- GradientDescentAndOptimizers
hubs:
- MachineLearning Hub
---
# Recurrent Neural Networks

RNNs were the workhorse for sequence modelling — language, speech, time series — for the decade between 2010 and 2020. Transformers replaced them for most language tasks. As of 2026, RNNs are mostly historical for language, but the underlying idea (state propagated through time) has resurged in state-space models (Mamba, S4, S6) that compete with transformers on long-context tasks.

This page is what you actually need to know about RNNs and their successors in 2026.

## The basic RNN

```
h_t = tanh(W_x @ x_t + W_h @ h_{t-1} + b)
y_t = W_y @ h_t + b
```

Hidden state `h_t` carries information forward. Each time step's prediction depends on the input and the prior state.

Two problems with vanilla RNNs:

1. **Vanishing / exploding gradients.** Backpropagation through many time steps multiplies many small/large values; gradients vanish or explode.
2. **Sequential computation.** Each step depends on the previous; can't parallelise across time during training. Slow.

## LSTM (Long Short-Term Memory)

Adds a "cell state" that flows through the sequence with controlled updates via three gates: forget, input, output. Solves the vanishing gradient problem; handles long-range dependencies much better than vanilla RNN.

For about a decade (2014-2020), LSTM was the default for sequence-to-sequence problems. Translation, summarisation, language modelling.

## GRU (Gated Recurrent Unit)

Simpler than LSTM (two gates instead of three; no separate cell state). Often performs similarly. Cheaper to train.

## Why transformers ate the field

For language and most sequential tasks, transformers (Vaswani et al., 2017) won decisively because:

- **Parallelisable training.** Compute all positions at once. GPUs love this; RNNs leave compute on the table.
- **Better long-range dependencies.** Attention directly connects any two positions; LSTMs have to propagate information through every intermediate step.
- **Empirically stronger** at most NLP benchmarks once the architectures matured.

By 2020-2021, almost all new language models were transformers. RNN training stopped being an active research area.

## Where RNNs persist

For specific cases, RNNs are still chosen:

### Tiny embedded inference

A small LSTM on a microcontroller doing keyword detection, sensor anomaly detection, or simple speech features can run with kilobytes of memory. Transformers don't fit.

### Streaming / online inference

Process one element at a time as it arrives. RNNs naturally suit this; transformers' parallel compute is moot when input arrives sequentially anyway.

Speech recognition pipelines often have an RNN-based "online" component for real-time transcription.

### Some time-series tasks

For forecasting workloads where the patterns are local in time and the dataset is small, LSTMs sometimes beat transformers. The literature is mixed; benchmark on your data.

## State-space models: the comeback

State-space models (SSMs) treat sequence modelling as a continuous state-space dynamical system, discretised. Examples:

- **S4** (Albert Gu et al., 2021) — efficient long convolutions parameterised as state-space systems.
- **Mamba** (2023) — selective state-space model with input-dependent dynamics. Competes with transformers on language and decisively wins on very long sequences.
- **S6 / Mamba 2** (2024) — refinements.

Why SSMs matter:

- **Linear-time inference** in sequence length, vs transformers' quadratic.
- **Linear-time training** with parallel scan operators. Closer to transformers' parallelism than vanilla RNNs.
- **Strong on long-context** tasks (genome, audio, very long documents).

As of 2026, Mamba-based and hybrid Mamba-attention models are showing up in production. Whether they fully replace transformers or settle into a niche for long-context-specific tasks is unclear; the trend has accelerated since 2023.

The SSM mental model is closer to a recurrent state than to attention. The underlying observation — recurrence captures temporal dynamics efficiently — is the same insight RNNs had; the engineering finally caught up.

## When to reach for which

In 2026:

- **Default for language**: transformer (decoder-only LLM family). Use unless you have a specific reason.
- **Long-context language** (>32k tokens): consider Mamba-style or hybrid attention-SSM models. Transformers with rotary position + ring attention also remain competitive at this scale.
- **Time-series forecasting**: try transformers (Informer, PatchTST), SSMs, and gradient-boosted trees. Each wins on different datasets; ensemble often best.
- **Tiny embedded**: GRU or LSTM still common; ONNX-quantised model. Transformer alternatives (TinyBERT etc.) compete on some tasks.
- **Speech recognition**: transformer (Whisper, Conformer) for offline; RNN-T or transformer streaming for online.
- **Online / streaming generic**: depends on task; both architectures have streaming variants.

## Training discipline

RNNs and their successors share challenges:

- **Gradient clipping** is essential. Unbounded gradients explode the optimiser.
- **Truncated BPTT** — backpropagate only N steps back, not the full sequence. Most training uses this.
- **Weight initialisation** matters more than for transformers. Xavier / Orthogonal / specific schemes for state-space models.
- **Regularisation** — variational dropout (Gal & Ghahramani) is RNN-specific and often helps.

Modern frameworks (PyTorch, JAX) handle most of this with sensible defaults; following the standard recipe is usually enough.

## Architectural trivia worth knowing

- **Bidirectional RNNs** — process the sequence forward and backward; concatenate hidden states. Used when the full sequence is available (not streaming). Common in older NLP for tagging tasks.
- **Encoder-decoder RNNs** — separate networks for input and output sequences, connected via attention. The original neural machine translation architecture; replaced by transformer encoder-decoder.
- **Pointer networks** — RNN with attention used to point at input positions. Used in some retrieval and combinatorial optimisation work.

These appear in older literature; rarely in new production code.

## Further reading

- [NeuralNetworkArchitectures] — full architecture landscape including SSMs
- [DeepLearningFundamentals] — math
- [GradientDescentAndOptimizers] — training discipline
