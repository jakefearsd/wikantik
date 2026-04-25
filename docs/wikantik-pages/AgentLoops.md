---
canonical_id: 01KQ12YDR96H86F5DJH6W9035G
title: Agent Loops
type: article
cluster: agentic-ai
status: active
date: '2026-04-24'
tags:
- agent
- agent-loop
- failure-modes
- reliability
- llm-orchestration
summary: A working catalogue of how the observe-reason-act loop in an LLM agent breaks
  in production, and the minimum defences each failure mode needs.
related:
- AgenticWorkflowDesign
- AgentPlanning
- AgentMemory
- AgentReasoning
- AgentTesting
- AgentObservability
- ToolUse
hubs:
- AgenticAi Hub
---
# Agent Loops

The agent loop is the thing that turns "LLM answers a question" into "LLM gets something done." It's also the thing that turns $0.002 in tokens into $40 in tokens when you look away. Most of agent engineering is designing the loop so it fails gracefully instead of expensively.

This page is a failure-mode catalogue. For the shape of the loop itself and which pattern (ReAct vs graph vs supervisor) to pick, start at [AgenticWorkflowDesign].

## The minimum loop

A working agent loop is six lines of pseudocode:

```
state = init(goal)
while not terminal(state):
    action = model(state)
    result = dispatch(action)
    state = append(state, action, result)
return extract_answer(state)
```

Every production problem comes from one of those six lines doing something slightly different from what you expected.

## Failures by layer

The loop spans three layers — model, tools, state. Every failure lives in one of them.

### Model-layer failures

**Schema drift on tool calls.** The model emits `{"user_id": "42"}` instead of `{"user_id": 42}`. Frequency: extremely high, especially under context pressure or after summarisation.

- *Defence:* validate every tool call with JSON Schema *before* dispatching. On failure, feed the validation error back as the tool's response message: `{"error": "user_id must be integer, got string '42'"}`. The model self-corrects on the next turn 9 times out of 10.
- *Do not:* throw, retry blindly, or try to coerce types silently. Silent coercion is how you end up deleting user 42 because the model meant user 4.2.

**Hallucinated tool names.** Model calls `search_emails` when your tool is `email_search`. Common when the context has been summarised and the tool spec evicted.

- *Defence:* validate the tool name against the registered set. On miss, return `{"error": "unknown tool 'search_emails'. Available: [email_search, calendar_get, ...]"}`. The model recovers near-perfectly when given the option list.

**Response truncation at max_tokens.** The model is mid-way through a tool call when it hits `max_tokens` and returns incomplete JSON. You get `{"action": "update_record", "args": {"id": 42, "na` and then nothing.

- *Defence:* set a generous `max_tokens` (e.g. 4096 even for short-reply loops — the tokens are free if unused). Parse failures on truncated JSON should surface with a distinct error code: retry once with higher limit, then escalate.

**Rate-limit / 429 responses.** The model API throttles you.

- *Defence:* honour `Retry-After` literally. Don't jitter, don't exponential-backoff faster than the header says. Naive exponential retries are how you compound your way into a 30-minute outage when the provider is degraded.

**Non-determinism at `temperature=0`.** Even at temp 0, outputs can differ between calls — batch size, load, and version drift produce small variances. Your eval comparing "prod output" to "expected output" will fail occasionally for reasons unrelated to your code.

- *Defence:* don't compare exact strings in automated tests. Compare on semantic equivalence (same tool called with semantically same args) or tolerance-based numeric comparison.

### Tool-layer failures

**Retry on non-idempotent tools.** Agent retries `send_email` because the first call timed out. Recipient gets two emails.

- *Defence:* every mutating tool takes an `idempotency_key` that the agent generates once per intended operation. The tool's backend dedupes on that key. If the agent can't be trusted to generate stable keys, the orchestrator generates them and substitutes at dispatch time.

**Slow tool blocking the loop.** A `fetch_document` tool that normally takes 2s takes 40s, and your whole agent stalls. You're also likely blocking other concurrent agents.

- *Defence:* per-tool timeouts (aggressive — 10–30s is reasonable for most things). On timeout, return `{"error": "timed_out after 30s"}` to the model. The agent will often pivot — "ok, I'll try a different approach."

