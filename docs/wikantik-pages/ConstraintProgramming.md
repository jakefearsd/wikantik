---
tags:
- operations-research
- optimization
- constraint-satisfaction
- algorithms
- ai
summary: 'Constraint Programming explained: the CSP model, propagation and search,
  global constraints, CP vs MIP/SAT, major solvers, and where to learn more.'
related:
- OperationsResearch
- GroupTheorySymmetry
- GameTheoryFundamentals
canonical_id: 01KQ0P44NX3FQP4M6QN56RMKFT
auto-generated: false
type: article
status: active
cluster: operations-research
date: 2025-05-15T00:00:00Z
title: Constraint Programming
---

# Constraint Programming: Combinatorial Inference

Constraint Programming (CP) is a **declarative** paradigm for solving combinatorial problems: you *describe* the problem — the decision variables, the values they may take, and the constraints relating them — and a general-purpose solver searches for assignments that satisfy every constraint (and, optionally, optimise an objective). You model *what* a valid solution looks like; the solver works out *how* to find one.

That focus makes CP especially strong on tightly-constrained, discrete problems — scheduling, rostering, timetabling, sequencing, configuration, and resource allocation — where the hard part is satisfying a thicket of interacting rules rather than optimising a smooth numeric objective.

## 1. The model: constraint satisfaction problems

Formally, a **Constraint Satisfaction Problem (CSP)** is a triple $(X, D, C)$:
- $X$ — a set of decision variables.
- $D$ — a domain of candidate values for each variable (often finite integers, e.g. $\{0, 1, \dots, n\}$).
- $C$ — a set of constraints, each restricting the values that some subset of variables may *jointly* take.

A **solution** assigns every variable a value from its domain such that all constraints hold. Attach an objective function to minimise or maximise and the CSP becomes a **Constraint Optimisation Problem (COP)**.

Textbook problems — map colouring, the $n$-queens puzzle, Sudoku — make the model concrete, but the industrial payoff is in scheduling, employee rostering, school and exam timetabling, vehicle routing, product configuration, and cutting/packing.

## 2. How CP solves: propagate, branch, backtrack

CP interleaves **inference** (propagation) with **search** (branching), backtracking whenever it hits a dead end.

*   **Domain Reduction:** Removing values from a variable's domain that cannot possibly be part of a valid solution.
*   **Constraint Propagation:** When a variable's domain is reduced, that change is propagated to every other variable linked by a constraint — which may trigger further reductions until a *fixpoint* is reached.
*   **Concrete Example:** If $X < Y$ and the domain of $Y$ becomes $\{1, 2\}$, the domain of $X$ is immediately reduced to $\{0, 1\}$.
*   **Search:** When propagation alone cannot decide the problem, the solver *branches* — tentatively fixing a variable — then propagates again. A contradiction triggers backtracking to the last open choice.

How hard the engine tries to prune is its **consistency level**: node consistency, *arc consistency* (the classic AC-3 algorithm), bounds consistency, and *generalised arc consistency (GAC)* for global constraints. Because CP search is systematic, it is **complete** — given enough time it can prove optimality, or prove that no solution exists at all, which local-search methods cannot.

## 3. Global constraints: the power of CP

Global constraints capture complex structural properties of a problem, enabling highly efficient propagation.
*   **AllDifferent:** Ensures all variables in a set take unique values. It uses graph-based algorithms (bipartite matching) to prune values far faster than a web of individual $X \neq Y$ checks.
*   **Cumulative:** Used in scheduling to ensure total resource usage across overlapping tasks never exceeds capacity.
*   **Circuit:** Ensures a set of variables forms a single Hamiltonian cycle (essential for Traveling Salesperson problems).
*   **Element / Table:** Index into an array by a decision variable, or enumerate the legal value combinations directly.

A solver's library of global constraints — and how strongly each one propagates — is a major differentiator between tools.

## 4. CP in context: how it differs from MIP, SAT, and local search

Understanding CP means knowing where it sits among neighbouring techniques:

- **vs Mathematical / Mixed-Integer Programming (MIP/LP).** MIP relaxes integrality to a continuous problem and drives branch-and-bound with linear-programming bounds; it shines when the problem has a strong linear relaxation (network flows, blending, large assignment problems). CP works directly on discrete domains and excels at feasibility-heavy, disjunctive, and scheduling problems where MIP relaxations are weak. The big commercial MIP solvers (Gurobi, CPLEX, FICO Xpress) live in this world.
- **vs Boolean Satisfiability (SAT).** SAT is the special case where every variable is Boolean. Modern CP solvers borrow SAT's machinery — clause learning and *lazy clause generation* — to explain failures and avoid repeating them. Google's CP-SAT solver is the leading example of this CP/SAT fusion.
- **vs local search & metaheuristics.** Tabu search, simulated annealing, and genetic algorithms scale to huge problems but are *incomplete* — fast good-enough answers, no optimality proof. CP can be combined with local search (e.g. Large Neighbourhood Search) to get the best of both.

A practical rule of thumb: reach for CP when the problem is **highly combinatorial and rule-dense** (scheduling, sequencing, rostering); reach for MIP when it is **numeric and well-relaxed**. Many modern solvers deliberately blur the line.

## 5. Modeling and solving in practice

