---
title: Trie Data Structure
type: article
cluster: data-structures
status: active
date: '2026-04-25'
tags:
- trie
- data-structures
- prefix-tree
- autocomplete
- ip-routing
summary: When a trie beats a hash table — autocomplete, IP longest-prefix match,
  spell check, dictionary scans — and the variants (radix, suffix, ternary
  search) that earn their name.
related:
- DataStructures
- BloomFilters
- StringMatchingAlgorithms
- DatabaseIndexingStrategies
hubs:
- DataStructures Hub
---
# Trie Data Structure

A trie (pronounced "try" or "tree" depending on which side of the religious war you're on) is a tree where each node represents a character / byte / element of a key, and the path from root to node spells the key. It's specifically good at problems where you want to do prefix queries — autocomplete, longest-prefix match, dictionary scans. Where it shines, nothing else comes close.

## The structure, briefly

```
Insert "cat", "car", "dog":

    (root)
    /    \
   c      d
   |      |
   a      o
  / \     |
 t   r    g
```

Each node holds (a) a marker indicating whether a key ends here, (b) child pointers / map per next character. Leaves can hold associated values (key → value mapping).

Two costs:

- **Memory** — each character is a node; for `n` keys of average length `L`, the trie has up to `n * L` nodes. Many implementations use child maps (HashMap, sparse array) to keep memory bounded.
- **Time** — insert / lookup / delete is `O(L)` where `L` is the key length. Independent of `n` (the number of keys). Worth contrasting with hash table's `O(L)` for hashing the key plus `O(1)` for the table lookup — for very long keys, hash table loses; for short keys, hash table wins.

## When tries earn their keep

### Prefix queries (autocomplete)

Given prefix "ap", find all keys starting with "ap." Trivial in a trie: descend to the "ap" node; all keys in its subtree are matches. `O(prefix length + matches)`.

In a hash table: impossible without scanning everything. In a sorted array: `O(log n + matches)` via binary search on prefix bounds. Trie wins on the worst case.

Production examples: search suggestions, command-line completion, IDE code completion (the simple kind, not the LLM kind).

### Longest-prefix match (IP routing)

Given an IP address, find the most specific prefix in a routing table that matches. Routers do this on every packet.

A trie (specifically a "patricia trie" or "radix trie") solves this in `O(prefix length)`. Hash tables can't do "longest match"; they can only do exact match.

Production examples: IP routing tables (Linux's FIB), CIDR-based access control, geographic routing.

### Spell check / dictionary lookup

For a misspelled word, find candidate corrections by exploring the trie within edit distance ≤ 2. The trie structure makes this efficient because branches that diverge from your word can be pruned early.

Production examples: spell checkers (aspell, hunspell), search query corrections.

### String matching variants

- **Aho-Corasick** — given a set of patterns, find all occurrences in a text. Built on a trie + failure links. Linear time. Used in network intrusion detection, bioinformatics motif matching.
- **Suffix trie / suffix tree** — all suffixes of a string. Substring queries in `O(query length)` regardless of text length. Used in genome alignment, log analysis.

## Variants

### Radix trie / Patricia trie

Compresses chains of single-child nodes into one. Saves memory; insert / lookup is faster.

Linux uses a variant of this for the routing table (`fib_trie`).

### Ternary search trie

Each node has three children (less, equal, greater). Cache-friendlier than a wide trie when the alphabet is large. Used in Apache Commons.

### HAT-trie

Cache-conscious; combines tries with hash tables at the leaves. Faster than plain tries on real-world string sets.

### DAFSA (Directed Acyclic Finite State Automaton)

Tries with shared subtree structure. Enormously space-efficient for natural-language dictionaries (English wordlist fits in ~100KB).

## When tries lose

- **Random / non-prefix queries on equal-length keys.** Hash tables are simpler and faster.
- **Memory-constrained environments where keys are long.** Each character potentially adds a node; bytes per key is high.
- **Workloads where insert/delete dominate over lookup.** Maintaining tree structure has overhead.
- **Sparse data with very long keys.** Bloom filter or hash set is more compact for membership tests.

## Memory layout matters

The naive C++/Java trie uses `Map<Character, Node>` per node. For ASCII text, prefer `Node[256]` arrays — direct indexing, no hash overhead. For Unicode strings, the array would be huge; use a sparse representation.

Cache-aware layouts pack frequently-accessed nodes together. Patricia tries / radix tries help; HAT-tries help further; DAFSAs maximally so.

In hot paths (e.g., per-packet routing), a radix-trie implementation laid out cache-friendly is dramatically faster than a generic hashmap-of-hashmap trie.

## A simple Python implementation

```python
class Trie:
    def __init__(self):
        self.children = {}
        self.value = None
        self.is_end = False

    def insert(self, key, value=None):
        node = self
        for c in key:
            if c not in node.children:
                node.children[c] = Trie()
            node = node.children[c]
        node.is_end = True
        node.value = value

    def search(self, key):
        node = self._descend(key)
        return node.value if node and node.is_end else None

    def starts_with(self, prefix):
        node = self._descend(prefix)
        if node is None: return []
        results = []
        self._collect(node, prefix, results)
        return results

    def _descend(self, key):
        node = self
        for c in key:
            if c not in node.children: return None
            node = node.children[c]
        return node

    def _collect(self, node, prefix, results):
        if node.is_end:
            results.append((prefix, node.value))
        for c, child in node.children.items():
            self._collect(child, prefix + c, results)
```

Adequate for small data, autocomplete prototypes. Not optimised for memory or cache; use a library for production scale.

## Production libraries

- **`pygtrie`** (Python) — pure Python; flexible.
- **`marisa-trie`** (Python wrapper around C++ MARISA) — read-only after construction; very compact.
- **`datrie`** (Python wrapper around libdatrie) — double-array trie; fast.
- **`hat-trie`** (C++) — cache-conscious; high-performance.
- **`patricia_trie`** (Rust crate) — radix trie.
- **Java's `TreeMap` with prefix range queries** — not exactly a trie, but covers the prefix-query case for sorted strings.

For production: pick a library that implements the variant matching your access pattern. Don't write your own unless you have a specific reason.

## Concrete real-world examples

- **Linux kernel `fib_trie`** — IPv4/IPv6 routing decisions on every packet.
- **DNS resolvers** — caching results keyed by domain name.
- **Auto-complete in search engines** — typeahead suggestions.
- **Suffix arrays / FM-index in bioinformatics** — genome alignment.
- **Trie-based log indexing** — variant of prefix matching for distributed log analysis.
- **Aho-Corasick in Snort / Suricata** — pattern matching across many attack signatures.

If you're working on networking, search, bioinformatics, or text-processing, you'll meet tries in production.

## Further reading

- [DataStructures] — broader data structure context
- [BloomFilters] — alternative for membership tests
- [StringMatchingAlgorithms] — Aho-Corasick and friends
- [DatabaseIndexingStrategies] — when to reach for trie-flavour indexes
