---
related:
- NumericalMethods
- LinearAlgebra
- MathematicsHub
summary: A rigorous study guide for Differential Calculus — covering the formal definition
  of derivatives, key theorems, Taylor series, and multivariable analysis (Jacobians,
  Hessians) for advanced engineering.
tags:
- calculus
- mathematics
- derivatives
- taylor-series
- optimization
- jacobian
- hessian
cluster: mathematics
title: Differential Calculus
date: '2026-05-03'
hubs:
- MathematicsHub
type: article
status: active
canonical_id: 01KQ0P44MWAYKY5RFMQHXY6HZX
---

# Differential Calculus: A Rigorous Study Guide

Differential calculus is the study of how functions change. While often introduced through basic differentiation rules, its power lies in its ability to model local behavior and optimize complex systems. This guide is intended for those who have completed a college-level sequence and wish to solidify their understanding of the formalisms and theorems that underpin modern analysis.

---

## I. Foundations: The Limit Definition

The derivative of a function $f$ at a point $x$ is defined as the limit of the difference quotient:

$$f'(x) = \lim_{h \to 0} \frac{f(x+h) - f(x)}{h}$$

If this limit exists, $f$ is said to be **differentiable** at $x$. Differentiability implies continuity, but the converse is not true (e.g., $f(x) = |x|$ is continuous but not differentiable at $x=0$).

### 1.1 Higher-Order Derivatives
The $n$-th derivative $f^{(n)}(x)$ represents the rate of change of the $(n-1)$-th derivative.
*   **$f''(x)$ (Concavity):** Indicates whether the function is curving upward ($f'' > 0$, convex) or downward ($f'' < 0$, concave).

---

## II. Fundamental Theorems

### 2.1 Mean Value Theorem (MVT)
If $f$ is continuous on $[a, b]$ and differentiable on $(a, b)$, there exists at least one $c \in (a, b)$ such that:
$$f'(c) = \frac{f(b) - f(a)}{b - a}$$
Geometrically, this means there is a point where the instantaneous rate of change equals the average rate of change over the interval.

### 2.2 Taylor's Theorem
Taylor series allow for the approximation of a differentiable function near a point $a$ using a polynomial:
$$f(x) \approx \sum_{n=0}^{N} \frac{f^{(n)}(a)}{n!} (x - a)^n$$
The **Taylor Remainder Theorem** provides a bound on the error of this approximation, which is critical for [Numerical Methods](NumericalMethods).

---

## III. Multivariable Calculus

In higher dimensions, the derivative is generalized through partial derivatives and vector operators.

### 3.1 The Gradient ($\nabla f$)
For a scalar field $f: \mathbb{R}^n \to \mathbb{R}$, the gradient is the vector of all partial derivatives:
$$\nabla f = \left[ \frac{\partial f}{\partial x_1}, \frac{\partial f}{\partial x_2}, \dots, \frac{\partial f}{\partial x_n} \right]^T$$
The gradient points in the direction of steepest ascent on the function's surface.

### 3.2 The Jacobian Matrix ($\mathbf{J}$)
For a vector-valued function $\mathbf{f}: \mathbb{R}^n \to \mathbb{R}^m$, the Jacobian represents the best linear approximation of the function at a point:
$$\mathbf{J}_{ij} = \frac{\partial f_i}{\partial x_j}$$
The determinant of the Jacobian ($|J|$) is used in change-of-variables for integration and measures local volume transformation.

### 3.3 The Hessian Matrix ($\mathbf{H}$)
The Hessian is the square matrix of second-order partial derivatives of a scalar field:
$$\mathbf{H}_{ij} = \frac{\partial^2 f}{\partial x_i \partial x_j}$$
The Hessian describes the local curvature of the function. In optimization, it is used to determine the nature of critical points (local min/max/saddle) via the **Second Derivative Test** in $n$ dimensions.

---

## IV. Optimization Theory

### 4.1 Necessary Conditions
At a local extremum (minimum or maximum) of a differentiable function, the gradient must be zero:
$$\nabla f(\mathbf{x}^*) = \mathbf{0}$$
These points are called **critical points**.

### 4.2 Sufficient Conditions
To confirm a critical point is a local minimum, the Hessian $\mathbf{H}$ must be **positive definite** (all eigenvalues $> 0$). If it is negative definite, the point is a maximum. If it has both positive and negative eigenvalues, it is a **saddle point**.

---

## V. Computational Considerations

In practice, derivatives are often computed via:
*   **Automatic Differentiation (AD):** Not to be confused with numerical or symbolic differentiation. AD decomposes functions into elementary operations and applies the chain rule systematically, providing exact derivatives at machine precision (used in PyTorch/JAX).
*   **Numerical Differentiation:** Using finite differences (e.g., $f'(x) \approx \frac{f(x+h) - f(x-h)}{2h}$). This is subject to truncation and round-off errors.

---
**See Also:**
- [Applied Math Survey](AppliedMathSurvey) — Contextualizing calculus within broader mathematics.
- [Numerical Methods](NumericalMethods) — Discretizing continuous calculus for computers.
- [Linear Algebra](LinearAlgebra) — The language of multivariable calculus.
- [Mathematics Hub](MathematicsHub) — Central index for all math topics.
