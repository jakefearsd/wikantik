---
cluster: wikantik-development
type: article
tags:
- frontend
- development
- ui-ux
- react
title: Reader UI Redesign
summary: Wikantik Reader UI - Implementation Plan for a modern, React-based reading
  experience.
canonical_id: 01KQ0P44T1NDZ71D6WFZZKPQ3A
status: active
date: '2026-05-15'
---
# Wikantik Reader UI - Implementation Plan

> **Status (2026-10-13):** Implemented, and the plan's premise has been superseded. The reader is not a separate app beside JSP templates: the JSP templates are gone and one React SPA serves reading, editing and the admin panel from `/` (see [RemoveJspAndAppPrefix](RemoveJspAndAppPrefix)). The shipped stack differs from the table below: React 19, plain JavaScript (JSX, no TypeScript), React Router 7, Vite 8, hand-written token-based CSS, and React state and hooks with no Zustand or TanStack Query. `docs/developer/FrontendArchitecture.md` in the repository describes the current frontend.

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
- [Finding the right REST endpoint](AnsweringRestApiQuestions) — how to locate the `/api/*` endpoint behind a question.
- [McpIntegration](McpIntegration) — For the agent-grade tool layer.
