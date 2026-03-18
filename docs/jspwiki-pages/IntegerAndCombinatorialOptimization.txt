---
type: article
cluster: operations-research
tags:
  - operations-research
  - integer-programming
  - combinatorial-optimization
  - branch-and-bound
  - tsp
date: 2026-03-17
related:
  - OperationsResearchHub
  - LinearProgrammingFoundations
  - ProductionSchedulingAndOR
  - SupplyChainAndLogisticsOptimization
status: active
summary: Integer programming, branch and bound, cutting planes, network flows, and the combinatorial optimization problems that define practical OR
---
# Integer and Combinatorial Optimization

Many real-world optimization problems cannot be solved with continuous variables alone. A logistics company does not send 3.7 trucks; a hospital does not schedule 2.4 nurses. When variables must be whole numbers — or binary (0/1) — we enter the domain of **integer programming (IP)** and **combinatorial optimization**.

This family of problems is far harder than linear programming. Most combinatorial optimization problems of practical interest are NP-hard, meaning no known polynomial-time algorithm solves all instances. Yet production-quality solvers routinely solve problems with millions of variables to provable optimality or near-optimality. Understanding why — and when solvers struggle — requires grasping the core algorithmic ideas.

## Integer Programming

An integer program is an LP with the additional requirement that some or all decision variables be integers:

```
Maximize:    cᵀx
Subject to:  Ax ≤ b
             x ≥ 0
             xᵢ ∈ ℤ for all i (or for a subset of i)
```

When all integer variables are restricted to {0, 1}, the problem is called a **binary integer program (BIP)** or **0-1 program**. Many planning and scheduling decisions are naturally binary: open or close a facility, include or exclude an item, assign or not assign a worker to a shift.

### Why Not Round the LP Solution?

A natural first thought is to solve the LP relaxation (ignoring integrality) and round the result. This almost never works reliably:

- Rounding can violate constraints
- Rounded solutions can be far from optimal — in some problems, the gap is unbounded
- There is no guarantee of which direction to round

For problems where the LP relaxation bound is tight (like network flow problems), rounding works because the LP automatically produces integer solutions. But for general IPs, the LP-IP gap can be enormous.

## Branch and Bound

The dominant algorithmic framework for solving IPs exactly is **branch and bound**, introduced by Land and Doig (1960). The idea is to divide the problem into subproblems (branch), compute bounds on each subproblem to prune unpromising branches (bound), and systematically explore the remaining tree.

### The Algorithm

1. **Solve the LP relaxation** of the original problem. If the LP solution is integral, done. If infeasible, done (original IP infeasible). Otherwise, let zLP be the LP optimal value and xLP the solution.

2. **Branch:** Select a fractional variable xᵢ = fᵢ where fᵢ is not an integer. Create two subproblems:
   - Left branch: add constraint xᵢ ≤ floor(fᵢ)
   - Right branch: add constraint xᵢ ≥ ceil(fᵢ)

3. **Bound:** Solve each subproblem's LP relaxation. The LP optimal value is an upper bound (for maximization) on any integer solution in that subproblem. If a subproblem's bound is worse than the best integer solution found so far, prune it.

4. **Fathom:** A subproblem is fathomed (pruned) if:
   - Its LP relaxation is infeasible
   - Its LP optimal is ≤ best known integer solution
   - Its LP optimal is integral (update best known solution)

5. **Select and repeat** with an unfathomed subproblem until none remain.

The key insight is that we never need to enumerate all possible integer solutions explicitly — bounding prunes large portions of the search tree. Well-implemented B&B solves IPs with millions of variables routinely.

### Branching Strategies

The choice of which variable to branch on affects efficiency dramatically:

- **Most infeasible:** Branch on the variable whose value is closest to 0.5
- **Strong branching:** For each candidate variable, tentatively solve both branches and choose the variable giving the best bound improvement
- **Pseudocost branching:** Estimate branch quality based on historical cost per unit of fractionality

Modern solvers use sophisticated adaptive strategies, learned over years of computational experiments.

## Cutting Plane Methods

Rather than branching, cutting plane methods tighten the LP relaxation by adding **valid inequalities** — constraints that all integer-feasible solutions satisfy but the current LP relaxation violates. Cutting planes push the LP feasible region toward integer solutions without branching.

### Gomory Cuts

Ralph Gomory (1958) showed how to automatically generate cutting planes from the LP simplex tableau. For any row of the tableau corresponding to a fractional basic variable, a valid inequality can be derived. Repeated application eventually produces an integral LP optimum.

Gomory cuts are algorithmically universal but often produce weak cuts that advance slowly. Modern solvers combine them with problem-specific cuts.

### Problem-Specific Cuts

