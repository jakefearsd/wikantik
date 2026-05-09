---
canonical_id: 01KQ12YDTKJP7GTEMGPMJV5FBV
title: Database Sharding
type: article
cluster: databases
status: active
date: '2026-05-15'
tags:
- database
- sharding
- partitioning
- horizontal-scaling
- consistent-hashing
auto-generated: false
summary: Horizontal scaling for write-heavy workloads. Detailed comparison of sharding
  schemes, consistent hashing implementation, and operational resharding strategies.
related:
- DatabasePartitioning
- ConsistentHashing
- DatabaseReplication
- DatabaseDesign
- DatabaseIndexingStrategies
hubs:
- DatabasesHub
---
# Database Sharding

Sharding is the architectural decision to partition a single logical dataset across multiple physical database instances. Unlike replication, which scales read throughput, sharding scales **write throughput** and **storage capacity**.

## 1. The Sharding Readiness Checklist

Before introducing the complexity of shards, verify that you have exhausted simpler scaling paths:

| Check | Metric for Action | Alternative |
|---|---|---|
| **Write IOPS** | $>80\%$ of disk throughput sustained | Increase volume IOPS (e.g., io2 Block Express) |
| **CPU Saturation** | $>70\%$ average on largest instance | Vertical scale (e.g., `r7g.16xlarge`) |
| **Index Bloat** | Index size $>$ RAM | Partitioning (Local to one instance) |
| **Vacuum/Maintenance** | Autovacuum cannot keep up with bloat | Partitioning or Cold Data Archival |

## 2. Sharding Schemes: A Technical Comparison

| Scheme | Logic | Best For | Operational Pain |
|---|---|---|---|
| **Range** | `[0-10k] -> S1`, `[10k-20k] -> S2` | Time-series, ordered scans | Hot spots at the "end" of the range |
| **Hash** | `hash(key) % N -> Shard` | Uniform distribution | Resharding requires moving $100\%$ of data |
| **Consistent Hash** | Virtual nodes on a ring | Elastic scaling | Implementation complexity |
| **Directory** | `LookupTable[key] -> ShardID` | Multi-tenant (uneven sizes) | The lookup table becomes a bottleneck |

## 3. Implementation: Consistent Hashing

Consistent hashing minimizes data movement during resharding. Only $K/N$ keys need to be remapped when adding a shard (where $K$ is total keys and $N$ is shards).

```python
import hashlib
import bisect

class ConsistentHashRing:
    def __init__(self, shards, replicas=100):
        self.replicas = replicas
        self.ring = {}
        self.sorted_keys = []
        for shard in shards:
            self.add_shard(shard)

    def _hash(self, key):
        return int(hashlib.md5(key.encode()).hexdigest(), 16)

    def add_shard(self, shard):
        for i in range(self.replicas):
            h = self._hash(f"{shard}:{i}")
            self.ring[h] = shard
            bisect.insort(self.sorted_keys, h)

    def get_shard(self, key):
        h = self._hash(key)
        idx = bisect.bisect_right(self.sorted_keys, h)
        if idx == len(self.sorted_keys):
            idx = 0
        return self.ring[self.sorted_keys[idx]]

# Usage
ring = ConsistentHashRing(["shard-0", "shard-1", "shard-2"])
target = ring.get_shard("user_12345")
```

## 4. The Irrevocable Choice: The Shard Key

The **Shard Key** determines the data locality. Choosing the wrong key leads to "Fan-out Queries" (querying every shard to find one result).

*   **Good Key:** `tenant_id` (in multi-tenant SaaS), `user_id` (in social apps).
*   **Bad Key:** `created_at` (all writes hit the newest shard), `is_active` (low cardinality).

## 5. Resharding Strategy: The Online Path

To move from 4 shards to 8 without downtime:
1.  **Dual Writes:** Update application logic to write new data to both the old shard and the new destination shard.
2.  **Snapshot & Backfill:** Copy historical data from the old shard to the new one.
3.  **Verification:** Compare checksums of the migrated data.
4.  **Cutover:** Flip the read path to the new shard.
5.  **Cleanup:** Stop dual writes and delete old data.

## 6. Recommended Substrates
*   **Citus (Postgres):** Distributed Postgres as an extension. Handles the routing and parallel execution.
*   **Vitess (MySQL):** The gold standard for MySQL sharding (used by YouTube/Slack).
*   **CockroachDB / TiDB:** New-SQL databases that shard automatically at the storage layer using Raft.

## Further Reading
* [DatabasePartitioning](DatabasePartitioning)
* [ConsistentHashing](ConsistentHashing)
* [DatabaseReplication](DatabaseReplication)
* [DatabaseIndexingStrategies](DatabaseIndexingStrategies)
