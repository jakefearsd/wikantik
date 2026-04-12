---
title: Wikantik Knowledge Graph Administration
type: article
tags: [knowledge-graph, administration, embeddings, ai, graph-management, operations]
date: 2026-04-08
status: active
summary: Comprehensive step-by-step guide for administering the Wikantik knowledge graph to maximize its value for AI agents and human users supporting business operations and software development.
related: [ArtificialIntelligence, AiAugmentedWorkflows]
---

# Wikantik Knowledge Graph Administration

The Wikantik knowledge graph is a structured layer that sits on top of the wiki's content, capturing entities, relationships, and properties extracted from page frontmatter and body links. When properly maintained, it enables AI agents to traverse contextual relationships between topics, discover connections that would otherwise require reading dozens of pages, and propose new knowledge for human review. This guide walks through every aspect of administering the knowledge graph to keep it accurate, complete, and valuable.

## The Knowledge Graph Architecture

Before diving into operations, it helps to understand how the knowledge graph is built and maintained.

### Nodes and Edges

Every entity in the knowledge graph is a **node**. Nodes have:

- **Name** — typically matching a wiki page name (e.g. `AssetAllocation`, `RetirementPlanningHub`)
- **Node type** — an optional classification (e.g. `hub`, `article`) drawn from the page's `type` frontmatter field
- **Source page** — the wiki page this node was projected from (if any)
- **Properties** — key-value metadata stored alongside the node (tags, summary, status, cluster, etc.)
- **Stub flag** — true when the node was created as a reference target but has no wiki page yet

**Edges** connect two nodes with a named relationship. For example, if a page's frontmatter contains `related: [IndexFundPortfolioConstruction, UnderstandingRiskTolerance]`, the projector creates edges of type `related` from the page's node to each target node.

### Provenance Tracking

Every node and edge carries a **provenance** value that records how it entered the graph:

| Provenance | Meaning |
|---|---|
| `human-authored` | Created from page frontmatter, body links, or manual admin action in the UI |
| `ai-inferred` | Submitted by an AI agent via the MCP `propose_knowledge` tool, still pending review |
| `ai-reviewed` | Originally proposed by an AI agent, then approved by a human administrator |

Provenance tracking ensures you always know whether a fact in the graph was authored by a human or suggested by AI, and whether AI suggestions have been vetted.

### How Pages Become Graph Data

The **[Graph Projector](GraphProjector)** is a page filter that fires automatically on every page save. It:

1. Parses the page's YAML frontmatter
2. Upserts a node for the page itself
3. Detects which frontmatter keys represent relationships versus properties
4. Creates edges for each relationship target
5. Scans the page body for [markdown links](MarkdownLinks) and creates `links_to` edges
6. Removes stale edges that no longer appear in the frontmatter or body
7. Creates **stub nodes** for any target that doesn't have its own wiki page yet

**Relationship detection rule:** A frontmatter key is treated as a relationship (creating edges) when its value is a list of strings *and* the key is not in the reserved property set. The reserved property keys — always treated as node properties, never as edges — are: `tags`, `keywords`, `type`, `summary`, `date`, `author`, `cluster`, `status`, `title`, `description`, `category`, and `language`.

For example, given this frontmatter:

```yaml
---
type: article
tags: [investing, diversification]
summary: How to allocate assets across classes.
related: [UnderstandingRiskTolerance, IndexFundPortfolioConstruction]
depends_on: [MarketFundamentals]
---
```

The projector creates:
- **Properties** on the node: `type=article`, `tags=[investing, diversification]`, `summary=...`
- **Edges** from this page: `related → UnderstandingRiskTolerance`, `related → IndexFundPortfolioConstruction`, `depends_on → MarketFundamentals`
- Additionally, any `[PageName](PageName)` links in the body text produce `links_to` edges

System pages (CSS themes, navigation fragments, etc.) are automatically excluded from projection.

## Accessing the Knowledge Graph Admin Panel

Navigate to **Admin > Knowledge** in the Wikantik admin panel. The page presents five tabs:

1. **Proposals** — Review queue for AI-submitted knowledge proposals
2. **Node Explorer** — Browse, search, and manage graph nodes
3. **Edge Explorer** — Browse, search, and manage graph edges
4. **KG Embeddings** — Structural embedding model for link prediction and anomaly detection
5. **Content Embeddings** — Content similarity model for finding related and duplicate pages

A **Clear All** button in the toolbar permanently deletes all knowledge graph data (nodes, edges, proposals, and embeddings). Use with extreme caution — this is irreversible.

## Step 1: Initial Graph Population

When starting with a fresh knowledge graph (or after a Clear All), the first task is to populate it from existing wiki content.

