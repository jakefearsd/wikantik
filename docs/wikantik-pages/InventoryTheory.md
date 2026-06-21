---
title: Inventory Theory
cluster: operations-research
type: article
hubs:
- DemandPlanningAndSop Hub
summary: EOQ and Newsvendor models, (s,S) multi-period stochastic policies, Clark-Scarf
  multi-echelon decomposition, and lead-time variance effects on safety stock.
canonical_id: 01KQ0P44R70Z2KWGKRTMCQFYGG
tags:
- operations-research
- optimization
- supply-chain
- stochastic-modeling
---

# Inventory Theory: Stochastic Optimization and Supply Chain Dynamics

Inventory theory is the mathematical discipline dedicated to determining the optimal timing and quantity of stock replenishment to minimize total relevant costs. For researchers in [Operations Research Hub](OperationsResearchHub), inventory theory provides the framework for placing capital—physical goods—in a system where demand is inherently noisy and costs are rarely linear.

This treatise explores the foundational models (EOQ and Newsvendor) and moves into advanced [Stochastic Models in OR](StochasticModelsInOR) and multi-echelon optimization.

---

## I. Deterministic Optimization: The EOQ Model

The **Economic Order Quantity (EOQ)** model provides the benchmark for balancing ordering frequency against holding costs under the assumption of constant, known demand.

### 1.1 The Classical EOQ Formula
The objective is to minimize the Total Annual Cost $TAC(Q)$:

$$
TAC(Q) = \frac{D}{Q} K + \frac{Q}{2} h
$$

Where $D$ is annual demand, $K$ is ordering cost, and $h$ is holding cost per unit per year. The optimal order quantity $Q^*$ is:

$$
Q^* = \sqrt{\frac{2DK}{h}}
$$

### 1.2 Extensions: Production and Discounts
*   **Economic Production Quantity (EPQ):** Adjusts for the rate of production $P$ during replenishment.
*   **Quantity Discounts:** Incorporates non-linear unit costs, requiring a piecewise optimization of the cost function.

---

## II. Stochastic Optimization: The Newsvendor Model

For single-period or seasonal goods, we use the **Newsvendor Model**, which balances the costs of overstocking and understocking.

### 2.1 The Critical Ratio
The optimal stocking level $Q^*$ is determined by the **Critical Ratio (CR)**:

$$
CR = \frac{C_u}{C_u + C_o}
$$

Where $C_u$ is the cost of underage (lost profit) and $C_o$ is the cost of overage (salvage loss). The goal is to find $Q^*$ such that $P(D \le Q^*) = CR$.

### 2.2 Demand Distribution
This model relies heavily on [Probability Theory](ProbabilityTheory) to model demand $D$. For $D \sim N(\mu, \sigma^2)$, the optimal level is $Q^* = \mu + \sigma Z_{CR}$.

---

## III. Multi-Period Stochastic Models: (s, S) Policies

In a continuous review setting with lead time $L$ and demand variability, we employ **(s, S) Policies**.
*   **s (Reorder Point):** Triggers an order when inventory falls below this level. It must cover expected demand during lead time plus a safety stock buffer.
*   **S (Order-Up-To Level):** The target inventory level after replenishment.

Finding the optimal $(s, S)$ is a [Dynamic Programming Patterns](DynamicProgrammingPatterns) problem, where the state is the current inventory level and the transition is the stochastic demand realization.

---

## IV. Advanced Research: Multi-Echelon and Lead Time Variance

### 4.1 Multi-Echelon Optimization
In systems with multiple tiers (e.g., Factory $\to$ DC $\to$ Retailer), we use the **Clark-Scarf Model**. It decomposes the system into nested subproblems using the concept of "installation stock" vs. "echelon stock," ensuring global optimality across the chain. See [Supply Chain and Logistics Optimization](SupplyChainAndLogisticsOptimization).

### 4.2 Lead Time Variability
The most common oversight is assuming fixed lead time. If lead time $L$ is stochastic, the total variance of demand during lead time is:

$$
\sigma_{total}^2 = \bar{L} \sigma_D^2 + \bar{D}^2 \sigma_L^2
$$

Ignoring the $\sigma_L^2$ term (the variance of the lead time itself) leads to significant service level failures in real-world systems.

## Conclusion

Inventory theory transforms the static "How much should I buy?" into a dynamic, risk-managed optimization problem. By bridging deterministic baselines with stochastic realities, researchers can design resilient supply chains that survive the inherent uncertainty of global trade.

---
**See Also:**
- [Operations Research Hub](OperationsResearchHub) — Contextual overview of OR techniques.
- [Mathematics Hub](MathematicsHub) — For the calculus and algebra underlying optimization.
- [Probability Theory](ProbabilityTheory) — For modeling demand and lead time uncertainty.
- [Supply Chain and Logistics Optimization](SupplyChainAndLogisticsOptimization) — System-wide logistics strategy.
