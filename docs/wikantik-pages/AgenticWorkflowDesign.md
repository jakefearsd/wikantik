---
canonical_id: 01KQ0P44K1796D0PBJ1NEJDY17
title: Agentic Workflow Design
type: article
cluster: agentic-ai
status: active
date: '2026-04-24'
tags:
- agent
- agentic-workflow
- llm-application-architecture
- tool-use
- reliability
summary: Patterns, trade-offs, and failure modes when building AI systems that plan
  and act across multiple tool calls — from simple ReAct loops to production-grade
  graph-based agents.
related:
- AgentLoops
- AgentPlanning
- AgentMemory
- AgentReasoning
- AgentTesting
- AgentObservability
- AiAgentArchitectures
- RagImplementationPatterns
- ToolUse
hubs:
- AgenticAi Hub
---
# Agentic Workflow Design

An agentic workflow is an LLM loop that chooses its own next tool call. That one-line definition hides every interesting design decision: how much autonomy you grant, how state survives between steps, and what you do when the model picks badly — which it will.

This page is a working engineer's map of the design space. It is not neutral. Opinions are marked. If you want a gentler introduction, start at [AgentLoops] for the mechanical shape and [AiAgentArchitectures] for the framework comparison; come back here when you need to make real architectural choices.

## What "agentic" means in practice

A plain prompt-response call is not agentic. A chained sequence of LLM calls where each step is hardcoded (retrieve → rerank → summarize) is not agentic either — that's a *pipeline*.

The system becomes agentic when **the model decides the shape of the next step at runtime**. The minimum you need for that:

- A **tool interface** the model can call (a JSON schema the model fills in).
- A **loop** that hands control back to the model with the tool result appended.
- A **termination condition** the model can signal (usually a special "final answer" tool or a role/state check).

With those three pieces you have a working ReAct-style agent in about 100 lines of Python. With those three pieces you also have roughly ten ways to fail, which is what the rest of this page is about.

## The four patterns worth knowing

Most agentic systems are one of these four patterns. Pick deliberately — the failure modes differ.

| Pattern | When to pick | When it fails | Reference implementation |
|---|---|---|---|
| **ReAct loop** | Single-agent tasks, ≤ 10 steps, tools that are read-only and cheap to retry | Long horizons (context blows up), any action with side effects (duplicate writes), tool schemas the model misformats | LangChain `AgentExecutor`, OpenAI `responses.create` with `tool_choice="auto"` |
| **Plan-and-execute** | Tasks where the plan is obvious up front and steps are mostly independent | Dynamic environments where the plan is wrong after step 1; premature optimisation of a problem ReAct solves faster | LlamaIndex `ReActAgentWorker` in plan mode, Plan-and-Solve prompting |
| **Graph / state-machine** | Multi-agent or multi-role workflows, checkpoint/resume, human-in-loop approval gates | Anything simple — the graph tooling itself becomes the majority of your code | LangGraph, CrewAI flows, SWE-agent's ACI |
| **Hierarchical / supervisor** | Genuinely multi-specialist tasks (e.g. research + code + review as separate agents) | Small teams just reinventing an API; most tasks are single-agent and this adds latency | AutoGen `GroupChat`, OpenAI Swarm, LangGraph supervisor |

**Strong opinion:** most production agents should be graph-based. ReAct is pedagogically clean but production-brittle: the moment you need to checkpoint, retry a single step, swap tools, or let a human approve a mutation, you are reinventing half of LangGraph in your own code. Start with a graph even when the graph has three nodes.

## State: the thing that actually breaks

The interesting design work in an agentic system is state management. Token usage is not the hard problem; *what to keep* is.

Agents accumulate four state channels, and you need to treat them differently:

1. **Scratch reasoning** — the model's chain-of-thought between steps. Safe to truncate aggressively; rarely needed past the current turn.
2. **Tool call history** — every tool invocation and its result. Keep every call *summary*, drop every full tool payload except the most recent. A `read_file` that returned 30k tokens three steps ago should now be a one-line note.
3. **Working memory** — facts the agent has committed to as true (e.g. "user ID is 42"). These need to survive summarisation; pin them as structured data, not prose.
4. **Episodic / long-term memory** — persists across sessions. Separate store, usually a vector DB or SQL table keyed on user. See [AgentMemory] for the cross-session story.

The single highest-leverage change most agent systems need is **structured working memory**. When the agent extracts a fact ("the ticket ID is INC-2291"), write it to a typed slot instead of leaving it in the chat transcript. Claude/GPT will drop it during summarisation otherwise, and you'll watch the agent re-query the same database for the fourth time in a 20-step run.

## Failure modes, ranked by frequency

Observed failure frequencies from production agent deployments (loosely ordered — see [AgentLoops] for specific detection recipes):

