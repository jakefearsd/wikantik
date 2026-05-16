---
title: 'Non-Standard Analysis: Infinitesimals in Finance'
canonical_id: 01KRQE4ZY7R0AMKK2P9XFRSP23
cluster: mathematics
relations:
- type: component_of
  target_id: 01KQ2P44XMGA8E1E7GAT4AYV43
- type: extension_of
  target_id: 01KRTB67YHJ96D0PBJ1NEJDY23
type: article
tags:
- non-standard-analysis
- infinitesimals
- hyperreals
- hft
- quantitative-finance
- sde
summary: Advanced coverage of Non-Standard Analysis (NSA) and hyperreal numbers. Details
  the application of infinitesimals to bridge discrete HFT events with continuous-time
  finance models (Stochastic Differential Equations).
status: active
date: '2026-05-15'
---

# Non-Standard Analysis: Infinitesimals in Finance

Non-Standard Analysis (NSA), introduced by Abraham Robinson (1960), provides a rigorous foundation for **Infinitesimals**—numbers that are smaller than any positive real number but greater than zero. In 2026, NSA is the primary bridge between the discrete "ticks" of High-Frequency Trading (HFT) and the continuous smooth curves of Stochastic Calculus.

## 1. The Hyperreal Number System ($\mathbb{R}^*$)
NSA extends the real numbers $\mathbb{R}$ to the **Hyperreals** $\mathbb{R}^*$, which include:
*   **Standard Reals**: $1, \pi, \sqrt{2}$.
*   **Infinitesimals ($\epsilon$)**: Numbers such that $| \epsilon | < 1/n$ for all $n \in \mathbb{N}$.
*   **Infinite Numbers ($\omega$)**: Numbers such that $\omega > n$ for all $n \in \mathbb{N}$.

## 2. Bridging the Discrete-Continuous Gap
In Finance, the price process is actually a sequence of discrete trades. However, the math of **Black-Scholes** uses continuous-time Brownian Motion ($dW_t$). 

### A. The Hyperreal Random Walk
Using NSA, we model Brownian motion as a **Hyperfinite Random Walk** with infinitesimal steps of size $\Delta t = \epsilon$. 
*   **The Benefit**: Unlike standard measure theory, which is highly abstract, NSA allows quants to treat SDEs as simple algebraic equations over the hyperreals. It makes the **Itô Integral** as intuitive as a standard summation.

## 3. High-Frequency Trading (HFT) Microstructure
Modern HFT systems operate at sub-millisecond scales where market "liquidity" looks non-continuous.
*   **Micro-Burst Modeling**: Hyperreal time-scales allow researchers to model "bursts" of volatility that appear instantaneous in $\mathbb{R}$ but have a detailed, sequential structure in $\mathbb{R}^*$. 
*   **Infill Asymptotics**: Deriving the limit properties of estimators as the frequency of observation goes to infinity (infinitesimal intervals).

## 4. Non-Standard Errors (NSE)
A 2025 research focus is the **Non-Standard Error**—the uncertainty introduced by the variation in researchers' modeling choices. Even with identical data, differences in infinitesimal assumptions lead to divergent volatility estimates in HFT environments.

---
**External Deep Dive:**
- [Non-standard Analysis (Wikipedia)](https://en.wikipedia.org/wiki/Non-standard_analysis) — Comprehensive overview of Robinson's framework.
- [Hyperreal Number (Wikipedia)](https://en.wikipedia.org/wiki/Hyperreal_number) — Formal construction of the $\mathbb{R}^*$ field.
- [Internal Set (Wikipedia)](https://en.wikipedia.org/wiki/Internal_set) — The critical set-theoretic distinction in NSA.

**See Also:**
- [Numerical Methods](NumericalMethods) — Finite-precision arithmetic.
- [Real Analysis](RealAnalysis) — The standard real number system.
- [Quantitative Finance Research Hub](QuantitativeFinanceResearchHub)
