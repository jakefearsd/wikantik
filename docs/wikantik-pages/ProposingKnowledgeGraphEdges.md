---
date: ''
runbook:
  when_to_use:
  - You are reading a page and notice an entity or relationship the graph clearly
    should know about
  - You are building a feature on top of the KG and need a missing edge to land first
  - A human operator has asked you to bulk-propose from a structured source
  inputs:
  - Source page name (source_page, the wiki page where the proposal originates)
  - 'The proposal: a type (new-node, new-edge, new-property or modify-property) and
    its data — for a new-edge, {source, target, relationship}'
  - Your confidence (0.0 to 1.0) and the reasoning, citing evidence from the page
    (both required)
  steps:
  - Read the page first — propose only edges you can defend with a quote from the
    page
  - Call /wikantik-admin-mcp/propose_knowledge with proposal_type, proposed_data,
    source_page, confidence and reasoning (needs an mcp_admin key)
  - Confirm the proposal lands by calling /wikantik-admin-mcp/list_proposals — it
    should show your entry as `pending`
  - Tell the user (or the operator) the proposal is in queue — they approve or reject
    it in the Proposals tab at /admin/knowledge-graph, or an admin agent does so with
    review_proposals
  - Track the outcome later via list_proposals — approved proposals become live in
    the graph; rejected ones stay in history, and a rejected edge cannot be proposed
    again
  pitfalls:
  - Proposing edges based on agent inference rather than page text — the graph wants
    citation-backed edges, not LLM speculation
  - Re-proposing identical edges — list_proposals first to confirm there isn't already
    a pending or approved entry; an edge that was previously rejected is refused outright,
    with the rejection reason in the error
  - Overloading reasoning with prose — a one-line quote from the source page is the
    contract
  - Treating propose_knowledge as a write to the graph — it's a queue write; an operator
    approves before the edge exists
  - Using a free-form node_type on a new-node proposal — it must match /^[a-z][a-z0-9_-]{0,30}$/
    or the call is refused
  related_tools:
  - /wikantik-admin-mcp/propose_knowledge
  - /wikantik-admin-mcp/list_proposals
  - /wikantik-admin-mcp/inspect_proposals
  - /wikantik-admin-mcp/review_proposals
  - /knowledge-mcp/get_node
  references:
  - HybridRetrieval
  - StructuralSpineDesign
  - KgInclusionPolicy
title: Proposing Knowledge Graph Edges
audience:
- agents
- humans
canonical_id: 01KQ4H83ZYTDAAMXFSCP27V7F6
cluster: agent-cookbook
type: runbook
tags:
- knowledge-graph
- mcp
- runbook
- agent-context
summary: How to use the propose_knowledge MCP tool on /wikantik-admin-mcp to suggest
  Knowledge Graph nodes and edges, and how an operator reviews them.
---

# Proposing Knowledge Graph Edges

The knowledge graph is small and curated by design. Agents and humans
both contribute proposals; an operator approves them before the graph
takes the edge. This keeps speculation out of the live graph.

## When to use this runbook

When you've observed a missing edge that has clear page-level support
and you want it landed.

## Context

`propose_knowledge` writes a proposal row to the `kg_proposals` table with
`status = pending`. Its required arguments are `proposal_type` (`new-node`,
`new-edge`, `new-property` or `modify-property`), `proposed_data`, `source_page`,
`confidence` and `reasoning`; for a `new-edge`, `proposed_data` is
`{source, target, relationship}`. `list_proposals` returns the queue (optional filters
`status`, `source_page`, `limit`, `offset`).

Operators triage in the **Proposals** tab of the admin Knowledge Graph page
(`/admin/knowledge-graph`), or an admin agent does it with `inspect_proposals`
(conflict flags and prior reviews for 1 to 50 ids) and `review_proposals`
(`approve`, `reject` or `judge`; `reject` needs a `reason`). Approving a proposal
promotes it into the graph as a human-curated entry; rejecting one keeps it in
history with its reason, and for an edge the rejection is remembered so the same
edge cannot be proposed again. Approving a proposal whose source page sits on
the Knowledge Graph exclusion list still succeeds but returns a warning; see
[KG Inclusion Policy](KgInclusionPolicy).

## Walkthrough

The frontmatter `steps` capture the canonical workflow: read,
propose, confirm, hand off, track. The "read first" step is
load-bearing — proposals without page-level support get rejected.

## Pitfalls

The frontmatter `pitfalls` are the recurring failure modes. The
"agent inference instead of page text" pitfall is the worst —
proposals from speculation rather than citation pollute the queue.
