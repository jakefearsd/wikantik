---
canonical_id: 01KQ12YDRCXJHHTSSSDRY6JTWR
title: Agent Observability
type: article
cluster: agentic-ai
status: active
date: '2026-04-24'
tags:
- agent
- observability
- tracing
- llm-monitoring
- evaluation
summary: What to instrument in an LLM agent, how the trace model differs from
  HTTP services, and the tools that earn their keep (LangSmith, Langfuse, OpenLLMetry).
related:
- AgenticWorkflowDesign
- AgentTesting
- AgentLoops
- DistributedTracing
- LlmTokenEconomicsAndPricing
hubs:
- AgenticAi Hub
---
# Agent Observability

An LLM agent is a distributed system whose components are a stochastic model, a set of tools that may call anything, and a state that evolves with each call. Debugging without traces is guesswork. The difference between teams that ship reliable agents and teams that don't is usually visible in whether they log every model call and tool call as a span from day one.

This page is what to log, how to structure it, and which tools are worth adopting.

## The three observability layers

Agents need the same three layers as any distributed system — metrics, logs, traces — but the semantics differ.

| Layer | HTTP service | LLM agent |
|---|---|---|
| **Metrics** | req/s, error rate, p50/p95 latency | task success rate, cost per task, tool validity rate, token throughput |
| **Logs** | structured app logs | structured app logs + full LLM inputs/outputs (sampled) |
| **Traces** | one span per service hop | one span per LLM call and one per tool dispatch, nested under a per-task trace |

Metrics tell you that something's wrong. Traces tell you what and where. Logs contain the prompts you need to reproduce. You need all three.

## The canonical trace

One trace per agent task, with spans at every step. Minimum span shape:

```
{
  "trace_id": "task_4f2e...",
  "span_id": "step_3_llm",
  "parent_span_id": "step_3",
  "name": "model.chat",
  "start_ts": ...,
  "end_ts": ...,
  "attributes": {
    "agent_version": "v2.3.1",
    "model": "claude-sonnet-4-6",
    "input_tokens": 2451,
    "output_tokens": 312,
    "cached_tokens": 2100,
    "cost_usd": 0.0091,
    "temperature": 0.0,
    "stop_reason": "tool_use",
    "tool_calls": ["cancel_subscription"],
    "validation_ok": true
  }
}
```

Every tool call gets its own span, nested under the step:

```
{
  "trace_id": "task_4f2e...",
  "span_id": "step_3_tool_cancel",
  "parent_span_id": "step_3",
  "name": "tool.cancel_subscription",
  "attributes": {
    "tool_input_hash": "...",
    "tool_output_hash": "...",
    "tool_duration_ms": 412,
    "tool_status": "ok"
  }
}
```

The attribute set matters more than the trace format. You will query this data constantly; design for the queries you'll actually run.

## Questions the telemetry needs to answer

Work backwards from these:

- "What tasks are failing today, grouped by failure mode?"
- "Why did this specific task fail? Show me the full transcript."
- "How much did each task cost?"
- "Did this recent prompt change improve or regress my eval set?"
- "Which tool has the highest error rate?"
- "Which prompts aren't hitting the cache?"

If any of these takes more than a minute to answer with your current telemetry, you have an observability gap.

## Sampling: what to keep, what to drop

LLM call logs are verbose. A 20-step agent with 30k-token contexts produces ~600k tokens of span data per task. At 1 task/s, that's 2 TB/month. You'll want to sample.

Reasonable policy:

- **Metrics:** 100% — they aggregate cheaply.
- **Trace skeletons** (IDs, timestamps, attributes, no payloads): 100%.
- **Full prompt/response bodies:** 1–5% of successful tasks, 100% of failed tasks. Failures are where you'll need the replay.
- **Tool inputs/outputs:** hash-only by default; full payload on 1% or on failure.

Raise the success-task sampling rate to 100% during incidents, then drop it back.

## Cost tracking

LLM cost is a primary dimension, not a footnote. Log per-call:

