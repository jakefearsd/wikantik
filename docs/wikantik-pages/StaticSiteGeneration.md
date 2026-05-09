---
cluster: frontend-development
canonical_id: 01KQ0P44WXXZHGMPJT9024QS5R
title: Static Site Generation
type: article
tags:
- ssg
- isr
- frontend
- performance
summary: Exploration of modern Static Site Generation (SSG), Incremental Static Regeneration (ISR), and hybrid rendering patterns.
auto-generated: false
date: 2025-05-15
---

# Static Site Generation (SSG) and Hybrid Rendering

Static Site Generation (SSG) pre-renders a website into static HTML at build time. While traditional SSG is limited by build scaling, modern frameworks introduce hybrid patterns like **Incremental Static Regeneration (ISR)** to handle massive content scale.

## 1. Traditional SSG vs. The Build Time Wall

Traditional SSG (e.g., Hugo, Jekyll) renders every page on every build.
*   **Pros:** Maximum performance (CDN delivery), high security (no database at runtime), zero infrastructure cost.
*   **Cons:** Build times grow linearly with the number of pages. For 100k+ pages, a full rebuild can take hours.

## 2. Incremental Static Regeneration (ISR)

ISR (popularized by Next.js) allows you to create or update static pages *after* you’ve built your site. It combines the benefits of static sites with the flexibility of server-side rendering.

### 2.1 The Revalidation Lifecycle
1.  **Initial Build:** The site is built with a subset of pages.
2.  **Stale Request:** A user requests a page. If the "revalidate" period has passed, the server serves the *stale* static page but triggers a background re-generation.
3.  **Background Update:** The server fetches fresh data and re-renders the HTML.
4.  **Fresh Cache:** The next user to request the page receives the updated static version.

## 3. Hybrid Patterns: ISR and SSG

Modern architectures (like Astro or Next.js) allow mixing rendering modes per-route:

*   **Static (SSG):** For content that rarely changes (About, Privacy Policy).
*   **Incremental (ISR):** For massive content sets (Product pages, Blog posts).
*   **On-Demand (SSR):** For personalized or real-time data (User profiles, Dashboards).

## 4. Practitioner Insights

### 4.1 The "Fallback" Strategy
When using ISR for dynamic routes, use a `fallback` state (e.g., `fallback: 'blocking'` or a loading skeleton) to ensure that users requesting newly created content (not in the initial build) don't receive a 404.

### 4.2 Cache Invalidation (On-Demand ISR)
Instead of waiting for a timer, use **On-Demand Revalidation**. Trigger a webhook from your CMS to purge the cache and re-generate a specific page the moment it is updated.

### 4.3 Islands Architecture (Astro)
In hybrid SSG, use the **Islands Architecture** to keep the HTML static while only hydrating specific interactive widgets. This minimizes the "JavaScript tax" on initial load.
