---
date: 2026-03-14T00:00:00Z
status: active
summary: Why running your own LLM locally — even a small, limited one — teaches you
  things about AI that using cloud APIs never will, plus practical setup with Ollama
  and hardware guidance
related:
- GenerativeAiAdoptionGuide
- UnderstandingGenerativeAi
- PracticalPromptEngineering
- AcceleratingAiLearning
- AIModelTraining
tags:
- generative-ai
- local-llm
- ollama
- open-source
- learning
type: article
cluster: generative-ai
---
# Running Local LLMs

You can run a large language model on your own computer. Right now. For free. It won't be as good as Claude or GPT-4 — those models are 10-100x larger than what fits on consumer hardware. But that's not the point.

The point is that running a local model pulls back the curtain on everything. Tokens stop being an abstract billing unit and become something you watch being generated one by one. Temperature stops being a mysterious slider and becomes a parameter you tune while watching the output change. The difference between a 7-billion and 70-billion parameter model stops being a marketing number and becomes a visceral experience of capability and limitation.

If you want to truly understand generative AI — not just use it — run a model locally. It takes 15 minutes.

## What You Learn That APIs Can't Teach

### 1. Tokens Are Physical

When you run a model locally, you watch tokens appear one at a time. On a CPU, they're slow enough to read as they generate. You see the model "thinking" — making one prediction at a time, each token constrained by every token that came before it.

This visceral experience changes how you prompt. You understand why longer contexts are slower (more computation per token), why the first tokens of a response set the tone for everything after (the model is autocompleting its own previous tokens), and why "think step-by-step" works (it forces intermediate reasoning tokens that constrain the final answer).

### 2. Model Size vs. Capability Is a Spectrum

| Model Size | Example | Runs On | Capability Level |
|-----------|---------|---------|------------------|
| 1-3B parameters | Phi-3 Mini, Qwen2.5-3B | Any laptop, even without GPU | Basic tasks: simple Q&A, formatting, templates. Struggles with complex reasoning. |
| 7-8B parameters | Llama 3.1 8B, Mistral 7B, Gemma 2 9B | Laptop with 8GB+ RAM | Solid for coding help, writing drafts, summarisation. Noticeably worse than cloud models on nuanced tasks. |
| 13-14B parameters | Llama 3.1 13B, Qwen2.5-14B | 16GB+ RAM, better with GPU | Surprisingly capable. Good reasoning, good code, good writing. The sweet spot for local use. |
| 30-70B parameters | Llama 3.1 70B, Mixtral 8x7B | 32-64GB RAM or dedicated GPU (24GB+ VRAM) | Approaching cloud model quality for many tasks. Slower but genuinely useful. |

Running a 3B model and then a 13B model on the same prompt is revelatory. The small model gives a superficially fluent but substantively shallow answer. The larger model provides nuance, catches edge cases, and self-corrects. You understand intuitively what "model capability" means — not as a benchmark number but as the difference between a response that looks right and one that is right.

### 3. Temperature and Sampling Demystified

**Temperature** controls randomness in token selection:
- **Temperature 0**: Always picks the most probable next token. Deterministic, repetitive, safe.
- **Temperature 0.7**: Picks from a distribution of likely tokens. More varied, occasionally surprising.
- **Temperature 1.5**: Picks from a wide distribution. Creative but unreliable. Starts making up words at high values.

With a local model, you can run the same prompt at temperature 0, 0.5, and 1.0 and compare outputs side by side. You learn that factual tasks want low temperature (narrow the prediction to the most likely answer) and creative tasks want moderate temperature (allow for unexpected but still plausible combinations).

Cloud APIs set temperature internally. Once you've tuned it locally, you understand why some cloud responses feel robotic (low temperature) or unhinged (high temperature).

### 4. System Prompts Are Just Text

Cloud interfaces hide the system prompt — the instructions that shape the model's personality and behaviour. Locally, you write it yourself:

```
You are a helpful coding assistant. You write clean, commented Python code.
When asked a question, you provide a brief explanation followed by a code example.
You never apologise or use filler phrases like "Great question!"
```

You can experiment: remove the system prompt entirely and see the raw model. Add a system prompt that says "You are a pirate" and watch every response change. This demystifies why ChatGPT "feels" different from Claude — it's largely the system prompt, not the underlying model.

### 5. Context Windows Are Hard Limits

Locally, you hit the context window wall in real time. Feed a model a 20-page document when its context window is 4,096 tokens and it simply truncates. The model doesn't tell you it lost information — it just responds based on whatever fit. This teaches you to:
- Front-load important information in your prompts
- Summarise long documents before asking questions about them
- Understand why RAG (retrieval-augmented generation) exists — it's a workaround for finite context

### 6. Privacy Is Guaranteed

When you run a model locally, your data never leaves your machine. No API calls, no cloud storage, no terms of service. This is genuinely valuable for:
- Sensitive business documents
- Client confidential information
- Personal data you don't want on someone else's server
- Experimentation without worrying about data retention policies

### 7. The Cost Structure Becomes Obvious

