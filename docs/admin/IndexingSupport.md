# Wiki Indexing Support — Implementation Reference

This reference is for operators and integrators who feed wiki content to search-engine crawlers, RAG ingestion pipelines, or other non-SPA consumers. It describes the endpoints and filters that expose raw and server-rendered content, and how to call them.

## Problem it solved

`wiki.wikantik.com` is a React SPA backed by Apache Tomcat 11. Without server-side rendering or raw-content endpoints, page URLs returned the SPA shell regardless of crawler intent, which blocked:

- Search engines (Googlebot, Bingbot) from indexing page content
- OpenWebUI's web loader from fetching page content for RAG
- Any programmatic bulk ingestion of wiki content

## Implemented surface

### 1. Raw content endpoint — `GET /wiki/{slug}?format={md|json}`

Implemented by **`com.wikantik.rest.WikiPageFormatFilter`** (web.xml-mapped on `/wiki/*`, declared before `SpaRoutingFilter`).

| `format` value | Response `Content-Type` | Response body |
|---------------|------------------------|---------------|
| `md` | `text/markdown; charset=UTF-8` | Page body as Markdown, H1 at top |
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

Markdown output:

- The page title is the H1 at the top (a leading H1 in the body is not repeated).
- Body content only — no navigation, sidebar, footer or related-pages list.
- Internal wiki links and attachment links are rewritten to absolute URLs under `wikantik.baseURL`.

Behaviour that applies to both formats:

- The view ACL is enforced against the caller's session. An unknown page or one the caller cannot view returns `404`.
- Responses carry `X-Robots-Tag: noindex` and a `Link: <.../wiki/{slug}>; rel="canonical"` header, so the raw representations do not compete with the HTML page in search results.
- A raw read counts as a page view in the `wikantik.page.views` metric.

### 2. Changes feed — `GET /api/changes?since={ISO8601_datetime}`

Implemented by **`com.wikantik.rest.ChangesResource`** (under `/api/`, same auth and ACL rules as other REST resources).

An invalid `since` value returns `400`. **Response (`application/json`):**

```json
{
  "since": "2026-04-01T00:00:00Z",
  "generated_at": "2026-04-11T12:00:00Z",
  "pages": [
    {
      "slug": "LinuxSystemAdministration",
      "modified_at": "2026-04-02T14:05:00Z",
      "url": "https://wiki.wikantik.com/wiki/LinuxSystemAdministration"
    }
  ]
}
```

If `since` is omitted, the feed lists every page the page manager reports as changed (full-export mode). `since` is `null` in that response.

### 3. Crawler-friendly rendering

Implemented via **`com.wikantik.ui.SemanticHeadRenderer`** and **`com.wikantik.rest.SpaRoutingFilter`**. Every `/wiki/{slug}` request, whatever its `Accept` header, is served the SPA shell with a server-rendered head and body, so crawlers that send `Accept: */*` or no header still get content:

- `<title>`, `<meta name="description">`, `<link rel="canonical">`
- Open Graph tags (`og:title`, `og:description`, `og:url`, `og:type`)
- JSON-LD structured data (schema.org `Article`, or another type derived from the page `type`)
- The rendered page body (headings, paragraphs, lists, tables) inside `#root`

Other SPA routes (`/search`, `/admin/*`, `/edit/*` and so on) share URL space with JSON APIs, so `SpaRoutingFilter` forwards them to the shell only when the request's `Accept` header contains `text/html`.

### 4. Sitemap

Implemented by **`com.wikantik.ui.SitemapServlet`** at `/sitemap.xml`:

- `<lastmod>` reflects actual page modification time (not deploy time)
- All pages visible to anonymous users are enumerated (ACL-filtered)
- The sitemap is referenced from `robots.txt` (`wikantik-war/src/main/webapp/robots.txt`)

See [docs/archive/Sitemap.md](../archive/Sitemap.md) and [docs/archive/SitemapOptimization.md](../archive/SitemapOptimization.md) for tuning details.

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

- **Agent-facing context**: For AI coding agents that consume this wiki as a knowledge base, prefer the `/knowledge-mcp` MCP server (hybrid retrieval + knowledge graph) over `GET /wiki/{slug}?format=*`, which is optimised for bulk ingestion rather than per-query retrieval. The `/api/pages/for-agent/{canonical_id}` projection (implemented in `com.wikantik.rest.PageForAgentResource`) serves a token-efficient, agent-grade view of a single page — see [docs/wikantik-pages/AgentGradeContentDesign.md](../wikantik-pages/AgentGradeContentDesign.md).
- **Structural discovery**: Bulk ingestion tools that want to understand wiki structure before paging through `/api/changes` can consume `/api/structure/*` — implemented in `com.wikantik.rest.StructureResource`. See [docs/wikantik-pages/StructuralSpineDesign.md](../wikantik-pages/StructuralSpineDesign.md).
- **Sitemap and Atom feeds**: [docs/archive/Sitemap.md](../archive/Sitemap.md). The Atom feed is served by `com.wikantik.content.AtomFeedServlet` and provides a time-ordered entry stream for feed readers and incremental ingestion pipelines.