### 1.1 Ensure Pages Have Frontmatter

The knowledge graph is only as rich as the frontmatter in your wiki pages. Before projecting, check the **Content Embeddings** tab — it lists all **Pages Without Frontmatter**. Each entry links to the page editor so you can add frontmatter.

A well-structured page should have at minimum:

```yaml
---
type: article
tags: [relevant, topic, keywords]
summary: One sentence describing the page's purpose.
related: [RelatedPageOne, RelatedPageTwo]
---
```

Hub pages that organize a cluster of articles should use `type: hub` and include a `cluster` identifier:

```yaml
---
type: hub
tags: [topic-area]
cluster: my-topic-cluster
summary: Overview hub for the topic area.
related: [SubArticleOne, SubArticleTwo]
---
```

The more relationship keys you add to frontmatter (e.g. `depends_on`, `supersedes`, `implements`, `related`), the richer the graph becomes. Any key whose value is a list of page names will be detected as a relationship.

### 1.2 Project All Pages

Go to the **Node Explorer** tab and click **Project All Pages**. This scans every non-system wiki page, parses its frontmatter and body links, and creates the corresponding nodes and edges. The status line will report how many pages were scanned, how many were projected, and any errors encountered.

After projection, the schema summary at the top of the Node Explorer shows the total node count, edge count, and which node types and relationship types exist in the graph.

### 1.3 Verify the Initial Graph

After projection, review the graph for quality:

- **Node Explorer:** Search for key topics and verify they appear as nodes. Check that node types are correct. Look for unexpected stub nodes — these indicate frontmatter references to pages that don't exist yet.
- **Edge Explorer:** Filter by relationship type to see whether the expected relationships were created. Check that `links_to` edges from body links are present.
- **Schema summary:** Confirm the relationship types match what you expect from your frontmatter conventions.

## Step 2: Training the Embedding Models

Once the graph is populated, train the embedding models to unlock predictive features.

### 2.1 Train the Structural Embedding Model (ComplEx)

Go to the **KG Embeddings** tab and click **Retrain Now**. This trains a ComplEx knowledge graph embedding model on the current graph structure. The model learns vector representations of entities and relationships that capture structural patterns — which nodes tend to connect via which relationship types.

The status bar shows:
- **Model version** — increments with each retrain
- **Entity count** — number of nodes in the model
- **Relation count** — number of distinct relationship types
- **Dimension** — embedding vector size (default: 50)
- **Last trained** — timestamp of the most recent training

Training is fast (milliseconds for typical wiki graphs of ~1K nodes) and the model is persisted to the database, so it survives server restarts.

### 2.2 Train the Content Embedding Model (TF-IDF)

Go to the **Content Embeddings** tab and click **Retrain Content**. This computes TF-IDF embeddings for all wiki page content, enabling content-based similarity comparisons between pages.

### 2.3 Configure Automatic Retraining

Both models support periodic automatic retraining via the `wikantik.kge.retrainMinutes` property in your `wikantik-custom.properties` file. When configured, both the structural and content models retrain on a background thread at the specified interval. This keeps the models current as pages are edited without requiring manual intervention.

## Step 3: Reviewing AI Proposals

AI agents that interact with the wiki via the MCP server can submit knowledge graph proposals using the `propose_knowledge` tool. These proposals appear in the **Proposals** tab for human review.

### 3.1 Proposals

Each proposal includes:

| Field | Description |
|---|---|
| **Type** | `new-node`, `new-edge`, `new-property`, or `modify-property` |
| **Source Page** | The wiki page that motivated the proposal |
| **Proposed Data** | The full data — node definition, edge definition, or property change |
| **Confidence** | The agent's self-assessed confidence (0-100%) |
| **Reasoning** | Why the agent believes this is correct, citing specific evidence |

### 3.2 Reviewing Effectively

When reviewing proposals:

1. **Read the reasoning carefully.** A well-formed proposal cites specific evidence from the source page. Vague reasoning ("these seem related") is a warning sign.
2. **Check the confidence score.** Low confidence proposals deserve more scrutiny. Very high confidence (>90%) from an AI agent should also be verified — overconfidence can indicate hallucination.
3. **Verify the source page.** Click through to the source page and confirm the proposed relationship or property actually reflects the page content.
4. **Consider the graph impact.** A new edge between two major hub nodes has broader implications than an edge between two leaf articles.

### 3.3 Approving Proposals

Click **Approve** to accept a proposal. For `new-edge` proposals, approval triggers two actions:

1. The edge is created in the knowledge graph with `ai-reviewed` provenance
2. The relationship is automatically written back into the source page's frontmatter, creating a durable record that persists even if the knowledge graph is rebuilt

