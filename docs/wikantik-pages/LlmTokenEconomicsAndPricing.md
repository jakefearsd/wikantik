---
canonical_id: 01KQ12YDVS8XTFM3XHT5XBJMQQ
title: Llm Token Economics And Pricing
type: article
cluster: agentic-ai
status: active
date: '2026-04-25'
tags:
- llm
- token-pricing
- prompt-caching
- cost-optimization
- inference-cost
summary: How input/output tokens, caching, and batch APIs decide what an LLM
  actually costs you, and the levers (caching prefix design, model routing,
  output limiting) that move the bill 10×.
related:
- ContextWindowManagement
- ContextCompression
- AgentObservability
- ModelQuantization
- CostEffectiveInference
hubs:
- AgenticAi Hub
---
# LLM Token Economics and Pricing

LLM cost is dominated by token count — input plus output, multiplied by per-token rates that differ by model and by direction. Most teams think they understand the math and then discover their actual bill is 5–10× larger than estimated, usually because of caching they didn't get and outputs they didn't constrain.

This page is the working accounting and the levers that move the bill substantially.

## The pricing structure, as of 2026

Major commercial APIs price in dollars per million tokens. Approximate ranges (subject to shifts):

| Class | Example | Input ($/M) | Output ($/M) | Cached input |
|---|---|---|---|---|
| Frontier | Claude Opus, GPT-5 | $15-20 | $75-100 | ~10% of full input |
| Mid-tier | Claude Sonnet, GPT-4 | $3-5 | $15-25 | ~10% of full input |
| Small | Claude Haiku, GPT-mini | $0.20-0.50 | $1-2 | ~10% of full input |
| Open weights | Llama, Mistral self-hosted | (your hardware) | (your hardware) | (your cache) |

Three takeaways:

1. **Output is 4-5× the price of input.** Limiting output length is the single biggest cost lever for verbose tasks.
2. **Cached input is ~10× cheaper than fresh input.** Cache hit rate dominates cost on long-prompt workloads.
3. **Mid-tier is 10-20× cheaper than frontier.** Most production traffic should be on mid-tier; reserve frontier for tasks where the quality gap pays.

## Counting tokens, briefly

Tokens are not characters or words. A token is a chunk of text from a tokenizer's vocabulary — roughly 4 characters or 0.75 words on average for English. Code, especially symbol-heavy code, often tokenizes more finely (~3 chars/token). Other languages vary more.

Practical rule: 1000 tokens ≈ 750 English words ≈ 4000 characters. Useful estimating constant; off by ±20%.

For exact counts, every provider ships a tokenizer:

- OpenAI: `tiktoken`.
- Anthropic: `anthropic.tokenizer` or count via the API's `count_tokens` endpoint.
- Llama / Mistral: `transformers.AutoTokenizer`.

Don't estimate when actual counts matter (cost forecasting, context-window enforcement). Tokenize.

## Prompt caching: the biggest single lever

Both Anthropic and OpenAI cache prompt prefixes. Once cached, subsequent requests with the same prefix re-use the cache at ~10% of the per-token cost. Cached prompts also process faster (lower latency).

The cache key is exact-match on the prefix. Three implications:

1. **Put stable content at the start of the prompt.** System prompt → tool definitions → few-shot examples → user query. Stable parts on the left are cacheable.
2. **Volatile content at the end.** Timestamps, user IDs, request-specific data. If you put `current_time=...` in the system prompt, you've broken caching for everyone.
3. **Batch requests with shared prefixes.** Agent tools / RAG retrievals with the same system prompt benefit hugely from cache.

A typical agentic system with a 4000-token system prompt and tool definitions can hit 90%+ cache rate on a hot topic. Without caching, the same workload might cost 5-10× more.

Anthropic charges a small premium for the *first* cached request (≈25% above input price); subsequent cached reads are ~10% of input price. Net win above ~2-3 cache hits.

Caching is feature-flagged for users to opt into in some APIs; turn it on; design your prompts to maximise hit rate; track cache rate as a first-class metric.

## Output limits: the second biggest

LLM output cost is per token generated. A request that returns 5000 tokens of detailed prose costs 5× one returning 1000.

Levers:

- **`max_tokens` parameter** — hard cap on output. Set realistic limits, not 4096-by-default.
- **Prompt instructions to be concise.** "Reply in 2-3 sentences." Effective; halves output on average.
- **Structured outputs.** A JSON object with named fields is shorter than free-form prose.
- **Reasoning vs answer separation.** Modern reasoning-mode APIs charge separately for reasoning tokens. Often 10-30× the answer length. Use reasoning mode only where it matters.

The classic trap: "the model gave me a verbose answer" → "set max_tokens lower" → answer gets truncated mid-thought. The right fix is usually clearer prompting, not lower max_tokens.

