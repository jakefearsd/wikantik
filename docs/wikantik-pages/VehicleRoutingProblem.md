---
cluster: operations-research
canonical_id: 01KQ0P44YF23KBS4503P4SK81P
title: Vehicle Routing Problem
type: article
tags:
- optimization
- logistics
- heuristics
- vrp
status: active
date: 2025-05-15
summary: A technical deep dive into VRP solution methodologies, focusing on Clarke-Wright Savings and Ant Colony Optimization.
auto-generated: false
---

# Vehicle Routing Problem (VRP): Optimization Heuristics

The Vehicle Routing Problem (VRP) is a combinatorial optimization challenge aimed at finding the most efficient set of routes for a fleet of vehicles to deliver goods to a specific set of customers. As an NP-hard problem, large-scale VRPs require sophisticated heuristics to find near-optimal solutions in reasonable time.

## 1. Clarke-Wright Savings Heuristic

The Clarke-Wright Savings algorithm is the foundational "greedy" heuristic for CVRP (Capacitated VRP). It operates by calculating the "savings" achieved by merging two independent routes.

### 1.1 The Savings Formula
Initially, assume every customer $i$ and $j$ is served by a dedicated return trip from the depot (0). The distance is $2d_{0i} + 2d_{0j}$.
If we merge $i$ and $j$ into a single route ($0 \to i \to j \to 0$), the new distance is $d_{0i} + d_{ij} + d_{j0}$.
The **Savings ($S_{ij}$)** is:
$$S_{ij} = d_{i0} + d_{0j} - d_{ij}$$

### 1.2 Algorithm Execution
1.  Compute the savings $S_{ij}$ for all pairs of customers.
2.  Sort pairs in descending order of savings.
3.  Iteratively merge routes starting from the top of the list, provided:
    *   The customers are not already in the same route.
    *   Neither customer is "internal" to an existing route (must be adjacent to the depot).
    *   Vehicle capacity ($Q$) is not exceeded.

## 2. Ant Colony Optimization (ACO)

ACO is a metaheuristic inspired by the pheromone-trailing behavior of ants. It is highly effective for dynamic and complex VRP variants.

### 2.1 Pheromone and Heuristic Information
An "ant" (agent) constructs a route by moving between nodes. The probability $P_{ij}$ of moving from $i$ to $j$ is:
$$P_{ij} = \frac{[\tau_{ij}]^\alpha \cdot [\eta_{ij}]^\beta}{\sum ([\tau_{ik}]^\alpha \cdot [\eta_{ik}]^\beta)}$$
Where:
*   $\tau_{ij}$ = Pheromone density on edge $(i, j)$.
*   $\eta_{ij}$ = Heuristic desirability (typically $1/d_{ij}$).
*   $\alpha, \beta$ = Parameters controlling the influence of pheromones vs. distance.

### 2.2 Global Update and Evaporation
After all ants complete their tours, pheromones are updated. Shorter tours receive higher pheromone reinforcement, while a percentage of existing pheromone "evaporates" to prevent premature convergence on local optima.

## 3. Comparison of Methodologies

| Method | Type | Complexity | Best For |
| :--- | :--- | :--- | :--- |
| **Exact (Branch & Cut)** | Deterministic | $O(exp)$ | Small sets (<100 nodes) |
| **Clarke-Wright** | Constructive | $O(N^2 \log N)$ | Rapid baseline solutions |
| **Tabu Search** | Local Search | $O(N^2)$ | Refinement of existing routes |
| **Ant Colony (ACO)**| Population-based| High | Complex/Dynamic constraints |

## 4. Constraint Modeling

Advanced VRP models must incorporate:
*   **Time Windows (VRPTW):** $e_i \le Arrival\_Time_i \le l_i$.
*   **Split Deliveries:** A single customer's demand is met by multiple vehicles.
*   **Backhauls:** Integrating pickups with deliveries to minimize empty miles.

Solving the VRP requires a balance between the speed of constructive heuristics like Clarke-Wright and the exploratory power of metaheuristics like ACO.
