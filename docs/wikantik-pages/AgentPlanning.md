---
canonical_id: 01KQ12YDRDY7T6HDG0N4W0CT2N
title: Agent Planning
type: article
cluster: agentic-ai
status: active
date: '2026-04-24'
tags:
- agent
- planning
- task-decomposition
- tree-of-thought
- replanning
summary: Plan representations for LLM agents — flat ReAct vs explicit plans vs
  graphs — and when each survives contact with reality.
related:
- AgenticWorkflowDesign
- AgentReasoning
- AgentLoops
- AiAgentArchitectures
hubs:
- AgenticAi Hub
---
# Agent Planning

Planning in an LLM agent answers two questions: *what will I do next?* and *how far ahead do I plan?* The first is every loop, every turn. The second is an architectural choice, and it has different right answers for different tasks.

Most agents don't need sophisticated planning. The ones that do, need it desperately — and the common upgrade paths look like this.

## Three planning regimes

| Regime | How the plan is represented | Good at | Bad at |
|---|---|---|---|
| **Implicit** (pure ReAct) | No explicit plan; model decides one action at a time | Short tasks (≤ 10 steps), exploration, tasks where the plan changes after every observation | Long tasks; the model loses track of what it was doing |
| **Explicit flat plan** | "Here's my plan: 1. X, 2. Y, 3. Z" generated up front, executed linearly | Tasks with obvious steps and mostly-independent actions | Plans that turn out wrong at step 1; no mechanism to replan |
| **Graph / tree plan** | Nodes = subtasks, edges = dependencies, executable | Parallel subtasks, branches, replanning specific legs | Simpler tasks where the graph overhead swamps the benefit |

**Default recommendation:** ReAct for tasks that finish in under 10 steps, graph planning for anything longer or anything that needs checkpoint/resume. Flat plans live in a narrow middle that's rarely the right answer.

## When to promote from ReAct to explicit planning

You need an explicit plan when at least one is true:

- **Multiple users need to approve or inspect the plan before execution.** A human-in-loop checkpoint between plan and action is the canonical reason.
- **Steps are expensive or slow.** A pre-committed plan lets you show progress, estimate completion, and cache intermediate results.
- **The task spans multiple agents.** A shared plan is how specialists coordinate. See [AiAgentArchitectures].
- **You need to resume from failure.** Pure ReAct has to redo everything after a crash; a stored plan lets you pick up at the failed step.

For everything else, the plan-overhead costs more than it saves. A 5-step task runs faster as ReAct than as "plan → execute step 1 → execute step 2 → ..."

## Planning representations that work

**Task list (Markdown checklist)** — the lowest-friction representation. Stored in working memory, updated per step. Works for any simple sequential task:

```
Goal: cancel user 42's subscription and issue refund

- [x] verify user identity
- [x] check subscription status
- [ ] cancel subscription
- [ ] issue refund for last payment
- [ ] send confirmation to user
```

Agent marks items `[done]`, `[in_progress]`, `[blocked: reason]`. One JSON field, two lines of prompt instruction.

**Directed acyclic graph (DAG)** — when subtasks have dependencies and some can run in parallel:

```json
{
  "nodes": [
    {"id": "A", "task": "fetch user record", "deps": []},
    {"id": "B", "task": "list active subscriptions", "deps": ["A"]},
    {"id": "C", "task": "list past payments", "deps": ["A"]},
    {"id": "D", "task": "cancel each subscription", "deps": ["B"]},
    {"id": "E", "task": "refund last payment", "deps": ["C"]},
    {"id": "F", "task": "send confirmation", "deps": ["D", "E"]}
  ]
}
```

The orchestrator (not the model) dispatches nodes whose deps are satisfied. The model generates the graph once and executes per-node.

**Tree of Thought (ToT)** — for tasks where the model should explore multiple strategies. Generate N candidate plans, evaluate each (by another model call), continue the best. Expensive; use only when one-shot planning produces poor plans (e.g. complex reasoning, competitive games). ToT papers show 10–20 point wins on specific benchmarks at 5–10× cost; decide whether that ratio pays in your setting.

## Replanning: the hard part

The plan is almost always wrong in some way after step 1. Handling this is the real planning discipline.

- **Reactive replanning.** Detect "step N failed" → regenerate the remaining plan given current state. Default approach. Keep the already-completed steps; the model doesn't redo them.
- **Proactive replanning.** After every N steps, ask the model "is this plan still right?" Cheaper than replanning-on-failure but adds latency.
- **Hybrid.** Always react on failure; proactively replan only at explicit checkpoints (e.g. "end of phase" in a multi-phase task).

Hard signals that trigger replanning:

- A tool returned a result incompatible with the plan (item not found when plan assumed it existed).
- The user intervened mid-execution with new information.
- N consecutive steps produced no progress (measured by unchanged working-memory state).
- A policy check failed (agent tried to take an action outside its permitted scope).

## Planning failures and their defences

**Paralysis / infinite replanning.** Agent regenerates the plan every turn and never executes. Defence: replan budget — max N replans per task; after that, escalate.

**Plan amnesia.** After replan, the agent drops the progress so far. Defence: never regenerate from scratch; pass the current plan state and ask the model to emit a diff or the remaining steps.

**Grandiose plans.** 50-step plan for a 3-step task. Defence: system prompt instructs "prefer the shortest plan that achieves the goal." Add a step-count sanity check; escalate if the plan is wildly bigger than the historical average for similar tasks.

**Brittle plan cells.** Plan says "step 3: call `search_records` with filter X"; filter X is slightly wrong. Defence: plan at the *intent* level, not the *tool* level. Step 3 says "find records matching the user's criteria"; the executor chooses the tool and arguments when the step runs.

## Classical planners as a comparison point

PDDL planners, STRIPS, heuristic search — these are what "planning" used to mean before LLMs. They're fast, sound, and optimal, but they require the world to be fully specified as preconditions and effects. The preconditions for "send the right email to the customer" are essentially unbounded in a real system, which is why classical planning doesn't fit here.

The LLM's contribution is graceful handling of unspecified preconditions. It's a planner that guesses — imperfectly but often adequately — what the unstated constraints are. In return you give up optimality and correctness guarantees, and you add the replanning machinery above.

For tasks with fully specified worlds (robotics with known physics, constrained scheduling), classical planners still win. For fuzzy real-world tasks, LLMs with the scaffolding above are the right tradeoff.

## Measurement

Track plans the same way you track the rest of agent behaviour — see [AgentTesting]:

- **Plan length distribution** — sudden shifts signal regression.
- **Replan rate per task** — high = plans don't survive execution.
- **Planning latency** — initial plan generation is often 30–50% of total task latency; monitor.
- **Plan-execution alignment** — ratio of executed tool calls that were in the original plan. Low alignment means the plan isn't usefully driving execution.

## Further reading

- [AgenticWorkflowDesign] — the outer shape this planning fits into
- [AgentReasoning] — the step-level reasoning strategies (CoT, ToT, reflection)
- [AgentLoops] — failure modes that plans must defend against
- [AiAgentArchitectures] — multi-agent coordination over shared plans
