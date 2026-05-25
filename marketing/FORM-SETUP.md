# Contact form setup — www.wikantik.com → Google Sheet

The "Get in touch" form on the landing page POSTs to a **Google Apps Script web
app** that appends a row to a **Google Sheet**. No server, no build step. This
is the one-time setup and the maintenance procedure.

Status: **live as of 2026-05-25** — the steps below are recorded for re-setup,
endpoint rotation, or moving the Sheet to another account.

## How it works

- `index.html` contains an inline `<script>` with a `LEAD_ENDPOINT` constant and
  a submit handler. The same serialization logic lives (unit-tested) in
  `form-helper.mjs` / `test/form.test.mjs`; keep the inline copy in sync.
- On submit the handler trims fields, drops bots via a honeypot
  (`company_url`), and `fetch`-POSTs `{name, email, use_case}` as JSON.
- `form-backend/Code.gs` is the Apps Script `doPost`: it appends
  `[timestamp, name, email, use_case]` to the first sheet (writing the header
  row on first run) and returns JSON.

### Why `mode: 'no-cors'`

Apps Script web apps return **no CORS headers**, so a browser on
`www.wikantik.com` cannot read the response cross-origin. The fetch therefore
uses `mode: 'no-cors'` (a "simple" `text/plain` request, no preflight): the row
is written reliably, but the response is **opaque** — we can't read it. So:

- A resolved `fetch` ⇒ we show the "✓ Got it" success state.
- We **cannot** detect server-side failures (e.g. a Sheets quota error). That's
  an accepted trade-off: client-side validation + the honeypot already gate the
  input, and a genuine network failure still rejects the `fetch` and triggers
  the "email us directly" mailto fallback.

## First-time setup

### 1. Create the lead Sheet
1. Sign in to the Google account that should own the leads (use a long-lived
   one — the script runs **as this account**).
2. https://sheets.google.com → **Blank spreadsheet**. Name it "Wikantik leads".
   Leave it empty; the header row is written on the first submission.

### 2. Add the Apps Script
3. In the sheet: **Extensions → Apps Script**.
4. Delete the stub, paste the entire contents of `form-backend/Code.gs`, **Save**.

### 3. Deploy as a Web app
5. **Deploy → New deployment**.
6. **⚙️ (Select type) → Web app**.
7. **Execute as: Me** · **Who has access: Anyone** (literally "Anyone", not
   "Anyone with a Google account").
8. **Deploy → Authorize access**. You'll hit "Google hasn't verified this app"
   (normal for a private script): **Advanced → Go to … (unsafe) → Allow**.
9. Copy the **Web app URL** ending in `/exec`.

### 4. Wire it into the page
10. Set `LEAD_ENDPOINT` in `index.html` to the `/exec` URL.
11. Confirm the fetch uses `mode: 'no-cors'` (see rationale above).
12. Re-deploy the static bundle (see "Deploying the site" below).

### 5. Test
13. Submit the form on https://www.wikantik.com → expect "✓ Got it — we'll be in
    touch." and a new row in the Sheet within a second or two.

## Maintenance

### Change the script WITHOUT changing the URL
Edit `Code.gs`, **Save**, then **Deploy → Manage deployments → ✏️ (edit) →
Version: New version → Deploy**. This keeps the same `/exec` URL.
**Do not** use "New deployment" for edits — that mints a new URL and forces a
site re-deploy.

### Change the endpoint (new Sheet/account)
Update `LEAD_ENDPOINT` in `index.html` and re-deploy the site.

## Deploying the site

The site is served by nginx on host `cloudflare` (docroot
`/var/www/www.wikantik.com`), fronted by the cloudflared tunnel. To push an
updated bundle from this repo:

```bash
# from repo root — stage the web files (not form-backend/test/helper/this doc)
rsync -av marketing/index.html marketing/styles.css marketing/favicon.svg \
      cloudflare:~/wikantik-deploy/site/
rsync -av marketing/assets/wikantik-logo-512.png \
      cloudflare:~/wikantik-deploy/site/assets/

# then on the host (sudo needs a password):
ssh cloudflare
sudo cp -r ~/wikantik-deploy/site/* /var/www/www.wikantik.com/
sudo chown -R www-data:www-data /var/www/www.wikantik.com
```

`index.html` is served `Cache-Control: no-cache`, so HTML changes go live on the
next upload; no nginx reload is needed for content changes.
