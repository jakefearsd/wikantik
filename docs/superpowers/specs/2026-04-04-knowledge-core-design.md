# Knowledge Core Design

> **Status:** Draft
> **Date:** 2026-04-04
> **Scope:** Self-describing knowledge graph, consumption MCP endpoint, proposal/review system, knowledge administration UI

## Problem Statement

Wikantik has a mature content layer (Markdown with YAML frontmatter, Lucene search, versioning) and a mature AI integration layer (47-tool authoring MCP). What it lacks is a **semantic reasoning layer** — the ability to represent, traverse, and query relationships between concepts, decisions, processes, and domain entities in a way that both humans and AI agents can exploit.

The goal is to make Wikantik a **knowledge core** for small and medium enterprises, with a primary use case of grounding GenAI coding agents in authoritative domain knowledge. When an AI coding agent (Claude Code, Cursor, Copilot) needs to understand the business domain, architectural constraints, or system dependencies, it queries the wiki's knowledge graph and gets accurate, company-specific context.

A secondary use case is encoding organizational decisions (ADRs, architectural shifts, policy changes) so that they propagate into agent behavior. Example: a company shifts to multi-region cloud deployment; that decision gets encoded in the knowledge graph, and coding agents automatically align with it.

## Design Principles

1. **Self-describing.** Agents discover the knowledge structure dynamically, just as humans would. No hardcoded domain assumptions, no fixed query categories. The system works regardless of what industry or domain the SME operates in.

2. **Schema-free metadata.** Node types, relationship types, and property keys emerge from content. A healthcare company's graph looks different from a fintech company's, but the query surface is the same.

3. **Provenance is first-class.** Every node and edge carries its origin: human-authored, AI-inferred, or AI-reviewed. Consuming agents can filter by confidence level.

4. **The wiki has no built-in AI.** All intelligence lives in external agents. The wiki is the knowledge authority and review gateway — it receives, stores, presents, and executes knowledge proposals, but never calls an LLM itself.

5. **Frontmatter is the source of truth for reviewed knowledge.** All confirmed knowledge (whether human-originated or AI-proposed-and-approved) lives in page frontmatter. The graph is a derived, queryable projection of that content plus unreviewed proposals.

6. **Separation of concerns.** The consumption MCP (read-only, for coding agents) is completely separate from the authoring MCP (read-write, for content creation). Different audiences, different design constraints, different tool surfaces.

## Architecture Overview

```
Human writes page --> Frontmatter parsed --> Graph Projector --> Knowledge Graph (PG)
                                                                       ^
External agent reads content --> Proposes enrichment --> kg_proposals   |
                                                             |         |
                                              Human reviews & approves--+
                                                             |
                                              Frontmatter write-back
                                                             |
                                              Coding agents query via
                                              Consumption MCP
```

### Components

1. **Knowledge Graph (PostgreSQL)** — Property graph stored in relational tables with JSONB for flexible properties.
2. **Graph Projector** — WikiEvent listener that synchronizes frontmatter changes to the graph on every page save.
3. **Consumption MCP (`wikantik-knowledge`)** — New module with a dedicated MCP endpoint. Read-only, self-describing, 5 tools.
4. **Proposal System** — Tools on the authoring MCP for external agents to submit knowledge proposals. Review workflow for human approval/rejection.
5. **Knowledge Administration UI** — React-based admin panel for proposal review, graph exploration, and manual curation.
6. **Knowledge Admin Role** — New `knowledge-admin` role gating access to the knowledge administration capabilities.

## Component 1: Knowledge Graph Data Model

PostgreSQL property graph using four tables.

### `kg_nodes`

| Column | Type | Purpose |
|--------|------|---------|
| `id` | UUID | Primary key |
| `name` | VARCHAR | Entity name (e.g., "Order", "PaymentGateway") |
| `node_type` | VARCHAR | Dynamic, from frontmatter `type` field (e.g., "domain-model", "adr", "service") |
| `source_page` | VARCHAR | Wiki page this node was derived from (nullable for stub nodes) |
| `provenance` | VARCHAR | `human-authored`, `ai-inferred`, `ai-reviewed` |
| `properties` | JSONB | All other metadata — fully flexible, schema-free |
| `created` | TIMESTAMP | |
| `modified` | TIMESTAMP | |

Unique constraint on `name`. One node per entity, enriched over time. Stub nodes (no `source_page`) are created when a page references an entity that doesn't have its own page yet.

### `kg_edges`

