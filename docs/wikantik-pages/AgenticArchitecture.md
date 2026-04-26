---
title: Agentic Architecture
type: article
cluster: agentic-ai
status: active
date: '2026-04-25'
tags:
- agent
- architecture
- llm
- autonomous-systems
- agentic-design
summary: Higher-level architectural patterns for agentic systems — LLM-as-router,
  pipeline-with-agent-supervisor, parallel agents, hybrid graph-and-agent —
  and the trade-offs each makes.
related:
- AgenticWorkflowDesign
- AiAgentArchitectures
- AgentLoops
- AgentPlanning
- ToolUse
hubs:
- AgenticAi Hub
---
# Agentic Architecture

Where [AgenticWorkflowDesign] focuses on the loop and [AiAgentArchitectures] on the named patterns (ReAct, Plan-and-Execute, etc.), this page is about how agentic components fit into a larger system. Most production deployments aren't pure agents — they're traditional services with agents embedded at specific points.

## The composition spectrum

Three positions a system can take on agency:

| Position | Example | When it fits |
|---|---|---|
| **Tool of an agent** | Application is a tool the agent calls. Agent is in charge. | Open-ended assistant tasks, research helpers |
| **Pipeline with agent steps** | Application is in charge; agent handles certain steps where flexibility is needed | Most production usage; structured workflows with one or two agentic stages |
| **Agent of a tool** | Application is the system; agent is invoked when it needs help (e.g., from a help button). | Mostly traditional with optional agent assistance |

Most production systems are in the middle: a structured workflow where one or two steps need an agent. Pure agent-in-charge architectures are rare in production because they're hard to bound, hard to evaluate, and hard to make compliant.

## Pattern: LLM-as-Router

An LLM examines incoming requests and routes them to specialised handlers (which may themselves be agents, deterministic services, or other LLMs).

```
User query → Classifier LLM → 
  ├─ FAQ handler (deterministic, fast)
  ├─ Account lookup (RAG agent)
  ├─ Refund request (multi-step agent with approval)
  └─ Escalate to human
```

Why it works: most user requests fall into a small number of categories. A cheap router classifies once; the right specialist handles the rest. Saves cost (specialists are cheaper / faster than one all-purpose agent) and improves quality (specialists are tuned for their case).

Failure modes:
- Misclassification cascade. Wrong category routes the request to a handler that can't help. Defence: confidence threshold; on low confidence, route to default or human.
- Drift. The set of handlers grows; the router doesn't update. Periodic eval of routing quality.

## Pattern: Pipeline with embedded agent

A traditional pipeline (validation → enrichment → processing → response) where one stage uses an agent:

```
Request → Validate → Enrich (RAG agent) → Generate response (LLM) → Audit log → Send
```

The validate, audit, and send stages are deterministic. The enrich and generate stages are agentic. The pipeline structure constrains the agent's scope — it doesn't have to decide when to terminate; the pipeline does.

This is the dominant pattern for chatbots, support agents, and most "smart" features. The agent is doing the open-ended work; the pipeline ensures the user gets a response in bounded time with bounded cost.

## Pattern: Parallel agents with synthesiser

For tasks where multiple perspectives or modalities help, run agents in parallel; synthesise.

```
Question → 
  ├─ Search-the-web agent
  ├─ Search-internal-docs agent  
  ├─ Search-recent-conversations agent
  → Synthesiser LLM → Final answer
```

Wins on questions where the right answer is a combination of sources. Costs more (multiple agents); is fast in wall-clock if parallelised.

The synthesiser is critical. A weak synthesiser produces inconsistent or contradictory output; a good one weighs the sources and surfaces conflict. Tune the synthesiser prompt heavily; A/B against simpler "use only source N" baselines.

## Pattern: Agent supervisor over deterministic workers

Inverse of the embedded-agent pattern. A long-running agent decides what to do next; specialised tools or services execute deterministically.

```
                    ┌──────────┐
goal ──────────────▶│  Agent   │
                    │ (planner │
                    │ + state) │
                    └────┬─────┘
                         ▼
        dispatches to one of:
        ├─ DB query tool
        ├─ HTTP fetch tool
        ├─ Email send tool
        └─ Calendar tool
```

The agent has the autonomy; the tools are just deterministic capabilities. Each tool has a narrow contract; the agent composes them.

This is ReAct architecturally. Productionising it requires:

