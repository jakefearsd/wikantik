---
canonical_id: 01KQ12YDW1PVAGBGRZ8ECJ6346
title: Neural Network Architectures
type: article
cluster: machine-learning
status: active
date: '2026-04-25'
tags:
- neural-networks
- deep-learning
- transformers
- cnn
- rnn
summary: The architectures that show up in 2026 — MLPs, CNNs, RNNs, transformers,
  mixture-of-experts — what each is good at, why transformers eat everything,
  and where the others still fit.
related:
- DeepLearningFundamentals
- ConvolutionalNeuralNetworks
- RecurrentNeuralNetworks
- GradientDescentAndOptimizers
- LinearAlgebra
hubs:
- MachineLearning Hub
---
# Neural Network Architectures

By 2026, transformers have eaten most of the deep-learning landscape. They started in language, took over vision, made meaningful inroads into speech, audio, and biology. Older architectures (CNNs, RNNs, MLPs) still appear, but the slope of "transformers everywhere" has steepened year over year.

This page is the architectures you'll meet, what they do, and the cases where each is still the right pick.

## MLP (Multi-Layer Perceptron)

The simplest neural network: stacked dense layers with non-linearities.

```
input -> linear(W1) -> ReLU -> linear(W2) -> ReLU -> ... -> output
```

When it's the right answer:

- **Tabular data.** Flat vectors of features, no spatial or sequential structure. Pure MLPs (or gradient-boosted trees, often more accurate at this scale) are the right tool.
- **Final classification heads** on top of bigger models. The 7B-parameter Llama ends in an MLP that maps the final hidden state to vocabulary logits.

When it's not:

- Anything with structure (images, text, time-series). You'll lose to architectures that exploit it.

## CNN (Convolutional Neural Network)

Convolutions detect local patterns; stacking convolutions detects hierarchical patterns. The original "ImageNet moment" architecture (AlexNet, 2012) was a CNN.

When CNNs are still the right pick:

- **Edge / embedded inference** where parameter and compute budgets are tight. CNNs are often 10-100× more efficient than transformers at the same task on small images.
- **Highly local-pattern tasks** (defect detection, OCR character recognition). Pure local features; no need for global attention.
- **Some specialised vision tasks** where ResNets and EfficientNets remain strong baselines.

When CNNs lose:

- **General vision.** Vision Transformers (ViTs), Swin Transformers, and hybrid CLIP-like models beat CNNs on ImageNet, COCO, and most modern benchmarks given enough data.
- **Multimodal tasks.** Transformers' shared input format makes vision-language modelling tractable in a way CNNs don't.

See [ConvolutionalNeuralNetworks].

## RNN / LSTM / GRU

Recurrent networks process sequences one step at a time, maintaining state across steps. LSTMs and GRUs added gating to handle long-range dependencies.

By 2026, RNNs are mostly historical for language. Transformers replaced them in 2017–2019. They persist in:

- **Tiny embedded / on-device** inference where parameter count matters and sequences are short.
- **Streaming / online** settings where step-by-step processing is natural and transformers' parallelisation advantage is moot.
- **State-space models** (Mamba, S4, S6) — a 2023-onwards revival of recurrent ideas with stronger long-context properties than attention. Increasingly competitive at long-context tasks.

For most sequence work in 2026, transformers are the default; state-space models are emerging as a contender for very-long-context tasks.

See [RecurrentNeuralNetworks].

## Transformers

The architecture that changed everything (Vaswani et al., 2017). Self-attention + position encoding + feedforward, stacked.

The core idea: every token can attend to every other token, weighted by learned similarity. The model decides what's relevant; you don't pre-specify locality (CNN) or recurrence (RNN).

Properties that made it dominant:

- **Parallelisable training.** Unlike RNNs, you can compute all positions at once. GPUs love this.
- **Scales gracefully.** Performance improves predictably with parameters, data, and compute (the "scaling laws").
- **Universal substrate.** The same architecture handles text, images, audio, code, biology — anything you can tokenise.
- **Pretraining + fine-tuning.** A big pretrained transformer is a strong starting point for many tasks.

Variants:

