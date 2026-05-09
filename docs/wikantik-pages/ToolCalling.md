---
cluster: agentic-ai
canonical_id: 01KQ0P44XTMXCTG6NHTPKXS5RR
title: Tool Calling
type: article
tags:
- tool
- agent
- function-calling
- pydantic
- reliability
summary: Technical deep dive into tool-calling mechanics, Pydantic schema enforcement, and robust error-handling loops for mitigating model hallucinations.
status: active
date: '2026-04-24'
auto-generated: false
---
# Tool Calling: From Chat to Agency

Tool calling (or function calling) is the mechanism that allows an LLM to interact with deterministic software systems. It transforms the model from a text generator into a reasoning engine for API orchestration.

## 1. Structured Definitions with Pydantic

In production, tools should be defined using typed schemas to ensure validation at the boundary. Pydantic is the industry standard for this in the Python ecosystem.

### Concrete Example: Tool Schema
```python
from pydantic import BaseModel, Field

class GetWeatherArgs(BaseModel):
    """Retrieves current weather for a location."""
    location: str = Field(description="The city and state, e.g. San Francisco, CA")
    unit: str = Field(
        default="celsius", 
        enum=["celsius", "fahrenheit"],
        description="Temperature unit"
    )

# The model sees the JSON schema representation:
print(GetWeatherArgs.model_json_schema())
```

## 2. The Execution Loop

A robust agentic loop does not crash on a malformed tool call; it uses the error as feedback to the model.

### Error-Handling & Hallucination Mitigation
Models often hallucinate parameters or call non-existent tools. The loop must handle these gracefully.

```python
def agent_loop(user_input):
    messages = [{"role": "user", "content": user_input}]
    
    for _ in range(5): # Limit to 5 iterations
        response = llm.chat(messages, tools=my_tools)
        
        if not response.tool_calls:
            return response.content # Final Answer
            
        for call in response.tool_calls:
            try:
                # 1. Validate against schema
                args = validate_tool_args(call.name, call.args)
                # 2. Execute actual code
                result = execute_tool(call.name, args)
                messages.append({"role": "tool", "name": call.name, "content": result})
            except Exception as e:
                # 3. Feed error back to LLM for self-correction
                error_msg = f"Error in tool '{call.name}': {str(e)}. Please correct your arguments."
                messages.append({"role": "tool", "name": call.name, "content": error_msg})
                
    return "Failed to complete task within iteration limit."
```

## 3. Common Hallucination Patterns

1.  **Missing Required Arguments:** The model calls `search_database()` without a `query` string.
    - *Fix:* Return the validation error: `{"error": "Field 'query' is required"}`.
2.  **Imaginary Tools:** The model calls `send_slack_message()` when only `send_email()` is available.
    - *Fix:* Return: `{"error": "Tool not found. Available tools: [send_email, search_db]"}`.
3.  **Type Mismatch:** The model passes a string `"100"` where an integer `100` is required.
    - *Fix:* Use Pydantic's strict mode or automatic coercion, and report failures.

## 4. Security: The Human-in-the-Loop (HITL)

Mutating tools (deleting files, sending money, making public posts) must require explicit approval.

**Pattern: Pre-Execution Interception**
1.  Model emits a `ToolCall` for a "Write" operation.
2.  The Orchestrator pauses the loop.
3.  The UI presents the tool arguments to the user: "Confirm: Send $500 to User B?"
4.  If confirmed, the loop continues. If denied, the orchestrator feeds "Action cancelled by user" back to the LLM.

## 5. Multi-Tool Composition

Sophisticated agents can call multiple tools in parallel (Parallel Tool Calling) or in sequence (Chaining).

**Example Sequence:**
1.  `search_docs(query="authentication")` -> Returns doc IDs.
2.  `read_file(path="auth_docs.md")` -> Returns content.
3.  `summarize_text(text="...")` -> Returns final answer.

The orchestrator must maintain a clean **State Object** that accumulates these observations, ensuring the context doesn't exceed the model's effective reasoning window.
