# Interface-friction findings

Distilled from the grounded-mcp tool-call logs and judge rationales.

## Tool usage (grounded-mcp)
- `retrieve_context`: 30 calls
- `search_knowledge`: 18 calls
- `read_pages`: 8 calls
- `get_page`: 3 calls
- `get_ontology`: 2 calls
- `list_stale_citations`: 1 calls

## Where model-driven tool-use underperformed the canned bundle
- `scim-group-admin-restriction`: mcp=1 < bundle=2 — The candidate answer says SCIM is not implemented at all in Wikantik, which is a different claim from the reference answer. The reference states that SCIM Group
- `kg-predicates-count`: mcp=1 < bundle=2 — ## Grading Analysis

The candidate answer correctly identifies **21 KG predicates**, which matches the reference. It also correctly identifies mappings to **SKO

## grounded-mcp answers that used NO tools
- none

## Repeated tool calls within one answer (possible loops)
- stale-citation-grading: `retrieve_context` ×4
- read-path-acl: `retrieve_context` ×3
- scim-group-admin-restriction: `retrieve_context` ×4

## Judge flagged vague or hallucinated
- chunker-heading-fidelity (grounded_mcp): The candidate answer correctly identifies the core bug: `ContentChunker` failing to force-emit the merge-forward buffer at heading boundarie
- agent-grade-runbook-frontmatter (grounded_bundle): The answer correctly identifies the three core required fields (when_to_use, steps, pitfalls) and the runbook: block requirement, matching t
- agent-grade-runbook-frontmatter (grounded_mcp): The answer correctly identifies the `runbook:` block as the required additional frontmatter and includes the three core fields from the refe
