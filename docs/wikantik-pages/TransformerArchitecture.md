---
canonical_id: 01KQ0P44XWV2CPAYD99SW0HVQF
title: Transformer Architecture
type: article
cluster: generative-ai
status: active
date: '2026-04-26'
summary: How transformers actually work — attention, positional encoding, the encoder/decoder
  variants, and the architectural choices that distinguish modern LLMs and enable
  their capabilities.
tags:
- transformer
- architecture
- llm
- generative-ai
- attention
related:
- AgentPromptEngineering
- OpenSourceLlmEcosystem
- NLPOverview
hubs:
- GenerativeAIHub
- MLHub
---
# Transformer Architecture

The transformer architecture, introduced in "Attention is All You Need" (2017), powers modern LLMs and most of contemporary deep learning. Understanding transformers is foundational to working with LLMs.

This page covers how they work and why they matter.

## The core innovation: attention

Pre-transformer sequence models (RNNs, LSTMs) processed tokens sequentially. Information had to flow through hidden states.

Transformers process all tokens in parallel. Each token "attends" directly to every other token via the attention mechanism.

### Self-attention

For each token:
1. Compute query (Q), key (K), value (V) vectors
2. Score against all other tokens: Q · K
3. Softmax over scores
4. Weighted sum of values

Result: each token's output is informed by all input tokens, weighted by relevance.

### Multi-head attention

Run attention multiple times in parallel with different projections. Concatenate.

Different heads learn different relationship types (syntax, semantics, coreference, etc.).

Typical: 12-128 heads.

### Computational cost

Self-attention is O(n²) in sequence length. This is the dominant cost for long contexts.

Many efficiency variants try to reduce this (sparse attention, linear attention, etc.). Standard attention with hardware-aware implementations (FlashAttention) remains common.

## Architecture components

### Embedding layer

Convert tokens to vectors. Typically 1024-12288 dimensions for modern LLMs.

### Positional encoding

Tokens have no inherent position in self-attention. Positional encodings inject position info.

Variants:
- **Sinusoidal** (original): fixed encoding by position
- **Learned**: trainable position embeddings
- **Relative position**: encode distances, not absolute positions
- **RoPE** (Rotary Position Embedding): rotation in attention; standard in modern LLMs
- **ALiBi**: linear bias based on position; helps extrapolation

### Transformer block

Per layer:
1. Layer norm
2. Multi-head attention
3. Residual connection
4. Layer norm
5. Feed-forward network (typically 4x hidden dim)
6. Residual connection

Modern variants tweak normalization placement (pre-norm vs post-norm), use SwiGLU instead of standard FFN, etc.

### Layers

Stack N transformer blocks. Modern LLMs: 32-128+ layers.

### Output

Final layer norm + linear projection to vocabulary size.

For generation: sample from the resulting distribution.

## Variants

### Encoder-only (BERT)

Bidirectional attention. Used for understanding tasks (classification, NER, embedding).

### Decoder-only (GPT)

Causal (left-to-right) attention. Used for generation.

Modern LLMs (GPT, Claude, Llama, etc.) are decoder-only.

### Encoder-decoder (T5, original transformer)

Encoder processes input; decoder generates output. Used for translation, summarization.

Less common for LLMs but still used (T5, FLAN).

### Vision transformers (ViT)

Treat image patches as tokens. Same architecture; different input pipeline.

### Multimodal

Combine text and image (or audio, etc.) tokens. Input modality has its own encoding; the rest of the architecture is shared.

## Training

### Pretraining

Self-supervised on huge text corpora.

Objective:
- BERT: masked language modeling (predict masked tokens)
- GPT: next-token prediction

Pretraining is expensive ($1M-100M+ for state-of-the-art LLMs).

### Fine-tuning

Adapt pretrained model to specific task with labeled data.

### RLHF (Reinforcement Learning from Human Feedback)

Train reward model from human preferences; train policy to maximize reward.

Aligns model with human preferences. Powers ChatGPT, Claude, etc.

### Constitutional AI

Variant where principles ("be helpful, harmless") guide self-critique. Anthropic's approach.

## Key innovations enabling LLM scale

### FlashAttention

Memory-efficient attention. Critical for long contexts.

### Mixture of Experts (MoE)

Multiple FFN "experts"; route each token to a subset.

Effective parameter count > active parameters per inference.

Models: Mixtral, DBRX, GPT-4 (rumored), DeepSeek-V2.

### Grouped query attention (GQA)

Share keys/values across query heads. Reduces memory.

### Sliding window attention

Local attention beyond a window; reduces compute for long context.

### Long context

Original transformers: 512-2K tokens.
Modern: 8K-1M+ tokens.

Achieved through:
- Better positional encodings (RoPE)
- Sparse / efficient attention
- Memory optimizations

## Inference

### Prefill

Process the prompt. Compute K and V for all tokens. Cache them.

This is the parallelizable, fast phase.

### Decode

Generate tokens one at a time. Each token requires reading the entire KV cache.

Memory-bandwidth bound. Why LLM inference is "slow."

### KV cache

Stores K and V from previous tokens. Reused across decode steps.

Memory grows linearly with context length.

### Optimizations

- **Continuous batching**: vLLM, TGI
- **PagedAttention**: efficient KV cache management
- **Speculative decoding**: small model proposes; big model verifies
- **Quantization**: INT8, INT4 weights and activations

## Scaling laws

Empirical findings (Kaplan et al., Chinchilla):
- Performance scales as power law in model size, data, compute
- Compute-optimal training has specific data-to-model ratios

Roughly: bigger model + more data → better performance, predictably.

## Emergent capabilities

Some abilities appear suddenly at scale:
- In-context learning
- Chain-of-thought reasoning
- Tool use
- Multi-turn instruction following

Why exactly is unclear. Architecture + scale + training data interact.

## Limitations

### Hallucinations

Plausible but wrong outputs. Models predict tokens, not truth.

### Computational cost

Even small LLMs are expensive at scale.

### Context window

Limited; long-context models exist but quality degrades with length.

### Lack of grounding

Without retrieval/tools, models reason from training data only.

### Reasoning limitations

Symbolic / mathematical reasoning is fragile. Better with chain-of-thought, tools.

### Training data cutoff

Models don't know recent events without retrieval.

## Why transformers won

- Parallelization (vs sequential RNNs)
- Long-range dependencies (attention can be global)
- Scalability (architecture works at huge scale)
- Hardware fit (matrix multiplications align with GPU/TPU strengths)
- Pretraining + fine-tuning paradigm

These advantages compounded; transformers eclipsed RNNs across NLP within a few years.

## Where transformers might be challenged

- State space models (Mamba): linear-time alternative
- Hybrid architectures: combine attention with other mechanisms
- Specialized architectures for specific modalities

For now, transformers remain dominant.

## Practical takeaways

For practitioners:
- You don't need to implement transformers; use libraries
- Architecture details matter less than data and training
- Pretrained models are the starting point for almost everything
- Inference optimization is its own discipline

For researchers: the architecture is mature; innovation happens at training, data, and adjacent components.

## Further Reading

- [AgentPromptEngineering](AgentPromptEngineering) — Working with LLMs
- [OpenSourceLlmEcosystem](OpenSourceLlmEcosystem) — Open models
- [NLPOverview](NLPOverview) — Broader NLP context
- [Generative AI Hub](GenerativeAIHub) — Cluster index
