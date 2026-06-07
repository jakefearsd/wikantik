# Marketing site expansion — design spec

**Date:** 2026-06-07
**Status:** Approved for planning
**Owner:** Jacob Fear
**Scope:** Expand `marketing/` (www.wikantik.com) from a single landing page into a
multi-page, SEO-optimized static site that markets Wikantik's enterprise and
AI-platform ("algorithmic") capabilities, while keeping the landing page the
strong centerpiece. All static HTML, no build step.

---

## 1. Goals & non-goals

### Goals
- Refresh the landing page (`index.html`) to add enterprise + platform teasers and
  a richer nav/footer, **without** diluting its punchy voice or its role as the
  centerpiece.
- Add genuinely substantive deep-dive pages for the enterprise features and the
  AI-platform algorithms, so the site has real SEO surface area and credibility.
- Go deep on technical SEO (structured data, internal-link mesh, sitemap/robots,
  AEO/answer-engine optimization).
- Link out from deep-dives to the **live wiki** (`wiki.wikantik.com`) design docs and
  to the live product as authority/proof.
- Keep the existing look & feel (terracotta brand, one shared `styles.css`,
  no build step) and the existing deploy mechanics.
- Be measurable: a clean sitemap + per-page canonical/meta so traffic can be watched
  in Google Search Console.

### Non-goals
- No SSG, templating engine, or build step (explicit constraint: "all static HTML").
- No new backend or infra; the lead form and its Apps Script backend are unchanged.
- No pricing/checkout system; commercial intent stays funneled through the existing
  lead form + "Explore the live wiki" CTAs.
- Not publishing the repo's internal `docs/*.md` as pages on www (we link to the
  live wiki instead).

### Success criteria
- ~19 pages live, each with unique title/description/canonical, valid JSON-LD, and a
  place in `sitemap.xml`.
- `seo-lint` + `sitemap` tests green; existing form test green.
- Every internal link resolves (relative paths); every outbound wiki link returns 200
  at build time (or falls back to a live product link).
- Lighthouse/CWV stays in the green (static, system fonts, one small CSS, JS only for
  the form).

---

## 2. Approach

**Hand-authored static HTML, no build step, one shared `styles.css`.**

Alternatives considered and rejected:
- *SSG / templating build* — contradicts the "all static HTML / no build step"
  constraint; adds a failure surface and a toolchain to maintain.
- *Tiny Node prebuild to stamp shared head/nav/footer* — same constraint violation;
  also makes the served artifact differ from the source.

Consistency that a build would normally enforce (shared head/nav/footer, no drift) is
instead guaranteed by a **Node "SEO-lint" test** that walks every HTML file and fails
on drift. This keeps pure static files while preventing the classic multi-page-static
problem of pages silently diverging.

---

## 3. Information architecture

```
www.wikantik.com/
├── index.html                         landing — refreshed, stays the centerpiece
├── platform/                          HUB: how the AI-native engine works
│   ├── index.html
│   ├── mcp-for-ai-agents.html         "MCP server for your knowledge base"
│   ├── hybrid-retrieval.html          BM25 + dense + KG rerank (RAG search)
│   ├── knowledge-graph.html           LLM-extracted entities & relations
│   ├── page-graph.html                wikilink graph, canonical IDs, clusters
│   ├── agent-grade-content.html       runbooks, page projections, verification
│   └── structural-spine.html          machine-queryable structural index
├── enterprise/                        HUB: security, identity, compliance, ops
│   ├── index.html
│   ├── sso-saml-oidc.html             SSO (SAML, OIDC, Google)
│   ├── scim-provisioning.html         SCIM 2.0 IdP onboarding/offboarding
│   ├── audit-log.html                 tamper-evident audit trail
│   ├── access-control-rbac.html       policy grants, ACLs, groups
│   ├── security-hardening.html        NIST passwords, CSRF/CSP, deserialization
│   └── self-hosting-and-backup.html   Docker, data ownership, backup & DR
├── compare/                           HUB: high-intent "alternative" queries
│   ├── index.html
│   ├── confluence-alternative.html
│   ├── notion-alternative.html
│   └── wiki-for-ai-agents.html        category page ("AI-native wiki")
├── sitemap.xml                        all pages, lastmod/priority
└── robots.txt                         allow all + AI crawlers, sitemap pointer
```