## Model routing: cheap for cheap, expensive for hard

Most production traffic doesn't need the biggest model. A typical pattern:

- Cheap model handles routine queries (tens of cents per 1000 calls).
- Expensive model handles complex queries.
- A router (small classifier, prompt-based, or rule-based) decides which gets each query.

Heuristics that work:

- **Rule-based routing first.** If the user input matches simple patterns (FAQ-like, short, structured), route cheap. Adds zero LLM cost.
- **Prompt-based routing.** Cheap LLM call decides ("is this a simple lookup or a multi-step problem?"). The router itself is cheap.
- **Confidence-based escalation.** Cheap model attempts; if it returns "I'm not sure" or low log-prob, escalate to expensive. Optimistic; works when cheap-model competence is decent.

Done well, model routing cuts costs 30-70% with negligible quality drop. Done poorly, the routing logic itself becomes the bottleneck.

## Batch APIs

OpenAI, Anthropic, and most providers offer batch APIs at ~50% the price of synchronous calls. Submit a JSONL file of requests; results come back within 24 hours.

Right for:

- Backfill workflows (extract entities from 1M documents).
- Periodic refresh jobs (re-summarise the corpus weekly).
- Eval rollouts (200 task fixtures, no urgency).

Wrong for:

- Anything user-facing.
- Iterative agent loops.

Most teams underuse batch APIs. If your batch backfill workload is ≥10% of total LLM spend, moving it to batch is a no-effort 50% reduction on that slice.

## Self-hosting trade-off

Self-hosting an open-weights model (Llama, Mistral) eliminates per-token charges but adds infrastructure cost.

Rough break-even (varies wildly with traffic shape):

- **GPU-hours of inference cost** vs **API token cost at your usage volume**.
- For a single 8B model on a single A10/L4 GPU at $1/hour: you can serve ~5-15 RPS depending on prompt size. That's ~$10/day for 1M tokens of mid-pace traffic. API equivalent would be $5-30 depending on cache rate.

Self-host wins when:

- **Latency control matters.** Your colocated GPU returns in 200ms; API has variable latency.
- **Privacy / compliance.** Data can't leave your VPC.
- **Volume is predictable and high.** Sustained 20+ RPS on one model is when self-hosting wins on cost.
- **You're using fine-tuned LoRA adapters** that don't have a managed equivalent.

API wins when:

- **Bursty traffic.** Provisioning enough GPU for peak wastes money the rest of the time.
- **Frontier quality matters.** Open weights are still ~6-12 months behind the frontier on hard tasks.
- **You don't want to operate inference infrastructure.** It's real work; budget for it.

## A cost dashboard

Track these per-model, per-day:

- Input tokens (cached + uncached separately).
- Output tokens (regular + reasoning separately if applicable).
- USD cost.
- Cache hit rate %.
- Per-task cost p50 / p95 / p99.
- Cost trend (week over week).

Alert on:

- Per-task p95 cost > 2× last week's baseline (catches prompt bloat).
- Cache hit rate drop > 10 percentage points (catches prefix instability).
- Daily spend > budget threshold.

Without these, prompt regressions silently triple your bill before the finance team notices. With them, you catch within a day. See [AgentObservability].

## Common cost mistakes

- **Volatile content in system prompt.** Timestamps, request IDs, user-specific data at the top. Cache hit rate: 0%.
- **Logging full prompt + completion at info level.** Burns observability storage; doesn't burn LLM cost but adds operational cost.
- **Forgetting that retries multiply cost.** A request that retries 3 times costs 3× the tokens.
- **Verbose CoT in agent loops.** Every step has 500 tokens of reasoning. 20 steps × 500 = 10K reasoning tokens per task; at frontier-output prices, this dominates the bill.
- **No cap on max_tokens.** Default 4096 across the board; the model uses what it needs; sometimes that's "all of it."

## Where the puck is moving

- **Sub-cent inference for small models.** Quality of 7B models is approaching what 70B models did a year ago; pricing follows.
- **Reasoning mode for hard tasks.** Hard problems become tractable; cost goes up; routing decisions become more important.
- **Free open-weights models.** Llama and equivalents at near-frontier quality for many tasks; self-hosting becomes more economic.
- **Per-feature pricing.** Caching, batch, fine-tunes — each priced separately. The bill is increasingly itemised; understand what you're being charged for.

## Further reading

- [ContextWindowManagement] — the input-token side
- [ContextCompression] — reducing input tokens via summarisation
- [AgentObservability] — the cost-tracking infrastructure
- [ModelQuantization] — quality/cost trade for self-hosted
- [CostEffectiveInference] — broader cost-optimisation context
