---
type: article
cluster: technology
status: active
summary: The evolution of large language models from GPT-3 through modern frontier
  models — scaling laws, RLHF, multimodal capabilities, the open-weight movement,
  and what it means for practitioners
date: 2026-03-17T00:00:00Z
tags:
- ai
- llm
- deep-learning
- transformers
- nlp
- technology
related:
- ArtificialIntelligence
- MachineLearning
- TheFutureOfMachineLearning
- GenerativeAiAdoptionGuide
- RunningLocalLlms
- DistributedComputingEvolution
---
# LLMs Since 2020

The story of large language models (LLMs) from 2020 to the present is a story of scaling, alignment, and democratization. In five years, LLMs went from a research curiosity to infrastructure — embedded in search engines, code editors, customer support systems, and scientific workflows. This article traces that arc.

For the broader context of AI and [machine learning](MachineLearning) fundamentals, see [Artificial Intelligence](ArtificialIntelligence).

## The GPT-3 Moment (2020)

OpenAI's GPT-3, released in June 2020, was a 175 billion parameter autoregressive language model trained on a large corpus of internet text. It was not the first large language model — GPT-2 (1.5B parameters, 2019) and Turing-NLG (17B parameters, 2020) preceded it — but GPT-3 crossed a qualitative threshold. It could write essays, generate code, translate languages, answer questions, and perform tasks it was never explicitly trained for through few-shot prompting: provide a few examples in the prompt, and the model generalizes.

GPT-3 demonstrated three things that shaped everything that followed:

1. **Scaling works.** More parameters and more data produced qualitatively better behavior, not just quantitatively better benchmark scores.
2. **Emergent abilities appear at scale.** Capabilities like arithmetic, code generation, and chain-of-thought reasoning appeared in larger models that were absent in smaller ones.
3. **Prompting is programming.** The way you frame a request to the model matters as much as the model itself. This spawned the field of prompt engineering.

## Scaling Laws and the Chinchilla Insight (2020–2022)

Kaplan et al. at OpenAI published scaling laws in January 2020 showing that model performance improves as a smooth power law with model size, dataset size, and compute. This gave labs a roadmap: spend more compute, get better models.

DeepMind's Chinchilla paper (2022) refined this by showing that most models were over-parameterized and under-trained. Chinchilla, a 70B parameter model trained on 1.4 trillion tokens, matched the performance of the 280B parameter Gopher while using the same compute budget. The implication was clear: you should scale data and parameters together, roughly in proportion.

This insight shifted the industry. Labs began investing as heavily in data quality and quantity as in model size.

## RLHF and the Alignment Revolution (2022–2023)

The raw capabilities of base language models are impressive but unreliable. A base model will happily generate harmful content, confidently state falsehoods, or produce incoherent rambling. The breakthrough that made LLMs commercially viable was Reinforcement Learning from Human Feedback (RLHF).

The RLHF pipeline, refined by Anthropic and OpenAI, works in three stages:

1. **Supervised fine-tuning (SFT):** Train the base model on high-quality examples of helpful, harmless responses.
2. **Reward modeling:** Human raters compare pairs of model outputs, and a reward model learns to predict which response humans prefer.
3. **Reinforcement learning:** The language model is fine-tuned to maximize the reward model's score using Proximal Policy Optimization (PPO) or similar algorithms.

InstructGPT (January 2022) demonstrated that RLHF could make a smaller model preferred by humans over a much larger base model. ChatGPT (November 2022) brought this to the public. It reached 100 million users in two months — the fastest consumer product adoption in history.

Anthropic's Constitutional AI (CAI) extended this approach by using a set of written principles (a "constitution") to guide the model's behavior, reducing reliance on large-scale human rating while maintaining alignment.

## The Frontier Model Race (2023–2025)

### GPT-4 and Multimodal Models

GPT-4 (March 2023) represented a step change in reasoning, factuality, and instruction-following. It was also natively multimodal — capable of processing images alongside text. GPT-4 scored in the 90th percentile on the bar exam and demonstrated strong performance across academic benchmarks that previous models struggled with.

### Claude and Safety-First Design

Anthropic's Claude models (Claude 1, 2, 3, 3.5, and 4 families) emphasized long-context understanding, nuanced instruction-following, and safety. Claude 3 (2024) introduced a tiered model family — Haiku, Sonnet, and Opus — at different capability/cost tradeoffs. Claude's extended context windows (up to 200K tokens) enabled new use cases: analyzing entire codebases, processing long legal documents, and maintaining coherent conversations over extended interactions.

### Google's Gemini

Google's Gemini models (2023–2025) were designed as natively multimodal from the ground up, processing text, images, audio, and video within a unified architecture. Gemini Ultra competed at the frontier, while Gemini Nano targeted on-device deployment.

### The Open-Weight Movement

Meta's release of LLaMA (February 2023) and its successors — LLaMA 2, LLaMA 3, and LLaMA 4 — democratized access to high-capability models. Mistral AI (founded 2023, Paris) released efficient open-weight models that punched above their parameter count. DeepSeek (China) pushed open-weight model quality further with DeepSeek-V2 and R1.

