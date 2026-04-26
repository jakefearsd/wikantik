---
title: Model Quantization
type: article
cluster: machine-learning
status: active
date: '2026-04-25'
tags:
- quantization
- llm
- inference
- gguf
- gptq
- awq
summary: Quantising neural networks from FP16/BF16 down to 8/4/2-bit for cheaper
  inference — the algorithms (GGUF, GPTQ, AWQ, SmoothQuant), the trade-offs,
  and what actually happens to quality.
related:
- LLMFineTuning
- CostEffectiveInference
- CpuInference
- OpenSourceLLMs
hubs:
- MachineLearning Hub
---
# Model Quantization

Quantisation reduces the precision of a neural network's weights (and sometimes activations) to make inference faster and cheaper. A 70B-parameter LLM that needs 140 GB of RAM in FP16 might fit in 35 GB at 4-bit. The quality cost is small; the deployment cost reduction is enormous.

This page is the working set for 2026: which algorithm, what to expect, and where quantisation fails.

## What changes when you quantise

A weight tensor in FP16 uses 2 bytes per parameter. Quantising to 4-bit uses 0.5 bytes — 4× smaller. Multiplied across billions of parameters, this is the difference between needing a $30k GPU and a $2k one.

Two quantisation regimes:

- **Weight-only quantisation** — only weights are quantised; activations stay in higher precision. Most common for LLM inference.
- **Full quantisation (W&A)** — both weights and activations quantised. Faster on hardware that supports it (e.g., INT8 matmul kernels) but harder to do without quality loss.

For LLMs in 2026, weight-only quantisation is dominant for serving; full quantisation is used in some specialised hardware paths.

## The algorithms

### Round-to-nearest (RTN)

The naive baseline. Pick a scale; round each weight to the nearest representable value.

```
scale = max(|w|) / max_quantised_value
w_q = round(w / scale)
```

Quality: poor at low bit-widths (3-bit and below). Good enough at 8-bit for many networks. Almost never the right pick for sub-8-bit.

### GGUF (formerly GGML)

The format used by llama.cpp and ecosystem. Multiple quantisation methods inside the format (`q4_0`, `q4_K_M`, `q5_K_S`, etc.). Each method makes different trade-offs.

`q4_K_M` is the most common community recommendation for 4-bit — keeps important tensors at higher precision, uses block-wise quantisation with dual scales.

GGUF is CPU-friendly (was designed for it). Strong for laptop / edge inference. Less common in datacenter deployments where GPU quantisation methods dominate.

### GPTQ

Post-training quantisation algorithm that uses second-order information (the inverse Hessian) to compensate for quantisation errors layer by layer. Processes layers sequentially.

Quality: typically 1-3% degradation at 4-bit on standard benchmarks. Better than RTN.

Cost: needs a calibration set (~128 sample inputs); takes minutes to hours to quantise a model.

Hardware: GPU-friendly. Used widely on Hugging Face for `gptq-int4` variants.

### AWQ (Activation-aware Weight Quantisation)

Observes that not all weights matter equally — some weights contribute disproportionately to activations. Protect those (keep at higher precision); aggressively quantise the rest.

Often beats GPTQ on quality at the same bit-width. Faster to compute the quantisation. Increasingly the recommended algorithm for production 4-bit deployment.

### SmoothQuant

Specifically for activation quantisation. Activations have outlier values that prevent full quantisation; SmoothQuant scales activations down (and weights up correspondingly) so activations are quantisable.

Used when you want INT8 W&A quantisation (matmul fully in INT8) for hardware that benefits.

### NF4 / Double-quant (QLoRA family)

Designed for training, specifically QLoRA fine-tuning. NF4 is a 4-bit format optimised for normally-distributed weights. Double-quant further compresses the quantisation constants.

For fine-tuning LoRA adapters on top of a quantised base, this is the right path. See [LLMFineTuning].

## Bit-width trade-offs

Approximate quality loss vs full-precision baseline (varies by model, task):

