---
cluster: software-engineering-practices
canonical_id: 01KQ0P44JM4CGZ3BJ3V961PGP0
title: Advanced Skill Patterns
type: article
tags:
- state-machines
- decision-logic
- workflow-engineering
auto-generated: false
date: '2026-05-15'
summary: Architectural patterns for complex decision-making. Finite State Machines
  (FSM), Decision Tables, and Rule Engines for adaptive systems.
---
# Advanced Skill Patterns

Engineering complex decision-making in adaptive systems requires moving beyond `if/else` chains toward formalisms that manage state, uncertainty, and scalability. This page covers the three primary patterns for structured logic: Finite State Machines (FSM), Decision Tables, and Rule Engines.

## 1. Finite State Machines (FSM)

An FSM models a system as a set of discrete states and transitions triggered by inputs. It is the gold standard for **Sequential Logic** (e.g., a checkout process, a multi-turn dialogue).

### Implementation: The State Pattern
Instead of a massive switch statement, encapsulate state-specific behavior in objects.

```python
class State:
    def on_event(self, event): pass

class Idle(State):
    def on_event(self, event):
        if event == "START": return Processing()
        return self

class Processing(State):
    def on_event(self, event):
        if event == "FINISH": return Success()
        if event == "ERROR": return Failed()
        return self

# Orchestrator
current_state = Idle()
current_state = current_state.on_event("START")
```

## 2. Decision Tables

Decision tables are ideal for **Combinatorial Logic** where the outcome depends on a set of independent conditions. They prevent the "Nested If" anti-pattern.

| Is Authenticated? | Has Admin Role? | Resource Level | Action |
|---|---|---|---|
| True | True | Any | ALLOW |
| True | False | Public | ALLOW |
| True | False | Private | DENY |
| False | Any | Any | REDIRECT_TO_LOGIN |

**Implementation Hint:** Store these tables as JSON or CSV. A generic engine reads the input vector, finds the matching row, and returns the action.

## 3. Rule Engines (Production Systems)

For systems with hundreds of volatile rules (e.g., fraud detection, insurance pricing), use a Rule Engine like **Drools** (Java) or **Durable Rules** (Python). These use the **Rete Algorithm** to optimize rule matching.

### Case Study: Dynamic Pricing
Rule: "If customer is Gold AND inventory < 10 AND today is Holiday, apply 5% surcharge."

```python
# durable-rules example
with ruleset('pricing'):
    @when_all(m.status == 'gold', m.inventory < 10, m.is_holiday == True)
    def apply_surcharge(c):
        c.assert_fact({'action': 'surcharge', 'value': 0.05})
```

## 4. Pattern Comparison

| Pattern | Best For | Scaling Limitation |
|---|---|---|
| **FSM** | Workflows, Dialogues | "State Explosion" as combinations grow. |
| **Decision Table** | Permissions, Calculations | Hard to maintain if inputs exceed 5-6 variables. |
| **Rule Engine** | Expert Systems, Fraud | "Hidden Logic" (hard to trace the 'why' across rules). |

## 5. Decision Selection Matrix
1.  Is the sequence of events critical? $\rightarrow$ **FSM**.
2.  Is the logic a set of static business requirements? $\rightarrow$ **Decision Table**.
3.  Does the logic change weekly without code deployments? $\rightarrow$ **Rule Engine**.

## Further Reading
* [AgenticWorkflowDesign](AgenticWorkflowDesign)
* [ErrorHandlingStrategies](ErrorHandlingStrategies)
* [DesignSystems](DesignSystems)
