---
canonical_id: 01KQ12YDW2SSJQ6N3MA8XDETJS
title: Number Theory
type: article
cluster: mathematics
status: active
date: '2026-04-25'
tags:
- number-theory
- cryptography
- mathematics
- prime-numbers
- modular-arithmetic
summary: The pieces of number theory that show up in cryptography and computing —
  primes, modular arithmetic, the discrete log problem, and how RSA and ECC
  actually work.
related:
- AbstractAlgebra
- EncryptionFundamentals
- LinearAlgebra
- BayesianReasoning
hubs:
- Mathematics Hub
---
# Number Theory

Number theory is the study of integers and their relationships — primes, divisibility, congruences, factorisation. It used to be called the purest of pure mathematics, with no practical applications. Then the 1970s happened: public-key cryptography turned number theory into the load-bearing infrastructure of secure communication.

This page is what an engineer needs to know to read crypto papers, understand why a given algorithm is hard, and not embarrass themselves at a security review.

## The objects

**Integers** `Z`: `..., -2, -1, 0, 1, 2, ...`. Arithmetic: addition, multiplication, division (with remainder), comparison. The starting point.

**Primes** `p`: integers > 1 with no positive divisors other than 1 and themselves. 2, 3, 5, 7, 11, 13, ... Infinitely many (Euclid's theorem; the proof is two lines and worth knowing).

**Modular integers** `Z/nZ` (often written `Z_n`): integers mod `n`. Arithmetic done modulo `n`. The fundamental object of computational number theory.

**Multiplicative group** `(Z/nZ)*`: the elements of `Z/nZ` with a multiplicative inverse. For prime `p`, `(Z/pZ)* = {1, 2, ..., p-1}`. Cryptographically central.

## The handful of theorems that matter

### Euclidean algorithm

`gcd(a, b)` computed by repeated remainder: `gcd(a, b) = gcd(b, a mod b)` until `b = 0`. `O(log min(a, b))` operations. The basis of essentially everything else.

**Extended Euclidean** finds `x, y` such that `ax + by = gcd(a, b)`. This is how you compute modular inverses: if `gcd(a, n) = 1`, the extended algorithm gives you `a^(-1) mod n`.

```
extended_gcd(a, b) -> (g, x, y) with g = gcd(a, b), ax + by = g
```

Modular inverse of `a` mod `n`: run extended-Euclidean on `(a, n)`; if `gcd ≠ 1`, no inverse; else `x mod n` is the inverse.

### Fermat's little theorem

For prime `p` and integer `a` not divisible by `p`: `a^(p-1) ≡ 1 (mod p)`.

Direct consequence: `a^(p-2) ≡ a^(-1) (mod p)` — modular inverse via fast exponentiation. Useful when you need many inverses.

### Euler's theorem and `φ(n)`

Generalises Fermat. Euler's totient `φ(n)` counts integers in `[1, n]` coprime to `n`. For prime `p`, `φ(p) = p - 1`. For `n = pq` with primes `p ≠ q`, `φ(n) = (p-1)(q-1)`.

Euler's theorem: `a^φ(n) ≡ 1 (mod n)` when `gcd(a, n) = 1`. The basis of RSA's correctness proof.

### Chinese Remainder Theorem (CRT)

If `m, n` are coprime, then for any `a, b`, there's a unique `x mod mn` with `x ≡ a (mod m)` and `x ≡ b (mod n)`. Constructive: explicit formula for `x` from `a, b, m, n`.

Practical use: computing `x mod (pq)` is faster as `x mod p` and `x mod q` separately, then combining via CRT. Used in RSA private-key operations to speed decryption ~4×.

### Fundamental theorem of arithmetic

Every integer > 1 has a unique factorisation into primes (up to ordering).

This is what makes "factor a 2048-bit RSA modulus" hard — there's only one factorisation, but finding it is computationally infeasible at this size.

### Primality testing vs factoring

Two distinct problems with very different difficulty:

- **Is `n` prime?** Polynomial-time (AKS algorithm in 2002, but probabilistic Miller-Rabin is overwhelmingly used in practice — fast and correct with arbitrarily high probability). Tractable for thousands-of-digit numbers.
- **Factor `n` into primes.** No polynomial-time classical algorithm known. Best classical: General Number Field Sieve, sub-exponential. Quantum: Shor's algorithm in polynomial time (if a sufficiently large quantum computer existed, RSA breaks).

This asymmetry is *why* RSA works: generating primes is fast, but factoring their product is hard.

## How RSA actually works

The construction:

1. Pick two large primes `p, q` (typically 1024-bit each for 2048-bit RSA).
2. `n = pq` and `φ(n) = (p-1)(q-1)`.
3. Pick a public exponent `e` coprime to `φ(n)` (commonly `e = 65537`).
4. Compute `d = e^(-1) mod φ(n)` via extended Euclidean.
5. Public key: `(n, e)`. Private key: `(n, d)`.

Encryption: `c = m^e mod n`. Decryption: `m = c^d mod n`.

Why it works: `c^d = m^(ed) ≡ m^(1 + kφ(n)) ≡ m * (m^φ(n))^k ≡ m * 1^k ≡ m (mod n)` by Euler's theorem.

Security: an attacker who could compute `d` would need to find `φ(n)`, which requires factoring `n`. As long as factoring stays hard for 2048-bit numbers (currently it does), RSA is secure.

The "as long as" is the post-quantum concern. A sufficiently large quantum computer using Shor's algorithm breaks this — hence post-quantum cryptography becoming non-optional planning by late 2020s.

## How elliptic-curve crypto works (briefly)

Elliptic-curve cryptography (ECC) lives in the group of points on an elliptic curve over a finite field. The discrete log problem on these groups is harder than on `(Z/pZ)*` for the same key size.

Practical consequence: 256-bit ECC keys give roughly the same security as 3072-bit RSA. Smaller, faster, lower bandwidth. ECC is the default for new asymmetric cryptography in 2026; RSA persists for backward compatibility.

The math is more involved (group law on the curve, point addition, scalar multiplication), but the underlying hard problem is the same shape — discrete log is hard.

## Discrete log and Diffie-Hellman

In a cyclic group `G` with generator `g`, the **discrete log problem** is: given `g, g^x ∈ G`, find `x`.

For `G = (Z/pZ)*` with prime `p`, this is intractable for sufficiently large `p` classically. (Quantum: also broken by Shor.)

**Diffie-Hellman** uses this:

1. Public: prime `p`, generator `g`.
2. Alice picks secret `a`, sends `g^a mod p`.
3. Bob picks secret `b`, sends `g^b mod p`.
4. Both compute `(g^a)^b = (g^b)^a = g^(ab) mod p`.

Eavesdropper sees `g, g^a, g^b` but solving for `ab` requires solving discrete log. Shared secret `g^(ab)` is computable by Alice and Bob but not the attacker.

ECDH is the same construction over an elliptic curve group; that's the version every modern TLS handshake uses.

## What you actually need from number theory in code

For practical crypto code:

- Big-integer arithmetic libraries that handle modular exponentiation efficiently. Use the standard library's `pow(a, b, n)` (Python), `BigInteger.modPow` (Java), `bigint` modular ops in Rust crates, etc. Don't implement these yourself.
- Constant-time implementations to avoid timing side-channels. The standard libraries usually handle this; verify before relying on it.
- Random number generation backed by the OS's CSPRNG. Never reach for `Math.random()` for crypto.

For implementations beyond crypto:

- Modular hashing (CRC, hash table sizing).
- RNG state spaces (linear congruential generators are number-theoretic; modern CSPRNGs are not).
- Codes (Reed-Solomon over finite fields uses both number theory and abstract algebra).

## A few non-crypto applications

- **Hashing.** Cuckoo hashing, perfect hashing, universal hashing — number theory underlies the analysis.
- **Sorting analysis.** Average-case bounds on radix sort use distributional arguments rooted in number theory.
- **Random number generation.** PRNGs based on linear congruences live entirely in modular arithmetic.
- **Algorithmic geometry / computer graphics.** Modular arithmetic for exact predicates avoids floating-point issues.
- **Combinatorial analysis.** Counting problems, generating functions, Pólya enumeration — number theory adjacent.

## What you don't need to know

Number theory has many beautiful directions that don't pay off for engineers:

- Analytic number theory (proving the prime number theorem, distribution of primes).
- Algebraic number theory (rings of integers in number fields, ideals).
- Riemann zeta function, L-functions.

These are foundational for research-level crypto and post-quantum work but not for application engineers.

## Books worth reading

- **Silverman, "A Friendly Introduction to Number Theory"** — the gentle entry, problem-driven.
- **Hardy & Wright, "An Introduction to the Theory of Numbers"** — classical reference.
- **Galbraith, "Mathematics of Public Key Cryptography"** — applied focus, covers RSA, ECC, lattice-based PQC. The right pick for crypto engineers.
- **Cohen, "A Course in Computational Algebraic Number Theory"** — the algorithmic side at depth.

## Further reading

- [AbstractAlgebra] — adjacent and overlapping
- [EncryptionFundamentals] — number theory applied
- [LinearAlgebra] — when crypto goes lattice-based
- [BayesianReasoning] — for probabilistic primality testing
