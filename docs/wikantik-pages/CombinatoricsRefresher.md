---
cluster: mathematics
canonical_id: 01KQ0P44NNZ12BPR1P8KBRNWCS
title: Combinatorics Refresher
type: article
tags:
- count
- structur
- set
summary: Technical overview of enumerative combinatorics, generating functions, and structural enumeration.
auto-generated: false
---

# Combinatorial Theory and Enumeration

Combinatorics focuses on the enumeration, structure, and optimization of finite sets. This article defines the fundamental machinery required for advanced counting problems.

## I. Inclusion-Exclusion and Möbius Inversion

### A. The Principle of Inclusion-Exclusion (PIE)
For a finite set $S$ and a collection of subsets $\{A_1, A_2, \ldots, A_m\}$, the size of their union is:

$$\left| \bigcup_{i=1}^{m} A_i \right| = \sum_{i} |A_i| - \sum_{i<j} |A_i \cap A_j| + \sum_{i<j<k} |A_i \cap A_j \cap A_k| - \cdots + (-1)^{m-1} |A_1 \cap \cdots \cap A_m|$$

### B. Möbius Inversion Formula
PIE is a specific case of Möbius inversion on a partially ordered set (poset) $(P, \le)$. If $f, g: P \to \mathbb{Z}$ satisfy $g(x) = \sum_{y \le x} f(y)$, then:

$$f(x) = \sum_{y \le x} g(y) \mu(y, x)$$

where $\mu$ is the Möbius function of the poset. For the subset lattice, $\mu(A, B) = (-1)^{|B|-|A|}$.

---

## II. Generating Functions

Generating functions encode sequences into formal power series, allowing combinatorial problems to be solved algebraically.

### A. Ordinary Generating Functions (OGFs)
Used for unlabeled structures: $A(x) = \sum_{n=0}^{\infty} a_n x^n$.
*   **Integer Partitions:** The OGF for $p(n)$, the number of ways to partition $n$, is:
    $$P(x) = \prod_{k=1}^{\infty} \frac{1}{1-x^k}$$

### B. Exponential Generating Functions (EGFs)
Used for labeled structures: $E(x) = \sum_{n=0}^{\infty} a_n \frac{x^n}{n!}$.
*   **The Exponential Formula:** If $\mathcal{C}$ is a class of connected labeled structures with EGF $C(x)$, the EGF for the class of all structures (sets of components) $\mathcal{E}$ is:
    $$E(x) = \exp(C(x))$$

### C. Convolution Theorem
The product of two EGFs, $E(x) = A(x)B(x)$, yields coefficients:
$$c_n = \sum_{k=0}^{n} \binom{n}{k} a_k b_{n-k}$$

---

## III. Structural Enumeration

### A. Polya Enumeration Theorem (PET)
PET counts distinct colorings of a set under the action of a permutation group $G$. The number of distinct colorings using $m$ colors is:
$$N = Z(G; m, m, \ldots, m)$$
where $Z(G)$ is the cycle index polynomial:
$$Z(G; x_1, x_2, \ldots) = \frac{1}{|G|} \sum_{g \in G} \prod_{k=1}^n x_k^{c_k(g)}$$
and $c_k(g)$ is the number of cycles of length $k$ in permutation $g$.

### B. Extremal Set Theory
*   **Sperner's Theorem:** The maximum size of an antichain in $\mathcal{P}([n])$ is $\binom{n}{\lfloor n/2 \rfloor}$.
*   **Erdős–Ko–Rado Theorem:** If $\mathcal{F}$ is an intersecting family of $r$-subsets of $[n]$ and $n \ge 2r$, then $|\mathcal{F}| \le \binom{n-1}{r-1}$.

---

## IV. Asymptotic Methods

For large $n$, the coefficients $a_n$ are estimated using the **Saddle Point Method**. If $A(x)$ has a singularity at $x=R$, the growth is typically:
$$a_n \sim C \cdot R^{-n} \cdot n^{\alpha}$$
This is derived by applying Cauchy's Integral Formula and deforming the integration contour around the singularity in the complex plane.