**Tool returns an implicit error.** HTTP 200 with `{"status": "failed"}` in the body. The agent parses the outer success and proceeds on bad data.

- *Defence:* normalise at the tool boundary. Every tool wrapper converts backend errors into the same envelope: `{"ok": false, "error": "...", "retryable": bool}`. Agents see a consistent shape and you stop seeing "the agent confidently reported success" bugs.

**Tool spec drift.** Backend team adds a required field to `create_ticket`; your tool spec still says it's optional. Loop hangs on "missing required field."

- *Defence:* generate tool specs from the same source of truth the backend uses (OpenAPI spec, protobuf definition). Review tool specs in the same PR as backend changes. If this isn't possible, add a weekly health check that dispatches one canonical call per tool and alerts on regressions.

### State-layer failures

**Silent context overflow.** The oldest messages — including the original user goal — get truncated. The agent optimises for whatever it thinks the goal is now, which is "whatever the most recent message said."

- *Defence:* pin the goal as the first user message *and* duplicate it into a persistent `goal:` slot in the system prompt that your summariser is instructed never to drop. Store the goal with the conversation and reassert it explicitly every N turns.

**Infinite replan loop.** Plan → attempt → fail → replan → attempt same thing → fail → replan. Cost accrues linearly in steps.

- *Defence:* (1) hard step cap (default: 25); (2) no-progress detector — if the set of tool calls in turn N equals turn N-1, stop; (3) escalate on cap hit instead of silently returning "I couldn't complete the task," so humans see the stuck cases.

**Memory saturation with irrelevant history.** A 30-step loop ends up with 80% of its context spent on tool responses from step 3.

- *Defence:* summarise old turns aggressively. Every tool response older than 3 turns should be replaced with a one-line summary: "Step 7: read_file(report.pdf) → 12-page document about Q4 revenue; key fact: $1.2M." Keep the fact, drop the payload.

**Stale working memory.** The agent noted "user is an admin" in turn 2; in turn 20 it's still acting on that assumption even though the user role field has actually changed.

- *Defence:* typed working-memory slots have TTLs or invalidation hooks. If this is too heavy, at minimum annotate working-memory facts with the turn they were extracted in, and have the model re-verify facts older than N turns before relying on them.

## Cost failure modes

These don't cause crashes, they cause bills.

**Tool-response bloat.** A `search_docs` tool returns 50k tokens of raw HTML on every call.

- *Defence:* enforce per-tool response size limits at the dispatch boundary. Truncate with a note: "result truncated from 50000 to 4000 chars — call `fetch_full` with id=X to see the rest."

**Verbose model reasoning.** Chain-of-thought at every step with no control. You pay for every "Let me think step by step about whether to use tool A or B..." paragraph.

- *Defence:* restrict chain-of-thought to decision points, not every turn. Many production systems set `reasoning_effort=low` or equivalent and reserve the deeper reasoning mode for "I'm stuck, think harder" moments.

**Prompt cache miss from rotating system prompt.** Your system prompt includes `current_time=2026-04-24T15:22:18Z`. Cache hit rate: 0%.

- *Defence:* put volatile fields at the *end* of the prompt, never the beginning. Tool definitions and policy go at the top (cacheable for weeks); timestamps and per-request context go at the bottom.

## Observability baseline

You cannot debug what you cannot see. Minimum telemetry per loop:

- Trace ID shared across all LLM calls and tool calls in the loop.
- Per-call span with: input tokens, output tokens, latency ms, tool name if applicable, validation result.
- Loop-level metrics: total steps, total cost, total latency, terminal state (success / max_steps / error).
- Sampled full-transcript dumps (1%) to object storage for post-mortem.

Without this you'll spend half your debugging time re-running the failing task trying to reproduce what happened.

## Further reading

- [AgenticWorkflowDesign] — higher-level patterns, when to pick each
- [AgentPlanning] — plan representations and their trade-offs
- [AgentMemory] — the state-layer defences in more depth
- [AgentReasoning] — reasoning strategies (ReAct, ToT, reflection)
- [AgentTesting] — fixed task sets to catch regressions
- [AgentObservability] — building the traces referenced above
- [ToolUse] — designing the tools themselves
