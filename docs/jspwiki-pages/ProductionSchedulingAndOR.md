---
type: article
cluster: operations-research
tags:
  - operations-research
  - scheduling
  - project-management
  - cpm
  - pert
  - job-shop
date: 2026-03-17
related:
  - OperationsResearchHub
  - IntegerAndCombinatorialOptimization
  - LinearProgrammingFoundations
  - HistoryOfOperationsResearch
status: active
summary: OR techniques for production and project scheduling — CPM, PERT, job-shop scheduling, and resource-constrained project optimization
---
# Production Scheduling and OR

Scheduling — assigning tasks to resources over time — is among the most practically important problem domains in operations research. It governs when a factory makes which product, how a hospital assigns operating rooms, when a construction project hits milestones, and how a chip manufacturer sequences thousands of jobs through its fab. Scheduling problems range from polynomial-time solvable to deeply NP-hard, and the dividing lines are often surprisingly delicate.

## Project Scheduling: CPM and PERT

### Critical Path Method (CPM)

The **Critical Path Method** was developed independently in 1957 by two teams: DuPont/Remington Rand for industrial construction and the U.S. Navy for the Polaris missile program (as PERT). Both represent a project as a directed acyclic graph (DAG) of activities.

**Formulation:**
- **Nodes:** Activities (tasks) with known durations
- **Arcs:** Precedence constraints (activity B cannot start until activity A is complete)
- **Critical path:** The longest path from start to finish — it determines the minimum project duration

**Forward pass — Earliest Start (ES) and Earliest Finish (EF):**

```
ES(j) = max over all predecessors i of { EF(i) }
EF(j) = ES(j) + duration(j)
```

**Backward pass — Latest Start (LS) and Latest Finish (LF):**

```
LF(j) = min over all successors k of { LS(k) }
LS(j) = LF(j) - duration(j)
```

**Float (slack):** F(j) = LS(j) - ES(j). Activities with zero float are on the **critical path** — any delay to them delays the entire project.

**Project crashing:** Resources can be spent to reduce activity durations. The crashing problem (which activities to accelerate, at what cost, to meet a deadline) is an LP. Activities on the critical path are candidates; off-path activities have float to absorb delays without affecting the deadline.

### PERT: Handling Duration Uncertainty

The **Program Evaluation and Review Technique (PERT)** extends CPM by treating activity durations as random variables. Each activity has three estimates:
- **Optimistic time (a):** Minimum possible duration
- **Most likely time (m):** Modal estimate
- **Pessimistic time (b):** Maximum duration

The beta distribution approximation gives:
- **Mean:** μ = (a + 4m + b) / 6
- **Variance:** σ² = ((b - a) / 6)²

For the critical path (longest expected duration), the project completion time is approximately normal with:
- Mean = sum of means along critical path
- Variance = sum of variances along critical path (assuming independence)

The completion probability for deadline T: P(complete by T) = Φ((T - μ_critical) / σ_critical)

This gives project managers a probability distribution over completion times, not just a point estimate.

**Limitation:** PERT underestimates expected project duration because it focuses on the single expected critical path, ignoring that other paths may become critical under adverse realizations. Monte Carlo simulation corrects this by sampling all path durations simultaneously.

## Machine Scheduling Theory

Formal machine scheduling frames the problem as: assign n jobs to m machines over time, subject to constraints, to optimize an objective. The field uses a standard notation: α | β | γ, where α describes the machine environment, β lists constraints, and γ specifies the objective.

### Machine Environments (α)

| Code | Environment | Description |
|------|-------------|-------------|
| 1 | Single machine | One machine, n jobs |
| Pm | Parallel machines | m identical parallel machines |
| Fm | Flow shop | m machines in series, all jobs same route |
| Jm | Job shop | m machines, jobs have different routes |
| Om | Open shop | m machines, job routing is free |

### Common Constraints (β)

- **rⱼ:** Jobs have release times (not available until rⱼ)
- **dⱼ:** Jobs have deadlines
- **prec:** Precedence constraints between jobs
- **pmtn:** Preemption allowed (jobs can be paused and resumed)
- **pⱼ = 1:** Unit processing times

### Objectives (γ)

- **Cₘₐₓ:** Makespan — completion time of the last job
- **∑Cⱼ:** Total completion time (related to average flow time)
- **∑wⱼCⱼ:** Weighted total completion time
- **Lₘₐₓ:** Maximum lateness (Cⱼ - dⱼ)
- **∑Tⱼ:** Total tardiness
- **∑Uⱼ:** Number of tardy jobs

### Single Machine Results

