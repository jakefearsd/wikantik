---
tags:
- llm
- cost-optimization
- tokens
- kv-cache
- attention
cluster: generative-ai
type: article
date: 2024-05-16T00:00:00Z
auto-generated: false
canonical_id: 01KQ0P44XRC8X14MGN8J21P08J
summary: A technical guide to token budgeting and memory optimization for LLMs, focusing
  on tokenizer volatility and KV-cache eviction strategies for long-context windows.
title: Token Budgeting
---
# Token Budgeting and Cache Management

As LLM context windows expand to 1M+ tokens, managing the "token budget" transitions from a simple cost problem to a fundamental memory and throughput bottleneck.

## 1. Tokenizer Volatility

The mapping from "words" to "tokens" is non-linear and model-dependent.
*   **Vocabulary Drift:** tiktoken (OpenAI), Llama-3 (Meta), and SentencePiece (Google) use different sub-word merges. A sentence in English might be 10 tokens in one model and 15 in another.
*   **The Special Token Overhead:** System prompts, chat templates (`<|im_start|>`), and tool-calling markers consume a fixed "base tax" of tokens that must be accounted for in every API call.
*   **Compression Ratios:** Technical code and non-English languages often have significantly higher token-to-word ratios, leading to unpredictable cost spikes in multilingual apps.

## 2. The KV-Cache Bottleneck

In Transformer models, the **Key-Value (KV) Cache** stores the pre-computed attention vectors for all tokens in the current context.

### 2.1 Memory Footprint
The KV cache size is $\mathcal{O}(L)$(sequence length). For a 70B parameter model:

$$
\text{Memory per token} \approx 2 \times \text{layers} \times \text{heads} \times d_{head} \times \text{precision\_bytes}
$$
At a 32k context, the cache for a single request can exceed 10GB of VRAM.

## 3. Cache Eviction Strategies

When the token count exceeds the physical memory or the model's architectural limit, we must "evict" tokens from the cache.

1.  **Sliding Window (FIFO):** Simply drop the oldest tokens.
    *   *Risk:* Loses the "System Prompt" and initial instructions, causing the model to lose track of the task.
2.  **Pinned Context:** Protect critical tokens (e.g., indices 0-500) from eviction while using a sliding window for the middle of the conversation.
3.  **Heavy Hitter Oracle (H2O):** Evicts tokens that receive the lowest cumulative attention scores. This keeps "semantically important" tokens (like the subject of a conversation) while dropping fillers.
4.  **Semantic Summarization:** Instead of evicting, the system triggers a background LLM call to summarize the oldest 2000 tokens into 200 tokens, which are then re-inserted as a "memory" block.

## 4. Architectural Optimizations

*   **PagedAttention:** Fragmented KV-cache memory management (similar to OS paging) used by engines like vLLM to achieve 2x-4x throughput by reducing memory waste.
*   **Quantized KV-Cache:** Storing the cache in INT8 or INT4 precision to reduce memory bandwidth and capacity requirements by 50-75% with minimal accuracy loss.
