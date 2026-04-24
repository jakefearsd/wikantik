# Wiki Indexing Support — Implementation Reference

**Status:** Implemented. This document describes the set of endpoints and filters that expose raw wiki content to search-engine crawlers, RAG ingestion pipelines, and other non-SPA consumers. It was originally written as a requirements spec; it is now a reference for the implemented surface.

## Problem it solved

`wiki.jakefear.com` is a React SPA backed by Apache Tomcat 11. Without server-side rendering or raw-content endpoints, page URLs returned the SPA shell regardless of crawler intent, which blocked:

- Search engines (Googlebot, Bingbot) from indexing page content
- OpenWebUI's web loader from fetching page content for RAG
- Any programmatic bulk ingestion of wiki content

## Implemented surface

### 1. Raw content endpoint — `GET /wiki/{slug}?format={md|json}`

Implemented by **`com.wikantik.rest.WikiPageFormatFilter`** (web.xml-mapped on `/wiki/*`, declared before `SpaRoutingFilter`).

| `format` value | Response `Content-Type` | Response body |
|---------------|------------------------|---------------|
| `md` | `text/markdown; charset=UTF-8` | Page body as clean Markdown, H1 at top |
| `json` | `application/json; charset=UTF-8` | JSON object (see schema below) |
| *(absent or other)* | passed through to `SpaRoutingFilter` | Normal SPA shell (unchanged) |

**JSON schema (`?format=json`):**

```json
{
  "slug": "LinuxSystemAdministration",
  "title": "Linux System Administration",
  "content": "# Linux System Administration\n\nFull page body in Markdown...",
  "summary": "First paragraph or frontmatter summary, max 300 chars",
  "tags": ["linux", "sysadmin"],
  "created_at": "2025-11-14T09:23:00Z",
  "modified_at": "2026-04-02T14:05:00Z"
}
```

Markdown output invariants:

- Page title as H1 at the top
- Body content only — no navigation, sidebar, footer, "related pages"
- Internal wiki links preserved as absolute URLs: `[Page Title](https://wiki.jakefear.com/wiki/PageSlug)`
- Images resolved to absolute URLs
- No injected boilerplate text

### 2. Changes feed — `GET /api/changes?since={ISO8601_datetime}`

Implemented by **`com.wikantik.rest.ChangesResource`** (under `/api/`, same auth and ACL rules as other REST resources).

**Response (`application/json`):**

```json
{
  "since": "2026-04-01T00:00:00Z",
  "generated_at": "2026-04-11T12:00:00Z",
  "pages": [
    {
      "slug": "LinuxSystemAdministration",
      "modified_at": "2026-04-02T14:05:00Z",
      "url": "https://wiki.jakefear.com/wiki/LinuxSystemAdministration"
    }
  ]
}
```

If `since` is omitted, returns all pages (full-export mode).

### 3. Crawler-friendly rendering

Implemented via **`com.wikantik.ui.SemanticHeadRenderer`** and **`com.wikantik.rest.SpaRoutingFilter`**. Detection is **`Accept`-header based** (not User-Agent), which is simpler and more robust against UA spoofing:

- Requests that accept `text/html` but don't request JS-executing content (i.e. traditional crawler signatures sending `Accept: */*` or omitting the header) receive a server-rendered no-JS HTML fallback with:
  - `<title>`, `<meta name="description">`, `<link rel="canonical">`
  - Open Graph (`og:title`, `og:description`, `og:url`, `og:type`)
  - JSON-LD `schema.org/Article` structured data
  - Full page body as rendered HTML (headings, paragraphs, lists) — crawlers can index the actual content, not just meta tags
- Browsers sending modern `Accept` headers continue to receive the SPA shell unchanged

This is a deliberate design choice over the User-Agent sniffing originally sketched — see the comment at `SpaRoutingFilter.java:152` ("crawlers which send `Accept: */*` or omit the header") for the rationale.

### 4. Sitemap

Implemented by **`com.wikantik.ui.SitemapServlet`** at `/sitemap.xml`:

- `<lastmod>` reflects actual page modification time (not deploy time)
- All visible pages are enumerated (ACL-filtered)
- Sitemap is referenced in `/robots.txt`

See [docs/Sitemap.md](docs/Sitemap.md) and [docs/SitemapOptimization.md](docs/SitemapOptimization.md) for tuning details.

## Integration contract (OpenWebUI sync)

The sync script consumes these endpoints as follows:

```
1. GET /sitemap.xml
   → Parse all <loc> and <lastmod> values

2. Compare lastmod values against previously indexed timestamps
   → Identify new or changed pages

3. For each changed page:
   GET /wiki/{slug}?format=json
   → Extract title + content
   → Upsert into OpenWebUI knowledge base

4. Periodically:
   GET /api/changes?since={last_run_timestamp}
   → Faster than full sitemap diff
```

The sync script itself lives in the OpenWebUI host repo; it calls Wikantik's `/wiki/{slug}?format=json` and `/api/changes` endpoints and pushes into OpenWebUI's `/api/v1/knowledge/` and `/api/rag/process/file`.

## Related

- **Agent-facing context**: For AI coding agents that consume this wiki as a knowledge base, prefer the `/knowledge-mcp` MCP server (hybrid retrieval + knowledge graph) over `GET /wiki/{slug}?format=*`, which is optimised for bulk ingestion rather than per-query retrieval. See [docs/wikantik-pages/AgentGradeContentDesign.md](docs/wikantik-pages/AgentGradeContentDesign.md) for the planned `/api/pages/{id}/for-agent` projection specifically shaped for agent token-efficiency.
- **Structural discovery**: Bulk ingestion tools that want to understand wiki structure before paging through `/api/changes` should also consume `/api/structure/*` once implemented — see [docs/wikantik-pages/StructuralSpineDesign.md](docs/wikantik-pages/StructuralSpineDesign.md).
- **Sitemap and Atom feeds**: [docs/Sitemap.md](docs/Sitemap.md)