**~19 pages.** Hub pages (`platform/index.html`, `enterprise/index.html`,
`compare/index.html`) are short overviews that link to their children + each other.
Deep-dive pages are 800–1,400 words of real technical content (anti-thin-content).

### Build sequence (highest value first)
1. Shared CSS additions + page template + landing refresh + nav/footer + `sitemap.xml`
   + `robots.txt` + the two test files.
2. `platform/` hub + `mcp-for-ai-agents` + `hybrid-retrieval` (highest-intent
   technical search).
3. `enterprise/` hub + `sso-saml-oidc` + `scim-provisioning` + `audit-log` (the new
   enterprise emphasis).
4. `compare/` hub + the three comparison pages.
5. Remaining platform pages (`knowledge-graph`, `page-graph`, `agent-grade-content`,
   `structural-spine`) and enterprise pages (`access-control-rbac`,
   `security-hardening`, `self-hosting-and-backup`).

Sequencing lets us ship and index the best pages first even if later pages are
trimmed.

---

## 4. Audience & keyword strategy

Primary audiences (per brainstorming): **AI/platform engineers** and
**enterprise/IT buyers**. The existing "eng leaders / teams whose wiki went stale"
angle stays on the landing page.

| Cluster | Primary intent keywords (illustrative) |
|---|---|
| platform/mcp-for-ai-agents | "MCP server for wiki", "knowledge base for AI agents", "wiki MCP" |
| platform/hybrid-retrieval | "hybrid search RAG", "BM25 + dense retrieval", "RAG knowledge base" |
| platform/knowledge-graph | "knowledge graph wiki", "entity extraction wiki" |
| platform/page-graph | "wikilink graph", "wiki backlinks graph" |
| platform/agent-grade-content | "agent-grade documentation", "runbooks for AI agents" |
| platform/structural-spine | "machine-queryable wiki index" |
| enterprise/sso-saml-oidc | "self-hosted wiki SSO", "SAML wiki", "OIDC wiki" |
| enterprise/scim-provisioning | "SCIM wiki", "SCIM 2.0 user provisioning" |
| enterprise/audit-log | "tamper-evident audit log", "wiki audit trail" |
| enterprise/access-control-rbac | "wiki RBAC", "page-level ACL wiki" |
| enterprise/security-hardening | "secure self-hosted wiki", "NIST password wiki" |
| enterprise/self-hosting-and-backup | "self-hosted wiki Docker", "wiki backup DR" |
| compare/confluence-alternative | "self-hosted Confluence alternative" |
| compare/notion-alternative | "Notion alternative for engineering teams" |
| compare/wiki-for-ai-agents | "AI-native wiki", "wiki for LLMs" |

Keyword lists are directional, not literal — copy stays natural and human, never
keyword-stuffed.

---

## 5. Page template (every page)

A documented canonical structure all pages follow (enforced by `seo-lint`):

**`<head>`**
- `<meta charset>`, viewport, adsense account meta (sitewide, as today).
- Unique `<title>` — keyword-front-loaded, ≤60 chars.
- Unique `<meta name="description">` — ≤155 chars.
- `<link rel="canonical">` — absolute `https://www.wikantik.com/<path>`.
- `<meta name="robots" content="index,follow">`.
- Open Graph (`og:type`, `og:url`, `og:title`, `og:description`, `og:image`) +
  Twitter card.
- `<link rel="icon">`, `<link rel="stylesheet" href="/styles.css">` (root-absolute so
  it works from subdirectories).
- JSON-LD: sitewide `Organization` + `WebSite`; page-type schema (see §6).

**`<body>`**
- Shared `<header class="nav">` — brand + primary nav (Platform, Enterprise, Compare,
  CTA). Same markup on every page; nav links are root-absolute.
- Breadcrumb bar on every non-landing page (visible + `BreadcrumbList` JSON-LD).
- Exactly one `<h1>`; logical `<h2>`/`<h3>`; descriptive anchor text; image alt text.
- Deep-dive body layout: intro definitional paragraph → sections → "How it works" →
  FAQ block → "Related" cards → CTA.
