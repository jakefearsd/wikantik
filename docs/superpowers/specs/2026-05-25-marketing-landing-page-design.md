# Marketing Landing Page — www.wikantik.com

**Date:** 2026-05-25
**Status:** Design approved (brainstorming complete)
**Author:** Jacob Fear (with Claude)

## Summary

A single-page marketing site for **www.wikantik.com**, currently a parked
Squarespace placeholder. Greenfield — not a modification of anything existing.
It pitches Wikantik as **the knowledge base for the AI era**: one source of
truth that humans enjoy reading and AI agents can natively read, search, and
reason over. The product itself lives at **wiki.wikantik.com**; this is the
public storefront in front of it.

The page is a **static, framework-free `index.html` + one CSS file** that drops
into the existing static-content directory served from the `www` domain. The
only dynamic element is a contact form that POSTs to a Google Apps Script
web-app endpoint, which appends a row to a Google Sheet.

## Goals

- Explain Wikantik's value and how it differs from other wiki / knowledge-base
  tools (Confluence, Notion, MediaWiki, Obsidian).
- Capture leads via a "Learn more" contact form (→ Google Sheet).
- Offer three convergent paths: explore the live wiki, learn more (contact),
  and sign in / create an account on the hosted instance.
- Be genuinely fun to read — witty, irreverent voice.

## Non-Goals

- No CMS, no framework, no build step. Hand-authored static HTML/CSS.
- No analytics or tracking scripts (consistent with the privacy policy: no
  third-party analytics or advertising).
- No concrete pricing figures — hosting pricing is described qualitatively.
- No customer logos / social proof in v1 (too early).

## Decisions (locked during brainstorming)

