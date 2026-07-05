# Wikantik marketing site (www.wikantik.com)

Static multi-page site. **No build step.** Edit the HTML / `styles.css` directly
and upload the bundle to the static host serving the `www` domain.

## Site structure
- `index.html` — landing page (the centerpiece; hero, pillars, platform/enterprise
  teasers, compare table, hosting, lead form + inline JS).
- `platform/` — hub + deep-dives on the AI engine (MCP, hybrid retrieval, RAG context
  bundles, knowledge graph, ontology/SPARQL, page graph, agent-grade content,
  structural spine).
- `enterprise/` — hub + deep-dives on security/identity/ops (SSO, SCIM, audit log,
  RBAC, security hardening, self-hosting & backup).
- `compare/` — hub + comparison pages (vs Confluence, vs Notion, AI-native wiki).
- `sitemap.xml`, `robots.txt` — crawl surface for the www origin (robots welcomes AI
  crawlers and points at the sitemap).

## Shared files / brand
- `styles.css` — the single shared stylesheet for every page (token system at the top,
  page-type components in the "Multi-page additions" block).
- `favicon.svg`, `assets/wikantik-logo-512.png` — brand.
- `form-backend/Code.gs` — Google Apps Script for the contact form.
- `form-helper.mjs` + `test/form.test.mjs` — unit-tested form serialization (kept in
  sync with the inline copy in `index.html`).

## Adding a page
1. Copy `templates/page.template.html` to the target path (e.g. `platform/foo.html`).
2. Fill the head slots (`{{TITLE}}`, `{{DESCRIPTION}}`, `{{CANONICAL}}`, `{{OG_TYPE}}`,
   `{{PAGE_JSONLD}}`), the `{{BREADCRUMB}}`, and `{{CONTENT}}`. Leave nav and footer
   identical to the template.
3. All internal links and assets are **root-absolute** (`/styles.css`, `/platform/…`).
4. Outbound links to the live wiki are allowed ONLY if the exact URL is in
   `verified-wiki-links.json` (each entry was confirmed to return 200). Otherwise link
   to the product root `https://wiki.wikantik.com/`.
5. Add the page's `<loc>` to `sitemap.xml` (hub priority 0.8, deep-dive 0.6).
6. Validate, then commit.

`templates/`, `test/`, `verified-wiki-links.json`, `form-helper.mjs`, `*.md` are
**dev-only** and excluded from the `WEB_FILES` allowlist in `bin/deploy-marketing.sh`
(they never reach the public docroot).

## Tests
The SEO-lint / sitemap / link tests enforce the per-page invariants and a sitemap
bijection. Run the full suite (use the glob form — Node 24's `--test` does not accept a
bare directory argument):

    node --test marketing/test/*.test.mjs

While building a cluster (before its sitemap entries exist), validate just that
directory's pages with the dev validator:

    node marketing/test/pagecheck.mjs platform

## Form backend setup (Google Sheet)
1. Create a Google Sheet to hold leads.
2. Extensions → Apps Script. Paste `form-backend/Code.gs`. Save.
3. Deploy → New deployment → type **Web app**.
   - Execute as: **Me**
   - Who has access: **Anyone**
4. Copy the deployment **/exec URL**.
5. In `index.html`, set `LEAD_ENDPOINT` to that URL.
6. Submit a test from the page; confirm a row appears in the Sheet.
   To change the endpoint later, edit `LEAD_ENDPOINT` and re-upload.

## Run the form unit test
    node test/form.test.mjs

## Deploy the site
Run the deploy script from the repo root:

    bin/deploy-marketing.sh

It rsyncs the web bundle (`index.html`, `styles.css`, `favicon.svg`, `ads.txt`,
`assets/`) to a staging dir on the marketing host, copies it into the nginx
docroot under sudo, restores `www-data` ownership, and verifies the key files
serve 200 on-origin. The host's sudo is **not** passwordless, so it prompts for
the sudo password — run it yourself (or `! bin/deploy-marketing.sh` inside a
Claude session). Add new web files to the `WEB_FILES` allowlist in the script.

After deploy, purge the Cloudflare cache for any path Google may have fetched as
a 404 (notably `/ads.txt`).
