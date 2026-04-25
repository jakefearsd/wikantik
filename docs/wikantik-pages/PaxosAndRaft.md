---
canonical_id: 01KQ12YDW4P2B6GRRJY43T5TTB
title: Paxos And Raft
type: article
cluster: distributed-systems
status: active
date: '2026-04-25'
tags:
- consensus
- paxos
- raft
- distributed-systems
- replication
summary: Why distributed systems need consensus, how Paxos and Raft actually
  differ, and which one you'd pick if you were building something today.
related:
- ConsistentHashing
- ConcurrencyDistributed
- DatabaseReplication
- ByzantineFaultTolerance
- DistributedComputingAlgorithms
hubs:
- DistributedSystems Hub
---
# Paxos and Raft

A consensus algorithm lets a group of machines agree on a value, in the presence of failures, without trusting any single machine. Both Paxos and Raft solve this. They make different ergonomic trade-offs to do it. The trade-offs are why most new systems pick Raft — including etcd, Consul, CockroachDB, TiKV, and the more interesting half of HashiCorp's stack.

This page is the engineer's view: what consensus actually buys you, where the algorithms differ, and which one to use.

## Why consensus

The minimum useful consensus is "agree on the order of operations." Once you have that, you can build:

- **Replicated state machines** — every replica applies the same operations in the same order. Same input → same state.
- **Linearizable storage** — `etcd`, `ZooKeeper`, `Spanner`. Reads see all earlier writes.
- **Distributed locks** — only one client holds the lock at a time, even across network partitions.
- **Leader election** — exactly one leader at a time among a cluster.

You don't need consensus for everything. CRDTs handle eventually-consistent data without it. Quorum reads/writes (Cassandra, DynamoDB) provide tunable consistency without full consensus. Reach for consensus when you genuinely need linearizability or strict ordering.

## What Paxos is

The original. Leslie Lamport, 1990. Multi-Paxos = single-decree Paxos run repeatedly to agree on a sequence of values.

Strengths:
- Provably correct.
- Foundational — most subsequent algorithms are Paxos variants.
- Production-proven in Google's Chubby and Spanner.

Weaknesses:
- Notoriously hard to understand. Lamport himself wrote a follow-up paper "Paxos Made Simple" because the first paper was too hard. It's still hard.
- Specification is loose; implementations diverge in important details (leader leases, log compaction, membership changes).
- "Plain Paxos" doesn't tell you how to do log replication or membership changes — you have to assemble Multi-Paxos yourself.

In practice, "we run Paxos" usually means "we run a custom Multi-Paxos variant with leases and our own membership-change protocol." Those custom bits are where bugs live.

## What Raft is

Stanford, 2014. Raft was explicitly designed for understandability. Same correctness guarantees as Multi-Paxos, but with a tighter spec that includes leader election, log replication, and membership changes as named, separate sub-protocols.

Strengths:
- Far easier to implement correctly. The reference paper is ~30 pages and most of it is pseudocode.
- Mature, well-tested implementations exist as libraries: `hashicorp/raft`, `etcd-io/raft`, `tikv/raft-rs`. Don't write your own.
- Strong leader simplifies reasoning — at any time exactly one leader handles writes.

Weaknesses:
- Strong leader is also a bottleneck. All writes go through one node; throughput caps at what the leader can handle.
- Worse latency under leader failover (hundreds of ms) than some Paxos variants with leases.
- Less flexible: Raft's ordering guarantees can be over-restrictive for some workloads where Paxos lets you be more clever.

## The actual differences

Most people summarising "Paxos vs Raft" miss the technical differences and focus on understandability. Both matter:

| Concern | Multi-Paxos | Raft |
|---|---|---|
| **Leader role** | Optional / can run leaderless; multiple proposers possible | Required; exactly one leader |
| **Log gaps** | Permitted — slot N can commit before slot N-1 | Forbidden — log is contiguous |
| **Out-of-order commits** | Yes (improves throughput, complicates reasoning) | No |
| **Membership changes** | Reconfiguration is its own subprotocol; many variants | Joint consensus or single-server changes; well-specified |
| **Spec completeness** | Loose; implementations differ | Tight; reference impl exists |
| **Production libraries** | Few, mostly bespoke | Many, mature |

For most workloads the differences don't matter at the throughput levels you're actually running. Raft is fast enough.

## When to pick which

**Pick Raft when:**
- You're starting from scratch.
- You want a battle-tested library you can adopt.
- You don't have a specific workload that needs Paxos's flexibility.
- Your team needs to maintain the code without paging Leslie Lamport.

This describes 95%+ of new distributed-systems projects.

**Pick Paxos when:**
- You're at Google, Microsoft, or a similar shop with the engineers and a workload to justify it.
- You need leaderless operation (multi-leader writes for geographic distribution).
- You need out-of-order commits for throughput reasons.
- You're maintaining a Paxos system that already exists.

Outside those cases, Paxos is mostly a pedagogical curiosity and a CV signal.

## Implementations worth knowing

- **etcd** — Kubernetes' control plane. Reference Raft impl in Go.
- **Consul** — service discovery + KV with HashiCorp's Raft library.
- **CockroachDB** — distributed SQL using Raft per range.
- **TiKV** — Raft-backed transactional KV; powers TiDB.
- **MongoDB** — replication uses a Raft-derived protocol.
- **Apache Kafka** — moved from ZooKeeper to KRaft (Kafka's Raft) in 3.x.
- **ZooKeeper** — Zab, Paxos-flavoured but different. Old, stable, mostly being replaced.
- **Google Spanner** — Multi-Paxos. Probably the most sophisticated production consensus system.

If you're picking a library, pick `hashicorp/raft` (Go), `tikv/raft-rs` (Rust), or `etcd-io/raft` (Go). All three are production-proven and battle-tested across many years and many incidents.

## Failure modes you'll actually hit

**Split brain on network partition.** A minority partition keeps thinking it's the leader. Raft handles this correctly (leader needs majority confirmation for commits) but custom Paxos implementations often get this wrong.

**Log divergence after leader failure.** Followers had different logs before failover; new leader reconciles by truncating divergent suffixes. Both algorithms handle this; the bugs are in the reconciliation code in your specific implementation.

**Snapshotting and log compaction.** Logs grow forever without compaction. Snapshotting is its own subprotocol; gets neglected in custom implementations until production runs out of disk.

**Membership change races.** Adding or removing nodes during failures is when both algorithms produce the most subtle bugs. Raft's joint consensus is well-defined but easy to misimplement.

**Read amplification.** Linearizable reads in Raft go through the leader (or use lease/quorum reads); a hot read workload bottlenecks on the leader. Architect read-only workloads to use follower reads with explicit staleness bounds where possible.

## Don't roll your own

If your reaction to this page is "let me implement Raft," stop. The reference implementations are 5,000–20,000 lines of code and have had hundreds of bug fixes since their initial releases. Your version will have bugs you won't find until production. Use the library.

If you're building distributed systems courseware or want to truly understand consensus, implement Raft from the paper as an exercise — but never deploy your hand-rolled version to anything that matters.

## Further reading

- [ConsistentHashing] — partitioning data once you have replicated state machines
- [ConcurrencyDistributed] — broader concurrency context
- [DatabaseReplication] — replication patterns built on consensus
- [ByzantineFaultTolerance] — when nodes might lie, not just fail
- [DistributedComputingAlgorithms] — gossip, vector clocks, related primitives
