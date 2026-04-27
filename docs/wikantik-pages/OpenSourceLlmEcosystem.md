---
canonical_id: 01KQ0P44TCFFVK8R06KHB77Z9B
title: Open Source LLM Ecosystem
type: article
cluster: generative-ai
status: active
date: '2026-04-26'
summary: A practical map of the open source LLM landscape — model families, inference
  engines, fine-tuning tools, and deployment patterns — for teams making informed
  decisions about open vs closed model use.
tags:
- llm
- open-source
- generative-ai
- inference
- fine-tuning
related:
- TransformerArchitecture
- AgentPromptEngineering
- PromptCaching
hubs:
- Generative AI Hub
---
# Open Source LLM Ecosystem

The open source LLM ecosystem has matured rapidly. Open models are competitive with closed APIs for many use cases. Tooling is solid. The question for most teams is when open makes sense, not whether it does.

This page maps the landscape.

## Why open source

### Cost

At high volume, self-hosted open models cost less than API calls.

### Privacy

Data stays on your infrastructure. No API provider sees it.

### Control

You choose the model, configuration, deployment. No surprise deprecations or behavior changes.

### Customization

Fine-tuning, embeddings, full weight access. Things APIs can't offer.

### Offline / edge

Some deployments must run without internet (regulated industries, offline products).

### Skill development

Working with open models builds organizational ML capabilities.

## Major model families

### Llama (Meta)

The dominant open family.
- Llama 3.1: 8B, 70B, 405B
- Llama 3.2: 1B, 3B, 11B, 90B (multimodal)
- Llama 3.3: 70B
- Code Llama: code-specialized

License allows commercial use with conditions.

### Mistral

- Mistral 7B: small, strong
- Mixtral 8x7B / 8x22B: MoE
- Mistral Large: closed but available via API
- Codestral: code

Apache 2.0 for older models; custom for newer.

### Qwen (Alibaba)

- Qwen 2.5: 0.5B, 1.5B, 3B, 7B, 14B, 32B, 72B
- Specialized: Coder, Math
- Strong at non-English languages

Apache 2.0 for many variants.

### Gemma (Google)

- Gemma 2: 2B, 9B, 27B
- Distilled from Gemini

Custom Google license.

### Phi (Microsoft)

- Phi-3 / Phi-4: small but strong
- Trained on heavily curated data

MIT license.

### DeepSeek

- DeepSeek V2 / V3: large MoE
- DeepSeek-Coder

Strong technical capability.

### Specialized models

- **Code**: Codestral, DeepSeek-Coder, StarCoder, Qwen-Coder
- **Embeddings**: BGE, E5, sentence-transformers, GTE
- **Vision**: CLIP, LLaVA, Qwen-VL
- **Speech**: Whisper, Parakeet

For specialized tasks, specialized models often beat large generalists.

## Inference engines

### llama.cpp

C++ library. CPU-friendly. Aggressive quantization.

The standard for running quantized LLMs on consumer hardware.

### vLLM

Python/CUDA. Continuous batching, PagedAttention.

Standard for high-throughput GPU serving.

### Text Generation Inference (TGI)

Hugging Face's serving framework. Similar capabilities to vLLM.

### Ollama

Simplified deployment for local LLM use. Built on llama.cpp.

### LM Studio

GUI for local inference. Good for non-technical users.

### exLlamaV2 / TabbyAPI

Memory-efficient GPU inference, especially for quantized models.

### MLC

Compiles models for many backends (mobile, browser, edge).

## Quantization tools

### GGUF (llama.cpp)

Format and quantization for CPU/edge inference.

Quants: Q2 (smallest, lowest quality) → Q8 (largest, highest quality).

Q4_K_M is a common balance.

### GPTQ

GPU-friendly quantization. Common in HF model hub.

### AWQ

Activation-aware. Often slightly better quality at same precision than GPTQ.

### EXL2

For exllamav2. Variable per-layer precision.

### bitsandbytes

Easiest path; native PyTorch support. 4-bit, 8-bit.

## Fine-tuning

