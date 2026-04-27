---
canonical_id: 01KQ0P44MWAYKY5RFMQHXY6HZX
title: Calculus Refresh for CS
type: article
cluster: mathematics
status: active
date: '2026-04-26'
summary: The calculus that software engineers actually need — derivatives, gradients,
  optimization, multivariable calculus — focused on ML and algorithm contexts rather
  than physics or engineering.
tags:
- calculus
- mathematics
- derivatives
- gradients
- optimization
related:
- AppliedMathSurvey
- ProbabilityTheory
- ComplexAnalysis
hubs:
- Mathematics Hub
---
# Calculus Refresh for CS

For software engineers entering ML or numerical work, calculus is the math that comes back. The full undergraduate sequence isn't needed; specific tools are. This page covers what's most useful.

## Derivatives

The derivative measures rate of change. For function f(x), the derivative f'(x) tells how f changes as x changes.

Notation:
- f'(x) — Lagrange's
- df/dx — Leibniz's
- ∂f/∂x — partial derivative (multivariable)

### Why CS people care

In machine learning: training is gradient descent. The "gradient" is just the derivative of the loss function with respect to model parameters. The model adjusts parameters in the direction that reduces loss most steeply.

Every ML training algorithm is, at its core, calculating derivatives.

### Common derivatives to know

- d/dx[xⁿ] = n·x^(n-1)
- d/dx[eˣ] = eˣ
- d/dx[ln(x)] = 1/x
- d/dx[sin(x)] = cos(x)
- d/dx[cos(x)] = -sin(x)

These cover most cases.

### Chain rule

For nested functions: d/dx[f(g(x))] = f'(g(x)) · g'(x).

Critical for ML: neural networks are nested functions; backpropagation is just the chain rule applied repeatedly.

### Product rule

d/dx[f(x)·g(x)] = f'(x)·g(x) + f(x)·g'(x)

For products of functions.

### Quotient rule

d/dx[f(x)/g(x)] = (f'·g - f·g')/g²

For quotients.

## Gradients (multivariable derivatives)

For a function of multiple variables f(x, y, z, ...), the gradient ∇f is a vector of partial derivatives:

∇f = [∂f/∂x, ∂f/∂y, ∂f/∂z, ...]

The gradient points in the direction of steepest ascent. Negative gradient points toward steepest descent.

### Gradient descent

The dominant ML training algorithm:
1. Compute gradient of loss w.r.t. parameters
2. Move parameters in direction of negative gradient
3. Repeat

The math is just: take derivatives; subtract.

### Hessian

Matrix of second derivatives. Used in second-order optimization (Newton's method) and curvature analysis.

For ML, second-order methods are usually too expensive; first-order (just gradient) is standard.

## Integrals

Integrals are accumulation. ∫f(x)dx is the area under f from a to b.

### Why CS people care

Less than derivatives, but:
- Probability theory (expectation, distributions)
- Continuous loss functions
- Information theory (KL divergence is an integral)
- Some optimization methods

### Common integrals

- ∫xⁿ dx = x^(n+1)/(n+1) + C
- ∫eˣ dx = eˣ + C
- ∫1/x dx = ln|x| + C

The fundamental theorem of calculus connects derivatives and integrals: if F'(x) = f(x), then ∫f(x)dx = F(x).

## Limits

The foundation of calculus. lim(x→a) f(x) is the value f approaches as x approaches a.

For software engineers: rarely needed directly, but underpins:
- Big-O notation (asymptotic behavior is a limit)
- Continuity
- Differentiability

## Series and convergence

Sums of infinite sequences. Whether they converge or diverge.

For ML: less important. For algorithm analysis: relevant for understanding why some algorithms are O(log n).

For Taylor series (approximating functions by polynomials): used in some optimization analysis.

## Optimization

Finding maxima and minima of functions.

### First-order conditions

At a maximum or minimum, the derivative (or gradient) is zero.

For unconstrained optimization: solve f'(x) = 0, check if it's a min or max.

### Second-order conditions

The second derivative tells you concavity:
- f''(x) > 0 → minimum
- f''(x) < 0 → maximum

Helpful for confirming.

### Constrained optimization

When variables must satisfy constraints. Lagrange multipliers; KKT conditions.

Less common in ML; common in operations research.

### Local vs. global

A function may have many local optima. Algorithms find local; finding global is hard.

For ML: most loss functions are non-convex; gradient descent finds local minima but they're often good enough.

## Specific techniques for ML

### Backpropagation

Apply the chain rule through a neural network. Compute gradient of loss w.r.t. each parameter.

Modern frameworks (PyTorch, TensorFlow) do this automatically (autograd). You don't compute by hand.

### Stochastic gradient descent

Instead of computing gradient over all training data (expensive), compute over a mini-batch (random subset).

Standard ML training algorithm. Many variants (Adam, RMSprop, etc.) tune the learning rate adaptively.

### Convex vs. non-convex

A convex function has a single global minimum reachable by gradient descent. Most ML loss functions are non-convex; gradient descent finds local minima.

Linear regression, logistic regression: convex. Neural networks: non-convex.

## What you don't need (usually)

- Detailed integration techniques (rarely needed in ML)
- Real analysis rigor (epsilon-delta proofs)
- Most of complex analysis (unless doing signal processing)
- Differential equations (unless physics simulation)

## Key intuitions

### Derivative = sensitivity

How much does the output change when the input changes? That's the derivative.

### Gradient = direction of steepest ascent

In multidimensional space, the gradient points "uphill." Going opposite means going downhill (toward minimum).

### Integral = total accumulation

Sum of f(x) over a range, weighted by infinitesimal dx.

### Optimization = finding flat points

At maxima and minima, the gradient is zero (the function is locally flat).

## Common failure patterns

- **Memorizing rules without understanding intuition.** When something unusual happens, you can't extend.
- **Avoiding the math entirely in ML.** You can use frameworks without calculus understanding, but debugging gets harder.
- **Confusing gradient with derivative in casual speech.** They're related; gradient is the multivariable version.
- **Forgetting numerical issues.** Theoretical calculus is exact; floating-point isn't.

## Further Reading

- [AppliedMathSurvey](AppliedMathSurvey) — Where calculus fits
- [ProbabilityTheory](ProbabilityTheory) — Probability uses integrals
- [ComplexAnalysis](ComplexAnalysis) — Calculus extended
- [Mathematics Hub](Mathematics+Hub) — Cluster index