The simplest non-trivial environment yields elegant closed-form results:

**1 || ∑Cⱼ (minimize total completion time):** Process jobs in **Shortest Processing Time (SPT)** order. Provably optimal in O(n log n). This extends to 1 || ∑wⱼCⱼ with weighted SPT: schedule in decreasing order of wⱼ/pⱼ.

**1 || Lₘₐₓ (minimize maximum lateness):** Process jobs in **Earliest Due Date (EDD)** order. Provably optimal in O(n log n).

**1 | rⱼ | Lₘₐₓ (with release times):** NP-hard. The simple EDD optimality breaks when jobs arrive at different times.

These results illustrate a recurring theme: preemption and the absence of release times make problems easier; their presence often triggers NP-hardness.

### Flow Shop Scheduling

In a **flow shop**, all n jobs process through m machines in the same order (machine 1 → machine 2 → ... → machine m). The objective is typically to minimize makespan Cₘₐₓ.

**Two-machine flow shop (F2 || Cₘₐₓ):** Solved optimally by **Johnson's Algorithm (1954)** in O(n log n):
1. Partition jobs into set S1 (shorter on machine 1 than machine 2) and S2 (shorter on machine 2)
2. Order S1 jobs by increasing processing time on machine 1
3. Order S2 jobs by decreasing processing time on machine 2
4. Schedule S1 jobs first, then S2 jobs

**General flow shop (Fm || Cₘₐₓ) for m ≥ 3:** NP-hard. In practice, solved by metaheuristics (iterated local search, genetic algorithms, NEH heuristic for initial solutions).

### Job Shop Scheduling

The **job shop** is the most general and most studied machine scheduling environment: each job has its own route through the machines, and each job-machine combination has a processing time. Minimize makespan.

The 2-machine job shop is polynomial; 3-machine job shop is NP-hard. The general m-machine job shop is one of the canonical NP-hard problems.

**Branch and bound:** State-of-the-art exact method. Can optimally solve instances with ~20 jobs and ~20 machines with sufficient computation. Beyond that, heuristics are necessary.

**Shifting bottleneck heuristic (Adams, Balas, Zawack 1988):** A highly effective heuristic that sequentially resolves scheduling conflicts one machine at a time, treating each machine as a 1-machine problem while fixing others. Often finds solutions within a few percent of optimal.

## Resource-Constrained Project Scheduling (RCPS)

Real projects don't just have precedence constraints — they compete for limited resources (workers, equipment, budget). The **Resource-Constrained Project Scheduling Problem (RCPSP)** extends CPM:

- Each activity requires a fixed amount of each resource per period
- Each resource has a capacity limit per period
- Find a schedule (start time for each activity) that respects precedences, resource limits, and minimizes project duration

RCPSP is NP-hard. Exact methods (branch and bound) handle projects up to ~50 activities optimally. For larger projects:

- **Priority rules:** Assign activities to resources using heuristic rules (most total successors first, minimum float first)
- **Genetic algorithms:** Encode activity orderings as chromosomes; evolve toward shorter durations
- **Scatter search:** Population-based metaheuristic, consistently among the best for RCPSP benchmarks

## Workforce Scheduling

Scheduling people — nurses, airline crews, call center agents, police officers — is among the largest-scale scheduling applications and is heavily regulated.

### Airline Crew Scheduling

Airline crew scheduling is a two-step process:

1. **Crew pairing:** Create a minimum-cost set of pairings (sequences of flights forming round trips) that covers all flights. A massive set-partitioning IP, often with millions of candidate pairings. Solved using **column generation** — generate pairings on demand as the LP demands them.

2. **Crew rostering (bidline/PBS):** Assign pairings to individual crew members, respecting union rules, rest requirements, and preferences. Another large IP.

Airline crew scheduling is considered one of the greatest commercial successes of OR. Delta, United, and other carriers save hundreds of millions annually vs. manual scheduling.

### Nurse Scheduling

Hospital nurse scheduling must cover demand (patient-to-nurse ratios by ward and shift), respect labor rules (days off, shift rotations, overtime limits), and accommodate preferences. Formulated as an IP, solved by branch and cut or local search.

## See Also

- [Operations Research Hub](OperationsResearchHub) — Cluster overview
- [Integer and Combinatorial Optimization](IntegerAndCombinatorialOptimization) — Branch and bound, integer programming methods used in scheduling
- [History of Operations Research](HistoryOfOperationsResearch) — CPM/PERT origins and the OR history behind them
- [Linear Programming Foundations](LinearProgrammingFoundations) — Project crashing as an LP
