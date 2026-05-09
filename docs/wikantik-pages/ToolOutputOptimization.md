---
canonical_id: 01KQ0P44XVNB8EWNENAJF1JM0Q
title: Tool Output Optimization
type: article
cluster: agentic-ai
status: active
date: 2026-05-02T00:00:00Z
summary: Engineering patterns for designing agent-friendly tool outputs that minimize context bloat while maximizing actionable information.
tags:
- tool-output
- agent-design
- tools
- mcp
- agentic-ai
related:
- TokenMetrics
- SkillPerformance
- CustomSkillsArchitecture
hubs:
- AgenticAiHub
auto-generated: false
---

# Tool Output Optimization

In an agentic workflow, tool outputs are injected directly into the Large Language Model's (LLM) context window. Poorly designed outputs cause "context poisoning" through verbosity or force unnecessary tool calls due to missing data. Optimizing tool output is a critical engineering task to ensure agent reliability and cost-efficiency.

## Core Principles

### 1. Signal-to-Noise Ratio (SNR)
The output must provide the maximum amount of actionable information in the minimum number of tokens.
*   **Anti-Pattern:** Returning a full 500-line JSON object when the agent only needs the `status` and `id` fields.
*   **Pattern:** Provide a "summary" mode by default, with an optional `--verbose` flag for deep inspection.

### 2. Structural Predictability
Agents parse structured data more reliably than prose.
*   **Recommended:** JSON, YAML, or consistent line-oriented formats (e.g., `key: value`).
*   **Avoid:** ASCII tables with complex borders (`+---+`), decorative headers, or conversational "preambles" ("I found the following results...").

### 3. Explicit Error States
Never return a success code (e.g., HTTP 200) with an error message in the body.
*   **Pattern:** If a tool fails, return a clear error code and a suggestion for correction.
*   **Example:** `Error: File not found at /src/main. Suggestion: Use 'list_files' to verify the path.`

### 4. Bounded Output (Pagination)
Never dump unlimited results into the context.
*   **Requirement:** All list-based tools must implement a `limit` and `offset` (or `page`) parameter.
*   **Example:** `Showing 10 of 450 matches. Use --page 2 to see more.`

## Output Design Patterns

### The "Summary-First" Pattern
For exploratory tools (like search or directory listing), return a high-level summary and the top N results.
```text
Found 12 matching files.
Top 3:
1. index.js (Modified 2h ago)
2. styles.css (Modified 1d ago)
3. utils.js (Modified 5m ago)
Use 'read_file' for specific content.
```

### The "Context-Aware" Match
When searching or grepping, provide a small window of context around the match so the agent can understand the surroundings without a second tool call.
```text
File: auth.py
Line 42: # Validate session
Line 43: if session.is_expired():  <-- MATCH
Line 44:     return Redirect("/login")
```

### The "Actionable Hint" Pattern
If a tool call is ambiguous, provide the valid options in the error message. This allows the agent to self-correct in the next turn.
```text
Error: Invalid 'region' parameter.
Supported regions: [us-east-1, us-west-2, eu-central-1].
```

## Optimizing for MCP (Model Context Protocol)

When implementing tools via an MCP server:

1.  **Strict Schemas:** Use precise JSON Schema definitions for inputs. If a field only accepts three strings, use an `enum`.
2.  **Describe the Output:** Use the `description` field in the tool definition to tell the agent exactly what format the output will take.
3.  **Include Example Payloads:** Providing a sample input/output pair in the tool description significantly improves the agent's "first-shot" success rate.

## Performance Killers

*   **Verbose Preambles:** "Welcome to Tool v1.0. Initializing..." (Waste of 15-20 tokens per call).
*   **Inconsistent Formatting:** Changing output structure based on the number of results.
*   **ANSI Color Codes:** These appear as raw escape characters (e.g., `\u001b[32m`) in the context, confusing the model and wasting tokens.
*   **Deeply Nested JSON:** Flat structures are easier for models to reference in their reasoning.

## Summary Checklist for New Tools

- [ ] Does it default to a concise summary?
- [ ] Are potentially large outputs paginated or limited?
- [ ] Are errors explicit and helpful?
- [ ] Is the output structured (JSON/YAML/Key-Value)?
- [ ] Does the tool description include an example call?
- [ ] Is all decorative formatting (ASCII art, colors) stripped?

## Further Reading
*   [TokenMetrics](TokenMetrics) — Measuring context efficiency.
*   [SkillPerformance](SkillPerformance) — Latency and reliability benchmarks.
*   [AgenticAi Hub](AgenticAiHub) — Comprehensive cluster index.
