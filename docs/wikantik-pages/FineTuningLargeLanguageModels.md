---
auto-generated: false
type: article
status: active
date: 2025-05-15T00:00:00Z
cluster: machine-learning
title: Fine-Tuning Large Language Models
hubs:
- FineTuningLargeLanguageModels Hub
tags:
- generative-ai
- llm
- fine-tuning
- lora
- machine-learning
summary: Technical guide to LLM fine-tuning strategies. Covers Full Fine-Tuning, LoRA/QLoRA,
  and instruction dataset curation.
canonical_id: 01KQ0P44QDNYW8AESQS6MKEEZJ
---

# Fine Tuning Large Language Models: Domain Adaptation

Fine-tuning is the process of specializing a pre-trained foundation model on a specific task or domain using a smaller, curated dataset.

## 1. Fine-Tuning Modalities

*   **Full Fine-Tuning (FFT):** Updating all model parameters.
    *   *Risk:* **Catastrophic Forgetting**, where the model loses its general reasoning capabilities in favor of the new data.
    *   *Cost:* Extremely high VRAM requirements (e.g., 8x A100 GPUs for a 70B model).
*   **PEFT (Parameter-Efficient Fine-Tuning):** Updating only a tiny fraction (<1%) of the parameters. The industry standard for domain adaptation.

## 2. LoRA: Low-Rank Adaptation

LoRA injects small, trainable "adapter" matrices into the transformer layers while keeping the original weights frozen.
*   **Mechanism:** $W_{new} = W_{frozen} + (A \times B)$, where $A$ and $B$ are low-rank matrices.
*   **Rank ($r$):** A hyperparameter (usually 8, 16, or 32). Lower rank reduces memory but limits expressive power.
*   **Concrete Benefit:** Fine-tuning a Llama-3-8B model with LoRA ($r=8$) requires only ~800MB of additional parameters, allowing the process to run on a single consumer GPU (24GB VRAM).

## 3. QLoRA: Quantized LoRA

QLoRA takes PEFT further by quantizing the base model to 4-bit precision (NF4) while maintaining 16-bit precision for the adapters.
*   **Concrete Efficiency:** This allows a **70B parameter model** to be fine-tuned on a single 48GB A6000 GPU, a task that previously required a server cluster.

## 4. Dataset Curation and RLHF

The quality of the fine-tuning data is more critical than the algorithm.
*   **Instruction Tuning:** Formatting data as (Instruction, Input, Response) triples.
*   **SFT (Supervised Fine-Tuning):** The first step, teaching the model the "style" of the response.
*   **RLHF (Reinforcement Learning from Human Feedback):** Aligning the model with human preferences (Helpfulness, Honesty, Harmlessness) using PPO or DPO (Direct Preference Optimization).

---
**See Also:**
- [Context Window Management](ContextWindowManagement) — Managing the inference-time context.
- [Embeddings In Gen AI](EmbeddingsInGenAI) — Understanding the base representation.
- [Knowledge Extraction From Text](KnowledgeExtractionFromText) — Building fine-tuning datasets.