- Input tokens, output tokens, cached tokens separately — caching changes cost by 10–100×.
- USD cost (don't compute downstream; the pricing changes and your historical queries get harder).
- Model version and provider.

Aggregate to dashboards:

- Cost per task, p50 and p95.
- Cost by task type.
- Cost trend week-over-week (surfaces prompt bloat early).
- Cache hit rate over time (regressions here compound).

See [LlmTokenEconomicsAndPricing] for the accounting specifics.

## The tools worth using

| Tool | Strengths | When to pick |
|---|---|---|
| **LangSmith** | Deep LangChain/LangGraph integration, excellent UI for trace exploration, built-in eval workflow | You're on LangChain; you want the lowest-friction option |
| **Langfuse** | Open source, model-agnostic, OpenTelemetry-ish, self-hostable | You want self-hosted or you're not on LangChain |
| **OpenLLMetry** | OpenTelemetry instrumentation for LLM frameworks; exports to your existing OTel stack | You already run Datadog/Honeycomb/Jaeger and want LLM data in the same pane |
| **Arize Phoenix** | Open source, eval-centric, strong on embedding drift detection | You care about eval-in-prod more than trace browsing |
| **Homegrown Postgres** | Total control, simple queries, no vendor lock-in | Small team, simple needs, doesn't mind building the UI yourself |

**Strong opinion:** start with Langfuse or OpenLLMetry. Both are open source and model-agnostic. The commercial tools add polish but lock you in; the self-hosted ones give you the data.

## Alerting

Real alerts, pager-worthy:

- **Task success rate drops below baseline** (e.g. 5-minute rolling window < 90% of 24-hour baseline). Usually means a prompt change broke something.
- **Cost per task exceeds threshold** (e.g. p95 > 2× baseline). Catches verbose-reasoning regressions early.
- **Tool error rate spikes** per tool. Catches upstream service degradation before user complaints.
- **Cache hit rate drops** (e.g. below 70% when steady state is 90%). Catches prompt-structure changes that break caching.

Nice-to-have:

- **Prompt-injection detection** — elevated rate of "ignore previous instructions"-style strings in user inputs.
- **Model-switch detection** — if the provider silently swaps your model, token-count distributions shift. Alerting on distribution drift catches this.

## Debugging workflow, concretely

A user reports the agent did something wrong. Ideal path:

1. Find the user's task in the trace UI by user ID + timestamp.
2. Open the full trace. See every LLM call, every tool call, every state transition.
3. Click the failing step. See the full prompt sent, the full response, the tool validation result.
4. Copy the prompt into a playground, reproduce the issue, iterate on a fix.
5. The fix gets added to the rollout eval set — see [AgentTesting].

Without telemetry this cycle is "I can't reproduce it." That's the gap observability closes.

## Privacy and compliance

Full-prompt logging captures everything users said. That has compliance implications:

- **PII in prompts** needs the same protection as PII anywhere else. Redact at log time or log to a protected store.
- **Deletion requests** need to propagate to your trace store. Design the schema with user/account IDs on every trace so deletion is a single index lookup.
- **Retention** — align with your data policy. Most agent telemetry is useful for 30–90 days; aggregate metrics longer, full payloads shorter.

## Instrumentation minimum (if you start today)

```
Every LLM call:          input, output, model, tokens, cost, latency, cache stats
Every tool call:         name, input, output, duration, error, idempotency key
Every task:              user_id, goal, final_state, total_steps, total_cost, terminal_reason
Sampled full transcripts: 1-5% of successes, 100% of failures
One trace ID:            correlates everything above
```

Three hours of work; years of saved debugging time.

## Further reading

- [AgenticWorkflowDesign] — the system being observed
- [AgentTesting] — how telemetry feeds eval replay
- [AgentLoops] — failure modes you'll want to see in traces
- [DistributedTracing] — trace semantics more broadly
- [LlmTokenEconomicsAndPricing] — the cost side of observability
