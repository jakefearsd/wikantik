---
type: article
tags: [algorithms, computer-science, data-structures, complexity, programming]
date: 2026-03-21
status: active
cluster: technology
summary: Essential algorithms every computer scientist should understand deeply, from sorting and searching to graph traversal and dynamic programming
related: [MachineLearning, ArtificialIntelligence, FundamentalsOfProgramming]
---
# Foundational Algorithms for Computer Scientists

Algorithms are the intellectual core of computer science. While languages, frameworks, and platforms change every few years, the fundamental algorithms endure — the same graph traversal that powered early network routing now drives social media recommendations, and the same dynamic programming principles behind sequence alignment underpin modern [machine learning](MachineLearning) optimization.

This article covers the algorithms that every working computer scientist should understand deeply — not as abstract theory, but as practical tools that appear repeatedly across domains.

## Complexity Analysis: The Universal Language

Before discussing specific algorithms, understanding how to analyze them is essential. Big-O notation describes how an algorithm's resource usage (time or space) grows with input size.

| Notation | Name | Example | Practical Feel |
|----------|------|---------|----------------|
| O(1) | Constant | Hash table lookup | Instant, regardless of data size |
| O(log n) | Logarithmic | Binary search | Doubles in data → one more step |
| O(n) | Linear | Linear search | Proportional to data size |
| O(n log n) | Linearithmic | Merge sort | Slightly worse than linear |
| O(n²) | Quadratic | Bubble sort | Doubles in data → 4x slower |
| O(2ⁿ) | Exponential | Brute-force subset enumeration | Impractical beyond ~30 elements |
| O(n!) | Factorial | Brute-force permutation | Impractical beyond ~12 elements |

The distinction between O(n log n) and O(n²) is the difference between sorting a billion records in minutes versus centuries. Complexity analysis is not academic — it is the single most important practical skill for writing software that scales.

## Sorting Algorithms

Sorting is the most studied problem in computer science, and for good reason: sorted data enables binary search, simplifies deduplication, powers database indexes, and is a prerequisite for many higher-level algorithms.

### Comparison-Based Sorts

**Merge Sort** — O(n log n) worst case, stable, but requires O(n) extra space. The algorithm divides the array in half recursively, sorts each half, and merges the sorted halves. Its guaranteed O(n log n) performance makes it the basis for most library sort implementations on linked lists. Merge sort also introduced the divide-and-conquer paradigm that pervades algorithm design.

**Quick Sort** — O(n log n) average case, O(n²) worst case (rare with randomization), in-place. Chooses a pivot element, partitions the array into elements less than and greater than the pivot, and recurses. In practice, quick sort is often faster than merge sort due to better cache locality. Most standard library sorts (C's `qsort`, Java's `Arrays.sort` for primitives) use quick sort variants.

**Heap Sort** — O(n log n) worst case, in-place, but not stable. Uses a binary heap data structure. Rarely used as a primary sort but valuable for its guaranteed worst-case performance and O(1) extra space.

### Non-Comparison Sorts

**Counting Sort** — O(n + k) where k is the range of values. Works by counting occurrences of each value. Only applicable when the range of possible values is small relative to the number of elements.

**Radix Sort** — O(d × (n + k)) where d is the number of digits. Sorts by processing individual digits, from least significant to most significant, using a stable sort (typically counting sort) at each level. Used in practice for sorting integers and fixed-length strings.

### Practical Sorting

Modern library sorts are hybrids. Python's Timsort combines merge sort and insertion sort, exploiting existing order in real-world data. C++'s `std::sort` typically uses introsort — quick sort that falls back to heap sort if recursion depth gets too deep. Understanding the properties (stability, worst-case guarantees, space usage) helps you choose the right tool.

## Search Algorithms

### Binary Search

Binary search finds an element in a sorted array in O(log n) time by repeatedly halving the search space. Despite its simplicity, binary search is notoriously hard to implement correctly — off-by-one errors in the boundary conditions have plagued programmers for decades.

Beyond simple lookup, binary search is a general technique for any problem where the answer space is monotonic. Binary search on the answer ("Is there a solution of size ≤ k?") converts optimization problems into decision problems.

### Hash Tables

Hash tables provide O(1) average-case lookup, insertion, and deletion by mapping keys to array indices through a hash function. They are the single most practically important data structure in software engineering — Python dictionaries, JavaScript objects, Java HashMaps, and database indexes all rely on hashing.

