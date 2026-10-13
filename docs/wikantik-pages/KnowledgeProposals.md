---
title: Knowledge Proposals
date: '2026-04-04'
cluster: wikantik-development
part-of:
- KnowledgeGraphCore
status: active
type: article
depends-on:
- ProvenanceModel
canonical_id: 01KQ0P44RMQK7627HQ0J02FE6K
summary: Proposal workflow for AI-suggested knowledge enrichment with human approval
  and frontmatter write-back
tags:
- development
- knowledge-graph
- ai
- workflow
---
# Knowledge Proposals

The proposal system lets extractors and AI agents suggest new nodes, edges, and property changes without writing to the Knowledge Graph directly. A proposal is screened by an asynchronous machine judge and confirmed by a human reviewer before it counts as fully trusted. Proposals live in `kg_proposals`; every machine or human verdict is recorded in `kg_proposal_reviews`.

## Proposal Types

- **new-node** — Suggest a new entity (`name`, `node_type`, `properties`)
- **new-edge** — Suggest a relationship between two nodes (`source`, `target`, `relationship`); the relationship must be one of the 21 predicates in the closed vocabulary (`related_to`, `part_of`, `requires`, `implements`, ...)
- **new-property** — Suggest adding a property to a node (`node_name`, `key`, `value`)
- **modify-property** — Suggest changing an existing property value

Only `new-node` and `new-edge` proposals are materialized into `kg_nodes` / `kg_edges`. For the two property types an approval records the verdict and nothing else.

## Lifecycle

1. **Submit** — The chunk-level and page-level entity extractors file proposals automatically (inside the wiki, or offline with `bin/kg-extract.sh`). AI agents file them with the admin-MCP `propose_knowledge` tool, supplying `proposal_type`, `proposed_data`, `source_page`, a `confidence` between 0.0 and 1.0, and `reasoning`. There is no REST endpoint for submitting a proposal.
2. **Pending** — The proposal appears in the admin panel's Proposals tab. The machine judge picks up pending proposals that have no machine verdict yet (`wikantik.kg.judge.*`: a pass every 5 minutes, 50 proposals per pass by default).
3. **Machine verdict** — The judge returns `approved`, `rejected` or `abstain`. An approval materializes the proposal at tier `machine` while it stays pending for a person; a rejection marks the proposal rejected (and, for an edge, records the triple in `kg_rejections`); an abstention leaves it pending and unmaterialized.
4. **Approve** — A reviewer approves in the Proposals tab or with the admin-MCP `review_proposals` tool. Approval marks the proposal approved, promotes the materialized rows to tier `human` (creating them first if the judge had not), clears any earlier rejection of the same triple, and — for `new-edge` — writes the relationship back to the source page's frontmatter. The rows keep `ai-inferred` provenance; see [ProvenanceModel](ProvenanceModel).
5. **Reject** — A reviewer rejects with a reason. Any rows the judge had materialized for the proposal are retracted, and a `new-edge` rejection is stored in `kg_rejections` so the same triple is not proposed again.

Reviewers can also force a judge run on chosen proposals (`review_proposals` with `verdict: judge`, or "Judge now" in the Proposals tab), and `bin/kg-judge.sh` triggers a runner pass or judges a single proposal from the command line. Proposals whose judge call keeps timing out are listed at `GET /admin/knowledge-graph/judge-timeouts`. Admin-MCP triage tools are `list_proposals` (filters plus `node_exists` and `edge_previously_rejected` conflict flags), `inspect_proposals` (bulk read with prior reviews) and `review_proposals` (bulk `approve`, `reject` or `judge`, up to 50 per call; `reject` requires a `reason`).

## Frontmatter Write-Back

When a `new-edge` proposal with a `source_page` is approved through the admin REST surface or `review_proposals`, the system adds the target to a list named after the relationship in the source page's YAML frontmatter and saves the page (author "Knowledge Admin"). For example, approving an `implements` edge from `X` to `Y` that was proposed from page `P` adds `Y` to an `implements:` list in `P`'s frontmatter. The page save does not create KG rows: frontmatter is no longer projected into KG edges (see [GraphProjector](GraphProjector)), so the write-back is a durable, human-visible record rather than a second source of edges. Judge-only approvals do not write back.

## Rejection Memory

The `kg_rejections` table stores negative knowledge — source, target and relationship triples that were rejected by the judge or a person, or removed with `delete_and_reject`. It is enforced at three points: extractors skip a rejected triple, `propose_knowledge` declines it and returns the rejection reason, and `list_proposals` / `inspect_proposals` flag a queued proposal with `edge_previously_rejected`. A later human approval of the same triple deletes the rejection.

[{Relationships}]
