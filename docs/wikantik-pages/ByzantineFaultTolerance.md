---
canonical_id: 01KQ0P44MTAXE77VEN5ARES10A
title: Byzantine Fault Tolerance
type: article
cluster: distributed-systems
status: active
date: '2026-04-26'
summary: Byzantine fault tolerance — what it means, why it's harder than crash-fault
  tolerance, the protocols that achieve it, and the practical contexts where you
  actually need it (and the much larger set of contexts where you don't).
tags:
- byzantine-fault-tolerance
- distributed-systems
- consensus
- bft
related:
- EventualConsistency
- CrdtDataStructures
hubs:
- DistributedSystemsHub
---
# Byzantine Fault Tolerance

In a Byzantine fault model, nodes can do anything: lie, send conflicting messages, collude. This is much stronger than the crash-fault model where nodes simply stop.

Byzantine fault tolerance (BFT) is harder, more expensive, and rarely needed. But when you need it (cryptocurrency, certain financial systems, adversarial environments), nothing else works.

## The Byzantine Generals problem

Lamport, Shostak, Pease (1982): generals must agree on attack/retreat. Some generals may be traitors sending conflicting messages.

Result: with f traitors, need at least 3f+1 total generals to reach consensus.

This is the foundational result of BFT.

## Failure models

### Crash failures

Node stops, sends nothing. Detectable (with timeouts).

Most practical systems handle this (Paxos, Raft).

### Omission failures

Node fails to send/receive some messages. Subset of crash.

### Timing failures

Messages arrive late. Asynchronous protocols handle implicitly.

### Byzantine failures

Node behaves arbitrarily — including malicious behavior.

Hardest model. Most expensive to handle.

## Why Byzantine is hard

In crash-fault model:
- Silence = failure
- Lying not possible
- Each node's claim about itself can be trusted

In Byzantine:
- Failed nodes may lie consistently
- Distinguish silence from lies
- Node A might tell B one thing and C another
- Need cryptographic verification or multiple independent sources

## The 3f+1 lower bound

To tolerate f Byzantine faults, need at least 3f+1 nodes.

Why? With 3f total nodes and f Byzantine:
- Honest nodes split: f see one view, f see another, f are Byzantine
- Byzantine can ally with either group, making both groups indistinguishable

Need 3f+1 to break the symmetry: any majority decision (2f+1) contains at least f+1 honest nodes.

## Practical BFT protocols

### PBFT (Practical Byzantine Fault Tolerance)

Castro and Liskov (1999). The breakthrough — first practical BFT for synchronous networks.

- Three-phase commit: pre-prepare, prepare, commit
- Replicas have leader; replace if suspected Byzantine
- O(n²) message complexity per consensus

Used as the basis for many subsequent BFT systems.

### Tendermint

BFT for blockchain. Synchronous voting rounds.

Used in Cosmos, Binance Smart Chain.

### HotStuff

Linear message complexity (vs PBFT's quadratic).

Used in Diem (formerly Libra).

### LibraBFT (DiemBFT)

HotStuff variant. Eventually became Aptos / Sui.

### Algorand

Byzantine agreement for cryptocurrency. Uses VRFs (verifiable random functions) to select committees.

### Honeybadger

Asynchronous BFT. No timing assumptions.

Slower but robust.

## Network assumptions

### Synchronous

Message delays bounded. Easier; many BFT protocols assume this.

### Partially synchronous

Eventually synchronous after some unknown time. Most practical model.

### Asynchronous

No bounds. FLP impossibility: deterministic consensus impossible.

Use randomization to circumvent (Honeybadger).

## When you need BFT

### Open systems

Public cryptocurrencies. Anyone can join; some will be malicious.

### Adversarial environments

Multi-party financial systems. Each party potentially malicious from others' view.

### Cross-organizational

When parties don't trust each other, BFT formalizes the trust assumptions.

### Critical infrastructure

Some military and aerospace applications.

## When you DON'T need BFT

### Single organization

If all nodes are operated by one team, crash-fault tolerance suffices.

Use Raft or Paxos. Much simpler, much cheaper.

### Internal services

Most distributed systems in companies.

### When trust assumptions are clear

Even with multiple parties, if you trust them not to be malicious (just to occasionally fail), you don't need BFT.

The vast majority of distributed systems don't need BFT.

## Cost of BFT

### Performance

PBFT: O(n²) messages per agreement. Throughput limited.

Modern protocols (HotStuff): O(n) but still expensive vs Raft.

### Latency

Multiple rounds of cryptographic verification.

### Operational complexity

More sophisticated protocols. Harder to debug.

### Scale

BFT typically tops out around 100-1000 nodes. Beyond that, hierarchical or sharded approaches.

## Cryptographic primitives

BFT relies on:
- Digital signatures (verify message origin)
- Hash functions (commit to values)
- Sometimes: threshold signatures, VRFs, zero-knowledge proofs

These let nodes prove they aren't lying about messages.

## Real-world examples

### Bitcoin / proof-of-work

Tolerates Byzantine faults via incentives + computational cost. Not strictly BFT in the classical sense but achieves similar properties.

### Ethereum 2.0 / proof-of-stake

Uses Casper FFG (BFT-derived) for finality.

### Hyperledger Fabric

Uses crash-fault Raft by default (private blockchain). BFT optional.

### Permissioned chains

Often use BFT protocols (Tendermint, PBFT variants).

## Common failure patterns

### Using BFT when CFT would do

Most operational distributed systems are inside one organization. BFT is overkill.

### Wrong assumptions about adversary

Some protocols assume adversary is computationally bounded; some assume bounded delays. Get assumptions wrong = security failure.

### Failure correlation

If "Byzantine" failures arise from common bugs (same software), 3f+1 doesn't help — all f+1 honest nodes have same bug.

This is why diversity (different implementations) matters in some BFT systems.

### Timing attacks

Synchronous BFT with bad timing assumptions can fail.

### Implementation bugs

BFT protocols are complex. Implementation bugs effectively introduce Byzantine behavior.

## State Machine Replication (SMR)

The general framework: replicate a deterministic state machine across nodes; consensus on input order.

Both Paxos/Raft (CFT) and PBFT (BFT) can be used as the consensus layer.

SMR with BFT consensus = BFT state machine replication.

## What replaced classical BFT

Modern blockchain influences:
- Proof-of-work (Bitcoin)
- Proof-of-stake (Ethereum, Cardano)
- Variants combining traditional BFT with crypto-economic incentives

These work in fully open settings where pure BFT doesn't scale.

## Practical takeaway

For most engineers building distributed systems:
- You likely don't need BFT
- Raft / Paxos handle the common case
- BFT matters only in genuinely adversarial settings

If you think you need BFT, ask: who exactly is the adversary? What's their incentive? Often the answer reveals you can use simpler approaches (auditing, retroactive verification, trust models).

When you genuinely need BFT, use a proven protocol; don't roll your own.

## Further Reading

- [EventualConsistency](EventualConsistency) — Different consistency model
- [CrdtDataStructures](CrdtDataStructures) — Coordination-free
- [Distributed Systems Hub](DistributedSystemsHub) — Cluster index