Running a model locally, you understand why AI APIs cost what they cost. Inference takes real compute. A 70B model on consumer hardware generates maybe 5-10 tokens per second. Cloud providers run these on arrays of specialised hardware at 50-100+ tokens per second. The subscription fee pays for hardware you'd need $5,000-$10,000 to replicate.

This understanding makes you a smarter buyer. You know when a cloud API is good value (complex tasks requiring large models) and when it's wasteful (simple tasks a local 7B model handles fine).

## Getting Started with Ollama

Ollama is the simplest way to run local models. It handles downloading, configuring, and serving models with a single command.

### Installation

```bash
# macOS or Linux
curl -fsSL https://ollama.com/install.sh | sh

# Or on macOS via Homebrew
brew install ollama
```

### Your First Model

```bash
# Download and run Llama 3.1 8B (4.7GB download)
ollama run llama3.1

# You're now in a chat. Type a message and press Enter.
>>> What is the Rule of 72 in investing?
```

That's it. You're running an LLM locally.

### Exploring Different Models

```bash
# Try a smaller, faster model
ollama run phi3

# Try a coding-specialised model
ollama run codellama

# Try a larger, more capable model (needs 16GB+ RAM)
ollama run llama3.1:13b

# List your downloaded models
ollama list

# Remove a model
ollama rm phi3
```

### The Ollama API

Ollama exposes a local API at `http://localhost:11434`. This lets you integrate local models into scripts, notebooks, and applications:

```bash
# Simple API call
curl http://localhost:11434/api/generate -d '{
  "model": "llama3.1",
  "prompt": "Explain TCP/IP in one paragraph",
  "stream": false
}'
```

```python
# Python example
import requests

response = requests.post('http://localhost:11434/api/generate', json={
    'model': 'llama3.1',
    'prompt': 'Write a Python function to calculate compound interest',
    'stream': False
})
print(response.json()['response'])
```

This API is compatible with many tools that expect an OpenAI-compatible endpoint, making it a drop-in replacement for testing and development.

## Hardware Guide

### Minimum (Basic Experimentation)

- **RAM**: 8GB
- **Storage**: 10GB free
- **GPU**: Not required
- **Models**: 1-3B parameter models (Phi-3, Qwen2.5-3B)
- **Speed**: Slow but functional (~5-10 tokens/second on CPU)

### Recommended (Comfortable Daily Use)

- **RAM**: 16GB
- **Storage**: 30GB free
- **GPU**: Nice to have (Apple Silicon excels here; M1/M2/M3 Mac unified memory is ideal)
- **Models**: 7-13B parameter models (Llama 3.1 8B, Mistral)
- **Speed**: Usable (~15-30 tokens/second with GPU/Apple Silicon)

### Power User (Near-Cloud Quality)

- **RAM**: 32-64GB
- **GPU**: NVIDIA with 16-24GB VRAM (RTX 3090, 4090) or M2/M3 Pro/Max Mac
- **Storage**: 100GB+ free
- **Models**: 30-70B parameter models
- **Speed**: Good (~20-40 tokens/second with proper GPU)

**Best value path**: An Apple Silicon Mac with 16GB+ unified memory is the best consumer hardware for local LLMs. The unified memory architecture means RAM and VRAM are the same pool, and the Neural Engine accelerates inference. A base M2 MacBook Air runs 7B models surprisingly well.

## Beyond Chat: What to Build

Once you can run a local model, build something. The act of building teaches faster than any tutorial:

1. **A document Q&A system**: Feed your own documents to the model and ask questions. This teaches you about RAG and context management.
2. **A code review tool**: Point the model at your code and ask for reviews. This teaches you about prompt engineering for specific tasks.
3. **A writing assistant with a custom personality**: Write a system prompt that matches your preferred style. This teaches you about system prompt engineering.
4. **A translation pipeline**: Translate documents between languages. This teaches you about quality evaluation and when local models are good enough.

See [Accelerating AI Learning](AcceleratingAiLearning) for more project ideas and learning paths.

## Local vs. Cloud: When to Use Which

| Use Case | Local | Cloud | Why |
|----------|-------|-------|-----|
| Sensitive/private data | Yes | Caution | Data never leaves your machine |
| Complex reasoning | Adequate (13B+) | Better | Larger cloud models handle nuance better |
| Simple text tasks | Yes | Overkill | Local 7B handles formatting, templates, etc. fine |
| Learning and experimentation | **Yes** | Misses the point | The whole value is in seeing how it works |
| Production applications | Testing only | Yes | Cloud provides reliability, speed, and scale |
| Offline work | Yes | No | Works without internet |
| Cost-sensitive high volume | Yes | Expensive | After hardware cost, inference is free |

## Further Reading

- [Understanding Generative AI](UnderstandingGenerativeAi) — The conceptual foundation
- [Practical Prompt Engineering](PracticalPromptEngineering) — Skills that transfer directly from local to cloud
- [Accelerating AI Learning](AcceleratingAiLearning) — Project-based learning paths using local models
- [Generative AI Tools for Individuals](GenerativeAiToolsForIndividuals) — Cloud tools that complement local experimentation
- [Generative AI Adoption Guide](GenerativeAiAdoptionGuide) — Hub page
