---
type: article
cluster: operations-research
tags:
  - operations-research
  - linear-programming
  - optimization
  - mathematics
  - simplex
date: 2026-03-17
related:
  - OperationsResearchHub
  - HistoryOfOperationsResearch
  - IntegerAndCombinatorialOptimization
  - MathematicalFoundationsOfMachineLearning
status: active
summary: The mathematical foundations of linear programming — formulation, the simplex method, duality theory, and why LP remains the workhorse of optimization
---
# Linear Programming Foundations

Linear programming (LP) is the foundational tool of operations research. It addresses the problem of optimizing (maximizing or minimizing) a linear function of several variables subject to linear equality and inequality constraints. The technique is both mathematically elegant and practically powerful — it underlies supply chain optimization, airline scheduling, portfolio management, and hundreds of other applications.

## What Is Linear Programming?

A linear program has three components:

1. **Decision variables** — quantities to be determined
2. **Objective function** — a linear expression to maximize or minimize
3. **Constraints** — linear inequalities or equalities that the variables must satisfy

### Standard Form

A canonical LP in standard form:

```
Maximize:    c₁x₁ + c₂x₂ + ... + cₙxₙ

Subject to:  a₁₁x₁ + a₁₂x₂ + ... + a₁ₙxₙ ≤ b₁
             a₂₁x₁ + a₂₂x₂ + ... + a₂ₙxₙ ≤ b₂
             ...
             aₘ₁x₁ + aₘ₂x₂ + ... + aₘₙxₙ ≤ bₘ
             x₁, x₂, ..., xₙ ≥ 0
```

In matrix notation: **maximize cᵀx subject to Ax ≤ b, x ≥ 0**, where **c** and **x** are n-vectors, **A** is an m×n matrix, and **b** is an m-vector.

### A Simple Example

A factory makes two products, A and B. Each unit of A earns $5 profit; each unit of B earns $4. Production requires machine time (8 hours available) and labor (6 hours available). Product A needs 2 machine hours and 1 labor hour per unit; Product B needs 1 machine hour and 2 labor hours.

```
Maximize:    5xₐ + 4x_b
Subject to:  2xₐ + x_b  ≤ 8   (machine hours)
             xₐ  + 2x_b ≤ 6   (labor hours)
             xₐ, x_b   ≥ 0
```

The optimal solution is xₐ = 10/3, x_b = 4/3, giving profit = 50/3 + 16/3 = 22. (In practice we might require integer solutions — see [Integer and Combinatorial Optimization](IntegerAndCombinatorialOptimization).)

## The Geometry of LP

The constraints define a **feasible region** — the set of all points satisfying all constraints simultaneously. For a problem with n variables, this region is a **convex polytope** (or polyhedron) in n-dimensional space. Convexity is crucial: a convex set has no "holes" or "dents," which means any local optimum is a global optimum.

The objective function defines a family of parallel hyperplanes. We seek the hyperplane with the best objective value that still intersects the feasible region. This optimal hyperplane will touch the feasible region at a **vertex** (corner point) of the polytope — unless the problem is unbounded or infeasible.

**Key theorem:** If an LP has an optimal solution, there exists an optimal vertex. This is the geometric foundation of the simplex method.

## The Simplex Method

Developed by George Dantzig in 1947, the simplex method is an iterative algorithm that moves along the edges of the feasible polytope from vertex to vertex, improving the objective at each step. It terminates when no neighboring vertex yields improvement.

### Algorithmic Sketch

1. **Find an initial basic feasible solution** — a vertex of the feasible polytope. For problems with ≤ constraints, this is often the origin (all variables = 0), augmented with slack variables.
2. **Compute reduced costs** — for each non-basic variable, compute how much the objective would improve if that variable entered the basis.
3. **Choose the entering variable** — the non-basic variable with the most favorable reduced cost (various pivot rules exist).
4. **Choose the leaving variable** — the basic variable that hits zero first as the entering variable increases (minimum ratio test).
5. **Pivot** — swap entering and leaving variables. Update the basis representation.
6. **Repeat** until no improving non-basic variable exists (optimality condition met) or detect unboundedness.

### Computational Complexity

The simplex method is not polynomial in the worst case — Klee and Minty (1972) constructed an LP where it examines exponentially many vertices. However, in practice, it is remarkably efficient, typically taking O(m) to O(3m) pivots for an LP with m constraints.

