---
tags:
- frontend
- development
- ui-ux
- react
date: '2026-05-15'
canonical_id: 01KQ0P44T1NDZ71D6WFZZKPQ3A
summary: Wikantik Reader UI - Implementation Plan for a modern, React-based reading
  experience.
status: active
cluster: wikantik-development
type: article
---
# Wikantik Reader UI - Implementation Plan

A modern, Medium-inspired reading experience built with React.

## Overview

Build a standalone React SPA that consumes Wikantik's existing REST API, providing a modern reading experience while the traditional JSP templates handle editing/administration.

## Architecture

### Core Concept
- **Standalone React SPA**
- Consumes Wikantik REST API (`/api/`)
- Clean separation of concerns between presentation and storage.

### Technology Stack

| Layer | Choice | Rationale |
|-------|--------|-----------|
| Framework | React 18 | Industry standard, large ecosystem |
| Build | Vite | Fast HMR, modern defaults |
| Language | TypeScript | Type safety, better DX |
| State | Zustand | Lightweight, simple API |
| Data Fetching | TanStack Query | Caching, background refetch |
| Styling | Vanilla CSS | No runtime cost, maximum flexibility |

-----

## Component Design

### Header (responsive)
- Transparent initially, solid on scroll.
- Hides when scrolling down, reveals on scroll up.
- Progress bar along bottom edge.

### Article Header
- Title (h1) and Subtitle.
- Metadata: Author, Reading Time, and Update Date.
- Full-width Hero Image.

-----

## Wiki Content Processing

To bridge the gap between static HTML and a dynamic React app, the parser must:
1.  **Transform Links**: Convert standard wiki links to React Router internal links.
2.  **Lazy Load**: Automatically add `loading="lazy"` to all images.
3.  **Horizontal Scroll**: Wrap tables in a scrollable `div` container for mobile responsiveness.

---
**See Also:**
- [REST API Reference](README)
- [McpIntegration](McpIntegration) — For the agent-grade tool layer.
