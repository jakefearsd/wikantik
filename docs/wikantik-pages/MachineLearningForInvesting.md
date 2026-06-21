---
status: active
date: '2026-05-15'
summary: Causal Factor Investing, HRP 2025 proofs, Bayesian Neural Networks for uncertainty-aware
  sizing, and GNNs for supply chain risk in investment management.
tags:
- machine-learning
- investing
- quantitative-finance
- causal-inference
- hrp
- bayesian-neural-networks
- gnn
- portfolio-optimization
type: article
relations:
- type: component_of
  target_id: 01KQ0P44NVFH6WVSSJ39P1PVPB
- type: component_of
  target_id: 01KQEKGDDVHTHY07CQ3YKSQ5PA
- type: extension_of
  target_id: 01KQ3P44XMGA8E1E7GAT4AYV43
cluster: machine-learning
canonical_id: 01KRTB67YHJ96D0PBJ1NEJDY23
title: 'Machine Learning for Investing: A Survey'
---

# Machine Learning for Investing: A Survey

By 2026, the application of Machine Learning (ML) to investing has shifted from "phenomenological" pattern matching to **Scientific Causal Discovery**. The focus is no longer just "What will the price be?" but "What is the causal mechanism driving the return?"

## 1. Causal Factor Investing (The 2025 Paradigm)

The "Factor Zoo"—the explosion of thousands of reported market factors—has been largely debunked as a **"Factor Mirage"** due to p-hacking and selection bias.

### A. The 7-Step Causal Protocol
Following the research of **Marco Lopez de Prado (2025)**, leading quant shops have adopted a Causal ML workflow:
1.  **Causal Graphing (DAGs)**: Mapping variable interdependencies using Judea Pearl's framework.
2.  **Do-Calculus**: Using graph surgery to select control variables and avoid "collider bias."
3.  **Double Machine Learning (DML)**: Using ML to estimate treatment effects while controlling for high-dimensional confounders.

## 2. Advanced Portfolio Optimization: HRP 2025

The most significant theoretical breakthrough in 2025 was the formal validation of **Hierarchical Risk Parity (HRP)**.

### A. Overcoming Markowitz's Instability
In *Risk Magazine (Jan 2025)*, Lopez de Prado and colleagues provided the first **analytical proof** that HRP is significantly less noisy than classical Mean-Variance Optimization (MVO).
*   **The update**: We now have derived analytical values for the "noise" of allocation weights coming from estimated covariance matrices.
*   **Finding**: HRP's tree-based clustering approach effectively regularizes the covariance matrix, preventing the "Optimizer's Curse" (where small errors in expected return lead to massive, unstable bets).

## 3. Position Sizing: Bayesian Neural Networks (BNNs)

Traditional ML provides point estimates (e.g., "The return will be 5%"). Advanced 2026 models use **Bayesian Neural Networks** to provide a probability distribution:

$$
P(W | D) = \frac{P(D | W) P(W)}{P(D)}
$$

Where $W$ are the weights and $D$ is the data.
*   **Uncertainty-Aware Sizing**: Quants use the **Epistemic Uncertainty** (model uncertainty) to size positions. If the model predicts a high return but has high variance in its weights, the position is automatically downsized.

## 4. Supply Chain Risk: Graph Neural Networks (GNNs)

2025 research has successfully applied **GNNs** to model systemic financial risk.
*   **Adjacency Matrices**: Companies are nodes; edges represent supply chain dependencies, co-ownership, or board intersections.
*   **Risk Propagation**: If a tier-2 supplier in Taiwan faces a "Hormuz Shock" precursor, the GNN propagates the risk through the graph, identifying vulnerable tech firms in the US before their quarterly earnings are impacted.

## 5. Execution and Tactical Allocation (RL)

Reinforcement Learning (RL) has moved beyond toy models to dominate **Execution and Tactical Allocation**.
- **SAPPO (Sentiment-Augmented PPO)**: *Kirtac & Germano (July 2025)* demonstrated that agents using LLM-derived sentiment in their advantage function achieve Sharpe Ratios of **1.90** vs. 1.55 for traditional DRL.
- **Hierarchical RL**: A "Master" agent decides the asset allocation (Medium-Term), while "Sub-agents" execute the trades at the micro-level to minimize market impact.

## 6. Case Study: The 2026 Iran War Shock

During the February 2026 kinetic escalation, traditional Markowitz-style optimizers failed due to the sudden "Correlation Breakdown" where all assets dropped in tandem.

**The ML Alternative**: Portfolios using **Hierarchical Risk Parity** and **Minimum Sentiment Connectedness** (MSC) maintained their risk diversification. By identifying "Systemic Chokepoint" sentiment 72 hours before the Hormuz escalation, these models automatically shifted from high-beta tech to defensive commodities and energy, mitigating the -15% index drop.

---
**See Also:**
- [Sentiment Analysis for Financial Markets](SentimentAnalysisForFinance)
- [TimeSeriesForecasting](TimeSeriesForecasting)
- [Geopolitical Risk and Investing](GeopoliticalRiskAndInvesting)
- [Hierarchical Risk Parity: Theoretical Evidence (Antonov et al., 2025)](https://quantresearch.org/Risk_Jan2025.pdf)
