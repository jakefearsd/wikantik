---
title: Open Source LLMs
type: article
cluster: agentic-ai
status: active
date: '2026-04-25'
tags:
- open-source-llm
- llama
- mistral
- qwen
- self-hosted
summary: The open-weights LLM landscape in 2026 — Llama, Mistral, Qwen, DeepSeek,
  the licensing shifts, and the production decisions (which model, what hardware,
  serving stack) that decide whether self-hosting wins or loses.
related:
- LLMFineTuning
- ModelQuantization
- CostEffectiveInference
- CpuInference
- LlmTokenEconomicsAndPricing
hubs:
- AgenticAi Hub
---
# Open Source LLMs

By 2026, open-weights LLMs are competitive with frontier commercial models on most tasks below the absolute frontier. The 6-12 month gap to GPT-class / Claude-class quality persists for the hardest reasoning tasks, but for most production work, open-weights is a credible choice.

This page is the state of the field, the decisions, and the operational reality of running these models.

## "Open source" is overloaded

Three distinct things often called "open source":

1. **Open weights** — model parameters published, usable for inference. License terms vary.
2. **Open training code** — the code used to train the model is published; reproducible.
3. **Open data** — the training data is published.

Most "open source" LLMs are open-weights only. Truly open (weights + code + data) models exist (OLMo, Pythia) but lag in capability. For practical use, "open weights" is what matters.

License variations matter:

- **Apache 2.0 / MIT** — fully permissive (Mistral original models, DeepSeek, Qwen). Use commercially without restriction.
- **Llama Community License** — permissive for most commercial use; restrictions above 700M MAU; explicit AUP.
- **Custom non-commercial licenses** — research only (e.g., older OpenAI Whisper variants for some uses).

Read the license. Treat "open weights" as a starting point; check the specific model.

## The 2026 leaders

### Meta Llama family

- **Llama 3.1, 3.2, 3.3 family** — strong across sizes (8B, 70B, 405B). Decent reasoning, good multilingual.
- **Llama 4** (released 2025) — current frontier of the family.

Strengths: ecosystem maturity (every tool supports Llama format); broad community fine-tunes; strong baseline.

Weaknesses: license has commercial restrictions for very large deployments; not the absolute strongest at any given size.

### Mistral

- **Mistral Small / Medium / Large** — recent names; capable; Apache 2.0 for many variants.
- **Mistral Codestral** — code-focused.
- **Mixtral** — mixture-of-experts; strong quality per inference cost.

Strengths: efficient; Apache 2.0 (in most variants); European data sovereignty option.

### Qwen (Alibaba)

- **Qwen 2.5, Qwen 3** — competitive frontier-adjacent. Strong on multilingual, code, math.
- **Qwen-VL, Qwen-Audio** — multimodal variants.
- **Qwen Coder** — code specialist.

Strengths: regularly tops open-weights leaderboards; strong Chinese capabilities; permissive license for most variants.

### DeepSeek

- **DeepSeek V3** — large MoE; competitive with GPT-class on many benchmarks.
- **DeepSeek R1** — reasoning model; open weights for reasoning is rare.
- **DeepSeek Coder** — strong code model.

Strengths: aggressive on reasoning; open R1 was a notable release. License is permissive.

### Smaller specialists

- **Phi family (Microsoft)** — small, surprisingly capable. Good for edge.
- **Gemma (Google)** — small to mid-size; fast.
- **OLMo (AI2)** — fully open including data and code.
- **StarCoder, StarCoder2** — code models.
- **Code Llama** (deprecated; superseded by Llama with code training).

For a given size point, the leader rotates. Check leaderboards monthly during active eval.

## Picking by use case

| Use case | Reasonable picks (early 2026) |
|---|---|
| General chat, mid-quality | Mistral Small, Qwen 2.5 7B |
| General chat, high-quality | Llama 70B, Mistral Large, Qwen 72B |
| Frontier reasoning | DeepSeek R1, Qwen reasoning variants |
| Code generation | Qwen Coder, DeepSeek Coder, Codestral |
| Multimodal (image) | Qwen-VL, Llava family, MoE-VL |
| Edge / mobile | Gemma 2B, Phi-3.5-mini |
| On-device assistant | Llama 3.2 1B / 3B, Phi-3.5 |
| Truly open (weights+code+data) | OLMo |
| Multilingual non-English | Qwen, Aya (Cohere) |

These rotate every few months. Recheck before adopting.

## When self-hosting wins

The economic break-even depends on traffic shape:

- **Sustained high volume** — a model running 20+ RPS on dedicated hardware is cheaper than API calls at typical commercial pricing.
- **Latency-critical** — colocated GPU inference returns in 100ms; commercial APIs vary; you control yours.
- **Privacy / compliance** — data can't leave your VPC; on-prem is the only option.
- **Custom fine-tunes** — you have a fine-tuned model; commercial APIs don't host yours.
- **Edge / on-device** — small models on user devices; no remote inference.

