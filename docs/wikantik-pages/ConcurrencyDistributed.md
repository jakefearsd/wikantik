---
title: Concurrency Distributed
type: article
cluster: distributed-systems
status: active
date: '2026-04-25'
tags:
- distributed-concurrency
- consensus
- distributed-locks
- coordination
summary: Coordinating concurrent work across multiple machines — distributed
  locks, leader election, atomic counters, idempotent operations — and the
  primitives modern systems use.
related:
- ConcurrencyPatterns
- DistributedComputingAlgorithms
- PaxosAndRaft
- ApiRateLimitingAlgorithms
hubs:
- DistributedSystems Hub
---
# Concurrency Distributed

Concurrency on a single machine has well-understood primitives — locks, channels, atomics. Concurrency across machines is harder because you can't trust your peers, the network isn't reliable, and there's no shared memory.

The patterns are different. The failure modes are worse. This page is the working set for getting it right.

## What's different

Single-machine concurrency primitives assume:

- **Reliable communication.** Threads can communicate via memory or channels without packet loss.
- **Shared time.** All threads see the same clock.
- **Failure detection.** A crashed thread is observable; the OS knows.
- **Bounded delay.** Operations complete within microseconds-to-milliseconds; long delays are bugs.

None of these hold across machines. Network packets are lost; clocks drift; "is that machine slow or dead?" is undecidable in finite time; delays are unbounded.

This is why distributed concurrency requires different patterns.

## Distributed locks

"Only one process holds this resource at a time, even across machines."

### Redis-based locks (the cheap version)

```python
def acquire_lock(redis, key, value, timeout):
    return redis.set(key, value, nx=True, ex=timeout)

def release_lock(redis, key, value):
    # Lua script that releases only if value matches
    return redis.eval(release_script, 1, key, value)
```

Cheap; works for "we'd prefer not to run two of these at once."