| Column | Type | Purpose |
|--------|------|---------|
| `id` | UUID | Primary key |
| `source_id` | UUID FK | From node |
| `target_id` | UUID FK | To node |
| `relationship_type` | VARCHAR | Dynamic, derived from frontmatter key name (e.g., "depends-on", "constrains", "related") |
| `provenance` | VARCHAR | `human-authored`, `ai-inferred`, `ai-reviewed` |
| `properties` | JSONB | Optional edge metadata (weight, confidence score, context) |
| `created` | TIMESTAMP | |
| `modified` | TIMESTAMP | |

Unique constraint on `(source_id, target_id, relationship_type)` to prevent duplicate edges.

### `kg_proposals`

| Column | Type | Purpose |
|--------|------|---------|
| `id` | UUID | Primary key |
| `proposal_type` | VARCHAR | `new-node`, `new-edge`, `new-property`, `modify-property` |
| `source_page` | VARCHAR | Page that motivated the proposal |
| `proposed_data` | JSONB | The full proposal — node definition, edge definition, or property change |
| `confidence` | FLOAT | Agent's self-assessed confidence (0-1) |
| `reasoning` | TEXT | Why the agent thinks this is correct — citing specific evidence from page content |
| `status` | VARCHAR | `pending`, `approved`, `rejected` |
| `reviewed_by` | VARCHAR | Who acted on it |
| `created` | TIMESTAMP | |
| `reviewed_at` | TIMESTAMP | |

### `kg_rejections`

| Column | Type | Purpose |
|--------|------|---------|
| `id` | UUID | Primary key |
| `proposed_source` | VARCHAR | Node name |
| `proposed_target` | VARCHAR | Node name |
| `proposed_relationship` | VARCHAR | Relationship type |
| `rejected_by` | VARCHAR | Who rejected it |
| `reason` | TEXT | Optional explanation |
| `created` | TIMESTAMP | |

Acts as a "negative knowledge" store — prevents external agents from re-proposing rejected relationships.

## Component 2: Graph Projector

A WikiEvent listener on `PAGE_SAVED` events. Fires whenever a page with frontmatter is created or modified.

### Projection Algorithm

1. **Parse frontmatter** using the existing `FrontmatterParser`.
2. **Upsert the page's node** — match by page name. Update `node_type` (from frontmatter `type` field), `properties` (all scalar/non-reference frontmatter values), `provenance: human-authored`, `source_page`.
3. **Resolve relationships** — for each frontmatter key whose value is a list of page/node names: ensure target nodes exist (create stubs if necessary), upsert edges with `provenance: human-authored` and `relationship_type` set to the frontmatter key name.
4. **Diff edges** — compare current `human-authored` edges for this source node against what the new frontmatter declares. Remove edges no longer present. Only touches `human-authored` edges — `ai-inferred` and `ai-reviewed` edges are not affected.
5. **Handle promotion write-backs** — if an edge already exists with `ai-reviewed` provenance, the save is a confirmation, not a new assertion. No duplicate created, no provenance change.

### Relationship Detection Convention

A frontmatter key is treated as a relationship (producing edges) when its value is a list of strings that match existing node or page names. Scalar values (strings, numbers, booleans) are treated as node properties stored in JSONB.

**Ambiguity note:** Some list-valued keys (e.g., `tags: [billing, auth]`) may contain values that coincidentally match page names but are not intended as relationships. The implementation should address this through one or more of: a configurable set of keys that are always treated as properties (e.g., `tags`, `keywords`), an explicit convention marker (e.g., keys prefixed with `rel-` are always relationships), or by requiring that relationship targets use wiki page name casing conventions. The exact disambiguation strategy should be resolved during implementation.

### Stub Node Lifecycle

A stub node (`source_page: null`) is created when a page references an entity that doesn't have its own wiki page yet. When someone later creates that page, the stub gets hydrated with `source_page`, `node_type`, properties, and its own outbound edges.

## Component 3: Consumption MCP

A new module (`wikantik-knowledge`) hosting a separate MCP server on its own endpoint (e.g., `/knowledge-mcp`). Completely independent from the authoring MCP. Read-only, small, focused.

### Tool Surface: 5 Tools

#### `discover_schema`

Returns the current shape of the knowledge base: what node types exist, what relationship types exist, what property keys are in use, their cardinalities, and sample values.

No parameters. Returns a schema description that lets an agent understand the vocabulary of this particular knowledge base before formulating queries.

