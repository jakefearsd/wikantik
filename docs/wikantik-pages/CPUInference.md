---
canonical_id: 01KQ0P44MV6PQB6DVJ6NST7SZ7
title: CPU Inference
type: article
cluster: machine-learning
status: active
date: '2026-04-26'
summary: Running ML models on CPUs — when it makes sense, the optimization techniques
  that close the gap with GPUs, and the realistic performance expectations for modern
  workloads including quantized LLMs.
tags:
- cpu-inference
- machine-learning
- optimization
- quantization
- deployment
related:
- CostEffectiveInference
- InferenceServing
- ModelSelectionEfficiency
hubs:
- ML Hub
---
# CPU Inference

Most ML inference content assumes GPUs. But CPUs run ML in production all the time — and modern CPUs run more than people realize, including quantized LLMs.

When CPU inference is viable, it's often cheaper, simpler, and more available.

## When CPU makes sense

### Small models

For models under ~1B parameters, CPUs are often competitive.

Examples:
- Embeddings (BERT-base, sentence transformers): CPUs work well
- Small classifiers (gradient boosting, smaller neural nets): CPUs win
- Tabular ML: CPUs are usually best

### Low QPS or batch workloads

If you're serving 1 request per second, a GPU sits 99% idle.

CPUs scale down better. Batch jobs without latency requirements often run cheapest on CPUs.

### Edge deployment

No GPUs available. Mobile, embedded, browser deployments.

### Cost-sensitive applications

Cloud CPU costs ~10x less than GPU per hour. If your model fits, the math is obvious.

### Data privacy / on-prem

CPU servers are plentiful. Customer environments may not have GPUs.

## When GPU is required

- Large models (>10B params unquantized)
- High QPS where amortizing GPU cost works
- Training (almost always GPU)
- Latency-critical real-time inference of large models

## CPU optimization techniques

### Vectorization

Modern CPUs have SIMD instructions:
- AVX-2: 256-bit (8 floats)
- AVX-512: 512-bit (16 floats)
- AMX (Intel Sapphire Rapids+): matrix operations

Numerical libraries (MKL, OpenBLAS) use these automatically.

### Quantization

INT8 quantization is dramatic on CPU:
- 4x less memory bandwidth
- 4x more arithmetic per cycle (with AVX-512 VNNI)
- Often 3-4x speedup

INT4 quantization (with libraries like llama.cpp) makes 7B+ LLMs viable on CPUs.

### Threading

Multi-core CPUs benefit from intra-op parallelism. PyTorch and TensorFlow do this automatically.

For many small models, thread coordination overhead means single-threaded is fastest.

### Memory layout

Cache-friendly layouts matter. Most frameworks handle this; custom kernels need care.

### Compile-time optimization

ONNX Runtime, OpenVINO, TVM specialize models for the target CPU.

Often 2-3x faster than naive PyTorch CPU.

## Specific tools

### ONNX Runtime

Cross-platform inference engine. Supports CPU, GPU, accelerators.

CPU performance is excellent. Often the right default for production CPU inference.

### OpenVINO

Intel's optimization toolkit. Best on Intel CPUs.

Strong for vision and speech models.

### llama.cpp

C++ library for LLM inference. Heavy use of quantization.

Runs Llama-3-8B comfortably on a modern laptop CPU. 70B models work on workstations.

### TFLite / Core ML

Mobile-focused. Highly optimized for ARM CPUs.

### scikit-learn

Tree-based models, linear models — CPU-only by default and fast.

## Realistic expectations

### Embeddings

Sentence-transformers (~80M params) on modern CPU: ~50-100 sentences/sec single-threaded.

Quantized: 200-400 sentences/sec.

### Small classification models

Sub-millisecond inference for many production models.

### LLMs (quantized)

7B Q4: 5-15 tokens/sec on a fast desktop CPU. Slow but usable for some applications.

13B Q4: 3-8 tokens/sec.

70B Q4: 1-3 tokens/sec on high-end hardware.

For interactive use, GPUs win. For batch and async use, CPU is viable.

## Memory bandwidth matters most

Modern CPUs are typically memory-bandwidth-bound on ML workloads.

DDR4: ~25 GB/s
DDR5: ~50 GB/s
HBM (server CPUs): 100s GB/s

This is why a 7B model at Q4 (~3.5 GB) generates ~7 tokens/sec on DDR5 — every token requires reading the whole model.

Quantization helps mainly through reduced memory bandwidth.

## Practical deployment

### Containerization

Docker containers with CPU images are standard. Easy to scale.

### Autoscaling

CPU services scale predictably. No GPU-specific orchestration.

### Multi-tenant

CPUs handle multi-tenant well. GPUs require careful memory management.

### Serverless

AWS Lambda, Cloud Functions, Cloudflare Workers — all CPU-only. Increasingly viable for small models.

## Common failure patterns

### Defaulting to GPU without measuring

Many production models would run fine on CPU. Measure before committing.

### Not quantizing

INT8 is almost free quality-wise; major speedup.

### Naive PyTorch CPU

Use ONNX Runtime or compiled inference engines for production.

### Ignoring AVX-512

Some cloud instances lack AVX-512. Check before benchmarking.

### Wrong threading

Sometimes single-threaded beats multi-threaded for small models.

## Cost example

GPU instance (A10): ~$1/hour
CPU instance (8-core): ~$0.10/hour

If your QPS is low enough that 8 cores keep up, CPU is 10x cheaper.

For ~1 QPS embedding workload, CPU likely wins decisively.

## Further Reading

- [CostEffectiveInference](CostEffectiveInference) — Cost optimization
- [InferenceServing](InferenceServing) — Serving infrastructure
- [ModelSelectionEfficiency](ModelSelectionEfficiency) — Model size tradeoffs
- [ML Hub](ML+Hub) — Cluster index
