---
canonical_id: 01KQ12YDRTD1YCKE6VC6B9V7YZ
title: Ai Hallucination Mitigation
type: article
cluster: agentic-ai
status: active
date: '2026-04-24'
tags:
- hallucination
- factuality
- rag
- grounding
- llm-safety
summary: Practical techniques for reducing LLM fabrication, ranked by effort-to-payoff.
  Grounding, constrained generation, citation enforcement, and the traps in common
  evaluation approaches.
related:
- RagImplementationPatterns
- HybridRetrieval
- LlmEvaluationMetrics
- AiSafetyAndAlignment
hubs:
- AgenticAi Hub
---
# AI Hallucination Mitigation

An LLM "hallucinates" when it generates plausible-sounding content that is false. This happens for structural reasons — the model is optimised for likelihood, not truth — and no prompt or technique eliminates it completely. What you can do is drive the rate from uncomfortable (often 5–15% on factual tasks) to tolerable (< 1%), while exposing residual errors to your users and systems.

Ranked roughly by effort-to-payoff.

## Retrieval grounding: the largest single lever

For any task where the answer exists in some corpus — documentation, customer records, policies — retrieval-augmented generation cuts hallucination rate by 50–90%. The model cites from the retrieved context instead of confabulating.

Three requirements for RAG to actually reduce hallucination (not just add noise):

1. **The prompt instructs citation from sources only.** "Answer using only the provided sources. If the sources don't contain the answer, reply 'I don't know.'" Without this, the model reads the sources as hints and freely adds extra "knowledge."
2. **Retrieval recall is high enough.** If the right chunk isn't in context, the model answers from priors — i.e. hallucinates. Aim for recall@10 > 90% on your eval; if you're below, fix retrieval before blaming generation.
3. **Source labels are explicit and parseable.** `[Source: doc-423]` per chunk. The model cites using these labels, and you can verify post-hoc that cited sources actually support the claim.

This is [RagImplementationPatterns] territory in depth.

## Constrained generation for structured outputs

If the output is a JSON schema, a specific DSL, or a closed set of choices, constrain generation to the valid grammar. Tools: `outlines`, `lm-format-enforcer`, OpenAI's `response_format`, Anthropic's tool use, grammar-constrained generation in llama.cpp.

Benefits:

- Invalid schemas become impossible instead of rare.
- Model can't hallucinate field names (only sample from permitted slots).
- Closed-set answers ("yes" / "no" / "insufficient information") eliminate free-form escape routes.

Do this wherever the output is machine-consumed. You save retry logic, you eliminate a whole class of parsing errors, and you force the model to commit to one of the valid answers rather than invent a new one.

## Calibrated abstention

Teach the model to say "I don't know" as a first-class output. Three moves:

- **Prompt:** "If you are not confident, reply 'I don't know' rather than guessing."
- **Schema:** for structured output, include `"insufficient_information"` as a valid enum value.
- **Eval:** include unanswerable questions in your test set; reward correct abstention as highly as correct answers.

Without the eval, the model learns to always answer because that's what the training distribution rewards. Adding unanswerable cases trains the calibration that matters.

Measure calibration: if the model says "I don't know" 5% of the time, of the remaining 95% confident answers, how often is it right? You want both numbers to shift — more abstention, higher confidence-conditional accuracy.

## Self-verification passes

Have the model check its own output against the sources:

```
Draft answer: {answer}
Sources: {sources}

For each claim in the draft, identify which source supports it. 
If any claim is not supported, flag it.
```

Then either:

- Return the claims the model flagged as unsupported to the user as caveats.
- Regenerate with a stronger citation prompt.

Caveat: self-verification has its own hallucination rate. Works better when the verification uses a separate model call (so it's not just continuing its own generation) and when sources are short enough to fully include.

## Citation enforcement

Post-hoc verification of the citations the model produced:

1. Model generates answer with inline citations: "The refund policy allows 30 days [source: doc-12]."
2. Your code extracts `[source: doc-12]`, fetches that source, and checks that the claim is supported.
3. Unsupported claims → flag, re-prompt, or remove.

This is where you catch fabricated citations — the classic "the model cited a paper that doesn't exist" case. For a production system answering user queries, citation verification is a must-have, not a nice-to-have.

## Uncertainty signals

Ask the model for confidence and use it:

- **Log-probability extraction.** For structured outputs, token-level logprobs approximate confidence. Thresholds of 0.8–0.95 on the answer token filter low-confidence outputs.
- **Explicit confidence rating.** "Rate your confidence 1–10." Noisy but usable; calibrate against a held-out set to find a useful threshold.
- **Consistency across multiple samples.** Generate N=5 answers at temperature > 0. Agreement = high confidence. Disagreement = trigger abstention or fallback.

Use uncertainty to route: high confidence → deliver; low confidence → escalate to human, ask a clarifying question, or abstain.

## Domain-specific fine-tuning — with caveats

Fine-tuning on domain data can reduce hallucination by teaching the model the domain's vocabulary, common structures, and admission patterns. But it also teaches the model new "facts" that become memorised-and-potentially-wrong, especially as the real-world facts drift.

Guidance:

- Fine-tune for *style* and *structure* (how your organisation writes answers), not for *facts*.
- Keep facts in retrieval, not in the model weights.
- A fine-tuned model + RAG typically beats either alone on factuality.

See [LLMFineTuning] for the recipe.

## What doesn't work as well as claimed

- **"Say only true things" prompts.** The model doesn't know what's true; the prompt is aspirational. Some impact on the margin; nowhere near sufficient on its own.
- **Temperature = 0.** Reduces variance, doesn't reduce hallucination. A confidently wrong answer at temp 0 is still wrong.
- **Chain-of-thought alone.** CoT improves reasoning tasks. On factual recall, it can actually hurt — the model rationalises wrong initial answers.
- **Model chains that check each other without grounding.** Two LLMs agreeing they're right is not evidence they're right. Agreement-based filters without external ground truth are circular.

## Measurement

You need a task-specific factuality eval. Generic benchmarks (TruthfulQA, HaluEval) measure the average; your traffic isn't the average.

Build 100–500 questions where you know the right answer (from docs, database, or labelled corpus). For each model output:

- **Faithfulness** — does the answer contradict the sources? (Unfaithful = bad even if technically true.)
- **Citation correctness** — does each cited source actually support the claim?
- **Abstention correctness** — on unanswerable questions, did the model correctly abstain?

RAGAS, TruLens, or a homegrown LLM-judge over these three axes works. Run weekly at minimum. See [LlmEvaluationMetrics] for the metric catalogue.

## Residual hallucination is a product problem

Even with everything above, residual hallucination rate won't hit zero. At some point the engineering reaches diminishing returns and the question becomes: how does the product handle wrong outputs?

- **Disclose sources prominently.** Users treat cited answers more sceptically than uncited confident prose, which is correct.
- **Show uncertainty, don't hide it.** "I'm not sure, but ..." is more truthful than a confident wrong answer.
- **Make correction cheap.** A thumbs-down that actually improves the system (via your fine-tune or RAG pipeline) turns hallucination into training data.
- **High-stakes answers go through a human.** For legal, medical, financial actions, model confidence is a suggestion, not a licence to execute.

## Further reading

- [RagImplementationPatterns] — grounding in depth
- [HybridRetrieval] — retrieval quality determines upper bound
- [LlmEvaluationMetrics] — metrics for factuality
- [AiSafetyAndAlignment] — the broader safety context
