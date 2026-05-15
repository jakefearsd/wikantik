---
related:
- GenerativeAI
- AgenticArchitecture
- LangChainFundamentals
cluster: Artificial Intelligence
canonical_id: 01J7KQTCD38PBFSD7TD6ACJFDE
type: article
tags:
- ai
- agents
- mcp
- automation
- architecture
summary: Technical overview of how AI agents interact with external systems through
  defined tool schemas and protocols like MCP.
status: active
date: 2026-05-15T00:00:00Z
title: 'Tool Use: Agentic Capabilities and Protocols'
---

# Tool Use: Agentic Capabilities and Protocols

Tool Use (also known as Function Calling) is the mechanism by which an LLM transcends static text generation to perform actions in the physical or digital world.

## 1. The Execution Loop

1.  **Intent Detection:** The model identifies that a user request requires external data or action.
2.  **Schema Selection:** The model selects the appropriate tool based on JSON schema descriptions.
3.  **Parameter Generation:** The model generates the arguments for the tool call.
4.  **Observation:** The system executes the tool and returns the result (observation) to the model.

## 2. Model Context Protocol (MCP)

MCP is the open standard used by Wikantik to allow agents to safely research, edit, and verify wiki content. It provides a structured way to expose local and remote tools to any agentic interface.

For architectural patterns, see [Agentic Architecture](AgenticArchitecture).
