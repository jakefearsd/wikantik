---
canonical_id: 01KQ12YDVK5NJ6W7MF9G57GKPQ
title: Linear Algebra
type: article
cluster: mathematics
status: active
date: '2026-04-25'
tags:
- linear-algebra
- matrices
- eigenvalues
- ml-foundations
- numerical
summary: Linear algebra for working engineers — the operations and decompositions
  that show up in machine learning, graphics, and search, with the numerical
  pitfalls libraries hide until they don't.
related:
- AbstractAlgebra
- DifferentialGeometry
- BayesianReasoning
- GradientDescentAndOptimizers
- EmbeddingsVectorDB
hubs:
- Mathematics Hub
---
# Linear Algebra

Linear algebra is the math of vectors, matrices, and the linear maps between them. It's also the dominant computational substrate of modern machine learning, computer graphics, search, signal processing, and quantum computing. Knowing it well is the difference between using PyTorch and understanding what it's doing.

This page is the working engineer's view: which operations and decompositions matter, what they compute, and where the numerical issues bite.

## The objects you actually use

- **Vector** — an ordered tuple. In ML, an embedding. In graphics, a point or direction. Operations: addition, scalar multiplication, dot product, norm.
- **Matrix** — a 2D array of numbers. Represents a linear map (or a system of equations, or a 2D dataset). Operations: matrix-vector multiply, matrix-matrix multiply, transpose, inverse, decomposition.
- **Tensor** — N-dimensional generalisation. The atomic data type in deep learning. Mostly: stacks of matrices.

You will spend 95% of your time on vectors and matrices. Tensors come into play for batched operations.

## Operations that show up everywhere

**Dot product.** `a · b = Σ a_i b_i`. Geometrically: `|a| |b| cos(θ)`. Cosine similarity (the basis of vector search) is the dot product of unit vectors.

**Matrix-vector multiply.** `Av`. Applies the linear map `A` to the vector `v`. The bedrock of any neural network forward pass.

**Matrix-matrix multiply.** `AB`. Composing linear maps. Computational cost `O(n³)` naively; modern BLAS gets within constants of `O(n^2.37)` for huge matrices via Strassen-like algorithms — and on GPUs the practical bound is whatever can be packed into fused matmul kernels.

**Transpose.** `A^T`. Swaps rows and columns. `(AB)^T = B^T A^T` — a fact that surprises people more often than it should.

**Inverse.** `A^(-1) A = I`. Almost never compute it explicitly in numerical code; solve `Ax = b` directly with `numpy.linalg.solve` or equivalent. Computing the inverse and multiplying is slower and less numerically stable.

**Determinant.** A scalar that's zero when the matrix is singular (not invertible). Used in change of variables, but rarely directly in ML — the log-determinant `log|det A|` shows up in normalising flows.

## Norms

Norms measure size. The ones that matter:

- **L2 (Euclidean):** `||v||_2 = sqrt(Σ v_i²)`. Default norm. Geometric distance.
- **L1 (Manhattan / taxicab):** `||v||_1 = Σ |v_i|`. Used in regularisation that promotes sparsity.
- **L∞ (max):** `||v||_∞ = max |v_i|`. Worst-case bounds.
- **Frobenius:** `||A||_F = sqrt(Σ a_ij²)`. L2 norm of a matrix viewed as a flat vector.
- **Spectral / operator:** `||A||_2 = σ_max(A)`. Largest singular value. The matrix norm consistent with the L2 vector norm.

In ML loss functions, L2 is overwhelmingly the default; L1 makes occasional appearances for sparsity (Lasso regression).

## The decompositions worth knowing

### Eigendecomposition

For square `A`: find `v, λ` such that `Av = λv`. The eigenvectors are directions that `A` only stretches, never rotates; eigenvalues are the stretch factors.

