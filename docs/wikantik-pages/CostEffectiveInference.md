---
canonical_id: 01KQ0P44P2RTSTPVGVX1EPPBES
title: Cost-Effective Inference
type: article
cluster: machine-learning
status: active
date: '2026-04-26'
summary: Practical strategies for reducing the cost of model inference — quantization,
  distillation, batching, caching, model selection — without unacceptable quality
  loss.
tags:
- inference
- machine-learning
- cost-optimization
- llm
- deployment
related:
- InferenceServing
- CPUInference
- PromptCaching
- ModelSelectionEfficiency
hubs:
- MLHub
---
# Cost-Effective Inference

Training models is expensive but bounded; inference is unbounded — cost scales with usage. For successful products, inference cost dominates total ML spend.

This page covers practical levers for reducing inference cost.

## The cost equation

Inference cost ≈ (compute per request) × (requests) / (compute per dollar)

You can attack any term:
- Reduce compute per request (smaller model, optimization)
- Reduce requests (caching, batching, prefiltering)
- Increase compute per dollar (better hardware, spot pricing)

## Model selection

The cheapest optimization: use a smaller model.

For LLMs:
- GPT-4 → GPT-4-mini: 10-50x cheaper, often acceptable
- Claude Opus → Claude Haiku: similar tradeoff
- 70B → 7B model: ~10x cheaper

For traditional ML:
- Deep neural net → gradient boosting → linear model

Always test: many tasks don't need the strongest model.

## Quantization

Reduce numerical precision of weights and activations.

- FP32 → FP16: 2x memory, ~2x speed
- FP16 → INT8: 2x memory, ~2x speed
- INT8 → INT4: 2x memory, marginal speed (depends on hardware)

Quality impact is usually minimal for FP16 and INT8. INT4 needs care.

Tools: bitsandbytes, GPTQ, AWQ, llama.cpp.

## Distillation

Train a smaller "student" model to mimic a larger "teacher."

Common approach:
1. Run teacher on lots of data
2. Train student on (input, teacher_output) pairs
3. Deploy student

Works well for many tasks. Requires the teacher and good training infrastructure.

## Pruning

Remove weights that contribute little. Unstructured pruning saves memory but rarely speed; structured pruning (entire heads, layers) speeds inference.

## Batching

Process multiple requests together. Modern GPUs are heavily underutilized at batch size 1.

Static batching: collect N requests, run together. Adds latency.

Dynamic batching: form batches at the inference engine. Used in vLLM, TGI.

Continuous batching: especially for autoregressive models, allows joining/leaving batches mid-generation. Major throughput gain.

## Caching

### Response caching

Identical request? Return cached response.

Hash the request (or relevant parts) as cache key.

Works best for deterministic outputs.

### Prompt caching

For LLMs: cache the prefix computation. New requests reusing the prefix skip recomputation.

Anthropic, OpenAI, and others now offer this directly.

Major savings for long system prompts or RAG with repeated context.

See [PromptCaching](PromptCaching).

### Semantic caching

Cache based on semantic similarity, not exact match. "What's the capital of France?" and "Capital of France?" share an answer.

Use embeddings + nearest neighbor lookup.

Risk: false-positive matches return wrong answers.

## Speculative decoding

For LLMs: a small "draft" model proposes tokens; the large model verifies in parallel.

Net effect: same outputs, fewer large-model forward passes. 2-3x speedup typical.

## Routing

Use multiple models tiered by capability:
- Cheap model handles 80% of queries
- Escalate to expensive model only when needed

Routing logic ranges from rules to learned classifiers.

## Hardware choices

### GPUs

A100, H100: high throughput, expensive
A10, L4: mid-tier
T4: budget, still capable

For LLMs, memory bandwidth often matters more than FLOPs.

### CPUs

For small models or non-latency-sensitive workloads, CPU inference is often cheaper.

Modern CPUs with AVX-512 / AMX can run quantized LLMs surprisingly well.

See [CPUInference](CPUInference).

### Accelerators

TPUs, Inferentia, Groq — specialized hardware can offer better cost/performance for some workloads.

### Spot/preemptible instances

For batch inference: 50-90% cheaper but can be interrupted.

## Batch vs real-time

If you don't need real-time:
- Batch overnight on cheap hardware
- Use spot instances
- Larger batch sizes

Many use cases don't actually need real-time.

## API vs self-hosted

API providers offer:
- Zero ops cost
- Latest models
- Pay-per-use

Self-hosted:
- Lower per-token cost at high volume
- Custom models
- Data privacy
- Operational burden

The breakeven varies. Many teams underestimate self-hosting ops cost.

## Measurement

Without metrics, optimization is guesswork. Track:
- Cost per request
- p50/p95/p99 latency
- Requests per second
- GPU utilization
- Cache hit rate

## Common failure patterns

### Premature optimization

Hand-tuning quantization for a model you'll replace next month wastes effort.

### Ignoring the cheap wins

Caching often saves 50%+ with little engineering.

### Over-engineering routing

Complex routing systems can cost more in maintenance than they save in inference.

### Not measuring quality after optimization

Quality regressions from quantization, distillation, or routing can be subtle.

### Using the wrong model

The strongest model is rarely needed. Test smaller models.

## Decision order

1. Choose the smallest model that works
2. Add caching aggressively
3. Quantize as much as quality allows
4. Batch where latency permits
5. Consider distillation for large-volume tasks
6. Optimize hardware/deployment

## Further Reading

- [InferenceServing](InferenceServing) — Serving infrastructure
- [CPUInference](CPUInference) — CPU-based deployment
- [PromptCaching](PromptCaching) — Caching for LLMs
- [ModelSelectionEfficiency](ModelSelectionEfficiency) — Model size tradeoffs
- [ML Hub](MLHub) — Cluster index
