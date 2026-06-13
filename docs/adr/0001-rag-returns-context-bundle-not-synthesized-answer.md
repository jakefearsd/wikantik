# RAG-as-a-Service returns an assembled context bundle, not a synthesized answer

RAG-as-a-Service (the wiki-owned retrieval-and-assembly layer) stops at a ranked,
de-duplicated, citation-bearing **context bundle** and never invokes a generation LLM to
compose prose answers; the calling agent's own model writes the answer. We chose this over
wiki-side answer synthesis because "solid" is defined by *verifiable grounding* — an
assembly property that is deterministic, testable, and cacheable — rather than generated
prose, which is neither; because it keeps a generation-LLM dependency (and its latency,
cost, and hallucination / prompt-injection surface) out of the wiki's request path and so
protects the mature Retrieval-as-a-tool baseline; and because our consumers are agents that
already bring capable LLMs, making wiki-side synthesis redundant work and a second
hallucination surface.

A turnkey "answer + citations" endpoint may be added later as an optional,
clearly-separated surface *if* a consumer that cannot bring its own LLM appears. It will
not be the core of RAG-as-a-Service.