Key design considerations:
- **Hash function quality:** Must distribute keys uniformly to avoid collisions
- **Collision resolution:** Chaining (linked lists at each bucket) vs. open addressing (probing for empty slots)
- **Load factor:** The ratio of entries to buckets; most implementations resize when this exceeds 0.7–0.75

### Balanced Binary Search Trees

Red-black trees and AVL trees maintain sorted order with O(log n) lookup, insertion, and deletion. They're used when you need both fast lookup and ordered iteration — database indexes, in-memory ordered maps (Java's TreeMap, C++'s `std::map`), and interval trees.

B-trees generalize this to disk-based storage, where each node holds multiple keys and has many children, minimizing disk I/O. Nearly every relational database uses B-trees or B+ trees for its indexes.

## Graph Algorithms

Graphs model relationships: social networks, road maps, dependency chains, network topologies, state machines. Graph algorithms are among the most practically important in computer science.

### Traversal

**Breadth-First Search (BFS)** — Explores nodes level by level using a queue. Finds shortest paths in unweighted graphs. Used in social network "degrees of separation," web crawling, and puzzle solving. Time: O(V + E).

**Depth-First Search (DFS)** — Explores as deep as possible before backtracking, using a stack (or recursion). Used for topological sorting, cycle detection, connected component identification, and maze generation. Time: O(V + E).

### Shortest Path

**Dijkstra's Algorithm** — Finds shortest paths from a single source in graphs with non-negative edge weights. Uses a priority queue to greedily extend the shortest known path. Time: O((V + E) log V) with a binary heap. Powers GPS navigation and network routing.

**Bellman-Ford** — Handles negative edge weights (which Dijkstra cannot). Slower at O(VE) but more general. Can detect negative cycles. Used in arbitrage detection in financial markets.

**Floyd-Warshall** — Finds shortest paths between all pairs of vertices. Time: O(V³). Simple to implement and useful when you need the complete distance matrix.

### Minimum Spanning Trees

**Kruskal's Algorithm** — Sorts edges by weight and adds them greedily, skipping edges that would create a cycle (detected using Union-Find). Time: O(E log E).

**Prim's Algorithm** — Grows the MST from a starting vertex, always adding the cheapest edge that connects a new vertex. Time: O(E log V) with a binary heap.

Applications: network design (minimum cost to connect all nodes), clustering (removing the longest MST edges), and approximation algorithms for NP-hard problems like the Traveling Salesman.

### Topological Sort

A linear ordering of vertices in a directed acyclic graph (DAG) such that every edge goes from earlier to later in the ordering. Essential for dependency resolution — build systems (Make, Maven), package managers (npm, pip), course prerequisite planning, and task scheduling all use topological sort.

## Dynamic Programming

Dynamic programming (DP) solves problems by breaking them into overlapping subproblems and storing their solutions to avoid redundant computation. It transforms exponential brute-force solutions into polynomial ones.

### The DP Recipe

1. **Define the state:** What information do you need to describe a subproblem?
2. **Write the recurrence:** How does the solution to a subproblem relate to smaller subproblems?
3. **Identify the base cases:** What are the trivial subproblems you can solve directly?
4. **Determine the computation order:** Bottom-up (tabulation) or top-down (memoization)?
5. **Extract the answer:** Which entry in your table contains the final answer?

### Classic DP Problems

**Longest Common Subsequence (LCS)** — Given two sequences, find the longest subsequence common to both. Used in diff tools, DNA sequence alignment, and version control systems. Time: O(mn) where m and n are sequence lengths.

**Knapsack Problem** — Given items with weights and values, maximize total value within a weight constraint. Models resource allocation problems in scheduling, budgeting, and portfolio optimization. The 0/1 knapsack runs in O(nW) where n is the number of items and W is the capacity.

**Edit Distance (Levenshtein)** — The minimum number of insertions, deletions, and substitutions to transform one string into another. Powers spell checkers, DNA analysis, and fuzzy string matching. Time: O(mn).

**Shortest Path (revisited)** — Both Dijkstra's and Floyd-Warshall are dynamic programming algorithms. Bellman-Ford's relaxation is DP over the number of edges.

## Greedy Algorithms

Greedy algorithms make the locally optimal choice at each step, hoping this leads to a globally optimal solution. They don't always work — but when they do, they're typically simpler and faster than DP alternatives.

**When greedy works:** Problems with the greedy choice property (a locally optimal choice leads to a globally optimal solution) and optimal substructure.

