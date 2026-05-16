---
cluster: Artificial Intelligence
related:
- AgenticArchitecture
- GoodMcpDesign
- WritingANewMcpTool
- FindingTheRightMcpTool
- ToolCalling
- McpIntegration
title: 'Tool Use: Agentic Capabilities and Protocols'
status: active
date: 2026-05-15T00:00:00Z
summary: Technical overview of how AI agents interact with external systems through
  defined tool schemas, the Model Context Protocol (MCP), and robust execution loops.
tags:
- ai
- agents
- mcp
- automation
- architecture
- tool-calling
- function-calling
type: article
canonical_id: 01J7KQTCD38PBFSD7TD6ACJFDE
---

# Tool Use: Agentic Capabilities and Protocols

Tool Use (also known as **Function Calling**) is the mechanism by which a Large Language Model (LLM) transcends static text generation to perform deterministic actions in the physical or digital world. It transforms the model from a chatbot into a reasoning engine capable of orchestrating complex workflows.

## 1. How it Works: The Mechanics

The interaction follows a structured **Observe-Reason-Act** loop. In the Wikantik ecosystem, this is facilitated by the **Model Context Protocol (MCP)**.

### The Tool Call Loop
1.  **Intent Detection**: The model identifies that a user request requires external data (e.g., "Find all pages in the finance cluster") or a mutating action (e.g., "Verify this page").
2.  **Schema Selection**: The model selects the appropriate tool based on JSON schema descriptions provided in the system prompt.
3.  **Parameter Generation**: The model generates the arguments for the tool call, adhering to the required types and formats.
4.  **Dispatch & Execution**: The orchestrator (the client interface) routes the request to the relevant MCP server (Knowledge or Admin). The server executes the deterministic Java code.
5.  **Observation**: The system returns the tool result (the observation) to the model.
6.  **Final Response**: The model reasons over the observation to either provide a final answer or initiate another tool call.

### Schema Enforcement
Tools are defined using **JSON Schema**, which acts as the contract between the model and the code.
- **In Python**: Tools are often defined using **Pydantic** models, which are then converted to JSON schemas for the LLM.
- **In Java (Wikantik)**: Tools implement the `McpTool` interface, which provides a `definition()` method returning a structured `McpSchema.Tool` object.

## 2. How to Develop Tools: The Developer's Path

Developing new capabilities for Wikantik agents involves extending the MCP surface.

### The McpTool Interface
Every tool must implement the `com.wikantik.mcp.tools.McpTool` interface:
```java
public interface McpTool {
    String name(); // The snake_case name used by the agent
    McpSchema.Tool definition(); // The JSON schema description and arguments
    McpSchema.CallToolResult execute(Map<String, Object> arguments); // The implementation logic
}
```

### Strategic Placement
- **Read-Only Tools**: Live in `wikantik-knowledge` and focus on retrieval.
- **Mutating/Admin Tools**: Live in `wikantik-admin-mcp` and handle edits.

For a deep dive into the 36+ tools available and how to wire them, see [MCP Integration](McpIntegration).

### Design Principles (See [Good MCP Design](GoodMcpDesign))
- **Compound Operations**: Bundle mechanical loops on the server. A single `verify_pages` call is more efficient than the agent calling `check_link` forty times.
- **Server vs. Agent Work**: Put deterministic logic (sorting, filtering, iterating) on the server. Save the agent's tokens for judgment and content generation.
- **Idempotency**: Mutating tools should be safe to retry. Writing the same content twice should not result in duplicate pages or errors.

## 3. How to Utilize Tools: The Agentic Strategy

Effective tool use requires the agent to be "MCP-aware"—knowing which tools to reach for and when.

### Selection Strategy (See [Finding the Right MCP Tool](FindingTheRightMcpTool))
- **Orientation**: Use `get_page_for_agent` instead of `get_page` for initial research. It provides a token-budgeted summary and metadata without pulling the full Markdown body.
- **Retrieval**: Use `retrieve_context` for natural language queries and `search_knowledge` for structured entity lookups.
- **Navigation**: Use `list_pages_by_filter` or `traverse_relations` to move through the wiki's hierarchy.

### Error Handling & Hallucination Mitigation
A robust agentic loop does not crash on a malformed tool call; it uses the error as feedback.
- **Validation Errors**: If the model omits a required argument, the server returns a schema error. The model sees this and self-corrects in the next turn.
- **Hallucinated Tools**: If the model calls a non-existent tool, the orchestrator provides a list of available tools to nudge the model back onto the valid path.

### Security: Human-in-the-Loop (HITL)
For high-stakes environments, mutating tools require explicit user approval. The orchestrator pauses the execution, presents the proposed arguments to the human, and only proceeds upon confirmation.

---
**See Also:**
- [Writing a New MCP Tool](WritingANewMcpTool) (Runbook)
- [Agentic Architecture](AgenticArchitecture)
- [Tool Calling](ToolCalling) (Technical Deep Dive)
- [MCP Integration](McpIntegration) (Implementation Guide)