**Clique inequalities:** For binary variables where at most one can equal 1 among a set (a clique in the conflict graph), the inequality is that at most one variable in the clique may be 1.

**Cover inequalities:** For 0-1 knapsack constraints, identify subsets of items whose total weight exceeds capacity — a "cover" — and add the inequality that at most (|cover| - 1) items from the cover can be included.

**Subtour elimination:** For the Travelling Salesman Problem, cuts that eliminate partial tours that don't form a valid route.

### Branch and Cut

The most powerful practical approach combines B&B with cutting planes: **branch and cut**. At each node of the B&B tree, cutting planes are added to tighten the LP relaxation before branching. This dramatically reduces the tree size.

All major commercial solvers (CPLEX, Gurobi) implement branch and cut with elaborate cut generation and management systems.

## The Travelling Salesman Problem

The **Travelling Salesman Problem (TSP)** asks: given a set of cities and distances between them, find the shortest route that visits each city exactly once and returns to the start. It is the canonical combinatorial optimization problem and a benchmark for IP methods.

### Why TSP Matters

- TSP appears directly in vehicle routing, circuit board drilling, DNA sequencing, telescope scheduling
- It is NP-hard (no known polynomial-time exact algorithm)
- Yet modern methods routinely solve instances with tens of thousands of cities to proven optimality

### Scale of the Problem

The number of distinct tours for n cities is (n-1)!/2. For n=20, this is about 6×10¹⁶ — far too many to enumerate. But branch-and-cut with good subtour elimination cuts has solved instances with 85,900 cities to optimality (Concorde solver, 2006).

### LP Relaxation for TSP

The LP relaxation of TSP is based on the assignment problem formulation augmented with subtour elimination constraints (Dantzig, Fulkerson, Johnson, 1954). The subtour elimination constraints are exponentially many, but they can be added lazily — only when a violated subtour is found in the current solution.

## Network Flow Problems

**Network flow** is a class of LP/IP problems defined on graphs where flow moves through arcs subject to capacity and conservation constraints. Network structure is ubiquitous in practice.

### Minimum Cost Flow

The most general network flow problem minimizes total shipping cost subject to flow conservation at each node and capacity bounds on each arc. This single formulation specializes to:

- **Transportation problem:** bipartite graph, no arc capacities
- **Assignment problem:** bipartite graph, unit supplies and demands
- **Shortest path:** single source, unit flow, minimize total cost
- **Maximum flow:** single source/sink, maximize flow

### Special Structure Yields Speed

Network flow problems have a **totally unimodular** constraint matrix — every square submatrix has determinant 0, +1, or -1. This means the LP relaxation always has an integer optimal solution when supply/demand values are integer. No branch-and-bound is needed: solve the LP and get the IP solution for free.

Specialized algorithms (network simplex, auction algorithms) solve minimum cost flow problems orders of magnitude faster than general LP.

## The Knapsack Problem

Given items with weights wᵢ and values vᵢ, and a knapsack with capacity W, select items to maximize total value without exceeding capacity. Despite its simplicity, the 0-1 knapsack problem is NP-hard. However:

- A pseudo-polynomial dynamic programming algorithm runs in O(nW) time
- A fully polynomial approximation scheme (FPTAS) finds solutions within a (1-ε) factor of optimal in polynomial time
- Instances with millions of items are routinely solved to near-optimality

Knapsack substructure appears inside larger problems: capital budgeting, portfolio selection, cutting stock, and bin packing all have knapsack-like components.

## Set Covering and Partitioning

Given a set of elements and subsets with costs, choose a minimum-cost collection of subsets that **covers** every element (set covering) or **partitions** it exactly once (set partitioning). Airline crew scheduling is a massive set partitioning problem: crews are subsets of flights, and every flight must be covered exactly once by a valid crew pairing. Problems with millions of variables are solved using **column generation** — a technique that generates variables (pairings) on demand.

## Approximation Algorithms

For NP-hard problems where exact solutions are too slow, approximation algorithms provide solutions with provable quality guarantees:

- **Christofides algorithm (1976):** Produces a TSP solution within 3/2 of optimal in polynomial time
- **Greedy approximations:** For set cover, the greedy algorithm achieves a (ln n + 1)-approximation
- **LP rounding:** Round fractional LP solutions systematically to obtain integral solutions with bounded loss

## See Also

- [Operations Research Hub](OperationsResearchHub) — Cluster overview
- [Linear Programming Foundations](LinearProgrammingFoundations) — LP relaxations underpin IP methods
- [Production Scheduling and OR](ProductionSchedulingAndOR) — Job-shop scheduling as IP
- [Supply Chain and Logistics Optimization](SupplyChainAndLogisticsOptimization) — Transportation problems and vehicle routing
