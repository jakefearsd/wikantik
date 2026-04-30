---
canonical_id: 01KQ0P44SMYE17K2A8F0T0ZNKK
title: Model Selection for Efficiency
type: article
cluster: machine-learning
status: active
date: '2026-04-26'
summary: Choosing models with efficiency in mind — the model size / quality tradeoff,
  efficient architectures, and how to make selection decisions when latency or cost
  matters more than benchmark accuracy.
tags:
- model-selection
- efficiency
- machine-learning
- inference
- cost
related:
- ModelSelection
- CostEffectiveInference
- CPUInference
hubs:
- MLHub
---
# Model Selection for Efficiency

The biggest model that fits your data isn't always the right model. For production, efficiency often matters more than peak accuracy.

This page covers selection with efficiency as a first-class concern.

## The size/quality curve

Larger models perform better, with diminishing returns. The curve has different shapes per task:

- **Image classification**: smooth curve; good small models exist
- **Language modeling**: relatively smooth; emergent capabilities at scale
- **Specialized tasks**: often plateau quickly

For most production tasks, you're far from the diminishing-returns asymptote with much smaller models than the largest.

## Quality vs cost tradeoffs

You can almost always trade quality for cost:
- Smaller model: less compute, lower quality
- Quantization: less memory, sometimes lower quality
- Distillation: smaller, with less quality drop
- Pruning: less compute, sometimes quality preserved

The right tradeoff depends on:
- Quality threshold (how much can you afford to drop?)
- Cost sensitivity (how much does latency/$ matter?)
- Volume (high volume amplifies cost differences)

## Efficient architectures

### Vision

- **MobileNet, EfficientNet**: designed for efficiency
- **ConvNeXt-T**: small, modern, strong
- **DistilBERT, MiniLM**: distilled BERTs

### Language

- **Distil family** (DistilBERT, DistilGPT): 40% smaller, 95% performance
- **MiniLM**: efficient embeddings
- **Phi family** (Microsoft): small but strong
- **Gemma 2B, Llama 3.2 1B**: small LLMs

### Tabular

- **LightGBM**: usually faster than XGBoost
- **Linear models**: fastest; surprisingly often sufficient

## Distillation

Train smaller "student" model to mimic larger "teacher."

Steps:
1. Train (or use) a strong teacher
2. Generate teacher predictions on lots of data
3. Train student to match teacher (and labels)

Often: 10x smaller, 5% quality loss.

Best when:
- You have lots of unlabeled data
- Teacher is much better than from-scratch student
- Inference cost dominates training

## Quantization

Reduce numerical precision:
- FP16: usually free quality-wise; 2x speedup
- INT8: ~1% quality drop typical; 2-4x speedup
- INT4: 1-3% quality drop; further speedup

Different parts of the model tolerate different precision. Mixed-precision quantization optimizes.

For LLMs, INT4 quantization (GPTQ, AWQ) is standard for efficiency.

## Pruning

Remove weights with minimal impact:

### Unstructured pruning

Zero out individual weights. Reduces model size, but rarely speeds up inference (sparse compute is slow on most hardware).

### Structured pruning

Remove whole heads, channels, layers. Speeds up real inference.

Generally less effective per-parameter than unstructured, but actually helps in production.

## Sparse models

Mixture of Experts (MoE): only some experts active per input. Effective parameter count > active parameter count.

Used in large open models (Mixtral, DBRX). High parameter count; modest active compute.

## Smaller pretrained models

Many tasks don't need the largest pretrained model:
- Sentence-transformers/all-MiniLM-L6-v2 vs all-mpnet-base-v2: 3x smaller, ~3% quality drop on retrieval
- DistilBERT vs BERT: similar story

Test with the smaller model first.

## Architecture changes for efficiency

### Smaller hidden dimensions

Fewer parameters per layer.

### Fewer layers

Linear cost reduction.

### Shared parameters

Same weights across layers (Universal Transformers).

### Linear attention

Replace quadratic attention with linear. Variable quality impact.

These tradeoffs are model- and task-dependent.

## Hardware-aware selection

The "best" model depends on your hardware:
- GPU memory determines max model size
- Memory bandwidth determines speed for large models
- Compute determines speed for small models
- CPU vs GPU economics differ dramatically

Profile candidate models on actual deployment hardware.

## Latency budget

Set a latency budget upfront:
- Inference: X ms
- Preprocessing: Y ms
- Network: Z ms

Eliminate models that don't fit. Don't pick the best then try to optimize down.

## Cost projection

Estimate cost at production volume:
- Cost per request × requests = total

Often the answer changes the model choice:
- 1 QPS: any model is cheap
- 100 QPS: cost differences matter
- 10K QPS: only efficient models viable

## Model variants

Many model families come in size variants:
- Llama 3: 1B, 3B, 8B, 70B, 405B
- Mistral: 7B, 22B, 70B
- Claude: Haiku, Sonnet, Opus

Try the smallest. Step up if needed.

## Two-stage approaches

Use a small model first; escalate to large model when uncertain:
- Confident small-model output → use it
- Uncertain → escalate

Routing can save 80%+ inference cost while maintaining quality.

## Common failure patterns

### Choosing the strongest available model

The best benchmark model isn't always the best production model.

### Not measuring cost

Cost feels abstract until the bill arrives.

### Skipping efficiency analysis

"We'll optimize later" tends to mean never.

### Quality drops after quantization

Test quality after every optimization.

### Ignoring latency tail

p99 latency > p50 latency in user experience impact.

### Insufficient testing of small models

Often the small model is enough; teams don't try.

## Practical workflow

1. Define latency budget and cost budget
2. List candidate models that meet budget at typical sizes
3. Pick smallest plausible candidate
4. Evaluate quality
5. If insufficient, step up size
6. Apply efficiency optimizations (quantization, distillation) as needed
7. Profile end-to-end before deployment

## When efficiency doesn't matter

- Internal tools, low volume
- Prototypes
- Tasks where quality dwarfs cost

For these, just pick the best model and ship.

## Further Reading

- [ModelSelection](ModelSelection) — General model selection
- [CostEffectiveInference](CostEffectiveInference) — Cost optimization
- [CPUInference](CPUInference) — CPU-based deployment
- [ML Hub](MLHub) — Cluster index
