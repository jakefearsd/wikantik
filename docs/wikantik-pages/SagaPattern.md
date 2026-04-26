---
title: Saga Pattern
type: article
cluster: software-architecture
status: active
date: '2026-04-25'
tags:
- saga
- distributed-transactions
- microservices
- compensating-transactions
- event-driven
summary: Saga pattern for distributed transactions across microservices —
  choreography vs orchestration, when each pays, and the failure modes
  unique to compensating-transaction designs.
related:
- EventDrivenArchitecture
- MicroservicesArchitecture
- DomainAndIntegrationEvents
- CqrsPattern
hubs:
- SoftwareArchitecture Hub
---
# Saga Pattern

Saga is the pattern for "I need a transaction, but it spans multiple services / databases." Two-phase commit doesn't work in practice across service boundaries (slow, fragile, blocking). Saga replaces it with a chain of local transactions, each having a compensating action that undoes it if a later step fails.

It's the canonical answer to "how do we handle distributed transactions in a microservices architecture." It's also genuinely complex; getting it right is harder than the diagrams suggest.

## The core idea

A business process becomes a sequence of steps, each in its own service:

```
Place Order:
  1. ReserveInventory      → compensate: ReleaseInventory
  2. ChargePayment         → compensate: RefundPayment
  3. CreateShipment        → compensate: CancelShipment
  4. SendConfirmationEmail → compensate: (nothing, it's idempotent)
```