This frontmatter write-back is important: it means approved AI knowledge becomes part of the page's permanent content, visible to future page editors and graph projections.

### 3.4 Rejecting Proposals

Click **Reject** to decline a proposal. You'll be prompted for an optional rejection reason. Providing a clear reason is important because:

- The rejection is recorded in a **rejection history**
- When an AI agent tries to submit the same relationship again, the system automatically declines it and returns the rejection reason
- This teaches agents what relationships are inappropriate, reducing future noise

### 3.5 Maintaining a Healthy Review Cadence

The Node Explorer's schema summary shows the count of **pending proposals**. Aim to keep this near zero. A backlog of unreviewed proposals means AI agents are working with an incomplete graph, and stale proposals may become irrelevant as pages change.

## Step 4: Exploring and Curating Nodes

The **Node Explorer** is your primary tool for understanding and curating the graph's entities.

### 4.1 Browsing and Filtering

- **Search:** Type in the search box to filter nodes by name
- **Type filter:** Select a node type (e.g. `hub`, `article`) to narrow the list
- **Status filter:** Filter by status values present in node properties
- **Pagination:** Navigate through large node sets with Prev/Next buttons

### 4.2 Inspecting Node Details

Click any node row to view its details in the right panel:

- **Properties:** All key-value metadata stored on the node
- **Outbound Edges:** Relationships where this node is the source (this node → target)
- **Inbound Edges:** Relationships where this node is the target (source → this node)
- **Similar by Structure:** Nodes that occupy similar positions in the graph topology (requires trained KG embedding model)
- **Similar by Content:** Nodes whose wiki page content is semantically similar (requires trained content model)

Click any node name in the edge tables to navigate to that node's detail view.

### 4.3 Handling Stub Nodes

Stub nodes appear with a "Yes" in the Stub column and an italic warning in the detail panel. These represent entities that are referenced in frontmatter or body links but don't have their own wiki page. Stubs are normal — they're placeholders that will be fleshed out when a page is created. However, a large number of stubs may indicate:

- **Typos in frontmatter** — a misspelled page name creates an orphaned stub
- **Planned but unwritten pages** — a hub page references articles that haven't been authored yet
- **External concepts** — references to things that may never get their own page

Review stubs periodically. Fix typos, create missing pages, or delete stubs that will never be resolved.

### 4.4 Deleting Nodes

Click **Delete** in the node detail panel to remove a node and all its edges. Use this for:

- Cleaning up typo-created stubs
- Removing test data
- Eliminating obsolete entities after a page is deleted

Deletion is permanent — the node and all its edges are removed from the database.

## Step 5: Exploring and Curating Edges

The **Edge Explorer** lets you browse the relationship layer of the graph.

### 5.1 Browsing Edges

- **Search:** Filter edges by source or target node name
- **Relationship type filter:** Select a specific relationship type (e.g. `related`, `links_to`, `depends_on`)
- **Pagination:** Navigate with Prev/Next buttons

### 5.2 Inspecting Edge Details

Click any edge row to see:

- The full source → relationship → target path
- The provenance badge (human-authored, ai-inferred, or ai-reviewed)
- Detailed cards for both the source and target nodes, including their properties and source pages

### 5.3 Relationship Type Conventions

Maintain consistent relationship types across your wiki. Common patterns:

| Relationship Type | Meaning | Example |
|---|---|---|
| `related` | General topical relationship | `AssetAllocation → UnderstandingRiskTolerance` |
| `links_to` | Body text contains a markdown link (auto-generated) | `RetirementPlanningHub → AiDrivenRetirementPlanning` |
| `depends_on` | Topic B requires understanding of topic A | `PortfolioRebalancing → AssetAllocation` |
| `supersedes` | This page replaces an older one | `NewPolicy → DeprecatedPolicy` |
| `implements` | Describes an implementation of a concept | `OurDeployProcess → ContinuousDeployment` |

Document your conventions and share them with content authors. Consistent relationship types make the graph queryable and meaningful.

## Step 6: Using Structural Embeddings for Graph Improvement

The **KG Embeddings** tab provides three AI-powered tools for improving graph quality. All require a trained structural embedding model.

### 6.1 Predicted Missing Edges

The model scores all *potential* edges (entity pairs not currently connected) and surfaces the highest-scoring predictions — relationships the model believes should exist based on the graph's structure.

Each prediction shows:
- **Source** and **Target** node names
- **Relationship type** the model predicts
- **Score** — higher means the model is more confident

Review each prediction and click **Create** to add the edge to the graph. The edge is created with `human-authored` provenance since you're making the editorial decision.