Example response:
```json
{
  "node_types": ["domain-model", "adr", "service", "business-rule", "dependency"],
  "relationship_types": ["depends-on", "constrains", "related", "governed-by", "owns"],
  "property_keys": {
    "domain": {"count": 42, "sample_values": ["billing", "auth", "shipping"]},
    "status": {"count": 87, "sample_values": ["active", "deprecated", "draft"]},
    "region": {"count": 15, "sample_values": ["us-east-1", "eu-west-1"]}
  },
  "stats": {"nodes": 312, "edges": 847, "unreviewed_proposals": 23}
}
```

#### `query_nodes`

Flexible node search with arbitrary filters over type, properties, and provenance.

Parameters:
- `filters` (JSONB) — any combination of field/value pairs applied against node type and JSONB properties
- `provenance_filter` (optional) — restrict to certain provenance levels
- `limit`, `offset` — pagination

Returns matching nodes with their properties.

#### `traverse`

Graph traversal from a starting node. Follows relationships outward, inward, or both, optionally filtered by relationship type and depth.

Parameters:
- `start_node` — name or id
- `direction` — `outbound`, `inbound`, or `both`
- `relationship_types` (optional) — filter to specific types; empty means all
- `max_depth` — traversal depth limit
- `provenance_filter` (optional)

Returns the subgraph: all nodes and edges encountered during traversal.

#### `get_node`

Retrieve full detail for a single node: all properties, all edges (inbound and outbound), source page content, and provenance.

Parameters:
- `node` — name or id

#### `search_knowledge`

Full-text search across node names, properties, and source page content — returning results as graph entities, not raw pages. Bridges the gap between "I don't know the exact name" and the structured query tools.

Parameters:
- `query` — search text
- `provenance_filter` (optional)
- `limit`

### Provenance Filtering Defaults

When no `provenance_filter` is specified, tools return `human-authored` and `ai-reviewed` knowledge only. Pending AI proposals (`ai-inferred`) are excluded by default. Agents must explicitly opt in to speculative content.

### Authorization

Inherits the wiki's existing permission model. If an agent's identity doesn't have view permission on a source page, the corresponding node and its edges are filtered from query results.

## Component 4: Proposal System

Three tools added to the existing authoring MCP for external agents to submit and query knowledge proposals.

### Proposal Tools

#### `propose_knowledge`

Submit a knowledge proposal.

Parameters:
- `proposal_type` — `new-node`, `new-edge`, `new-property`, `modify-property`
- `proposed_data` (JSONB) — the full proposal (node definition, edge definition, or property change)
- `source_page` — the wiki page that motivated the proposal
- `confidence` (float, 0-1) — agent's self-assessed confidence
- `reasoning` (text) — why the agent believes this is correct, citing specific evidence from page content

The tool checks against `kg_rejections` before accepting — if the same relationship has been previously rejected, the proposal is declined with an explanation.

#### `list_rejections`

Query the rejection history so agents can avoid re-proposing.

Parameters:
- `filters` (optional) — filter by source node, target node, relationship type

#### `list_proposals`

Query pending proposals to avoid duplication.

Parameters:
- `status` (optional) — filter by `pending`, `approved`, `rejected`
- `source_page` (optional)
- `limit`, `offset`

### Approval Mechanics

On **approve**:
1. Node/edge created in the knowledge graph with `provenance: ai-reviewed`
2. Frontmatter updated on the source page (write-back) — the approved relationship is added to the appropriate frontmatter key
3. The page save triggered by the write-back goes through the Graph Projector, which recognizes the edge already exists at `ai-reviewed` provenance and does not duplicate it
4. Proposal record updated: `status: approved`, `reviewed_by`, `reviewed_at`

On **reject**:
1. Entry written to `kg_rejections` with the rejected relationship details and reason
2. Proposal record updated: `status: rejected`, `reviewed_by`, `reviewed_at`

## Component 5: Knowledge Administration UI

A React-based admin interface, accessible at `/admin/knowledge`, gated behind the `knowledge-admin` role.

### Knowledge Admin Role

A new role (`knowledge-admin`) added to the existing role-based permission system. Separate from the existing `Admin` role — domain experts who curate knowledge don't necessarily need full wiki administration privileges. Can be combined with other roles as needed.

### Functional Areas

#### Proposal Review Queue

- List of pending proposals, sortable by confidence, source page, date, proposing agent
- Each proposal shows: what's proposed (node/edge/property), the reasoning/evidence, confidence score, link to source page
- Actions: approve or reject (with optional reason)
- Batch operations for high-confidence proposals from trusted agents

#### Graph Explorer

