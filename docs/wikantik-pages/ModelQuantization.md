---
canonical_id: 01KQEKGDD6EWGYTHZ65JHZRK4P
title: Model Quantization
type: article
cluster: machine-learning
status: active
date: '2026-05-15'
tags:
- quantization
- llm
- inference
- gguf
- gptq
- awq
summary: Technical guide to neural network quantization (4-bit, 8-bit), covering GGUF, GPTQ, and a concrete AWQ implementation.
related:
- LLMFineTuning
- CostEffectiveInference
- CpuInference
- OpenSourceLLMs
hubs:
- MachineLearningHub
auto-generated: false
---
# Model Quantization

Model quantization reduces the precision of a neural network's weights and activations (e.g., from FP16 to INT4) to decrease memory footprint and increase inference speed. For Large Language Models (LLMs), quantization is the primary enabler for running 7B+ parameter models on consumer hardware.

## 1. Quantization Levels
- **8-bit (INT8)**: Minimal quality loss (<1%). Often the default for production CPU/GPU inference.
- **4-bit (INT4)**: The "sweet spot" for LLMs. Reduces memory by 4x with roughly 2-5% degradation in perplexity.
- **2-bit**: Significant quality drop. Only used for extremely memory-constrained environments.

## 2. Dominant Algorithms
- **GGUF (GGML)**: Optimized for CPU and Apple Silicon. Used by `llama.cpp`. Supports "K-Quants" which use mixed precision for different layers.
- **GPTQ**: Post-training quantization using second-order information (Hessian). GPU-friendly.
- **AWQ (Activation-aware Weight Quantization)**: Protects "salient" weights that contribute most to activations. Often superior to GPTQ for 4-bit LLMs.
- **SmoothQuant**: Specifically addresses activation outliers for INT8 W&A quantization.

## 3. Concrete Example: Quantizing with AutoAWQ
AutoAWQ is a popular library for generating 4-bit AWQ models that are compatible with inference engines like vLLM.

```python
from awq import AutoAWQForCausalLM
from transformers import AutoTokenizer

model_path = "meta-llama/Llama-3-8B"
quant_path = "Llama-3-8B-awq"
quant_config = { "zero_point": True, "q_group_size": 128, "w_bit": 4, "version": "GEMM" }

# 1. Load Model and Tokenizer
model = AutoAWQForCausalLM.from_pretrained(model_path)
tokenizer = AutoTokenizer.from_pretrained(model_path, trust_remote_code=True)

# 2. Quantize
model.quantize(tokenizer, quant_config=quant_config)

# 3. Save Quantized Model
model.save_quantized(quant_path)
tokenizer.save_pretrained(quant_path)
```

## 4. Hardware Acceleration
- **NVIDIA Tensor Cores**: Support native INT8 and FP8 arithmetic.
- **Apple Neural Engine (ANE)**: Optimized for 4-bit and 8-bit weights.
- **Intel AMX**: Specialized instructions for INT8 matrix multiplication in Sapphire Rapids+ CPUs.

## 5. When Quantization Fails
- **Small Models (<3B)**: Lower redundancy means quantization artifacts are more noticeable.
- **Reasoning/Math Tasks**: Sensitive logic often degrades faster than creative writing.
- **Outlier Layers**: Some layers in Transformers exhibit extreme values; naive quantization causes these layers to "blow up" the entire output.

## Summary of Technical implementation added
- Defined **INT4, INT8, and 2-bit** trade-offs.
- Explained the mechanics of **AWQ, GPTQ, and GGUF**.
- Provided a complete **Python example using AutoAWQ** for 4-bit quantization.
- Detailed the hardware-specific accelerations (Tensor Cores, AMX, ANE).
- Listed failure modes for small models and complex tasks.