This apparent paradox — exponential worst case, polynomial average case — is still not fully explained theoretically. The smoothed analysis framework (Spielman and Teng, 2004) gave partial answers, showing that small random perturbations make the worst case vanish.

### Interior-Point Methods

Karmarkar's 1984 algorithm, and the class of interior-point (barrier) methods that followed, are polynomial in the worst case. Interior-point methods traverse the *interior* of the feasible region rather than its boundary, following a curved path to the optimum.

For very large LPs (millions of variables and constraints), interior-point methods are often competitive with or faster than simplex. Modern commercial solvers (CPLEX, Gurobi) implement both and choose heuristically.

## LP Duality

Every LP (the **primal**) has an associated **dual LP**. For the standard primal:

```
Primal: max cᵀx s.t. Ax ≤ b, x ≥ 0
Dual:   min bᵀy s.t. Aᵀy ≥ c, y ≥ 0
```

Duality is not a technical curiosity — it is conceptually central to LP.

### Weak Duality

For any primal feasible x and dual feasible y: **cᵀx ≤ bᵀy**. The dual provides an upper bound on the primal objective (for maximization). This means any feasible dual solution certifies that the primal cannot do better.

### Strong Duality

If both the primal and dual have feasible solutions, they have equal optimal values: **max cᵀx = min bᵀy**. The duality gap is zero at optimality.

### Complementary Slackness

A primal solution x* and dual solution y* are both optimal if and only if:
- For each primal constraint: yᵢ*(bᵢ - aᵢᵀx*) = 0 (either the constraint is tight, or the dual variable is zero)
- For each dual constraint: xⱼ*(aⱼᵀy* - cⱼ) = 0 (either the variable is at a bound, or the reduced cost is zero)

### Economic Interpretation

The dual variables have a natural interpretation as **shadow prices** or **marginal values**. In the factory example, the dual variable for the machine-hours constraint tells you how much the optimal profit would increase if you had one more machine hour. This interpretation makes LP duality an essential tool for sensitivity analysis — understanding how much the optimal solution changes when parameters shift.

## Sensitivity Analysis

Real-world LP models contain uncertain parameters: costs, resource capacities, demand forecasts. Sensitivity analysis answers questions like:

- Over what range of the objective coefficient for product A does the current basis remain optimal?
- If machine capacity increases by 10%, how much does profit improve?
- What is the minimum cost increase that would make it worthwhile to add a third machine?

Commercial solvers report sensitivity ranges automatically. This capability transforms LP from a one-time calculation into a continuous planning tool.

## Network LP: A Special Case

Many practical LPs have a special structure — the constraint matrix is a **network incidence matrix**, with one +1 and one -1 per column. These **network flow problems** can be solved much faster than general LP using specialized network simplex or shortest-path algorithms.

The transportation problem is an archetypal network LP:
- Given m supply sources and n demand destinations
- Each source has a supply capacity, each destination a demand requirement
- Each (source, destination) pair has a shipping cost
- Find the minimum-cost flow that satisfies all demands without exceeding supply

This structure appears in shipping, telecommunications routing, assignment problems, and production planning.

## Applications of LP

| Industry | Application | Scale |
|----------|-------------|-------|
| Airlines | Crew scheduling, fleet assignment | Millions of variables |
| Oil refining | Refinery planning, blending | Hundreds of constraints |
| Agriculture | USDA crop allocation planning | National scale |
| Finance | Portfolio optimization, risk management | Thousands of assets |
| Telecommunications | Network capacity planning | Large networks |
| Military | Logistics, airlift planning | Theater-scale |
| Retail | Inventory allocation, shelf placement | Chain-wide |

## LP Software

Modern LP is solved using commercial or open-source solvers:

- **Gurobi** — the fastest commercial solver; widely used in industry
- **CPLEX** (IBM) — flagship commercial solver, decades of refinement
- **HiGHS** — high-performance open-source solver
- **GLPK** — open-source, suitable for smaller problems

Modeling languages like **AMPL**, **GAMS**, and the Julia package **JuMP** allow formulating models independently of the solver.

## See Also

- [Operations Research Hub](OperationsResearchHub) — Cluster overview
- [History of Operations Research](HistoryOfOperationsResearch) — How LP was developed by Dantzig
- [Integer and Combinatorial Optimization](IntegerAndCombinatorialOptimization) — LP extended to integer variables
- [Mathematical Foundations of Machine Learning](MathematicalFoundationsOfMachineLearning) — Convex optimization methods shared with ML
