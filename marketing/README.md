# Wikantik marketing site (www.wikantik.com)

Static single-page site. No build step. Edit `index.html` / `styles.css` and
upload the bundle to the static host serving the `www` domain.

## Files
- `index.html` — the page (all sections + inline form JS)
- `styles.css` — styling
- `favicon.svg`, `assets/wikantik-logo-512.png` — brand
- `form-backend/Code.gs` — Google Apps Script for the contact form
- `form-helper.mjs` + `test/form.test.mjs` — unit-tested form serialization
  (kept in sync with the inline copy in `index.html`)

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
