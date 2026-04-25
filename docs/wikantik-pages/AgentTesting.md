---
canonical_id: 01KQ12YDRGCZBA685NG52C8AZ0
title: Agent Testing
type: article
cluster: agentic-ai
status: active
date: '2026-04-24'
tags:
- agent
- testing
- evaluation
- regression-testing
- rollout-testing
summary: How to test an LLM agent without losing your mind — fixed task sets,
  trajectory comparison, LLM-as-judge, and the anti-patterns that give teams
  false confidence.
related:
- AgenticWorkflowDesign
- AgentLoops
- AgentObservability
- LlmEvaluationMetrics
- AiEvaluationAndBenchmarks
- RetrievalExperimentHarness
hubs:
- AgenticAi Hub
---
# Agent Testing

You cannot unit-test an agent. You can test individual tools, the orchestration logic, and the prompt templates, but the agent's behaviour as a whole is a stochastic function of the model, the tools, and the state. The only honest form of agent testing is *rollout testing*: run the agent on a fixed set of tasks, grade the outcomes, track the scores over time.

This page is the discipline around that rollout testing. For the loop being tested, see [AgentLoops]; for metric definitions in isolation, [LlmEvaluationMetrics].

## The test pyramid, adjusted

| Layer | What it tests | Cost per run | Run frequency |
|---|---|---|---|
| **Tool unit tests** | Each tool wrapper in isolation | Milliseconds | Every commit |
| **Deterministic logic tests** | Orchestrator, state machine, validators | Milliseconds | Every commit |
| **Replayed traces** | Parsed past LLM outputs against current orchestration code | Seconds | Every commit |
| **Rollout eval (small)** | Agent on 20–50 fixed tasks with temp=0, mocked or live tools | Minutes, $1–5 | Every prompt/model change |
| **Rollout eval (large)** | 200+ tasks, live tools, production-parity | 30–60 min, $20–100 | Before release |
| **Shadow production** | Agent runs alongside prod, diffs outputs | Free marginal | Continuous |

The two rollout eval tiers are the ones most teams skip and then regret.

## Fixed task sets are the load-bearing idea

Start here: write down 20 tasks your agent should be able to complete. For each, record the initial user request, expected tool calls (roughly), and an expected outcome (exact string, structured object, or "anything that does X").

Store as JSON:

```json
{
  "id": "task_042",
  "input": "Cancel my subscription and refund my last payment.",
  "fixture": {"user_id": 42, "account_state": "active_paid"},
  "expected": {
    "final_state": {"subscription": "cancelled", "refund_issued": true},
    "expected_tool_calls": ["cancel_subscription", "issue_refund"],
    "max_steps": 6
  },
  "tags": ["critical-path", "mutation"]
}
```

This file is your test suite. Commit it. Freeze it. Every change to the prompt, model, or orchestration replays this set and reports deltas.

## Grading: the honest and the useful

Three grading approaches, pick based on task type:

1. **Exact-match / structured-output** — the expected outcome is a specific record in a database or a JSON object. Free, deterministic, catches regressions hard. Use wherever you can.
2. **LLM-as-judge** — a stronger model grades the agent's output against the expected answer. Necessary for open-ended tasks. Calibrate it against 50–100 human labels before trusting it; inter-rater agreement between your humans and the judge model should be > 80%.
3. **Human eval** — your only option for the first version of a new task type. Budget 1–2 minutes per task per rater. Do not skip.

A task should have one grading mode. Mixed grading (partial credit on reasoning + exact match on output) is where bugs hide.

## Trajectory grading, not just outcome grading

Two agents that arrive at the same final state can have wildly different intermediate costs. Include trajectory metrics:

- **Steps to completion** (p50, p95). If a task used to take 5 steps and now takes 12, something regressed even if the answer is still right.
- **Tool call set match**. The agent should have called approximately the expected tools. Compare multisets, not sequences — order is often incidental.
- **Cost per task** (input tokens, output tokens, USD). Regression here is often the first signal of a prompt issue.
- **Unnecessary tool calls**. Any tool called more than once with identical arguments in the same trajectory is a waste; count these.

