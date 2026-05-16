---
date: '2026-05-24'
summary: Methods for deterministic and stochastic testing of agentic loops, including
  trajectory-based evaluation, shadow production, and LLM-as-judge calibration.
cluster: agentic-ai
auto-generated: false
canonical_id: 01KQ12YDRGCZBA685NG52C8AZ0
type: article
title: Agent Testing
tags:
- agent
- testing
- evaluation
- llm-as-judge
status: active
hubs:
- AgentLoops Hub
---
# Agent Testing

You cannot unit-test an agent as a black box. Traditional unit tests confirm that `input A` produces `output B`, but agents are stochastic. Testing an agent requires evaluating the **trajectory**—the sequence of tool calls and reasoning steps—not just the final answer.

## The Agent Testing Pyramid

| Tier | Strategy | Frequency |
|---|---|---|
| **L1: Unit Tests** | Test individual tool functions and prompt-template parsers. | Every Commit |
| **L2: Deterministic Traces** | Replay a recorded sequence of LLM responses against the orchestration code. | Every Commit |
| **L3: Rollout Evals** | Run the full agent on a fixed set of 50-100 tasks with mocked tools. | Every Prompt Change |
| **L4: Shadow Production** | Run a candidate agent alongside production, comparing outputs but not serving them. | Weekly / Monthly |

## Trajectory-Based Evaluation

A "pass" in agent testing is defined by three factors:
1. **Success:** Was the goal achieved?
2. **Efficiency:** Did it use the minimum number of tool calls?
3. **Safety:** Did it avoid forbidden tool sequences?

### Reference Test Case (JSON Schema)
```json
{
  "test_id": "cancel_sub_001",
  "prompt": "Cancel user 42's subscription and refund the last 30 days.",
  "expected_outcome": {
    "status": "cancelled",
    "refund_amount": 29.99
  },
  "mandatory_tools": ["lookup_user", "cancel_subscription", "issue_refund"],
  "forbidden_tools": ["delete_user"],
  "max_steps": 5
}
```

## LLM-as-Judge Calibration

Using a stronger model (e.g., GPT-4o or Claude 3.5 Sonnet) to grade a smaller agent's performance is efficient but requires calibration.

**The Calibration Loop:**
1. Select 100 agent trajectories.
2. Have a human expert grade them on a 1-5 scale.
3. Have the Judge LLM grade the same 100 using the same rubric.
4. Compute the **Cohen's Kappa** coefficient. If it is below 0.7, your rubric is too vague or your Judge prompt needs more constraints.

## Shadow Production

Shadowing is the most reliable way to catch "vibe-based" regressions. Run the new agent logic on a stream of production requests. Store the results in a database and run a diff.

```python
# Conceptual Shadow Dispatcher
async def shadow_dispatch(request):
    prod_task = run_agent(v1_prompt, request)
    shadow_task = run_agent(v2_candidate_prompt, request)
    
    prod_res, shadow_res = await asyncio.gather(prod_task, shadow_task)
    
    if prod_res.trajectory != shadow_res.trajectory:
        log_regression(request, prod_res, shadow_res)
    
    return prod_res.final_answer
```

## Avoiding the "Cost Spike" Regression
Always track **Tokens Per Success (TPS)**. A new prompt that improves success rate by 2% but increases average tool calls from 3 to 12 is a failed experiment in a production environment.

## Further Reading
- [AgenticWorkflowDesign](AgenticWorkflowDesign) — Architecture patterns for reliable agents.
- [LlmEvaluationMetrics](LlmEvaluationMetrics) — Detailed breakdown of ROUGE, BLEU, and BERTScore.
- [AgentObservability](AgentObservability) — How to capture the traces needed for L2 testing.
