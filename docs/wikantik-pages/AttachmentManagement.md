---
summary: Drag-and-drop file attachment UI with server-side image processing and inline Markdown references
tags:
- development
- attachments
- ui
- file-management
type: article
status: deployed
cluster: wikantik-development
date: '2026-04-03'
related:
- WikantikDevelopment
- BlogFeature
- JspToReactMigration
depends-on:
- JspToReactMigration
---
# Attachment Management

The attachment management feature modernizes file handling with a drag-and-drop upload interface, server-side image processing, and automatic Markdown image reference insertion.

## Features

- Drag-and-drop file upload with progress indicators
- Server-side image thumbnail generation
- Filename validation and sanitization
- Inline Markdown reference insertion (`![alt](attachment:filename)`)
- Attachment listing with delete capability

## Implementation

The upload endpoint is in `wikantik-rest`, with the React UI component handling the drag-and-drop interaction and progress display. The `AttachmentManager` in `wikantik-main` handles storage through the provider abstraction.

[{Relationships}]
