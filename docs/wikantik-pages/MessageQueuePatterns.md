---
canonical_id: 01KQ0P44SEC29SY26WNQKAG9HM
title: Message Queue Patterns
type: article
cluster: distributed-systems
status: active
date: '2026-04-26'
summary: How message queues work — the major systems (RabbitMQ, Kafka, SQS), the
  patterns (pub-sub, work queues, request-reply), and the choice criteria for production
  use.
tags:
- message-queue
- rabbitmq
- kafka
- sqs
- async-messaging
related:
- BackgroundJobProcessing
- DeadLetterQueuePatterns
- IdempotencyPatterns
- WebhookPatterns
---
# Message Queue Patterns

Message queues decouple producers from consumers. Producer puts a message; consumer reads it later. Both can scale independently; both can be unavailable temporarily without losing messages.

The category is large: RabbitMQ, Kafka, SQS, NATS, Redis Streams, ActiveMQ. Each has different characteristics. Picking the right one depends on use case.

## What queues solve

### Decoupling

Producer doesn't need to know who consumes. Consumer doesn't need to know who produces.

### Buffering

Producer can produce faster than consumer consumes (briefly). Queue absorbs the burst.

### Reliability

If consumer is down, messages wait. Once consumer comes back, processes the backlog.

### Distribution

One producer; many consumers. Or many producers; one queue. Or fan-out to many subscribers.

## The major patterns

### Work queue

One queue; multiple workers compete to consume. Each message goes to one worker.

Used for: distributing work across workers. Email sending; job processing.

### Pub-sub (publish-subscribe)

One queue (topic); multiple subscribers each get every message.

Used for: notifications, event broadcasting, multiple downstream consumers.

### Request-reply

Producer sends a request message with a reply-to address. Consumer processes; sends response to reply-to.

Used for: async RPC. Less common today as HTTP is usually simpler.

### Fan-out

Producer publishes; many consumers each get a copy. Variant of pub-sub.

### Routing

Messages routed by attribute or topic. RabbitMQ has rich routing (exchanges, bindings).

## The major systems

### RabbitMQ

Mature; full-featured. AMQP protocol; flexible routing.

Strengths: rich routing; pub-sub and work queues; managed offerings.
Weaknesses: more complex than simpler queues; performance ceiling at scale.

For most enterprise messaging needs, RabbitMQ is solid.

### Apache Kafka

Distributed log. Different model — messages stored persistently; consumers track their position.

Strengths: very high throughput; replay capability; ecosystem (Connect, Streams).
Weaknesses: more complex; not really a "queue" (it's a log).

For event-streaming use cases, Kafka is the standard.

### AWS SQS

Cloud-managed queue. Simple; reliable; integrated with AWS.

Strengths: serverless; pay-per-use; standard or FIFO.
Weaknesses: AWS-only; basic features.

For most AWS-hosted apps, SQS is the right default.

### Google Pub/Sub

GCP-native. Pub-sub model.

### Azure Service Bus

Azure-native. Comparable to RabbitMQ in features.

### NATS

Lightweight; high-performance. Modern.

### Redis Streams / lists

Redis can be a queue. Lightweight; fast; but not durable across Redis failures unless configured carefully.

For simple use cases without independent infrastructure, Redis works. For real production, dedicated queues are better.

## Delivery guarantees

### At-most-once

Message delivered once or not at all. No duplicates; possible loss.

Rarely the right answer.

### At-least-once

Message delivered one or more times. No loss; possible duplicates.

The standard for most queues. Consumers must handle duplicates (idempotency).

### Exactly-once

Message delivered exactly once. No loss; no duplicates.

Hard to actually achieve. Often "exactly-once semantics" is at-least-once + idempotent processing, presented as one.

For most use cases, at-least-once + idempotent consumers is the practical choice. See [IdempotencyPatterns](IdempotencyPatterns).

## Ordering guarantees

### Strict ordering

Messages received in the order produced. Required for some use cases (transactions, sequential events).

Standard SQS doesn't guarantee order; SQS FIFO does (with throughput limits).
Kafka guarantees order within a partition.

### No ordering

Messages may arrive in any order. Higher throughput; more flexibility.

For most jobs, ordering doesn't matter; use the higher-throughput option.

## Specific patterns

### DLQ (Dead Letter Queue)

Messages that fail repeatedly go to DLQ. See [DeadLetterQueuePatterns](DeadLetterQueuePatterns).

### Visibility timeout / ACK

When consumer pulls a message, it's invisible to others. Consumer ACKs when done; if no ACK within timeout, message becomes visible again.

Provides at-least-once guarantee. Crashes don't lose messages.

### Priority queues

Multiple queues with different priorities. High-priority work jumps the line.

Most queue systems support this.

### Delayed messages

Schedule a message for future delivery. SQS has delay seconds; some systems have richer scheduling.

Useful for retries, reminders, scheduled work.

### Message TTL

Messages older than TTL get deleted (or moved to DLQ). Prevents queue from filling with stale messages.

## Operational concerns

### Queue depth monitoring

Growing queue = consumer not keeping up. Alert.

### Consumer lag (Kafka)

How far behind real-time the consumer is. Critical metric.

### Failed message handling

Where do messages go that can't be processed? DLQ + alerting.

### Cost

Per-message pricing (SQS) or per-cluster pricing (RabbitMQ, Kafka). Different cost shapes.

## Common failure patterns

- **No DLQ.** Failed messages retry forever or get lost.
- **No idempotency.** Duplicates cause problems.
- **Tight ordering requirements where they aren't needed.** Limits throughput.
- **Loose ordering where it matters.** Reordered events break logic.
- **Self-managed queues without ops investment.** Outages from config.
- **Queue as primary storage.** Queues aren't databases.

## Choosing

| Use case | Pick |
|----------|------|
| AWS-hosted; simple work queue | SQS |
| Pub-sub at scale | Kafka or cloud equivalent |
| Rich routing; multiple patterns | RabbitMQ |
| Event sourcing; replay needed | Kafka |
| High-throughput modern | NATS or Kafka |
| Quick prototype | Redis Streams |

For most modern cloud apps, SQS or its cloud equivalent + Kafka for streaming.

## Further Reading

- [BackgroundJobProcessing](BackgroundJobProcessing) — Common queue use case
- [DeadLetterQueuePatterns](DeadLetterQueuePatterns) — Failed-message handling
- [IdempotencyPatterns](IdempotencyPatterns) — Required for at-least-once
- [WebhookPatterns](WebhookPatterns) — Different async pattern
