---
canonical_id: 01KQ0P44KZWNJPTF8W2F7NTRFR
title: Applied Math Survey
type: article
cluster: mathematics
status: active
date: '2026-04-26'
summary: Map of applied mathematics — calculus, linear algebra, probability, optimization,
  numerical methods, discrete math, differential equations — and where each shows
  up in computing, science, and engineering.
tags:
- mathematics
- applied-math
- calculus
- linear-algebra
- optimization
related:
- CalculusRefreshForCS
- ProbabilityTheory
- ChaosDynamical
- ComplexAnalysis
- TopologyMathematics
- DifferentialGeometry
hubs:
- Mathematics Hub
---
# Applied Math Survey

Applied mathematics is the toolbox underneath science, engineering, and computing. Different problems pull different tools; understanding what's available shapes how you approach unfamiliar problems.

This page is the map.

## Calculus

The mathematics of change. Derivatives (rate of change at a point), integrals (accumulation), limits, sequences, series.

Where it shows up:
- Physics: motion, fields, waves
- Engineering: control systems, signal processing
- Machine learning: gradient descent (taking derivatives of loss functions)
- Optimization: finding extrema by setting derivatives to zero
- Probability: continuous distributions integrate to 1

For computer scientists, the most-needed parts: differentiation rules, multivariable calculus, basic ODE/PDE awareness. See [CalculusRefreshForCS](CalculusRefreshForCS).

## Linear algebra

Vectors, matrices, vector spaces, linear transformations, eigenvalues.

Where it shows up:
- Computer graphics: 3D transformations
- Machine learning: nearly everything (data as matrices, neural network weights)
- Optimization: linear programming
- Network analysis: graph structures as matrices
- Signal processing: Fourier methods

The most-needed parts: matrix multiplication, eigendecomposition, SVD, basic geometry of vector spaces.

For ML practitioners, linear algebra is foundational. Most ML algorithms are linear algebra operations under the hood.

## Probability and statistics

Random variables, distributions, expectation, variance, hypothesis testing, Bayesian inference.

Where it shows up:
- Statistics: data analysis
- Machine learning: probabilistic models, uncertainty estimation
- Cryptography: random number generation, security analysis
- Finance: risk modeling
- Physics: statistical mechanics, quantum mechanics

Probability is the math of uncertainty. Underrated in practical importance.

See [ProbabilityTheory](ProbabilityTheory).

## Discrete mathematics

Logic, set theory, combinatorics, graph theory, number theory.

Where it shows up:
- Computer science fundamentals: algorithms, data structures
- Cryptography: number theory (primes, modular arithmetic)
- Network analysis: graph theory
- Database theory: relational algebra (set theory)
- Verification: formal logic

For software engineers, discrete math is often more relevant than continuous calculus.

## Optimization

Finding the best value of a function subject to constraints. Linear programming, nonlinear optimization, convex optimization, integer programming.

Where it shows up:
- Operations research: scheduling, routing, resource allocation
- Machine learning: training (minimize loss)
- Engineering: design optimization
- Economics: utility maximization

For computational problems with "find the best X", optimization frameworks apply.

## Numerical methods

Solving math problems on computers. Floating-point arithmetic, error analysis, iterative methods, root finding, numerical integration, linear system solvers.

Where it shows up:
- Scientific computing: simulation, modeling
- Machine learning: gradient methods, matrix solvers
- Computer graphics: ray tracing, physical simulation
- Engineering: finite element analysis

The math is exact; the computer's representation isn't. Numerical methods bridge the gap.

## Differential equations

Equations involving derivatives. ODEs (ordinary), PDEs (partial), boundary value problems.

Where it shows up:
- Physics: dynamics, fluid flow, electromagnetism
- Engineering: control systems
- Biology: population dynamics, epidemiology
- Finance: option pricing (Black-Scholes)
- Climate: weather and climate models

Most physical phenomena obey differential equations. Numerical methods solve them.

## Complex analysis

Calculus on complex numbers. Analytic functions, residue theorem, conformal mapping.

Where it shows up:
- Signal processing: frequency domain analysis
- Physics: quantum mechanics, fluid dynamics
- Engineering: electrical circuits

See [ComplexAnalysis](ComplexAnalysis).

## Topology

Properties of spaces preserved under continuous deformation. Open sets, continuity, compactness, connectedness.

Where it shows up:
- Physics: cosmology, particle physics
- Computer science: data analysis (topological data analysis)
- Network analysis: connectivity properties

See [TopologyMathematics](TopologyMathematics).

## Differential geometry

Calculus on curved spaces. Manifolds, tangent spaces, curvature.

Where it shows up:
- General relativity: spacetime as a manifold
- Computer graphics: surface modeling
- Machine learning: manifold learning, optimization on manifolds

See [DifferentialGeometry](DifferentialGeometry).

## Dynamical systems and chaos

Systems that evolve over time. Stability, periodic orbits, chaos.

Where it shows up:
- Climate modeling
- Population dynamics
- Cryptography (chaotic systems for randomness)
- Economics (market dynamics)

See [ChaosDynamical](ChaosDynamical).

## Information theory

Entropy, channel capacity, coding theorems.

Where it shows up:
- Compression algorithms
- Cryptography
- Machine learning (cross-entropy loss; mutual information)
- Communications

## Graph theory

Vertices, edges, paths, cycles, planarity, coloring.

Where it shows up:
- Algorithms (shortest path, max flow)
- Network analysis (social, transportation, communication)
- Machine learning (graph neural networks)
- Compiler design (control flow graphs)

## Choosing what to learn

For software engineers entering ML or scientific computing:

1. Linear algebra (essential)
2. Probability (essential)
3. Calculus refresh (foundational)
4. Optimization basics (where it applies)
5. Discrete math if you didn't take it (essential for theory)

For data scientists:

1. Statistics
2. Probability
3. Linear algebra
4. Calculus

For systems / theory:

1. Discrete mathematics
2. Logic
3. Algebra
4. Combinatorics

The "math you need" depends on the problems you tackle. The survey above provides orientation.

## Common failure patterns

- **Trying to learn math in the abstract.** Without a problem you're trying to solve, math becomes academic.
- **Overlearning before applying.** Better to learn enough to start; deepen as needed.
- **Avoiding math out of fear.** Many ML/CS topics actually require modest math; don't be discouraged by the appearance of formality.
- **Ignoring numerical issues.** Theoretical math and computational math diverge; account for floating-point.

## Further Reading

- [CalculusRefreshForCS](CalculusRefreshForCS) — Targeted calculus for software engineers
- [ProbabilityTheory](ProbabilityTheory) — Probability foundations
- [ComplexAnalysis](ComplexAnalysis) — Complex-number calculus
- [TopologyMathematics](TopologyMathematics) — Spatial properties
- [DifferentialGeometry](DifferentialGeometry) — Curved spaces
- [ChaosDynamical](ChaosDynamical) — Time evolution
- [Mathematics Hub](Mathematics+Hub) — Cluster index
