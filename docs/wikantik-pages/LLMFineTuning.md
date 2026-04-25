---
canonical_id: 01KQ12YDVGV7J9XMMSMXGZYZNE
title: LLM Fine Tuning
type: article
cluster: agentic-ai
status: active
date: '2026-04-24'
tags:
- llm
- fine-tuning
- lora
- qlora
- post-training
- model-training
summary: When fine-tuning an LLM beats prompting, plus the working engineer's guide
  to LoRA/QLoRA/full-finetune trade-offs, data requirements, and evaluation pitfalls.
related:
- LLMFineTuning
- FineTuningLargeLanguageModels
- ModelQuantization
- LlmEvaluationMetrics
- OpenSourceLLMs
- PracticalPromptEngineering
- RagImplementationPatterns
hubs:
- AgenticAi Hub
---
# LLM Fine-Tuning

Fine-tuning is the right answer surprisingly rarely. Before you reach for it, work through this order: prompt-engineer → few-shot → RAG → fine-tune. Each step is roughly 10× cheaper than the next and solves more problems than engineers tend to admit.

When you *do* need fine-tuning, the choice is almost never between "fine-tune or don't" but between three concrete techniques that have different cost curves, different failure modes, and different kinds of behaviour they're good at changing.

## When fine-tuning beats prompting

Fine-tune when at least one is true:

- **Format adherence matters more than content freshness.** You need the model to emit a specific JSON schema, function-call shape, or DSL every single time. Prompting gets you 98%; fine-tuning gets you to the 99.9% that lets you drop the retry loop.
- **Latency or cost of the base model is prohibitive.** A 7B fine-tuned on your task often beats a 70B prompted. This is where most real production wins come from.
- **The task requires a style or domain idiom no amount of in-context examples conveys.** Legal drafting voice, a specific medical reporting template, brand-voice copy.
- **You need on-prem inference for compliance** and the base model you can self-host isn't quite good enough zero-shot.

Do **not** fine-tune to:

- Teach the model new facts. That's RAG's job. Fine-tunes forget, confuse, and hallucinate facts they were trained on five epochs earlier. Facts belong in a retrieval index.
- Improve reasoning on a task where the base model fails. Your 10k-row dataset will not teach GPT-4-class reasoning into a 7B. Upgrade the base, or decompose the task.

## The three techniques, ranked by when to use them

