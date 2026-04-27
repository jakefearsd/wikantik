---
canonical_id: 01KQ0P44QVJ5WPG6NQKNSRNP0Q
title: Hash Table Design
type: article
cluster: data-structures
status: active
date: '2026-04-26'
summary: Designing hash tables — hash functions, collision resolution, load factors,
  resizing strategies — and the engineering tradeoffs that distinguish a fast hash
  table from a slow one.
tags:
- hash-tables
- data-structures
- algorithms
- collision-resolution
related:
- BalancedSearchTrees
- HeapAndPriorityQueues
hubs:
- Data Structures Hub
---
# Hash Table Design

Hash tables are the most-used data structure in software. They give O(1) average lookup, insert, delete. The "average" hides important engineering decisions.

Understanding hash table design helps you use them well, avoid pathological cases, and know when to use alternatives.

## The basic structure

- An array of slots (the table)
- A hash function: key → array index
- A collision resolution strategy

Insert: hash key, place in slot.
Lookup: hash key, check slot.
Delete: hash key, remove from slot.

## Hash functions

A good hash function:
- Uniform distribution across slot range
- Fast to compute
- Avalanche effect: small input changes flip ~half the output bits

### Common functions

- **FNV**: fast, simple, decent
- **MurmurHash**: fast, good distribution, used by Hadoop, Kafka
- **xxHash**: fastest non-cryptographic
- **CityHash, FarmHash**: Google's
- **SipHash**: secure against hash flooding

For application use: language standard library hash. Don't roll your own.

### Cryptographic hashes

SHA-256 etc. Overkill for hash tables (slow). Use only when adversarial inputs matter.

## Collision resolution

Two main strategies:

### Chaining

Each slot is a linked list (or other structure). Collisions append to list.

Pros:
- Simple
- Handles high load factors gracefully
- Deletion is straightforward

Cons:
- Pointer chasing (cache unfriendly)
- Memory overhead (list nodes)

### Open addressing

When slot is full, probe to find empty slot.

Probe sequences:
- **Linear**: try i+1, i+2, i+3, ... (cache friendly, but clustering)
- **Quadratic**: try i+1², i+2², ... (less clustering)
- **Double hashing**: use second hash as step (best uniformity)

Pros:
- Cache friendly (no pointer chasing)
- Lower memory overhead

Cons:
- Performance degrades sharply at high load
- Deletion is tricky (tombstones)
- Clustering can hurt linear probing

### Robin Hood hashing

Open addressing variant. When inserting, if you encounter a record with smaller probe distance, swap.

Equalizes probe distances. Practical and fast.

### Cuckoo hashing

Two hash functions; each key has two possible slots. Insert: place in either; if both occupied, evict and reinsert.

Worst-case O(1) lookup. Insertion can fail (need rehash).

### Hopscotch hashing

Combines open addressing with bounded distance. Each key within H slots of its ideal position.

## Load factor

Load factor α = filled slots / total slots.

Performance vs load factor depends on strategy:
- Chaining: degrades gradually
- Open addressing: degrades sharply above ~0.7
- Robin Hood: works well to ~0.9

## Resizing

When load factor exceeds threshold, resize the table (double capacity, rehash all keys).

### Cost

Single resize: O(n).
Amortized over insertions: O(1).

### Incremental rehashing

For latency-sensitive applications: rehash a few entries per operation rather than all at once.

Used in Redis.

## Modern implementations

### Java HashMap

Chaining. Treeifies long chains for worst-case O(log n).

Default load factor 0.75.

### C++ std::unordered_map

Chaining. Often slower than alternatives due to spec constraints.

Many use absl::flat_hash_map or robin_hood instead.

### Python dict

Open addressing with perturbation-based probing.

Insertion-ordered since Python 3.7.

### Go map

Open addressing variant. Uses 8-key buckets for cache locality.

### Rust HashMap

SipHash by default (DoS-resistant). Open addressing (Robin Hood-style).

## Performance considerations

### Cache locality

Open addressing wins for cache. Each probe is contiguous memory.

Chaining: pointer chasing, cache misses.

### Branch prediction

Linear probing has predictable access patterns.

### Hash quality

Bad hash → many collisions → terrible performance.

Test hash quality with key distribution from production.

### Memory layout

Pack data tightly. Group hot fields. Avoid pointer indirection.

absl::flat_hash_map is fast partly because it stores keys+values inline.

## Hash flooding attacks

Adversary submits keys all hashing to same slot. Hash table degrades to linked list.

Mitigations:
- Random hash seed per process
- SipHash or other DoS-resistant hash
- Treeify long chains (Java approach)

Many languages randomize hash seeds by default. Some still vulnerable.

## When NOT to use hash table

### Need ordered iteration

Use a tree (TreeMap, std::map).

### Worst-case bounds matter

Hash tables have O(n) worst case. For real-time systems, may not be acceptable.

### Range queries

Hash tables can't do range queries. Use trees.

### Very small data

For small N (<10-20), linear search is faster.

### Frequent updates with unstable hash

If keys change, hash tables break.

## Specialized variants

### Concurrent hash tables

Multiple threads access simultaneously.

- Java: ConcurrentHashMap (lock striping)
- Java: high-performance: NonBlockingHashMap
- C++: tbb::concurrent_hash_map

Lock-free designs exist but are complex.

### Persistent / immutable hash tables

Update returns a new map; old map unchanged.

Used in functional languages (Clojure, Scala).

Hash array mapped tries (HAMT) are a common implementation.

### Bloom filters

Probabilistic. Tells you "definitely not" or "maybe yes."

Useful for cache filters: skip expensive lookup if Bloom says no.

### Cuckoo filters

Like Bloom but supports deletion.

### Count-min sketch

Probabilistic counting. For analytics, frequency estimation.

## Memory overhead

Empty hash table: pointer + size + capacity. Tens of bytes.

Per entry:
- Chaining: key + value + pointer + (allocation overhead)
- Open addressing: key + value + (hash if cached)

Open addressing typically lower per-entry overhead.

## Common failure patterns

### Custom hash that's bad

Random-seeming functions often have bad distribution. Use library hashes.

### Mutating keys after insertion

Hash changes; key unfindable.

### Insufficient capacity

Hash tables resize, but resizing during a critical path causes latency spikes.

### Adversarial inputs

User-supplied keys without DoS-resistant hash.

### Wrong load factor

Default usually fine; tune only with measurement.

### Iterating during modification

Behavior is implementation-specific. Often UB or exception.

## Engineering recommendation

For most application code:

1. Use the language standard library hash table
2. Don't reimplement
3. Benchmark only if performance is suspect
4. Consider absl::flat_hash_map (C++), or hashbrown (Rust default)

When you do need to optimize:
1. Profile to confirm hash table is the bottleneck
2. Test with realistic key distribution
3. Measure load factor in production
4. Consider open-addressing variant if cache-bound

## Further Reading

- [BalancedSearchTrees](BalancedSearchTrees) — Ordered alternative
- [HeapAndPriorityQueues](HeapAndPriorityQueues) — Different access pattern
- [Data Structures Hub](Data+Structures+Hub) — Cluster index