The workflow is *model-and-solve*: express variables and constraints in a modelling layer, then let the solver search.
*   **SAT-based search:** Modern CP solvers (e.g. **Google OR-Tools CP-SAT**) use Boolean Satisfiability techniques to manage the underlying search tree.
*   **Search heuristics:** Variable and value ordering (e.g. `CHOOSE_MIN_DOMAIN_SIZE` — the "fail-first" principle) steers the solver toward contradictions earlier, speeding up the proof of optimality.
*   **Restarts & LNS:** Periodic restarts and Large Neighbourhood Search help escape unproductive regions of the search tree on large instances.

## 6. Worked example: job-shop scheduling

**Problem:** 10 jobs must be processed on 5 shared machines. Each job has a specific sequence and duration.
*   **Variables:** `start_time_job_i_machine_j`.
*   **Constraints:**
    1.  **No-Overlap:** `IntervalVar` on each machine ensures a machine only does one job at a time.
    2.  **Precedence:** `start_time_task_2 >= end_time_task_1`.
*   **Optimization:** Minimize the **Makespan** (the time the last job finishes).

## 7. Tools and solvers

A defining strength of CP is the **separation of model from solver**: one high-level model can often run on several back-end engines. The landscape splits into modelling languages, open-source solvers, and commercial solvers.

### Modelling languages
- **[MiniZinc](https://www.minizinc.org/)** — a free, solver-independent constraint-modelling language. Write the model once; MiniZinc compiles it to *FlatZinc* and runs it on any supported back end (Gecode, Chuffed, OR-Tools, CP Optimizer, …). The best place for a newcomer to start.
- **OPL** — IBM's Optimization Programming Language, the native modelling language for CP Optimizer and CPLEX. Python users typically reach for the **docplex** API instead.
- **Native APIs** — most solvers also expose direct programming interfaces (OR-Tools' `cp_model` in Python/C++/Java/C#, Choco's Java API, and so on).

### Open-source solvers (free — ideal for learning)
- **[Google OR-Tools — CP-SAT](https://developers.google.com/optimization/cp)** — the dominant modern CP solver. Free, Apache-licensed, lazy-clause-generation/SAT-based, with first-class Python, C++, Java, and C# APIs. The pragmatic default for most new CP work.
- **[Gecode](https://www.gecode.org/)** — a fast, well-documented C++ CP library and a standard MiniZinc back end.
- **[Choco Solver](https://choco-solver.org/)** — a mature Java CP library widely used in research and on the JVM.
- **[Chuffed](https://github.com/chuffed/chuffed)** — a lazy-clause-generation solver that regularly tops the MiniZinc Challenge.

### Commercial solvers
- **[IBM ILOG CP Optimizer](https://www.ibm.com/products/ilog-cplex-optimization-studio)** — the flagship commercial CP solver, part of CPLEX Optimization Studio. Particularly strong on scheduling thanks to rich interval-variable and sequence modelling, with an automatic search that needs little hand-tuning. Free academic and community editions are available.
- **[Hexaly](https://www.hexaly.com/)** (formerly LocalSolver) — a commercial "model-and-run" optimiser that tackles large constraint-based combinatorial and routing models with minimal solver tuning.
- **[SICStus Prolog](https://sicstus.sics.se/)** — a commercial Prolog with a mature `CLP(FD)` finite-domain constraint library, reflecting CP's roots in *constraint logic programming*.

> **Adjacent tooling.** Planning libraries such as **[Timefold](https://timefold.ai/)** (the successor to OptaPlanner) solve constraint-rich scheduling and rostering problems via local search and "constraint streams." They are not classical propagation-based CP, but they target the same family of problems and often surface in the same searches.

## 8. Where to learn more

- **The MiniZinc Handbook**, plus the University of Melbourne's Coursera courses *Modeling Discrete Optimization* and *Solving Algorithms for Discrete Optimization* — the most approachable hands-on introduction.
- ***Handbook of Constraint Programming*** (Rossi, van Beek & Walsh, 2006) — the comprehensive academic reference.
- **The OR-Tools CP-SAT documentation** and the community **CP-SAT Primer** — practical, example-driven, and current.
- **IBM CP Optimizer documentation** — especially for interval-variable scheduling models.

## Frequently Asked Questions

**What is constraint programming?**
A declarative paradigm for combinatorial problem-solving: you state decision variables, their possible values (domains), and the constraints relating them, and a solver searches for assignments satisfying all constraints — optionally optimising an objective. You model the problem rather than code the search.

**Constraint programming vs linear / integer programming — what's the difference?**
MIP/LP relaxes integer requirements to a continuous problem and searches with linear-programming bounds, excelling when the problem has a strong linear relaxation. CP works directly on discrete domains via propagation and search, excelling on scheduling, sequencing, and feasibility-heavy problems. Modern solvers increasingly combine both.

**What is the best constraint programming solver?**
For most new projects, **Google OR-Tools CP-SAT** (free, fast, well-supported) is the pragmatic default. **IBM ILOG CP Optimizer** is the leading commercial option, especially for scheduling. **MiniZinc** is the best way to learn and to stay solver-independent.

**Is constraint programming only for scheduling?**
No. Scheduling and rostering are flagship use cases, but CP also handles timetabling, vehicle routing, configuration, resource allocation, and cutting/packing — any tightly-constrained, discrete problem.

---
**See Also:**
- [Operations Research Hub](OperationsResearch) — General optimization context.
- [Group Theory & Symmetry](GroupTheorySymmetry) — Symmetry breaking to shrink the CP search space.
- [Game Theory Fundamentals](GameTheoryFundamentals) — Competitive constraints.
