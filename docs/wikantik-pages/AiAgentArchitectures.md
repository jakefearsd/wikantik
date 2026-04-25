---
canonical_id: 01KQ12YDRMKEWE1R0HDE5THSC3
title: Ai Agent Architectures
type: article
cluster: agentic-ai
status: active
date: '2026-04-24'
tags:
- agent
- architecture
- multi-agent
- react
- swe-agent
summary: ReAct, Reflexion, Plan-and-Execute, supervisor-worker, debate, and SWE-agent
  — what each actually delivers and when the simpler answer wins.
related:
- AgenticWorkflowDesign
- AgentPlanning
- AgentReasoning
- AgentLoops
hubs:
- AgenticAi Hub
---
# AI Agent Architectures

There are five or six named architectures that show up in every LLM-agent paper. This page maps each to the situations where it earns its complexity, and flags the ones that look clever in a paper and collapse in production.

## ReAct — the baseline

**Shape:** `Thought → Action → Observation → Thought → ...` in one model, one loop.

**What it's good at:** single-task agents under ~10 steps. Read-heavy tasks (search, retrieval, question answering). Quick prototypes where you want to see if "the model can do this at all."

**What breaks:** long tasks (the loop blows through context), mutating tasks (retries create duplicates), and anything needing checkpoint/resume.

**When to pick:** first thing you build. Don't build anything more sophisticated until ReAct has demonstrably failed on your task.

## Plan-and-Execute

**Shape:** one model call generates a plan; a second loop (ReAct-like) executes each step.

**What it's good at:** tasks where the plan is knowable up front and mostly independent (generate a report over N sources, run a deterministic workflow). Tasks that need human approval before execution.

**What breaks:** dynamic environments where the plan is wrong after step 1; small tasks where planning overhead costs more than it saves.

**When to pick:** the task has an obvious plan you could write on a napkin, and you want progress tracking or human-in-loop gates.

**Skip when:** you're using it because it sounds more "principled" than ReAct. Principle-by-framework isn't a reason.

## Reflexion

**Shape:** ReAct + post-attempt self-critique + retry with critique in context.

**What it's good at:** tasks with objective verification (tests, schema conformance) where the base model sometimes nearly-gets-it. Code generation and fix-then-retry workflows.

**What breaks:** the critique can be wrong (and often is on subjective tasks), adding noise. Cost doubles or triples.

**When to pick:** base model failure rate is ~20–40% and you have objective verification to distinguish right from wrong attempts.

**Alternative:** switch to an extended-reasoning model. Much of Reflexion's value is "think harder" which extended reasoning does natively.

## Supervisor-Worker (Hierarchical)

**Shape:** a "supervisor" agent delegates subtasks to specialist "worker" agents and composes their outputs.

**What it's good at:** genuinely multi-specialist tasks (research + code + review). Cross-team boundaries where specialist tools/prompts matter. Clear separation of concerns.

**What breaks:** the supervisor spends 30% of its tokens arguing with itself about which worker to call. Workers often do what a single agent could have done, with 2× latency.

**Reality check:** measure. If a well-prompted single agent gets within 80% of the supervisor-worker system, delete the supervisor. This happens more often than papers admit.

**When to pick:** your tools and prompts for different subtasks are so distinct that combining them into one agent's system prompt would exceed reasonable length (> 4k tokens of tool defs). The separation pays for itself.

## Debate / Multi-agent deliberation

**Shape:** two or more agents argue a position; a judge picks the winner.

**What it's good at:** fuzzy tasks with no ground truth where perspective diversity improves outcomes (strategic choices, reviews, subjective writing critique).

**What breaks:** compute cost scales with N agents × rounds. The judge becomes the bottleneck — if the judge is weak, the debate produces noise.

**When to pick:** rarely. Usually the task can be solved by a single strong model with better prompting.

## SWE-agent / Repo-level coding agents

**Shape:** specialised ReAct-like loop with an "agent-computer interface" (ACI) — curated tools for file editing, testing, command execution, tuned to match how LLMs actually want to interact with code.

**What it's good at:** modifying real codebases. GitHub issue resolution. SWE-bench and similar.

**Key insight:** the ACI matters more than the model. A strong model with bad tools (raw bash, no file preview, no diff view) loses to a weak model with well-designed tools.

**When to pick:** when your agent's task is "modify code in a repository." Steal liberally from SWE-agent, Aider, Claude Code's harness, and the open-source equivalents.

## Conversational / Slot-filling agents

**Shape:** a state machine where the agent gathers required slots (user fields, parameters) through conversation, then executes once everything is filled.

**What it's good at:** form-like interactions (booking, support triage, onboarding). Situations where the final action needs specific info and the conversation is the user experience.

**What breaks:** trying to force open-ended tasks into this shape. If the slots are unknown ahead of time, ReAct or plan-and-execute will serve better.

**When to pick:** the action is fixed, the missing inputs are knowable in advance, and the UX is chat.

## The architecture that isn't an architecture

Most production agents are "ReAct + a bunch of practical defences." A graph-based orchestrator (LangGraph or equivalent), a handful of nodes, checkpointing, tool validation, working memory, summarisation. There's no catchy name for this, but it's what most teams converge on.

The named architectures above are useful vocabulary. They're not mutually exclusive — a graph-based orchestration can contain ReAct loops as nodes and Reflexion-style retries at failure edges. Pick the pieces you need.

## A decision framework

Work top-down:

1. **Is the task ≤ 10 steps, read-only or idempotent?** → ReAct in a graph wrapper. Done.
2. **Does it need human approval or resumable execution?** → Plan-and-execute with explicit plan state.
3. **Is it code-on-a-repo?** → Steal SWE-agent's ACI; don't reinvent.
4. **Is it genuinely multi-specialist?** → Supervisor-worker, but measure against a single-agent baseline.
5. **Does the base model fail on objective tasks?** → Reflexion or extended reasoning; A/B them.
6. **Something exotic?** → Ignore the paper until you've tried the simpler options.

## What doesn't work (yet) in production

- **Fully autonomous long-horizon agents.** Running unsupervised for hours, making strategic decisions, adapting to new information — current models are not reliable enough for this in high-stakes contexts. Add human checkpoints.
- **Learned agent policies.** Fine-tuning an agent model end-to-end on task success is research-grade, not production-grade. The baseline "good model + good prompt + good tools" beats it for most teams.
- **Large agent societies.** More than ~5 agents in a coordination graph tend to spend most of their tokens on coordination overhead. Reduce agent count; increase individual agent quality.

## Further reading

- [AgenticWorkflowDesign] — the engineering defences every architecture above needs
- [AgentPlanning] — plan representations
- [AgentReasoning] — turn-level reasoning techniques
- [AgentLoops] — failure modes
