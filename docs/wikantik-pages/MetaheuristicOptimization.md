---
cluster: operations-research
canonical_id: 01KQ0P44SFQM7F9TZXPEJTWMYG
title: "Metaheuristic Optimization: The Math of SA and GA"
type: article
tags:
- optimization
- simulated-annealing
- genetic-algorithms
- acceptance-probability
- math
summary: A technical deep dive into the mathematical mechanisms of Simulated Annealing and Genetic Algorithms, focusing on the Metropolis criterion and evolutionary selection pressures.
auto-generated: false
date: 2025-01-24
---
# Metaheuristic Optimization: The Math of SA and GA

In [Operations Research](OperationsResearch), we frequently encounter "NP-hard" landscapes where global optima are obscured by a thicket of local minima. When exact methods (like Branch and Bound) become computationally intractable, we turn to **Metaheuristics**. This article dissects the mathematical machinery of the two most prominent paradigms: Simulated Annealing (SA) and Genetic Algorithms (GA).

---

## 1. Simulated Annealing: The Thermodynamics of Search

Simulated Annealing maps the physical process of cooling a metal to the search for a global minimum of a cost function $f(x)$.

### 1.1 The Metropolis Criterion
The heart of SA is the **Acceptance Probability**, which allows the algorithm to escape local traps by occasionally accepting "uphill" moves (moves that increase the cost). 

Given a current state$x$and a candidate neighbor$x'$, let$\Delta E = f(x') - f(x)$. The probability of accepting$x'$is:$$P(\text{accept}) = 
\begin{cases} 
1 & \text{if } \Delta E \le 0 \\
\exp\left(-\frac{\Delta E}{T}\right) & \text{if } \Delta E > 0 
\end{cases}$$Where$T$is the "temperature" parameter. 

**The Math of Exploration:** At high temperatures ($T \to \infty$),$P \to 1$for all$\Delta E$, making the search essentially a random walk. As$T \to 0$,$P \to 0$for all$\Delta E > 0$, and the algorithm behaves like a greedy Hill Climber.

### 1.2 Cooling Schedules: Logarithmic vs. Geometric
The effectiveness of SA depends on how$T$is reduced over time$k$.
*   **Geometric Cooling:**$T_{k+1} = \alpha T_k$, where$0.8 \le \alpha < 1$. This is the standard in engineering for its speed.
*   **Logarithmic Cooling:**$T_k = \frac{C}{\log(k+d)}$. This schedule is mathematically guaranteed to find the global optimum given infinite time, but it is too slow for most practical applications.

---

## 2. Genetic Algorithms: The Algebra of Evolution

Genetic Algorithms (GA) are population-based searches that use operators inspired by molecular biology: Selection, Crossover, and Mutation.

### 2.1 Selection Pressure: Tournament vs. Roulette
Selection is the mechanism that enforces "survival of the fittest."
*   **Roulette Wheel Selection:** The probability of selecting individual$i$is$P_i = \frac{f_i}{\sum f_j}$. This is prone to "stagnation" if one individual is significantly fitter than the rest.
*   **Tournament Selection:** Pick$k$individuals at random and select the best among them. This is more robust as it depends only on the *relative* rank, not the absolute magnitude of fitness.

### 2.2 The Schemata Theorem
The mathematical justification for GAs is John Holland’s **Schemata Theorem**. It states that "building blocks" (short, high-fitness strings called schemata) increase their presence in the population exponentially over generations.
Let$m(H, t)$be the number of strings matching schema$H$at generation$t$.$$m(H, t+1) \ge m(H, t) \frac{f(H)}{\bar{f}} [1 - \text{Loss}_{crossover} - \text{Loss}_{mutation}]$$Where$f(H)$is the average fitness of schema$H$and$\bar{f}$is the average fitness of the whole population. This confirms that GA is not a random search but a structured information-processing engine.

---

## 3. Comparison and Convergence

| Feature | Simulated Annealing (SA) | Genetic Algorithm (GA) |
| :--- | :--- | :--- |
| **Search Mode** | Single trajectory (Trajectory-based) | Population-based (Parallel) |
| **Global Guarantee** | Yes (given infinite time/log-cooling) | No (prone to premature convergence) |
| **Key Math** | Boltzmann/Metropolis Distribution | Schemata Theorem / Parity Laws |
| **Best For** | Continuous or very large discrete spaces | Combinatorial problems (e.g. TSP) |

### 3.1 The Exploitation vs. Exploration Trade-off
The performance of any metaheuristic is a balance between **Exploration** (visiting new regions) and **Exploitation** (refining the current best). 
*   In SA, this is controlled by the **Temperature**$T$.
*   In GA, this is controlled by the **Mutation Rate**$\mu$and **Crossover Rate**$P_c$.

Researchers should prioritize SA when the neighborhood structure is well-defined and the "energy" landscape is relatively smooth. Choose GA when the problem can be decomposed into independent sub-components that can be recombined through crossover.
