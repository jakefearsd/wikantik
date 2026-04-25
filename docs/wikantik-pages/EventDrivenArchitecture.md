---
canonical_id: 01KQ12YDTV1P29FG8NWTY40E9K
title: Event Driven Architecture
type: article
cluster: software-architecture
status: active
date: '2026-04-25'
tags:
- event-driven
- messaging
- kafka
- saga
- outbox-pattern
summary: Events vs commands, the outbox pattern, and how to keep an event-driven
  system from becoming an unprovable mess.
related:
- DomainAndIntegrationEvents
- ApacheKafkaFundamentals
- MicroservicesArchitecture
- CqrsPattern
- DistributedTracing
hubs:
- SoftwareArchitecture Hub
---
# Event-Driven Architecture

In an event-driven architecture, components communicate primarily by publishing events ("OrderPlaced") rather than calling each other directly. Subscribers react. The benefit is decoupling: the publisher doesn't need to know who reacts. The cost is that the system's behaviour is now spread across N services and inferable only by tracing.

Get it right and you have a flexible system that scales. Get it wrong and you have a distributed monolith with worse observability. The difference is mostly discipline around three or four patterns.

## Events vs commands vs messages

These get conflated; they shouldn't.

- **Command** — "Do this." A directed instruction. Single intended recipient. Fails if recipient is unavailable or rejects. Synchronous or async, but conceptually request/response.
- **Event** — "This happened." A statement of fact about the past. Many possible reactors. Doesn't fail (the past doesn't unhappen). Consumers each decide what to do.
- **Message** — the umbrella term. Both commands and events are messages.

A common mistake: treating events as commands. "OrderPlaced" really means "now, do the rest of the order processing" — that's a command pretending to be an event. The smell: the publisher cares whether the consumer succeeded.

Healthy heuristic: if you'd be okay with no consumer reacting (because the event is a fact and reactions are optional), it's an event. If you'd alert on no reaction, it's a command misnamed.

## The patterns that earn their keep

### Outbox pattern

The classic problem: you want to atomically (a) update the database and (b) publish an event. Doing (a) and then (b) means crashes between them lose events; doing (b) and then (a) means events for never-committed state.

Solution: write the event into a table in the same transaction as the data change. A separate process reads the outbox table and publishes to the message broker, marking rows as published once confirmed.

```sql
BEGIN;
  UPDATE orders SET status = 'shipped' WHERE id = 42;
  INSERT INTO outbox (event_type, payload) 
    VALUES ('OrderShipped', '{"order_id":42}');
COMMIT;
```

A worker polls or CDC-tails the outbox and publishes. At-least-once delivery; consumers must be idempotent. Standard pattern; widely applicable; non-negotiable if you care about exactly-once-state.

### Idempotent consumers

At-least-once delivery means duplicates. The consumer must produce the same final state whether the event arrives once or three times.

Achieved by:
- Storing the event ID consumed alongside the side effect, and skipping events with a previously-seen ID.
- Designing operations to be naturally idempotent — `set_status('shipped')` instead of `increment_count(1)`.
- Using deduplication windows in the broker (Kafka exactly-once, Pulsar dedup).

Don't deploy event-driven systems without idempotent consumers. The first network blip will create duplicates and the data will be wrong forever.

### Saga (compensating transactions)

For business processes that span multiple services, distributed transactions (2PC) don't work in practice. Saga is the alternative:

1. Each step is a local transaction in one service.
2. Each step has a compensating action that undoes it.
3. If a later step fails, the saga executes compensations in reverse.

```
Place order:
  1. reserve_inventory  →  on_failure: nothing yet, just stop
  2. charge_payment     →  on_failure: release_inventory
  3. ship_order         →  on_failure: refund_payment, release_inventory
```

Implementation:
- **Choreography** — each service listens for events and acts; no central coordinator.
- **Orchestration** — a saga orchestrator service drives the steps.

Choreography is simpler for small flows; orchestration scales better when flows have branching, retries, or human approval. Most production sagas converge on orchestration over time.

### Event sourcing (sometimes)

Store all state changes as a sequence of events; current state is derived by replaying. Powerful for auditability, time-travel debugging, and CQRS read models.

