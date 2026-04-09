# Hub Membership and Default Frontmatter Design

**Date:** 2026-04-09
**Status:** Approved

## Problem

Pages without frontmatter are omitted as nodes in the Knowledge Graph, creating gaps in the wiki's structural model. Hub pages exist but membership is manual, one-directional, and has no tooling for discovery or management. There is no mechanism to propose or review Hub membership based on content similarity.

## Goals

1. Every non-system page gets a default frontmatter block sufficient for KG inclusion
2. Hub membership is bidirectional (`hubs` on member pages, `related` on Hub pages) and automatically synchronized
3. Content embeddings power a proposal algorithm that suggests Hub membership for human review
4. A new admin UI tab provides the review workflow with bulk operations
5. A new HubSet plugin renders Hub member lists in wiki pages

## Architecture: Unified Save Pipeline

All frontmatter operations happen in the existing PageFilter chain. The Hub proposal engine is a separate batch process.

```
Page Save
  |
  v
FrontmatterDefaultsFilter (preSave)
  - Injects defaults if no frontmatter present
  |
  v
PageProvider writes content
  |
  v
GraphProjector (postSave, existing)
  - Projects page into KG
  |
  v
HubSyncFilter (postSave)
  - Syncs bidirectional Hub membership
  - Secondary saves use thread-local SUPPRESS_SYNC flag
```

The HubProposalService runs independently as a batch process, triggered manually or after content embedding retraining.

## Section 1: Default Frontmatter Generation

### New Filter: FrontmatterDefaultsFilter

**Type:** PageFilter (preSave), runs before all other filters.

When a page is saved and `FrontmatterParser.parse()` returns empty metadata, the filter generates and injects a default frontmatter block.

### Generated Fields

| Field | Value | Notes |
|-------|-------|-------|
| `title` | Derived from page name | CamelCase/underscore splitting (e.g., `MyPageName` -> `"My Page Name"`) |
| `type` | `"article"` | Fixed default |
| `tags` | Top N TF-IDF keywords | Default N=3, configurable via `wikantik.frontmatter.defaultTags` |
| `summary` | Heuristic sentence extraction | First suitable sentence from body |
| `auto-generated` | `true` | Marks block as not human-reviewed |

### Tag Generation Algorithm

1. Strip markdown formatting from page body
2. Tokenize and remove stopwords
3. Score terms by TF-IDF against the existing corpus (reuses `TfidfModel` from `EmbeddingService`)
4. Select top N terms (default 3)
5. Normalize to lowercase

### Summary Extraction Heuristic

1. Strip frontmatter, markdown formatting (headings, links, images, plugin calls)
2. Split into sentences
3. Select the first sentence between 40 and 200 characters long
4. If no sentence fits the range, take the first sentence and truncate at 200 characters with ellipsis

### Scope

- Applies to new pages, pages saved without frontmatter, and MCP imports (all go through `PageSaveHelper` which triggers the filter chain)
- System pages excluded via `systemPageRegistry.isSystemPage()`
- Pages that already have any frontmatter (even partial — e.g., just `type: hub`) are passed through unchanged. The filter only acts when `FrontmatterParser.parse()` returns completely empty metadata. It does not fill in missing fields on existing frontmatter blocks.

### Configuration

- `wikantik.frontmatter.defaultTags` (int, default: 3) — number of auto-generated tags. Optional in `wikantik-custom.properties`.

## Section 2: Bidirectional Hub Membership Sync

### New Filter: HubSyncFilter

**Type:** PageFilter (postSave), runs after GraphProjector.

Compares current frontmatter against the previously saved version to detect Hub membership changes, then updates the other end.

### Direction 1: Member Page Edited (hubs field changed)

- Diff old and new `hubs: [...]` list
- For each **added** Hub: load Hub page, append this page to `related: [...]` if not present, save
- For each **removed** Hub: load Hub page, remove this page from `related: [...]`, save

### Direction 2: Hub Page Edited (related field changed)

- Only applies when page has `type: hub`
- Diff old and new `related: [...]` list
- For each **added** member: load member page, append this Hub to `hubs: [...]` if not present, save
- For each **removed** member: load member page, remove this Hub from `hubs: [...]`, save

### Recursion Prevention

Secondary saves set a thread-local flag `HubSyncFilter.SUPPRESS_SYNC`. When set, the filter skips processing. The secondary save still triggers `GraphProjector` so the KG stays current.

### Edge Cases

- **Target page doesn't exist:** Skip sync; resolves when that page is eventually saved
- **Target page has no frontmatter:** Only affects pages not yet saved since the feature was introduced; handled by the backfill (Section 6)
- **No-op changes:** If the target already has the correct state (e.g., Hub already lists the page), no save is performed

