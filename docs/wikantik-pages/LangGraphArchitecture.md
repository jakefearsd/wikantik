---
canonical_id: 01KQ0P44L1796D0PBJ1NEJDY22
title: LangGraph Architecture
type: article
cluster: agentic-ai
status: active
date: '2026-05-08'
tags:
- langgraph
- agentic-ai
- state-machine
- flow-engineering
summary: Deep dive into LangGraph's state-machine architecture for building reliable, cyclical agentic workflows with built-in persistence and human-in-the-loop gates.
related:
- LangChainFundamentals
- AgenticWorkflowDesign
- FlowEngineering
---
# LangGraph Architecture

LangGraph is a library for building stateful, multi-actor applications with LLMs. Unlike standard LangChain which favors linear "Chains," LangGraph is built on a **State Machine** (Graph) model. This is the foundation of modern **Flow Engineering**.

## Why Graphs?

In a production agentic system, you need three things that chains cannot provide:
1.  **Cycles:** The ability for the agent to go back to a previous step (e.g., "Tool failed, try again").
2.  **State Persistence:** Saving the agent's progress to a database so it can resume after a crash.
3.  **Human-in-the-Loop:** Pausing the agent and waiting for a human to approve a sensitive tool call (e.g., `delete_database`).

## The Core Concept: The State Object

The "State" is the single source of truth passed between every node in the graph. In LangGraph, you define a schema (usually a `TypedDict`) that tracks the conversation history and any extracted data.

### Concrete Example: A Basic ReAct Graph

```python
from typing import TypedDict, Annotated
from langgraph.graph import StateGraph, END
from langchain_core.messages import BaseMessage, HumanMessage
import operator

# 1. Define the State
class AgentState(TypedDict):
    # 'operator.add' tells LangGraph to append new messages instead of overwriting
    messages: Annotated[list[BaseMessage], operator.add]

# 2. Define Nodes (The Logic)
def call_model(state: AgentState):
    response = model.invoke(state["messages"])
    return {"messages": [response]}

def call_tool(state: AgentState):
    # Logic to execute the tool chosen by the model
    return {"messages": [tool_result]}

# 3. Build the Graph
workflow = StateGraph(AgentState)
workflow.add_node("agent", call_model)
workflow.add_node("tools", call_tool)

workflow.set_entry_point("agent")
workflow.add_conditional_edges("agent", should_continue)
workflow.add_edge("tools", "agent")

app = workflow.compile()
```

## Advanced Patterns: The "Checkpoint"

LangGraph’s most powerful feature is its **Checkpointer**. By passing a thread ID, you can save the entire state of the graph to SQLite or Postgres after every node execution.

```python
# Enable persistence
from langgraph.checkpoint.sqlite import SqliteSaver
memory = SqliteSaver.from_conn_string(":memory:")
app = workflow.compile(checkpointer=memory)

# Resume a specific thread
config = {"configurable": {"thread_id": "user_session_42"}}
app.invoke({"messages": [HumanMessage(content="Check my balance")]}, config)
```

## Comparison: LangGraph vs. Legacy Agents

| Feature | LangChain AgentExecutor | LangGraph |
|---|---|---|
| **Control** | "Black Box" (Automatic) | Explicit (You define the nodes) |
| **Cycles** | Limited | Native |
| **State** | Chat History Only | Arbitrary Structured Data |
| **Persistence** | None (Built-in) | Native (First-class) |

**Architectural Insight:** Use LangGraph when your agent needs to be **deterministic at the macro-level** (the paths it can take) but **agentic at the micro-level** (what it does at each node).

## Further Reading
- [AgenticWorkflowDesign](AgenticWorkflowDesign) — Patterns and failure modes.
- [FlowEngineering](FlowEngineering) — The philosophy behind graph-based agents.
- [AgentTesting](AgentTesting) — How to verify non-deterministic graphs.