It's also enormous added complexity. Don't event-source by default. Adopt event sourcing for specific aggregates where the audit trail or replay is genuinely valuable (financial transactions, ticket booking history). For everything else, regular state + outbox events is simpler.

## Schema management

Events are an integration contract. Once published, you can't take them back, and consumers depend on the shape.

- **Strong schema, evolved carefully.** Avro, Protobuf, or JSONSchema in a registry (Confluent Schema Registry, Apicurio).
- **Backward-compatible changes by default.** Add fields, never remove or rename. Use deprecation cycles for retired fields.
- **Schema review is part of code review.** A new event type should be reviewed by consumers as much as the publisher.

The teams that get bitten skipped the schema registry, then regretted it for years. Worth the upfront work.

## Topic / channel design

Topic granularity is a recurring decision:

- **Per-aggregate topic** — `orders`, `users`, `payments`. The default. Easy to reason about. Scales reasonably.
- **Per-event-type topic** — `order_placed`, `order_shipped`. More topics, narrower consumers. Better at fine-grained subscriptions but more operational overhead.
- **Single firehose** — all events on one topic. Simple but ungovernable; consumers filter what they want. Don't.

For Kafka specifically, partition design dominates throughput. Pick a partition key with high cardinality and even distribution; key by aggregate ID so events for the same aggregate are ordered. See [ApacheKafkaFundamentals].

## Failure modes

**Lost events.** Publish-without-outbox loses events on crash. Add the outbox.

**Duplicate side-effects.** At-least-once delivery + non-idempotent consumer = double-billed customers. Make the consumer idempotent.

**Out-of-order processing.** Two events for the same aggregate arrive out of order at a consumer. If they have causal dependencies, the consumer corrupts state. Mitigations: partition by aggregate ID (preserves order within partition), or version events with logical timestamps and reject out-of-order updates.

**Schema drift.** Producer ships a schema change; one consumer didn't update; that consumer crashes. Schema registry with compatibility checks blocks this at publish time.

**Cascade storms.** Event A triggers consumer B which publishes event C which triggers consumer D... A flood of events from one upstream change. Defence: rate limiting at consumers, idempotency, bounded retries.

**Hot consumer lag.** One consumer can't keep up; lag grows without bound. Monitor consumer lag per topic per consumer group. Alert at thresholds. Scale consumers horizontally or partition more aggressively.

## Observability

Event-driven systems are tracers' nightmares without effort. A user request hits service A, which publishes event X, which is consumed by B and C, which publish more events...

Minimum:
- **Trace context propagation** — every event carries the W3C `traceparent`. Consumers continue the trace span. OpenTelemetry handles this for major brokers.
- **Per-event metadata** — event ID, timestamp, producer, schema version, trace ID.
- **End-to-end trace UI** — Jaeger, Tempo, Datadog, etc., that can render the full trace across services.

Without this, "why did the user see X" takes hours per investigation. With it, minutes.

## The architecture's hidden cost

Event-driven systems trade *easy reasoning* for *flexibility*. In a synchronous monolith, you can read code top-to-bottom and follow what happens. In an event-driven system, the only way to know what happens after an event is to grep all consumers.

This shows up in:

- **Onboarding time.** New engineers take longer to understand the system.
- **Debugging time.** "Why did this happen" requires tracing across services.
- **Refactoring caution.** Changing event semantics requires consumer-side coordination.

For these reasons, event-driven within a service ("internal events") is rarely worth it. The pattern is for crossing service boundaries where decoupling is genuinely valuable. Inside a service, regular function calls are simpler and faster.

## When to pick this style

Use event-driven for:

- Inter-service communication where N services react to the same upstream change.
- Audit trails.
- Streaming data pipelines.
- Saga / long-running business processes.

Don't use it for:

- Simple request/response inside a service.
- Cases where you actually need synchronous failure feedback to the user. (Use commands, not events, for these.)
- Workflows where the event count would explode (every keystroke generating an event is rarely useful).

## Further reading

- [DomainAndIntegrationEvents] — distinguishing the two
- [ApacheKafkaFundamentals] — the most common substrate
- [MicroservicesArchitecture] — events as the cross-service glue
- [CqrsPattern] — separating command and query sides
- [DistributedTracing] — observability for event flows