### KG Integration

`hubs` is added to the relationship keys in `FrontmatterRelationshipDetector`, so Hub membership automatically becomes KG edges of type `hubs`.

## Section 3: Hub Membership Proposal Algorithm

### New Service: HubProposalService

A batch process that computes Hub membership proposals using TF-IDF content embeddings.

### Step 1: Compute Hub Centroid Embeddings

- For each page with `type: hub`, gather content embeddings of all current `related` member pages from `kg_content_embeddings`
- Average into a single centroid vector per Hub
- Store in `hub_centroids` table
- Hubs with fewer than 2 members are skipped (insufficient signal)

### Step 2: Score Candidate Pages

- For every non-Hub page with a content embedding, compute cosine similarity against every Hub centroid
- Skip pages already members of a given Hub
- Produces a matrix of (page, hub, raw_similarity) triples

### Step 3: Percentile Normalization

- Collect all raw similarity scores across the full matrix
- Convert each to its percentile rank (0-100) within the distribution
- A score of 97 means "more similar to this Hub than 97% of all page-Hub combinations"

### Step 4: Apply Review Threshold

- Proposals at or above the review threshold (default: 90th percentile) are written to the review queue with status `pending`
- Proposals below the threshold are discarded

### Step 5: Write Proposals

- Written to `hub_proposals` table
- Duplicate check: skip if (hub_name, page_name) pair already exists with status `pending`, `approved`, or `rejected`
- Rejected proposals are never re-proposed (rejection history is the proposal record itself)

### Trigger Mechanisms

- **Manual:** Admin clicks "Generate Hub Proposals" in the Hub Proposals tab
- **Scheduled:** Runs after content embedding retrain completes
- Both require the TF-IDF content model to be trained; returns error if not ready

### Configuration

- `wikantik.hub.reviewPercentile` (int, default: 90) — minimum percentile for review queue. Optional in `wikantik-custom.properties`.

## Section 4: Hub Proposals Admin UI Tab

New sixth tab in AdminKnowledgePage.jsx, after "Content Embeddings."

### Top Bar

