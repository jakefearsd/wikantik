---
canonical_id: 01KQ0P44L1796D0PBJ1NEJDY21
title: LangChain Fundamentals
type: article
cluster: agentic-ai
status: active
date: '2026-05-08'
tags:
- langchain
- llm-framework
- lcel
- tool-use
summary: Technical core of the LangChain framework — focusing on LCEL (LangChain Expression Language), tool binding, and the transition from chains to agentic loops.
related:
- AgenticWorkflowDesign
- LangGraphArchitecture
- ToolUse
---
# LangChain Fundamentals

LangChain is a framework for composable LLM applications. While often criticized for its "thick" abstractions, its primary value in a production environment lies in its **standardized interface for tool-calling** and its **Expression Language (LCEL)**, which handles the orchestration of prompt-to-model-to-parser pipelines.

## LCEL: The Orchestration Layer

LangChain Expression Language (LCEL) uses a declarative approach to define chains. It is built on the `Runnable` protocol, which provides a consistent interface for `invoke`, `stream`, and `batch` operations.

### Concrete Example: A Validated Tool-Calling Chain

Instead of manually parsing JSON, LCEL allows you to bind a Pydantic schema directly to the model.

```python
from langchain_openai import ChatOpenAI
from langchain_core.pydantic_v1 import BaseModel, Field

# 1. Define the Tool Schema
class GetWeather(BaseModel):
    location: str = Field(description="The city and state, e.g. San Francisco, CA")

# 2. Bind the Tool to the Model
model = ChatOpenAI(model="gpt-4o").bind_tools([GetWeather])

# 3. Create the Chain with a Parser
chain = model | (lambda x: x.tool_calls[0]['args'] if x.tool_calls else x.content)

# 4. Invoke
result = chain.invoke("What is the weather in Berlin?")
# Result: {'location': 'Berlin'}
```

*Value: This pattern eliminates the "manual regex parsing" failure mode common in v1 LLM apps.*

## The Components that Matter

1.  **Chat Models:** The standardized interface for OpenAI, Anthropic, and local providers (Ollama).
2.  **Output Parsers:** Critical for "Agentic" workflows to ensure the LLM output is structured (JSON/Pydantic) before it hits a tool.
3.  **Document Loaders & Vector Stores:** The foundation of RAG. LangChain provides connectors for 100+ sources, but the real work is in the **Reranking** and **Context Window Management**.

## Architecture Critique: Chains vs. Agents

-   **Chains:** Deterministic, hardcoded sequences. (e.g., `Prompt | Model | Parser`).
-   **Agents:** Non-deterministic. The model is placed in a loop and decides which tool to call.

**Strong Opinion:** Do not use `AgentExecutor` (the legacy LangChain agent). It is a "black box" that is notoriously hard to debug. If you need a loop, build it explicitly using **LangGraph**, which provides a state-machine view of the agentic cycle.

## Further Reading
- [LangGraphArchitecture](LangGraphArchitecture) — Moving beyond simple chains to stateful graphs.
- [AgenticWorkflowDesign](AgenticWorkflowDesign) — The broader patterns of autonomous loops.
- [ToolUse](ToolUse) — Designing the interfaces for your agents.
