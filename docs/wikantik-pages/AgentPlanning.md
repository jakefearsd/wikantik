---
summary: Plan representations for LLM agents — flat ReAct vs explicit plans vs graphs
  — and when each survives contact with reality.
date: '2026-04-24'
cluster: agentic-ai
related:
- AgenticWorkflowDesign
- AgentReasoning
- AgentLoops
- AiAgentArchitectures
canonical_id: 01KQ12YDRDY7T6HDG0N4W0CT2N
type: article
title: Agent Planning
tags:
- agent
- planning
- task-decomposition
- tree-of-thought
- replanning
status: active
hubs:
- AgenticAiHub
- AgentLoops Hub
---
# Agent Planning: Architectural Regimes and Replanning

Planning in an agentic system answers two fundamental questions: what action to take next, and the appropriate look-ahead horizon. The choice of planning architecture depends on task complexity and requirements for human-in-the-loop validation.

## Planning Regimes

| Regime | Plan Representation | Best Use Case | Limitations |
|---|---|---|---|
| **Implicit (ReAct)** | No explicit plan; one action at a time. | Short tasks (≤ 10 steps), high uncertainty. | Loses track of long-horizon goals. |
| **Explicit Flat Plan** | Sequential list of steps generated upfront. | Straightforward, independent actions. | Brittle; fails if step 1 outcomes change context. |
| **Graph-Based (DAG)** | Nodes as subtasks, edges as dependencies. | Parallel tasks, complex dependencies. | High overhead for simple workflows. |

**Recommendation**: Use **ReAct** for exploration and short tasks; use **Graph/DAG planning** for long-running workflows that require checkpoints, resumes, or multi-agent coordination.

---

## Explicit Planning Drivers

Transition from implicit to explicit planning when:
*   **Human-in-the-Loop**: The plan requires approval before execution.
*   **Cost/Latency**: Pre-calculating steps allows for better progress tracking and caching.
*   **Resumability**: Stored plans allow an agent to pick up at a failed step rather than re-executing from scratch.
*   **Multi-Agent Coordination**: A shared plan acts as the synchronization primitive for specialized agents.

## Implementation Patterns

### 1. The Dynamic Checklist
The simplest representation is a markdown checklist in working memory:
```markdown
- [x] Step 1: Data extraction
- [ ] Step 2: Analysis
- [ ] Step 3: Reporting
```
The agent updates the status per turn. This is sufficient for most sequential tasks.

### 2. Directed Acyclic Graph (DAG)
When subtasks are parallelizable, represent the plan as a JSON-encoded DAG. The orchestrator dispatches nodes whose dependencies are met, while the model focuses on executing the current node.

### 3. Tree of Thought (ToT)
For complex reasoning or competitive scenarios, generate multiple plan branches and evaluate them via a critic model. This increases cost (5-10x) but improves success rates in high-entropy environments.

---

## Replanning and Failure Recovery

Handling execution-time surprises is the core challenge of planning.

*   **Reactive Replanning**: Triggered by tool failure or unexpected results. The model regenerates the *remaining* plan based on current state.
*   **Proactive Checkpoints**: At the end of each logical phase, explicitly ask the model to validate if the current plan still aligns with the goal.
*   **Intent-Level Planning**: Plan at the level of *intent* (e.g., "Find the user's latest invoice") rather than *specific tool calls*. This allows the executor to adapt tool arguments at runtime without invalidating the plan.

### Failure Modes to Prevent
*   **Infinite Replanning**: The agent regenerates the plan every turn without acting. **Mitigation**: Set a replan budget (e.g., max 3) before escalating to a human.
*   **Plan Amnesia**: Replanning causes the agent to forget completed steps. **Mitigation**: Always include the `[done]` history in the replanning prompt.
*   **Grandiose Planning**: The agent generates excessive steps for simple tasks. **Mitigation**: Add system prompt constraints favoring the shortest path.

## Measurement and Evaluation
*   **Replan Rate**: How often does the plan fail execution?
*   **Plan-Execution Alignment**: Does the agent's actual tool-call sequence match the plan?
*   **Latency Impact**: Monitor the percentage of total turn time spent on plan generation vs. execution.
