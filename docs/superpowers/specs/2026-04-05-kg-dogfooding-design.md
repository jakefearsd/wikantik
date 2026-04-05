# Knowledge Graph Dogfooding — Design Spec

**Date:** 2026-04-05
**Status:** Approved

## Summary

Document the Knowledge Graph system and all Wikantik development features as wiki pages with rich frontmatter relationships, then extend the system to make that graph immediately useful for development. Three workstreams: content (wiki pages), a plugin (RelationshipsPlugin), and a UI enhancement (status filtering in Node Explorer).

## Motivation

The wiki should be the source of truth for what exists, what's being built, and what's planned. Rich frontmatter relationships create a navigable graph that reduces the need for expensive code exploration. New ideas start as wiki pages and flow through the design/plan/build lifecycle. AI agents can query the graph to understand the system before diving into code.

## Status Vocabulary

All development cluster pages use a `status` frontmatter field tracking lifecycle progression:

| Status | Meaning |
|--------|---------|
| `idea` | Concept only, no spec |
| `designed` | Has a design spec |
| `planned` | Has spec + implementation plan |
| `active` | Currently being built |
| `deployed` | Running in the system |

`status` is already in `PROPERTY_ONLY_KEYS` in `FrontmatterRelationshipDetector`, so it is stored as a node property, not treated as an edge.

## Relationship Types

Existing: `related`, `depends-on`

New: `part-of`, `enables`, `supersedes`

These are not in `PROPERTY_ONLY_KEYS`, so the FrontmatterRelationshipDetector will automatically treat list values under these keys as edges. No code changes needed to the detector.

| Type | Meaning | Example |
|------|---------|---------|
| `related` | General association | KnowledgeGraphCore ↔ McpIntegration |
| `depends-on` | Requires this to function | AdminSecurityUi → DatabaseBackedPermissions |
| `part-of` | Is a sub-feature of | GraphProjector → KnowledgeGraphCore |
| `enables` | Unlocks this capability | DatabaseBackedPermissions → AdminSecurityUi |
| `supersedes` | Replaces this | RemoveJspAndAppPrefix → JspToReactMigration |

## Phase 1a: KG Decomposition

Restructure `KnowledgeGraphCore.md` as a hub page. Extract detailed content into 5 focused sub-feature pages:

### New Pages

**GraphProjector.md**
- Content: PageFilter sync mechanism, upsert logic, stale edge removal, idempotent re-projection
- Frontmatter:
  - `status: deployed`
  - `type: article`
  - `cluster: wikantik-development`
  - `part-of: [KnowledgeGraphCore]`
  - `depends-on: [FrontmatterConventions]`

**KnowledgeProposals.md**
- Content: Proposal/approval/rejection workflow, frontmatter write-back on approval, rejection memory preventing re-proposals
- Frontmatter:
  - `status: deployed`
  - `type: article`
  - `cluster: wikantik-development`
  - `part-of: [KnowledgeGraphCore]`
  - `depends-on: [ProvenanceModel]`

**KnowledgeAdminUi.md**
- Content: Three-tab admin panel (Proposals, Node Explorer, Edge Explorer), proposal review workflow, graph browsing
- Frontmatter:
  - `status: deployed`
  - `type: article`
  - `cluster: wikantik-development`
  - `part-of: [KnowledgeGraphCore]`
  - `depends-on: [AdminSecurityUi, KnowledgeProposals]`

**ProvenanceModel.md**
- Content: Three-tier trust model (human-authored, ai-inferred, ai-reviewed), provenance filtering in consumption MCP, upgrade semantics on approval
- Frontmatter:
  - `status: deployed`
  - `type: article`
  - `cluster: wikantik-development`
  - `part-of: [KnowledgeGraphCore]`

**FrontmatterConventions.md**
- Content: PROPERTY_ONLY_KEYS, relationship detection rules, how list-valued keys become edges, the status vocabulary, new relationship types
- Frontmatter:
  - `status: deployed`
  - `type: article`
  - `cluster: wikantik-development`
  - `part-of: [KnowledgeGraphCore]`

### Updated Page: KnowledgeGraphCore.md

Restructured as a hub page with brief overview. Detailed content moved to sub-pages.

- `status: active` → `status: deployed`
- Add `related` links to all 5 sub-pages and McpIntegration
- Add `depends-on: [McpIntegration]`

## Phase 1b: Existing Page Updates

All 8 existing cluster pages updated with corrected statuses and richer relationships:

