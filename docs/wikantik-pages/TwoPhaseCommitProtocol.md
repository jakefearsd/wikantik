---
canonical_id: 01KQ0P44Y3JQQV1VH2ZC2Y19VM
title: Two-Phase Commit Protocol
type: article
cluster: databases
status: active
date: '2026-04-26'
summary: How two-phase commit works for distributed transactions, why it has fallen
  out of favor, and the alternatives (sagas, outbox pattern, event sourcing) that
  modern systems prefer.
tags:
- two-phase-commit
- distributed-transactions
- saga-pattern
- consistency
- databases
related:
- ReadReplicasAndReplication
- IdempotencyPatterns
- LeaderElectionAlgorithms
- MessageQueuePatterns
---
# Two-Phase Commit Protocol

Two-phase commit (2PC) is the classical algorithm for atomic distributed transactions. Multiple databases either all commit or all abort. The math works; the practice has problems.

This page covers how 2PC works, why modern systems mostly avoid it, and the alternatives.

## The protocol

Two phases:

### Phase 1: Prepare

Coordinator asks each participant: "Can you commit?"

Each participant:
1. Performs the transaction; doesn't commit yet
2. Writes prepare record to durable log
3. Responds "yes" or "no"

If all say yes, proceed to commit. If any says no, abort all.

### Phase 2: Commit

Coordinator sends "commit" or "abort" to all participants. Participants act accordingly.

Each participant:
1. Acts on the decision
2. Writes commit/abort to log
3. Releases locks

If all goes well: distributed transaction committed atomically.

## Why it has problems

### Blocking on coordinator failure

If the coordinator crashes after Phase 1 but before Phase 2, participants are stuck. They've prepared; can't commit alone (no permission); can't abort alone (might leave system inconsistent if others committed).

Recovery requires the coordinator to come back or manual intervention.

### Performance

Every transaction waits for all participants twice. Latency increases.

### Locks held throughout

Participants hold locks from prepare until commit/abort. Blocks other transactions.

### Network partitions

If a partition occurs during commit, some participants may commit; others may not. The protocol doesn't fully handle this case.

### Limited scalability

The coordinator is a bottleneck. For high-throughput distributed transactions, 2PC is too slow.

## Why it's still used

### XA transactions in enterprise

Java EE / Jakarta EE has XA — distributed transactions across resources. Banks, insurance, traditional enterprise systems use this.

The performance is acceptable for low-volume; the consistency guarantees are required.

### Within database engines

Some distributed databases use 2PC internally for cross-shard transactions. The implementation is tuned; failures are rare.

### Spanner-like systems

Google Spanner uses a variant of 2PC with TrueTime. Latency is bounded; failures handled. Specialized to Google's infrastructure.

## Modern alternatives

For most distributed-system needs, alternatives are preferred:

### Saga pattern

Break the distributed transaction into a sequence of local transactions, each with a compensating action.

```
Step 1: Reserve inventory (compensate: release inventory)
Step 2: Charge payment (compensate: refund payment)
Step 3: Ship order (compensate: cancel shipment)
```

If any step fails, run compensating actions for completed steps. Reaches eventual consistency.

Pros: no blocking; scalable; partition-tolerant.
Cons: not atomic (intermediate states visible); compensations must be designed; complex to implement correctly.

For most cross-service transactions, sagas are the right pattern.

### Outbox pattern

For "transaction in DB + send message" pattern:

1. Local DB transaction writes both the data change AND a message to an "outbox" table
2. Separate process reads the outbox; sends messages
3. Marks outbox row as sent

Atomic at the database level (single transaction). Eventually consistent for the message side.

### Event sourcing

Record events; derive state. Distributed because each service consumes events independently.

No distributed transactions; eventual consistency; replay capability.

### Idempotency + retries

Make operations idempotent. Retry failures. Eventual success.

Doesn't provide atomicity but handles many use cases. See [IdempotencyPatterns](IdempotencyPatterns).

### Choreography vs. orchestration for sagas

Choreography: services react to events; no central coordinator. Decentralized.
Orchestration: a saga coordinator manages the flow. Centralized.

Trade-offs: choreography is more decoupled; orchestration is easier to reason about.

## When you actually need atomic distributed transactions

The list is shorter than people think:

- Financial transactions across separate ledgers
- Multi-step inventory operations where partial completion is unacceptable
- Compliance requirements that mandate atomicity

For most "this should happen together" requirements, eventual consistency with idempotent retry is sufficient.

## Specific patterns

### Single database transactions

If you can keep the data in one database, you don't need distributed transactions. Start here.

### Service ownership of data

Each service owns its data. Cross-service operations use sagas, not distributed transactions.

### Outbox for cross-service consistency

Most "atomic update + notification" requirements solved by outbox pattern.

### Compensating actions

For sagas: design compensating actions up front. Rollback is part of the design.

## Common failure patterns

- **Reflexively reaching for 2PC.** Often unnecessary; modern alternatives are better.
- **Sagas without compensation design.** Failures lead to inconsistent state.
- **Long-running 2PC transactions.** Locks held; cascading failures.
- **Coordinator as single point of failure.** If using 2PC, the coordinator must be highly available.
- **Mixing transactional and eventual systems.** Unclear semantics.

## A reasonable position

For most modern systems:

1. Keep transactional needs within single databases when possible
2. Use sagas for cross-service consistency
3. Use outbox for atomic data + notification
4. Reach for 2PC only when atomicity is genuinely required and other patterns don't fit
5. Embrace eventual consistency where the business permits

## Further Reading

- [ReadReplicasAndReplication](ReadReplicasAndReplication) — Replication consistency
- [IdempotencyPatterns](IdempotencyPatterns) — For retries
- [LeaderElectionAlgorithms](LeaderElectionAlgorithms) — Strong-consistency alternative
- [MessageQueuePatterns](MessageQueuePatterns) — For event-driven sagas
