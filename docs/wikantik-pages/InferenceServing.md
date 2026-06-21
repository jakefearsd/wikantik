---
date: '2026-04-26'
summary: Production inference serving — serving frameworks, batching strategies, autoscaling,
  multi-model deployment, and the operational realities that determine whether ML
  models actually deliver business value.
cluster: machine-learning
related:
- CostEffectiveInference
- CPUInference
- MlModelDeployment
canonical_id: 01KQ0P44R4PPR5C2BAXMJJ4871
type: article
title: Inference Serving
status: active
hubs:
- MLHub
- MlModelDeploymentHub
tags:
- inference
- serving
- machine-learning
- deployment
- operations
---
# Inference Serving

Training a model is a project; serving it is a system. Production inference serving has its own discipline distinct from training.

This page covers the practical concerns.

## Core requirements

A production inference service must:
- Accept requests (HTTP, gRPC, queue)
- Run models efficiently
- Scale with traffic
- Recover from failures
- Be observable
- Update models safely

## Serving frameworks

### TorchServe

PyTorch's official serving framework. Decent default for PyTorch models.

### TensorFlow Serving

Mature, performant. Strong for TF models.

### NVIDIA Triton

Multi-framework. Excellent batching and GPU utilization. Industry standard for GPU serving.

### Ray Serve

Python-native, flexible composition. Good for complex pipelines.

### vLLM, TGI

LLM-specific. Continuous batching and PagedAttention give major throughput gains.

### Custom (FastAPI/Flask + model)

Quick to build; loses out on optimizations like batching.

Choose based on:
- Framework you trained in
- Model type (LLM vs traditional)
- Scale requirements
- Team experience

## Batching strategies

### No batching

One request → one inference. Wastes hardware.

### Static batching

Wait for N requests, then batch. Adds latency.

### Dynamic batching

Form batches based on queue + max wait time. Tunable latency-throughput tradeoff.

### Continuous batching (LLMs)

Requests can join/leave the batch mid-generation. Major throughput improvement for autoregressive models.

vLLM and TGI implement this.

## Latency budget

Define p50, p95, p99 latency targets.

Components:
- Network in
- Queueing
- Preprocessing
- Inference
- Postprocessing
- Network out

Profile each. Common surprises:
- Tokenization can dominate for short LLM requests
- Preprocessing/postprocessing in Python is slow
- TCP setup adds tens of ms for cold connections

## Autoscaling

Scale up to handle load; scale down to save money.

### Metrics

- QPS / RPS (request-rate based)
- GPU/CPU utilization
- Queue length / age (best for ML)
- Custom metrics

### Cold start

GPUs and large models load slowly. Cold start can be 30s+.

Mitigations:
- Keep minimum replicas warm
- Pre-warm on scale-up signals
- Use smaller models for low traffic

### Spot instances

For non-critical, interruption-tolerant workloads, spot instances cut cost dramatically.

## Multi-model serving

### Multi-model on one instance

Multiple models share resources. Saves money for low-traffic models.

### Model ensemble pipelines

Output of one model feeds another. Common for vision + classification, retrieval + reranking.

Triton's ensemble feature; Ray Serve composition.

### Routing

Choose model per request based on input characteristics.

## Versioning and rollout

### Blue-green

Two environments; switch traffic atomically.

### Canary

Send small % to new version. Monitor metrics. Increase gradually.

### Shadow / mirror

Run new version in parallel without serving its responses. Compare quality.

### A/B testing

Send different traffic to different versions. Measure business impact.

For ML models, output drift between versions is common. Shadow testing catches surprises.

## Monitoring

### Latency

p50, p95, p99 — track all three.

### Throughput

QPS over time. Detect traffic anomalies.

### Errors

Inference errors, timeouts, OOM.

### Quality

This is unique to ML:
- Distribution shift detection
- Output statistics
- Sample-based human review
- Online metrics where available

### Cost

Cost per request, by model. Surprising things happen.

## Caching

### Response caching

Identical input → cached output. Effective for queries with repetition.

### Embedding caching

For pipelines with embeddings, cache by content hash.

### Prompt caching (LLMs)

Cache prefix computations. Major savings for system prompts and RAG.

## Resource isolation

Multi-tenant models can interfere:
- One model's batch starves another
- OOM in one impacts all
- GPU memory fragmentation

Mitigations:
- Per-model resource limits
- Queue isolation
- Separate processes/pods for isolation

## Failure handling

### Graceful degradation

Model down? Fall back to:
- Cached results
- Simpler model
- Static response
- Explicit "unavailable" message

### Circuit breakers

If error rate spikes, stop sending traffic. Lets the system recover.

### Retries

Retry transient failures. Avoid retry storms.

### Timeouts

Per-request timeouts prevent slow requests from blocking workers.

## Hardware utilization

### GPU utilization

GPU "utilization" metric is misleading. A GPU at 100% utilization may be memory-bandwidth-bound.

Better: tokens/second, requests/second.

### Memory

Out-of-memory is the most common failure mode. Monitor headroom.

### CPU/GPU split

For small models, CPU may be cheaper. See [CPUInference](CPUInference).

## Common failure patterns

### Optimizing inference but not the rest

Tokenization, preprocessing, network — often the bottleneck.

### Insufficient observability

Without metrics, you can't optimize.

### Under-provisioning for tail latency

p99 matters for user experience even when p50 looks fine.

### Cold start surprises

Autoscaling that creates 30s of timeouts.

### Model swap regressions

New model deploys and quality silently drops.

### Batch starvation

One slow request blocks an entire batch. Mitigate with timeouts and dynamic batching.

## Operational maturity

Stages:
1. **Notebook to API**: hosted demo, no SLA
2. **Production POC**: serves real traffic, manual ops
3. **Scaled production**: autoscaling, monitoring, on-call
4. **Optimized production**: cost optimization, multi-model, sophisticated routing

Most teams under-invest in stages 3-4.

## Build vs buy

Hosted inference services (Replicate, Together, Modal, AWS SageMaker, Vertex AI) handle a lot of this.

For small teams, hosted is often the right call until cost becomes prohibitive.

## Further Reading

- [CostEffectiveInference](CostEffectiveInference) — Cost optimization
- [CPUInference](CPUInference) — CPU-based serving
- [MlModelDeployment](MlModelDeployment) — Deployment processes
- [ML Hub](MLHub) — Cluster index
