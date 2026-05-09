---
cluster: agentic-ai
canonical_id: 01KQ0P44TT1XDG7NR610YMJK18
title: Production Local AI
type: article
tags:
- model
- production
- vllm
- serving
- security
summary: Architectural guide for serving local AI in production, focusing on vLLM throughput optimization, quantization strategies, and multi-tenant security.
status: active
date: '2026-04-24'
auto-generated: false
---
# Production-Grade Local AI Serving

Transitioning from a local demo to a production-grade service requires moving beyond single-user tools like Ollama to high-throughput inference engines like vLLM, and implementing robust security boundaries.

## Throughput Optimization: vLLM vs. Ollama

While Ollama is excellent for developer workflows, its sequential inference model is a bottleneck for multi-user applications. For production, **vLLM** is the standard.

### PagedAttention & Continuous Batching
The core innovation of vLLM is **PagedAttention**, which manages the Key-Value (KV) cache like virtual memory. This eliminates fragmentation and allows for:
- **Continuous Batching:** Processing new requests as soon as an existing one finishes a token, rather than waiting for the entire batch to complete.
- **High Concurrency:** Serving 10x-20x more concurrent users on the same GPU compared to naive transformers implementations.

### Concrete Example: Launching a vLLM Server
To serve Llama 3 8B with 4-bit quantization and high throughput:

```bash
python -m vllm.entrypoints.openai.api_server \
    --model meta-llama/Meta-Llama-3-8B-Instruct \
    --quantization bitsandbytes \
    --load-format bitsandbytes \
    --gpu-memory-utilization 0.95 \
    --max-model-len 8192 \
    --port 8000
```

## Quantization Strategies

Selecting the right quantization format depends on your hardware and fidelity requirements:

| Format | Library | Best For | Pros/Cons |
|---|---|---|---|
| **GGUF** | llama.cpp | CPU + GPU Mixed | Maximum compatibility; slower than pure GPU formats. |
| **NF4** | bitsandbytes | NVIDIA GPUs | Industry standard for 4-bit; good balance of speed/quality. |
| **EXL2** | ExLlamaV2 | NVIDIA GPUs | Extreme speed for local inference; requires specialized kernels. |
| **AWQ** | AutoAWQ | Production Serving | Activation-aware; excellent quality retention for reasoning. |

**Expert Tip:** For vLLM, use **AWQ** or **FP8** (on H100s) for the best throughput-to-quality ratio.

## Multi-Tenant Security

In a production environment where multiple users or agents hit the same model, you must defend against **Prompt Injection** and **Data Leakage**.

### 1. Prompt Injection Guards
Use a "Sandwich" approach:
- **Pre-processor:** A small, fast model (like Llama-Guard) checks the user input for malicious intent *before* it reaches the main LLM.
- **System Prompt Hardening:** Place non-negotiable instructions at the *end* of the prompt context to prevent them from being overridden by earlier user input.

### 2. Namespace Isolation (RAG)
When using Retrieval-Augmented Generation, ensure users only retrieve documents they are authorized to see.
- **Metadata Filtering:** Tag every vector with an `owner_id` or `tenant_id`.
- **Enforcement:** The retrieval query must include a hard filter: `collection.query(..., where={"tenant_id": current_user_id})`.

## High Availability & Observability

### Health Checks
A production LLM service must expose a `/health` endpoint that checks not just if the process is running, but if the GPU is responsive and model weights are loaded.

### Tracing with OpenTelemetry
Integrate tracing to identify bottlenecks in the RAG pipeline.
- **Span 1:** Query Embedding (Latency)
- **Span 2:** Vector Search (Recall)
- **Span 3:** LLM Inference (Tokens/sec)

**Concrete Metric:** Monitor **Time to First Token (TTFT)** and **Inter-Token Latency**. If TTFT exceeds 2 seconds, your batch size or concurrent request count is likely too high for your hardware.