| Bits | Memory | Typical quality loss | Notes |
|---|---|---|---|
| 16 (FP16/BF16) | 1× | 0% | Baseline |
| 8 | 2× smaller | < 0.5% | Often imperceptible |
| 6 | ~2.7× smaller | < 1% | Good GGUF range |
| 5 | 3.2× smaller | 1-2% | GGUF popular |
| 4 | 4× smaller | 2-5% | Most production use |
| 3 | 5.3× smaller | 5-15% | Quality drops noticeably |
| 2 | 8× smaller | 20%+ | Aggressive; for memory-constrained inference |

The "knee of the curve" is around 4-bit. Below that, quality drops fast; above that, you're paying memory you didn't need.

For most production: 4-bit AWQ or 4/5-bit GGUF (q4_K_M, q5_K_M).

## Where quantisation fails

- **Activation outliers**. Some layers have rare-but-large activation values; quantising them blows up. Modern algorithms (SmoothQuant, AWQ) mitigate this; older RTN doesn't.
- **Specific model architectures**. Some models quantise worse than others. Mixture-of-Experts models can be tricky because expert routing is sensitive.
- **Reasoning tasks at low bit-width**. Tasks requiring multi-step reasoning (math, coding) degrade faster under quantisation than tasks like classification.
- **Very small models**. Sub-3B models have less redundancy; quantisation hits them harder than 70B models.

## Inference speed

Quantisation reduces memory bandwidth (the bottleneck for LLM inference on GPUs) almost proportionally to bit-width:

- 4-bit weights → 4× less memory bandwidth → 2-4× faster tokens-per-second.
- 8-bit weights → 2× less memory bandwidth → 1.5-2× faster.

Compute can also be faster on hardware with native INT4/INT8 matmul (recent NVIDIA GPUs, Apple Silicon, dedicated NPUs). Older GPUs may dequantise on-the-fly to FP16, which still wins on memory but less on compute.

## Production tooling

- **llama.cpp + GGUF** — CPU/Apple Silicon/embedded inference. The default for "run a 7B model on a laptop."
- **vLLM** — high-throughput GPU serving; AWQ and GPTQ quantisation supported.
- **TensorRT-LLM** — NVIDIA's optimised serving stack; INT8 / INT4 / FP8 paths.
- **TGI (Text Generation Inference)** — Hugging Face's serving stack; multiple quantisation backends.
- **Hugging Face `transformers` + `bitsandbytes`** — easy on-the-fly quantisation for testing; not the fastest production path.
- **MLX, MLC-LLM** — alternative inference engines with quantisation support, increasingly competitive.

Most teams: use a pre-quantised model from Hugging Face (look for `awq`, `gptq`, `gguf` variants) with a serving stack that supports it. Skip the quantisation step yourself.

## When to skip quantisation

- **You're running on hardware with abundant FP16 memory.** A100 / H100 with the model fitting in FP16 won't see meaningful speedup from quantisation.
- **Quality matters disproportionately**. Frontier reasoning tasks; legal / medical applications. Worth the extra compute.
- **You can serve at higher batch sizes**. Big batches make compute the bottleneck (not memory bandwidth); quantisation helps less.

In 2026, quantisation is the default for self-hosted LLM serving on consumer/prosumer hardware. The question is which method, not whether.

## A pragmatic path

For deploying an open-weights LLM (Llama, Mistral, Qwen):

1. **Pick a model and check Hugging Face** for pre-quantised AWQ or GGUF variants.
2. **If running on GPU**: vLLM + AWQ-int4. Strong baseline; minimal setup.
3. **If running on CPU / Mac**: llama.cpp + GGUF q4_K_M.
4. **Run your eval** to confirm quality acceptable for your task.
5. **Profile**; tune batch size, KV cache settings, draft / speculative decoding if applicable.

A day of work; production-grade serving stack at the end.

## Further reading

- [LLMFineTuning] — quantisation in fine-tuning context (QLoRA)
- [CostEffectiveInference] — broader cost optimisation
- [CpuInference] — CPU-specific paths
- [OpenSourceLLMs] — picking the base model