- **Idempotency on tools** — agents retry; non-idempotent tools cause double-billing.
- **Permission scoping** — what tools can the agent invoke; for what scopes.
- **Cost / step caps** — bound runaway loops.
- **Auditability** — every decision logged.

## Pattern: Long-running agent with checkpoints

For tasks lasting minutes to hours: research, complex code generation, multi-step business processes. The agent runs as a graph (LangGraph or similar); state is checkpointed at every transition; human can intervene.

```
Start ─▶ Research ─▶ Plan ─▶ Execute ─▶ Review ─▶ Submit
   │       │          │        │         │
   ▼       ▼          ▼        ▼         ▼
   checkpoint table — resumable, inspectable, interruptible
```

Difference from embedded-agent pattern: the agent is the system, not a step inside one. Production examples: code-generation agents (Devin and similar), research agents, customer-support agents handling complex multi-turn cases.

Operational requirements:

- **Durable state** — the checkpoint table is the source of truth; can survive crashes and restarts.
- **Human-in-the-loop hooks** — at every transition, the agent can pause and ask.
- **Time / cost budgets** — bounded; alert before exceeding.
- **Replay capability** — for debugging, you can re-run a checkpoint sequence.

## Where to draw the autonomy line

For each decision the system makes, ask: deterministic logic, agent decision, or human approval?

| Decision class | Best mechanism |
|---|---|
| Data validation against fixed schema | Deterministic |
| Routing user request to category | Agent (LLM router) |
| Selecting which tool to call | Agent |
| Executing a known operation | Deterministic |
| Composing a response | Agent |
| Triggering a refund > $threshold | Human approval |
| Modifying production data | Deterministic + audit |
| Acknowledging an alert | Agent or human depending on severity |

The pattern: agents handle ambiguous decisions; deterministic code handles unambiguous ones; humans handle decisions with stakes you wouldn't put in code.

This decomposition is the work. Draw the boundaries badly (agent making decisions that should be deterministic, deterministic code where flexibility is needed) and the system disappoints.

## The cost equation

Agentic architectures cost more than their non-agentic equivalents:

- **Inference cost.** Every agent step is an LLM call. 10 steps × $0.01 per step = $0.10 per task; multiply by traffic.
- **Latency.** LLM calls take seconds. Multi-step agents take tens of seconds. User-facing UX needs to handle this — streaming responses, progress indicators, async patterns.
- **Operational cost.** Observability, evaluation, compliance — agentic systems need more of all three than traditional services.

The savings (relative to fully-coded equivalents) come from:

- Less code to write for handling many input variations.
- Faster iteration on behaviour (prompt change vs code change).
- Capabilities that genuinely couldn't be coded (open-ended reasoning).

For tasks where the variation is small and the logic is simple, agentic architecture is overkill. For tasks where the variation is large or the logic is genuinely hard to specify, agents earn their keep.

## Anti-patterns

- **Agent for deterministic tasks.** "Use an LLM to validate this email format." Regex is faster, more reliable, free.
- **Multi-agent where single agent suffices.** "We'll have a researcher agent, a writer agent, and a reviewer agent." Often the writer agent could do all three with a single prompt.
- **Agent without bounds.** No step cap, no cost cap, no termination condition. Runaway loops are a matter of time.
- **Agent without observability.** When it goes wrong (it will), you have no way to debug.
- **Agent on top of unreliable tools.** The agent retries; the tools cascade-fail; the agent retries more. Make tools robust before depending on them in agent context.
- **Skipping the deterministic baseline.** "Will the agent be better than a coded solution?" — measure. Often the coded solution is good enough and 10× cheaper.

## A pragmatic decision tree

For a new feature where agentic might fit:

1. **Can this be solved with a query / template / coded logic?** Try first; baseline.
2. **Can it be solved with a single LLM call (no tools)?** Try second.
3. **Does it need RAG?** Add retrieval; still single-LLM-call.
4. **Does it need tool use?** Add tool use; single agent.
5. **Does it need multi-step planning?** Add a graph orchestrator.
6. **Does it need multiple specialists?** Add supervisor / parallel agents.

Stop at the simplest level that works. Don't skip levels.

## Further reading

- [AgenticWorkflowDesign] — the per-loop engineering
- [AiAgentArchitectures] — named architectural patterns
- [AgentLoops] — failure modes
- [AgentPlanning] — plan representations
- [ToolUse] — tool design