- Shared expanded `<footer>` that doubles as a sitemap nav (columns: Platform,
  Enterprise, Compare, Company) — strong internal linking on every page.

Boilerplate (head/nav/footer) is duplicated across static files by necessity; the
`seo-lint` test guards against drift.

---

## 6. SEO architecture (deep)

### On-page
- One `<h1>`; semantic heading hierarchy; descriptive internal anchor text (no "click
  here"); alt text on all images.
- Keyword-front-loaded titles; compelling meta descriptions (CTR, not ranking, but
  matters).
- Root-absolute internal links so the link graph is unambiguous.

### Structured data (JSON-LD)
- Sitewide: `Organization` (name, url, logo) + `WebSite` (url, name).
- Landing: `SoftwareApplication` (extend the existing block).
- Each deep-dive: `TechArticle` (headline, description, author, datePublished,
  dateModified, mainEntityOfPage).
- Every non-landing page: `BreadcrumbList`.
- Pages with a Q&A section: `FAQPage`.

### Internal-link mesh (biggest lever for a new site)
- Landing → both hubs + 2–3 marquee deep-dives.
- Hub → all its children + sibling hubs.
- Deep-dive → its hub (breadcrumb) + 2–3 sibling deep-dives ("Related") + relevant
  cross-cluster page where natural.
- Footer sitemap nav present on **every** page.

### Outbound authority links
- Each deep-dive links to the matching **live** wiki design doc where one exists and
  returns 200, e.g.:
  - hybrid-retrieval → `wiki.wikantik.com/wiki/HybridRetrieval` ✓ (verified live)
  - structural-spine → `wiki.wikantik.com/wiki/StructuralSpineDesign` ✓ (verified live)
- **Not every repo doc maps to a live slug** (e.g. `ScimProvisioningDesign` 404s).
  Rule: verify each outbound wiki URL returns 200 at build time; if none exists for a
  topic, link to the **live product surface** instead (e.g. `wiki.wikantik.com/` or a
  relevant live page) and/or a live page that does exist. The link-check test enforces
  "no dead outbound wiki links shipped."
- All deep-dives also link to the live product ("Explore the live wiki").

### Crawlability
- `robots.txt` (www origin, separate from the wiki's): `Allow: /`, explicit welcome
  for AI crawlers (GPTBot, ClaudeBot, PerplexityBot, Google-Extended, etc., consistent
  with the "get noticed by AI" goal), and `Sitemap: https://www.wikantik.com/sitemap.xml`.
- `sitemap.xml`: every page with `<loc>`, `<lastmod>`, sensible `<priority>`
  (landing 1.0, hubs 0.8, deep-dives 0.6).

### AEO / answer-engine optimization
- Each deep-dive opens with a crisp, quotable definitional paragraph.
- Question-style `<h2>`s and an `FAQPage`-backed FAQ block so featured snippets and LLM
  answer engines (ChatGPT/Perplexity/Claude) can quote/cite. On-brand for a product
  about AI reading content.

### Performance / Core Web Vitals
- Static HTML; system font stack (already); single shared CSS; JS only on the landing
  form. No web fonts, no heavy images (reuse existing logo asset; new inline SVG/emoji
  for icons as today).

---

## 7. Look & feel

Keep the terracotta brand, design tokens, type scale, and existing components.
**Extend the single shared `styles.css`** (no per-page CSS) with reusable patterns:
- `.breadcrumb` bar.
- Article/prose layout for deep-dives (readable max-width, heading rhythm).
- Hub grid (reuse `.pillar` / `.host-card` visual language).
- "Related pages" cards.
- Callout/feature blocks.
- Expanded multi-column footer (sitemap nav).
- Matching mobile rules in the existing `@media (max-width:720px)` block.

**Decision:** all sitewide assets and nav/footer links use **root-absolute paths**
(`/styles.css`, `/platform/`, `/enterprise/…`) so every page — root or subdirectory —
resolves them identically. The existing landing page is updated from its relative
`href="styles.css"` to `/styles.css` to match. (Both work at the root; root-absolute is
the only form that also works from `/platform/*` and `/enterprise/*`.)

---

## 8. Content sourcing & accuracy

Deep-dive substance comes from the repo's own docs/code so claims are true:
`ScimProvisioning.md`, `SingleSignOn.md` / `FullOAuth.md` / `OAuthImplementation.md`,
`AuditLog.md` + `AuditLogDesign.md`, `HybridRetrieval.md`, `KnowledgeGraphRerank.md`,
`KgInclusionPolicy.md`, `PageGraphVsKnowledgeGraph.md`, `AgentGradeContentDesign.md`,
`StructuralSpineDesign.md`, `GoodMcpDesign.md`, `BackupAndRecovery.md`,
`ObservabilityDesign.md`, and the security model in `CLAUDE.md`. Specific claims
(endpoints, claim config, permission names, tool counts) are verified against code
while writing — no invented capabilities. Comparison pages stay factual and "sporting"
in tone (matching the existing site voice); claims about competitors are limited to
defensible, well-known facts.

---

## 9. Deploy (unchanged mechanics)

- Add the new files/dirs to the `WEB_FILES` allowlist in `bin/deploy-marketing.sh`:
  `platform`, `enterprise`, `compare`, `sitemap.xml`, `robots.txt`. (rsync already
  recurses directories.)
- Existing on-origin 200 verification and the Cloudflare cache-purge note still apply;
  extend the verify step to spot-check a couple of new key paths.
- Update `marketing/README.md` to document the new structure, the page template, and
  the test commands.

---

## 10. Testing

Test-first where there is real logic:
- `marketing/test/seo-lint.test.mjs` — walks every `*.html` under `marketing/`
  (excluding dev-only files) and asserts: exactly one `<h1>`; non-empty unique
  `<title>` and `<meta description>`; a `<link rel=canonical>` matching the file's path;
  OG title/description present; `nav` + `footer` present; at least one JSON-LD block
  that parses as valid JSON; the page's canonical path appears in `sitemap.xml`.
  Canonical derivation: `index.html` → `https://www.wikantik.com/`; `X/index.html` →
  `https://www.wikantik.com/X/`; `X/page.html` → `https://www.wikantik.com/X/page.html`.
- `marketing/test/sitemap.test.mjs` — bijection between shipped HTML pages and
  `sitemap.xml` `<loc>` entries (no orphan pages, no dead sitemap entries); every
  `<loc>` is an absolute `https://www.wikantik.com/...` URL.
- Outbound-wiki link check — **offline and deterministic by default** (unit tests must
  not hit the network): the test collects every `wiki.wikantik.com` link in the HTML
  and asserts each appears in a committed `marketing/verified-wiki-links.json`
  allowlist of URLs already confirmed to return 200. Adding a deep-dive outbound link
  therefore requires verifying it live once and recording it in that file. A separate,
  opt-in `--online` mode (not run by default) actually fetches each allowlisted URL to
  catch pages that later 404. Topics with no live wiki page link to the product surface
  instead.
- Existing `marketing/test/form.test.mjs` stays green; the inline form JS in
  `index.html` stays in sync with `form-helper.mjs`.

Run: `node --test marketing/test/` (or per-file `node marketing/test/<f>.mjs`,
matching the existing convention).

---

## 11. Risks & mitigations
- **Thin/doorway content penalty** — mitigated by 800–1,400-word substantive pages
  sourced from real docs; hubs clearly add navigational value.
- **Boilerplate drift across 19 static files** — mitigated by `seo-lint`.
- **Dead outbound wiki links** — mitigated by build-time 200 verification + product
  fallback.
- **Landing page dilution** — mitigated by treating the refresh conservatively: add
  teasers + nav/footer, keep the voice and length disciplined.
- **Scope/time** — mitigated by the build sequence; later pages can be deferred without
  blocking the high-value ones.

---

## 12. Out of scope / future
- Per-page OG images (could add later; for now reuse the logo).
- Blog / changelog on www.
- Analytics beyond Search Console + AdSense already present.
- Localization.