| Technique | VRAM for 7B | Training time | Inference overhead | Use when |
|---|---|---|---|---|
| **Full fine-tune** | ~80 GB | Hours–days | None (it's just a new model) | You own the compute, you have >100k high-quality examples, and you genuinely need to shift the model's behaviour broadly |
| **LoRA** (rank 8–32) | ~24 GB | Minutes–hours | Tiny (merged at load time) | The default. Works for 90% of fine-tune use cases. Keep several LoRA adapters per base model |
| **QLoRA** (4-bit base + LoRA) | ~12 GB | Slightly slower than LoRA | Small (de-quant at inference) | You're on a single consumer GPU (e.g. 24 GB 4090) or running hundreds of cheap experiments |

**Strong opinion:** start with QLoRA. The loss of quality versus LoRA is small and frequently unmeasurable; the cost collapse lets you actually iterate. Graduate to full-rank LoRA only when you have an eval that shows QLoRA falling short.

Skip exotic schemes (prefix-tuning, IA³, DoRA, etc.) unless you've read the ablation and it matches your exact setup. LoRA/QLoRA are overwhelmingly the methods that show up in production.

## Data is the thing

The ratio of "time spent on data" to "time spent on hyperparameters" in a successful fine-tune is roughly 10:1. Most teams get this inverted and then wonder why their runs don't converge.

Minimum viable dataset shape for instruction tuning:

- **500–5000 examples** for a narrow task (format adherence, specific QA style). More doesn't help much past the plateau.
- **Diversity over volume.** Every prompt pattern you want the model to handle needs at least 20–50 examples. A dataset that's 80% one template will overfit to that template.
- **Held-out eval set of 100–500 examples**, never seen during training, labelled by the same process. If you don't have this, you don't have a fine-tune, you have a vibe.
- **Negative examples matter.** Include counter-examples where the correct output is "I don't know" or a refusal. Without them the model learns to always answer, and the failure mode will surface in prod when the input is slightly out of distribution.

Label quality trumps volume. 500 examples labelled by a domain expert will beat 50,000 labelled by crowd workers every time. Pay for the experts.

## Concrete recipe: QLoRA on Llama 3.1 8B

This is the setup most small teams should run first:

```
base_model         = meta-llama/Llama-3.1-8B-Instruct
quantization       = 4-bit NF4 (bitsandbytes)
lora_rank          = 16
lora_alpha         = 32
lora_dropout       = 0.05
target_modules     = ["q_proj","k_proj","v_proj","o_proj","gate_proj","up_proj","down_proj"]
optimizer          = paged_adamw_8bit
learning_rate      = 2e-4
warmup_ratio       = 0.03
epochs             = 2-3 (stop on eval loss plateau)
batch_size         = 1 with gradient_accumulation_steps=16
```

Hugging Face `trl` with `SFTTrainer` and the `peft` library implement this almost verbatim. Training a 3k-example dataset on a single 24 GB GPU runs in 30–60 minutes.

## The evaluation trap

Loss curves are lying to you. Training loss going down while the model gets worse on your task is common — it means you're memorising the training set instead of learning the pattern.

Track all four:

1. **Training loss** (diagnostic only — should decrease smoothly; spikes mean LR too high).
2. **Eval loss** on held-out data (should decrease then plateau; divergence from training loss means overfit).
3. **Task-specific metrics**: exact-match for structured output, ROUGE/BLEU for summarisation, pass@1 for code. These are what you actually care about.
4. **Base-model regression check.** Run the fine-tuned model on a general benchmark (MMLU, HellaSwag, or a 100-sample internal set of general queries). If it dropped by more than 5%, you over-fine-tuned and the model forgot how to be useful outside your task.

See [LlmEvaluationMetrics] for the detailed metric catalogue and [AgentTesting] for the rollout-based equivalents if your fine-tuned model is part of an agent.

## Catastrophic forgetting, concretely

Every fine-tune trades off between "better at my task" and "worse at everything else." The question is only how much.

A typical 2-epoch QLoRA on 3k domain examples will:

- Improve the target task by 10–30 points on task-specific metrics.
- Drop MMLU by 1–3 points.
- Drop instruction-following on *other* formats by 5–15 points (this is the one people miss).

Mitigations, in order of bang-per-buck:

1. **Mix in general instruction data** (e.g. 10% of a general SFT dataset alongside your domain data). This alone cuts regression by half or more.
2. **Stop earlier.** Task metrics usually plateau after 1-2 epochs; continuing just erodes general ability.
3. **Lower LoRA rank.** Rank 8 regresses less than rank 32 at a small task-quality cost.
4. **Preserve base model behaviour with a reference-policy term** (DPO/KTO-style) if you care about alignment properties.

## Fine-tune vs RAG vs both

The common-sense decision tree:

- Dynamic facts that change hourly → RAG.
- Static format/style that must be exact → fine-tune.
- Both needed → fine-tune a small model to emit your format reliably, then do RAG at inference for the content.

The "both" case is where most mature production systems end up. The fine-tuned model handles structure (JSON schema, function calls, tool use idioms). Retrieval handles content. This composition is strictly stronger than either alone and it's what a typical 2026 customer-support or internal-knowledge assistant looks like under the hood.

## Shipping a fine-tuned model

- Serve with vLLM or TGI; both handle LoRA adapters natively.
- Keep the adapter file separate from the base model in deployment; it's small (tens of MB), versions cleanly, and you can A/B by loading multiple adapters.
- Log every inference with `prompt_hash`, `adapter_version`, `latency_ms`, and output. You'll want this the first time quality regresses in prod.
- Set up continuous eval: your held-out set runs nightly against the current production adapter. Automated email if a metric drops > 5%.

## Further reading

- [FineTuningLargeLanguageModels] — related page, focus is conceptual
- [ModelQuantization] — the quantization math behind QLoRA
- [OpenSourceLLMs] — picking your base model
- [PracticalPromptEngineering] — the step before you reach for fine-tuning
- [RagImplementationPatterns] — when RAG is the right answer instead
- [LlmEvaluationMetrics] — metric catalogue with formulas
