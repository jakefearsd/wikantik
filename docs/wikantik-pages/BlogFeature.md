---
status: deployed
depends-on:
- JspToReactMigration
summary: Blog subsystem with date-prefixed directory storage, composable plugins,
  and split-view editor
tags:
- development
- blog
- content-management
- ui
type: article
cluster: wikantik-development
related:
- WikantikDevelopment
- AttachmentManagement
- JspToReactMigration
date: '2026-04-03'
documents:
- About
---
# Blog Feature

The blog feature adds a time-ordered content stream to the wiki, with entries stored as wiki pages under a dedicated directory structure using YYYYMMDD date prefixes.

## Design

Blog entries are wiki pages with special frontmatter fields (`synopsis`, `author`, `date`) stored in a configurable blog directory. The `BlogPlugin` renders entries as a chronological feed, and the `BlogEditorPlugin` provides a split-view Markdown editor for drafting entries.

## Storage

Entries are stored as regular wiki pages with a naming convention: `Blog/YYYYMMDD-Title.md`. The date prefix enables efficient chronological querying without additional database tables.

[{Relationships}]
