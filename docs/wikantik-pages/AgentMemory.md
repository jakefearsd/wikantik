---
canonical_id: 01KQ12YDRBZ9RPVCT0GCH9HG6Y
related:
- AiMemoryAndPersistence
- AgenticWorkflowDesign
- AgentLoops
- ContextWindowManagement
- ContextCompression
- VectorDatabases
summary: How to keep LLM agents from forgetting what matters and remembering what doesn't — four state channels, their storage substrates, and the patterns that survive long-running loops.
tags:
- agent
- memory
- context-window
- vector-memory
- state-management
hubs:
- AgenticAiHub
title: Agent Memory
date: '2026-04-24'
cluster: agentic-ai
status: active
type: article
---

# Agent Memory: State Management and Storage Substrates

Memory in an LLM agent is effectively a state management problem across four distinct channels: reasoning, tool history, working facts, and long-term knowledge. Each channel requires a specific storage substrate and eviction policy.

## The Four Memory Channels

| Channel | Lifetime | Storage Substrate | Content |
|---|---|---|---|
| **Scratch Reasoning** | Single turn | Model Context | Internal "Chain of Thought" or reasoning steps. |
| **Tool History** | Current loop | Model Context (Summarized) | Sequence of tool calls, arguments, and results. |
| **Working Memory** | Current task | System Prompt / Structured Data | Extracted facts (e.g., `user_id`, `plan_status`) that must survive summarization. |
| **Long-Term Memory** | Cross-session | Vector DB / SQL / Graph | User preferences, past conversation summaries, persistent knowledge. |

---

## 1. Scratch Reasoning: Context Management

Reasoning tokens (like Chain-of-Thought) help the model but increase token costs without providing lasting value.

*   **Eviction Policy**: Keep only the **current** turn's reasoning.
*   **Summarization**: Drop prior turns' reasoning during context compression. It is rarely needed for future turns.
*   **Telemetry**: Log full reasoning to an external observer/telemetry store for debugging, rather than keeping it in the model's active context window.

## 2. Tool History: Rolling Summarization

The standard policy of "drop the oldest messages" when context fills up is often a failure mode, as it can delete the user's original goal.

**Policy: Summarization by Age with Pinned Goal**
1.  **Pin the Goal**: Always keep the initial user instruction in the prompt.
2.  **Rolling Summary**: When context reaches a threshold (e.g., 50%), summarize turns 1 through $N-5$ into a concise paragraph, preserving only current turns ($N-4$ to $N$) in full fidelity.
3.  **Preserve Entities**: Ensure the summarization prompt instructs the model to retain IDs, names, and specific values exactly.

## 3. Working Memory: Fact Extraction

Working memory consists of facts the agent discovers that must drive future actions. These should be stored in structured "slots" rather than prose.

*   **Pattern**: Use a JSON block in the system prompt for high-signal facts (e.g., `account_id`, `current_step`).
*   **Update Mechanism**: The agent uses a dedicated `update_working_memory` tool when it discovers new facts.
*   **Benefit**: This block survives all summarization and allows the orchestrator to monitor progress (or stall) programmatically.

## 4. Long-Term Memory: Storage Selection

Selecting the right substrate for long-term memory is critical for retrieval quality.

| Use Case | Substrate |
|---|---|
| **Semantic Recall** | Vector Database (Fuzzy match on past interactions) |
| **User Preferences** | SQL Database (Typed columns for `timezone`, `persona`) |
| **Exact Recall** | Transaction Log / Audit DB (e.g., "Was refund #123 issued?") |
| **Relational Knowledge** | Knowledge Graph (Mapping entities and their relations) |

### Forgetting and Retention
Unbounded memory is an anti-pattern. Every channel needs a retention policy:
*   **Privacy**: Implement a deletion path for GDPR/compliance (deleting specific user nodes/vectors).
*   **TTL**: Apply Time-To-Live to facts that may become stale (e.g., `current_location`).

## Cross-Session Continuity

A minimal continuity stack requires:
1.  **User Context**: Injected into every turn.
2.  **Session Summary**: The last $N$ turns of the previous session summarized and loaded at startup.
3.  **Preference Injection**: Structured facts ("prefers JSON output") placed in the system prompt.

## Verification
Memory quality should be measured via automated evals:
*   **Goal Retention**: Does the final action match the initial instruction after 15+ turns?
*   **Fact Persistence**: Can the agent recall a ticket ID provided 10 turns ago?
*   **Recall Latency**: Monitor the time added by vector retrieval vs. the quality gain.
