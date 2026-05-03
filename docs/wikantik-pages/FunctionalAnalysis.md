---
cluster: mathematics
canonical_id: 01KQ0P44QJ5QH64QB4M0V4WW1Q
title: Functional Analysis
type: article
tags:
- mathematics
- functional-analysis
- operator-theory
- banach-space
- hilbert-space
summary: Analysis of infinite-dimensional vector spaces, linear operators, and spectral theory.
auto-generated: false
---

# Functional Analysis and Operator Theory

Functional analysis is the study of vector spaces endowed with a topology, typically infinite-dimensional, and the linear mappings between them. Operator theory focuses on the properties of these mappings (operators), particularly their spectral characteristics.

## I. Topological Vector Spaces (TVS)

A topological vector space $X$ over a field $\mathbb{K}$ ($\mathbb{R}$ or $\mathbb{C}$) is a vector space with a topology such that vector addition and scalar multiplication are continuous.

### 1.1 Hierarchy of Spaces

The most common spaces used in analysis follow a specific hierarchy:
$$\text{Hilbert Space} \subset \text{Banach Space} \subset \text{Normed Space} \subset \text{TVS}$$

1.  **Normed Space:** A vector space $X$ with a norm $\|\cdot\|$ where the topology is induced by the metric $d(x, y) = \|x-y\|$.
2.  **Banach Space:** A normed space that is complete (every Cauchy sequence converges in $X$).
3.  **Hilbert Space:** A Banach space where the norm is induced by an inner product: $\|x\| = \sqrt{\langle x, x \rangle}$.

## II. Bounded Linear Operators

An operator $T: X \to Y$ is **bounded** if there exists $M > 0$ such that $\|Tx\|_Y \le M\|x\|_X$ for all $x \in X$. For linear operators between normed spaces, boundedness is equivalent to continuity.

The space of all bounded linear operators $\mathcal{B}(X, Y)$ is a Banach space under the operator norm:
$$\|T\| = \sup_{x \neq 0} \frac{\|Tx\|_Y}{\|x\|_X}$$

### 2.1 Fundamental Theorems

*   **Open Mapping Theorem:** A surjective bounded linear operator between Banach spaces is an open map.
*   **Closed Graph Theorem:** A linear operator between Banach spaces is bounded if and only if its graph is closed.
*   **Uniform Boundedness Principle (Banach-Steinhaus):** A pointwise bounded family of bounded linear operators on a Banach space is uniformly bounded.

## III. Hilbert Space Theory

Hilbert spaces permit geometric concepts like orthogonality.

### 3.1 Riesz Representation Theorem
Every continuous linear functional $f$ on a Hilbert space $\mathcal{H}$ can be represented as an inner product: $f(x) = \langle x, y \rangle$ for a unique $y \in \mathcal{H}$.

### 3.2 Adjoint Operators
For $T \in \mathcal{B}(\mathcal{H})$, the adjoint $T^*$ is the unique operator satisfying $\langle Tx, y \rangle = \langle x, T^*y \rangle$.
*   **Self-Adjoint:** $T = T^*$
*   **Unitary:** $U^*U = UU^* = I$
*   **Normal:** $T^*T = TT^*$

## IV. Spectral Theory

The spectrum $\sigma(T)$ generalizes the set of eigenvalues.

### 4.1 The Spectrum
For $T \in \mathcal{B}(X)$, $\sigma(T) = \{ \lambda \in \mathbb{C} : T - \lambda I \text{ is not invertible in } \mathcal{B}(X) \}$.
The spectrum is a non-empty, compact subset of $\mathbb{C}$. It decomposes into:
1.  **Point Spectrum:** Eigenvalues ($\lambda I - T$ is not injective).
2.  **Continuous Spectrum:** $\lambda I - T$ is injective with dense but not surjective range.
3.  **Residual Spectrum:** $\lambda I - T$ is injective with range that is not dense.

### 4.2 The Spectral Theorem
For a bounded self-adjoint operator $T$ on a Hilbert space $\mathcal{H}$, there exists a unique projection-valued measure $E$ such that:
$$T = \int_{\sigma(T)} \lambda \, dE(\lambda)$$

## V. Operator Classes

### 5.1 Compact Operators
An operator is compact if it maps bounded sets to pre-compact sets. Compact operators $T \in \mathcal{K}(\mathcal{H})$ behave similarly to finite-rank matrices:
*   $0$ is the only possible limit point of $\sigma(T)$.
*   Every non-zero $\lambda \in \sigma(T)$ is an eigenvalue of finite multiplicity.

### 5.2 Fredholm Operators
An operator $T$ is Fredholm if its kernel and cokernel are finite-dimensional and its range is closed. The index is defined as:
$$\text{ind}(T) = \text{dim}(\text{ker } T) - \text{dim}(\text{coker } T)$$

## VI. Operator Algebras

A $C^*$-algebra is a Banach algebra $\mathcal{A}$ with an involution $*$ satisfying $\|A^*A\| = \|A\|^2$.
The **Gelfand-Naimark Theorem** states that any commutative $C^*$-algebra is isometrically $*$-isomorphic to $C_0(S)$ for some locally compact Hausdorff space $S$. Every $C^*$-algebra is isometrically $*$-isomorphic to a closed subalgebra of $\mathcal{B}(\mathcal{H})$.
