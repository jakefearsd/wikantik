---
canonical_id: 01KQ0P44RQ9XTZT2ERA1ZCVXWY
title: Leader Election Algorithms
type: article
cluster: distributed-systems
status: active
date: '2026-04-26'
summary: How distributed systems agree on a leader — Raft, Paxos, Bully, Zab — and
  the cases where you need consensus vs. where simpler patterns work.
tags:
- leader-election
- consensus
- raft
- paxos
- distributed-systems
related:
- GossipProtocol
- MessageQueuePatterns
---
# Leader Election Algorithms

In distributed systems, a leader is a node with special responsibilities — typically coordinating writes, deciding ordering, or representing the cluster externally. When the leader fails, the system needs to elect a new one.

This page covers the major algorithms and when each fits.

## Why leadership

### Single coordinator

Without a leader, every node makes decisions. Coordination requires consensus (slow). With a leader, the leader decides; others follow.

### Write coordination

In master-slave databases, the master is the leader. Writes go to master; replicate to slaves.

### Ordering

For systems requiring consistent ordering (event sourcing, log-structured storage), the leader assigns the sequence.

### External representation

The cluster's "address" is the current leader. External clients talk to the leader.

## What's hard

### Network partitions

Two halves of the cluster can each elect a leader. "Split brain" — both think they're leader; both write; data corruption.

Solutions: quorum (majority must agree); fencing (old leader can't write after losing); explicit timeouts.

### Fast failover

When the leader dies, how fast does a new one get elected? Tradeoff: fast detection means more false positives (electing a new leader when the old one is just slow).

### Stable leadership

A leader that flaps in and out causes thrashing. Algorithms try to keep stable leadership when possible.

## Major algorithms

### Raft

The modern dominant choice. Consensus algorithm by Diego Ongaro (2014). Designed for understandability.

Phases:
1. **Leader election**: when no leader, candidates request votes
2. **Log replication**: leader appends to its log; replicates to followers
3. **Safety**: only nodes with up-to-date logs can become leader

Used by:
- etcd
- Consul
- TiDB
- CockroachDB
- many other systems

For new distributed systems, Raft is usually the right choice.

### Paxos

The original consensus algorithm. Predates Raft. Mathematically rigorous; notoriously hard to understand.

Variants: Multi-Paxos, Fast Paxos, Cheap Paxos.

Used by:
- Google Chubby
- Spanner
- Some database systems

Most new systems use Raft instead because it's easier to implement correctly.

### Zab (Zookeeper Atomic Broadcast)

Used by Apache Zookeeper. Variant similar to Raft conceptually. Predates Raft.

Used by:
- Zookeeper
- Systems built on Zookeeper

### Bully Algorithm

Older; simpler. Each node has an ID. The node with the highest ID becomes leader.

When the leader is suspected dead, other nodes broadcast "election." Higher-ID nodes respond. The eventual highest-ID node becomes leader.

Used in some older systems; rarely chosen for new ones (Raft is generally better).

### Ring-based

Nodes form a ring. Election message passes around; collects IDs; eventual highest becomes leader.

Conceptually elegant; doesn't handle partitions as well as Raft.

## How Raft works (simplified)

### States

- **Follower**: passive; waits for leader heartbeats
- **Candidate**: requesting votes
- **Leader**: actively coordinating

### Election

1. Follower's election timeout expires (no heartbeat from leader)
2. Becomes candidate; requests votes from others
3. If gets majority, becomes leader
4. If sees a higher-term leader, steps back to follower
5. If election times out, starts new election with higher term

### Log replication

1. Leader receives client requests
2. Appends to local log
3. Sends to followers
4. Once majority of followers have it, leader commits
5. Notifies followers; they commit

### Safety

- Only one leader per term
- Committed entries can never be lost (provided majority survives)
- Logs are appended; never overwritten

## When you need leader election

### Distributed databases

Master-slave replication; need to elect new master on failure.

### Coordination services

Zookeeper, etcd, Consul. They run leader-elected internally; expose leadership for client use.

### Distributed schedulers

Only one scheduler should make decisions. Election ensures one leader.

### Distributed locks

The "lock holder" is effectively a leader for the lock's resource.

## When you don't need leader election

### Eventually consistent systems

DynamoDB, Cassandra. No leader needed; gossip-based. See [GossipProtocol](GossipProtocol).

### Stateless services

Web servers behind a load balancer. No coordination; no leader needed.

### Single-node systems

Don't introduce leader election if you don't have multiple nodes.

## Practical considerations

### Quorum size

Raft requires majority for decisions. With N nodes, need ⌊N/2⌋+1.

Common cluster sizes: 3 (tolerates 1 failure), 5 (tolerates 2), 7 (tolerates 3).

Larger clusters: more redundancy but slower consensus.

### Election timeout

Typical: 150-300ms.

Too short: false positives (electing a new leader during minor delays).
Too long: slow failover.

### Heartbeat interval

Typical: 50-100ms. Faster than election timeout so a healthy leader keeps election from triggering.

### Network partitions

Minority partition can't elect a leader (no majority). Stays read-only or unavailable until partition heals.

This is correct behavior — the alternative is split brain.

## Don't roll your own

Implementing Raft correctly is hard. Use existing implementations:

- **etcd Raft library**: Go
- **Apache Ratis**: Java
- **Hashicorp Raft**: Go
- **TiKV's Raft**: Rust

For most needs, use a higher-level system (etcd, Consul, Zookeeper) rather than building on Raft directly.

## Common failure patterns

- **Implementing Raft from scratch.** Subtle bugs; use existing library.
- **Leader election without majority.** Split brain.
- **Aggressive election timeout.** False elections; instability.
- **Single-server "leader."** Not really an election; a single point of failure.
- **Misunderstanding consistency model.** Strong consistency requires correct quorum.

## Further Reading

- [GossipProtocol](GossipProtocol) — Eventually-consistent alternative
- [MessageQueuePatterns](MessageQueuePatterns) — Different distributed pattern
