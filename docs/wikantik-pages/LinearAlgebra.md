---
related:
- AbstractAlgebra
- DifferentialCalculus
- NumericalMethods
- MathematicsHub
summary: A rigorous study guide for Linear Algebra — covering vector spaces, linear
  transformations, the four fundamental subspaces, and the Spectral Theorem for engineering
  and research.
tags:
- linear-algebra
- matrices
- eigenvalues
- vector-spaces
- spectral-theorem
cluster: mathematics
title: Linear Algebra
date: '2026-05-03'
hubs:
- MathematicsHub
type: article
status: active
canonical_id: 01KQ12YDVK5NJ6W7MF9G57GKPQ
---

# Linear Algebra: A Rigorous Study Guide

Linear algebra is the study of vectors, linear transformations, and the spaces they inhabit. While its computational utility in machine learning is undeniable, a deep understanding requires moving beyond matrix manipulation to the underlying geometry and structural theorems. This guide is designed for those looking to regain core skills at a college-senior or graduate level.

---

## I. Vector Spaces and Linear Transformations

### 1.1 Formal Definition
A **Vector Space** $V$ over a field $F$ (usually $\mathbb{R}$ or $\mathbb{C}$) is a set closed under addition and scalar multiplication, satisfying eight axioms (associativity, commutativity, identity, etc.).

### 1.2 Basis and Dimension
*   **Linear Independence:** A set of vectors is independent if no vector can be written as a linear combination of the others.
*   **Basis:** A set of linearly independent vectors that span the space $V$.
*   **Dimension:** The number of vectors in any basis of $V$.

### 1.3 Change of Basis
If we have a vector $\mathbf{x}$ in basis $B$ and wish to find its coordinates in basis $C$, we use a **Transition Matrix** $\mathbf{P}$:
$$[\mathbf{x}]_C = \mathbf{P}_{C \leftarrow B} [\mathbf{x}]_B$$
This concept is fundamental to understanding how diagonalizing a matrix is simply finding a basis where the transformation acts as a simple scaling along axes.

---

## II. The Four Fundamental Subspaces

For any $m \times n$ matrix $\mathbf{A}$, there are four critical subspaces that define its behavior:

1.  **Column Space (Range), $C(\mathbf{A})$:** The span of the columns. Dimension = rank($\mathbf{A}$).
2.  **Nullspace (Kernel), $N(\mathbf{A})$:** The set of all $\mathbf{x}$ such that $\mathbf{A}\mathbf{x} = \mathbf{0}$. Dimension = $n - \text{rank}(\mathbf{A})$.
3.  **Row Space, $C(\mathbf{A}^T)$:** The span of the rows. Dimension = rank($\mathbf{A}$).
4.  **Left Nullspace, $N(\mathbf{A}^T)$:** The set of all $\mathbf{y}$ such that $\mathbf{A}^T\mathbf{y} = \mathbf{0}$. Dimension = $m - \text{rank}(\mathbf{A})$.

**The Fundamental Theorem of Linear Algebra:** The column space is orthogonal to the left nullspace, and the row space is orthogonal to the nullspace.

---

## III. Eigendecomposition and the Spectral Theorem

### 3.1 Eigenvalues and Eigenvectors
For square $\mathbf{A}$, $\mathbf{A}\mathbf{v} = \lambda \mathbf{v}$. The scalars $\lambda$ are found via the characteristic equation $\det(\mathbf{A} - \lambda \mathbf{I}) = 0$.

### 3.2 Diagonalization
A matrix is diagonalizable if there exists an invertible $\mathbf{P}$ such that $\mathbf{A} = \mathbf{P}\mathbf{D}\mathbf{P}^{-1}$, where $\mathbf{D}$ is a diagonal matrix of eigenvalues. This is possible if and only if $\mathbf{A}$ has $n$ linearly independent eigenvectors.

### 3.3 The Spectral Theorem
One of the most powerful results in linear algebra: **Every real symmetric matrix is orthogonally diagonalizable.**
$$\mathbf{A} = \mathbf{Q}\mathbf{D}\mathbf{Q}^T$$
Where $\mathbf{Q}$ is an orthogonal matrix ($\mathbf{Q}^T\mathbf{Q} = \mathbf{I}$). This means the eigenvectors are not just independent, but mutually perpendicular.

---

## IV. Singular Value Decomposition (SVD)

The SVD generalizes eigendecomposition to *any* matrix $\mathbf{A}$ (including non-square and singular):
$$\mathbf{A} = \mathbf{U} \mathbf{\Sigma} \mathbf{V}^T$$
*   $\mathbf{U}$: Orthonormal eigenvectors of $\mathbf{A}\mathbf{A}^T$.
*   $\mathbf{V}$: Orthonormal eigenvectors of $\mathbf{A}^T\mathbf{A}$.
*   $\mathbf{\Sigma}$: Diagonal matrix of singular values ($\sigma_i = \sqrt{\lambda_i}$).

The SVD provides the most robust way to compute the **rank**, the **pseudo-inverse**, and the **best low-rank approximation** of a matrix.

---

## V. Inner Product Spaces

By adding an **Inner Product** $\langle \mathbf{u}, \mathbf{v} \rangle$, we gain the ability to measure lengths (norms) and angles.
*   **Orthogonality:** $\langle \mathbf{u}, \mathbf{v} \rangle = 0$.
*   **Projections:** The projection of $\mathbf{b}$ onto the span of $\mathbf{A}$ is $\mathbf{p} = \mathbf{A}(\mathbf{A}^T\mathbf{A})^{-1}\mathbf{A}^T \mathbf{b}$. This is the foundation of the **Least Squares** method.

---
**See Also:**
- [Differential Calculus](DifferentialCalculus) — Where linear algebra meets local function approximation.
- [Numerical Methods](NumericalMethods) — Solving large-scale linear systems efficiently.
- [Abstract Algebra](AbstractAlgebra) — Generalizing the concept of spaces and operations.
- [Mathematics Hub](MathematicsHub) — Central index for all math topics.
