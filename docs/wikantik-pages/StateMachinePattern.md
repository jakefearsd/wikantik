---
canonical_id: 01KQ0P44WXHERWG8P8664W6JV4
title: State Machine Pattern
type: article
cluster: design-patterns
status: active
date: '2026-04-26'
summary: When state machines clarify code — the cases where finite state representation
  beats nested conditionals, the implementation patterns, and the trade-offs vs.
  ad hoc state.
tags:
- state-machine
- finite-state
- design-patterns
- workflows
related:
- JavaRecordsAndSealedClasses
- SpecificationPattern
- CleanCodePrinciples
hubs:
- DesignPatterns Hub
---
# State Machine Pattern

A state machine represents a system as discrete states and transitions between them. Each state has a defined set of allowed transitions; invalid transitions are detected at boundaries. For domains with non-trivial lifecycle (orders, workflows, document approval, network connections), the explicit state machine usually beats ad hoc state.

## When state machines fit

Three indicators that suggest state machine modeling:

### Clear states with names

If you can name distinct states (Pending, Confirmed, Shipped, Delivered, Cancelled), the system has a state machine. If states are vague combinations of booleans (`isPending`, `isCancelled`, `wasConfirmed`), explicit modeling helps.

### Transitions matter

If specific actions can only happen in specific states (cancel only when Pending; ship only when Confirmed), making this explicit prevents bugs.

### Audit/trace requirements

Logging or auditing transitions is much easier when transitions are explicit. "Order changed from Pending to Confirmed at 12:00 by user X" is a natural event.

## Implementation patterns

### Enum + transition table

The simplest approach. State is an enum; allowed transitions are a static map:

```java
public enum OrderState {
    PENDING(Set.of(CONFIRMED, CANCELLED)),
    CONFIRMED(Set.of(SHIPPED, CANCELLED)),
    SHIPPED(Set.of(DELIVERED)),
    DELIVERED(Set.of()),
    CANCELLED(Set.of());

    private final Set<OrderState> allowedTransitions;

    public boolean canTransitionTo(OrderState target) {
        return allowedTransitions.contains(target);
    }
}
```

Validation at transition time:

```java
if (!order.state().canTransitionTo(newState)) {
    throw new IllegalStateException("Invalid transition");
}
```

Works for simple state machines.

### Sealed interface with records

Modern Java approach using sealed types:

```java
public sealed interface OrderState
    permits Pending, Confirmed, Shipped, Delivered, Cancelled {}

public record Pending(Instant createdAt) implements OrderState {}
public record Confirmed(Instant confirmedAt, String confirmedBy) implements OrderState {}
public record Shipped(Instant shippedAt, String trackingNumber) implements OrderState {}
public record Delivered(Instant deliveredAt) implements OrderState {}
public record Cancelled(Instant cancelledAt, String reason) implements OrderState {}
```

Each state can carry its own data (the tracking number lives only in Shipped). Pattern matching dispatches behavior per state. See [JavaRecordsAndSealedClasses](JavaRecordsAndSealedClasses).

### State machine library

For complex state machines (many states, complex transitions, side effects per transition), libraries like Spring State Machine or Squirrel-foundation handle:

- State persistence
- Transition triggers and guards
- Entry/exit actions
- Hierarchical states

Useful for workflows; overkill for simple status fields.

## Anti-patterns

### Boolean explosion

```java
public class Order {
    private boolean confirmed;
    private boolean cancelled;
    private boolean shipped;
    private boolean delivered;
}
```

States are implicit; invalid combinations are possible (`shipped` but not `confirmed`?). Always replace with an enum or sealed type.

### Status string

```java
private String status;  // "pending", "confirmed", ...
```

Type-unsafe; typos fail at runtime. Use enum or sealed.

### Transitions enforced everywhere

If every method that changes state must remember to validate the transition, mistakes will happen. Centralize in a `transitionTo(newState)` method or in the state enum itself.

## Specific patterns

### Guards

A guard is a precondition for a transition: "can transition if X." Implement as a method or as a Specification:

```java
public boolean canShip() {
    return state == CONFIRMED && hasItems() && hasShippingAddress();
}
```

### Side effects per transition

Some transitions have associated actions: "on Confirmed → notify customer." Capture these:

```java
public void confirm() {
    transitionTo(CONFIRMED);
    notificationService.sendConfirmation(this);
}
```

Avoid spreading these effects throughout the codebase; centralize per state machine.

### Persistence

Persist the state value (enum or string). Reconstruct the state object on load. The transitions and rules can then be applied consistently across persistence and runtime.

For sealed-type states, persistence is more complex (which type? which fields?). Often the canonical representation is the enum-style state name plus state-specific fields stored separately.

## When state machines are overkill

- Two states (boolean is fine)
- Linear progression with no branching (just check the latest event)
- Transitions don't matter; only the current state matters

## Common failure patterns

- **Implicit state machines.** Multiple booleans pretending the system has no state machine.
- **Transition logic scattered throughout the codebase.** Centralize.
- **No enforcement on transitions.** Any state can become any other state.
- **Treating states as orthogonal flags.** They're not; they're alternatives.
- **State machine library for trivial cases.** Enum + transition map is enough for most.

## Further Reading

- [JavaRecordsAndSealedClasses](JavaRecordsAndSealedClasses) — Sealed types for state representation
- [SpecificationPattern](SpecificationPattern) — Guards as specifications
- [CleanCodePrinciples](CleanCodePrinciples) — Why explicit state beats implicit
- [DesignPatterns Hub](DesignPatterns+Hub) — Cluster index
