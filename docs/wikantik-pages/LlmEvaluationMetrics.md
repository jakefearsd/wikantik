---
canonical_id: 01KQ12YDVRAG1XX0MVJ9RRWT89
title: Llm Evaluation Metrics
type: article
cluster: agentic-ai
status: active
date: '2026-04-24'
tags:
- llm-evaluation
- metrics
- benchmarks
- llm-as-judge
- rouge
- bleu
summary: The metrics that actually tell you whether an LLM system works — what
  ROUGE/BLEU/MMLU/SWE-bench measure, where they lie, and the evaluation habits
  that beat benchmark chasing.
related:
- AgentTesting
- AiEvaluationAndBenchmarks
- AiHallucinationMitigation
- RetrievalExperimentHarness
hubs:
- AgenticAi Hub
---
# LLM Evaluation Metrics

Evaluating LLMs is embarrassing. There is no single "score" for a model, and nearly every published metric is gameable, misleading, or measuring something other than what you care about. This page is an inventory of what each common metric actually measures, and the habits that produce useful evals despite the landscape.

## The three kinds of evaluation you'll run

| Scope | Purpose | Cost | Frequency |
|---|---|---|---|
| **Per-call unit metrics** | Did this specific output meet a spec? | Free (hash-compare or schema check) | Every call in prod |
| **Task-level rollouts** | On a fixed task set, does the system still work? | $1–100 per run | Every prompt/model change |
| **Public benchmarks** | How does this model compare to others in general? | $100–thousands | Before adopting a new model |

Most of the time you care about the middle row. The first catches specific regressions; the third informs model choice. Public benchmarks are the most talked-about and the least load-bearing.

## String-similarity metrics (ROUGE, BLEU, METEOR)

**What they measure:** n-gram overlap between model output and a reference.

**What they actually tell you:** a little. ROUGE-L correlates weakly with human judgement on summarisation. BLEU correlates better on translation. Both collapse as models get stronger and outputs get more varied.

**When to use:** regression detection on tasks with one obviously-correct answer and not many valid paraphrases. A sudden ROUGE drop usually means something broke.

**When to ignore:** any open-ended task. A model can paraphrase correctly and score zero on BLEU.

## Semantic similarity (BERTScore, embedding cosine)

**What they measure:** semantic similarity between model output and reference, ignoring exact wording.

**What they actually tell you:** more than n-gram metrics, but still not enough. Two outputs can have high BERTScore while one is correct and one is plausibly wrong.

**When to use:** cheap automated screening between attempts. As a signal in a larger eval, not the eval itself.

## Task-specific exact metrics

**Pass@k** — on code generation, the fraction of N generated samples that pass unit tests. pass@1 is the honest metric; pass@10 inflates scores.

**Exact match / F1** — on QA and extraction tasks, did the model output the expected tokens. The canonical SQuAD metric; works when the answer space is narrow.

**Accuracy on closed-set choices** — for multiple-choice benchmarks (MMLU, HellaSwag) and classification tasks. Clean, comparable.

**Schema validity rate** — for structured output tasks. Did the JSON parse? Did it match the schema? Extremely useful in production, often under-reported.

These are the metrics that actually tell you something because the answer has a definition.

## LLM-as-judge

**What it measures:** whatever you ask the judge model to grade.

**What it actually tells you:** roughly what a human would say, if the judge is calibrated. If not, it tells you what a smart-but-biased auto-grader thinks.

**Calibration protocol:**

1. Sample 100 model outputs.
2. Have 2+ humans grade independently.
3. Have the judge grade the same 100.
4. Compute agreement between judge and humans.
5. If < 80% agreement, the rubric is unclear or the judge is wrong. Fix the rubric.
6. Re-calibrate whenever the judge model version changes.

**Known biases:**

- Judges prefer longer outputs (length bias).
- Judges prefer the first option in pairwise comparison (position bias — mitigate by randomising order).
- Judges trust confident-sounding outputs more than uncertain-sounding ones, regardless of correctness.

