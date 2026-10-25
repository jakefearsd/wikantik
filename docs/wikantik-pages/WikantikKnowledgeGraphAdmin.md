---
date: '2026-04-08'
status: active
summary: Comprehensive step-by-step guide for administering the Wikantik knowledge
  graph to maximize its value for AI agents and human users supporting business...
tags:
- knowledge-graph
- administration
- embeddings
- ai
- graph-management
- operations
type: article
canonical_id: 01KQ0P44YXH9WR0JWF0A6EBVMV
cluster: wikantik-development
related:
- ArtificialIntelligence
- AiAugmentedWorkflows
title: Wikantik Knowledge Graph Administration
---
# Wikantik Knowledge Graph Administration

The Wikantik Knowledge Graph (KG) is a structured layer that sits on top of the wiki's content, capturing the entities mentioned in page text, the typed relationships between them, and the provenance and review state of each. It is built by LLM extraction and human curation, not from page frontmatter or wikilinks (those feed the Page Graph and the structural index; see [PageGraphVsKnowledgeGraph](PageGraphVsKnowledgeGraph)). When properly maintained, it enables AI agents to traverse contextual relationships between topics, discover connections that would otherwise require reading dozens of pages, and propose new knowledge for human review. This guide walks through every aspect of administering the knowledge graph to keep it accurate, complete, and valuable.

## The Knowledge Graph Architecture

Before diving into operations, it helps to understand how the knowledge graph is built and maintained.

### Nodes and Edges

Every entity in the knowledge graph is a **node**. Nodes have:

- **Name** — unique across the graph (for example `Apache Lucene` or `Reciprocal Rank Fusion`)
- **Node type** — one of nine entity classes: `person`, `organization`, `place`, `event`, `product`, `technology`, `concept`, `project`, `version` (the extractors map synonyms such as `framework` or `database` onto `technology`; an unrecognised type falls back to `concept` in the chunk extractor and is dropped by the page extractor)
- **Source page** — the wiki page whose text the entity was extracted from. A node with no source page is shown as a **stub**
- **Properties** — key-value metadata stored alongside the node
- **Provenance** and **tier** — how the node entered the graph and how far it has been vetted (below)

**Edges** connect two nodes with a named relationship. The relationship must come from a closed vocabulary of 21 predicates (listed in Step 5.3), enforced by a database CHECK constraint. A few predicates also carry SHACL shapes — `implements` needs a `technology` subject and a `concept` object, and `located_in` needs a `place` object — and an edge that violates a shape is refused when a curator writes it and skipped when the machine pipeline materializes it.

### Provenance Tracking

Every node and edge carries a **provenance** value and a review **tier**. See [ProvenanceModel](ProvenanceModel) for the full model; in short:

| Provenance | Meaning |
|---|---|
| `human-authored` | A person authored or confirmed the entity |
| `human-curated` | An admin explicitly created or confirmed the edge |
| `ai-inferred` | Produced by the extraction and proposal pipeline |
| `ai-reviewed` | Legacy label for judge-approved content awaiting a person; the current pipeline records that state in the tier instead |

The tier is `machine` for content the judge LLM approved and `human` for content a person authored, confirmed or approved. Reads default to `min_tier=machine` (both tiers); `human` is the strict view.

### How Content Becomes Graph Data

Page frontmatter is **not** projected into the graph; the GraphProjector that once did that was retired in April 2026 (see [GraphProjector](GraphProjector)). Instead:

1. **Chunk.** When a page is saved, the chunker splits it into passages (`kg_content_chunks`).
2. **Gate.** The [inclusion policy](KgInclusionPolicy) decides whether the page may contribute entities at all. The default is exclude.
3. **Extract.** The entity extractor (Ollama by default) reads each chunk and proposes entities and relations; they are filed in `kg_proposals`. This happens off the save thread, per page and rate-limited, and as a batch run from the Extraction tab or `bin/kg-extract.sh`.
4. **Judge.** An asynchronous judge LLM screens pending proposals. Approved ones are materialized into `kg_nodes` / `kg_edges` at tier `machine`. Materialization only ever creates a missing node; a node that already exists under that name is left unchanged (first-writer-wins).
5. **Review.** A person approves (tier `human`, and a `new-edge` is written back to the source page's frontmatter) or rejects (the rows are retracted and the triple is remembered in `kg_rejections`).

System pages (CSS themes, navigation fragments, etc.) never contribute entities.

## Accessing the Knowledge Graph Admin Panel

Navigate to **Admin > Knowledge & Search > Knowledge Graph** (`/admin/knowledge-graph`). The page presents eight tabs, described one by one in [KnowledgeAdminUi](KnowledgeAdminUi):

1. **Proposals** — Review queue for AI-submitted knowledge proposals, with the machine judge's verdicts
2. **Extraction** — Start, watch and cancel a corpus-wide extraction run
3. **Node Explorer** — Browse, search, and manage graph nodes
4. **Edge Explorer** — Browse, search, and manage graph edges
5. **Content Embeddings** — Status of the mention-centroid index, and pages without frontmatter
6. **Hub Proposals** — Hub-membership proposals
7. **Hub Discovery** — Cluster-based discovery of candidate hubs
8. **LLM Activity** — Live view of recent extraction, judge and embedding calls

A **Clear all KG data** button in the toolbar permanently deletes every node, edge, proposal, review and rejection (and the mention and node-embedding rows that cascade from them). Use with extreme caution — this is irreversible. Cluster inclusion is managed separately at `/admin/kg-policy`.

## Step 1: Initial Graph Population

When starting with a fresh knowledge graph (or after a Clear All), the first task is to populate it from existing wiki content.

### 1.1 Check the prerequisites

- **Knowledge Graph enabled.** `wikantik.knowledge.enabled` (default `true`). When it is false no KG services are built and `/admin/knowledge-graph/*` returns 503.
- **A reachable extractor.** The default backend is Ollama (`wikantik.knowledge.extractor.backend`), the model is `wikantik.knowledge.extractor.ollama.model` (`gemma4-graph:12b`) and the server is `wikantik.knowledge.extractor.ollama.base_url`. The shipped default points at a private inference host, so a fresh deployment must override it, and `wikantik.genai.mode` must be `full` (the default) because `embeddings-only` and `none` block chat inference. Set the backend to `disabled` for zero save-time overhead.
- **Chunks and embeddings.** Chunking runs on save. After a fresh install or a chunker change, rebuild with `bin/kg-rebuild.sh` (see [SearchIndexAndKnowledgeGraphRebuild](SearchIndexAndKnowledgeGraphRebuild)).
- **Inclusion decisions.** Nothing is extracted until a page's cluster has an `include` row or the page sets `kg_include: true`. On a new deployment, open `/admin/kg-policy` and run the bootstrap wizard or set clusters one by one; see [KgInclusionPolicy](KgInclusionPolicy).

### 1.2 Make sure pages have frontmatter

The cluster policy reads each page's `cluster:` frontmatter, so a page with no frontmatter can enter the graph only through `kg_include: true`. The **Content Embeddings** tab lists **Pages Without Frontmatter**; each entry links to the page editor, and **Backfill Frontmatter** writes default frontmatter (title, `type: article`, extracted tags and summary) into all of them. The backfill does not assign a cluster. A well-structured page has at minimum:

```yaml
---
type: article
cluster: my-topic-cluster
tags: [relevant, topic, keywords]
summary: One sentence describing the page's purpose.
---
```

Hub pages that organize a cluster use `type: hub` and declare the cluster with `cluster:`; see [ClusterDeclarationDesign](ClusterDeclarationDesign). Frontmatter relationship lists such as `related:` help readers and the structural index but do not create Knowledge Graph edges.

### 1.3 Run extraction

Open the **Extraction** tab and press Start. The run walks every included page, shows progress by pages and chunks, and can be cancelled (the in-flight page finishes first). **Force re-extract** reprocesses pages that were already extracted — use it after changing the extractor model or prompts. After the first pass, edits are picked up on save.

The same work is available offline with the standalone CLI, which does not need Tomcat:

```bash
bin/kg-extract.sh --max-pages 50 --dry-run        # smoke run, no proposals written
bin/kg-extract.sh --concurrency 1                 # low GPU pressure
bin/kg-extract.sh --report reports/extract.json   # write the final status as JSON
```

Other useful flags are `--page-pattern <glob>`, `--only-missing` (skip pages that already have proposals) and `--ollama-model`; `bin/kg-extract.sh --jar-help` prints them all. Note that the CLI's own `--ollama-model` default is `gemma4-assist:latest`, whereas the in-server default is `gemma4-graph:12b`.

### 1.4 Verify the initial graph

After extraction and a few judge passes, review the graph for quality:

- **Proposals tab:** proposals appear, and the verdict badges start to fill in as the judge works through the queue.
- **Node Explorer:** the schema summary at the top shows the total node count, edge count, which node types and relationship types exist, and the pending-proposal breakdown. Search for key topics and verify they appear as nodes with the right types.
- **Edge Explorer:** filter by relationship type to see whether the expected relationships were created.

## Step 2: Embeddings

Wikantik uses a single Ollama-backed embedding model that produces dense vectors for every page chunk. Those vectors back hybrid search and are also reused — as on-the-fly centroids over `chunk_entity_mentions` — for KG-node similarity, so there is no separate "structural" or "content" embedding model to retrain.

The chunk embedding indexer runs continuously: it picks up newly chunked pages on each save and writes vectors into `content_chunk_embeddings`. The **Content Embeddings** tab (backed by `GET /admin/knowledge-graph/embeddings/status`) reports:

- **Ready** — true when the in-memory mention-centroid index has loaded
- **Dimension** — vector size for the active model
- **Mentioned node count** — KG nodes for which at least one mention chunk has an embedding

There is no manual "retrain" button. To recompute every chunk vector wholesale, for example after switching the active embedding model, run `bin/kg-rebuild.sh --skip-chunks --skip-extract` (it re-embeds without re-chunking or re-extracting) or `POST /admin/content/reindex-embeddings`.

## Step 3: Reviewing AI Proposals

Proposals reach the **Proposals** tab from the entity extractors and from AI agents using the admin MCP `propose_knowledge` tool. The full lifecycle is in [KnowledgeProposals](KnowledgeProposals).

### 3.1 Proposals

Each proposal includes:

| Field | Description |
|---|---|
| **Type** | `new-node`, `new-edge`, `new-property`, or `modify-property` (only the first two change the graph when approved) |
| **Source Page** | The wiki page that motivated the proposal |
| **Proposed Data** | The full data — node definition, edge definition, or property change |
| **Confidence** | The extractor's or agent's self-assessed confidence (0.0 to 1.0) |
| **Reasoning** | Why the agent believes this is correct, citing specific evidence |
| **Machine verdict** | The judge's `approved`, `rejected` or `abstain`, with a rationale you can expand |

The **Filter** menu narrows the queue to proposals awaiting machine review, machine approved, machine rejected or machine abstained.

### 3.2 The machine judge

An asynchronous judge LLM (`wikantik.kg.judge.enabled`, default `true`) runs every `wikantik.kg.judge.cron.interval_min` minutes (default 5) over up to `wikantik.kg.judge.batch_size` pending, unjudged proposals (default 50) with a per-call timeout of `wikantik.kg.judge.timeout_seconds` (default 120). Unless you set `wikantik.kg.judge.endpoint` / `wikantik.kg.judge.model`, it uses the extractor's Ollama server and model. Judge-approved proposals are materialized immediately at tier `machine`, so they are visible to agents before a person has looked at them. The status line above the queue shows how many proposals are still unjudged; **Run judge runner** starts a pass now, **Judge now** on a row judges that proposal, and `bin/kg-judge.sh` does the same from a shell (`--status`, `--proposal-id UUID`). Proposals whose judge call keeps timing out are listed at `GET /admin/knowledge-graph/judge-timeouts`.

### 3.3 Reviewing Effectively

When reviewing proposals:

1. **Read the reasoning carefully.** A well-formed proposal cites specific evidence from the source page. Vague reasoning ("these seem related") is a warning sign.
2. **Check the confidence score and the machine verdict.** Low confidence proposals deserve more scrutiny. Very high confidence (>90%) from an AI agent should also be verified — overconfidence can indicate hallucination.
3. **Verify the source page.** Click through to the source page and confirm the proposed relationship or property actually reflects the page content.
4. **Consider the graph impact.** A new edge between two major hub nodes has broader implications than an edge between two leaf articles.

### 3.4 Approving Proposals

Click **Approve** to accept a proposal (or select several rows for a bulk approve, or use the admin MCP `review_proposals` tool). Approval:

1. promotes the rows the judge materialized to tier `human`, creating them if the judge had not, and clears any earlier rejection of the same triple;
2. for `new-edge` proposals, writes the relationship back into the source page's frontmatter, creating a durable record that persists even if the knowledge graph is rebuilt.

The rows keep `ai-inferred` provenance; the tier records that a person approved them.

Approving a proposal never overwrites an existing node. Materialization is first-writer-wins: when a node with the proposal's name already exists (curated by a person, extracted earlier or created by another proposal), it is left completely unchanged — type, provenance, properties and tier — and the skipped write is logged at INFO. A `new-edge` proposal likewise creates a missing endpoint as a `concept` node but leaves an existing endpoint alone. Releases up to 2.4.56 replaced an existing node's type, provenance and properties when a `new-node` proposal was approved.

### 3.5 Rejecting Proposals

Click **Reject** to decline a proposal. You'll be prompted for a rejection reason (**Reject (no reason)** skips the prompt). Providing a clear reason is important because:

- anything the judge had materialized for the proposal is retracted;
- a rejected edge is recorded in the **rejection history** (`kg_rejections`);
- when an extractor or an AI agent tries to submit the same relationship again, it is automatically declined — `propose_knowledge` returns the rejection reason;
- this teaches agents what relationships are inappropriate, reducing future noise.

### 3.6 Maintaining a Healthy Review Cadence

The Node Explorer's schema summary shows the count of **pending proposals**. Aim to keep this near zero. A backlog of unreviewed proposals means AI agents are working with an incomplete graph, and stale proposals may become irrelevant as pages change.

## Step 4: Exploring and Curating Nodes

The **Node Explorer** is your primary tool for understanding and curating the graph's entities.

### 4.1 Browsing and Filtering

- **Search:** Type in the search box to filter nodes by name
- **Type filter:** Select a node type (for example `technology` or `concept`) to narrow the list
- **Status filter:** Filter by the `status` value held in a node's properties
- **Pagination:** Navigate through large node sets with Prev/Next buttons

### 4.2 Inspecting Node Details

Click any node row to view its details in the right panel:

- **Properties:** All key-value metadata stored on the node
- **Outbound Edges:** Relationships where this node is the source (this node → target)
- **Inbound Edges:** Relationships where this node is the target (source → this node)
- **Context:** The page chunks that mention the node
- **Similar Nodes:** Nodes whose mention chunks are close to this node's in embedding space (cosine similarity over the shared mention-centroid vectors; shown when the index is ready)

Click any node name in the edge tables to navigate to that node's detail view.

### 4.3 Handling Stub Nodes

Stub nodes appear with a "Yes" in the Stub column and an italic warning in the detail panel. A stub is typically a node created by hand without a `source_page`. A few are normal, but a large number of them may indicate:

- **Typos or near-duplicate names** — a misspelled name creates a second node beside the real one
- **Entities that never got a home** — a concept that deserves a source page
- **Noise** — extraction artifacts that will never be resolved

Review stubs periodically. Merge duplicates, give a node a source page (an admin MCP `curate_nodes` upsert accepts `source_page`), or delete stubs that will never be resolved.

### 4.4 Deleting and Merging Nodes

Click **Delete** in the node detail panel to remove a node and all its edges, or select several rows for a bulk delete. Use this for:

- Cleaning up typo-created stubs
- Removing test data
- Eliminating obsolete entities after a page is deleted

Deletion is permanent — the node and all its edges are removed from the database.

To collapse two nodes that name the same thing, use a merge: `POST /admin/knowledge-graph/nodes/merge` with `{sourceId, targetId}`, or the admin MCP `curate_nodes` `merge` action. A merge moves the source node's edges onto the target, deletes the source, and renames list-valued frontmatter entries that name the old node on the pages the source's inbound edges came from.

## Step 5: Exploring and Curating Edges

The **Edge Explorer** lets you browse the relationship layer of the graph.

### 5.1 Browsing Edges

- **Search:** Filter edges by source or target node name
- **Relationship type filter:** Select a specific relationship type (for example `part_of`, `requires` or `implements`)
- **Pagination:** Navigate with Prev/Next buttons

### 5.2 Inspecting and Curating Edges

Click any edge row to see:

- The full source → relationship → target path
- The provenance badge (human-authored, human-curated, ai-inferred, or ai-reviewed)
- Detailed cards for both the source and target nodes, including their properties and source pages
- The edge's audit history

The row and bulk actions are **Confirm** (elevate an AI-inferred edge in place to `human-curated`, tier `human`), **Delete**, and **Delete + Prevent** (delete and record a rejection so extractors and agents do not propose the same triple again). **New edge** creates a hand-curated edge; it passes through the same SHACL gate and is refused with an explanation when it violates a shape or when one endpoint is a page node and the other an entity.

### 5.3 Relationship Type Conventions

Relationship types come from a fixed vocabulary, shown here with their direction (always source → target). Prefer the most specific predicate; use `related_to` only when nothing else fits.

| Relationship Type | Meaning |
|---|---|
| `related_to` | Generic association; use only when no more specific type fits |
| `part_of` / `contains` | A is a part of B / A contains B |
| `is_a` / `instance_of` / `generalizes` | A is a subtype of B / A is a concrete instance of B / A generalizes B |
| `requires` / `enables` | A requires B / A enables or supports B |
| `uses` / `produces` | A uses or operates on B / A produces or emits B |
| `replaces` / `precedes` | A replaces or supersedes B / A comes before B in time or sequence |
| `extends` / `implements` | A extends or specializes B / A is a concrete implementation of B |
| `alternative_to` / `contrasts_with` / `compatible_with` | Peer alternatives / explicitly compared / interoperate |
| `mitigates` / `defines` / `applies_to` | A reduces the harm of B / A defines B / A is relevant within the scope of B |
| `located_in` | A is spatially within B |

The vocabulary is defined in `RelationshipTypeVocabulary` and the `kg_edges_relationship_type_check` constraint (migrations V027 and V030). Adding a predicate is a code-and-migration change, not an admin action.

## Step 6: Finding and Fixing Quality Problems

The structural embedding model (ComplEx vectors, predicted missing edges, anomaly scores, merge candidates) and the TF-IDF content model were removed along with their tables (migration V019). Quality work now relies on these tools:

- **Orphans.** The admin MCP `list_orphaned_kg_nodes` tool lists nodes with no edges, filterable by node type and source page. Connect them, merge them into a better-connected node, or delete them.
- **Duplicates.** Use search in the Node Explorer or `search_knowledge`, then merge (Step 4.4).
- **Type drift.** Fix a wrong `node_type` with an upsert (a retype from the page editor's Knowledge tab or `curate_nodes`). `bin/kg-cleanup-node-types.sh` is an interactive one-shot that finds `node_type` values outside the format `^[a-z][a-z0-9_-]{0,30}$` and lets you rename, delete or skip each one.
- **SHACL violations.** `GET /admin/ontology/violations` and the `shacl` family on the `/admin/drift` dashboard list edges that violate a shape. Either retype the node or fix the edge; the burn-down workflow is in `docs/admin/OntologyManagement.md`.
- **Judge timeouts.** `GET /admin/knowledge-graph/judge-timeouts` lists proposals the judge repeatedly timed out on; decide them by hand.
- **Similar entities.** `find_similar` on `/knowledge-mcp` and the Node Explorer's Similar Nodes panel suggest near neighbours worth reviewing.

## Step 7: Pages Without Frontmatter

The **Content Embeddings** tab lists every page that lacks YAML frontmatter. Such pages have no `cluster`, so the inclusion policy cannot admit them (only `kg_include: true` can), and they carry no tags, summary or type for search and SEO. Prioritize adding frontmatter to:

- **Frequently accessed pages** — these hold knowledge that belongs in the graph
- **Hub pages** — these organize clusters
- **Pages in clusters you have decided to include** — otherwise their content is silently skipped

## Step 8: Ongoing Maintenance

A healthy knowledge graph requires periodic attention, not just initial setup.

### 8.1 Weekly Review Checklist

1. **Check the Proposals tab** — approve or reject all pending proposals and confirm the judge is draining the unjudged queue
2. **Check `/admin/kg-policy`** — decide any clusters in the pending-review queue
3. **Review judge timeouts** — decide proposals the judge cannot handle
4. **Check orphans and stubs** — `list_orphaned_kg_nodes`, and the Stub column in the Node Explorer
5. **Check `/admin/drift`** — the SHACL count should stay at zero
6. **Review Pages Without Frontmatter** — add frontmatter to newly created pages

### 8.2 After Major Content Changes

When you add a new cluster of pages or reorganize existing content:

1. Decide the cluster's inclusion at `/admin/kg-policy`. A new cluster is excluded until you act; changing a policy from the dashboard starts reconciliation, which a CLI change does not.
2. Wait for extraction. Edits to included pages are extracted on save; for a bulk import you can start a run from the **Extraction** tab.
3. Check the Node Explorer for unexpected stubs and the Edge Explorer for sensible relationship types.

If you change the embedding model or the chunker settings (`wikantik.chunker.*`), chunk boundaries and embeddings are invalidated — run `bin/kg-rebuild.sh`.

### 8.3 Monitoring Graph Health

Use the schema summary in the Node Explorer to track key metrics:

- **Node count** — should grow steadily as content is added
- **Edge count** — should grow proportionally to nodes; a very low ratio may indicate sparse extraction
- **Pending proposals** — should stay near zero; the breakdown shows how many are new nodes, new edges, judge-approved, abstained or unjudged
- **Node types** and **Relationship types** — watch for unexpected types that indicate extractor drift

The **LLM Activity** tab shows recent extraction, judge and embedding calls, including failures and in-flight requests, which is the quickest way to see that the inference host is reachable.

### 8.4 Re-extraction vs. Incremental Updates

Extraction runs incrementally on save, so the graph stays current. A full re-extraction (**Force re-extract**, or `bin/kg-rebuild.sh`) is only needed when:

- Setting up the graph for the first time
- Recovering from a Clear All
- You change the extractor model, prompts or chunker settings
- A cluster is newly included and you do not want to wait for its pages to be edited

`bin/kg-rebuild.sh --reset-kg` first deletes pending proposals and every machine-tier node whose provenance is `ai-inferred` (cascading to their machine-tier edges), and `--purge-kg` truncates the whole KG layer including human-authored data and requires typing PURGE; both are covered in [SearchIndexAndKnowledgeGraphRebuild](SearchIndexAndKnowledgeGraphRebuild). Rows a person approved keep `ai-inferred` provenance and only change tier, so `--reset-kg` selects on tier as well (`bin/db/kg-reset-machine-tier.sql`): approved nodes, every other tier `human` row, and a machine node that anchors a human-tier edge all survive. To see what a reset would remove, run `SELECT tier, provenance, COUNT(*) FROM kg_nodes GROUP BY 1, 2`; the `machine` / `ai-inferred` row is the deletable set. Releases up to 2.4.56 deleted every `ai-inferred` node, approved ones included, so on those versions count them first.

## Step 9: Optimizing the Graph for AI Agents

AI agents read the graph through `/knowledge-mcp` (`discover_schema`, `query_nodes`, `get_node`, `traverse`, `search_knowledge`, `find_similar`) and contribute to it with `propose_knowledge` on `/wikantik-admin-mcp`. To maximize the value agents get from the graph:

### 9.1 Keep Inclusion and Frontmatter Accurate

Agents only see entities from included pages. Keep `cluster` and `kg_include` correct, and keep summaries and types current so the structural index and retrieval can route agents to the right pages. See [KgInclusionPolicy](KgInclusionPolicy).

### 9.2 Prefer Specific Relationship Types

Agents use relationship types to understand the *nature* of connections, not just their existence. `requires` conveys different meaning than `related_to`, which is different from `replaces`. Retype or replace edges that dump everything into `related_to`.

### 9.3 Provide Fast Feedback on Proposals

When agents submit proposals and receive timely feedback (approvals or rejections with reasons), they learn what kinds of knowledge are valued. Rejected proposals with clear reasons are especially valuable — the rejection history prevents agents from re-submitting the same incorrect relationships.

### 9.4 Resolve Stubs and Orphans

Stub and orphan nodes are weak spots for agent traversal: an agent that reaches one finds no source page to read and few onward edges. Prioritize giving a source page to, or merging, the ones that many other nodes point at.

### 9.5 Keep the Chunk Embeddings Current

Agents benefit from up-to-date similarity data when looking for related concepts. The chunk embedding indexer runs continuously on every page save, but if the active embedding model changes you'll want to run `bin/kg-rebuild.sh --skip-chunks --skip-extract` to recompute all chunk vectors against the new model.

## Step 10: Advanced Configuration

### 10.1 Tuning Keys

Every key below is declared with its default in `ini/wikantik.properties` (the MCP key in `wikantik-mcp.properties`); override them in `wikantik-custom.properties`:

| Key | Default | Effect |
|---|---|---|
| `wikantik.knowledge.enabled` | `true` | Master switch for the whole KG subsystem |
| `wikantik.knowledge.extractor.backend` | `ollama` | `ollama`, `claude` or `disabled` |
| `wikantik.knowledge.extractor.ollama.model` | `gemma4-graph:12b` | Extraction model; `think:false` is sent on every request |
| `wikantik.knowledge.extractor.timeout_ms` | `120000` | Per-chunk HTTP timeout |
| `wikantik.knowledge.extractor.confidence_threshold` | `0.6` | Proposals below this are dropped, not filed |
| `wikantik.knowledge.extractor.concurrency` | `2` | Parallel extraction calls for the admin batch run (clamped to 1-10) |
| `wikantik.knowledge.extractor.per_page_min_interval_ms` | `5000` | Re-save storm protection per page |
| `wikantik.knowledge.extractor.prefilter.enabled` | `false` | Skip chunks (pure code, no proper noun, too short) before they cost an LLM call; `prefilter.dry_run` logs what it would skip |
| `wikantik.kg.judge.enabled` | `true` | The asynchronous judge |
| `wikantik.kg.judge.cron.interval_min` / `batch_size` / `concurrency` | `5` / `50` / `1` | Judge cadence and throughput |
| `wikantik.kg.read.default_min_tier` | `machine` | Default tier for REST snapshot reads (`human` = strict) |
| `wikantik.kg_policy.enabled` | `true` | Cluster inclusion policy |
| `wikantik.mcp.kg_curation.bulk_limit` | `50` | Maximum ids or operations per bulk MCP curation call |

The offline CLI's Claude paths are behind cost guards: `--extractor claude` and `--judge claude` refuse to run unless the JVM is started with `-Dwikantik.kg.extractor.allow_claude=true` or `-Dwikantik.kg.judge.allow_claude=true` and an API key sits in the environment variable named with `--anthropic-key-env`. Those two properties are read only as system properties, never from the properties file.

### 10.2 Changing the Vocabularies

The entity classes (`EntityTypeVocabulary`), the relationship predicates (`RelationshipTypeVocabulary`) and the SHACL shapes (`shapes.ttl`) are code, not configuration. A new predicate needs a migration that widens the CHECK constraint on `kg_edges.relationship_type`; a new entity class also needs the ontology's T-Box and `NodeTypeMapping` updated (a drift test keeps them aligned). `docs/admin/OntologyManagement.md` walks through the procedure.

### 10.3 Command-line tools

| Script | Purpose |
|---|---|
| `bin/kg-extract.sh` | Standalone entity extraction (`--max-pages`, `--page-pattern`, `--only-missing`, `--dry-run`, `--concurrency`, `--report`, `--judge none\|ollama\|claude`) |
| `bin/kg-rebuild.sh` | Chunk + Lucene rebuild, embedding reindex, optional KG reset or purge, then extraction; `--skip-chunks`, `--skip-embeddings`, `--skip-extract`, `--dry-run` |
| `bin/kg-judge.sh` | Trigger a judge pass, judge one proposal, or show the queue |
| `bin/kg-judge-experiment.sh` | Compare judge models on a sample of pending proposals; see [RunningTheJudgeExperimentHarness](RunningTheJudgeExperimentHarness) |
| `bin/kg-materialize-approved.sh` | Replay machine-approved `new-node` proposals into `kg_nodes` (`--dry-run`, `--limit N`); needs no LLM. First-writer-wins and safe to re-run: it creates only missing nodes. Run it on 2.4.57 or later, because earlier versions overwrote an existing node |
| `bin/kg-policy.sh` | Inclusion policy CLI; see [KgInclusionPolicy](KgInclusionPolicy) |
| `bin/kg-cleanup-node-types.sh` | Interactive one-shot to rename or delete `node_type` values that fail the `^[a-z][a-z0-9_-]{0,30}$` format |
| `bin/kg-chunker-stats.sh` | Offline chunk-size statistics for tuning the chunker |

## Troubleshooting

### Extraction produces no proposals
Check, in order: the page's cluster is included or the page sets `kg_include: true` (use the Explain tab on `/admin/kg-policy`); `wikantik.knowledge.extractor.backend` is not `disabled` and `wikantik.genai.mode` is `full`; the Ollama server in `wikantik.knowledge.extractor.ollama.base_url` is reachable and serves the configured model (the LLM Activity tab shows the failing calls); and `wikantik.knowledge.enabled` is true.

### Proposals pile up unjudged
The judge needs the same reachable Ollama server. Check `wikantik.kg.judge.enabled`, the "pending unjudged" status line on the Proposals tab, the LLM Activity tab, and `GET /admin/knowledge-graph/judge-timeouts`; **Run judge runner** forces a pass.

### Proposals keep getting re-submitted by AI
Ensure you're **rejecting** unwanted proposals (not just ignoring them) and providing clear rejection reasons. Only rejected edges are recorded in the rejection history that prevents re-submission.

### Merge causes unexpected page edits
When you merge node A into node B, the system renames list-valued frontmatter entries that name A to B on the pages that source A's inbound edges. This keeps those pages consistent with the graph. Review the merge before proceeding.

### A curated edge is refused
The edge violated a SHACL shape (`implements` needs a `technology` subject and a `concept` object; `located_in` needs a `place` object), used a relationship outside the 21-predicate vocabulary, or linked a page node to an entity node. Retype the node, pick another predicate, or use entity endpoints.

## Controlling KG Inclusion

The knowledge graph isn't built from every page. Cluster-level policy decides what contributes; per-page frontmatter overrides handle the rest. For the full model and dashboard walkthrough, see [KgInclusionPolicy](KgInclusionPolicy).

The short version:

- **Default-exclude.** A cluster you haven't touched contributes nothing.
- **Cluster dashboard** at `/admin/kg-policy` lets you toggle cluster inclusion with a reason. Eager reconciliation runs on commit.
- **Frontmatter override** (`kg_include: true | false`) wins over cluster policy. Useful for WIP, sensitive, or one-off content.
- **CLI** at `bin/kg-policy.sh` mirrors the dashboard for scripting and emergencies, but its `set` and `clear` do not start reconciliation, and `purge --confirm` (the only destructive operation) is not scoped to one cluster.

System pages (Sandbox, Main, etc.) are always excluded — both from the KG and from the search index — via the existing `SystemPageRegistry`.

---

**Cluster Hub:** [[WikantikDevelopmentHub|Wikantik Platform Development Hub: Architecture, MCP Protocols, and Agent Briefings]] (`wikantik-development`)
