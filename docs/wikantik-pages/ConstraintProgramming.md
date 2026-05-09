---
cluster: operations-research
canonical_id: 01KQ0P44NX3FQP4M6QN56RMKFT
title: Constraint Programming
type: article
tags:
- operations-research
- optimization
- constraint-satisfaction
- algorithms
- ai
status: active
date: 2025-05-15
summary: Technical analysis of Constraint Programming (CP) for combinatorial optimization. Covers domain reduction, propagation, and search heuristics.
auto-generated: false
---

# Constraint Programming: Combinatorial Inference

Constraint Programming (CP) is a paradigm for solving combinatorial problems by identifying and pruning the search space based on logical constraints.

## 1. Core Mechanisms: Propagation and Reduction

Unlike Mathematical Programming (which uses continuous relaxation), CP works directly with discrete domains.
*   **Domain Reduction:** Removing values from a variable's domain that cannot possibly be part of a valid solution.
*   **Constraint Propagation:** When a variable's domain is reduced, that change is propagated to all other variables linked by constraints.
*   **Concrete Example:** If $X < Y$ and the domain of $Y$ becomes $\{1, 2\}$, the domain of $X$ is immediately reduced to $\{0, 1\}$.

## 2. Global Constraints: The Power of CP

Global constraints capture complex structural properties of a problem, allowing for highly efficient propagation.
*   **AllDifferent:** Ensures all variables in a set take unique values. It uses graph-based algorithms (matching) to prune values much faster than individual $X \neq Y$ checks.
*   **Cumulative:** Used in scheduling to ensure total resource usage across overlapping tasks never exceeds capacity.
*   **Circuit:** Ensures a set of variables forms a single Hamiltonian cycle (essential for Traveling Salesperson problems).

## 3. Modeling and Solving with Google OR-Tools

CP is implemented in modern solvers like **Google OR-Tools (CP-SAT)**.
*   **SAT-based Search:** Modern CP solvers use Boolean Satisfiability (SAT) techniques to manage the underlying search tree.
*   **Heuristics:** Use `SearchStrategy` (e.g., `CHOOSE_MIN_DOMAIN_SIZE`) to guide the solver toward failure points earlier, speeding up the proof of optimality.

## 4. Concrete Implementation: Job-Shop Scheduling

**Problem:** 10 jobs must be processed on 5 shared machines. Each job has a specific sequence and duration.
*   **Variables:** `start_time_job_i_machine_j`.
*   **Constraints:** 
    1.  **No-Overlap:** `IntervalVar` on each machine ensures a machine only does one job at a time.
    2.  **Precedence:** `start_time_task_2 >= end_time_task_1`.
*   **Optimization:** Minimize the **Makespan** (the time the last job finishes).

---
**See Also:**
- [Operations Research Hub](OperationsResearch) — General optimization context.
- [Graph Theory Symmetry](GroupTheorySymmetry) — Analyzing problem structure.
- [Game Theory Fundamentals](GameTheoryFundamentals) — Competitive constraints.
