---
title: Geometric Brownian Motion
type: article
cluster: computational-finance
status: published
date: '2026-05-10'
summary: The mathematical foundation of modern financial modeling, describing continuous-time stochastic processes with constant drift and volatility.
tags:
- mathematics
- stochastic-calculus
- quantitative-finance
- monte-carlo
- modeling
relations:
- {type: related_to, target_id: 01KQ0P44SRPADVP4CV9AZFQ47T} # Monte Carlo Retirement
- {type: related_to, target_id: BlackScholesModel}
canonical_id: 01KS8J2Z2A938D4EYVWFA9F36M
---

# Geometric Brownian Motion (GBM)

Geometric Brownian Motion (GBM) is a continuous-time stochastic process in which the logarithm of the randomly varying quantity follows a Brownian motion (also called a Wiener process). In software engineering and quantitative finance, GBM is the standard mathematical model used to simulate asset prices over time, notably serving as the foundation for the [Black-Scholes Model](BlackScholesModel) and [Monte Carlo Retirement Planning](MonteCarloRetirementPlanning) simulations.

## 1. The Stochastic Differential Equation (SDE)

A stochastic process $S_t$(representing the price of an asset at time$t$) is said to follow a Geometric Brownian Motion if it satisfies the following stochastic differential equation:

$$
dS_t = \mu S_t dt + \sigma S_t dW_t
$$

Where:*$S_t$: The asset price at time$t$.
*$\mu$: The percentage **drift** (expected return). A constant deterministic trend.
*$\sigma$: The percentage **volatility** (standard deviation). A constant representing market noise.
*$W_t$: A standard Wiener process (Brownian motion), representing the random shock.$dW_t \sim \mathcal{N}(0, dt)$.

### Why "Geometric"?
Unlike standard Brownian motion, which can result in negative values, GBM is strictly positive ($S_t > 0$for all$t$if$S_0 > 0$). The term$\mu S_t$and$\sigma S_t$mean that the magnitude of the drift and volatility scale proportionally with the current price, which accurately reflects percentage-based returns in financial markets.

## 2. Solving with Itô's Lemma

To simulate GBM in software, we cannot directly integrate the SDE because the$dW_t$term is nowhere differentiable. We must apply **Itô's Lemma**, a foundational theorem in stochastic calculus.

Applying Itô's Lemma to the natural logarithm of the price$Y_t = \ln(S_t)$yields:

$$
d(\ln S_t) = \left( \mu - \frac{\sigma^2}{2} \right) dt + \sigma dW_t
$$

This equation is highly significant for software engineers building Monte Carlo engines: the right side of the equation consists entirely of constants and a standard normal distribution. This allows us to integrate directly to find the exact analytical solution for$S_t$:

$$
S_t = S_0 \exp \left( \left( \mu - \frac{\sigma^2}{2} \right) t + \sigma W_t \right)
$$

### The "Volatility Drag"The term$\left( \mu - \frac{\sigma^2}{2} \right)$reveals a critical phenomenon known as **Volatility Drag**. The geometric (compound) return of an asset is always lower than its arithmetic average return by exactly half the variance. This explains why an asset that gains 50% and then loses 50% does not break even (it loses 25%).
## 3. Implementation in Software (Monte Carlo)

When building a Monte Carlo engine, continuous time is discretized into finite steps ($\Delta t$). The discrete-time approximation (Euler-Maruyama method) derived from the exact solution is:

$$
S_{t+\Delta t} = S_t \exp \left( \left( \mu - \frac{\sigma^2}{2} \right) \Delta t + \sigma \sqrt{\Delta t} Z \right)
$$

Where$Z$is a random draw from the Standard Normal Distribution$\mathcal{N}(0,1)$.
### Python / NumPy Vectorized Implementation
In 2026 high-performance systems, this is typically implemented using vectorized operations on GPUs or highly optimized CPU libraries:

```python
import numpy as np

def simulate_gbm(S0, mu, sigma, T, dt, num_simulations):
    # Number of time steps
    N = int(T / dt)
    
    # Generate standard normal random variables for the entire matrix
    Z = np.random.standard_normal((N, num_simulations))
    
    # Calculate the deterministic drift and the stochastic shock
    drift = (mu - 0.5 * sigma**2) * dt
    shock = sigma * np.sqrt(dt) * Z
    
    # Pre-allocate price array
    S = np.zeros((N + 1, num_simulations))
    S[0] = S0
    
    # Vectorized cumulative sum of the log returns, then exponentiated
    # S_t = S_0 * exp(cumsum(drift + shock))
    log_returns = drift + shock
    S[1:] = S0 * np.exp(np.cumsum(log_returns, axis=0))
    
    return S
```

## 4. Limitations and Extensions

While GBM is computationally efficient, its assumption of constant volatility and normally distributed log-returns fails to capture real-world market dynamics (fat tails and volatility clustering). 
Modern computational finance systems often extend GBM using:
*   **Jump-Diffusion Models (Merton):** Adds sudden, discontinuous jumps to the price path.
*   **Stochastic Volatility (Heston Model):** Models$\sigma$ itself as a separate, mean-reverting stochastic process.

## See Also
*   [The Black-Scholes Model](BlackScholesModel) — Pricing derivatives using GBM.
*   [Monte Carlo Retirement Planning](MonteCarloRetirementPlanning) — Using GBM to stress-test personal finance.