### Full fine-tuning

Update all weights. Memory-intensive; not always best.

### LoRA / QLoRA

Train small adapter weights. 100x fewer trainable parameters; quality nearly matches full fine-tuning for many tasks.

QLoRA: LoRA on quantized base. Runs on consumer GPUs.

### Tools

- Hugging Face PEFT
- axolotl
- LLaMA-Factory
- Unsloth (faster training)

### When fine-tuning beats prompting

- Lots of training data
- Specialized format / style
- Cost / latency requirements
- Repeated specific tasks

### When prompting wins

- Limited examples
- Tasks change frequently
- Capability already exists in base model

## Vector databases (for RAG)

Open source:
- **Qdrant**: Rust, strong feature set
- **Weaviate**: Go, integrated ML
- **Milvus**: scalable, mature
- **pgvector**: PostgreSQL extension; simplest if you have PG
- **Chroma**: Python-friendly, smaller scale

Commercial: Pinecone, Vespa.

## Frameworks

### LangChain

Python, JavaScript. Many integrations. Some criticism for over-abstraction.

### LlamaIndex

Focused on RAG and indexing.

### Haystack

Mature, modular pipelines.

### DSPy

Programming model for LLM applications. Compile prompts.

### Direct API calls

Often the right choice. Frameworks add abstraction overhead.

## Hardware for self-hosting

### Consumer

- RTX 4090: 24GB, runs 7B-13B comfortably, 30-70B with quantization
- M-series Mac: unified memory advantage; Mac Studio with 128GB+ runs 70B models

### Workstation

- 2-4 RTX 4090: 70B at full precision
- A6000: 48GB

### Server

- A100 (40/80GB), H100 (80GB)
- Multi-GPU for very large models

### Cloud

- Hosted services (Replicate, Together, Modal, Fireworks)
- GPU rentals (Lambda, RunPod, Paperspace)
- Hyperscaler (AWS, GCP, Azure)

## Quality vs closed APIs

The picture in late 2025/early 2026:

- **General reasoning**: closed (GPT-4, Claude) ahead but gap narrowing
- **Specific benchmarks**: open often competitive or better
- **Coding**: open (Qwen-Coder, Codestral) competitive
- **Long context**: closed ahead for very long context
- **Multimodal**: open catching up

For many production tasks, a 70B open model is sufficient.

## Cost comparison

### API

- GPT-4o: ~$2.50 / 1M input tokens
- Claude Sonnet: similar
- Smaller models: 10-100x cheaper per token

### Self-hosted

Hardware + electricity + ops time.

A100 instance: ~$1-2/hour.

Throughput at 70B: ~1000-3000 tokens/sec with batching.

Breakeven: depends on volume. Often 10M+ tokens/day favors self-hosting if you have the ops capability.

## Common failure patterns

### Underestimating ops

Self-hosting LLMs is harder than running APIs.

### Choosing model by benchmark

Benchmark performance doesn't always match your task.

### Wrong quantization level

Q2 ruins quality. Q8 wastes memory. Q4-Q5 is usually right.

### Frameworks over fundamentals

Reaching for LangChain when you need 50 lines of Python.

### Skipping evaluation

Open models need evaluation on your specific task.

### Outdated information

Ecosystem moves fast. 6-month-old advice may be stale.

## Decision framework

Use open source when:
- High volume justifies hosting
- Privacy requirements
- Customization needed
- Cost-sensitive

Use closed APIs when:
- Need cutting-edge capability
- Low volume
- No ops capacity
- Prototyping

Many teams run hybrid: closed for hard tasks, open for high-volume ones.

## Where to start

1. Try llama.cpp + GGUF quants on your laptop
2. Run a small model end-to-end
3. Evaluate on your task
4. Scale up only if needed

## Further Reading

- [TransformerArchitecture](TransformerArchitecture) — Model foundation
- [AgentPromptEngineering](AgentPromptEngineering) — Agent patterns
- [PromptCaching](PromptCaching) — Cost optimization
- [Generative AI Hub](Generative+AI+Hub) — Cluster index