Pitfalls (Martin Kleppmann's analysis, 2017):

- **GC pause / network delay.** Holder is paused; lock TTL expires; another holder acquires; original wakes up and continues "holding" the lock.
- **Failover.** Redis primary fails before replicating SET; replica becomes primary; second client acquires the same lock.
- **Clock drift.** Lock TTL relies on clock; clocks differ between Redis nodes.

Mitigations:

- **Fencing tokens** — every lock acquisition returns a monotonically increasing token; resource server verifies the token; rejects writes with old tokens. Defeats the GC-pause attack.
- **Redlock** — acquire on majority of independent Redis nodes. Improves the failover concern; doesn't fully solve the GC-pause / fencing concern.
- **Use a real consensus system** when correctness matters.

For "best-effort" locks (cron singletons, rate-limit-related coordination): Redis is fine.
For correctness-critical locks (financial transactions, irreversible operations): use etcd or ZooKeeper.

### Consensus-based locks (the correct version)

etcd and ZooKeeper both provide strongly-consistent locks via consensus protocols (Raft / Zab).

etcd lease + lock pattern:

```go
session, err := concurrency.NewSession(client, concurrency.WithTTL(10))
mutex := concurrency.NewMutex(session, "/my-lock")
mutex.Lock(context.Background())
defer mutex.Unlock(context.Background())
// ... critical section
```

The session gives a lease; the lock is held while the lease is alive; if the holder dies, the lease eventually expires and the lock releases.

For irreversible operations (charging cards, sending shipments, entering data into systems of record), this is the pattern.

## Leader election

"Exactly one node is the leader at any time."

Use cases:

- One scheduler node owns triggering periodic jobs.
- One coordinator node owns a cluster operation.
- One master assigns work to followers.

Approach:

- **etcd / ZooKeeper election primitives.** Each node tries to acquire a lock; the holder is leader. On lease expiry, another node takes over.
- **Raft-style elections within your service.** Embed Raft (HashiCorp Raft library, etcd-raft); your nodes elect a leader as part of their normal operation.
- **Application-level via a database row.** A "leader" row with a lease timestamp; node holding it is leader. Cheap; works for moderate use cases; subject to clock-drift concerns.

Most "we need a leader" problems are solved by deploying etcd / Consul and using their primitives. Don't roll your own.

## Atomic counters

"Increment a number; multiple machines may increment concurrently; never miss an increment; never count one twice."

Approach:

- **Atomic SQL operations.** `UPDATE counters SET value = value + 1`. Postgres transactions handle this; rate limited by row contention at high write rates.
- **Redis INCR.** Atomic; fast; non-durable by default.
- **Sharded counters.** Each writer increments its own shard; readers sum. Trades consistency latency for write throughput.
- **Approximate counts.** HyperLogLog for cardinality; sampling for high-volume metrics. Cheap when exactness isn't required.

For high-volume metrics, exact counters bottleneck; approximate or sharded designs are necessary.

## Idempotent operations

The defence against retry-induced double-effects.

Pattern:

```python
def charge(idempotency_key, amount):
    # Has this idempotency key been used?
    existing = lookup(idempotency_key)
    if existing:
        return existing  # return previous result
    
    # Atomically: charge, record key+result.
    with transaction:
        result = charge_impl(amount)
        record(idempotency_key, result)
    return result
```

Idempotency requires:

- A key generator on the caller (UUID per intended operation).
- Storage of (key → result) on the receiver.
- The check-and-perform happens atomically (transaction).
- Keys eventually expire (otherwise the table grows forever).

For any retried mutation, this is non-negotiable. See [SagaPattern].

## Distributed transactions

Two-phase commit (2PC): a coordinator polls participants for vote; if all yes, commit; if any no, abort.

Limitations:

- **Blocking.** If coordinator dies after some participants prepared, those participants are blocked indefinitely.
- **Latency.** Multiple round-trips; sensitive to slowest participant.
- **Failure modes.** Many; subtle.

In modern distributed systems, 2PC is rare for cross-service work. Sagas (compensating transactions) are preferred. See [SagaPattern], [DistributedComputingAlgorithms].

For within-database distributed transactions (one Postgres cluster across nodes), the database handles this internally — Postgres uses 2PC for cross-shard with partition managers.

## Optimistic vs pessimistic concurrency

- **Pessimistic** (locks). Acquire lock; do work; release. Simple; serialised; doesn't scale.
- **Optimistic** (versioning / CAS). Read with version; do work; write with version check; retry if version changed. Concurrent reads; serialised conflicts; scales better.

In SQL: optimistic via `WHERE version = $expected_version` on UPDATE.

In application code: use atomic CAS where available (Redis, etcd compare-and-swap); use database row locks (`SELECT FOR UPDATE`) where strong serialisation is needed.

For most application-level concurrency: optimistic with retry on conflict. Conflicts are rare; the retry cost is low.

## Distributed rate limiting

See [ApiRateLimitingAlgorithms]. The interesting concurrency aspect: counters per-user shared across N application instances. Centralised counter (Redis) is the simplest approach. Decentralised approaches gain throughput at the cost of approximation.

## Eventual consistency vs strong consistency

A core tradeoff:

- **Strong consistency.** Reads see all earlier writes. Requires coordination on every read or write. Slower; doesn't scale geographically.
- **Eventual consistency.** Writes propagate; reads may see stale data; eventually consistent.

For most workloads, eventual consistency is fine — caches, social timelines, profile pictures. For specific subsystems (account balances, inventory at zero, identity), strong consistency is necessary.

Most modern distributed databases let you choose per-operation: strong reads vs eventual reads. Use strong where it matters; eventual where it doesn't. The default to "everything strong" is pessimistic over-engineering.

## Coordination-free patterns

The opposite of locks: design so coordination isn't required.

- **CRDTs** — see [DistributedComputingAlgorithms]. Concurrent updates merge automatically.
- **Idempotent operations.** Retry safe; no need for exactly-once.
- **Append-only logs** — multiple writers append independently; readers reconcile.
- **Sharding by key** — each key has one owner; no cross-key coordination.

When you can design coordination out, you scale better. The hard part: many problems don't fit these patterns naturally.

## Distributed concurrency primitives, summary

| Need | Substrate |
|---|---|
| Best-effort lock | Redis SET NX EX |
| Correctness-critical lock | etcd / ZooKeeper |
| Leader election | etcd / Consul / Raft library |
| Atomic counter | Redis INCR / SQL UPDATE |
| Distributed semaphore | etcd / Redis |
| Idempotency | App-level table |
| Cross-service transaction | Saga (compensating transactions) |
| Eventual consistency | CRDTs / quorum reads/writes |
| Strong consistency | Consensus (Spanner, CockroachDB, etc.) |

For most production teams: a Redis instance and a Postgres database cover most needs. Add etcd / Consul when correctness-critical coordination is required.

## Failure modes specific to distributed concurrency

**Split brain.** Network partition; both sides think they're "the active one." Defended by quorum (majority must agree).

**Phantom locks.** Lock holder dies without releasing; lock TTL is the safety. Choose TTLs carefully — too short and active holders lose their lock; too long and crashed holders block work.

**Clock skew.** Two nodes' clocks differ; lease expiries differ; surprises. Use logical clocks where possible.

**Cascading retries.** Service A fails; B retries; A's downstream gets retry storm. Add jitter, circuit breakers, exponential backoff.

**Thundering herd.** Cache key expires; 1000 requests miss simultaneously; all hit the database. Single-flight pattern (one fetcher; others wait); stale-while-revalidate; jittered TTLs.

## A pragmatic baseline

For most distributed services in 2026:

1. **Use idempotency keys** for mutating operations.
2. **Use database transactions** for single-DB consistency.
3. **Use Redis for caches, locks-where-correctness-isn't-critical, rate limiting.**
4. **Use etcd / Consul for leader election and correctness-critical coordination.**
5. **Use saga / compensation** for cross-service transactions.
6. **Default to eventually consistent** unless a specific requirement demands strong consistency.

This stack handles 90% of distributed concurrency needs. The remaining 10% require deeper understanding of consensus, CRDTs, and the specific algorithms in [DistributedComputingAlgorithms].

## Further reading

- [ConcurrencyPatterns] — single-machine concurrency
- [DistributedComputingAlgorithms] — algorithms in depth
- [PaxosAndRaft] — consensus algorithms
- [ApiRateLimitingAlgorithms] — distributed rate limiting specifically
