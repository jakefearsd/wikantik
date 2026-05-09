---
canonical_id: 01KQQ6SGVRSG0BJMX4AKGGF23S
date: 2025-02-13T00:00:00Z
cluster: mathematics
type: article
tags:
- mathematics
- information-theory
- entropy
- shannon
- llm
- compression
title: Information Theory
- type: part-of
  target_id: 01KQ2P44XMGA8E1E7GAT4AYV43
- type: prerequisite-for
  target_id: 01KQEKGD8QYAS6P09AM61S5E2W
- type: prerequisite-for
  target_id: 01KQEKGDAZH3G3X2J4VFM9MP88
summary: Advanced Information Theory focused on Shannon entropy, token compression math, and the fundamental limits of LLM context windows.
status: active
auto-generated: false
---

# Information Theory: Entropy, Compression, and LLM Economics

Information Theory is the rigorous quantification of uncertainty, storage, and communication. Founded by Claude Shannon in 1948, it provides the mathematical limits for data compression (Source Coding) and transmission (Channel Capacity). In the era of Large Language Models (LLMs), these principles govern tokenization efficiency, KV cache scaling, and the "Information Bottleneck" of neural representations.

## 1. Shannon Entropy ($H$): The Limit of Surprise

Entropy measures the average amount of information produced by a stochastic source. For a discrete random variable $X$ with outcomes $\{x_1, \dots, x_n\}$ and probability mass function $P(X)$, entropy is defined as:

$$H(X) = -\sum_{i=1}^{n} P(x_i) \log_2 P(x_i)$$

If $P(x_i) = 1$ for some $i$, $H(X) = 0$ (no uncertainty). Maximum entropy occurs when all outcomes are equiprobable ($P(x_i) = 1/n$), yielding $H(X) = \log_2 n$.

### Concrete Example: LLM Token Prediction
Consider a vocabulary of 50k tokens. 
- **Uniform Distribution:** If the next token were selected randomly, entropy would be $\log_2(50,000) \approx 15.6$ bits/token.
- **Natural Language:** Real-world English is highly redundant. An LLM might predict the next token with high confidence (e.g., after "The capital of France is", the token "Paris" has $P \approx 0.99$).
- **Surprisal:** The information content of "Paris" in this context is $-\log_2(0.99) \approx 0.014$ bits. If the LLM predicted "Banana" ($P \approx 10^{-6}$), the surprisal would be $\approx 19.9$ bits.

## 2. Token Compression Math

The **Source Coding Theorem** states that the minimum average code length $L$ for a source $X$ satisfies $L \geq H(X)$. In LLMs, this dictates the efficiency of Tokenizers (like BPE or SentencePiece).

### The Tokenization Bottleneck
Tokenizers map raw bytes to integers. If a tokenizer is inefficient (e.g., character-level), the "entropy per token" is low, wasting the LLM's finite context window.
- **Efficiency Metric:** $\eta = \frac{H(\text{Source})}{\text{Average Bits Per Token}}$.
- **Compression Gaps:** When an LLM processes code (highly structured) vs. creative writing, the bits-per-character varies. Information Theory allows us to quantify why a 128k context window feels "shorter" for dense technical logs than for prose.

## 3. Mutual Information and The Information Bottleneck

Mutual Information $I(X; Y)$ quantifies how much information is shared between two variables:
$$I(X; Y) = H(X) - H(X|Y) = H(Y) - H(Y|X)$$

In Deep Learning, the **Information Bottleneck (IB)** theory suggests that a network learns by:
1. Maximizing $I(X; T)$: Retaining relevant information from input $X$ in hidden representation $T$.
2. Minimizing $I(T; Y)$: Compressing $T$ to remove noise while preserving the ability to predict $Y$.

## 4. Channel Capacity ($C$) and Context Windows

Shannon's Noisy-Channel Coding Theorem defines the maximum rate $C$ at which information can be transmitted with zero error:
$$C = B \log_2(1 + \text{SNR})$$

In RAG (Retrieval-Augmented Generation), we can model the retrieval process as a noisy channel. If the "noise" (irrelevant chunks) is too high relative to the "signal" (relevant facts), the LLM's "Channel Capacity" to produce a correct answer collapses.

## Summary Table: Compression in Practice

| Format | Entropy Basis | Application |
| :--- | :--- | :--- |
| **Huffman Coding** | Frequency-based | Deflate, ZIP |
| **Arithmetic Coding** | Range-based | H.264, Modern Compression |
| **BPE Tokenization** | Subword-frequency | GPT-4, Llama-3 |
| **KV Cache** | Temporal redundancy | Transformer Inference |

## See Also
- [[MathematicsHub]]
- [[ProbabilityTheory]]
- [[MathematicalFoundationsOfMachineLearning]]
- [[ContextCompression]]