| Page | Status | Relationship Updates |
|------|--------|---------------------|
| `WikantikDevelopment.md` | → `deployed` | No relationship changes |
| `JspToReactMigration.md` | → `deployed` | Add `enables: [AdminSecurityUi, KnowledgeAdminUi, BlogFeature]` |
| `DatabaseBackedPermissions.md` | → `deployed` | Add `enables: [AdminSecurityUi]` |
| `AdminSecurityUi.md` | → `deployed` | Add `enables: [KnowledgeAdminUi]` |
| `McpIntegration.md` | → `deployed` | Add `depends-on: [KnowledgeGraphCore]` |
| `BlogFeature.md` | → `deployed` | Add `depends-on: [JspToReactMigration]` |
| `AttachmentManagement.md` | → `deployed` | Add `depends-on: [JspToReactMigration]` |
| `KnowledgeGraphCore.md` | → `deployed` | See decomposition section above |

## Phase 2: Missing Feature Backfill

9 new pages for features that have specs/plans but no wiki representation.

### Status: deployed

**BlogEditorSplitView.md**
- Content: Live preview editor for blog entries, split-view Markdown editing
- Frontmatter: `status: deployed`, `part-of: [BlogFeature]`

**TestStubConversion.md**
- Content: StubPageManager, StubSystemPageRegistry, StubReferenceManager for test isolation
- Frontmatter: `status: deployed`, `related: [ConstructorInjection]`

**ConstructorInjection.md**
- Content: Testability refactor from service locator to constructor dependency injection
- Frontmatter: `status: deployed`, `related: [TestStubConversion]`

**McpIntegrationTestFix.md**
- Content: Fixed WikiEngine lazy initialization, failsafe reporting for integration tests
- Frontmatter: `status: deployed`, `related: [McpIntegration]`

**UserProfileBio.md**
- Content: Bio field on user profiles
- Frontmatter: `status: deployed`

**WikiToMarkdownConverter.md**
- Content: Migration tool from legacy wiki syntax to Markdown
- Frontmatter: `status: deployed`, `enables: [BlogFeature, KnowledgeGraphCore]`

**RemoveJspAndAppPrefix.md**
- Content: Final JSP removal, single SPA entry point
- Frontmatter: `status: deployed`, `depends-on: [JspToReactMigration]`, `supersedes: [JspToReactMigration]`

### Status: designed

**WikiAuditSkill.md**
- Content: Automated content quality auditing concept
- Frontmatter: `status: designed`, `depends-on: [McpIntegration]`

**McpAuditTools.md**
- Content: MCP tools that would power the audit skill
- Frontmatter: `status: designed`, `part-of: [McpIntegration]`, `enables: [WikiAuditSkill]`

All pages get `type: article`, `cluster: wikantik-development`, and appropriate `date` fields.

## Phase 3a: RelationshipsPlugin

A wiki plugin that renders a page's knowledge graph relationships as navigable links.

### Usage

```markdown
[{Relationships}]
```

Embeddable anywhere in page content. Added to the bottom of all pages created/updated in this work.

### Rendered Output

For a page like GraphProjector:

```
Relationships

Part of: KnowledgeGraphCore
Depends on: FrontmatterConventions
Related: McpIntegration
```

Each name is a wiki link.

### Behavior

- Looks up current page's node in the knowledge graph by page name
- Fetches all edges (outbound and inbound) via `KnowledgeGraphService`
- Groups by relationship type
- Outbound edges use the relationship type as label
- Inbound edges use inverted labels:

| Relationship | Outbound label | Inbound label |
|-------------|---------------|---------------|
| `depends-on` | Depends on | Dependency of |
| `part-of` | Part of | Parts |
| `enables` | Enables | Enabled by |
| `supersedes` | Supersedes | Superseded by |
| `related` | Related | Related |

- If the page has no node or no edges, renders nothing
- Renders inside a styled `<div>` consistent with existing wiki plugin output

### Implementation

- New class `RelationshipsPlugin` in `wikantik-main`
- Extends existing plugin base class
- Uses `KnowledgeGraphService.getEdgesForNode()` for edge retrieval
- Uses `KnowledgeGraphService.getNodeNames()` for batch UUID-to-name resolution
- No parameters needed initially

## Phase 3b: Status Filtering in Node Explorer

### Frontend: GraphExplorer.jsx

- Add a status dropdown alongside the existing node type dropdown
- Values populated from the graph schema (picks up `deployed`, `designed`, `planned`, etc. automatically)
- Default: show all
- Selecting a status filters the node list by passing `status` as a query parameter

### Backend: AdminKnowledgeResource.java

- The node listing endpoint accepts an optional `status` query parameter
- Passes it through to the repository

### Backend: JdbcKnowledgeRepository.java

- Node query method accepts optional `status` parameter
- Filters by matching `status` in the node's JSONB `properties` column: `WHERE properties->>'status' = ?`

## Verification

1. Build: `mvn clean install -T 1C -DskipITs`
2. Deploy and verify:
   - All new/updated pages load correctly
   - GraphProjector creates nodes and edges for all new pages
   - `[{Relationships}]` plugin renders relationship links on each page
   - Node Explorer status dropdown filters nodes correctly
   - Edge Explorer shows new relationship types (part-of, enables, supersedes)
   - Knowledge graph node/edge counts increase as expected
