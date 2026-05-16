---
cluster: data-structures
canonical_id: 01KQ0P44X1DSX621T62H1GPJ9A
title: String Matching Algorithms
type: article
tags:
- algorithms
- kmp
- boyer-moore
- string-matching
summary: A technical comparison of the Knuth-Morris-Pratt (KMP) and Boyer-Moore string matching algorithms, detailing their internal mechanics and worst-case performance guarantees.
auto-generated: false
date: 2024-05-16
---
# String Matching: Beyond the Naive Approach

Finding a pattern $P$of length$M$within a text$T$of length$N$is a fundamental problem. While the naive approach takes$O(N \cdot M)$, optimized algorithms achieve linear or sub-linear performance by exploiting the internal structure of the pattern.

## 1. Knuth-Morris-Pratt (KMP)

KMP avoids re-scanning characters by pre-processing the pattern to find the **Longest Proper Prefix which is also a Suffix (LPS)**.

### 1.1 The LPS Array
For every position$i$in$P$,$LPS[i]$stores the length of the longest proper prefix of$P[0 \dots i]$that is also a suffix of$P[0 \dots i]$.

**Example:**$P = \text{"ABABC"}$*   `A`: 0
*   `AB`: 0
*   `ABA`: 1 (`A` matches `A`)
*   `ABAB`: 2 (`AB` matches `AB`)
*   `ABABC`: 0

### 1.2 The Search Logic
When a mismatch occurs at$P[j]$and$T[i]$, we do not reset$i$. Instead, we use the LPS array to "shift" the pattern:$j = LPS[j-1]$. This ensures we never back-track the text pointer, guaranteeing **$O(N+M)$** time complexity.

## 2. Boyer-Moore

Boyer-Moore is often faster than KMP in practice because it frequently skips large sections of the text. It scans the pattern from **right to left**.

### 2.1 The Bad Character Heuristic
When a mismatch occurs at$T[i] = c$:
*   If$c$is not in the pattern, we shift the pattern past$i$.
*   If$c$is in the pattern, we shift the pattern to align the rightmost occurrence of$c$in$P$with$T[i]$.

### 2.2 The Good Suffix Heuristic
If a suffix of the pattern has already matched before the mismatch, we shift the pattern to align that matched suffix with its next occurrence in the pattern.

### 2.3 Performance
*   **Average Case:**$O(N/M)$(Sub-linear).
*   **Worst Case:**$O(N \cdot M)$(though$O(N+M)$variants exist).

## 3. Rabin-Karp: Rolling Hashes

Rabin-Karp uses a **Rolling Hash** to find potential matches.
1.  Compute hash$H_P$of the pattern.
2.  Compute hash$H_T$of the current text window.
3.  If$H_T = H_P$, verify the match character-by-character.
4.  Update$H_T$in$O(1)$time by subtracting the outgoing character and adding the incoming one.

**Best For:** Multiple pattern matching or searching in data streams.

## Summary

| Algorithm | Complexity (Avg) | Scan Direction | Key Mechanic |
| :--- | :--- | :--- | :--- |
| **KMP** |$O(N+M)$| Left-to-Right | LPS Array (Prefix/Suffix) |
| **Boyer-Moore** |$O(N/M)$| Right-to-Left | Bad Character / Good Suffix |
| **Rabin-Karp** |$O(N+M)$ | Left-to-Right | Rolling Hash |
