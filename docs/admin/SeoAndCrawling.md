# SEO and Crawling

This guide is for operators who want a Wikantik site indexed by search engines. It
describes what the application serves for crawlers (robots.txt, the sitemap, per-page
titles and structured data), the configuration that controls it, and the manual setup
you do outside the application in Google Search Console and the Cloudflare dashboard.
For raw-content and change-feed endpoints for RAG pipelines, see
[IndexingSupport.md](IndexingSupport.md).

## What the application serves

### robots.txt

`wikantik-war/src/main/webapp/robots.txt` allows indexing of public content and
advertises the sitemap. Edit the `Sitemap:` line when you deploy under a different host:

```
Sitemap: https://wiki.wikantik.com/sitemap.xml
```

It disallows `/admin/`, `/edit/`, `/diff/`, `/preferences`, `/reset-password`,
`/page-graph`, `/knowledge-graph`, `/login`, `/me/mentions`, `/api/`, `/mcp`, `/metrics`
and `/search`, and sets `Crawl-delay: 1`. One exception is deliberate:
`Allow: /api/pages/` precedes `Disallow: /api/`. The React reader fetches page bodies
from `/api/pages/{name}?render=true`, and Google's renderer skips robots-disallowed
resources, so blocking that path made rendered pages look empty and Google reported
"Soft 404". Keep the `Allow` ahead of the `Disallow`.

### sitemap.xml

`SitemapServlet` (mapped to `/sitemap.xml` in `web.xml`) lists every public page. It
emits `<loc>` and `<lastmod>` only. It omits `<changefreq>` and `<priority>` on purpose,
because Google ignores both; `<lastmod>` is the only optional field worth sending, and
only while it stays accurate. It also adds:

- Google Image sitemap entries for image attachments.
- A Google News entry for pages modified within the last 2 days that carry frontmatter
  `tags`.

Menu and system pages (such as `LeftMenu`) are excluded.

The sitemap has one configuration property. Behind an SSL-terminating proxy
(cloudflared, nginx, a load balancer) the generated URLs can come out as `http://`; set
the base URL explicitly:

| Property | Default | Effect |
|----------|---------|--------|
| `wikantik.sitemap.baseURL` | blank (derive from the request) | Base URL used for sitemap entries, for example `https://wiki.example.com` |

Two sibling properties feed other surfaces with the same reverse-proxy problem and also
default to deriving the origin from the request: `wikantik.feed.baseURL` (Atom feed
links) and `wikantik.public.baseURL` (`/tools/*` citation links). There are no
sitemap properties for change frequency or priority.

### Per-page title and structured data

`SemanticHeadRenderer` emits a unique `<title>` for each page (from the frontmatter
`title:`, falling back to the page name) along with `og:title`/`twitter:title`, and
JSON-LD structured data: `Article` or `CollectionPage` (for hubs), `BreadcrumbList` for
clustered non-hub pages, and `WebSite` with `SearchAction` on the home page.
`SpaRoutingFilter.stripShellTitle` removes the static SPA shell title so the per-page
title is the only one in the document; crawlers honour the first `<title>` they see.

Verify a deployment:

```bash
# robots.txt advertises the right sitemap
curl -s https://wiki.wikantik.com/robots.txt | grep -i sitemap

# the page title is unique, not just "Wikantik"
curl -s https://wiki.wikantik.com/wiki/TestDrivenDevelopment | grep -oiP '<title>[^<]*</title>'

# structured data is present
curl -s https://wiki.wikantik.com/wiki/TestDrivenDevelopment | grep -c 'application/ld+json'
```

### Notify search engines of changes

The admin MCP tool `ping_search_engines` (`service` of `indexnow`, `google_ping` or
`all`) submits changed page URLs to IndexNow (Bing, Yandex). It requires an absolute
`wikantik.baseURL` and `wikantik.indexnow.apiKey` (blank skips IndexNow with an explicit
"apiKey not configured" error); the same key must be served publicly at
`<baseURL>/<key>.txt`. Prefer `service=indexnow`: the `google_ping` option calls Google's
retired sitemap-ping endpoint, so submit the sitemap in Search Console instead (below).

## Set up Google Search Console

Search Console is how Google learns the site exists, where the sitemap is, and which
pages it excludes. Do this once after deploying.

1. Go to <https://search.google.com/search-console> and sign in with the Google account
   that should own the property.
2. Choose **Add property**, then **Domain**, and enter your registrable domain (for
   example `wikantik.com`). A domain property covers every subdomain in one step; the
   URL-prefix type covers only one host.
3. Google gives you a TXT record. In the Cloudflare dashboard, open the zone, go to
   **DNS**, **Records**, **Add record**, type `TXT`, name `@`, and paste the
   `google-site-verification=…` value. Click **Verify** in Search Console once DNS has
   propagated (a few minutes).
4. Open **Sitemaps**, enter `https://<your-host>/sitemap.xml`, and submit.
5. Spot-check a page with **URL Inspection**: confirm Google can fetch and render it and
   sees the per-page title. Use **Request indexing** for a few key pages.

Over the next weeks watch the **Pages** report (indexed versus excluded, and why) and
**Performance** (impressions and clicks). A new domain ramps slowly.

Optionally add the site to Bing Webmaster Tools (<https://www.bing.com/webmasters>); you
can import the property, including its sitemap, directly from Google Search Console.

## Configure Cloudflare

### Decide on the AI-crawler block

When Cloudflare fronts the site, its managed robots.txt can prepend a block of its own
(it is not in the repository). At the last check it set `Content-Signal:
search=yes,ai-train=no` and disallowed AI crawlers such as `GPTBot`, `ClaudeBot`,
`Google-Extended`, `CCBot`, `Bytespider`, `meta-externalagent` and `Applebot-Extended`.

- This does not affect classic search. `Googlebot` and `Bingbot` are separate agents
  from `Google-Extended` and `GPTBot`, and `search=yes` allows search indexing.
- It does block AI answer engines from reading the wiki. Whether to allow them is a
  product decision: for a knowledge base meant to be cited by AI tools, allowing them may
  matter more than blocking training crawlers.

To change it, open the Cloudflare dashboard, select the zone, go to **Security** then
**Bots** (the label varies by plan), and adjust **Block AI bots** or the **Managed
robots.txt** toggle. Afterwards re-check `curl -s https://<your-host>/robots.txt`.

### Confirm Googlebot is not challenged

A WAF rule, "Under Attack" mode or Bot Fight Mode that serves Googlebot a JavaScript
challenge silently stops indexing. In **Security** then **Events**, filter by the
Googlebot user agent and confirm requests are *Allowed*, not *Challenged* or *Blocked*.
Cloudflare's verified-bot handling normally covers this.

## Set realistic expectations

A correct sitemap gets pages crawled; it does not make them rank. Unique titles,
structured data and a discoverable sitemap let the content compete, but durable organic
traffic on a new domain still depends on topical focus and inbound links, which is a
content strategy rather than a configuration change.
