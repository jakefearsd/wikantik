---
date: '2026-05-24'
summary: Tactical guide to LLM fine-tuning, focusing on QLoRA hyperparameters, data
  diversity requirements, and avoiding the "catastrophic forgetting" trap in production.
cluster: agentic-ai
auto-generated: false
canonical_id: 01KQ12YDVGV7J9XMMSMXGZYZNE
type: article
title: LLM Fine-Tuning
tags:
- llm
- fine-tuning
- lora
- qlora
- post-training
- pytorch
status: active
hubs:
- FineTuningLargeLanguageModels Hub
---
# LLM Fine-Tuning

Fine-tuning is the correct solution for format adherence and style injection, but it is almost always the wrong solution for teaching a model new facts. For facts, use RAG; for behavior, use fine-tuning.

## The Hierarchy of Model Improvement

1. **Prompt Engineering:** Costs $0.00. Solves 80% of issues.
2. **Few-Shot Prompting:** Costs tokens. Solves 10% more.
3. **RAG:** Costs infra + tokens. Solves factual drift.
4. **Fine-Tuning:** Costs compute + engineering time. Required only for 99.9% reliability on complex schemas or deep domain vernacular.

## Parameter-Efficient Fine-Tuning (PEFT)

Full fine-tuning (updating all weights) is prohibitively expensive for most teams. **LoRA (Low-Rank Adaptation)** and its quantized sibling **QLoRA** are the production standards. They freeze the base model and train small adapter matrices ($A$ and $B$) that are injected into the attention layers.

| Technique | VRAM (7B model) | Accuracy Impact | Training Time |
|---|---|---|---|
| **Full Fine-Tuning** | >160 GB | Baseline | Slow |
| **LoRA** | ~24-28 GB | Negligible | Fast |
| **QLoRA (4-bit)** | ~12-16 GB | 1-2% drop | Moderate |

### QLoRA Hyperparameter Recipe
For a Llama 3.1 8B or Mistral 7B model on a single 24GB A10G or 3090/4090:

```python
# Reference config for Hugging Face PEFT/TRL
config = LoraConfig(
    r=16,              # Rank: Higher = more capacity, but higher VRAM
    lora_alpha=32,     # Scaling factor: typically 2 * r
    target_modules=[   # Target ALL linear layers for best results
        "q_proj", "k_proj", "v_proj", "o_proj", 
        "gate_proj", "up_proj", "down_proj"
    ],
    lora_dropout=0.05,
    bias="none",
    task_type="CAUSAL_LM"
)
```

## Data: The Hardest Part

Fine-tuning is extremely sensitive to data quality. **500 high-quality, human-curated examples** will outperform 50,000 synthetic examples every time.

- **Diversity is Mandatory:** If your dataset uses the same prompt template 90% of the time, the model will overfit to the template, not the task. Mix in 10-20% general instruction data (e.g., SlimOrca or ShareGPT) to prevent "catastrophic forgetting"—where the model loses general reasoning ability.
- **Negatives are Critical:** Include examples where the correct answer is "I don't know" or a refusal. Without them, the model will learn to hallucinate an answer for any out-of-distribution input.

## Evaluation and Convergence

Never trust training loss. A model can have zero training loss but fail in production because it simply memorized the dataset (overfitting).

1. **Eval Loss:** Track loss on a held-out set (10% of data). If eval loss starts rising while training loss falls, stop training immediately.
2. **Benchmark Regression:** After fine-tuning, run the model through a general benchmark (e.g., MMLU). A drop of >5% suggests you've over-fitted and destroyed the model's base intelligence.
3. **Format Validation:** If fine-tuning for JSON, run 1000 test cases through a schema validator. Aim for >99.5% validity.

## Serving Fine-Tuned Models
Do not merge the LoRA weights into the base model if you have multiple tasks. Serve the base model with **vLLM or LoRAX**, which allow you to swap adapters dynamically at request time with negligible latency overhead.

## Further Reading
- [[ModelQuantization]] — Deep dive into NF4 and 4-bit loading.
- [[LlmEvaluationMetrics]] — How to build a custom eval harness.
- [[RagImplementationPatterns]] — Why you should probably do RAG instead.
