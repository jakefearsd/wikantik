---
canonical_id: 01KQ4H83ZYTDAAMXFSCP27V7F6
title: Proposing Knowledge Graph Edges
type: runbook
cluster: agent-cookbook
audience: [agents, humans]
summary: How to use the `propose_knowledge` MCP tool on `/wikantik-admin-mcp` to suggest new entity / relation edges for the knowledge graph, and how operators triage the proposals via `list_proposals`.
tags:
  - knowledge-graph
  - mcp
  - runbook
  - agent-context
runbook:
  when_to_use:
    - You are reading a page and notice an entity or relationship the graph clearly should know about
    - You are building a feature on top of the KG and need a missing edge to land first
    - A human operator has asked you to bulk-propose from a structured source
  inputs:
    - Source canonical_id (the page where the proposal originates)
    - The proposed entity name or edge tuple (subject, predicate, object)
    - Optional rationale (recorded with the proposal)
  steps:
    - Read the page first — propose only edges you can defend with a quote from the page
    - Call /wikantik-admin-mcp/propose_knowledge with the source page, entity/relation, and rationale
    - Confirm the proposal lands by calling /wikantik-admin-mcp/list_proposals — it should show your entry as `pending`
    - Tell the user (or the operator) the proposal is in queue — they decide accept/reject
    - Track the outcome later via list_proposals — accepted edges become live in the graph; rejected ones stay in history
  pitfalls:
    - Proposing edges based on agent inference rather than page text — the graph wants citation-backed edges, not LLM speculation
    - Re-proposing identical edges — list_proposals first to confirm there isn't already a pending or accepted entry
    - Overloading rationale with prose — a one-line quote from the source page is the contract
    - Treating propose_knowledge as a write to the graph — it's a queue write; an operator approves before the edge exists
  related_tools:
    - /wikantik-admin-mcp/propose_knowledge
    - /wikantik-admin-mcp/list_proposals
    - /knowledge-mcp/get_node
  references:
    - HybridRetrieval
    - StructuralSpineDesign
---

# Proposing Knowledge Graph Edges

The knowledge graph is small and curated by design. Agents and humans
both contribute proposals; an operator approves them before the graph
takes the edge. This keeps speculation out of the live graph.

## When to use this runbook

When you've observed a missing edge that has clear page-level support
and you want it landed.

## Context

`propose_knowledge` writes a proposal row to `hub_proposals` (or the
equivalent KG-proposal table) with `status = pending`. `list_proposals`
returns the queue. Operators triage from the admin UI or another
script; accepted proposals become real graph edges, rejected ones
remain in history with a reason.

## Walkthrough

The frontmatter `steps` capture the canonical workflow: read,
propose, confirm, hand off, track. The "read first" step is
load-bearing — proposals without page-level support get rejected.

## Pitfalls

The frontmatter `pitfalls` are the recurring failure modes. The
"agent inference instead of page text" pitfall is the worst —
proposals from speculation rather than citation pollute the queue.