| Decision | Choice |
|----------|--------|
| Positioning | "Knowledge base for the AI era" — humans **and** machines, equally |
| Voice | Witty & irreverent (friendly fire at bloated legacy wikis) |
| Visual direction | **Clean & Punchy** — bright, airy, big confident type, soft terracotta glow; design stays out of the copy's way |
| One borrowed beat | A single dark "Terminal moment" (the "ask your wiki" MCP exchange) stolen from the rejected Bold-Terminal direction |
| Primary CTA | Layered: **Explore the live wiki** (low friction) · **Learn more** (contact form) · **Sign in with Google** (quiet) |
| Comparison table | Direct, names competitors; **no self-host row** (don't headline self-hosting) |
| Hosting | **Open on-page section**, both tiers visible; **qualitative pricing only** |
| Hosting pricing language | "Priced on compute, not per seat — lightly marked up. Add your whole team for free." |
| Form backend | Google Apps Script web-app endpoint → appends row to a Google Sheet |

## Brand

Pulled from the live product so the storefront matches the wiki:

- **Primary / accent:** terracotta `#C45D3E`
- **Link / success green:** `#15803d`
- **Ink:** near-black warm `#16110E`; muted brown `#6b5d51`
- **Warm wash background:** `#FAF7F2`
- **Logo:** the serif "W" mark — `#C45D3E` rounded square, white Georgia-serif "W"
  (matches `favicon.svg`). Reuse `docs/brand/wikantik-logo-512.png` and
  `favicon.svg`.
- **Type:** `system-ui, sans-serif` for body/UI; Georgia serif reserved for the
  logo mark. Headlines are heavy-weight sans (800), tight letter-spacing.
- **Operator / contact:** Jacob Fear — `jakefear@gmail.com`.

## Page structure

A single vertical scroll. Section order is fixed:

1. **Nav** — logo + wordmark; anchor links (Why Wikantik · For agents ·
   Compare); terracotta "Learn more" button.
2. **Hero** — eyebrow "The knowledge base for the AI era"; headline
   **"Where knowledge goes to *live*."** (terracotta "live."); humans-and-machines
   subhead; dual CTA (Explore the live wiki → wiki.wikantik.com · Learn more →
   form anchor); quiet "Already in? Sign in with Google" → wiki.wikantik.com
   login. Soft terracotta radial glow, top-right.
3. **Problem** — warm-wash band. "Be honest: nobody's opened your wiki since
   2019." Frames both pains: humans ignore stale wikis, *and* AI assistants
   can't use them. Root cause: built for nobody in particular.
4. **Four pillars** — 2×2 grid:
   - 📝 **Markdown in, freedom out** — plain markdown, git-backed, self-hostable;
     export it, diff it, walk away. No lock-in.
   - 🎯 **Search that actually finds it** — hybrid retrieval (keyword + meaning +
     knowledge-graph rerank). Plain-language questions, right page. Not keyword
     roulette.
   - 🤖 **Your AI's favorite coworker** — native MCP servers; agents query,
     retrieve, and cite your wiki; knowledge graph hands them entities and
     relationships, not a wall of HTML.
   - 👥 **Built for two readers** — every page serves humans (clean, fast
     reading) and machines (token-budgeted projections, runbooks, verification
     metadata). Write once; both get the good version.
5. **Terminal moment** — the one dark band (`#17120F`). "Ask your wiki. Out
   loud. Like a person." A monospace mock MCP exchange: a plain-language question
   → a cited, verified runbook answer. Proof with personality.
6. **Comparison** — "How we're different (We'll keep it sporting. Mostly.)".
   Four rows × three columns (Wikantik · Confluence/Notion · MediaWiki/Obsidian):
   - Plain markdown, you own your data — ✅ / ⚠️ / ✅
   - Hybrid AI search built in — ✅ / ⚠️ / ❌
   - Agents read it natively (MCP) — ✅ / ❌ / ❌
   - Knowledge graph of your content — ✅ / ❌ / ❌
7. **Hosting** — open section, both tiers visible:
   - 🛠 **Self-managed** — "Docker up, point it at Postgres, done." Free. It's
     just software.
   - ☁️ **Fully managed** (highlighted, "EASIEST" badge) — we run/patch/back-up/
     keep-it-fast. "Priced on compute, not per seat — lightly marked up. Add
     your whole team for free."
8. **Learn more** — contact form: Name, Work email, "What would you use it
   for?"; terracotta submit. Inline success + error states (no page nav).
   "No spam. No newsletter you didn't ask for."
9. **Footer** — logo + "Wikantik · built by Jacob Fear"; links: Explore the
   wiki · Privacy · Terms · Contact (`mailto:jakefear@gmail.com`).

Headlines/copy in this doc are the approved drafts; minor polish is expected
during build but the structure and voice are fixed.

## Technical design

### Files & layout

A self-contained bundle that drops into the existing `www` static dir:

```
index.html              # the whole page, semantic sections
styles.css              # brand tokens + layout (no framework)
favicon.svg             # copied from wikantik-frontend/public/favicon.svg
assets/wikantik-logo-512.png   # copied from docs/brand/
```

- **No framework, no build step.** Plain HTML5 + one stylesheet. CSS custom
  properties hold the brand tokens. Responsive via CSS grid/flex with a single
  mobile breakpoint (the 2×2 grids and comparison columns stack on narrow
  screens).
- **Smooth-scroll anchors** for nav links (`#why`, `#agents`, `#compare`,
  `#learn-more`).
- **Minimal JS**, inline in `index.html`: only the form submit handler. No
  third-party scripts. Respects `prefers-reduced-motion` for the glow/scroll.
- **SEO/meta:** title, description, Open Graph + Twitter card tags, canonical
  URL `https://www.wikantik.com/`, `robots: index,follow`, JSON-LD
  `SoftwareApplication`/`Organization`. Reuse the favicon.
- **Accessibility:** semantic landmarks, alt text on the logo, labelled form
  inputs, AA contrast (verify terracotta-on-white and white-on-terracotta),
  visible focus states, keyboard-operable.

### Contact form → Google Sheet

The page is static, so there is no server to receive the POST. Approach:

1. A **Google Apps Script** bound to the target Google Sheet exposes a
   `doPost(e)` web app (deployed "execute as me", "anyone can access"). It
   appends `[timestamp, name, email, use_case]` to the sheet and returns JSON.
2. The form handler `fetch()`-POSTs the fields to the web-app URL. To dodge
   CORS preflight against the Apps Script endpoint, send as
   `Content-Type: text/plain` (or `application/x-www-form-urlencoded`) and parse
   on the script side — the standard Apps-Script-as-form-endpoint pattern.
3. On success: swap the form for an inline thank-you ("Got it — we'll be in
   touch."). On failure: show an inline error and a `mailto:` fallback so a
   submission is never silently lost. **Never swallow the error** — log to
   console with context and surface the fallback.
4. The web-app URL is not a secret (it only accepts appends), but it lives in a
   single clearly-marked constant at the top of the inline script for easy
   rotation. Light anti-spam: a hidden honeypot field; bail if filled.

This keeps the whole site static and host-anywhere while still capturing leads.

### Deployment

Out of scope for the build itself (the static dir / DNS cutover from Squarespace
is an operator step), but the deliverable is the static bundle ready to upload.
Document the Apps Script setup steps (create script, paste `doPost`, deploy as
web app, copy URL into `index.html`) in a short README beside the bundle.

## Testing & verification

No app test harness (this lives outside the Maven build). Verification is
manual and lightweight:

- Open `index.html` locally; confirm layout at desktop + mobile widths.
- Validate HTML (W3C) and check AA contrast on terracotta pairings.
- Submit the form against a test deployment of the Apps Script; confirm a row
  lands in the Sheet and the success state renders; confirm the error path
  shows the `mailto:` fallback when the endpoint is unreachable.
- Lighthouse pass (performance/SEO/accessibility) — expect near-100 given no
  framework and no third-party scripts.

## Open items / future

- Real managed-hosting pricing model (kept qualitative for now).
- Possible later additions: customer logos / testimonials, a short demo video
  or GIF in the Terminal moment, a `/docs` or changelog link.
- DNS cutover from Squarespace to wherever the static bundle is hosted.
