---
canonical_id: 01KQ0P44WXP5CMS1QZ2GFGERVM
title: State Management Patterns
type: article
cluster: design-patterns
status: active
date: '2026-05-15'
tags:
- state-management
- state-machines
- event-driven
- agentic-workflows
- frontend-architecture
summary: Comprehensive guide to state management across the stack, covering frontend reactive state, backend durable workflows, and agentic state-machine designs.
auto-generated: false
---

# State Management Patterns

State management is the architectural discipline of capturing, evolving, and persisting the "current truth" of a system. As systems transition from simple CRUD to complex, long-running processes—particularly in AI-driven or distributed environments—state management moves from a localized implementation detail to a core architectural concern.

## 1. Frontend Reactive State (React/Web)

In the frontend, state management focuses on **synchronizing the UI with underlying data** while maintaining performance through unidirectional data flow.

-   **Component Local State:** Best for UI-only transient state (e.g., `is_open`, `input_value`).
-   **Context API:** Solves "prop-drilling" for global configuration (e.g., `theme`, `locale`). It is not a high-frequency state management tool due to re-render overhead.
-   **Atomic/Global Stores (Zustand, Redux):** Provides granular subscriptions and middleware for complex side effects.
-   **Server State (React Query/SWR):** A specialized pattern that separates *local* state from *cached server data*, handling synchronization, caching, and optimistic updates.

## 2. Agentic State-Machine Designs

When building [Agentic Workflows](AgenticWorkflowDesign), state is not just data—it is a **progression of intent**. AI agents require structured state machines to prevent infinite loops and ensure task completion.

### The "Plan-Act-Reflect" Loop
A common agentic pattern uses a Finite State Machine (FSM) to govern the agent's behavior:

| State | Action | Next State (Success) | Next State (Fail) |
|---|---|---|---|
| **Planning** | LLM generates a task list | **Executing** | **Planning** (Re-plan) |
| **Executing** | Tool use / Code execution | **Reflecting** | **Executing** (Retry) |
| **Reflecting** | LLM evaluates the result | **Planning** (Next Task) | **Executing** (Correction) |
| **Finished** | Final answer returned | - | - |

### Managing Agent Memory
Agent state typically consists of:
1.  **Short-term (Conversation History):** The raw log of messages, often managed via sliding-window truncation.
2.  **Working Memory (Scratchpad):** Structured data extracted during the task (e.g., "extracted_user_id: 42").
3.  **Task Graph:** A representation of what has been done and what remains (DAG).

## 3. Event-Driven Transitions and FSMs

In backend systems, state is often moved via **Event-Driven Transitions**. Instead of imperatively setting `status = 'SHIPPED'`, the system emits a `SHIPPING_LABELED` event, and the state machine transitions the aggregate.

### Advantages of Formal State Machines
-   **Determinism:** Only valid transitions are allowed (e.g., cannot transition from `CANCELLED` to `SHIPPED`).
-   **Auditability:** Every transition is triggered by a discrete event, creating a clear history.
-   **Visualizability:** State graphs can be auto-generated from code to verify business logic.

**Example State Machine Configuration (JSON-based):**
```json
{
  "id": "order_fulfillment",
  "initial": "unpaid",
  "states": {
    "unpaid": {
      "on": { "PAYMENT_RECEIVED": "paid" }
    },
    "paid": {
      "on": { 
        "INVENTORY_RESERVED": "ready_to_ship",
        "CANCELLED": "refund_pending"
      }
    },
    "ready_to_ship": {
      "on": { "SHIPPING_LABEL_GENERATED": "shipped" }
    }
  }
}
```

## 4. Durable Workflows (Temporal Pattern)

For processes that last hours, days, or months, state must survive process restarts and server failures. **Durable Execution** patterns (pioneered by Temporal) ensure that the state of a function—including its local variables and stack—is persisted.

-   **Check-pointing:** The system automatically saves the state after every successful activity.
-   **Replay-based Recovery:** If a worker dies, a new worker recreates the state by replaying the event log (similar to [Event Sourcing](EventSourcing)).

## 5. Decision Matrix: Which Pattern to Use?

| Need | Pattern | Recommended Tooling |
|---|---|---|
| Simple UI Sync | Reactive Hook | `useState` / `Zustand` |
| Long-running Transaction | Saga Pattern | [SagaPattern](SagaPattern) |
| Complex AI Reasoning | FSM / Task Graph | `LangGraph` / `XState` |
| Distributed Reliability | Durable Workflow | `Temporal` / `Azure Durable Functions` |
| Perfect Audit Trail | Event Sourcing | [EventSourcing](EventSourcing) |

## Further Reading
- [AgenticWorkflowDesign](AgenticWorkflowDesign)
- [EventSourcing](EventSourcing)
- [StateMachinePattern](StateMachinePattern)
- [SoftwareArchitecturePatterns](SoftwareArchitecturePatterns)
