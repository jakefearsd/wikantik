# Marketing Site Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expand `marketing/` (www.wikantik.com) from one landing page into a ~19-page, SEO-optimized static site covering Wikantik's enterprise and AI-platform capabilities, while keeping the landing page the centerpiece.

**Architecture:** Hand-authored static HTML, no build step, one shared `styles.css`. A copy-from template file (`marketing/templates/page.template.html`) keeps the head/nav/footer identical across pages; Node `node:test` SEO-lint + sitemap tests enforce per-page invariants and prevent drift. Deep-dives link out to the live wiki (`wiki.wikantik.com`) as authority/proof. Deployed via the existing `bin/deploy-marketing.sh` (allowlist updated).

**Tech Stack:** HTML5, CSS (existing token system), vanilla JS (existing lead form only), Node 18+ `node:test` for the test harness. No new dependencies.

**Source of truth for content facts:** the repo's own docs (`docs/ScimProvisioning.md`, `docs/SingleSignOn.md`, `docs/FullOAuth.md`, `docs/AuditLog.md` + `docs/wikantik-pages/AuditLogDesign.md`, `docs/HybridRetrieval.md`/`docs/wikantik-pages/HybridRetrieval.md`, `docs/KnowledgeGraphRerank.md`, `docs/KgInclusionPolicy.md`, `docs/wikantik-pages/PageGraphVsKnowledgeGraph.md`, `docs/wikantik-pages/AgentGradeContentDesign.md`, `docs/wikantik-pages/StructuralSpineDesign.md`, `docs/wikantik-pages/GoodMcpDesign.md`, `docs/BackupAndRecovery.md`, `CLAUDE.md` security/architecture sections). Never invent capabilities; verify specifics against these or code while writing.

---

## House style (every page must follow)

- Voice: confident, plain-language expert; benefit-led; second person ("your team", "your agents"). A little wry on the landing/compare pages; more substantive and technical on deep-dives. Never keyword-stuffed.
- Each deep-dive: 800–1,400 words. Open with a crisp, quotable, definitional first paragraph (the `.doc-lede`). Then "How it works" with concrete specifics (real config keys, endpoints, table names) for credibility. Then "Why it matters". Then an FAQ block (2–3 Q&As) and a "Related" block.
- Exactly one `<h1>` per page. Logical `<h2>`/`<h3>`. Descriptive anchor text (never "click here"). Alt text on any image.
- All internal links and assets are **root-absolute** (`/styles.css`, `/platform/…`).
- Outbound wiki links are allowed ONLY if the URL is present in `marketing/verified-wiki-links.json`. Otherwise link to the live product (`https://wiki.wikantik.com/`).
- datePublished/dateModified on new pages = `2026-06-07`.

---

## Reference: canonical URL derivation (used by tests and every page)

- `index.html` → `https://www.wikantik.com/`
- `X/index.html` → `https://www.wikantik.com/X/`
- `X/page.html` → `https://www.wikantik.com/X/page.html`

---

## Task 1: Foundation — CSS additions, page template, robots.txt, verified-links allowlist

**Files:**
- Modify: `marketing/styles.css` (append a "Multi-page additions" block)
- Create: `marketing/templates/page.template.html`
- Create: `marketing/robots.txt`
- Create: `marketing/verified-wiki-links.json`

- [ ] **Step 1: Append shared component CSS to `marketing/styles.css`**

Append exactly this block at the end of the file:

```css

/* ============ Multi-page additions ============ */

/* Breadcrumb */
.breadcrumb { border-bottom: 1px solid var(--border); background: var(--wash); font-size: 0.82rem; }
.breadcrumb .container { padding-top: 10px; padding-bottom: 10px; }
.breadcrumb a { color: var(--muted); }
.breadcrumb a:hover { color: var(--ink); }
.breadcrumb [aria-current="page"] { color: var(--ink); font-weight: 600; }

/* Doc / article layout */
.doc { max-width: 760px; padding-top: 48px; padding-bottom: 8px; }
.doc-head { margin-bottom: 26px; }
.doc-head h1 { font-size: 2.4rem; font-weight: 800; letter-spacing: -0.02em; line-height: 1.1; margin: 6px 0 14px; }
.doc-lede { font-size: 1.15rem; color: var(--muted); margin: 0; }
.doc-body { font-size: 1rem; }
.doc-body h2 { font-size: 1.5rem; font-weight: 800; letter-spacing: -0.01em; margin: 36px 0 10px; }
.doc-body h3 { font-size: 1.15rem; font-weight: 700; margin: 24px 0 8px; }
.doc-body p { color: var(--ink); margin: 0 0 14px; }
.doc-body ul, .doc-body ol { color: var(--ink); padding-left: 22px; margin: 0 0 14px; }
.doc-body li { margin: 4px 0; }
.doc-body a { font-weight: 600; }
.doc-body code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 0.88em;
  background: var(--wash); padding: 1px 5px; border-radius: 4px; }

/* Callout */
.callout { border: 1px solid var(--border); border-left: 3px solid var(--terracotta);
  background: var(--wash); border-radius: var(--radius); padding: 16px 18px; margin: 20px 0; }
.callout p { margin: 0; color: var(--muted); font-size: 0.95rem; }

/* FAQ */
.faq { margin-top: 40px; }
.faq details { border-top: 1px solid var(--border); padding: 14px 0; }
.faq summary { cursor: pointer; font-weight: 700; color: var(--ink); }
.faq details[open] summary { color: var(--terracotta); }
.faq details p { margin: 10px 0 0; color: var(--muted); }

/* Related cards */
.related { max-width: 760px; margin: 44px auto 0; }
.related h2 { font-size: 1.2rem; font-weight: 800; margin: 0 0 14px; }
.related-cards { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.related-card { border: 1px solid var(--border); border-radius: var(--radius); padding: 16px 18px; color: var(--ink); }
.related-card:hover { border-color: var(--terracotta); text-decoration: none; }
.related-card strong { display: block; font-size: 1rem; margin-bottom: 4px; }
.related-card span { font-size: 0.88rem; color: var(--muted); }

.doc-cta { margin: 40px auto 56px; max-width: 760px; display: flex; gap: 12px; justify-content: center; flex-wrap: wrap; }

/* Hub */
.hub-hero { padding: 64px 0 24px; text-align: center; }
.hub-hero .hero-sub { margin-bottom: 0; }
.hub-grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 18px; margin: 28px 0 8px; }
.hub-card { border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 22px; color: var(--ink); }
.hub-card:hover { border-color: var(--terracotta); text-decoration: none; }
.hub-card .hub-icon { font-size: 1.4rem; margin-bottom: 8px; }
.hub-card h3 { font-size: 1.05rem; font-weight: 800; margin: 0 0 6px; }
.hub-card p { font-size: 0.92rem; color: var(--muted); margin: 0; }

/* Site footer (multi-column, doubles as sitemap nav) */
.footer--site { background: var(--wash); border-top: 1px solid var(--border); padding: 44px 0 28px; }
.footer-cols { display: grid; grid-template-columns: 1.4fr 1fr 1fr 1fr; gap: 28px; }
.footer-col h4 { font-size: 0.72rem; letter-spacing: 0.08em; text-transform: uppercase;
  color: var(--soft); font-weight: 700; margin: 0 0 10px; }
.footer-col h4:not(:first-of-type) { margin-top: 18px; }
.footer-col a { display: block; color: var(--muted); font-size: 0.9rem; padding: 3px 0; }
.footer-col a:hover { color: var(--ink); }
.footer-tag { color: var(--muted); font-size: 0.9rem; margin: 10px 0 0; }
.footer--site .footer-credit { color: var(--soft); font-size: 0.82rem; margin: 28px 0 0; text-align: center; }

@media (max-width: 720px) {
  .hub-grid { grid-template-columns: 1fr; }
  .related-cards { grid-template-columns: 1fr; }
  .footer-cols { grid-template-columns: 1fr 1fr; }
  .doc-head h1 { font-size: 1.9rem; }
}
```

