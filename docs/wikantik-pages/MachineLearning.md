---
canonical_id: 01KQ0P44S50Z07V4E8K8XA6Q43
title: Machine Learning
tags:
- backprop
- scaling-laws
- bias-variance
- transformer
cluster: machine-learning
type: article
date: 2025-05-15T00:00:00Z
auto-generated: false
summary: Bias-variance decomposition, backpropagation via the chain rule, and Transformer
  scaling laws (Chinchilla) — the mathematical foundations behind modern ML.
---

# Machine Learning: Calculus, Tradeoffs, and Scaling Laws

This article moves beyond the introductory surface of Machine Learning to examine the rigorous mathematical foundations of optimization and the emergent laws governing large-scale model training.

## I. The Bias-Variance Tradeoff: A Decomposition

The generalization error of any machine learning model can be decomposed into three components: **Bias**, **Variance**, and **Irreducible Error**.

### A. The Error Decomposition
For a true function $f(x)$ and an estimate $\hat{f}(x)$, the expected Mean Squared Error (MSE) at a point $x$ is:

$$
\mathbb{E}[(y - \hat{f}(x))^2] = \text{Bias}[\hat{f}(x)]^2 + \text{Var}[\hat{f}(x)] + \sigma^2
$$

*   **Bias ($\mathbb{E}[\hat{f}(x)] - f(x)$):** Error from erroneous assumptions in the learning algorithm (underfitting).
*   **Variance ($\mathbb{E}[\hat{f}(x)^2] - \mathbb{E}[\hat{f}(x)]^2$):** Error from sensitivity to small fluctuations in the training set (overfitting).
*   **$\sigma^2$ (Irreducible Error):** The noise inherent in the data itself.

### B. The Double Descent Phenomenon
Modern deep learning has challenged the classical view that increasing model complexity always increases variance. In the **Interpolation Regime**, where models are large enough to perfectly fit the training data, the test error often enters a second "descent" phase, achieving lower error than smaller models.

## II. The Multivariable Calculus of Backpropagation

Backpropagation is the application of the **Chain Rule** to compute the gradient of the loss function $\mathcal{L}$ with respect to the weights $W$ in a computational graph.

### A. The Forward Pass
For a layer $l$:

$$
z^{(l)} = W^{(l)} a^{(l-1)} + b^{(l)}
$$

$$
a^{(l)} = \sigma(z^{(l)})
$$

### B. The Backward Pass (The Gradient Flow)
We define the "error" $\delta^{(l)}$ for layer $l$ as the derivative of the loss with respect to the pre-activation input $z^{(l)}$:

$$
\delta^{(l)} = \frac{\partial \mathcal{L}}{\partial z^{(l)}}
$$

1.  **Output Layer Error:**

    $$
    \delta^{(L)} = \nabla_a \mathcal{L} \odot \sigma'(z^{(L)})
    $$

2.  **Hidden Layer Error (Recursive):**

    $$
    \delta^{(l)} = ((W^{(l+1)})^T \delta^{(l+1)}) \odot \sigma'(z^{(l)})
    $$

3.  **Weight Gradients:**

    $$
    \frac{\partial \mathcal{L}}{\partial W^{(l)}} = \delta^{(l)} (a^{(l-1)})^T
    $$

### C. Optimization and Vanishing Gradients
The multiplicative nature of the chain rule in deep networks leads to the **Vanishing Gradient** problem, where $\delta^{(l)}$ approaches zero as it propagates backward. Solutions include **Residual Connections** and **Batch Normalization**, which maintain the magnitude of the gradient signal.

## III. Transformer Scaling Laws: The Compute-Optimal Frontier

The transition from small models to Large Language Models (LLMs) is governed by "Scaling Laws"—empirical power laws relating loss to compute ($C$), parameters ($N$), and data ($D$).

### A. The Power Law Form
Kaplan et al. (2020) observed that cross-entropy loss $L$ follows:

$$
L(N, D) \propto \left( \frac{N}{N_c} \right)^{-\alpha_N} + \left( \frac{D}{D_c} \right)^{-\alpha_D}
$$

This suggests that as long as you scale $N$ and $D$ in tandem, the loss continues to decrease predictably.

### B. The Chinchilla Scaling (Hoffmann et al., 2022)
The "Chinchilla" research corrected the Kaplan laws, identifying that previous models (like GPT-3) were significantly undertrained. 
*   **The Chinchilla Rule:** For every doubling of compute budget, both model size ($N$) and training data ($D$) should be scaled equally.
*   **Tokens-to-Parameter Ratio:** The compute-optimal ratio is approximately **20 tokens per parameter**. A 70B parameter model requires ~1.4 trillion tokens to be compute-optimal.

## IV. The Modern Stack: Attention and Normalization

The success of modern architectures rests on two key mechanisms:

1.  **Self-Attention:** $\text{Attention}(Q, K, V) = \text{softmax}(\frac{QK^T}{\sqrt{d_k}})V$. This allows for global dependency modeling with $\mathcal{O}(L^2)$ complexity, where $L$ is sequence length.
2.  **LayerNorm vs. RMSNorm:** Normalization is critical for stabilizing deep networks. **RMSNorm** has become the standard in models like Llama for its computational efficiency, as it omits the mean-centering step of LayerNorm.

## V. Conclusion: Beyond the Frontier

Machine learning is moving from "Training" to "Inference-time Compute" (e.g., OpenAI o1). The next scaling frontier is not just more data or parameters, but **Test-Time Search**—allowing models to "think" longer by exploring the reasoning manifold before outputting a token.

The practitioner's task is to balance the rigorous calculus of the backward pass with the empirical physics of the scaling laws to build systems that are both mathematically sound and compute-efficient.