**Classic examples:**
- **Huffman coding:** Builds optimal prefix-free codes for data compression by repeatedly merging the two least-frequent symbols
- **Activity selection:** Choose the maximum number of non-overlapping activities by always picking the one that ends earliest
- **Dijkstra's algorithm:** Greedy selection of the nearest unvisited vertex
- **Kruskal's/Prim's MST:** Greedy edge selection for minimum spanning trees

## Cryptographic Algorithms

Modern security infrastructure depends on a small number of algorithms whose correctness is essential:

### Symmetric Encryption

**AES (Advanced Encryption Standard)** — The dominant symmetric cipher. Operates on 128-bit blocks with 128, 192, or 256-bit keys. Used everywhere: HTTPS, disk encryption, VPNs, secure messaging. AES is a substitution-permutation network — it applies rounds of byte substitution, row shifting, column mixing, and key addition.

### Asymmetric Encryption

**RSA** — Based on the difficulty of factoring large semiprimes. Used for key exchange and digital signatures. Being gradually replaced by elliptic curve cryptography for better security at shorter key lengths.

**Elliptic Curve Cryptography (ECC)** — Based on the difficulty of the elliptic curve discrete logarithm problem. Provides equivalent security to RSA with much smaller keys (256-bit ECC ≈ 3072-bit RSA). Used in TLS, Bitcoin, and Signal.

### Hash Functions

**SHA-256** — Produces a 256-bit digest from arbitrary input. Used for data integrity verification, digital signatures, blockchain proof-of-work, and password storage (with appropriate salting and key derivation). The property that finding two inputs with the same hash is computationally infeasible (collision resistance) is what makes these functions useful.

### Post-Quantum Cryptography

Quantum computers threaten RSA and ECC by efficiently solving the factoring and discrete logarithm problems (Shor's algorithm). NIST has standardized post-quantum algorithms based on lattice problems (CRYSTALS-Kyber for key exchange, CRYSTALS-Dilithium for signatures) that are believed resistant to quantum attacks.

## Compression Algorithms

### Lossless Compression

**Huffman Coding** — Assigns shorter bit strings to more frequent symbols. Optimal among prefix-free codes. Used as a component in gzip, DEFLATE, and JPEG.

**Lempel-Ziv (LZ77/LZ78)** — Replaces repeated sequences with references to earlier occurrences. The basis of gzip, PNG, and ZIP. LZ algorithms exploit the redundancy in structured data — text, code, and structured formats compress dramatically.

**Arithmetic Coding** — Encodes an entire message as a single number between 0 (inclusive) and 1 (exclusive), achieving compression closer to the theoretical entropy limit than Huffman coding. Used in modern formats like LZMA (7-Zip) and some image/video codecs.

### Lossy Compression

**DCT-based (JPEG, MP3)** — The Discrete Cosine Transform converts spatial/temporal data to frequency domain, where high-frequency components (fine details, high-pitched sounds) can be discarded with minimal perceptual impact.

**Wavelet-based (JPEG 2000)** — Multi-resolution analysis that provides better quality at low bitrates than DCT.

**Neural compression** — Learned compression using autoencoders and other neural networks. An active research area that blurs the line between compression and [machine learning](MachineLearning).

## Algorithms in Practice

The gap between knowing an algorithm and applying it effectively is significant:

- **Choose the right data structure first.** The algorithm often follows from the data structure. Need fast lookup? Hash table. Need ordered traversal? Balanced BST. Need priority? Heap.
- **Constant factors matter.** An O(n log n) algorithm with poor cache behavior can be slower than an O(n²) algorithm with excellent locality for practical input sizes.
- **Profile before optimizing.** Measure where time is actually spent rather than guessing. The bottleneck is often I/O, memory allocation, or data structure choice — not the core algorithm.
- **Use library implementations.** Standard library sorts, hash tables, and graph libraries have been optimized for decades. Write your own only when you need specialized behavior.

## Further Reading

- [Machine Learning](MachineLearning) — Many ML algorithms (gradient descent, backpropagation, EM) build on the foundations covered here
- [The Future of Machine Learning](TheFutureOfMachineLearning) — How algorithmic advances are shaping the next generation of ML
- [Artificial Intelligence](ArtificialIntelligence) — The broader AI field and its algorithmic foundations
- [Operations Research](OperationsResearchHub) — The applied discipline that turned graph algorithms, dynamic programming, and combinatorial optimization into industrial practice: scheduling, logistics, revenue management