- [ ] **Step 2: Create `marketing/templates/page.template.html`** (copy-source for every new page; NOT deployed, NOT in the WEB_FILES allowlist)

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="google-adsense-account" content="ca-pub-5083997587716933">
  <title>{{TITLE}}</title>
  <meta name="description" content="{{DESCRIPTION}}">
  <meta name="robots" content="index,follow">
  <link rel="canonical" href="{{CANONICAL}}">
  <link rel="icon" type="image/svg+xml" href="/favicon.svg">

  <!-- Open Graph -->
  <meta property="og:type" content="{{OG_TYPE}}">
  <meta property="og:url" content="{{CANONICAL}}">
  <meta property="og:title" content="{{TITLE}}">
  <meta property="og:description" content="{{DESCRIPTION}}">
  <meta property="og:image" content="https://www.wikantik.com/assets/wikantik-logo-512.png">

  <!-- Twitter -->
  <meta name="twitter:card" content="summary">
  <meta name="twitter:title" content="{{TITLE}}">
  <meta name="twitter:description" content="{{DESCRIPTION}}">
  <meta name="twitter:image" content="https://www.wikantik.com/assets/wikantik-logo-512.png">

  <link rel="stylesheet" href="/styles.css">

  <!-- Sitewide identity -->
  <script type="application/ld+json">
  {
    "@context": "https://schema.org",
    "@graph": [
      { "@type": "Organization", "@id": "https://www.wikantik.com/#org",
        "name": "Wikantik", "url": "https://www.wikantik.com/",
        "logo": "https://www.wikantik.com/assets/wikantik-logo-512.png" },
      { "@type": "WebSite", "@id": "https://www.wikantik.com/#site",
        "name": "Wikantik", "url": "https://www.wikantik.com/",
        "publisher": { "@id": "https://www.wikantik.com/#org" } }
    ]
  }
  </script>

  <!-- Page-specific structured data (TechArticle + BreadcrumbList [+ FAQPage]) -->
  {{PAGE_JSONLD}}
</head>
<body>
  <header class="nav">
    <div class="container nav-inner">
      <a class="brand" href="/" aria-label="Wikantik home">
        <span class="brand-mark" aria-hidden="true">W</span>
        <span class="brand-name">Wikantik</span>
      </a>
      <nav class="nav-links" aria-label="Primary">
        <a href="/platform/">Platform</a>
        <a href="/enterprise/">Enterprise</a>
        <a href="/compare/">Compare</a>
        <a class="btn btn-primary nav-cta" href="https://wiki.wikantik.com/">Explore the wiki →</a>
      </nav>
    </div>
  </header>

  <main id="top">
    {{BREADCRUMB}}
    {{CONTENT}}
  </main>

  <footer class="footer footer--site">
    <div class="container">
      <div class="footer-cols">
        <div class="footer-col footer-col--brand">
          <a class="brand" href="/" aria-label="Wikantik home">
            <span class="brand-mark" aria-hidden="true">W</span>
            <span class="brand-name">Wikantik</span>
          </a>
          <p class="footer-tag">The knowledge base for the AI era.</p>
        </div>
        <div class="footer-col">
          <h4>Platform</h4>
          <a href="/platform/mcp-for-ai-agents.html">MCP for AI agents</a>
          <a href="/platform/hybrid-retrieval.html">Hybrid retrieval</a>
          <a href="/platform/knowledge-graph.html">Knowledge graph</a>
          <a href="/platform/page-graph.html">Page graph</a>
          <a href="/platform/agent-grade-content.html">Agent-grade content</a>
          <a href="/platform/structural-spine.html">Structural spine</a>
        </div>
        <div class="footer-col">
          <h4>Enterprise</h4>
          <a href="/enterprise/sso-saml-oidc.html">SSO (SAML / OIDC)</a>
          <a href="/enterprise/scim-provisioning.html">SCIM provisioning</a>
          <a href="/enterprise/audit-log.html">Audit log</a>
          <a href="/enterprise/access-control-rbac.html">Access control</a>
          <a href="/enterprise/security-hardening.html">Security hardening</a>
          <a href="/enterprise/self-hosting-and-backup.html">Self-hosting &amp; backup</a>
        </div>
        <div class="footer-col">
          <h4>Compare</h4>
          <a href="/compare/confluence-alternative.html">vs Confluence</a>
          <a href="/compare/notion-alternative.html">vs Notion</a>
          <a href="/compare/wiki-for-ai-agents.html">AI-native wiki</a>
          <h4>Company</h4>
          <a href="https://wiki.wikantik.com/">Live wiki</a>
          <a href="https://wiki.wikantik.com/privacy-policy.html">Privacy</a>
          <a href="https://wiki.wikantik.com/terms-of-service.html">Terms</a>
          <a href="mailto:jakefear@gmail.com">Contact</a>
        </div>
      </div>
      <p class="footer-credit">Wikantik · built by Jacob Fear</p>
    </div>
  </footer>
</body>
</html>
```

Breadcrumb snippet (fill per page; landing/hubs vary):

```html
<nav class="breadcrumb" aria-label="Breadcrumb">
  <div class="container">
    <a href="/">Home</a> ›
    <a href="/platform/">Platform</a> ›
    <span aria-current="page">PAGE NAME</span>
  </div>
</nav>
```

- [ ] **Step 3: Create `marketing/robots.txt`**

```
# www.wikantik.com — marketing site
User-agent: *
Allow: /

# AI / answer engines — explicitly welcome (we want to be cited)
User-agent: GPTBot
Allow: /
User-agent: OAI-SearchBot
Allow: /
User-agent: ChatGPT-User
Allow: /
User-agent: ClaudeBot
Allow: /
User-agent: Claude-Web
Allow: /
User-agent: anthropic-ai
Allow: /
User-agent: PerplexityBot
Allow: /
User-agent: Google-Extended
Allow: /
User-agent: Applebot-Extended
Allow: /
User-agent: CCBot
Allow: /

