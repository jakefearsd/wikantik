---
canonical_id: 01KQ12YDSQR1JJT368PPKBKBS7
title: Bloom Filters
type: article
cluster: data-structures
status: active
date: '2026-04-25'
tags:
- bloom-filter
- data-structures
- probabilistic
- caching
- lsm-tree
summary: Bloom filters in the working engineer's view — the math you need, when
  the false-positive trade-off pays, and the variants (counting, scalable,
  blocked) that matter in practice.
related:
- DataStructures
- HashFunctions
- DatabaseIndexingStrategies
- CachingStrategies
hubs:
- DataStructures Hub
---
# Bloom Filters

A Bloom filter is the trick that lets you ask "have I seen this thing before?" using almost no memory, at the cost of occasionally lying yes when the answer is no. False positives are possible; false negatives are impossible. That asymmetry is the whole point — for many real workloads, "definitely no" is the answer that matters, and "probably yes" is fine because you can do a real check after.

## The math, briefly

A Bloom filter is a bit array of size `m`, plus `k` independent hash functions. To insert an element: hash it `k` times, set those `k` bits. To query: hash, check those `k` bits. All set → "probably yes." Any unset → "definitely no."

False-positive rate `p` for `n` inserted items:

```
p ≈ (1 - e^(-kn/m))^k
```

Optimal `k` for given `m` and `n`:

```
k = (m/n) * ln(2) ≈ 0.693 * (m/n)
```

In practical numbers:

| Items (n) | Bits/item (m/n) | k | False-positive rate |
|---|---|---|---|
| any | 8 | 6 | ~2% |
| any | 10 | 7 | ~1% |
| any | 14 | 10 | ~0.1% |
| any | 24 | 17 | ~0.001% |

**Working rule of thumb:** 10 bits per item gives 1% FP rate. 8 bits per item gives 2%. That's the design space for most applications.

Memory at scale: 1 billion items at 1% FP rate = 10 billion bits ≈ 1.25 GB. Compare to a hash set storing 8-byte hashes: 8 GB. Bloom filter is 6× smaller for the same lookup capability.

## Where they actually pay off

**Avoiding expensive checks.** This is 90% of real Bloom filter usage. You have a slow lookup (disk read, network call, expensive computation). A Bloom filter sits in front; if it says "definitely no," you skip the slow lookup entirely. False positives just mean you do the slow lookup occasionally for nothing — no correctness issue.

Concrete examples:

- **LSM trees** (RocksDB, LevelDB, Cassandra, Bigtable). Each SSTable has a Bloom filter for its keys. A point lookup checks the Bloom filter first; if no, skip the SSTable read. Without this, point lookups would read every level.
- **CDN cache layers.** Bloom filter says "this URL has never been requested" → don't bother checking the slower cache, go straight to origin.
- **Web crawlers.** "Have I seen this URL?" 99% of "no" answers come back instantly without hitting a database.
- **Spam / malware checks.** "Is this URL on any of N blocklists?" Bloom filter rules out 95% before doing any real work.
- **Database query planning.** Skip a join probe if the filter says no match is possible.

**When *not* to use a Bloom filter:**

- You can't tolerate false positives at any rate. (Authentication, financial decisions, anything safety-critical.)
- You need to delete items. (Standard Bloom filters can't delete; see counting Bloom below.)
- The set is small enough that a hash set fits in memory comfortably. Don't add complexity for small wins.
- You need to enumerate the set (Bloom filters can't tell you what's in them).

## Variants worth knowing

**Counting Bloom Filter** — replace each bit with a small counter (4 bits is typical). Increment on insert, decrement on delete. Supports deletion at 4× the memory cost. Use when you need delete and accept the overhead.

**Scalable Bloom Filter** — chain multiple Bloom filters with progressively tighter false-positive rates. Lets the filter grow without knowing the final size in advance. Lookup checks each filter in chain; insert goes to the latest one. Mature library: `pyprobables`.

**Blocked / Cache-Aware Bloom Filter** — a bit array whose bits are organised in cache-line-sized chunks. All `k` hashes for one item land in the same chunk. Reduces cache misses dramatically for large filters; ~25% slower false-positive rate for ~3× faster lookups. RocksDB uses this.

**Cuckoo Filter** — fingerprint-based, supports deletion, often smaller than Bloom for the same false-positive rate. More complex insert (can fail at high load); read pattern is similar. Worth using when you need delete *and* good performance; counting Bloom otherwise.

**Quotient Filter** — fingerprint-based with locality (inserted items occupy contiguous slots). Better cache behaviour than Bloom for some workloads; can be merged. Less common in practice.

**XOR Filter / Binary Fuse Filter** — newer designs (2019, 2022) that use `k` hashes that XOR to a fingerprint. Smaller than Bloom for the same FP rate, faster lookups, but build-time is higher and they don't support online inserts after construction. Right when you build once and query a lot.

For most production needs in 2026: standard Bloom for hot-loop lookups, blocked Bloom for very large filters, counting Bloom or Cuckoo if you need delete, XOR/Binary Fuse if you can rebuild offline.

## Hash functions matter (a bit)

You need `k` "independent" hash functions. In practice, deriving `k` hashes from two via the formula `h_i(x) = h_1(x) + i * h_2(x) mod m` is fine and standard. Use a fast non-cryptographic hash for both: xxHash, MurmurHash3, or SipHash.

Cryptographic hashes (SHA-256, BLAKE2) are overkill — slower and not needed; you don't have an adversary trying to construct false positives in most use cases.

If you do have an adversary (e.g. URL filters where attackers can submit URLs to game the filter), use SipHash with a random key or use one of the more recent adversary-resistant designs.

## Implementation gotchas

**Mutable bit array sharing.** A Bloom filter under concurrent inserts needs atomic OR for the bit set, not plain OR. Lookup is naturally lock-free. Most libraries handle this; if you write your own, don't forget.

**Persistence.** Bloom filters are pure binary blobs — `mmap` the bit array directly. Don't deserialise to a hash set on load; the filter *is* its bits.

**Sizing for unknown growth.** If you plan for `n` items but get `2n`, your false-positive rate roughly squares. Either oversize, or use a scalable variant.

**Saturation.** As the filter approaches `m` set bits, FP rate approaches 100%. Monitor `popcount(bits) / m`; alert before you blow out the FP budget.

**Cross-language/format compatibility.** A Bloom filter written by one language's library is rarely portable to another's. Standardise the hash and serialisation format if you cross language boundaries.

## A worked example: LSM-tree point reads

A read-heavy LSM-tree database (Cassandra, RocksDB) might have 5 SSTables on disk. A point lookup, naively, reads up to 5 SSTables to find a key.

With a Bloom filter per SSTable at 10 bits/item / 1% FP rate:

- For a key not in the database: each filter says "definitely no" with 99% probability. Average reads ≈ 0.05 SSTables (per filter, expected). 5 SSTables × 0.01 ≈ 0.05 disk reads instead of 5.
- For a key in the database: the filter for the right SSTable says "yes" (correctly); other filters mostly say "no." 1 disk read plus ≈ 0.04 wasted reads from other filters.

Result: roughly 100× fewer disk reads on negative lookups, ~1× on positive lookups. This is the difference between LSM trees being fast and slow.

## Further reading

- [DataStructures] — broader context
- [HashFunctions] — choosing the underlying hash
- [DatabaseIndexingStrategies] — Bloom filters in storage engines
- [CachingStrategies] — Bloom filters in front of caches