1. **Schema drift on tool calls.** Model emits `{"user_id": "42"}` when the schema expects an integer. Fix: validate every tool call with JSON Schema *before* dispatching, feed the validation error back to the model as the tool response. Do not crash the loop on validation failure — prompt it to correct. This alone eliminates ~40% of hang/retry noise.
2. **Silent context overflow.** The framework you picked truncates oldest-first, dropping the original user goal. The agent then finishes a task that's no longer the task it was asked to do. Fix: pin the goal statement as the first user message *and* as a system-level `goal` field the summariser can't drop.
3. **Retry on non-idempotent tools.** Agent retries `send_email` because the first call timed out. User gets two emails. Fix: every mutating tool needs an idempotency key the agent generates once per "intended operation" and re-uses on retries.
4. **Infinite replan loops.** Plan succeeds → re-plans → succeeds → re-plans. Fix: hard step cap (default: 25) and a no-progress detector (if the tool call set in turn N equals the tool call set in turn N-1, stop and escalate).
5. **Hallucinated tool names.** Model calls a tool that doesn't exist, especially under context pressure. Fix: validate the tool name against the registered set and return `{"error": "unknown tool, available: [...]"}` to nudge the next turn. Never throw.
6. **Over-delegation.** Supervisor agent delegates work that the model could have just done, adding three round-trips and doubling cost. Fix: measure it. If a supervisor/worker split has no measurable quality win over a single agent, delete the supervisor.

## A concrete reference shape

Below is the smallest agent architecture I'd deploy in production today. Every piece is there because I've seen its absence cause an outage.

```
                       ┌──────────────────────────┐
  initial goal ───────▶│     Orchestrator Node    │◀──── checkpoint store
                       │  (state machine / graph) │        (SQL / Redis)
                       └────┬──────────────┬──────┘
                            │              │
                   LLM call │              │ tool dispatch
                            ▼              ▼
                       ┌─────────┐    ┌──────────┐
                       │  Model  │    │  Tools   │
                       │  (w/   │    │ (w/ JSON │
                       │ cache) │    │  Schema  │
                       │        │    │ validator│
                       └────┬────┘    └────┬─────┘
                            │              │
                            ▼              ▼
                       ┌──────────────────────────┐
                       │    Observability sink    │
                       │   (traces + evals)       │
                       └──────────────────────────┘
```

What this buys you:

- **Checkpoints at every state transition.** Cost of implementation: one `INSERT` per node. Value: every "the agent died 18 steps in after $4 of tokens" becomes a resume, not a restart.
- **Prompt-cached model calls.** For Claude and OpenAI both, the tool definitions + system prompt are the cacheable prefix. Cache hit rate above 90% on long loops is routine and cuts cost ~10×.
- **Schema-validated tool I/O both directions.** The model gets schema violations back as tool responses so it self-corrects. Your code never sees a malformed arg.
- **Structured traces.** Every LLM call and tool call gets a span with cost, latency, input/output hashes, tool-call validity. LangSmith, Langfuse, or a homegrown Postgres table all work; pick the one you'll actually look at. See [AgentObservability].

## What to skip in v1

Things that sound important but usually aren't until your v2:

- **Self-reflection / self-critique loops.** The classic "ask the agent to grade its own output" adds latency and, in controlled evals, rarely improves accuracy on tasks where the underlying model is already competent. If the base model can't do the task, reflection won't save it. If it can, reflection is overhead.
- **Semantic routing between specialist agents.** Embedding-similarity routing is a plausible-sounding idea that collapses in practice because the embedding doesn't know which agent is *good at* the task, only which is topically close. Use rules or a small classifier.
- **Vector memory for everything.** Chat history summarisation + structured working memory covers 90% of what people reach for vector memory to do, with none of the retrieval noise. See [AgentMemory] for when vector memory *is* the right call.

## Evaluation, because otherwise you're flying blind

Most teams build agents by vibes. Don't. The cheapest useful eval is a fixed set of 20–50 task-rollout pairs stored as JSON, replayed on every prompt or model change. Track:

- Task success (binary, human-labeled on first pass, LLM-judged later once the judge is calibrated against your humans)
- Steps-to-completion (tightly correlated with cost)
- Tool-call validity rate (catches schema drift before users do)
- Total cost and p95 latency per task

When these metrics plateau, you graduate to harder benchmarks — SWE-bench for code agents, τ-bench or agentbench for generalists. See [AgentTesting] and [LlmEvaluationMetrics] for the full discipline.

## Further reading

- [AgentLoops] — the mechanical failure catalogue these patterns defend against
- [AgentPlanning] — plan representations and their trade-offs
- [AgentMemory] — short-term, long-term, and when vector memory earns its keep
- [RagImplementationPatterns] — agents that retrieve before they act
- [ToolUse] — designing the tools, not just the agent
- [AgentObservability] — what to log, what to alert on
- [AgentTesting] — fixed task sets and rollout comparison