- **Encoder-only** (BERT, RoBERTa) — for understanding tasks; no autoregressive generation.
- **Decoder-only** (GPT family, Llama, Claude) — for generation. Dominant since GPT-3.
- **Encoder-decoder** (T5, BART) — for sequence-to-sequence (translation, summarisation). Still used; less common than decoder-only by 2026.

Cost: attention is O(n²) in sequence length. Long-context tricks (FlashAttention, ring attention, sparse attention, linear attention) push this lower in practice.

## Mixture of Experts (MoE)

Each layer has multiple "expert" sub-networks; a router picks which ones process each token. Total parameters are huge; activated parameters per token are small.

Examples: Mixtral 8x7B (47B total parameters, ~13B activated per token), DeepSeek-V3.

Win:

- Far better quality per inference FLOP than a dense model of the same activated size.
- Allows scaling parameters past where dense models become uneconomical to serve.

Trade-off:

- Memory footprint is large (you load all experts even if you only activate a few).
- Routing introduces complexity in training and serving.

Production trend: most frontier models in 2026 are MoEs internally. Open-weights MoEs are increasingly common.

## Diffusion models

For generation tasks (image, video, audio, occasionally text), diffusion models work by learning to reverse a noise process. Given pure noise, they progressively denoise into a sample.

State of the art for image and video generation in 2026 (Stable Diffusion 3, FLUX, video-generation models). Different in spirit from autoregressive language models — generates the full output in parallel rather than token-by-token.

Use when:

- Image / video / audio generation is the task.
- You want high-fidelity samples; latency budget allows multiple denoising steps.

Don't use when:

- The task is fundamentally autoregressive (text generation, code generation). Transformers do this better.

## Architecture decisions you might actually face

### "What architecture for my custom task?"

In 2026, the answer is almost always:

1. **If labelled data is small (< 100k examples)**: fine-tune a pretrained model. The pretrained backbone is more important than the architecture choice. For text → fine-tune an existing LLM. For vision → fine-tune a CLIP or DINO.
2. **If you're doing image classification/detection/segmentation specifically**: a vision transformer or hybrid (DINOv2, EVA, ConvNext) pretrained at scale, fine-tuned on your data.
3. **If you're doing tabular data**: try gradient-boosted trees first. They often win.
4. **If you have a genuinely novel modality**: tokenise it and use a transformer. The substrate is universal enough.

The era of designing custom architectures for specific tasks is mostly over. Pretrained big models, adapted, win.

### "How big a model do I need?"

For most production deployments:

- **1-3B parameters**: sufficient for most narrow fine-tuned tasks, fast on commodity GPUs.
- **7-70B parameters**: better quality; may need quantisation or self-hosted GPUs.
- **Frontier (100B+)**: API-only for most teams; reserved for tasks where quality matters most.

The right size is task-dependent. Run a few, measure, pick.

### "Open weights or commercial API?"

- **Commercial API** (Claude, GPT, Gemini): better quality, simpler operations.
- **Open-weights** (Llama, Mistral, Qwen, DeepSeek): control, lower cost at scale, on-prem.

Most production systems pair: open-weights for high-volume routine tasks, commercial API for hard tasks where the quality gap pays.

## What's emerging

- **Mamba / state-space models** — competitive with transformers at long context; cheaper inference.
- **Sparse / mixture-of-depths** architectures that prune compute per token.
- **Multimodal-by-default** models (Gemini, GPT-4o-style) that handle text, vision, audio in one substrate.
- **Reasoning models** (o1, Claude extended thinking, DeepSeek R1) — separate inference-time reasoning compute.
- **Test-time compute scaling** — spending more inference compute for harder tasks rather than larger models.

Where the 2027-2028 architectures land is unclear. The trend over the past five years has been "transformers but bigger" with some innovation around efficiency. Expect continued mostly-transformers with significant inference-time techniques.

## Further reading

- [DeepLearningFundamentals] — the math underneath
- [ConvolutionalNeuralNetworks] — CNNs in depth
- [RecurrentNeuralNetworks] — RNNs and their successors
- [GradientDescentAndOptimizers] — how any of these are trained
- [LinearAlgebra] — the operations that fill GPUs