The open-weight movement changed the economics of AI:

- Researchers could study frontier-class architectures directly
- Companies could deploy models on their own infrastructure, maintaining data sovereignty
- Fine-tuning for specific domains became accessible to small teams
- [Running models locally](RunningLocalLlms) became practical on consumer hardware

## Key Technical Advances

### Longer Context Windows

Early GPT-3 had a 2,048 token context window. By 2025, frontier models routinely handle 128K–200K tokens, and some experimental systems process over 1 million tokens. Techniques enabling this include:

- **Rotary Position Embeddings (RoPE):** Better extrapolation to unseen sequence lengths
- **Flash Attention:** Memory-efficient exact attention computation
- **Ring Attention:** Distributing attention computation across devices for very long sequences
- **Sliding window attention:** Processing long sequences in overlapping chunks

### Mixture of Experts (MoE)

MoE architectures activate only a subset of model parameters for each input token, dramatically reducing inference cost. Mixtral 8x7B (Mistral, 2023) demonstrated that a 47B total parameter MoE model could match dense 70B models while being much cheaper to run. GPT-4 is widely believed to use an MoE architecture.

### Reasoning and Chain-of-Thought

Models improved significantly at multi-step reasoning through:

- **Chain-of-thought prompting:** Encouraging the model to "think step by step" before answering
- **Tool use:** Models learned to call external tools — calculators, code interpreters, search engines — to compensate for their weaknesses
- **Reasoning-specialized models:** OpenAI's o1 (2024) and o3 series, and Anthropic's Claude with extended thinking, represented a new paradigm where models spend variable compute at inference time on harder problems

### Inference Optimization

Making large models practical for deployment required significant optimization:

- **Quantization:** Reducing model weights from 16-bit to 8-bit, 4-bit, or even lower precision with minimal quality loss
- **Speculative decoding:** Using a small draft model to predict tokens that the large model then verifies in parallel
- **KV-cache optimization:** Reducing memory requirements for storing attention states during generation
- **Distillation:** Training smaller, faster models to mimic larger ones

## The Current Landscape (2025–2026)

The LLM landscape in 2025–2026 is characterized by:

| Trend | Description |
|-------|-------------|
| **Commoditization of capability** | Tasks that required frontier models in 2023 can now be handled by smaller, cheaper models |
| **Specialization** | Domain-specific models for code, medicine, law, and science outperform general-purpose models in their domains |
| **Multimodal by default** | Text-only models are the exception; most new models process text, images, audio, and video |
| **Agentic workflows** | Models that can plan, use tools, and execute multi-step tasks autonomously |
| **On-device deployment** | Small but capable models running on phones, laptops, and edge devices |
| **Safety as table stakes** | RLHF, constitutional AI, and red-teaming are standard practice, not differentiators |

## What LLMs Cannot Do

Despite remarkable progress, LLMs have fundamental limitations:

- **They do not have persistent memory** across conversations (without external systems)
- **They hallucinate** — generating plausible but false information, especially on niche topics
- **They lack grounding** in physical reality; their knowledge comes from text, not experience
- **They struggle with precise computation** — arithmetic, counting, and formal logic remain weak spots
- **They have a knowledge cutoff** — they don't know about events after their training data ends
- **They cannot verify their own outputs** — they lack the metacognitive ability to reliably know when they're wrong

Understanding these limitations is essential for using LLMs effectively. See [Practical Prompt Engineering](PracticalPromptEngineering) for strategies that work within these constraints.

## Timeline

| Date | Milestone |
|------|-----------|
| Jun 2020 | GPT-3 (175B parameters) released |
| Jan 2022 | InstructGPT demonstrates RLHF effectiveness |
| Nov 2022 | ChatGPT launches, reaches 100M users in 2 months |
| Feb 2023 | Meta releases LLaMA, sparking open-weight movement |
| Mar 2023 | GPT-4 released (multimodal, strong reasoning) |
| Jul 2023 | LLaMA 2 released under permissive license |
| Dec 2023 | Mistral releases Mixtral 8x7B (MoE architecture) |
| Feb 2024 | Google launches Gemini 1.5 with 1M token context |
| Mar 2024 | Claude 3 family (Haiku, Sonnet, Opus) released |
| Sep 2024 | OpenAI releases o1 (reasoning-focused model) |
| Apr 2025 | LLaMA 4 released; Claude 4 family launched |

## Further Reading

- [Artificial Intelligence](ArtificialIntelligence) — Broader AI overview
- [Machine Learning](MachineLearning) — ML fundamentals and algorithms
- [The Future of Machine Learning](TheFutureOfMachineLearning) — Where the field is headed
- [Generative AI Adoption Guide](GenerativeAiAdoptionGuide) — Practical guide for using these models
- [Running Local LLMs](RunningLocalLlms) — Running models on your own hardware
