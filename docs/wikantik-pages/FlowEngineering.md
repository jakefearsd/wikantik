---
canonical_id: 01KQQ73TY09BCB35TV94P0B9E1
date: 2026-05-03T00:00:00Z
cluster: agentic-ai
type: article
tags:
- agentic-ai
- flow-engineering
- state-machines
- langgraph
- alphacodium
- agent-architecture
- orchestration
title: Flow Engineering
- type: part-of
  target_id: 01KQEKGD6VT29FGWF8YE9TM671
- type: prerequisite-for
  target_id: 01KQ0P44SXF2N2KAP11ANNF92D
summary: Explores the shift from unconstrained ReAct loops to explicit Flow Engineering.
  Details how to design deterministic, DAG-based state machines (like the AlphaCodium
  pattern) to constrain LLM hallucinations and guarantee robust agent execution.
status: active
---

# Flow Engineering: Taming the Agentic Loop

The initial wave of Agentic AI relied heavily on the **ReAct (Reason + Act)** framework. In ReAct, the LLM is the absolute orchestrator: it sits in an unconstrained `while(True)` loop, deciding what to think, what tool to call, and when to stop.

While mathematically elegant, ReAct is an operational nightmare. It suffers from infinite loops, tool hallucination, and catastrophic failure if the model loses track of its internal state. **Flow Engineering** is the architectural response to this chaos.

## 1. What is Flow Engineering?

Flow Engineering is the practice of removing the orchestration responsibility from the LLM and placing it into a deterministic, code-defined **State Machine** or **Directed Acyclic Graph (DAG)**.

Instead of one massive prompt instructing the model to "solve the problem," the problem is decomposed into a strict graph of cognitive nodes. The LLM only executes the logic within a specific node, and standard software engineering (Python/Go) routes the state to the next node.

## 2. The AlphaCodium Paradigm

The most famous example of Flow Engineering is the AlphaCodium architecture (originally designed for competitive programming). It completely discards the ReAct loop in favor of a rigid, multi-stage flow:

1.  **Problem Reflection**: The LLM is asked *only* to describe the problem requirements and constraints. (State saved).
2.  **Public Test Reasoning**: The LLM explains why the provided public tests result in their respective outputs. (State saved).
3.  **Solution Strategy Generation**: Generate 3 distinct algorithmic approaches. (State saved).
4.  **Strategy Ranking**: A separate LLM call evaluates the 3 strategies and picks the best one.
5.  **Code Generation**: Generate the code for the winning strategy.
6.  **Iterative Fixing**: Run the code against tests. If it fails, pass the stack trace to a "Fixer" node. Loop max 3 times.

**Why this works**: At no point is the LLM asked to "manage" the process. It is only ever asked to perform a micro-task (e.g., "rank these 3 strategies"). The Python orchestration layer handles the persistence and state transitions.

## 3. Implementing State Machines (LangGraph/Temporal)

Modern agent frameworks (like LangGraph or Temporal workflows) are built entirely around Flow Engineering.

### The State Object
The core of a flow is the `State` object. It is a strictly typed schema (e.g., a Pydantic model) that is passed from node to node.
```python
class AgentState(BaseModel):
    original_query: str
    extracted_entities: List[str] = []
    draft_document: Optional[str] = None
    verification_errors: List[str] = []
```

### Nodes and Edges
- **Nodes** are Python functions that take the `State`, call an LLM (or API) to perform a specific task, and return a *mutation* to the `State`.
- **Conditional Edges** are Python functions that inspect the `State` and return the name of the next node to execute. (e.g., `if state.verification_errors: return "FixerNode" else: return "End"`).

## 4. Flow Engineering vs. Test-Time Compute

Flow Engineering and [[TestTimeComputeScaling]] represent two ends of the agentic spectrum:
- **Flow Engineering** relies on *human-designed heuristics*. The developer explicitly maps out the cognitive path (e.g., "Always write tests before code"). It is highly reliable, observable, and cheap, but constrained by human imagination.
- **Test-Time Compute (LATS)** relies on *machine-discovered heuristics*. The tree search algorithm dynamically finds the path. It is computationally expensive and less predictable, but can solve problems the developer doesn't know how to map.

## 5. Conclusion

For 95% of enterprise use cases (RAG pipelines, data extraction, code review bots), unconstrained ReAct is dead. Flow Engineering—treating agents as state machines with LLM-powered nodes—provides the observability, debugging capability, and reliability required for production systems.

## See Also
- [[AiAgentArchitectures]] — Overview of ReAct and Plan-and-Execute.
- [[AgentPlanning]] — Task decomposition strategies.
- [[TestTimeComputeScaling]] — The contrasting approach to agent orchestration.