- "Generate Hub Proposals" button (disabled with tooltip if content embeddings aren't trained)
- Status line: last run timestamp, number of pending proposals, review threshold percentile

### Proposal Table

| Column | Notes |
|--------|-------|
| Checkbox | For bulk selection |
| Hub Name | Clickable link to Hub page |
| Page Name | Clickable link to member page |
| Percentile Score | 0-100, default sort descending |
| Raw Similarity | Cosine similarity value |
| Created Date | When proposal was generated |

- Sortable by any column
- Filterable by Hub name (dropdown)
- Paginated (consistent with existing admin tabs)

### Bulk Operations

- "Approve Selected" button — applies membership for all checked proposals
- "Reject Selected" button — opens shared reason text field, rejects all checked
- Threshold input: "Approve all proposals above ___%" with "Apply" button — selects and approves all pending proposals at or above the entered percentile
- Live count indicator: "X proposals match" updates as threshold is adjusted

### Individual Row Actions

- Approve (checkmark icon) — immediate, updates frontmatter on both pages via normal save path
- Reject (X icon) — prompts for optional reason

### Approval Side-Effects

1. Member page frontmatter updated: Hub added to `hubs: [...]`
2. `HubSyncFilter` handles the reverse (page added to Hub's `related: [...]`)
3. Proposal status set to `approved`, `reviewed_by` and `reviewed_at` recorded

### Rejection Side-Effects

1. Proposal status set to `rejected` with reason
2. Future proposal runs skip this (hub_name, page_name) pair

## Section 5: HubSet Plugin

### New Class: HubSetPlugin extends AbstractReferralPlugin

Produces the set of all pages belonging to a named Hub.

### Parameters

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `hub` | yes | — | Hub page name |
| `max` | no | unlimited | Maximum rows to output |
| `detail` | no | `"links"` | Output mode: `"links"` or `"cards"` |

Inherited from AbstractReferralPlugin: `separator`, `before`, `after`, `exclude`, `include`, `sortOrder`, `columns`, `maxwidth`

### Output Mode: links (default)

Renders clickable wiki links to each member page. Uses all AbstractReferralPlugin formatting (column layout, separators, sort order, include/exclude regex patterns).

### Output Mode: cards

For each member page, reads frontmatter and renders:
- Page title (linked)
- Summary (from frontmatter)
- Tags (rendered as labels/badges)

Wrapped in CSS class `hub-set-cards` for styling. Respects `max`, `exclude`, `include`, and `sortOrder`.

### Behavior

- If `hub` names a page that doesn't exist or isn't `type: hub`: returns inline error message
- If the Hub has no members (or all filtered out): returns message *"Hub 'X' has no member pages."* styled with CSS class `hub-set-empty`
- `max` applied after filtering and sorting

### Wiki Markup

```
[{HubSet hub='Technology' max='10' detail='cards'}]
```

## Section 6: Backfill and Migration

### Frontmatter Backfill

**Endpoint:** `POST /admin/knowledge/backfill-frontmatter`

- Iterates all wiki pages (excluding system pages)
- For each page without frontmatter: generates defaults using the same logic as `FrontmatterDefaultsFilter`, saves the page (triggering normal postSave chain)
- Returns count: pages processed, pages skipped (already had frontmatter), pages errored
- Requires TF-IDF content model to be trained (returns error if not ready)

**Async execution:** Runs in background, progress available via `GET /admin/knowledge/backfill-frontmatter/status` returning `{total, processed, errors, running}`.

**UI location:** Button in the "Content Embeddings" tab, next to the "Pages Without Frontmatter" list header. Includes confirmation dialog and progress bar.

### Hub Membership Sync Bootstrap

Existing Hub pages have `related` lists but member pages lack `hubs` fields.

**Endpoint:** `POST /admin/knowledge/sync-hub-memberships`

Triggers a save-cycle for each Hub page, which causes `HubSyncFilter` to propagate `hubs` fields onto all member pages.

**UI location:** Button in the "Hub Proposals" tab top bar, alongside "Generate Hub Proposals."

## Section 7: Data Model Changes

### New Table: hub_centroids

```sql
CREATE TABLE hub_centroids (
    id          SERIAL PRIMARY KEY,
    hub_name    VARCHAR(255) NOT NULL UNIQUE,
    centroid    VECTOR NOT NULL,
    model_version VARCHAR(100) NOT NULL,
    member_count INT NOT NULL,
    created     TIMESTAMP NOT NULL DEFAULT NOW()
);
```

### New Table: hub_proposals

```sql
CREATE TABLE hub_proposals (
    id              SERIAL PRIMARY KEY,
    hub_name        VARCHAR(255) NOT NULL,
    page_name       VARCHAR(255) NOT NULL,
    raw_similarity  DOUBLE PRECISION NOT NULL,
    percentile_score DOUBLE PRECISION NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'pending',
    reason          TEXT,
    reviewed_by     VARCHAR(255),
    reviewed_at     TIMESTAMP,
    created         TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (hub_name, page_name)
);
```

### Frontmatter Field Changes

- `hubs` added to relationship keys in `FrontmatterRelationshipDetector` (List\<String\> values become KG edges of type `hubs`)
- `auto-generated` added to `PROPERTY_ONLY_KEYS` (never becomes a KG edge)

### Configuration Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wikantik.frontmatter.defaultTags` | int | 3 | Number of auto-generated tags |
| `wikantik.hub.reviewPercentile` | int | 90 | Minimum percentile for proposal review queue |

### No Changes to Existing Tables

`kg_content_embeddings`, `kg_nodes`, `kg_edges`, `kg_proposals`, and `kg_rejections` are used as-is.

## New REST Endpoints Summary

| Method | Path | Description |
|--------|------|-------------|
| POST | `/admin/knowledge/backfill-frontmatter` | Trigger async frontmatter backfill |
| GET | `/admin/knowledge/backfill-frontmatter/status` | Backfill progress |
| POST | `/admin/knowledge/sync-hub-memberships` | Bootstrap bidirectional Hub links |
| POST | `/admin/knowledge/hub-proposals/generate` | Trigger proposal batch |
| GET | `/admin/knowledge/hub-proposals?status=pending&hub=X` | List proposals (paginated) |
| POST | `/admin/knowledge/hub-proposals/{id}/approve` | Approve proposal |
| POST | `/admin/knowledge/hub-proposals/{id}/reject` | Reject proposal |
| POST | `/admin/knowledge/hub-proposals/bulk-approve` | Bulk approve by IDs |
| POST | `/admin/knowledge/hub-proposals/bulk-reject` | Bulk reject by IDs |
| POST | `/admin/knowledge/hub-proposals/threshold-approve` | Approve all above percentile |

## Component Summary

| Component | Module | Type |
|-----------|--------|------|
| `FrontmatterDefaultsFilter` | wikantik-main | PageFilter (preSave) |
| `HubSyncFilter` | wikantik-main | PageFilter (postSave) |
| `HubProposalService` | wikantik-main | Service (batch) |
| `HubSetPlugin` | wikantik-main | Plugin (extends AbstractReferralPlugin) |
| `HubProposalsTab.jsx` | wikantik-frontend | React component |
| Hub proposal REST endpoints | wikantik-rest | AdminServlet extensions |
| DDL for hub_centroids, hub_proposals | wikantik-war | SQL migration |
