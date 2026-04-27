---
canonical_id: 01KQ0P44TX9QMV0WBV9WXAS4N9
title: Prompt Caching
type: article
cluster: generative-ai
status: active
date: '2026-04-26'
summary: Prompt caching for LLM applications — what providers offer, how to structure
  prompts to maximize cache hits, and the dramatic cost reductions possible for
  RAG and agent workloads.
tags:
- prompt-caching
- llm
- generative-ai
- cost-optimization
related:
- PromptCachingStrategies
- AgentPromptEngineering
- OpenSourceLlmEcosystem
hubs:
- Generative AI Hub
---
# Prompt Caching

Prompt caching lets LLMs reuse computation for repeated prefixes. New requests sharing a prefix with previously-cached requests skip most of the computation, dramatically reducing cost and latency.

For applications with long static prompts (system prompts, RAG, few-shot examples), prompt caching can cut cost 90%+.

## Why caching helps

LLMs process prompts as a sequence. Each token's computation depends on all previous tokens (causal attention).

For a prompt of N tokens, the model performs O(N²) work to set up generation.

If two prompts share the first M tokens, the model could reuse the computation for those M tokens — if it has the cached state.

## What providers offer

### Anthropic (Claude)

Opt-in via `cache_control` markers. Cached prefixes:
- 90% cheaper to read
- 25% more expensive to write
- 5-minute TTL by default; 1-hour available

Mark up to 4 cache breakpoints in your prompt.

### OpenAI

Automatic for prompts ≥1024 tokens. No code changes needed.
- 50% discount on cached tokens
- ~5-10 minute TTL

### Google (Gemini)

Explicit context caching. Create cache, reuse it.
- Pay storage per hour
- Pay reduced rate for cache reads

### Open source / self-hosted

vLLM and TGI support prefix caching automatically.
- Memory-bound: cache fits while serving fits memory
- LRU eviction

Self-hosting gives the most control.

## What to cache

### System prompts

Long system prompts repeat across requests. Major win.

If your system prompt is 5K tokens and you handle 1M requests/day, that's 5B input tokens/day before user content. Caching collapses this to ~1 cache write + 1M cache reads.

### Few-shot examples

Examples in the prompt repeat. Cache them.

### RAG context

If chunks recur across queries, caching helps.

For typical RAG: each query retrieves different chunks. Caching is more useful for:
- Conversation history
- Recently retrieved chunks
- Document-specific contexts

### Long documents (multiple queries)

Asking many questions about the same document? Cache the document.

### Tool definitions

Tool schemas in agent prompts can be lengthy. Cache them.

### Conversation history

For multi-turn: cache history, append new turn.

## What NOT to cache

### Highly variable content

If every request has unique content at the start, caching doesn't help.

### Short prompts

Below provider thresholds, caching doesn't apply (or doesn't pay back the write cost).

### Privacy-sensitive content

Caching may share infrastructure across requests. Verify provider isolation guarantees.

## Structuring prompts for caching

The key insight: caching works on prefixes. Put stable content first, variable content last.

### Bad order

```
[user query]
[system prompt]
[examples]
```

System prompt repeats but isn't a prefix. No caching benefit.

### Good order

```
[system prompt]
[examples]
[tool definitions]
[user query]
```

Stable prefix; variable suffix. Cache hits on every request.

### Multiple cache levels

Some providers allow multiple cache breakpoints:

```
[system prompt]              -- cache point 1 (rarely changes)
[examples]                    -- cache point 2 (occasionally changes)
[tool definitions]            -- cache point 3 (changes per app)
[conversation history]        -- cache point 4 (grows per conversation)
[user query]                  -- not cached (always new)
```

Each cache point can be reused independently when content changes downstream.

## Anthropic-specific patterns

```python
messages = [
    {
        "role": "user",
        "content": [
            {
                "type": "text",
                "text": SYSTEM_PROMPT,
                "cache_control": {"type": "ephemeral"}
            },
            {
                "type": "text",
                "text": user_query
            }
        ]
    }
]
```

Cache markers tell Anthropic what to cache. The system measures the prefix up to each marker.

## OpenAI-specific patterns

Automatic. Just reuse the same prefix consistently.

Hit rate is typically:
- 100% for identical prompts within TTL
- High for shared prefixes
- Low for highly varied content

## Self-hosted (vLLM)

Prefix caching is enabled by default.

Considerations:
- KV cache memory pressure
- Eviction policies
- Cache hit rate visible in metrics

For high-traffic deployments, prefix caching dramatically improves throughput.

## Measuring cache effectiveness

Track:
- **Cache hit rate**: % of requests using cached content
- **Cached tokens / total tokens**: portion of work saved
- **Cost savings**: actual billing reduction
- **Latency improvement**: caches make responses faster too

If hit rate is low, prompt structure may need work.

## Cost math

Without caching:
- 5K system prompt × $3/M tokens × 1M requests = $15K/day

With Anthropic caching (90% read discount, 25% write premium):
- Cache write: 5K × $3.75/M × 1 = ~$0
- Cache reads: 5K × $0.30/M × 1M = $1.5K/day
- ~90% savings

## Common failure patterns

### Variable prefix

System prompt has dynamic content (date, user ID) at the start. No cache hits.

Fix: move dynamic content to suffix.

### Frequent invalidation

System prompt changes constantly during development. Cache write cost dominates.

Stabilize prompts before relying on caching.

### Inconsistent formatting

Whitespace differences invalidate cache. Be exactly consistent.

### TTL surprises

Cache expires at 5 min idle. Bursty traffic patterns may not benefit.

### Memory pressure (self-hosted)

KV cache competes with batch capacity. May need to tune.

### Cache coherence

Multiple deployment instances may have different caches. Each instance warms separately.

## Practical workflow

1. Identify stable prefix in your prompts (system, examples, tools)
2. Structure prompts: stable first, variable last
3. Add cache markers (Anthropic) or rely on auto (OpenAI)
4. Monitor hit rate
5. Iterate on prompt structure if hit rate is low

## Edge cases

### Prompt updates

When system prompt changes, all caches invalidate.

For high-volume apps, batch prompt updates rather than rolling out gradually.

### Rate limiting

Cached requests use less compute but still count against rate limits.

### Multi-tenant

Per-customer caching may be needed. Some providers support isolation; verify.

### Streaming

Caching applies to prefix processing. Streaming the response is independent.

## Where this is going

- Longer cache TTLs
- More automatic caching
- Better cache management for self-hosted
- Cross-request optimization beyond simple prefix caching

The cost dynamics of LLM inference favor caching. Expect more sophistication.

## Further Reading

- [PromptCachingStrategies](PromptCachingStrategies) — Strategy details
- [AgentPromptEngineering](AgentPromptEngineering) — Agent patterns
- [OpenSourceLlmEcosystem](OpenSourceLlmEcosystem) — Self-hosted options
- [Generative AI Hub](Generative+AI+Hub) — Cluster index