**Best practice:** Don't blindly accept high-scoring predictions. Verify that the relationship makes semantic sense by checking both pages. The model is finding structural patterns, not reading content — it might predict that two nodes should be connected because they have similar graph neighborhoods, even if the actual topics are unrelated.

### 6.2 Low-Plausibility Edges (Anomaly Detection)

These are *existing* edges that the model considers unlikely given the rest of the graph structure. A low plausibility score suggests the relationship is unusual or potentially incorrect.

Review these edges and ask:

- **Is this a data quality issue?** Perhaps a typo in frontmatter created an edge to the wrong node.
- **Is this genuinely unusual but correct?** Some legitimate relationships are structurally unusual — a cross-domain link between two otherwise unrelated topic clusters, for example.
- **Should this be deleted?** If the edge is clearly wrong, go to the Edge Explorer to delete it, or fix the source page's frontmatter.

### 6.3 Merge Candidates

The model identifies pairs of nodes that may be duplicates based on three similarity scores:

- **Structure** — how similar their graph neighborhoods are (same edges, same neighbors)
- **Content** — how similar their wiki page content is (TF-IDF cosine similarity)
- **Combined** — a weighted blend of structural and content similarity

Click **Merge** to combine two duplicate nodes. Merging:

1. Moves all edges from the source node to the target node
2. Updates all frontmatter references across wiki pages (renames the old name to the new name)
3. Deletes the source node

**Before merging**, verify that the two nodes genuinely represent the same concept. Common legitimate merge scenarios:

- `AssetAllocation` and `[Asset Allocation](AssetAllocation)` (naming inconsistency)
- `REST API` and `RestApi` (different naming conventions)
- A stub node and a fully-realized page node for the same concept

## Step 7: Using Content Embeddings for Page Quality

The **Content Embeddings** tab provides content-level intelligence.

### 7.1 Similar Page Pairs

After training the content model, this table shows the most similar page pairs ranked by TF-IDF cosine similarity. High similarity between two pages may indicate:

- **Duplicate content** that should be merged or deduplicated
- **Overlapping topics** that should cross-reference each other
- **Content that belongs in the same cluster** but isn't tagged that way

Review the top pairs and take action:
- Add `related` frontmatter links between legitimately related pages
- Merge or consolidate truly duplicative pages
- Update cluster assignments to group related content

### 7.2 Pages Without Frontmatter

This table lists all wiki pages that lack YAML frontmatter. These pages are invisible to the knowledge graph — they produce no nodes, no edges, and no properties. Each page name links to the editor so you can add frontmatter.

Prioritize adding frontmatter to:
- **Frequently accessed pages** — these represent important knowledge that should be in the graph
- **Hub pages** — these organize clusters and their relationships are particularly valuable
- **Pages referenced by other pages' frontmatter** — without frontmatter, these are stub nodes instead of full entities

## Step 8: Ongoing Maintenance

A healthy knowledge graph requires periodic attention, not just initial setup.

### 8.1 Weekly Review Checklist

1. **Check the Proposals tab** — approve or reject all pending proposals
2. **Retrain both embedding models** (or verify automatic retraining is running)
3. **Review Predicted Missing Edges** — accept valid predictions to fill gaps
4. **Review Low-Plausibility Edges** — investigate and fix any data quality issues
5. **Review Merge Candidates** — merge confirmed duplicates
6. **Check Similar Page Pairs** — ensure high-similarity pages cross-reference each other
7. **Review Pages Without Frontmatter** — add frontmatter to newly created pages

### 8.2 After Major Content Changes

When you add a new cluster of pages, reorganize existing content, or bulk-edit frontmatter:

1. Click **Project All Pages** in the Node Explorer to refresh the entire graph
2. **Retrain both embedding models** to incorporate the new structure and content
3. Check the Node Explorer for any unexpected stubs (typos in new frontmatter)
4. Review the Edge Explorer to confirm new relationship types are consistent with conventions

### 8.3 Monitoring Graph Health

Use the schema summary in the Node Explorer to track key metrics:

- **Node count** — should grow steadily as content is added
- **Edge count** — should grow proportionally to nodes; a very low ratio may indicate sparse frontmatter
- **Pending proposals** — should stay near zero
- **Node types** and **Relationship types** — watch for unexpected types that indicate inconsistent naming

### 8.4 Re-projection vs. Incremental Updates

The Graph Projector runs automatically on every page save, so the graph stays current incrementally. **Project All Pages** is only needed when:

- Setting up the graph for the first time
- Recovering from a Clear All
- The graph has drifted out of sync due to a bug or database issue
- You've made bulk frontmatter changes outside the normal page save flow

