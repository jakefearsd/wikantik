---
canonical_id: 01KQQ73TXJJRDPT07BS6ZPXJTT
date: 2026-05-03T00:00:00Z
cluster: agentic-ai
type: article
tags:
- agentic-ai
- test-time-compute
- system-2
- lats
- mcts
- openai-o1
- inference-scaling
- reasoning
title: Test-Time Compute Scaling
relations:
- type: part-of
  target_id: 01KQEKGD6VT29FGWF8YE9TM671
- type: supersedes
  target_id: 01KQ0P44PEJG4KBKH84YFQP91B
summary: A deep exploration of the paradigm shift from pre-training scaling laws to
  inference scaling laws. Details how System 2 agents use Language Agent Tree Search
  (LATS), MCTS, and Process Reward Models (PRMs) to dynamically expand compute on
  difficult problems.
status: active
---

# Test-Time Compute Scaling: Building System 2 Agents

For years, the AI industry relied on the **Pre-Training Scaling Laws**: throw more data and compute at the model during training, and it gets smarter. By late 2024, with models like OpenAI o1 and DeepSeek R1, a new axis emerged: **Inference Scaling Laws** (or Test-Time Compute).

Instead of relying on the model's "System 1" intuition (its immediate next-token prediction), we can engineer "System 2" thinking by allowing the agent to spend exponentially more compute during *inference* to search, backtrack, and verify its own logic.

This article details how to architect systems that dynamically scale compute on hard problems.

## 1. The Death of the "One-Shot" Prompt

Standard Chain-of-Thought (CoT) asks the model to "think step by step." This is an unguided, forward-only generation. If the model makes a math error at step 2, step 10 is doomed, but the model has no mechanism to halt, reflect, and backtrack.

Test-Time Compute architectures wrap the LLM in a classical search algorithm. The LLM acts as the heuristic generator, while a symbolic engine maintains the search tree.

## 2. Language Agent Tree Search (LATS)

LATS combines the exploratory power of **Monte Carlo Tree Search (MCTS)** with the generative power of LLMs. It is the architectural backbone of self-correcting agents.

### The LATS Algorithm
1.  **Selection**: Starting from the root (the initial prompt), traverse the current tree of thoughts to find the most promising leaf node using the UCT (Upper Confidence Bound applied to Trees) formula. UCT balances exploiting known good paths with exploring unvisited paths.
2.  **Expansion**: Prompt the LLM to generate $k$ possible *next steps* (actions or thoughts) from the selected node.
3.  **Evaluation (Simulation)**: For each new step, use an LLM or an external tool (like a Python interpreter or a compiler) to evaluate the state. This requires a **Reward Model** (see Section 3).
4.  **Backpropagation**: Propagate the reward score back up the tree to the root, updating the value of all ancestor nodes.

**Why it wins**: Unlike Tree-of-Thought (ToT) which searches blindly, LATS uses environmental feedback (e.g., "The compiler threw a syntax error") to assign negative rewards, dynamically pruning dead ends and focusing compute on viable solution paths.

## 3. Process Reward Models (PRMs)

To make tree search work, you need a way to score intermediate steps.
- **Outcome Reward Models (ORMs)** score the final answer (e.g., 1 for correct, 0 for incorrect). This is useless for searching long pathways because the signal is too sparse.
- **Process Reward Models (PRMs)** are trained to evaluate the logical soundness of *every single step* in a chain of reasoning.

In a custom System 2 agent, you deploy a smaller, specialized LLM (the PRM) whose sole job is to look at the main Agent's proposed next step and output a confidence score $[-1, 1]$. This score drives the MCTS backpropagation.

## 4. Compute Scaling: The Verifier vs. Generator Asymmetry

The core insight of Test-Time Compute is that **verification is computationally cheaper than generation**.

It is mathematically easier to verify a correct mathematical proof than to invent one from scratch. By generating thousands of candidate trajectories (generation) and having a PRM score them (verification), we can effectively buy higher intelligence with brute-force inference compute.

### Implementing Dynamic Budgets
A production System 2 agent doesn't use MCTS for every query. It employs a dynamic compute budget:
1.  **Router**: A fast model evaluates the query's complexity.
2.  **Trivial Task**: Route to standard zero-shot inference (Latency: 1s).
3.  **Complex Task**: Route to the LATS engine, allocating a budget of $N$ tree expansions (Latency: 30s - 5m).

## 5. Conclusion

Test-Time Compute moves AI engineering away from "prompt tweaking" and towards classical search optimization. By treating the LLM not as an oracle, but as a node-expansion heuristic in a vast search tree, we can solve problems that are structurally impossible for standard autoregressive models.

## See Also
- [[FlowEngineering]] — Deterministic, state-machine orchestration (the opposite approach to tree search).
- [[AgentReasoning]] — Historical context on CoT and ReAct.
- [[AiAgentArchitectures]] — Core architectural patterns.
