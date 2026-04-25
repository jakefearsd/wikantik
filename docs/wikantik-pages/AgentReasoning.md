---
canonical_id: 01KQ12YDRF456K824EWF9801KS
title: Agent Reasoning
type: article
cluster: agentic-ai
status: active
date: '2026-04-24'
tags:
- agent
- reasoning
- chain-of-thought
- tree-of-thought
- reflection
summary: When chain-of-thought helps, when reflection pays off, and when the
  extended-reasoning mode in a frontier model just replaces all of the above.
related:
- AgenticWorkflowDesign
- AgentPlanning
- AgentLoops
- ChainOfThoughtReasoning
hubs:
- AgenticAi Hub
---
# Agent Reasoning

Reasoning strategies for LLM agents are a crowded space: CoT, ToT, Reflexion, ReAct, Reason+Act, self-consistency, debate. Most are incremental over a well-prompted single call, and a 2026 frontier model with its built-in extended-reasoning mode often flattens the whole landscape.

Here's what actually earns its cost.

## Chain-of-thought: the baseline

The "show your work" prompt, `"Let's think step by step"` or equivalent. For any non-trivial reasoning task, CoT improves accuracy — often by 10–30 points on math or logic benchmarks, less on factual tasks.

Guidelines:

- On modern instruction-tuned models, CoT is often implicit — the model reasons without being asked. Explicit CoT still helps on older or smaller models.
- Use structured CoT (numbered steps, named sub-goals) over free-form for anything you'll parse. "Plan:\n1. ...\n2. ..." beats a prose paragraph.
- Don't surface CoT to end users. Users don't want to read the model's internal debate; they want the answer.

For agents specifically, CoT between tool calls is helpful for deciding *which* tool to call but costs tokens. Reserve full CoT for decision points (tool choice, termination) and suppress it for executing straightforward steps.

## Extended reasoning modes (o1, Claude extended thinking)

2026 frontier models ship with a separate reasoning mode that takes longer, spends more tokens, and arrives at better answers on complex problems. OpenAI's `o1` series, Anthropic's extended thinking, DeepSeek R1, and open-weights models trained on similar traces.

Pattern: spend 2–20× more tokens on reasoning than on the final answer. The answer is often one line; the reasoning chain is many pages.

When to use:

- **Hard math / logic / code** — the gap between regular mode and reasoning mode can be 20+ points on HumanEval, MATH, Codeforces.
- **Ambiguous planning** — when the first plan is likely to be wrong, reasoning mode front-loads the work.
- **High-stakes decisions** — where cost matters less than getting it right.

When not to use:

- **Low-latency interactions** — reasoning mode can take 10–60 seconds per turn. Fine for batch; bad for interactive.
- **Structured-output generation** — JSON extraction, form filling, routine tool calls. Regular mode is a strictly better cost/quality trade here.
- **Volume tasks** — costs 5–20× regular mode. Budget accordingly.

A pragmatic agent design: default to regular mode, promote to reasoning mode on demand ("think harder") when the agent detects it's stuck or when a specific tool annotates that this step is hard.

## Reflection: sometimes worth it

The "Reflexion" pattern: after an attempt, the model critiques its own output, then retries with the critique as context. Published ablations show +5–20 points on agent benchmarks.

Reality check: reflection is a cost multiplier (2–3× tokens per task) for a modest quality bump that isn't always present. Before adding it:

1. Measure without reflection.
2. Add reflection on a sampled subset.
3. Confirm the quality delta is real and justifies the cost.

When reflection pays:

- The base model is weak relative to the task — reflection surfaces errors the model can spot post-hoc but not prospectively.
- The task has objective verification (tests pass, query returns expected shape). Self-critique with ground truth beats self-critique from nowhere.
- Long tasks where early missteps compound. Reflection every N steps catches drift.

When reflection doesn't help:

- The base model is strong — it often produces the right answer first, and reflection adds noise.
- The task is subjective — the critique can't distinguish good from bad.
- Already using extended reasoning — reasoning mode is effectively internalised reflection.

## Tree-of-Thought: occasional win, often overkill

ToT generates N candidate reasoning paths, evaluates each, keeps the best, iterates. Works when one-shot reasoning often lands on a local minimum.

- Cost: O(N) × regular CoT cost.
- Benefit: measurable on specific task classes (puzzles, constrained reasoning, game-like problems).
- Production use: rare. The implementation complexity is high and extended-reasoning modes deliver most of the benefit without the orchestration.

If you need ToT-like exploration, consider:

- **Best-of-N** — generate N answers, pick the one the model rates highest (or majority-vote). Simpler than ToT, often captures most of the gain.
- **Self-consistency** — generate N answers with temperature > 0, take the most common answer. Essentially free over generating one answer.

Self-consistency is underrated. On arithmetic and closed-set reasoning, N=5 with majority vote often matches more sophisticated schemes.

## ReAct and Reason+Act: the working default

ReAct interleaves reasoning and action:

```
Thought: I need to find the user's subscription
Action: lookup_user(id=42)
Observation: {active_subscriptions: 1, last_payment: ...}
Thought: OK, I have the subscription. Now I need to cancel it.
Action: cancel_subscription(user_id=42, sub_id=...)
Observation: ok
Thought: Done. I should also refund the last payment.
...
```

This is the baseline pattern for nearly every production agent. Modern frameworks (LangGraph, CrewAI, OpenAI Assistants) implement it almost identically under the hood.

Tuning hints:

- Drop the `Thought:` channel if the base model reasons implicitly well. You save tokens and lose nothing.
- Keep `Thought:` if you need the reasoning log for debugging or audit.
- The `Observation:` block should be aggressively summarised for older turns — see [AgentMemory].

## Anti-patterns

- **Stacking reasoning strategies.** CoT + reflection + ToT + extended-reasoning — each adds cost; the gains don't stack. Pick one.
- **Asking the agent to grade itself.** It's reliably overconfident. Use a separate judge model or a rubric.
- **Reasoning about deterministic things.** "Think step by step about whether to call `get_user` with id=42" when the code already decided — wasted tokens.
- **Extended reasoning for routine tasks.** You paid 10× for nothing.

## Measurement

The only honest question is whether your reasoning strategy improves your task's outcome per dollar. Fixed rollout eval (see [AgentTesting]) with cost tracked:

- Regular mode: 60% success, $0.02/task
- Regular + reflection: 68% success, $0.06/task
- Extended reasoning: 78% success, $0.25/task

Which wins depends on what a success is worth to you. Know that number.

## Further reading

- [AgenticWorkflowDesign] — how reasoning fits into the loop
- [AgentPlanning] — the planning layer above turn-level reasoning
- [AgentLoops] — failure modes reasoning should defend against
- [ChainOfThoughtReasoning] — deeper on CoT specifically
