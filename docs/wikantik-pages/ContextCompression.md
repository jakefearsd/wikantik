---
- type: part-of
  target_id: 01KQEKGDAZH3G3X2J4VFM9MP88
- type: implements
  target_id: 01KQ0P44Q1GRZAM50GV1CFTN7B
- type: derived-from
  target_id: 01H8G3Z1K6Q5W7P9X2V4R0T8MN
type: article
tags:
- generative-ai
- llm
- context-management
- context-compression
- token-optimization
- rag
summary: Advanced techniques for maximizing information density within the LLM context
  window. Covers token-pruning (LLMLingua), KV cache management (StreamingLLM), and
  recursive summarization for long-range dependency handling.
status: active
date: 2026-05-03T00:00:00Z
title: Context Compression
cluster: generative-ai
canonical_id: 01KQ0P44NZTW99SE4ZZ8P1BX2D
---

# Context Compression: Maximizing the Attention Budget

The "Attention Bottleneck" is the primary constraint in modern [Agentic AI](AgenticAiHub). As context windows grow to 1M+ tokens, the signal-to-noise ratio drops, costs spike, and model performance on "needle-in-a-haystack" tasks degrades. Context Compression is the discipline of reducing raw token count while preserving semantic signal.

## 1. Selective Context & Token Pruning (LLMLingua)

Selective context techniques use a smaller, faster "compressor" model to identify and remove low-information tokens from the prompt before it reaches the target LLM.

- **LLMLingua**: Uses a small language model (e.g., Llama-3-8B) to calculate the perplexity of each token in the context. Tokens with low surprise (high probability) are often filler words or redundant and can be pruned with minimal loss in meaning.
- **Compression Ratio**: These techniques can achieve 2x–5x compression while maintaining 95%+ of the original performance on RAG benchmarks.

## 2. KV Cache Management

The Key-Value (KV) cache stores the attention states for all tokens in a conversation. In long-running agent interactions, this cache becomes the primary memory bottleneck.

### StreamingLLM & Attention Sinks
Standard windowed attention fails because the model loses "attention sinks" (often the first few tokens in a prompt) that are critical for the softmax distribution. **StreamingLLM** preserves the first $N$ tokens (the sink) and the most recent $L$ tokens (the local context), allowing for infinite conversation length without the quadratic memory cost of full attention.

### Heavy Hitter Oracle (H2O)
H2O prunes the KV cache by identifying and keeping only the "heavy hitter" tokens—those that consistently receive high attention weights. This reduces the cache footprint by up to 80% without significantly impacting long-range recall.

## 3. Recursive Summarization

For tasks that exceed even large context windows (e.g., analyzing a library of 100 books), agents use a hierarchical approach:
1.  **Chunking**: Documents are split into segments.
2.  **Summarization**: Each segment is summarized into a dense "fact list."
3.  **Recursive Stacking**: Summaries are themselves grouped and summarized until a top-level "Global World Model" is formed.

## 4. Contextual Distillation

A "Knowledge Distillation" approach where a long context is passed through a model, which then generates a **Dense Knowledge State**. This state—a list of specific facts, constraints, and observations—is used as the "Short-Term Memory" in subsequent turns, effectively "forgetting" the original verbose text while retaining its meaning.

## See Also
- [[EfficientContext]] — Managing the context stack.
- [[InformationTheory]] — Mathematical foundations of compression.
- [[VectorIndexingInternals]] — How retrieval feeds the context.
- [[AgenticAiHub]] — Using compressed context in autonomous agents.
