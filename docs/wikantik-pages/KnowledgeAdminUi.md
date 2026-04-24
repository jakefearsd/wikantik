---
canonical_id: 01KQ0P44RG9F6EFB0MPVN81K6S
summary: Three-tab admin panel for reviewing proposals, browsing nodes, and exploring edges
tags:
- development
- knowledge-graph
- admin
- ui
- react
type: article
status: deployed
cluster: wikantik-development
date: '2026-04-04'
part-of:
- KnowledgeGraphCore
depends-on:
- AdminSecurityUi
- KnowledgeProposals
---
# Knowledge Admin UI

The knowledge admin panel is accessible at `/admin/knowledge` and provides three views for managing the knowledge graph.

## Proposals Tab

A review queue showing pending AI proposals with confidence scores and reasoning. Reviewers can approve or reject each proposal. Approvals trigger node/edge creation and frontmatter write-back. Rejections record negative knowledge.

## Node Explorer

A browsable list of all graph nodes with filters for node type, status, and text search. Selecting a node shows its properties, provenance, and all inbound/outbound edges with resolved names. Edge targets are clickable for graph navigation.

## Edge Explorer

A dedicated view for browsing all edges in the graph. Supports filtering by relationship type, searching by source or target name, and pagination. Each edge shows source name, relationship type, target name, and provenance.

## Access Control

The admin panel is protected by `AdminAuthFilter` which requires `AllPermission`. All REST endpoints under `/admin/knowledge/*` enforce the same restriction.

[{Relationships}]
