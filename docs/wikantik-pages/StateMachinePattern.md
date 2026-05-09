---
canonical_id: 01KQ0P44WXHERWG8P8664W6JV4
title: State Machine Pattern
type: article
cluster: design-patterns
status: active
date: '2026-05-15'
summary: Implementing robust state transitions using sealed types and transition tables to eliminate invalid states in complex lifecycles.
tags:
- state-machine
- design-patterns
- sealed-types
- java
auto-generated: false
---
# State Machine Pattern

A Finite State Machine (FSM) models a system as a set of discrete states and the transitions between them. In software engineering, this pattern prevents "illegal states" (e.g., a cancelled order being shipped) by making transitions explicit and type-safe.

## Core Components

1.  **State:** A distinct condition of the system (e.g., `PENDING`, `ACTIVE`).
2.  **Event:** An input that triggers a potential transition.
3.  **Transition:** The move from state A to state B.
4.  **Guard:** A boolean condition that must be met for a transition to occur.
5.  **Action:** A side effect executed during transition (e.g., sending an email).

## Implementation Strategy: Sealed Interfaces (Java 21+)

Modern Java allows for extremely robust FSMs using sealed interfaces and records. This ensures that the set of states is fixed and exhaustive, allowing the compiler to check for missing transition logic.

### Concrete Example: Connection Lifecycle

```java
public sealed interface ConnectionState 
    permits Disconnected, Connecting, Connected, Error {}

public record Disconnected() implements ConnectionState {}
public record Connecting(Instant attemptStarted) implements ConnectionState {}
public record Connected(String sessionId, Instant connectedAt) implements ConnectionState {}
public record Error(String message, int retryCount) implements ConnectionState {}

public class Connection {
    private ConnectionState state = new Disconnected();

    public void connect() {
        state = switch (state) {
            case Disconnected d -> new Connecting(Instant.now());
            case Error e when e.retryCount() < 3 -> new Connecting(Instant.now());
            case Connecting c -> c; // Idempotent
            case Connected c -> throw new IllegalStateException("Already connected");
            default -> throw new IllegalStateException("Cannot connect from " + state);
        };
    }

    public void onEstablished(String sid) {
        if (state instanceof Connecting) {
            this.state = new Connected(sid, Instant.now());
        } else {
            throw new IllegalStateException("Received SID while in state: " + state);
        }
    }
}
```

## Transition Tables

For complex machines with many states, a transition table (often implemented as a `Map<State, Map<Event, State>>`) is cleaner than `switch` blocks.

| From State | Event | To State | Action/Guard |
| :--- | :--- | :--- | :--- |
| `INITIAL` | `START` | `RUNNING` | `initSystem()` |
| `RUNNING` | `PAUSE` | `PAUSED` | `persistContext()` |
| `PAUSED` | `RESUME` | `RUNNING` | `loadContext()` |
| `RUNNING` | `ERROR` | `FAILED` | `logError()` |

## Best Practices

-   **Make Illegal States Unrepresentable:** Don't use a single class with many nullable fields. Use separate classes for each state (like the records above).
-   **Centralize Transitions:** Avoid spreading state-change logic across the entire codebase. A single `TransitionManager` or the State object itself should handle the logic.
-   **Idempotency:** Ensure that triggering the same event in the same state doesn't cause unexpected side effects.
-   **Audit Logging:** FSMs naturally provide an audit trail. Log every transition with the event that triggered it and the timestamp.

## Anti-Patterns

-   **The Boolean Flag Explosion:** Using `isCancelled`, `isShipped`, `isDelivered` instead of an `enum` or `sealed` type. This allows `shipped && cancelled == true`, which is an impossible state.
-   **Implicit State:** Relying on the presence or absence of data (e.g., "if `trackingNumber` is not null, it's shipped") instead of an explicit state indicator.
