---
title: Markov Chain Fundamentals
type: article
cluster: mathematics
status: active
date: '2026-04-25'
tags:
- markov-chain
- probability
- stochastic-process
- mcmc
summary: Markov chains in plain language — discrete states, transition matrices,
  stationary distributions — and the practical applications (PageRank, MCMC,
  language modelling, queueing) where they matter.
related:
- ProbabilityTheory
- BayesianReasoning
- LinearAlgebra
hubs:
- Mathematics Hub
---
# Markov Chain Fundamentals

A Markov chain is a stochastic process where the next state depends only on the current state — not on how you got there. Despite the simple-sounding rule, the model captures a surprising amount of useful behaviour: page ranking, MCMC sampling, queueing systems, language modelling, weather forecasting, genetic sequence analysis.

This page is the working set: states, transitions, the stationary distribution, and where it shows up in production.

## The model

A Markov chain has:

- A set of states `S = {s_1, s_2, ..., s_n}` (finite, for our purposes).
- A transition matrix `P` where `P[i][j]` is the probability of going from state `i` to state `j`.

Constraint: each row sums to 1 (you have to go somewhere).

Example: weather as a 3-state chain.

```
       Sun  Cloud Rain
Sun   [0.7  0.2   0.1]
Cloud [0.3  0.4   0.3]
Rain  [0.2  0.3   0.5]
```

If today is sunny, tomorrow has 70% chance of sunny, 20% cloudy, 10% rainy. The Markov property says: doesn't matter what yesterday was; only today matters.

## The Markov property

`P(X_{n+1} | X_n, X_{n-1}, ..., X_0) = P(X_{n+1} | X_n)`

Memoryless. The future depends only on the present.

This is a strong assumption. Real systems often have memory — yesterday's weather, last week's stock price. Models are extended to capture more (HMMs, n-gram models with longer context); the Markov chain is the building block.

## Predicting future states

Multiplying by the transition matrix advances one step:

- Initial distribution `π_0` = probability of being in each state.
- After one step: `π_1 = π_0 · P`.
- After `k` steps: `π_k = π_0 · P^k`.

Example: today is definitely sunny → `π_0 = [1, 0, 0]`. After 5 days: `[1, 0, 0] · P^5`.

For large `k`, this converges (under conditions) to the **stationary distribution**.

## The stationary distribution

The unique distribution `π` satisfying `π · P = π`. The fraction of time spent in each state in the long run.

For our weather chain, the stationary distribution is roughly `[0.5, 0.27, 0.23]` — half the days sunny, etc.

Computing it:

- **Eigenvector method.** `π` is the left-eigenvector of `P` with eigenvalue 1. Solve `π · P = π`, `π_i ≥ 0`, `sum(π_i) = 1`.
- **Power iteration.** Start with any distribution; multiply by `P` repeatedly; converges to `π` (under ergodicity conditions).

The stationary distribution is what makes Markov chains useful in many applications. PageRank, MCMC, queueing analysis — all about computing or sampling from the stationary distribution.

## Conditions for convergence

The stationary distribution exists and is unique if the chain is:

- **Irreducible** — every state reachable from every other.
- **Aperiodic** — no fixed cycle period.
- **Positive recurrent** — expected return time to each state is finite.

Together: ergodic. For finite-state chains, irreducibility and aperiodicity are usually enough.

In practice, you check these conditions for your specific application. Most well-formed real-world chains are ergodic.

## Where Markov chains show up

### PageRank

Google's original ranking algorithm. The web is a graph; transitions are weighted by hyperlinks. The stationary distribution gives each page a "rank."

```
P[i][j] = 1/out_degree(i) if page i links to page j; else 0
PageRank[j] = stationary distribution of P
```

With damping (random teleport) to ensure ergodicity.

PageRank is computed by power iteration over the transition matrix. Converges in tens of iterations. The web graph has ~10⁹ nodes; power iteration is the only tractable approach.

### MCMC (Markov Chain Monte Carlo)

The dominant approach to sampling from complex probability distributions in Bayesian inference.

The idea: design a Markov chain whose stationary distribution is the target distribution `p(x)`. Run the chain; samples from the chain (after burn-in) are samples from `p(x)`.