For these, self-hosting is the right call.

## When commercial APIs win

- **Bursty traffic** — provisioning enough GPU for peak wastes the rest.
- **Frontier quality matters** — open-weights still trails frontier commercial on hard reasoning.
- **You don't want to operate inference** — it's real ops work.
- **Cross-cutting capabilities** (vision, code, function calling, long context) bundled in one API.

For these, pay for the API. The self-host operational cost outweighs the API savings.

## Hardware sizing

Approximate VRAM for inference:

| Model | FP16 | Int8 | Int4 |
|---|---|---|---|
| 7B | ~14 GB | ~8 GB | ~5 GB |
| 13B | ~26 GB | ~14 GB | ~8 GB |
| 70B | ~140 GB | ~80 GB | ~40 GB |
| 405B | ~810 GB | ~440 GB | ~220 GB |
| MoE 8x22B | ~280 GB (all loaded; ~80 GB activated) | scaled |

These are rough; concrete numbers depend on context size, KV cache, batching.

Hardware:

- **Consumer**: RTX 4090 (24 GB) — 7B FP16, 13B INT8, 70B INT4 with offloading.
- **Workstation**: A6000 (48 GB), or pair of 4090s — 70B INT4, mid-size FP16.
- **Datacenter**: H100 (80 GB), A100 (40 / 80 GB), H200 (141 GB) — large models.
- **Apple Silicon**: M2/M3/M4 Ultra with up to 192 GB unified memory — surprising option for medium models on macOS.

For most teams: an A100 80 GB or H100 serves a 70B at INT8 with reasonable batching. ~$3-5/hour cloud, or ~$30k purchase.

## Serving stacks

- **vLLM** — high-throughput; PagedAttention; the production default.
- **TGI (Text Generation Inference)** — Hugging Face's serving stack; mature.
- **TensorRT-LLM** — NVIDIA-optimised; fastest on NVIDIA hardware; more complex.
- **llama.cpp** — CPU + Apple Silicon + lighter GPU; widely used for laptop / edge.
- **MLX** — Apple Silicon; clean.
- **MLC-LLM** — multi-platform; targets diverse hardware.
- **SGLang** — newer; structured-output-friendly.

For most production GPU serving in 2026: vLLM is the safe default. TensorRT-LLM if you've maxed vLLM and need more throughput.

## Fine-tuning open models

LoRA / QLoRA fine-tuning on top of open-weights base. Standard tooling: HuggingFace `trl`, `peft`, axolotl, unsloth. See [LLMFineTuning].

The full-finetune path (modify all weights) is rare unless you have unusual budget. LoRA suffices for nearly every domain adaptation use case.

A LoRA adapter is small (tens of MB); deploy alongside the base model. Multiple LoRAs per base = multiple specialised models from one inference setup.

## Quantisation in production

Most production self-hosted serving uses 4-bit or 8-bit quantisation. AWQ or GPTQ for GPU serving; GGUF for CPU / Apple Silicon. See [ModelQuantization].

Quality cost: 1-3% on most benchmarks at 4-bit. Memory savings: 4×. Throughput gain: 2-4× (memory-bandwidth-bound LLM inference).

## What's coming

Trends through 2026:

- **More efficient MoE** — better routing, sparser activation. Quality per inference FLOP keeps improving.
- **Reasoning-mode open weights** — DeepSeek R1 was the start; expect more.
- **Long context efficiency** — Mamba / SSM variants; sparse attention; ring attention. Open-weights long-context becomes routine.
- **Vision-language merge** — multimodal-by-default open models.
- **Stronger small models** — 3-7B models reaching what 70B did a year ago. Edge inference becomes more capable.

The cadence of new releases is fast; lock in for 6 months at most before re-evaluating.

## A pragmatic recipe

For a team adopting open-weights:

1. **Pilot with a few models** — Llama 70B, Mistral Large, Qwen 72B at minimum.
2. **Run your eval** on each.
3. **Pick the strongest** for your task (often surprises; not always the latest).
4. **Quantise** to 4-bit AWQ or 8-bit GPTQ.
5. **Serve via vLLM** on appropriate hardware.
6. **Set up observability** — see [AiObservabilityInProduction].
7. **Build a periodic re-eval habit** — check new model releases monthly.

Two weeks of work; production-grade open-weights serving.

## Further reading

- [LLMFineTuning] — adapting open models
- [ModelQuantization] — making them cheaper
- [CostEffectiveInference] — broader cost optimisation
- [CpuInference] — when GPUs aren't available
- [LlmTokenEconomicsAndPricing] — comparison economics
