---
title: Chain Of Thought Reasoning
type: article
cluster: agentic-ai
status: active
date: '2026-04-25'
tags:
- chain-of-thought
- prompt-engineering
- llm-reasoning
- extended-thinking
summary: Chain-of-thought prompting — when it helps, when it hurts, and how
  the 2024+ extended-reasoning modes (o1, Claude extended thinking, R1)
  changed what "show your work" means.
related:
- AgentReasoning
- AgenticWorkflowDesign
- PracticalPromptEngineering
hubs:
- AgenticAi Hub
---
# Chain of Thought Reasoning

Chain-of-thought (CoT) prompting is "show your work" for LLMs. Instead of asking for an answer, ask for the reasoning *and* the answer. The model produces both; quality on reasoning-heavy tasks goes up. It's the most-cited prompting technique in the literature, and one of the few that has held up across model generations.

The 2024 introduction of dedicated reasoning modes (o1, Claude extended thinking, DeepSeek R1) shifted the landscape further. Sometimes you don't prompt for chain of thought; the model has it built in.

## Why it works

The intuition: producing intermediate steps gives the model "more compute" between question and answer. Each token generated is conditioned on all previous tokens; reasoning out loud lets the model build up to the answer instead of jumping there.

For tasks where the answer requires multi-step reasoning (math, logic, multi-hop QA), CoT improves accuracy by 10-30 percentage points on standard benchmarks compared to direct answering.

For tasks where the answer is a direct fact lookup ("who is the CEO of OpenAI?"), CoT doesn't help. The model already has the fact; pretending to reason about it just adds tokens.

## Basic CoT prompting

The simplest form, sometimes called "zero-shot CoT":

```
Q: A juggler can juggle 16 balls. Half are golf balls, and half of the
golf balls are blue. How many blue golf balls are there?

Let's think step by step.
```

The model produces:

```
Half of 16 is 8 golf balls.
Half of those are blue: 8/2 = 4.
Answer: 4.
```

The phrase "Let's think step by step" (or any equivalent) is the trigger. On most modern models, just asking the question often elicits CoT implicitly; on smaller or older models, the trigger phrase matters more.

## Few-shot CoT

Provide examples of question-with-reasoning before the actual question:

```
Q: Roger has 5 tennis balls. He buys 2 cans of 3 balls each. How many balls does he have?
A: Roger started with 5. 2 cans × 3 = 6 new balls. 5 + 6 = 11.

Q: A juggler has 16 balls; half are golf balls; half of those are blue. How many blue golf balls?
A:
```

Few-shot examples calibrate the format and depth of reasoning. For specialised tasks (legal reasoning, medical diagnostics), few-shot examples in the appropriate style produce noticeably better reasoning.

## Self-consistency

Run CoT multiple times at temperature > 0. Take the majority answer.

```python
answers = [model.complete(prompt, temperature=0.7) for _ in range(5)]
final = most_common([extract_answer(a) for a in answers])
```

Cheap; reliable improvement on reasoning tasks. Effectively averages out noise in the reasoning process.

When self-consistency doesn't help: tasks where the answer is fundamentally hard for the model. If 5 attempts produce 5 different wrong answers, voting doesn't fix the underlying gap.

## Tree-of-thoughts and friends

Generate multiple candidate reasoning paths; evaluate each (with another LLM call); continue or branch from the best.

For genuinely hard puzzle-like reasoning (24-game, crosswords), produces meaningful gains. For most production tasks, the cost (5-10× regular CoT) outweighs the benefit.

Self-consistency is the cheap version of this idea. ToT is the expensive version. Most teams should pick self-consistency.

## Extended reasoning modes (the 2024+ shift)

OpenAI's o1, Anthropic's Claude extended thinking, DeepSeek R1, and similar models shipped with a separate inference mode where the model spends thousands of tokens of internal reasoning before producing an answer.

Architecturally similar to running CoT internally and not showing the user. From the API caller's perspective:

- You ask a question.
- The model spends seconds to minutes thinking (charged at separate "reasoning tokens" rate).
- You receive the final answer.

These modes won decisively on hard reasoning benchmarks (math, code, complex logic) — typically 10-30 points over the standard mode of the same model.

Implications:

- **Stop manually prompting CoT for complex tasks if your model has reasoning mode.** It's better-tuned and the model knows when to stop.
- **Cost is real.** Reasoning mode is 5-20× the cost of regular mode per task.
- **Latency is real.** Tens of seconds; not for interactive UX without async patterns.

Use reasoning mode for: hard math/code, complex planning, decisions where quality dominates cost.
Use regular mode for: routine tasks, structured outputs, anything latency-sensitive.

## When CoT hurts

Surprising but observed: CoT can decrease accuracy on some tasks.

- **Direct factual recall.** "What's the capital of Burkina Faso?" CoT just gives the model time to talk itself out of the right answer.
- **Tasks the model is already good at.** If accuracy is already 99%, CoT might drop it to 95% by introducing reasoning errors.
- **Highly structured tasks.** Schema extraction, entity recognition. The model's reasoning narrative gets in the way of the structured output.

Heuristic: try with and without CoT on your actual task. Don't assume CoT helps; measure.

## Self-critique and reflection

Variant: ask the model to critique its own reasoning, then revise.

```
Q: [problem]
[initial CoT answer]

Now critically review your answer. Are there errors in the reasoning?
[critique]

Provide a revised answer based on the critique.
[revised answer]
```

Sometimes helps; cost-quality trade-off varies. Better with capable judges or external verification (e.g., running tests on generated code).

For agent-shaped systems, see [AgentReasoning] for the broader reflection pattern.

## CoT in agent loops

For tools-using agents (ReAct), CoT-between-actions is the model deciding which tool to call:

```
Thought: I need to find the customer's recent orders. Let me query the orders table.
Action: orders_lookup(customer_id=42)
Observation: 3 orders in last month.
Thought: Now I need the latest one's status.
...
```

The `Thought:` channel is CoT. It's expensive (every step has reasoning tokens) but useful for debugging — you can see what the agent thought.

For mature production agents, you can often suppress the `Thought:` channel after development; the model still reasons internally, just doesn't surface it. Saves cost.

## Anti-patterns

- **CoT for retrieval-only tasks.** "What does the doc say about X?" → don't reason; retrieve, quote.
- **Stacked techniques without measurement.** CoT + reflection + ToT + reasoning mode. Costs add up; gains don't always.
- **Trusting the reasoning trace.** The model's stated reasoning isn't always how it actually arrived at the answer. Models can produce plausible-but-wrong reasoning that happens to lead to the right answer (and vice versa). Don't audit the model by reading its CoT.
- **Asking for too much detail.** "Explain in detail" produces verbose unhelpful reasoning. "Reason step by step concisely" works better.

## Concrete recommendations

For most production usage in 2026:

1. **Default to no explicit CoT** with modern instruction-tuned models. They reason implicitly when needed.
2. **For reasoning-heavy tasks** where the model has a reasoning mode, use it. Don't prompt-hack CoT.
3. **For models without reasoning mode**, use few-shot CoT with task-specific examples for tasks above your model's "easy" threshold.
4. **For agentic systems**, keep `Thought:` during development for debuggability; suppress for cost in production.
5. **Measure.** Run with and without CoT on your eval set; pick what wins on quality-cost-latency.

## Further reading

- [AgentReasoning] — reasoning techniques in agentic systems
- [AgenticWorkflowDesign] — reasoning in the agent loop
- [PracticalPromptEngineering] — broader prompting context
