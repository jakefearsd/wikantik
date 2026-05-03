---
cluster: mathematics
canonical_id: 01KQ0P44PNH5Z7WQQMY43G947G
title: Differential Geometry
type: article
tags:
- mathematics
- differential-geometry
- manifolds
- riemannian-geometry
- relativity
summary: Study of curved spaces using calculus, covering manifolds, tangent spaces, metrics, and curvature.
auto-generated: false
status: active
date: '2026-04-26'
related:
- AppliedMathSurvey
- TopologyMathematics
- CalculusRefreshForCS
- ComplexAnalysis
---

# Differential Geometry

Differential geometry uses the tools of calculus and linear algebra to study the properties of curved spaces, known as manifolds. It provides the mathematical framework for general relativity, gauge theory, and various techniques in manifold learning and robotics.

## 1. Differentiable Manifolds

A manifold $M$ is a topological space that is locally homeomorphic to Euclidean space $\mathbb{R}^n$. For calculus to be performed on $M$, it must be equipped with a **differentiable structure**.

### 1.1 Charts and Atlases
A **chart** $(U, \phi)$ consists of an open set $U \subseteq M$ and a homeomorphism $\phi: U \to V \subseteq \mathbb{R}^n$. An **atlas** is a collection of charts that cover $M$. For the manifold to be $C^k$-differentiable, the transition maps between overlapping charts ($\phi_j \circ \phi_i^{-1}$) must be $C^k$-diffeomorphisms in $\mathbb{R}^n$.

## 2. Tangent Spaces and Vector Fields

At each point $p \in M$, there is an associated vector space $T_pM$ called the **tangent space**.

### 2.1 Tangent Vectors
A tangent vector $v \in T_pM$ can be defined as an equivalence class of curves $\gamma: (-\epsilon, \epsilon) \to M$ passing through $p$, or as a derivation on the algebra of smooth functions $C^\infty(M)$. In local coordinates $\{x^i\}$, a basis for $T_pM$ is given by the partial differential operators $\{\frac{\partial}{\partial x^i}\}$.

### 2.2 Vector Fields
A **vector field** $X$ is a smooth section of the tangent bundle $TM = \bigsqcup_{p \in M} T_pM$, assigning a tangent vector $X_p \in T_pM$ to every point $p$.

## 3. Riemannian Metrics

A **Riemannian metric** $g$ is a smooth assignment of an inner product $g_p: T_pM \times T_pM \to \mathbb{R}$ to each point $p$. This allows for the definition of geometric properties:
*   **Length of a curve:** $L(\gamma) = \int_a^b \sqrt{g(\gamma'(t), \gamma'(t))} dt$.
*   **Angle between vectors:** Defined via the inner product $g(v, w)$.
*   **Volume:** Defined via the volume form derived from the determinant of $g_{ij}$.

## 4. Connections and Curvature

To compare vectors at different points, a manifold requires a **connection** (or covariant derivative) $\nabla$.

### 4.1 Levi-Civita Connection
On a Riemannian manifold, there exists a unique connection $\nabla$ that is symmetric (torsion-free) and compatible with the metric ($Xg(Y, Z) = g(\nabla_X Y, Z) + g(Y, \nabla_X Z)$).

### 4.2 Parallel Transport and Geodesics
A vector $v$ is **parallel transported** along a curve if $\nabla_{\dot{\gamma}} v = 0$. A curve $\gamma$ is a **geodesic** if it parallel transports its own tangent vector: $\nabla_{\dot{\gamma}} \dot{\gamma} = 0$. Geodesics are locally distance-minimizing paths.

### 4.3 Curvature
The **Riemann curvature tensor** $R$ measures the degree to which the second covariant derivatives fail to commute:
$$R(X, Y)Z = \nabla_X \nabla_Y Z - \nabla_Y \nabla_X Z - \nabla_{[X, Y]} Z$$
Sectional curvature, Ricci curvature, and Scalar curvature are various contractions and averages of this tensor.

## 5. Differential Forms

A differential $k$-form is a smooth section of the $k$-th exterior power of the cotangent bundle $\Lambda^k(T^*M)$.
*   **Exterior Derivative ($d$):** Maps $k$-forms to $(k+1)$-forms, satisfying $d^2 = 0$.
*   **Stokes' Theorem:** $\int_M d\omega = \int_{\partial M} \omega$, unifying the fundamental theorems of vector calculus.

## 6. Lie Groups and Lie Algebras

A **Lie group** $G$ is a manifold that also possesses a group structure such that the group operations (multiplication and inversion) are smooth. The **Lie algebra** $\mathfrak{g}$ is the tangent space at the identity $T_eG$, equipped with a Lie bracket $[X, Y]$ representing the infinitesimal group action.

## 7. Applications

### 7.1 Physics
*   **General Relativity:** Spacetime is modeled as a 4D Lorentzian manifold. Gravity is the curvature of this manifold, governed by the Einstein Field Equations: $G_{\mu\nu} + \Lambda g_{\mu\nu} = \kappa T_{\mu\nu}$.
*   **Gauge Theory:** Forces are modeled as connections on fiber bundles over spacetime.

### 7.2 Machine Learning and Robotics
*   **Manifold Learning:** Techniques like Isomap or t-SNE assume high-dimensional data is sampled from a lower-dimensional manifold.
*   **Robotics:** Configuration spaces of robotic systems are typically manifolds (e.g., $SO(3)$ for 3D rotations or $SE(3)$ for rigid body motion).
*   **Information Geometry:** The study of manifolds of probability distributions, where the Fisher information metric defines the Riemannian structure.