Never trust an LLM judge you haven't calibrated. Never report LLM-judge scores as absolute.

## RAG-specific metrics (RAGAS-style)

**Faithfulness** — are claims in the answer grounded in the retrieved context? Implemented as: LLM-judge asks "does source X support claim Y," over each claim × source.

**Answer relevancy** — does the answer address the question? Usually a judge call.

**Context precision / recall** — are the retrieved chunks actually relevant? Separate from whether the answer is good.

**Why they matter:** RAG systems have two quality axes (retrieval and generation). These metrics let you diagnose which is the bottleneck when the final answer is wrong.

## Benchmarks that actually mean something

Most public benchmarks are saturated or contaminated. The ones that still carry signal as of 2026:

- **MMLU** — still useful for baseline knowledge; widely contaminated but models above 85% are genuinely strong.
- **MMLU-Pro** — harder, newer, less contaminated.
- **GPQA Diamond** — expert-level questions, hard to memorise.
- **HumanEval / MBPP** — basic coding; saturated but still useful as a floor check.
- **SWE-bench Verified** — real-world coding tasks from GitHub issues; hard to game.
- **τ-bench / AgentBench** — tool-use and multi-turn agent ability.
- **LiveCodeBench** — code problems released after training cutoffs; resists contamination.
- **LMArena (Chatbot Arena)** — human pairwise preference; prone to style bias but captures subjective quality.

Ignore benchmarks where top models have been in the 95%+ range for more than a year. They've stopped discriminating.

## Your benchmark is your eval set

Public benchmarks tell you how the model performs on average. Your users are not average.

The single highest-leverage eval practice: maintain a held-out set of 100–500 real task inputs from your application, labelled with expected outputs. Run this set on every candidate prompt, model, or pipeline change.

This is usually the only eval that matters for your product. Public numbers are for external communication. Internal decisions should come from your eval set.

## Correlation with human preference

For open-ended generation, the gold standard is human ratings. Proxies, ordered by correlation:

1. Pairwise human preference (strongest but expensive).
2. Single-rating human scores (weaker; raters anchor differently).
3. LLM-as-judge with calibration (moderate; see above).
4. LLM-as-judge without calibration (noise).
5. Automatic metrics (BLEU, ROUGE) on subjective tasks (noise).

Budget at least one human-labelling round per major change, even if your automated evals are beautiful. Humans catch what the automation misses.

## Failure modes in eval pipelines

- **Training-set contamination.** Your eval set ended up in the pretraining data of the next model. Check with canary strings; rotate sets periodically.
- **Selection bias.** "I'll eval on tasks where my model does well." Unfaithful. Include your product's failure cases in the eval set.
- **Moving targets.** Eval set changes between runs. Freeze the eval; version it; use the same version for historical comparisons.
- **Metric creep.** Adding metrics until the model looks good on one of them. Pick metrics before running evals; don't p-hack.

## What to surface per eval run

```
Eval set version: v12 (200 tasks, frozen 2026-03-15)
Model: claude-sonnet-4-6
Prompt: v7

Overall:
  Task success rate: 84.5% (+2.1 vs v6)
  Cost per task:    $0.031 (-15% vs v6)
  p95 latency:      4.8s

Per task-type:
  customer_query:   91% success (n=120)
  billing_action:   75% success (n=50)
  technical_query:  82% success (n=30)

Regressions vs v6:
  billing_action tasks #B017, #B024 now fail (were passing)

Cost breakdown:
  Input tokens avg:  3421 (cache hit 92%)
  Output tokens avg: 287
```

This is what "good eval output" looks like: hard numbers, per-subset breakdown, explicit regressions list, no vibes.

## Further reading

- [AgentTesting] — the rollout eval workflow in depth
- [AiEvaluationAndBenchmarks] — public benchmarks in more detail
- [AiHallucinationMitigation] — factuality-specific evaluation
- [RetrievalExperimentHarness] — this wiki's own retrieval eval pipeline