Algorithms (Metropolis-Hastings, Gibbs, Hamiltonian Monte Carlo) construct chains with the right stationary distribution. See [BayesianReasoning].

### Hidden Markov Models (HMM)

Markov chain where you observe noisy outputs, not the states directly. The classic application: speech recognition (states are phonemes; observations are audio features).

Algorithms:

- **Forward-backward** — compute marginal probabilities of states given observations.
- **Viterbi** — find most likely sequence of states.
- **Baum-Welch** — learn HMM parameters from data.

HMMs were dominant in speech and bioinformatics for decades; in 2026 they're often replaced by neural sequence models (transformers, RNNs) but still appear in specific applications.

### Language modelling (n-gram)

Pre-deep-learning approach: Markov chains over words. State = last `n-1` words; transition probability to next word from corpus statistics.

Trigram model:

```
P(w_3 | w_1, w_2) = count(w_1, w_2, w_3) / count(w_1, w_2)
```

Replaced by neural language models since 2018 but still appears in some applications (autocomplete, statistical machine translation legacy).

### Queueing systems

Customer arrivals + service form Markov chains. The state is queue length; transitions are arrivals (+1) and completions (-1).

Stationary distribution gives expected queue length, wait times, utilisation. The basis of M/M/1, M/M/c queue formulas in operations research.

For latency analysis of services, queueing theory + Markov-chain analysis still pays.

### Reinforcement learning

Markov Decision Processes (MDPs) extend Markov chains with actions and rewards. The agent picks an action in each state; the next state is a stochastic function of state and action.

Value iteration, policy iteration, Q-learning all operate on the underlying MDP structure.

See [ReinforcementLearningFundamentals].

### Finance

Stock-price models often use Markov-chain or random-walk variants. Geometric Brownian motion (continuous-time analogue) underlies Black-Scholes options pricing.

### Random walks on graphs

Many graph algorithms can be analysed as Markov chains: random walks for node similarity, spectral clustering, recommendation algorithms.

## Continuous-time Markov chains

Same idea, time is continuous. Transitions happen at random times with exponential waiting times. The system is described by a rate matrix `Q` instead of a transition matrix.

Used in:
- Continuous-time queueing.
- Chemical kinetics.
- Reliability analysis (failure / repair rates).
- Continuous-time HMMs.

Math is more involved; the engineering uses are similar.

## What Markov chains can't do

- **Long-range dependencies.** The chain's "memory" is just the current state. For sequences with long-range structure, n-gram models with large `n` blow up combinatorially; neural alternatives win.
- **Non-stationary processes.** If transition probabilities change over time, you're not in a basic Markov chain anymore.
- **Hidden structure**. HMMs handle this for some cases; for richer structure, deeper models needed.

## A pragmatic application: PageRank from scratch

```python
import numpy as np

def pagerank(adjacency, damping=0.85, max_iter=100, tol=1e-6):
    n = len(adjacency)
    # Build transition matrix (column-stochastic for left-multiply convention)
    out_degrees = adjacency.sum(axis=1)
    M = np.zeros((n, n))
    for i in range(n):
        if out_degrees[i] > 0:
            M[:, i] = adjacency[i] / out_degrees[i]
    
    # Damped transition matrix
    teleport = np.ones(n) / n
    
    pagerank = np.ones(n) / n
    for _ in range(max_iter):
        new_pagerank = damping * (M @ pagerank) + (1 - damping) * teleport
        if np.linalg.norm(new_pagerank - pagerank, 1) < tol:
            break
        pagerank = new_pagerank
    return pagerank
```

20 lines; functional PageRank. The same shape works for many applications.

## Tools

- **NumPy / SciPy** for transition matrices and eigenvector computation.
- **NetworkX** has random-walk and PageRank built in.
- **PyMC, NumPyro, Stan** for MCMC with sophisticated chains.
- **`hmmlearn`** for hidden Markov models.

## Further reading

- [ProbabilityTheory] — foundations
- [BayesianReasoning] — MCMC in depth
- [LinearAlgebra] — eigenvectors and matrix iteration