If step 3 fails, you execute compensations in reverse: cancel-shipment isn't relevant because step 3 never finished, but you do need to refund payment (step 2's compensation) and release inventory (step 1's compensation).

The result: distributed atomicity, achieved through compensations rather than locking.

## Choreography vs orchestration

Two implementation styles.

### Choreography

Each service listens for events; reacts; emits its own events. No central coordinator.

```
OrderService publishes "OrderPlaced"
  ↓
InventoryService consumes; tries to reserve
  ↓ on success: publishes "InventoryReserved"
  ↓ on failure: publishes "InventoryReservationFailed"
  
PaymentService consumes "InventoryReserved"; tries to charge
  ↓ on success: publishes "PaymentCharged"
  ↓ on failure: publishes "PaymentFailed"
                + InventoryService consumes; releases inventory

ShipmentService consumes "PaymentCharged"; tries to ship
  ↓ failure: publishes "ShipmentFailed"
            + PaymentService consumes; refunds
            + InventoryService consumes; releases
```

Each service knows its own logic; the saga emerges from the events. Decoupled.

Strengths:
- Decentralised; each service owns its part.
- Easy to add new participants (just listen for the event).

Weaknesses:
- Hard to reason about the whole flow — it's spread across N services.
- Hard to debug — "why did this saga get stuck" requires tracing across services.
- Cyclic event dependencies are easy to create accidentally.
- No single place to inspect saga state.

Use for: simple sagas (≤ 4 steps); independent teams owning each step; you're comfortable with the debugging cost.

### Orchestration

A central orchestrator (a saga service) drives the flow:

```
OrderSaga:
  step 1: call InventoryService.Reserve(...)
    on failure: terminate saga; publish failure
  step 2: call PaymentService.Charge(...)
    on failure: call InventoryService.Release(...); terminate
  step 3: call ShipmentService.CreateShipment(...)
    on failure: call PaymentService.Refund(...); 
                call InventoryService.Release(...); terminate
  step 4: publish success
```

The orchestrator is itself a stateful service — saga state persisted, durable, observable.

Strengths:
- Centralised logic; readable.
- Saga state inspectable in one place.
- Easier to add new steps or alter flow.
- Built-in observability of saga progress.

Weaknesses:
- Orchestrator is a single point of failure (mitigated by replicating it).
- Tighter coupling — orchestrator knows about all participants.
- More infrastructure (the orchestrator service itself).

Use for: complex sagas (5+ steps, branches, retries); when you need observability of saga state; when the team can own the orchestrator.

**For most production sagas in 2026, orchestration wins.** Tools (Temporal, Camunda, AWS Step Functions, Cadence) make orchestration tractable. The choreography path scales poorly past trivial flows.

## Idempotency: non-negotiable

Saga steps and compensations must be idempotent. The orchestrator may retry; events may be delivered twice; partial failures may leave you uncertain whether a step ran.

For each step:

- Use an idempotency key generated once per step instance.
- The receiving service checks: "have I done this already with this key? Return the prior result. Else perform and record."
- Compensations same way — "have I refunded this payment for this saga? Return idempotent."

Without idempotency, retries during failure produce double-charges, double-refunds, and corrupted state.

## State management

The saga has a state. Persistent, accessible, recoverable:

```
saga_id: ord-2026-04-25-12345
status: in_progress
current_step: 3
steps_completed: [reserve_inventory, charge_payment]
context: {order_id: 42, user_id: 100, ...}
created_at: ...
updated_at: ...
```

Tools:

- **Temporal / Cadence** — workflow engines designed for this; durable execution; built-in retry, compensation, observability. The category leader.
- **AWS Step Functions** — managed; AWS-only.
- **Apache Camel Sagas** — for Java shops on Camel.
- **Custom on Postgres** — for simpler cases; saga state in a table; a worker advances it.

For new orchestration-based sagas in 2026, Temporal is the standard choice. The mental model maps directly to saga semantics; durability and visibility come built in.

## Failure modes

### Forgotten compensations

Step succeeded; later step failed; compensation didn't run because of a bug. State is inconsistent.

Defence: every step has a defined compensation; tested. Sagas are reviewed for compensation correctness, not just happy path.

### Compensation that fails

Compensation is a service call; service calls fail. What if the compensation itself fails?

Strategies:

- **Retry with backoff** — most failures are transient; retry resolves them.
- **Dead letter / human intervention** — if retries exhaust, escalate to human; saga is paused, not lost.
- **Eventual consistency tolerance** — sometimes "the inventory is still reserved" is OK to wait out; not all inconsistencies are urgent.

The saga literature is sparser on "compensation fails permanently" because the answer is application-specific.

### Compensation when prior steps shouldn't undo

Some steps' effects can't be reversed. A confirmation email sent to the customer; you can't unsend.

In these cases, the compensation is "send an apology email" or "do nothing and accept the inconsistency." The pattern admits this; the design must contemplate it.

### Long-running sagas and timeout

Saga in progress for hours / days; what if you need to deploy a new version of the orchestrator? Temporal-style engines version their workflows; older sagas run their original code; new sagas use the new code.

Without this, mid-flight sagas can break on deploy. Plan for it.

### Cascading sagas

Step in saga A triggers saga B, which triggers saga C. Failures cascade across; debugging becomes hard.

Defence: keep saga scope narrow; resist the urge to make every cross-service flow a saga.

## When saga is overkill

Not every multi-step process needs a saga:

- **Tasks with no failure recovery requirement.** "Send these notifications" — failure means notifications didn't go; user notices; manual fix. Saga is overhead.
- **Strict consistency not required.** "Update analytics from this event" — can be eventually consistent without compensation.
- **Single-service operations** even if they touch multiple tables. Database transactions handle it.
- **Idempotent operations.** If retry-until-success works, saga's compensation logic is unused.

Saga is for cases where you genuinely need atomicity-like guarantees across service boundaries with non-idempotent operations.

## Modelling business processes

The saga concept maps cleanly onto business processes that span multiple "departments":

- **Order fulfilment.** Inventory + Payment + Shipment + Notification.
- **Account opening.** Identity verification + KYC + Account creation + Notification.
- **Booking.** Availability check + Hold + Payment + Confirmation.
- **Cancellation.** Notify + Refund + Release reservations + Update accounting.

Each step is a distinct business action; the orchestrator captures the policy linking them.

This is also where Temporal / Camunda's "BPMN-style" diagrams become useful — they're how the business owns the flow, while engineers implement steps.

## Patterns within sagas

- **Pivot transactions.** A step beyond which compensation is no longer possible. After "issue physical certificate," you can't un-issue. Plan for these; design the saga so the pivot point is well-understood.
- **Parallel steps.** Some sagas have non-dependent steps that can run in parallel. Tools support this; do it where it shortens latency.
- **Human-in-loop steps.** A saga can wait for human approval. Temporal / similar handle this with signals; the saga is paused awaiting an external event.
- **Compensation chains.** If step 3 fails, compensate 2 and 1; if step 4 fails, compensate 3, 2, and 1. The orchestration engine handles this.

## A pragmatic adoption path

For a team adopting sagas:

1. **Identify the actual cross-service flows that need atomicity.** Most don't. List the few that do.
2. **Pick orchestration over choreography** for any non-trivial saga.
3. **Adopt Temporal (or Step Functions, or similar)** rather than rolling your own orchestrator.
4. **Design every step to be idempotent.** Test that retry produces the right result.
5. **Test compensation paths.** Don't ship a saga whose compensation has only ever been written, never run.
6. **Monitor sagas in production.** Stuck sagas need attention; failing compensations need alerting.

## Further reading

- [EventDrivenArchitecture] — events as saga substrate
- [MicroservicesArchitecture] — context where sagas matter
- [DomainAndIntegrationEvents] — events between services
- [CqrsPattern] — adjacent pattern; often co-occurs with sagas