## Step 9: Optimizing the Graph for AI Agents

AI agents interact with the knowledge graph through the MCP server's `propose_knowledge` and `list_proposals` tools. To maximize the value agents get from the graph:

### 9.1 Maintain Rich Frontmatter

The more relationship types and targets you define in frontmatter, the more context agents can discover through graph traversal. A page with only `tags` and `summary` produces a node with properties but no edges to other entities. A page with `related`, `depends_on`, `implements`, and other relationship keys produces a richly connected node that agents can traverse.

### 9.2 Use Descriptive Relationship Types

Agents use relationship types to understand the *nature* of connections, not just their existence. `depends_on` conveys different meaning than `related`, which is different from `supersedes`. Use specific, descriptive relationship types rather than dumping everything into `related`.

### 9.3 Provide Fast Feedback on Proposals

When agents submit proposals and receive timely feedback (approvals or rejections with reasons), they learn what kinds of knowledge are valued. Rejected proposals with clear reasons are especially valuable — the rejection history prevents agents from re-submitting the same incorrect relationships.

### 9.4 Fill Stub Nodes

Stub nodes are dead ends for agent traversal. When an agent follows an edge to a stub node, it finds no properties, no source page to read, and no onward edges. Prioritize creating wiki pages for stubs that appear as targets of many edges — these are clearly important concepts that the graph references but cannot describe.

### 9.5 Keep the Embedding Models Current

Agents benefit from structural similarity data when looking for related concepts. Stale embedding models trained on an old graph version miss new pages and relationships. Configure automatic retraining or retrain manually after significant content changes.

## Step 10: Advanced Configuration

### 10.1 Embedding Model Hyperparameters

The ComplEx structural embedding model can be tuned via `wikantik-custom.properties`:

| Property | Default | Description |
|---|---|---|
| `wikantik.kge.dimension` | 50 | Embedding vector dimensionality. Higher values capture more nuance but use more memory. |
| `wikantik.kge.epochs` | 100 | Number of training iterations. More epochs improve convergence but increase training time. |
| `wikantik.kge.learningRate` | 0.01 | Step size for gradient updates. Lower values train more slowly but more stably. |
| `wikantik.kge.negSamples` | 10 | Negative samples per positive triple. More samples improve discrimination but slow training. |
| `wikantik.kge.margin` | 1.0 | Margin in the ranking loss. Larger margins push positive and negative scores further apart. |
| `wikantik.kge.retrainMinutes` | (disabled) | Interval in minutes for automatic periodic retraining of both models. |

For most wikis, the defaults work well. Consider increasing `dimension` to 100 and `epochs` to 200 if your graph has more than 5,000 nodes and you notice poor prediction quality.

### 10.2 Adding Custom Relationship Types

To introduce a new relationship type, simply start using it in page frontmatter:

```yaml
---
audited_by: [ComplianceTeam, SecurityReview]
---
```

Because `audited_by` is not in the reserved property set and its value is a list of strings, the Graph Projector will automatically create edges of type `audited_by` to each target. No configuration changes are needed — the schema is dynamic.

### 10.3 Reserving New Property Keys

If you need a list-valued frontmatter key to be treated as a property (not a relationship), it must be added to the `PROPERTY_ONLY_KEYS` set in the `FrontmatterRelationshipDetector` class. The current reserved set is: `tags`, `keywords`, `type`, `summary`, `date`, `author`, `cluster`, `status`, `title`, `description`, `category`, `language`.

## Troubleshooting

### Projection produces zero nodes
Check that your pages have valid YAML frontmatter blocks delimited by `---` lines. Pages without frontmatter still produce nodes but with no properties or typed edges.

### Predicted Missing Edges table is empty
The structural embedding model needs at least 2 nodes and some edges to make predictions. Ensure the graph is populated and the model has been trained. Also check that predictions are filtered against existing edges — if all high-scoring pairs already have edges, no predictions appear.

### Proposals keep getting re-submitted by AI
Ensure you're **rejecting** unwanted proposals (not just ignoring them) and providing clear rejection reasons. Only rejected proposals are recorded in the rejection history that prevents re-submission.

### Merge causes unexpected page edits
When you merge node A into node B, the system renames all frontmatter references from A to B across all wiki pages that have edges pointing to A. This is by design — it keeps frontmatter consistent with the graph. Review the merge confirmation dialog carefully before proceeding.

### Stale edges persist after editing frontmatter
The Graph Projector runs a diff on every page save that removes edges no longer present in the frontmatter. If stale edges persist, try re-saving the affected page, or use **Project All Pages** to refresh the entire graph.