## The temperature trap

Setting `temperature=0` for reproducibility in tests sounds correct and is mostly wrong. Three reasons:

1. Even at temp 0, production will run at higher temp for some of your traffic. Your test-temp success rate overestimates real success rate.
2. Temp 0 hides fragility. An agent that only works at temp 0 will fail in production when occasional non-zero sampling happens for cost or UX reasons.
3. Temp 0 outputs still vary slightly across API calls (batch size, model version).

The right approach: run each task at the production temperature, multiple times (3–5 rollouts), report the success rate as a percentage. A task at 5/5 passes is solid; 3/5 is fragile; 1/5 is luck.

## Replaying past production traces

Your observability stack (see [AgentObservability]) already logs every production rollout. Those are free regression tests.

- Nightly: sample 100 recent production rollouts, re-run the orchestration code against the same LLM outputs, assert behavioural equivalence.
- On every PR: replay the same sample, flag any trajectory whose tool-call set changes.

This catches "we refactored the orchestration and broke tool dispatch" bugs without needing the model to be deterministic. The LLM's outputs are the fixture.

## Chaos / adversarial testing

Regular tests check happy paths. Build a second set for robustness:

- **Tool failures** — inject 500 errors, timeouts, rate limits. The agent should retry, escalate, or report gracefully. It should never crash the orchestrator.
- **Malformed inputs** — user prompt in a different language, prompt injection attempts ("ignore previous instructions"), oversized inputs.
- **Partial state** — start the agent with a partially-filled state (simulating resume-from-checkpoint). The agent should not duplicate work already done.
- **Rate-limit cascades** — 429 from the model during step 3. Does the agent honour `Retry-After`?

Each category deserves 5–20 task fixtures. Track adversarial success rate separately from happy-path success rate; they move differently.

## The LLM-as-judge calibration step

LLM judges are tempting because they're cheap, but they're biased. Before relying on judge scores for any decision:

1. Sample 100 agent rollouts.
2. Have 2 humans grade each independently. Resolve disagreements with a third.
3. Have the judge model grade the same 100.
4. Compute agreement rate between judge and humans.
5. If < 80%, either the judge prompt is bad or the task is harder to grade than it looks. Fix the prompt or define a narrower rubric.
6. Re-calibrate monthly; the judge model changes under you even without version bumps.

**Do not:** use LLM-as-judge without this calibration step. "Claude said it was 85% correct" is not a meaningful number if Claude and your humans disagree on what correct means.

## Shadow production

Once you have a stable rollout suite, shadow production is almost free:

- New prompt / model candidate runs in parallel with prod on sampled real traffic.
- You don't surface its output to users.
- You log outcomes and compare: trajectory deltas, tool-call deltas, cost deltas.
- Promote after a signed-off win.

The ROI is high because the traffic is real. Synthetic fixtures always drift from prod distribution; shadow production doesn't.

## What anti-patterns look like

- **Eval on training data** — your eval set was used to write examples in the prompt. Numbers lie. Hold eval out from the beginning.
- **Cherry-picked rollouts** — "I ran it and it worked." Single rollouts are meaningless; count aggregate success rates only.
- **Judge grades without calibration** — see above.
- **"The agent agrees it did well"** — asking the agent to grade itself. Reliably optimistic, reliably useless.
- **No cost tracking** — agent quality quietly tanked but the bill tripled. Without cost in the eval you miss this.

## Further reading

- [AgenticWorkflowDesign] — the system under test
- [AgentLoops] — failure modes the tests should catch
- [AgentObservability] — the telemetry feeding trace replay
- [LlmEvaluationMetrics] — the metric catalogue
- [AiEvaluationAndBenchmarks] — public benchmarks and their limits
- [RetrievalExperimentHarness] — this wiki's own rollout harness for RAG
