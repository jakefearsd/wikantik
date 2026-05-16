---
canonical_id: 01KRPNNV2JWFYC22G9MYATZ0M7
title: Hash Functions and Cryptographic Hashing
tags:
- hash-functions
- cryptography
- data-structures
- bloom-filters
- algorithms
date: '2026-05-15'
summary: A technical dive into hash functions, covering avalanche effect, collision
  resistance, non-cryptographic hashes (MurmurHash, xxHash), and applications in Bloom
  Filters.
status: published
cluster: computer-science-foundations
type: article
---

# Hash Functions and Cryptographic Hashing

A **Hash Function** is a mathematical algorithm that maps data of arbitrary size to a fixed-size bit string (a hash value, digest, or simply a hash). They are the fundamental building blocks of modern computer science, powering everything from hash tables to blockchain ledgers.

## 1. Core Properties

A robust hash function must satisfy several critical properties:

*   **Determinism:** The same input must always produce the same output.
*   **Uniformity:** Outputs should be evenly distributed across the available hash space to minimize collisions.
*   **Avalanche Effect:** A tiny change in the input (even a single bit) should radically change the output digest. This ensures the output is unpredictable.
*   **Speed:** It should be computationally efficient to generate a hash.

## 2. Cryptographic vs. Non-Cryptographic

Hash functions bifurcate into two distinct categories based on their security guarantees.

### Cryptographic Hash Functions
Designed to withstand adversarial attacks. They prioritize security over raw speed and enforce:
*   **Pre-image Resistance:** Given a hash `h`, it is computationally infeasible to find an input `m` such that `hash(m) = h`.
*   **Collision Resistance:** It is infeasible to find two different inputs `m1` and `m2` that hash to the same output.
*   *Examples:* SHA-256, SHA-3, BLAKE3.

### Non-Cryptographic Hash Functions
Designed for raw speed and excellent distribution, but not security. They are vulnerable to intentional collision generation (HashDoS attacks).
*   **Use Cases:** Hash tables, caching, [Bloom Filters](BloomFilters).
*   *Examples:* MurmurHash3, xxHash, CityHash.

## 3. Application: Bloom Filters

One of the most elegant applications of non-cryptographic hash functions is the **Bloom Filter**—a space-efficient probabilistic data structure. 

By passing an element through $k$ different hash functions and setting the corresponding bits in a bit array, a Bloom Filter can quickly determine set membership. It guarantees no false negatives (if it says an item is not present, it definitely isn't) but allows for a tunable rate of false positives. This makes them ideal for database query optimization and caching layers.
