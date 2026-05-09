---
canonical_id: 01KQ12YDW2SSJQ6N3MA8XDETJS
title: Number Theory
type: article
cluster: mathematics
status: active
date: 2025-02-13T00:00:00Z
tags:
- mathematics
- number-theory
- cryptography
- rsa
- ecc
- modular-arithmetic
summary: Advanced Number Theory for cryptographic applications, focusing on prime distribution, modular groups, and the discrete log problem in RSA/ECC.
auto-generated: false
---

# Number Theory: Cryptographic Foundations and Complexity

Number theory, once considered the purest of mathematics, is now the load-bearing infrastructure of global security. This article focuses on the integer properties and modular arithmetic required to implement and audit modern asymmetric cryptography.

## 1. Modular Arithmetic and the Ring $\mathbb{Z}/n\mathbb{Z}$

The core of RSA and ECC is arithmetic modulo $n$.
- **Bézout's Identity:** For any $a, b$, there exist $x, y$ such that $ax + by = \gcd(a, b)$.
- **Modular Inverse:** If $\gcd(a, n) = 1$, then $a$ has an inverse $a^{-1} \pmod n$, found via the **Extended Euclidean Algorithm**.

### Concrete Example: RSA Key Generation
1. Select primes $p=61, q=53$.
2. Compute $n = p \times q = 3233$.
3. Compute $\phi(n) = (p-1)(q-1) = 60 \times 52 = 3120$.
4. Choose $e = 17$ (public exponent).
5. Compute $d = e^{-1} \pmod{\phi(n)} = 17^{-1} \pmod{3120}$. Using Extended Euclidean, we find $d = 2753$.
6. **Encryption:** $c = m^{17} \pmod{3233}$.
7. **Decryption:** $m = c^{2753} \pmod{3233}$.

## 2. Prime Distribution and Primality Testing

The **Prime Number Theorem** states that the density of primes near $x$ is $1/\ln x$. This ensures that picking a random 1024-bit number and checking for primality is computationally cheap.
- **Miller-Rabin Test:** A probabilistic test that identifies composites with error probability $4^{-k}$.
- **Security Implications:** "Dirty" primes (those with specific structures) can weaken RSA. Modern implementations use **Safe Primes** ($p = 2q + 1$).

## 3. The Discrete Logarithm Problem (DLP)

The security of Diffie-Hellman and ECC rests on the difficulty of finding $x$ in $g^x \equiv y \pmod p$.
- **Complexity:** While modular exponentiation is $O(\log x)$, the fastest classical algorithm to reverse it (Index Calculus) is sub-exponential, not polynomial.
- **ECC Advantage:** Elliptic Curve Discrete Log (ECDLP) has no known sub-exponential attack, allowing for 256-bit keys that match 3072-bit RSA security.

## 4. Number Theory in Zero-Knowledge Proofs (ZKP)

Modern ZKPs (like zk-SNARKs) rely on **Quadratic Residues** and **Bilinear Pairings** over finite fields.
- **Legendre Symbol $(\frac{a}{p})$:** Quickly determines if $a$ is a square $\pmod p$.
- **Roots of Unity:** Used in Fast Fourier Transforms (FFT) for polynomial commitment schemes, allowing an agent to prove knowledge of a private key without revealing it.

## Summary Table: Hard Problems in Number Theory

| Problem | Definition | Application |
| :--- | :--- | :--- |
| **Factoring** | Given $n=pq$, find $p, q$ | RSA Encryption |
| **DLP** | Given $g^x \pmod p$, find $x$ | Diffie-Hellman |
| **ECDLP** | Given $xP$ on a curve, find $x$ | ECDSA, Ed25519 |
| **Shortest Vector** | Find shortest vector in a lattice | Post-Quantum (PQC) |

## See Also
- [[AbstractAlgebra]]
- [[EncryptionFundamentals]]
- [[LinearAlgebra]]
- [[QuantumComputing]]
