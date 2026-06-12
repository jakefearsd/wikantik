---
date: '2026-05-24'
summary: Quantitative and qualitative metrics for LLM systems, focusing on the limitations
  of string similarity (ROUGE/BLEU) vs. the reliability of task-specific exact match
  and LLM-as-judge.
cluster: agentic-ai
auto-generated: false
canonical_id: 01KQ12YDVRAG1XX0MVJ9RRWT89
type: article
title: LLM Evaluation Metrics
tags:
- llm-evaluation
- metrics
- benchmarks
- llm-as-judge
status: active
hubs:
- AgentLoops Hub
---
# LLM Evaluation Metrics

Standard software testing relies on deterministic assertions. LLM testing relies on statistical distributions. Choosing the wrong metric leads to "benchmark chasing" where a model improves on paper but regresses in the hands of users.

## The Hierarchy of Metrics

| Metric Type | Example | Use Case | Limitations |
|---|---|---|---|
| **Deterministic** | Exact Match, JSON Schema Validity | Code Gen, Extraction, Structured I/O | Too rigid for creative tasks. |
| **N-Gram Overlap** | ROUGE-L, BLEU | Summarization, Translation | Penalizes synonyms; blind to factual correctness. |
| **Model-Based** | BERTScore, G-Eval | Semantic alignment, nuance | High cost; potential "Judge Bias". |
| **Human-in-the-Loop** | Likert Scale, Pairwise Pref | Final product validation | Extremely slow and expensive. |

## Code-Specific Metrics

For engineering tasks, n-gram overlap is useless. We use **Pass@k**.
A model generates $n$ samples for a coding problem. If $c$ samples pass the unit tests, the probability that at least one of $k$ samples passes is:

$$
\text{Pass@k} = 1 - \frac{\binom{n-c}{k}}{\binom{n}{k}}
$$

*Practitioner Note: In production, always report Pass@1. Pass@10 and Pass@100 are often used to inflate results in academic papers.*

## RAG-Specific Evaluation (RAGAS)

Evaluating a Retrieval-Augmented Generation system requires breaking the problem into two parts: retrieval quality and generation quality.

1. **Faithfulness:** Does the answer derive *only* from the retrieved context? (Prevents hallucination).
2. **Answer Relevance:** Does the answer actually address the user's prompt?
3. **Context Precision:** Is the retrieved context actually useful for answering the question?

## The LLM-as-Judge Pattern

Using a model like GPT-4o to grade a smaller model (e.g., Llama 3) is now the industry standard for subjective tasks. 

```python
# Reference rubric for a Judge LLM
JUDGE_PROMPT = """
Evaluate the assistant's response based on Accuracy and Conciseness.
Score 1-5. 
A score of 5 means the answer contains zero hallucinations and no fluff.
Context: {retrieved_context}
Question: {user_query}
Response: {assistant_response}
"""
```

**CRITICAL: Judge Calibration.** You must manually grade 100 samples alongside the Judge LLM. If your agreement rate is below 80%, your rubric is too vague.

## Public Benchmarks to Watch (2026)

- **MMLU-Pro:** A harder, cleaner version of the classic MMLU (Massive Multitask Language Understanding).
- **GPQA:** Graduate-level science questions that are Google-proof.
- **HumanEval:** The baseline for Python code generation.
- **SWE-bench:** Real GitHub issues that require the model to edit multiple files.

## Further Reading
- [AgentTesting](AgentTesting) — Moving from single-call metrics to multi-turn trajectory eval.
- [AiEvaluationAndBenchmarks](AiEvaluationAndBenchmarks) — How to interpret Leaderboard scores.
- [RetrievalExperimentHarness](RetrievalExperimentHarness) — Building a local test suite for RAG.