Sitemap: https://www.wikantik.com/sitemap.xml
```

- [ ] **Step 4: Verify candidate live-wiki slugs and create `marketing/verified-wiki-links.json`**

For each candidate URL below, fetch it (WebFetch or `curl -s -o /dev/null -w '%{http_code}'`) and keep ONLY those returning 200 / real content. `HybridRetrieval` and `StructuralSpineDesign` are already confirmed live; `ScimProvisioningDesign` is confirmed 404 (exclude). Verify the rest:

Candidates to test:
`https://wiki.wikantik.com/wiki/HybridRetrieval` (✓), `…/StructuralSpineDesign` (✓),
`…/AuditLogDesign`, `…/AgentGradeContentDesign`, `…/PageGraphVsKnowledgeGraph`,
`…/KgInclusionPolicy`, `…/GoodMcpDesign`, `…/KnowledgeGraphRerank`, `…/ObservabilityDesign`,
`…/DockerDeployment`, `…/SingleSignOn`, `…/ScimProvisioning`, `…/BackupAndRecovery`.

Write the confirmed-live URLs (always include the two known-good plus the product root) into the file:

```json
{
  "_comment": "Outbound wiki links allowed on marketing pages. Each URL was confirmed to return 200. Add only after verifying live.",
  "allowed": [
    "https://wiki.wikantik.com/",
    "https://wiki.wikantik.com/login",
    "https://wiki.wikantik.com/wiki/HybridRetrieval",
    "https://wiki.wikantik.com/wiki/StructuralSpineDesign"
  ]
}
```

(Add each additional confirmed-live URL to the `allowed` array.)

- [ ] **Step 5: Commit**

```bash
git add marketing/styles.css marketing/templates/page.template.html marketing/robots.txt marketing/verified-wiki-links.json
git commit -m "feat(marketing): shared CSS, page template, robots.txt, verified-links allowlist"
```

---

## Task 2: Test harness + landing refresh + initial sitemap

**Files:**
- Create: `marketing/test/_util.mjs`
- Create: `marketing/test/seo-lint.test.mjs`
- Create: `marketing/test/sitemap.test.mjs`
- Create: `marketing/test/links.test.mjs`
- Create: `marketing/sitemap.xml`
- Modify: `marketing/index.html` (root-absolute `/styles.css`; new nav; new multi-column footer; add "Platform" + "Enterprise" teaser sections + internal links; keep hero/problem/pillars/terminal/compare/hosting/form intact)

- [ ] **Step 1: Write `marketing/test/_util.mjs`** (shared helpers)

```js
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";

export const ROOT = new URL("..", import.meta.url).pathname; // marketing/
export const SITE_ORIGIN = "https://www.wikantik.com";
const EXCLUDE_DIRS = new Set(["test", "templates", "form-backend", "assets", "node_modules"]);

export function htmlFiles(dir = ROOT) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) {
      if (!EXCLUDE_DIRS.has(name)) out.push(...htmlFiles(full));
    } else if (name.endsWith(".html")) {
      out.push(full);
    }
  }
  return out;
}

export function relPath(file) { return relative(ROOT, file); }

export function canonicalForPath(rel) {
  if (rel === "index.html") return `${SITE_ORIGIN}/`;
  if (rel.endsWith("/index.html")) return `${SITE_ORIGIN}/${rel.slice(0, -"index.html".length)}`;
  return `${SITE_ORIGIN}/${rel}`;
}

export function read(file) { return readFileSync(file, "utf8"); }
```

- [ ] **Step 2: Write `marketing/test/seo-lint.test.mjs`**

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { join } from "node:path";
import { ROOT, htmlFiles, relPath, canonicalForPath, read } from "./_util.mjs";

const sitemap = read(join(ROOT, "sitemap.xml"));

