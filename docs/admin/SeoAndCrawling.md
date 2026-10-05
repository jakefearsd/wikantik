# SEO & Crawling — getting wiki.wikantik.com indexed

Operator guide for the search-visibility work done 2026-05-25. The first section
records the code changes (already landed); the rest is the manual setup you need
to do in Google Search Console and the Cloudflare dashboard — Claude can't touch
those.

## 1. What changed in code (ships on next redeploy)

| Fix | File | Effect |
|-----|------|--------|
| Sitemap directive pointed at the wrong domain (`wiki.jakefear.com`) | `wikantik-war/src/main/webapp/robots.txt` | robots.txt now advertises `https://wiki.wikantik.com/sitemap.xml`, so sitemap autodiscovery works |
| Every page had the generic `<title>Wikantik</title>` | `SemanticHeadRenderer` (emits `<title>`) + `SpaRoutingFilter.stripShellTitle` (drops the static shell title) | Each page now gets a unique `<title>` from its frontmatter `title:` (falls back to the page name), plus the readable title flows into `og:title`/`twitter:title` |
| JSON-LD structured data missing on the live site | already in `SemanticHeadRenderer` source | The deployed 2.0.x build predates the JSON-LD work — a redeploy ships Article/CollectionPage + BreadcrumbList structured data |

**These are inert until you redeploy docker1.** The sitemap itself (1,167 URLs,
correct HTTPS hosts, valid `<lastmod>`) was already healthy.

### Deploy

```bash
# build a green image, cut + publish a release, then swap the image on docker1
bin/cut-release.sh X.Y.Z          # then finish the push manually if prompted
bin/deploy-release.sh X.Y.Z       # pull published image → remote.sh deploy --skip-build
```

### Verify after deploy

```bash
# 1. robots.txt advertises the right sitemap, no jakefear leak
curl -s https://wiki.wikantik.com/robots.txt | grep -i sitemap
#   → Sitemap: https://wiki.wikantik.com/sitemap.xml

# 2. per-page title is unique (not just "Wikantik")
curl -s https://wiki.wikantik.com/wiki/TestDrivenDevelopment | grep -oiP '<title>[^<]*</title>'
#   → <title>Test Driven Development (TDD) - Wikantik</title>

# 3. structured data is present
curl -s https://wiki.wikantik.com/wiki/TestDrivenDevelopment | grep -c 'application/ld+json'
#   → 1 or 2 (Article + BreadcrumbList on clustered pages)
```

## 2. Google Search Console (do this once, after deploy)

Search Console is how Google learns the site exists, where the sitemap is, and
reports indexing problems. Without it you're waiting on passive discovery.

1. Go to https://search.google.com/search-console and sign in with the Google
   account you want to own the property.
2. **Add a property → Domain** and enter `wikantik.com` (Domain properties cover
   every subdomain — `wiki.`, `www.`, apex — in one shot; the URL-prefix type
   only covers one host).
3. Google gives you a **TXT record to add to DNS**. Add it in the Cloudflare
   dashboard:
   - Cloudflare → select the **wikantik.com** zone → **DNS → Records → Add record**
   - Type `TXT`, Name `@`, Content = the `google-site-verification=…` string
   - Save, then back in Search Console click **Verify** (DNS can take a few
     minutes to propagate).
4. Once verified: **Sitemaps** (left nav) → enter `https://wiki.wikantik.com/sitemap.xml`
   → **Submit**. (You enter just the path/URL; Search Console resolves it.)
5. Spot-check a page: **URL Inspection** → paste
   `https://wiki.wikantik.com/wiki/TestDrivenDevelopment` → confirm Google can
   fetch and render it, and that it sees the per-page title. Use **Request
   indexing** to nudge a few key pages.

**What to watch over the next 2-4 weeks:** the *Pages* report (how many of the
1,167 are indexed vs. excluded, and why), and *Performance* (impressions/clicks
once pages rank). New domains ramp slowly — don't expect traffic in week one.

### Optional: Bing Webmaster Tools

Cheap incremental reach (also feeds DuckDuck;go and, increasingly, AI answer
engines). https://www.bing.com/webmasters → add `https://wiki.wikantik.com` →
you can **import directly from Google Search Console** in one click, which also
carries the sitemap over.

## 3. Cloudflare changes (a decision, not just a fix)

### 3a. The AI-crawler block — decide deliberately

The live `robots.txt` has a **Cloudflare-managed block prepended** to our own
(Cloudflare injects it; it is *not* in our repo). It currently sets:

```
User-agent: *
Content-Signal: search=yes,ai-train=no
...
User-agent: GPTBot      Disallow: /
User-agent: ClaudeBot   Disallow: /
User-agent: Google-Extended  Disallow: /
User-agent: CCBot, Bytespider, meta-externalagent, Applebot-Extended …  Disallow: /
```

- This does **not** hurt classic Google/Bing search — `Googlebot` and `Bingbot`
  are separate from `Google-Extended`/`GPTBot`, and `search=yes` explicitly
  allows search indexing. So organic *search* SEO is unaffected.
- It **does** block AI answer engines (ChatGPT, Claude, Perplexity, Google AI
  summaries) from reading the wiki. For a product pitched as "the knowledge base
  for the AI era," letting those engines cite your showcase content may be worth
  more than blocking training crawlers. **This is your call.**

To change it: Cloudflare dashboard → **wikantik.com** zone → **Bots** (or
**Security → Bots**) → look for **"Block AI bots" / "AI Scrapers and Crawlers"**
and the **Managed robots.txt** toggle. Turn the managed block off (or switch it
to "allow") if you want AI engines in. Leave it on if you want them out. After
changing, re-check `curl -s https://wiki.wikantik.com/robots.txt`.

### 3b. Confirm Googlebot isn't being challenged

Make sure no WAF / "Under Attack" / Bot Fight Mode rule is serving Googlebot a
JS challenge instead of the page (that silently tanks indexing). In Cloudflare →
**Security → Events**, filter by the Googlebot user-agent and confirm requests
are *Allowed*, not *Challenged/Blocked*. Cloudflare verified-bot allowlisting
normally handles this, but worth a 30-second check.

### 3c. (Minor) sitemap cache header

`/sitemap.xml` responds with `Cache-Control: private`. Harmless — Googlebot
ignores it for crawl scheduling — so no action needed. If you ever want CDN
caching of the sitemap, add a Cloudflare Cache Rule for the `/sitemap.xml` path;
not worth doing now.

## 4. The honest caveat about traffic

A correct sitemap gets pages **crawled**; it doesn't make them **rank**. The
demo corpus is a topical grab-bag (`ProxyPattern`, `RoadMealPlanning`,
`MetaheuristicOptimization`…) on a brand-new domain with no inbound links —
Google has little reason to rank it yet. The code fixes above let the content
*compete* (unique titles, structured data, discoverable sitemap); durable
organic traffic still needs topical focus and links, which is a content strategy,
not a config change.
