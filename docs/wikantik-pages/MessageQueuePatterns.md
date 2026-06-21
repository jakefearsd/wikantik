---
type: article
cluster: distributed-systems
date: '2026-04-26'
title: Message Queue Patterns
tags:
- message-queue
- rabbitmq
- kafka
- sqs
- distributed-systems
summary: Architectural analysis of asynchronous messaging patterns, delivery guarantees
  (at-least-once), and poison pill handling.
related:
- BackgroundJobProcessing
- DeadLetterQueuePatterns
- IdempotencyPatterns
- WebhookPatterns
canonical_id: 01KQ0P44SEC29SY26WNQKAG9HM
auto-generated: false
status: active
---

Message queues decouple service execution by introducing an asynchronous buffer between producers and consumers.

## Core Message Broker Comparison

| Feature | RabbitMQ | Apache Kafka | AWS SQS |
|---|---|---|---|
| **Model** | Push (Broker manages state) | Pull (Consumer tracks offset) | Pull (Stateless API) |
| **Ordering** | Guaranteed per queue | Guaranteed per partition | FIFO queues only |
| **Throughput** | High (tens of K/sec) | Extreme (millions/sec) | Scales infinitely (managed) |
| **Durability** | Ephemeral or Persistent | Persistent (Distributed Log) | Durable (Replicated) |
| **Routing** | Complex (Exchanges/Bindings) | Simple (Topic-based) | Simple (Queue-based) |

## Delivery Guarantees

1. **At-Most-Once:** Messages may be lost, never duplicated. (Rarely acceptable).
2. **At-Least-Once:** Messages are never lost, but may be duplicated (e.g., if a consumer crashes before ACK). **Industry standard.**
   - **Requirement:** Consumers must be **[Idempotent](IdempotencyPatterns)**.
3. **Exactly-Once:** Theoretically impossible in distributed systems without distributed transactions. Usually achieved via a combination of at-least-once delivery and deduplication at the consumer.

## Poison Pills and Backoff Math

A **Poison Pill** is a message that causes a consumer to crash or fail repeatedly. 

### Dead Letter Queues (DLQ)
Messages that fail more than $N$times are moved to a DLQ for manual inspection. This prevents a single malformed message from blocking the entire pipeline.

### Exponential Backoff
When a processing error occurs (e.g., DB is down), the consumer should delay the next retry to avoid a self-inflicted DoS.

$$
\text{Delay}_i = \text{base} \times 2^i + \text{jitter}
$$

*Jitter* is essential to prevent "thundering herd" synchronization across multiple workers.
## Common Patterns

### 1. Work Queues (Competing Consumers)
Multiple workers pull from one queue. Each message is processed by exactly one worker. Used for horizontal scaling of job processing.

### 2. Pub-Sub (Fan-out)
One message is copied to$N$independent queues, each serving a different consumer group. Used for cross-service event notification.

### 3. Change Data Capture (CDC)
Streaming database transaction logs (via Kafka Connect/Debezium) into a queue. Used to maintain read replicas or search indices without modifying application code.

## Operational Checklist
- **Monitor Queue Depth:** An increasing queue size indicates consumer starvation or a failure loop.
- **Set Visibility Timeouts:** Ensure the timeout is longer than the$P99.9$processing time to prevent duplicate deliveries during normal operation.
- **Enable DLQ Alerting:** Every message in a DLQ represents a failure that automated retries could not solve.
- **Limit Message Size:** Large payloads ($>256\text{KB}$) should be stored in object storage (S3), with only the URI passed in the message.
