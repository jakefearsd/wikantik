# LLM model selection is a cost-governed, eval-scored, swappable axis — never a default to premium

Every LLM-using component in the pipeline — entity/relation extraction, embeddings, and the
optional semantic-contradiction check on citations — treats *model choice* as a **swappable
axis scored by the evaluation harness, with cost and latency as explicit dimensions** — not a
fixed vendor bet.

We **favor local models** (the existing Ollama inference host) and **low-cost API models**,
and reserve premium models (e.g. Claude) for **targeted, high-value slices** where the harness
shows they pay for themselves. The existing `ClaudeEntityExtractor` is *one available backend*,
not the destination; "better extractor" means **fit-for-purpose on the value/cost frontier**
(gemma4-e4b was simply too small for relation extraction), not "Claude for everything."

Rationale: the operator needs degrees of freedom to experiment broadly and to control cost.
Hardcoding a premium vendor — or *any* single model — removes both. Model upgrades ship the
same way every other change does: by measured lift on the harness, now weighed against cost.