Useful when:
- Analysing the dynamics of a linear system (`A^k v` blows up if any `|λ| > 1`).
- PCA on a covariance matrix.
- Spectral methods on graphs (the Laplacian's eigenvectors).

Limitation: only real-symmetric matrices are guaranteed real eigenvalues. General matrices can have complex ones; numerical stability suffers.

### Singular Value Decomposition (SVD)

`A = U Σ V^T` where `U, V` are orthogonal and `Σ` is diagonal with non-negative entries (singular values).

The Swiss-army knife. Works for any matrix (any shape, any rank). The decomposition reveals:

- **Rank** — number of non-zero singular values.
- **Best low-rank approximation** — keep top-k singular values, throw the rest away. Optimal in Frobenius norm. The basis of dimensionality reduction.
- **Pseudo-inverse** — `A^+ = V Σ^+ U^T` (with `Σ^+` having reciprocals of non-zero entries). Works for non-square or rank-deficient matrices.
- **PCA** — the singular vectors of a centred data matrix are the principal components.

Computational cost is `O(min(mn², m²n))` — expensive but well-implemented in BLAS. For huge matrices, use truncated SVD (compute only the top-k singular values; far cheaper).

### QR decomposition

`A = QR` where `Q` is orthogonal, `R` is upper triangular.

Used to:
- Solve `Ax = b` more numerically stably than direct inversion.
- Compute least-squares solutions when `A` is tall.
- Form the Gram-Schmidt orthogonalisation.

The first algorithm `numpy.linalg.lstsq` reaches for. Stable, predictable, well-implemented.

### Cholesky decomposition

For symmetric positive-definite `A`: `A = LL^T` where `L` is lower triangular.

Specifically for solving `Ax = b` when `A` is SPD. Faster than LU or QR; the right tool for inversion of covariance matrices, kernel methods.

## Solving Ax = b: pick the right tool

| Matrix shape | Tool |
|---|---|
| Square, well-conditioned | `numpy.linalg.solve` (LU under the hood) |
| Square, SPD | Cholesky (`scipy.linalg.cho_solve`) |
| Tall (more rows than cols) | Least-squares via QR (`numpy.linalg.lstsq`) |
| Wide (more cols than rows) | Min-norm solution via SVD |
| Singular | SVD-based pseudo-inverse |
| Sparse, large | Iterative (CG for SPD, GMRES for general) |

Don't compute the inverse. The standard `solve` calls are 2-3× faster and more numerically stable than `inv(A) @ b`.

## Numerical stability: where libraries protect you and where they don't

Floating-point matmul is associative *in math* but not in floating-point arithmetic. Different orderings give slightly different answers. Most of the time you don't care; sometimes you do.

Pitfalls that bite:

- **Catastrophic cancellation.** Subtracting nearly-equal numbers loses precision. `a^T a - b^T b` is not the same as `(a-b)^T(a+b)` numerically.
- **Ill-conditioned matrices.** A matrix where the ratio of largest to smallest singular value is huge — `Ax = b` becomes hyper-sensitive to tiny changes in `b`. Check `numpy.linalg.cond(A)`; values >> 10⁶ are warning signs.
- **Loss of orthogonality.** Repeated multiplications of "almost orthogonal" matrices drift; periodic re-orthogonalisation may be needed.
- **Mixed precision.** Matmul in float16 saves memory and time on modern GPUs but accumulates rounding errors. For training, fine; for some scientific computations, problematic.

In practice: use double-precision for scientific work, single-precision for ML training, mixed precision (FP16 or BF16 with FP32 accumulation) for production ML inference. Know which you're using.

## The geometric view, briefly

Internalising the geometry pays off:

- A matrix-vector product `Av` rotates and stretches `v`. SVD says: it's a rotation, then axis-aligned scale, then another rotation.
- Eigenvectors are special: they're directions that don't rotate.
- A linear map between vector spaces preserves zero, addition, and scalar multiplication. Almost no real-world transformation is purely linear, but many are *approximately* linear locally — and that's why linear algebra is everywhere in calculus-on-manifolds and ML gradients.
- Embedding spaces are vector spaces. Cosine similarity is geometry. The reason RAG works is that semantically similar text lands at small angles in embedding space.

## Practical computational stacks

- **`numpy`** — single-machine, single-precision or double, CPU. The standard.
- **`scipy.linalg`** — extra routines (Cholesky, banded matrices, sparse).
- **`torch`** — full ML framework; CPU and GPU; autograd.
- **`jax.numpy`** — same API as numpy with autograd, JIT, and device portability.
- **BLAS / LAPACK** — the actual numerical kernels under all of the above. Optimised by Intel MKL, OpenBLAS, Apple Accelerate, etc.

For prototyping, use numpy. For production ML, torch or jax. For HPC, learn what BLAS routine your library is calling and tune accordingly.

## Where this shows up in practice

- **Neural networks** — every forward pass is a chain of matmuls and element-wise nonlinearities.
- **Embeddings / retrieval** — cosine similarity = normalised dot product. See [EmbeddingsVectorDB].
- **PCA, t-SNE, UMAP** — dimensionality reduction; SVD or eigendecomposition under the hood.
- **Graphics** — every transformation is a matrix; perspective projection is a matrix.
- **Recommender systems** — collaborative filtering as low-rank matrix factorisation.
- **Quantum computing** — gates are unitary matrices.

Linear algebra is the most-used college math in industry by a wide margin.

## Further reading

- [AbstractAlgebra] — the algebraic structures linear algebra is a special case of
- [DifferentialGeometry] — calculus on curved versions of vector spaces
- [BayesianReasoning] — covariance matrices everywhere
- [GradientDescentAndOptimizers] — calculus + linear algebra in ML training
- [EmbeddingsVectorDB] — linear algebra at scale for retrieval