for (const file of htmlFiles()) {
  const rel = relPath(file);
  const html = read(file);
  test(`SEO invariants: ${rel}`, () => {
    const h1 = html.match(/<h1[\s>]/gi) || [];
    assert.equal(h1.length, 1, `expected exactly one <h1>, found ${h1.length}`);

    const title = html.match(/<title>([^<]+)<\/title>/i);
    assert.ok(title && title[1].trim(), "missing <title>");

    const desc = html.match(/<meta\s+name=["']description["']\s+content=["']([^"']+)["']/i);
    assert.ok(desc && desc[1].trim(), "missing meta description");

    const canon = html.match(/<link\s+rel=["']canonical["']\s+href=["']([^"']+)["']/i);
    assert.ok(canon, "missing canonical");
    assert.equal(canon[1], canonicalForPath(rel), "canonical mismatch");

    assert.match(html, /property=["']og:title["']/i, "missing og:title");
    assert.match(html, /property=["']og:description["']/i, "missing og:description");
    assert.match(html, /class="nav"/i, "missing nav");
    assert.match(html, /<footer/i, "missing footer");

    const ld = [...html.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/gi)];
    assert.ok(ld.length >= 1, "missing JSON-LD");
    for (const m of ld) JSON.parse(m[1]); // throws on invalid JSON

    assert.ok(sitemap.includes(canonicalForPath(rel)), `not in sitemap.xml: ${rel}`);
  });
}
```

- [ ] **Step 3: Write `marketing/test/sitemap.test.mjs`**

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { join } from "node:path";
import { ROOT, SITE_ORIGIN, htmlFiles, relPath, canonicalForPath, read } from "./_util.mjs";

const sitemap = read(join(ROOT, "sitemap.xml"));
const locs = [...sitemap.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1].trim());
const pages = htmlFiles().map((f) => canonicalForPath(relPath(f)));

test("every page is in the sitemap", () => {
  for (const p of pages) assert.ok(locs.includes(p), `missing from sitemap: ${p}`);
});
test("every sitemap loc maps to a page", () => {
  for (const l of locs) assert.ok(pages.includes(l), `sitemap loc has no page: ${l}`);
});
test("all locs are absolute www URLs", () => {
  for (const l of locs) assert.ok(l.startsWith(`${SITE_ORIGIN}/`), `non-absolute loc: ${l}`);
});
```

- [ ] **Step 4: Write `marketing/test/links.test.mjs`** (offline; allowlist-based)

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { join } from "node:path";
import { ROOT, htmlFiles, relPath, read } from "./_util.mjs";

const allowed = new Set(JSON.parse(read(join(ROOT, "verified-wiki-links.json"))).allowed);

for (const file of htmlFiles()) {
  const rel = relPath(file);
  const html = read(file);
  test(`outbound wiki links are verified: ${rel}`, () => {
    const links = [...html.matchAll(/href=["'](https:\/\/wiki\.wikantik\.com[^"']*)["']/g)].map((m) => m[1]);
    for (const l of links) {
      // allow exact match, or product sub-pages we don't deep-link as authority
      const ok = allowed.has(l) || l === "https://wiki.wikantik.com/" ||
                 l.startsWith("https://wiki.wikantik.com/privacy-policy") ||
                 l.startsWith("https://wiki.wikantik.com/terms-of-service");
      assert.ok(ok, `unverified wiki link in ${rel}: ${l} (add to verified-wiki-links.json after confirming 200)`);
    }
  });
}
```

- [ ] **Step 5: Create `marketing/sitemap.xml` with the landing entry only (grows per page task)**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url><loc>https://www.wikantik.com/</loc><lastmod>2026-06-07</lastmod><priority>1.0</priority></url>
</urlset>
```

- [ ] **Step 6: Refresh `marketing/index.html`**

Make these precise edits, leaving hero, problem, pillars, terminal, compare, hosting, and the lead form sections intact:
1. Change `<link rel="stylesheet" href="styles.css">` → `href="/styles.css"`.
2. Replace the `<nav class="nav-links">` block so its links match the template: `/platform/`, `/enterprise/`, `/compare/`, and the CTA `https://wiki.wikantik.com/` "Explore the wiki →". Keep the `#learn-more` lead form anchor reachable (the hosting section's "Learn more →" button still targets `#learn-more`).
3. Insert two new teaser sections **after** the `#why` pillars section and before the `.terminal` section:

```html
  <section class="section" id="platform-teaser">
    <div class="container">
      <p class="eyebrow center">Platform</p>
      <h2 class="section-title center">An engine your AI agents can actually use</h2>
      <p class="hosting-sub center">Native MCP servers, hybrid retrieval, and a knowledge graph of your own content — the machinery that makes Wikantik answer-engine ready.</p>
      <div class="hub-grid">
        <a class="hub-card" href="/platform/mcp-for-ai-agents.html"><div class="hub-icon" aria-hidden="true">🔌</div><h3>MCP for AI agents</h3><p>Your agents query, retrieve, and cite your wiki over the Model Context Protocol.</p></a>
        <a class="hub-card" href="/platform/hybrid-retrieval.html"><div class="hub-icon" aria-hidden="true">🎯</div><h3>Hybrid retrieval</h3><p>BM25 + dense embeddings + a knowledge-graph rerank. Ask in plain language.</p></a>
        <a class="hub-card" href="/platform/knowledge-graph.html"><div class="hub-icon" aria-hidden="true">🕸️</div><h3>Knowledge graph</h3><p>Entities and relationships extracted from your pages, not a wall of HTML.</p></a>
      </div>
      <div class="center" style="margin-top: 22px;"><a class="btn btn-ghost" href="/platform/">Explore the platform →</a></div>
    </div>
  </section>

  <section class="section hosting" id="enterprise-teaser">
    <div class="container">
      <p class="eyebrow center">Enterprise</p>
      <h2 class="section-title center">Ready for the security review</h2>
      <p class="hosting-sub center">SSO, SCIM provisioning, a tamper-evident audit log, role-based access control, and self-hosting with your own backups. Own your data; pass the questionnaire.</p>
      <div class="hub-grid">
        <a class="hub-card" href="/enterprise/sso-saml-oidc.html"><div class="hub-icon" aria-hidden="true">🔐</div><h3>SSO (SAML / OIDC)</h3><p>Log in with your IdP — SAML, OIDC, Google. Session-fixation hardened.</p></a>
        <a class="hub-card" href="/enterprise/scim-provisioning.html"><div class="hub-icon" aria-hidden="true">👤</div><h3>SCIM provisioning</h3><p>Automated onboarding and offboarding driven by your identity provider.</p></a>
        <a class="hub-card" href="/enterprise/audit-log.html"><div class="hub-icon" aria-hidden="true">🧾</div><h3>Tamper-evident audit</h3><p>A verifiable, exportable trail of every privileged action.</p></a>
      </div>
      <div class="center" style="margin-top: 22px;"><a class="btn btn-ghost" href="/enterprise/">See enterprise features →</a></div>
    </div>
  </section>
```

4. Replace the existing simple `<footer class="footer">…</footer>` with the multi-column `<footer class="footer footer--site">…</footer>` from the template (Task 1, Step 2).

- [ ] **Step 7: Run the test harness**

Run: `node --test marketing/test/`
Expected: PASS (landing conforms; sitemap has the one entry that matches the one page; no unverified wiki links).

- [ ] **Step 8: Commit**

```bash
git add marketing/test/_util.mjs marketing/test/seo-lint.test.mjs marketing/test/sitemap.test.mjs marketing/test/links.test.mjs marketing/sitemap.xml marketing/index.html
git commit -m "feat(marketing): SEO-lint/sitemap/link tests, sitemap.xml, landing refresh with platform+enterprise teasers"
```

---

## Per-page task format (Tasks 3–20)

Each page task has the same shape. The engineer:
1. Copies `marketing/templates/page.template.html` to the target path.
2. Fills `{{TITLE}}`, `{{DESCRIPTION}}`, `{{CANONICAL}}`, `{{OG_TYPE}}`, `{{PAGE_JSONLD}}`, `{{BREADCRUMB}}`, `{{CONTENT}}` from the brief.
3. Writes 800–1,400 words (deep-dives) / a short overview (hubs) of real content per the brief and House style, sourcing facts from the named docs.
4. Adds the page's `<loc>` to `marketing/sitemap.xml` (priority: hub 0.8, deep-dive 0.6).
5. Adds any new outbound wiki link to `marketing/verified-wiki-links.json` ONLY after confirming it returns 200.
6. Runs `node --test marketing/test/` → PASS.
7. Commits `git add <page> marketing/sitemap.xml [marketing/verified-wiki-links.json] && git commit -m "feat(marketing): add <path>"`.

`{{PAGE_JSONLD}}` for deep-dives = a `TechArticle` + a `BreadcrumbList` (+ `FAQPage` if the page has an FAQ). Pattern:

```html
  <script type="application/ld+json">
  { "@context": "https://schema.org", "@type": "TechArticle",
    "headline": "H1 TEXT", "description": "DESCRIPTION",
    "datePublished": "2026-06-07", "dateModified": "2026-06-07",
    "author": { "@type": "Person", "name": "Jacob Fear" },
    "publisher": { "@id": "https://www.wikantik.com/#org" },
    "mainEntityOfPage": "CANONICAL" }
  </script>
  <script type="application/ld+json">
  { "@context": "https://schema.org", "@type": "BreadcrumbList",
    "itemListElement": [
      { "@type": "ListItem", "position": 1, "name": "Home", "item": "https://www.wikantik.com/" },
      { "@type": "ListItem", "position": 2, "name": "CLUSTER", "item": "https://www.wikantik.com/CLUSTER/" },
      { "@type": "ListItem", "position": 3, "name": "PAGE", "item": "CANONICAL" }
    ] }
  </script>
  <script type="application/ld+json">
  { "@context": "https://schema.org", "@type": "FAQPage",
    "mainEntity": [
      { "@type": "Question", "name": "Q1?", "acceptedAnswer": { "@type": "Answer", "text": "A1." } }
    ] }
  </script>
```

`{{CONTENT}}` deep-dive skeleton:

```html
  <article class="doc">
    <header class="doc-head">
      <p class="eyebrow">CLUSTER</p>
      <h1>H1</h1>
      <p class="doc-lede">One crisp definitional paragraph.</p>
    </header>
    <div class="doc-body">
      <h2>…</h2>
      <p>…</p>
      <!-- sections per brief; use <h2>/<h3>, <ul>, <code>, .callout -->
      <section class="faq">
        <h2>Frequently asked questions</h2>
        <details><summary>Q1?</summary><p>A1.</p></details>
        <details><summary>Q2?</summary><p>A2.</p></details>
      </section>
    </div>
    <aside class="related">
      <h2>Related</h2>
      <div class="related-cards">
        <a class="related-card" href="…"><strong>Sibling</strong><span>One line.</span></a>
        <a class="related-card" href="…"><strong>Sibling</strong><span>One line.</span></a>
      </div>
    </aside>
    <div class="doc-cta">
      <a class="btn btn-primary" href="https://wiki.wikantik.com/">Explore the live wiki →</a>
      <a class="btn btn-ghost" href="/#learn-more">Talk to us</a>
    </div>
  </article>
```

---

## Task 3: `platform/index.html` (hub)

**Files:** Create `marketing/platform/index.html`; Modify `marketing/sitemap.xml`.

- Title: `Platform — MCP, hybrid search & knowledge graph | Wikantik`
- Description: `The AI-native engine behind Wikantik: native MCP servers, hybrid retrieval, and a knowledge graph your agents can read, search, and reason over.`
- Canonical: `https://www.wikantik.com/platform/`  · OG_TYPE: `website`
- Breadcrumb: Home › Platform (current). JSON-LD: BreadcrumbList (2 items) only.
- `{{CONTENT}}`: `.hub-hero` (h1 "The AI-native knowledge platform", lede), then a `.hub-grid` of 6 `.hub-card`s linking to the six platform deep-dives (MCP, hybrid retrieval, knowledge graph, page graph, agent-grade content, structural spine), each with a one-line description. Close with a `.doc-cta`.
- Sitemap priority 0.8.

## Task 4: `platform/mcp-for-ai-agents.html`

- Title: `MCP server for your wiki — a knowledge base for AI agents | Wikantik`
- Description: `Wikantik exposes native Model Context Protocol servers so your AI agents query, retrieve, and cite your wiki directly — read-only retrieval plus admin curation tools.`
- Canonical: `https://www.wikantik.com/platform/mcp-for-ai-agents.html` · OG_TYPE `article`
- Facts (from `CLAUDE.md` agent-surface table, `docs/wikantik-pages/GoodMcpDesign.md`): two MCP servers over Streamable HTTP — `/knowledge-mcp` (16 read-only retrieval + Knowledge Graph + Page Graph + agent-projection + batched-read tools) and `/wikantik-admin-mcp` (25 write/analytics/curation tools). Also an OpenAPI 3.1 tool server at `/tools/*` (2 tools: `search_wiki`, `get_page`) for non-MCP/OpenWebUI clients. Bearer-token / API-key auth via access filters. Same source of truth serves humans and agents. Mention `/wiki/{slug}?format=md|json` raw content + `/api/changes?since=` change feed for RAG ingestion.
- Sections: What is MCP / why it matters → The two servers (read vs. curate) → How an agent uses it (the terminal-style example: ask → cited runbook) → Beyond MCP (OpenAPI tools, raw markdown, change feed) → FAQ.
- FAQ ideas: "Which MCP clients work?" "Is it read-only?" "How is access controlled?"
- Related: hybrid-retrieval, knowledge-graph, agent-grade-content. Outbound: live product; `GoodMcpDesign` wiki page IF verified live.
- Sitemap priority 0.6.

## Task 5: `platform/hybrid-retrieval.html`

- Title: `Hybrid retrieval: BM25 + dense + knowledge-graph rerank | Wikantik`
- Description: `How Wikantik search works: Lucene BM25 fused with dense embedding similarity via weighted Reciprocal Rank Fusion, then a knowledge-graph rerank — with a fail-closed BM25 fallback.`
- Canonical: `https://www.wikantik.com/platform/hybrid-retrieval.html` · OG_TYPE `article`
- Facts (from `docs/HybridRetrieval.md` / live `wiki/HybridRetrieval`, `docs/KnowledgeGraphRerank.md`): combines Lucene BM25 with dense embedding cosine similarity using **weighted Reciprocal Rank Fusion (RRF)**; optional knowledge-graph rerank; **fail-closed BM25 fallback** if the dense backend is unavailable. Dense backend is pluggable: `wikantik.search.dense.backend = inmemory | pgvector | lucene-hnsw` (lucene-hnsw is the docker1 production default). This is what makes natural-language questions resolve to the right page instead of keyword roulette.
- Sections: The problem with keyword search → What hybrid retrieval is (definition) → How it works (BM25 + dense + RRF + KG rerank, with the config key) → Reliability (fail-closed fallback) → Why it matters for RAG and agents → FAQ.
- FAQ: "What embedding backends are supported?" "What happens if the vector store is down?" "Does it work for agents and humans?"
- Related: knowledge-graph, mcp-for-ai-agents, agent-grade-content. Outbound: `https://wiki.wikantik.com/wiki/HybridRetrieval` (verified ✓). Sitemap 0.6.

## Task 6: `platform/knowledge-graph.html`

- Title: `Knowledge graph: entities & relations from your content | Wikantik`
- Description: `Wikantik extracts a knowledge graph from your pages — LLM-derived entities with co-mention and typed-relation edges, pgvector embeddings, and hub discovery — so agents reason over meaning.`
- Canonical: `https://www.wikantik.com/platform/knowledge-graph.html` · OG_TYPE `article`
- Facts (from `CLAUDE.md` Page-Graph-vs-Knowledge-Graph, `docs/KgInclusionPolicy.md`, `docs/KnowledgeGraphRerank.md`): Knowledge Graph nodes are LLM-extracted entities; edges are co-mention or typed-relation predicates. pgvector-backed embeddings; hub discovery; `kg_*` tables; curated via admin MCP + `/admin/knowledge-graph/*`. Cluster-primary **inclusion policy** (default-exclude, `kg_include:` frontmatter override) keeps the graph curated. **Distinct from the Page Graph** (link that page). Used in the retrieval rerank.
- Sections: What a knowledge graph is → How Wikantik builds it (entities, co-mention/typed edges, embeddings) → Inclusion policy (curation, default-exclude) → Knowledge Graph vs. Page Graph (1 paragraph + link) → How agents use it → FAQ.
- Related: page-graph, hybrid-retrieval, mcp-for-ai-agents. Outbound: `PageGraphVsKnowledgeGraph` / `KgInclusionPolicy` wiki pages IF verified live, else product. Sitemap 0.6.

## Task 7: `platform/page-graph.html`

- Title: `Page graph: wikilinks, canonical IDs & clusters | Wikantik`
- Description: `Wikantik's page graph is the real wikilink graph of your content — rename-stable canonical IDs and cluster-hub membership — navigable by humans and agents alike.`
- Canonical: `https://www.wikantik.com/platform/page-graph.html` · OG_TYPE `article`
- Facts (from `CLAUDE.md` Page Graph section, `docs/wikantik-pages/PageGraphVsKnowledgeGraph.md`): Page Graph edges are real page-to-page wikilinks parsed from page bodies. Companion structures: `canonical_id` (rename-stable identifier in frontmatter) and `cluster:` (hub membership). Reader route `/page-graph`; operator surfaces `/admin/page-graph/*`. Explicitly **not** the Knowledge Graph (entities). Powers backlinks, cluster trees, structural navigation.
- Sections: What the page graph is → Wikilinks as first-class edges → Canonical IDs (rename-stable links) → Clusters & hubs → Page graph vs. knowledge graph (link) → FAQ.
- Related: knowledge-graph, structural-spine, hybrid-retrieval. Outbound: `PageGraphVsKnowledgeGraph` IF live, else product. Sitemap 0.6.

## Task 8: `platform/agent-grade-content.html`

- Title: `Agent-grade content: runbooks, projections & verification | Wikantik`
- Description: `Wikantik serves every page to two readers — humans and machines: token-budgeted projections, runbook types, verification metadata, and derived agent hints for reliable AI answers.`
- Canonical: `https://www.wikantik.com/platform/agent-grade-content.html` · OG_TYPE `article`
- Facts (from `docs/wikantik-pages/AgentGradeContentDesign.md`): `type: runbook`; page verification (recency/verified metadata); `/api/pages/for-agent/{id}` token-budgeted projection; derived `agent_hints`; retrieval-quality CI. Same source serves a clean human read and a machine-optimized projection.
- Sections: Two readers, one source → Page projections for agents (`/api/pages/for-agent/{id}`) → Runbooks & verification → Agent hints → Retrieval-quality CI → FAQ.
- Related: mcp-for-ai-agents, hybrid-retrieval, structural-spine. Outbound: `AgentGradeContentDesign` IF live, else product. Sitemap 0.6.

## Task 9: `platform/structural-spine.html`

- Title: `Structural spine: a machine-queryable wiki index | Wikantik`
- Description: `Wikantik's structural spine is a small set of first-class APIs, persistent canonical IDs, and cluster-hub membership that let agents navigate your wiki by shape, not just keywords.`
- Canonical: `https://www.wikantik.com/platform/structural-spine.html` · OG_TYPE `article`
- Facts (from `docs/wikantik-pages/StructuralSpineDesign.md`, live ✓): "a small set of first-class APIs, a persistent canonical_id, and cluster-hub membership" so agents "navigate the wiki by shape, not by keyword." Save-time canonical_id enforcement; `Main.md` is generated from pins. All phases shipped.
- Sections: Navigating by shape, not keywords → First-class structural APIs → Canonical IDs & enforcement → Cluster hubs → Why agents need structure → FAQ.
- Related: page-graph, agent-grade-content, knowledge-graph. Outbound: `https://wiki.wikantik.com/wiki/StructuralSpineDesign` (verified ✓). Sitemap 0.6.

## Task 10: `enterprise/index.html` (hub)

**Files:** Create `marketing/enterprise/index.html`; Modify sitemap.

- Title: `Enterprise wiki: SSO, SCIM, audit & RBAC | Wikantik`
- Description: `Wikantik is ready for the security review: SSO (SAML/OIDC), SCIM provisioning, a tamper-evident audit log, role-based access control, hardened auth, and self-hosting you control.`
- Canonical: `https://www.wikantik.com/enterprise/` · OG_TYPE `website`
- Breadcrumb: Home › Enterprise. JSON-LD: BreadcrumbList (2 items).
- `{{CONTENT}}`: `.hub-hero` (h1 "Enterprise-ready, security-review-ready"), `.hub-grid` of 6 cards to the six enterprise deep-dives, `.doc-cta`. Sitemap 0.8.

## Task 11: `enterprise/sso-saml-oidc.html`

- Title: `SSO for your wiki: SAML, OIDC & Google | Wikantik`
- Description: `Single sign-on for Wikantik via SAML and OIDC (pac4j), including Google. Configurable identity claim, session-fixation hardening, and fail-closed account binding.`
- Canonical: `https://www.wikantik.com/enterprise/sso-saml-oidc.html` · OG_TYPE `article`
- Facts (from `docs/SingleSignOn.md`, `docs/FullOAuth.md`, `docs/OAuthImplementation.md`, `CLAUDE.md` security): pluggable auth (LDAP, database, container, SSO via **pac4j**); SAML + OIDC; **Google OIDC is live in production** at wiki.wikantik.com. Identity binding keys on `wikantik.sso.identityClaim` (default `sub`; can use `preferred_username`). SSO never adopts a pre-existing non-SSO local account of the same name (auto-provisioned profiles carry an `sso.subject` marker; collision without marker fails closed). Successful SSO login **rotates the HTTP session** (fixation defense). SAML HTTP-POST `/sso/callback` is CSRF-exempt (IdP-signed assertion is its own defense). Failures redirect to `/login` with an `?error=` code.
- Sections: SSO that fits your IdP → SAML & OIDC (pac4j), Google live → Identity binding & the claim config → Security: session rotation, fail-closed binding, CSRF handling → FAQ.
- FAQ: "Which providers are supported?" "Can I trust `preferred_username`?" "What stops account takeover by name collision?"
- Related: scim-provisioning, access-control-rbac, security-hardening. Outbound: live product `/login`; `SingleSignOn` wiki page IF live. Sitemap 0.6.

## Task 12: `enterprise/scim-provisioning.html`

- Title: `SCIM 2.0 provisioning for your wiki | Wikantik`
- Description: `Automate user lifecycle with SCIM 2.0: bearer-authed Users and Groups CRUD, PATCH active, soft-delete, and discovery — IdP-driven onboarding and offboarding for Wikantik.`
- Canonical: `https://www.wikantik.com/enterprise/scim-provisioning.html` · OG_TYPE `article`
- Facts (from `docs/ScimProvisioning.md`, `CLAUDE.md` wikantik-scim): SCIM 2.0 server at `/scim/v2/*`. Bearer-authed (`wikantik.scim.token`, `ScimAccessFilter`). `Users` CRUD + PATCH active + soft-delete; `Groups` CRUD + membership PATCH + hard delete; discovery (`ServiceProviderConfig`/`Schemas`/`ResourceTypes`). User decommission routes through the unified `UserLifecycleService`; Group membership through `GroupManager`. **SCIM Groups never grant the Admin role** (security boundary). Deactivation enforced at every login module (lock check).
- Sections: Provisioning without tickets → What SCIM 2.0 covers here (Users, Groups, discovery) → Onboarding & offboarding lifecycle → Security boundaries (no Admin via SCIM, lock enforcement) → FAQ.
- FAQ: "Which IdPs work with SCIM here?" "Does offboarding actually lock access?" "Can a SCIM group escalate to admin?"
- Related: sso-saml-oidc, access-control-rbac, audit-log. Outbound: product; `ScimProvisioning` wiki page IF live (note: `ScimProvisioningDesign` is 404 — do not link it). Sitemap 0.6.

## Task 13: `enterprise/audit-log.html`

- Title: `Tamper-evident audit log for your wiki | Wikantik`
- Description: `Wikantik records privileged actions in a tamper-evident audit log you can query, cryptographically verify, and export — with retention and purge controls for compliance.`
- Canonical: `https://www.wikantik.com/enterprise/audit-log.html` · OG_TYPE `article`
- Facts (from `docs/AuditLog.md`, `docs/wikantik-pages/AuditLogDesign.md`, specs `2026-06-03-audit-log-v2-hardening`, `2026-06-03-audit-retention-purge`, `CLAUDE.md`): tamper-evident `/admin/audit*` log — query / verify / export. Hash-chained / verifiable integrity. Retention + purge controls. Behind `AdminAuthFilter` (AllPermission).
- Sections: Why a tamper-evident trail → What gets logged → Verify & export → Retention & purge → Compliance posture → FAQ.
- FAQ: "What does 'tamper-evident' mean here?" "Can I export for a SIEM?" "How long is data kept?"
- Related: access-control-rbac, security-hardening, scim-provisioning. Outbound: product; `AuditLogDesign` wiki page IF live. Sitemap 0.6.

## Task 14: `enterprise/access-control-rbac.html`

- Title: `RBAC & access control: policy grants, ACLs, groups | Wikantik`
- Description: `Fine-grained access control in Wikantik: database-backed role policy grants, page-level ACLs, and managed groups — enforced across the UI, REST API, and admin surfaces.`
- Canonical: `https://www.wikantik.com/enterprise/access-control-rbac.html` · OG_TYPE `article`
- Facts (from `CLAUDE.md` security model, `docs/RelationalUserDatabase.md`, `docs/PageOwnership.md`): JAAS-based authz. Fine-grained permissions — page: `view`, `comment`, `edit`, `modify`, `upload`, `rename`, `delete`; wiki: `createPages`, `createGroups`, `editPreferences`, `editProfile`, `login`. **Database-backed policy grants** (`policy_grants` table, admin UI `/admin/security`). **Database-backed groups** (`groups` + `group_members`). Page-level ACLs via inline `[{ALLOW view Admin}]`. REST `/api/*` enforces ACLs via `RestServletBase.checkPagePermission()`. `/admin/*` behind `AdminAuthFilter` (AllPermission). Bootstrap admin override for setup.
- Sections: Layers of access control → The permission model (the two lists) → Policy grants (DB-backed, admin UI) → Page-level ACLs → Groups → Enforced everywhere (UI, REST, admin) → FAQ.
- Related: sso-saml-oidc, security-hardening, audit-log. Outbound: product. Sitemap 0.6.

## Task 15: `enterprise/security-hardening.html`

- Title: `Security hardening: NIST passwords, CSP, safe deserialization | Wikantik`
- Description: `Wikantik ships hardened by default: NIST 800-63B password validation with a breach blocklist, CSRF/CORS/CSP and security headers, and deserialization filtering on every input stream.`
- Canonical: `https://www.wikantik.com/enterprise/security-hardening.html` · OG_TYPE `article`
- Facts (from `CLAUDE.md` security model, wikantik-http module): **NIST 800-63B** password validation with common-password blocklist. **Deserialization filtering** — `ObjectInputFilter` whitelists on all `ObjectInputStream` usage. Servlet filters in `wikantik-http`: CSRF (synchronizer token), CORS, CSP, security headers, SPA routing. JAAS auth. Session rotation on login. (Cross-link the SSO/RBAC/audit pages.)
- Sections: Secure by default → Passwords done right (NIST 800-63B + blocklist) → Deserialization filtering → CSRF, CORS, CSP & headers → Defense in depth (links to SSO, RBAC, audit) → FAQ.
- Related: access-control-rbac, sso-saml-oidc, audit-log. Outbound: product. Sitemap 0.6.

## Task 16: `enterprise/self-hosting-and-backup.html`

- Title: `Self-hosted wiki on Docker, with backup & disaster recovery | Wikantik`
- Description: `Run Wikantik on your own infrastructure with Docker and PostgreSQL — plain-markdown data you own — backed by a 3-2-1 backup strategy and tested disaster recovery.`
- Canonical: `https://www.wikantik.com/enterprise/self-hosting-and-backup.html` · OG_TYPE `article`
- Facts (from `docs/DockerDeployment.md`, `docs/BackupAndRecovery.md`, `CLAUDE.md`, hosting section of landing): Docker + PostgreSQL; plain markdown, git-backable, export-anytime (no lock-in). Self-managed is free software; fully-managed is priced on compute, not per seat. Backup: **3-2-1** strategy, off-site pull to NAS (read-only), daily timer, monitored. Data ownership / portability is the headline.
- Sections: Own your data → Run it on Docker + Postgres → No lock-in (markdown export) → Backup: 3-2-1 + off-site → Disaster recovery → Self-managed vs. fully-managed → FAQ.
- Related: access-control-rbac, security-hardening, audit-log. Outbound: `DockerDeployment`/`BackupAndRecovery` wiki pages IF live, else product. Sitemap 0.6.

## Task 17: `compare/index.html` (hub)

**Files:** Create `marketing/compare/index.html`; Modify sitemap.

- Title: `Wikantik vs. Confluence, Notion & friends | Wikantik`
- Description: `How Wikantik compares: a markdown wiki you own, with hybrid AI search and native MCP for agents. See the honest, sporting comparisons against Confluence, Notion, and classic wikis.`
- Canonical: `https://www.wikantik.com/compare/` · OG_TYPE `website`
- Breadcrumb: Home › Compare. JSON-LD: BreadcrumbList (2 items).
- `{{CONTENT}}`: `.hub-hero` + a `.hub-grid` of 3 cards (vs Confluence, vs Notion, AI-native wiki) + reuse the landing's compare-table markup (copy the existing `<table class="compare-table">` from `index.html`) so the hub carries the at-a-glance grid. `.doc-cta`. Sitemap 0.8.

## Task 18: `compare/confluence-alternative.html`

- Title: `A self-hosted Confluence alternative you actually own | Wikantik`
- Description: `Looking for a Confluence alternative? Wikantik is a self-hosted, plain-markdown wiki with hybrid AI search and native MCP for agents — your data, your infra, no per-seat pricing.`
- Canonical: `https://www.wikantik.com/compare/confluence-alternative.html` · OG_TYPE `article`
- Tone: factual, sporting (match landing's "we'll keep it sporting" voice). Only defensible, well-known claims about Confluence (hosted SaaS or self-managed Data Center, per-seat pricing, not markdown-native, no native MCP/knowledge-graph). Lead with Wikantik's strengths, not competitor bashing.
- Sections: Why people leave Confluence → What Wikantik does differently (markdown you own, hybrid search, MCP, knowledge graph, self-host) → Honest trade-offs (Confluence has a bigger ecosystem / enterprise install base) → Migration thoughts → FAQ.
- Related: notion-alternative, wiki-for-ai-agents, enterprise hub. Outbound: product. Sitemap 0.6.

## Task 19: `compare/notion-alternative.html`

- Title: `A Notion alternative for engineering teams | Wikantik`
- Description: `Wikantik is a Notion alternative built for engineering knowledge: plain markdown you own, git-friendly, with hybrid AI search and native MCP so your agents can use it too.`
- Canonical: `https://www.wikantik.com/compare/notion-alternative.html` · OG_TYPE `article`
- Tone: factual/sporting. Defensible Notion facts (hosted SaaS, block model not plain-markdown files, no self-host, no native MCP/knowledge-graph for your content). Lead with Wikantik strengths.
- Sections: Where Notion stops for eng teams → Wikantik's answer (markdown ownership, self-host, hybrid search, MCP) → Honest trade-offs (Notion's docs/db/UX polish) → FAQ.
- Related: confluence-alternative, wiki-for-ai-agents, platform hub. Outbound: product. Sitemap 0.6.

## Task 20: `compare/wiki-for-ai-agents.html`

- Title: `What is an AI-native wiki? A wiki for LLMs & agents | Wikantik`
- Description: `An AI-native wiki serves humans and machines from one source: native MCP, hybrid retrieval, a knowledge graph, and agent-grade projections. Here's what that means and why it matters.`
- Canonical: `https://www.wikantik.com/compare/wiki-for-ai-agents.html` · OG_TYPE `article`
- Category/definitional page (strong AEO play). Define "AI-native wiki" crisply up top. Contrast a normal wiki (HTML wall, keyword search) with one built for agents (MCP, hybrid retrieval, knowledge graph, projections, change feed). This page should be the best quotable definition on the web.
- Sections: Definition (quotable) → Why classic wikis fail agents → The five things that make a wiki AI-native (MCP, hybrid retrieval, knowledge graph, agent-grade projections, change feed) → How Wikantik implements each (link the platform pages) → FAQ.
- Related: platform hub, mcp-for-ai-agents, hybrid-retrieval. Outbound: product. Sitemap 0.6.

---

## Task 21: Wire-up, deploy allowlist, docs, final verification

**Files:**
- Modify: `bin/deploy-marketing.sh` (WEB_FILES allowlist)
- Modify: `marketing/README.md`
- Verify: full site

- [ ] **Step 1: Add new web assets to the `WEB_FILES` allowlist in `bin/deploy-marketing.sh`**

Change the `WEB_FILES=( … )` array to include the new dirs/files. The dev-only `templates/`, `test/`, `form-backend/`, `form-helper.mjs`, `verified-wiki-links.json`, `README.md`, `FORM-SETUP.md` MUST stay out of it.

```bash
WEB_FILES=(
    index.html
    styles.css
    favicon.svg
    ads.txt
    robots.txt
    sitemap.xml
    assets
    platform
    enterprise
    compare
)
```

- [ ] **Step 2: Confirm the verify step covers a new path**

In `bin/deploy-marketing.sh`, ensure the on-origin verification list includes at least one new path (e.g. `/platform/` and `/sitemap.xml`). If the verify list is a hardcoded set of paths, add `platform/index.html` and `sitemap.xml`; if it derives from `WEB_FILES`, no change needed. (Read the verify section before editing; do not break the existing checks.)

- [ ] **Step 3: Update `marketing/README.md`**

Document: the new multi-page structure (platform/, enterprise/, compare/), the `templates/page.template.html` copy-from workflow, the root-absolute link rule, the `verified-wiki-links.json` allowlist rule, and the test commands (`node --test marketing/test/`). Note that `templates/`, `test/`, `verified-wiki-links.json` are dev-only and excluded from `WEB_FILES`.

- [ ] **Step 4: Full test run**

Run: `node --test marketing/test/`
Expected: PASS — all ~19 pages conform; sitemap is a bijection with the pages; no unverified wiki links.

- [ ] **Step 5: Internal-link sanity check**

Run a quick check that every root-absolute internal href points at a file that exists:

```bash
cd marketing && grep -rohE 'href="/[a-z0-9/_-]+\.html"' index.html platform enterprise compare \
  | sed -E 's/.*href="(\/[^"]+)".*/\1/' | sort -u \
  | while read -r p; do [ -f ".${p}" ] || echo "MISSING: ${p}"; done; cd ..
```

Expected: no `MISSING:` lines. Fix any broken links, re-run tests.

- [ ] **Step 6: Commit**

```bash
git add bin/deploy-marketing.sh marketing/README.md
git commit -m "feat(marketing): add platform/enterprise/compare + sitemap/robots to deploy allowlist; update README"
```

- [ ] **Step 7: (Manual, user-run) Deploy**

Deploy is interactive (sudo on the marketing host). Hand back to the user:
`! bin/deploy-marketing.sh`
Then purge the Cloudflare cache for `/sitemap.xml`, `/robots.txt`, and any new paths Google may have fetched as 404. Submit the new `https://www.wikantik.com/sitemap.xml` in Google Search Console.

---

## Self-review notes (coverage check)

- Spec §3 IA (19 pages) → Tasks 2–20 (landing + 3 hubs + 15 deep-dives). ✓
- Spec §5 template / §7 look-and-feel → Task 1 (CSS + template). ✓
- Spec §6 SEO (meta, canonical, JSON-LD types, internal mesh, sitemap, robots, AEO) → template + per-page JSON-LD + footer mesh + Task 1/2 sitemap+robots + FAQ blocks. ✓
- Spec §6 outbound-authority + "no dead wiki links" → `verified-wiki-links.json` + `links.test.mjs` + per-page Step 5. ✓
- Spec §8 content sourcing → per-page Facts lists cite exact docs. ✓
- Spec §9 deploy → Task 21 (WEB_FILES, verify, README). ✓
- Spec §10 tests (offline, deterministic, canonical-for-index) → Task 2. ✓
- Build sequence (spec §3) → Task order: foundation → harness/landing → platform → enterprise → compare → wire-up. ✓