- Browse the knowledge graph: search and filter nodes by type, properties, provenance
- Select a node to see its full neighborhood — all inbound/outbound edges, properties, source page
- Drill into relationships: click through from node to node
- Filter by provenance to see what's human-authored vs AI-contributed
- Uses the same underlying service layer as the consumption MCP — `discover_schema`, `query_nodes`, `traverse`, `get_node` are the same operations

#### Manual Curation

- Create nodes and edges directly (for knowledge that doesn't belong on any specific wiki page)
- Edit node properties and edge types
- Merge duplicate nodes (when the same entity was created under slightly different names)
- Delete nodes/edges that are stale or incorrect
- All manual curation actions recorded with `human-authored` provenance

### Shared Foundation

The UI and the consumption MCP use the same underlying service layer. The MCP tools are a thin wrapper over the same query/traversal logic that the UI calls. One implementation, two interfaces.

## Provenance Model

Four provenance levels representing the lifecycle of knowledge:

| Provenance | Meaning | How it gets there | In frontmatter? |
|---|---|---|---|
| `human-authored` | A human wrote this in frontmatter | Page save with frontmatter | Yes — originated there |
| `ai-inferred` | An external agent proposed this | Proposal submitted, awaiting review | No — `kg_proposals` only |
| `ai-reviewed` | An agent proposed it and a human approved | Proposal approved | Yes — written back on approval |
| `ai-rejected` | An agent proposed it and a human rejected | Proposal rejected | No — `kg_rejections` only |

### Consumption Defaults

- **Default** (no filter): `human-authored` + `ai-reviewed` — confirmed facts only
- **Strict**: `human-authored` only — only what humans explicitly stated
- **Inclusive**: all levels including `ai-inferred` — everything, for exploration

### Independence of Node and Edge Provenance

Nodes and edges carry provenance independently. A human-authored node can have AI-inferred edges. A stub node (created automatically) can gain human-authored edges when its referencing page is saved.

### History Recovery

Provenance transitions (e.g., "this edge was ai-inferred on Jan 3, then ai-reviewed on Jan 7") are recoverable from the `kg_proposals` table (which records created and reviewed timestamps) and from git history on frontmatter write-backs.

## Explicit Non-Goals

The following are intentionally out of scope for this design:

- **Built-in AI/LLM capability** — the wiki never calls an LLM. All intelligence is external.
- **Graph visualization** — the Graph Explorer is a functional browse/query UI, not a visual graph rendering tool. Visualization could be a follow-on.
- **Vector embeddings or RAG** — complementary technology but a separate concern. Could layer on top of this knowledge graph later.
- **Refactoring the existing authoring MCP** — the authoring MCP gets three new proposal tools but is otherwise untouched.
- **Content migration** — seeding the graph from existing wiki pages is a follow-on task, not part of this build.
- **RDF/SPARQL/OWL** — the property graph model provides the semantic layer without requiring formal ontology engineering. If needed later, the node/edge model maps onto RDF naturally.

## Research Sources

This design was informed by current industry thinking on knowledge management and semantic technologies:

- [Using knowledge graphs to unlock GenAI at scale — EY](https://www.ey.com/en_us/insights/emerging-technologies/using-knowledge-graphs-to-unlock-genai-at-scale)
- [Knowledge Graphs for Enterprise AI: Context, Reasoning — Gend](https://www.gend.co/blog/knowledge-graphs-enterprise-ai)
- [The year of the Knowledge Graph (2025) — Semantic Arts](https://www.semanticarts.com/the-year-of-the-knowledge-graph-2025/)
- [GraphRAG & Knowledge Graphs: Making Your Data AI-Ready for 2026 — Fluree](https://flur.ee/fluree-blog/graphrag-knowledge-graphs-making-your-data-ai-ready-for-2026/)
- [RAG in 2026: Bridging Knowledge and Generative AI — Squirro](https://squirro.com/squirro-blog/state-of-rag-genai)
- [How AI Agents Use MCP for Enterprise Systems 2026 — AgileSoftLabs](https://www.agilesoftlabs.com/blog/2026/02/how-ai-agents-use-mcp-for-enterprise)
- [Knowledge graphs: the missing link in enterprise AI — CIO](https://www.cio.com/article/3808569/knowledge-graphs-the-missing-link-in-enterprise-ai.html)
- [Enterprise AI and agentic software trends shaping 2026 — Intelligent CIO](https://www.intelligentcio.com/north-america/2025/12/24/enterprise-ai-and-agentic-software-trends-shaping-2026/)
