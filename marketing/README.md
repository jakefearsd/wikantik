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
Upload `index.html`, `styles.css`, `favicon.svg`, and `assets/` to the static
host for www.wikantik.com. (DNS cutover from the current Squarespace placeholder
is a separate operator step.)
