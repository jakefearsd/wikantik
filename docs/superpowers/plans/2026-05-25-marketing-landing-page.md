# Marketing Landing Page (www.wikantik.com) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a static, framework-free single-page marketing site for www.wikantik.com that pitches Wikantik as "the knowledge base for the AI era," in a witty/irreverent voice, with a contact form that appends leads to a Google Sheet.

**Architecture:** One hand-authored `index.html` + one `styles.css`, no build step, no third-party scripts. CSS custom properties hold brand tokens. The only JS is an inline form handler that POSTs to a Google Apps Script web app (`doPost`) which appends a row to a Google Sheet. The bundle lives in `marketing/` in this repo as the source of truth; deployment to the static host is a manual operator step.

**Tech Stack:** HTML5, CSS3 (grid/flex, custom properties), vanilla JS (`fetch`), Google Apps Script. No framework, no bundler, no npm.

**Spec:** `docs/superpowers/specs/2026-05-25-marketing-landing-page-design.md`

**Conventions for this plan:**
- This bundle is outside the Maven build — there is no JUnit harness. Verification is concrete and manual (open in a browser, validate HTML, check the row lands in the Sheet). The one piece of real logic (form field serialization) is factored into a pure function and unit-tested with a tiny Node script.
- Sole developer works directly on `main`. Stage files by name, never `git add -A`. End commit messages with the co-author trailer:
  `Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>`
- Brand tokens: terracotta `#C45D3E`, green `#15803d`, ink `#16110E`, muted `#6b5d51`, warm wash `#FAF7F2`, border `#ece7e0`.

---

## File Structure

```
marketing/
  index.html              # the whole page (all sections + inline form JS)
  styles.css              # brand tokens + layout + all section styles
  favicon.svg             # copied from wikantik-frontend/public/favicon.svg
  assets/
    wikantik-logo-512.png # copied from docs/brand/wikantik-logo-512.png
  form-backend/
    Code.gs               # Google Apps Script doPost (version-controlled reference)
  test/
    form.test.mjs         # Node unit test for the form serialization helper
  README.md               # setup: Apps Script deploy + static-host upload
```

Responsibilities:
- `index.html` — semantic markup for all nine sections + inline `<script>` with the form handler and the testable `serializeForm` helper.
- `styles.css` — all visual styling; one mobile breakpoint at `720px`.
- `form-backend/Code.gs` — server side; appends `[timestamp, name, email, use_case]` to the Sheet.
- `test/form.test.mjs` — asserts `serializeForm` produces the expected payload and honeypot bail behavior.
- `README.md` — operator runbook for deploying the Apps Script and uploading the bundle.

---

## Task 1: Scaffold bundle, copy assets, base styles, RAT exclude

**Files:**
- Create: `marketing/index.html`
- Create: `marketing/styles.css`
- Create: `marketing/favicon.svg` (copy)
- Create: `marketing/assets/wikantik-logo-512.png` (copy)
- Modify: `pom.xml` (add RAT exclude for `marketing/**`)

- [ ] **Step 1: Create the directory and copy brand assets**

```bash
mkdir -p marketing/assets marketing/form-backend marketing/test
cp wikantik-frontend/public/favicon.svg marketing/favicon.svg
cp docs/brand/wikantik-logo-512.png marketing/assets/wikantik-logo-512.png
ls -la marketing marketing/assets
```
Expected: `favicon.svg` in `marketing/`, `wikantik-logo-512.png` in `marketing/assets/`.

- [ ] **Step 2: Create the skeleton `index.html`**

Create `marketing/index.html` with the document shell and empty section anchors (sections are filled in later tasks). The full `<head>` meta/SEO is finalized in Task 11.

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Wikantik — the knowledge base for the AI era</title>
  <link rel="icon" type="image/svg+xml" href="favicon.svg">
  <link rel="stylesheet" href="styles.css">
</head>
<body>
  <!-- nav: Task 2 -->
  <!-- hero: Task 2 -->
  <!-- problem: Task 3 -->
  <!-- pillars: Task 3 -->
  <!-- terminal: Task 4 -->
  <!-- compare: Task 5 -->
  <!-- hosting: Task 6 -->
  <!-- learn-more: Task 7 -->
  <!-- footer: Task 8 -->
  <!-- form script: Task 9 -->
</body>
</html>
```

- [ ] **Step 3: Create `styles.css` with brand tokens and base rules**

Create `marketing/styles.css`:

```css
:root {
  --terracotta: #C45D3E;
  --terracotta-dark: #a84a2f;
  --green: #15803d;
  --ink: #16110E;
  --muted: #6b5d51;
  --soft: #777;
  --wash: #FAF7F2;
  --dark: #17120F;
  --border: #ece7e0;
  --radius: 10px;
  --radius-lg: 12px;
  --maxw: 1040px;
  --font-ui: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
  --font-serif: Georgia, "Times New Roman", serif;
}

* { box-sizing: border-box; }

html { scroll-behavior: smooth; }

body {
  margin: 0;
  font-family: var(--font-ui);
  color: var(--ink);
  background: #fff;
  line-height: 1.6;
  -webkit-font-smoothing: antialiased;
}

a { color: var(--green); text-decoration: none; }
a:hover { text-decoration: underline; }

.container { max-width: var(--maxw); margin: 0 auto; padding: 0 24px; }

.section { padding: 64px 0; }

.eyebrow {
  font-size: 0.72rem; letter-spacing: 0.08em; text-transform: uppercase;
  color: var(--terracotta); font-weight: 700; margin: 0 0 8px;
}

h2.section-title {
  font-size: 2rem; font-weight: 800; letter-spacing: -0.02em;
  line-height: 1.12; margin: 0 0 12px;
}

.center { text-align: center; }

/* Buttons */
.btn {
  display: inline-block; font-family: var(--font-ui); font-size: 0.95rem;
  font-weight: 700; padding: 12px 22px; border-radius: var(--radius);
  cursor: pointer; border: 1px solid transparent; transition: background .15s, transform .05s;
}
.btn:hover { text-decoration: none; }
.btn:active { transform: translateY(1px); }
.btn-primary { background: var(--terracotta); color: #fff; }
.btn-primary:hover { background: var(--terracotta-dark); color: #fff; }
.btn-ghost { background: #fff; color: var(--ink); border-color: var(--border); }
.btn-ghost:hover { border-color: var(--terracotta); color: var(--ink); }

@media (prefers-reduced-motion: reduce) {
  html { scroll-behavior: auto; }
  .btn { transition: none; }
}
```

- [ ] **Step 4: Add `marketing/**` to the Apache RAT excludes**

RAT runs from the root pom; the `marketing/` bundle has no Apache license headers (it is a deliverable, not Java source). Add an exclude so `mvn clean install` stays green.

In `pom.xml`, inside the `apache-rat-plugin` `<excludes>` block (around line 1002, after the last existing `<exclude>`), add:

```xml
              <exclude>marketing/**</exclude>                                  <!-- static marketing site, not part of the Java build -->
```

- [ ] **Step 5: Verify RAT passes and the page opens**

Run: `mvn apache-rat:check -q`
Expected: BUILD SUCCESS (no unapproved-license failures referencing `marketing/`).

Then open `marketing/index.html` in a browser. Expected: a blank page with the Wikantik favicon in the tab title "Wikantik — the knowledge base for the AI era" and no console errors.

- [ ] **Step 6: Commit**

```bash
git add marketing/index.html marketing/styles.css marketing/favicon.svg marketing/assets/wikantik-logo-512.png pom.xml
git commit -m "feat(marketing): scaffold landing-page bundle + brand tokens

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: Nav + Hero

**Files:**
- Modify: `marketing/index.html` (replace nav + hero comments)
- Modify: `marketing/styles.css` (append nav + hero styles)

- [ ] **Step 1: Add the nav and hero markup**

In `marketing/index.html`, replace the `<!-- nav: Task 2 -->` and `<!-- hero: Task 2 -->` comment lines with:

```html
  <header class="nav">
    <div class="container nav-inner">
      <a class="brand" href="#top" aria-label="Wikantik home">
        <span class="brand-mark" aria-hidden="true">W</span>
        <span class="brand-name">Wikantik</span>
      </a>
      <nav class="nav-links" aria-label="Primary">
        <a href="#why">Why Wikantik</a>
        <a href="#agents">For agents</a>
        <a href="#compare">Compare</a>
        <a class="btn btn-primary nav-cta" href="#learn-more">Learn more</a>
      </nav>
    </div>
  </header>

  <main id="top">
  <section class="hero">
    <div class="hero-glow" aria-hidden="true"></div>
    <div class="container hero-inner">
      <p class="eyebrow">The knowledge base for the AI era</p>
      <h1 class="hero-title">Where knowledge goes to <span class="accent">live.</span></h1>
      <p class="hero-sub">A markdown wiki your team actually enjoys — wired so your AI agents can read, search, and reason over everything you know. Same source of truth. Two kinds of readers.</p>
      <div class="hero-cta">
        <a class="btn btn-primary" href="https://wiki.wikantik.com/">Explore the live wiki →</a>
        <a class="btn btn-ghost" href="#learn-more">Learn more</a>
      </div>
      <p class="hero-signin">Already in? <a href="https://wiki.wikantik.com/login">Sign in with Google</a></p>
    </div>
  </section>
```

Note: `<main>` opens here and is closed at the end of the footer task. Sections between are inside `<main>`.

- [ ] **Step 2: Append nav + hero styles to `styles.css`**

```css
/* Nav */
.nav { position: sticky; top: 0; z-index: 10; background: rgba(255,255,255,0.92);
  backdrop-filter: blur(8px); border-bottom: 1px solid var(--border); }
.nav-inner { display: flex; align-items: center; justify-content: space-between; height: 60px; }
.brand { display: flex; align-items: center; gap: 8px; color: var(--ink); font-weight: 700; }
.brand:hover { text-decoration: none; }
.brand-mark { width: 26px; height: 26px; border-radius: 6px; background: var(--terracotta);
  color: #fff; font-family: var(--font-serif); font-weight: 700; display: flex;
  align-items: center; justify-content: center; font-size: 16px; }
.nav-links { display: flex; align-items: center; gap: 20px; font-size: 0.9rem; }
.nav-links > a { color: var(--muted); }
.nav-links > a:hover { color: var(--ink); }
.nav-cta { color: #fff; padding: 8px 14px; font-size: 0.85rem; }
.nav-cta:hover { color: #fff; }

/* Hero */
.hero { position: relative; overflow: hidden; padding: 84px 0 72px; text-align: center; }
.hero-glow { position: absolute; top: -90px; right: -60px; width: 340px; height: 340px;
  border-radius: 50%; background: radial-gradient(circle, #F3C9B5, transparent 70%); }
.hero-inner { position: relative; }
.hero-title { font-size: 3.2rem; font-weight: 800; letter-spacing: -0.03em;
  line-height: 1.05; margin: 0 0 18px; }
.hero-title .accent { color: var(--terracotta); }
.hero-sub { font-size: 1.12rem; color: var(--muted); max-width: 560px; margin: 0 auto 28px; }
.hero-cta { display: flex; gap: 12px; justify-content: center; flex-wrap: wrap; }
.hero-signin { font-size: 0.85rem; color: var(--soft); margin-top: 16px; }
```

- [ ] **Step 3: Verify in browser**

Reload `marketing/index.html`. Expected: sticky white nav with the serif "W" mark + wordmark on the left, three links + a terracotta "Learn more" button on the right; centered hero with the eyebrow, the headline "Where knowledge goes to **live.**" (the word "live." in terracotta), the subhead, two buttons, and the small "Sign in with Google" line. The soft terracotta glow shows top-right. Clicking "Learn more" smooth-scrolls (no target yet — that's fine).

- [ ] **Step 4: Commit**

```bash
git add marketing/index.html marketing/styles.css
git commit -m "feat(marketing): nav + hero

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: Problem + Four Pillars

**Files:**
- Modify: `marketing/index.html` (replace problem + pillars comments)
- Modify: `marketing/styles.css` (append styles)

- [ ] **Step 1: Add the problem and pillars markup**

Replace `<!-- problem: Task 3 -->` and `<!-- pillars: Task 3 -->` with:

```html
  <section class="problem">
    <div class="container center">
      <h2 class="section-title">Be honest: nobody's opened your wiki since 2019.</h2>
      <p class="problem-sub">It's stale, the search is a slot machine, and the knowledge that matters lives in someone's DMs. Now your shiny AI assistant can't use any of it either. Two problems, same root cause: your wiki was built for nobody in particular.</p>
    </div>
  </section>

  <section class="section" id="why">
    <div class="container">
      <p class="eyebrow center">Why Wikantik</p>
      <h2 class="section-title center">Four things your current wiki doesn't do</h2>
      <div class="pillars">
        <article class="pillar">
          <div class="pillar-icon" aria-hidden="true">📝</div>
          <h3>Markdown in, freedom out</h3>
          <p>Plain markdown, git-backed, self-hostable. Your knowledge is yours — export it, diff it, walk away with it. No lock-in, no hostage situation.</p>
        </article>
        <article class="pillar">
          <div class="pillar-icon" aria-hidden="true">🎯</div>
          <h3>Search that actually finds it</h3>
          <p>Hybrid retrieval — keyword + meaning + a knowledge-graph rerank. Ask in plain language, get the right page. Not keyword roulette.</p>
        </article>
        <article class="pillar" id="agents">
          <div class="pillar-icon" aria-hidden="true">🤖</div>
          <h3>Your AI's favorite coworker</h3>
          <p>Native MCP servers mean your agents query, retrieve, and cite your wiki directly. The knowledge graph hands them entities and relationships, not a wall of HTML.</p>
        </article>
        <article class="pillar">
          <div class="pillar-icon" aria-hidden="true">👥</div>
          <h3>Built for two readers</h3>
          <p>Every page serves humans (clean, fast reading) and machines (token-budgeted projections, runbooks, verification metadata). Write once; both get the good version.</p>
        </article>
      </div>
    </div>
  </section>
```

- [ ] **Step 2: Append problem + pillars styles**

```css
/* Problem */
.problem { background: var(--wash); padding: 56px 0; }
.problem .section-title { max-width: 760px; margin-left: auto; margin-right: auto; }
.problem-sub { font-size: 1.05rem; color: var(--muted); max-width: 600px; margin: 0 auto; }

/* Pillars */
.pillars { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; margin-top: 28px; }
.pillar { border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 24px; }
.pillar-icon { font-size: 1.5rem; margin-bottom: 10px; }
.pillar h3 { font-size: 1.1rem; font-weight: 700; margin: 0 0 8px; }
.pillar p { font-size: 0.95rem; color: var(--muted); margin: 0; }
```

- [ ] **Step 3: Verify in browser**

Reload. Expected: a warm-wash "Problem" band with the centered punchline + subhead; below it the "Why Wikantik" section with a 2×2 grid of four bordered pillar cards (emoji, bold title, muted body). The nav "Why Wikantik" link jumps to the pillars section; "For agents" jumps to the third pillar.

- [ ] **Step 4: Commit**

```bash
git add marketing/index.html marketing/styles.css
git commit -m "feat(marketing): problem section + four pillars

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: Terminal moment ("ask your wiki")

**Files:**
- Modify: `marketing/index.html` (replace terminal comment)
- Modify: `marketing/styles.css` (append styles)

- [ ] **Step 1: Add the terminal-moment markup**

Replace `<!-- terminal: Task 4 -->` with:

```html
  <section class="terminal">
    <div class="container center">
      <h2 class="section-title terminal-title">Ask your wiki. Out loud. Like a person.</h2>
      <p class="terminal-sub">Your AI assistant talks to Wikantik over MCP — and answers from <em>your</em> knowledge, with citations.</p>
      <div class="terminal-box" role="img" aria-label="Example: an AI assistant asks Wikantik how to roll back a bad production deploy and receives a cited, recently-verified runbook answer.">
        <div class="t-line t-q">→ "How do we roll back a bad prod deploy?"</div>
        <div class="t-line t-ok">✓ Found runbook: <span class="t-u">Rollback Procedure</span> (verified 3d ago)</div>
        <div class="t-line t-body">&nbsp;&nbsp;1. bin/remote.sh rollback — re-promotes the :rollback image</div>
        <div class="t-line t-body">&nbsp;&nbsp;2. DB + pages persist across the swap …</div>
        <div class="t-line t-meta">cited 2 pages · 1 knowledge-graph entity</div>
      </div>
    </div>
  </section>
```

- [ ] **Step 2: Append terminal styles**

```css
/* Terminal moment */
.terminal { background: var(--dark); color: #F3EBE3; padding: 64px 0; }
.terminal-title { color: #F3EBE3; }
.terminal-sub { color: #b8a99c; max-width: 480px; margin: 0 auto 26px; }
.terminal-box { background: #0F0B09; border: 1px solid #332923; border-radius: var(--radius-lg);
  padding: 20px 22px; max-width: 560px; margin: 0 auto; text-align: left;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 0.84rem; line-height: 1.9; }
.t-q { color: #E0925C; }
.t-ok { color: #7fae7f; }
.t-u { text-decoration: underline; }
.t-body { color: #cfc4ba; }
.t-meta { color: #6b5d51; }
```

- [ ] **Step 3: Verify in browser**

Reload. Expected: a dark (`#17120F`) full-width band with a light heading, muted subhead, and a darker monospace "terminal" card showing the question in terracotta, a green verified-runbook hit, two body lines, and a dim citation line. Confirm the card text is left-aligned and the band contrasts clearly with the white sections above/below.

- [ ] **Step 4: Commit**

```bash
git add marketing/index.html marketing/styles.css
git commit -m "feat(marketing): terminal 'ask your wiki' moment

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 5: Comparison table

**Files:**
- Modify: `marketing/index.html` (replace compare comment)
- Modify: `marketing/styles.css` (append styles)

- [ ] **Step 1: Add the comparison markup**

Replace `<!-- compare: Task 5 -->` with:

```html
  <section class="section" id="compare">
    <div class="container">
      <h2 class="section-title center">How we're different</h2>
      <p class="compare-note center">(We'll keep it sporting. Mostly.)</p>
      <div class="compare-wrap">
        <table class="compare-table">
          <thead>
            <tr>
              <th scope="col"><span class="vh">Capability</span></th>
              <th scope="col" class="col-us">Wikantik</th>
              <th scope="col">Confluence / Notion</th>
              <th scope="col">MediaWiki / Obsidian</th>
            </tr>
          </thead>
          <tbody>
            <tr><th scope="row">Plain markdown, you own your data</th><td class="col-us">✅</td><td>⚠️</td><td>✅</td></tr>
            <tr><th scope="row">Hybrid AI search built in</th><td class="col-us">✅</td><td>⚠️</td><td>❌</td></tr>
            <tr><th scope="row">Agents read it natively (MCP)</th><td class="col-us">✅</td><td>❌</td><td>❌</td></tr>
            <tr><th scope="row">Knowledge graph of your content</th><td class="col-us">✅</td><td>❌</td><td>❌</td></tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
```

- [ ] **Step 2: Append comparison styles**

```css
/* Comparison */
.compare-note { color: var(--soft); font-size: 0.9rem; margin: 0 0 22px; }
.compare-wrap { max-width: 720px; margin: 0 auto; border: 1px solid var(--border);
  border-radius: var(--radius-lg); overflow: hidden; }
.compare-table { width: 100%; border-collapse: collapse; font-size: 0.92rem; }
.compare-table th[scope="col"] { background: var(--wash); font-weight: 700; padding: 12px;
  text-align: center; color: var(--soft); }
.compare-table th.col-us, .compare-table td.col-us { color: var(--terracotta); font-weight: 700; }
.compare-table th[scope="row"] { text-align: left; font-weight: 500; padding: 12px;
  border-top: 1px solid #f0f0f0; }
.compare-table td { text-align: center; padding: 12px; border-top: 1px solid #f0f0f0; }
.vh { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }
```

- [ ] **Step 3: Verify in browser**

Reload. Expected: centered "How we're different" title, the "(We'll keep it sporting. Mostly.)" note, and a bordered four-row table. The "Wikantik" column header and its check cells are terracotta. Row labels are left-aligned; the four rows match the spec (no self-host row). Confirm the empty top-left header cell reads as blank visually (screen-reader text "Capability" is hidden).

- [ ] **Step 4: Commit**

```bash
git add marketing/index.html marketing/styles.css
git commit -m "feat(marketing): comparison table

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 6: Hosting section

**Files:**
- Modify: `marketing/index.html` (replace hosting comment)
- Modify: `marketing/styles.css` (append styles)

- [ ] **Step 1: Add the hosting markup**

Replace `<!-- hosting: Task 6 -->` with:

```html
  <section class="section hosting">
    <div class="container">
      <p class="eyebrow center">Hosting</p>
      <h2 class="section-title center">Run it your way. Or don't run it at all.</h2>
      <p class="hosting-sub center">Wikantik is yours either way. Host it yourself, or let us keep it alive while you get on with your life.</p>
      <div class="host-cards">
        <article class="host-card">
          <div class="host-icon" aria-hidden="true">🛠</div>
          <h3>Self-managed</h3>
          <p>Docker up, point it at Postgres, done. Runs on your infra, your rules. Open and yours forever — no asterisks, no seat counter.</p>
          <p class="host-price">Free. It's just software.</p>
        </article>
        <article class="host-card host-card--featured">
          <span class="host-badge">Easiest</span>
          <div class="host-icon" aria-hidden="true">☁️</div>
          <h3>Fully managed</h3>
          <p>We run it, patch it, back it up, and keep it fast. You write pages and point your agents at it.</p>
          <p class="host-price">Priced on compute, not per seat — lightly marked up. Add your whole team for free.</p>
        </article>
      </div>
      <div class="center" style="margin-top: 28px;">
        <a class="btn btn-primary" href="#learn-more">Learn more →</a>
      </div>
    </div>
  </section>
```

- [ ] **Step 2: Append hosting styles**

```css
/* Hosting */
.hosting-sub { color: var(--muted); max-width: 460px; margin: 0 auto 30px; }
.host-cards { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; max-width: 720px;
  margin: 0 auto; }
.host-card { border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 24px;
  position: relative; }
.host-card--featured { border: 2px solid var(--terracotta); }
.host-badge { position: absolute; top: -11px; right: 16px; background: var(--terracotta);
  color: #fff; font-size: 0.62rem; font-weight: 700; letter-spacing: 0.05em;
  text-transform: uppercase; padding: 3px 9px; border-radius: 20px; }
.host-icon { font-size: 1.5rem; margin-bottom: 10px; }
.host-card h3 { font-size: 1.15rem; font-weight: 800; margin: 0 0 8px; }
.host-card--featured h3 { color: var(--terracotta); }
.host-card p { font-size: 0.95rem; color: var(--muted); margin: 0 0 12px; }
.host-price { font-size: 0.92rem; font-weight: 700; color: var(--ink) !important; margin: 0 !important; }
```

- [ ] **Step 3: Verify in browser**

Reload. Expected: "Hosting" eyebrow + "Run it your way. Or don't run it at all." title + subhead; two side-by-side cards. Right card has a 2px terracotta border, an "Easiest" pill badge overlapping its top edge, terracotta "Fully managed" heading, and the bold "Priced on compute, not per seat…" line. Left card shows "Free. It's just software." A centered "Learn more →" button sits below.

- [ ] **Step 4: Commit**

```bash
git add marketing/index.html marketing/styles.css
git commit -m "feat(marketing): hosting section (self-managed vs fully managed)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 7: "Learn more" contact form (markup + states)

**Files:**
- Modify: `marketing/index.html` (replace learn-more comment)
- Modify: `marketing/styles.css` (append styles)

- [ ] **Step 1: Add the form markup with success/error containers**

Replace `<!-- learn-more: Task 7 -->` with:

```html
  <section class="section learn" id="learn-more">
    <div class="container center">
      <h2 class="section-title">Curious? Let's talk.</h2>
      <p class="learn-sub">Tell us a bit about your team and we'll show you how to get Wikantik running — your way or ours.</p>

      <form id="lead-form" class="lead-form" novalidate>
        <label class="field">
          <span class="field-label">Name</span>
          <input type="text" name="name" autocomplete="name" required>
        </label>
        <label class="field">
          <span class="field-label">Work email</span>
          <input type="email" name="email" autocomplete="email" required>
        </label>
        <label class="field">
          <span class="field-label">What would you use it for?</span>
          <textarea name="use_case" rows="3"></textarea>
        </label>
        <!-- honeypot: hidden from humans, bots fill it -->
        <div class="hp" aria-hidden="true">
          <label>Leave this empty <input type="text" name="company_url" tabindex="-1" autocomplete="off"></label>
        </div>
        <button type="submit" class="btn btn-primary form-submit">Learn more</button>
        <p class="form-fineprint">No spam. No newsletter you didn't ask for.</p>
      </form>

      <div id="form-success" class="form-success" hidden>
        <p class="form-success-msg">✓ Got it — we'll be in touch.</p>
      </div>
      <div id="form-error" class="form-error" hidden>
        <p>Hmm, that didn't go through. Email us directly at
          <a href="mailto:jakefear@gmail.com?subject=Wikantik%20%E2%80%94%20Learn%20more">jakefear@gmail.com</a>.</p>
      </div>
    </div>
  </section>
```

- [ ] **Step 2: Append form styles**

```css
/* Learn more / form */
.learn { background: var(--wash); }
.learn-sub { color: var(--muted); max-width: 420px; margin: 0 auto 24px; }
.lead-form { max-width: 400px; margin: 0 auto; text-align: left; }
.field { display: block; margin-bottom: 12px; }
.field-label { display: block; font-size: 0.8rem; font-weight: 600; color: var(--muted);
  margin-bottom: 4px; }
.field input, .field textarea { width: 100%; padding: 10px 12px; border: 1px solid #d6d0c9;
  border-radius: 8px; font-size: 0.95rem; font-family: var(--font-ui); background: #fff;
  color: var(--ink); }
.field input:focus, .field textarea:focus { outline: 2px solid var(--terracotta); outline-offset: 1px;
  border-color: var(--terracotta); }
.field textarea { resize: vertical; }
.hp { position: absolute; left: -9999px; width: 1px; height: 1px; overflow: hidden; }
.form-submit { width: 100%; margin-top: 4px; }
.form-fineprint { text-align: center; font-size: 0.78rem; color: var(--soft); margin: 10px 0 0; }
.form-success { max-width: 400px; margin: 0 auto; }
.form-success-msg { color: var(--green); font-weight: 700; font-size: 1.1rem; }
.form-error { max-width: 420px; margin: 12px auto 0; color: var(--terracotta-dark); font-size: 0.9rem; }
```

- [ ] **Step 3: Verify in browser**

Reload. Expected: warm-wash "Curious? Let's talk." section with a centered 400px-wide form (Name, Work email, use-case textarea, full-width terracotta "Learn more" button, fineprint). The honeypot field is off-screen (not visible). Inputs show a terracotta focus ring. The success and error blocks are present in the DOM but hidden (wired up in Task 9). The nav/hero/hosting "Learn more" links scroll here.

- [ ] **Step 4: Commit**

```bash
git add marketing/index.html marketing/styles.css
git commit -m "feat(marketing): learn-more contact form markup + states

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 8: Footer (and close `<main>`)

**Files:**
- Modify: `marketing/index.html` (replace footer comment, close `<main>`)
- Modify: `marketing/styles.css` (append styles)

- [ ] **Step 1: Add the footer markup and close `<main>`**

Replace `<!-- footer: Task 8 -->` with:

```html
  </main>

  <footer class="footer">
    <div class="container footer-inner">
      <div class="brand">
        <span class="brand-mark" aria-hidden="true">W</span>
        <span class="footer-credit">Wikantik · built by Jacob Fear</span>
      </div>
      <nav class="footer-links" aria-label="Footer">
        <a href="https://wiki.wikantik.com/">Explore the wiki</a>
        <a href="https://wiki.wikantik.com/privacy-policy.html">Privacy</a>
        <a href="https://wiki.wikantik.com/terms-of-service.html">Terms</a>
        <a href="mailto:jakefear@gmail.com">Contact</a>
      </nav>
    </div>
  </footer>
```

- [ ] **Step 2: Append footer styles**

```css
/* Footer */
.footer { border-top: 1px solid var(--border); padding: 24px 0; }
.footer-inner { display: flex; align-items: center; justify-content: space-between;
  flex-wrap: wrap; gap: 12px; font-size: 0.85rem; color: var(--soft); }
.footer-credit { color: var(--soft); }
.footer-links { display: flex; gap: 16px; flex-wrap: wrap; }
.footer-links a { color: var(--soft); }
.footer-links a:hover { color: var(--ink); }
```

- [ ] **Step 3: Verify in browser + validate HTML structure**

Reload. Expected: a footer with the "W" mark + "Wikantik · built by Jacob Fear" on the left and four links on the right. Confirm `<main>` is now closed exactly once (the page should have one `<main id="top">` open in Task 2 and one `</main>` here).

Run: `grep -c "<main" marketing/index.html && grep -c "</main>" marketing/index.html`
Expected: `1` and `1`.

- [ ] **Step 4: Commit**

```bash
git add marketing/index.html marketing/styles.css
git commit -m "feat(marketing): footer

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 9: Form handler JS + serialization unit test

**Files:**
- Create: `marketing/test/form.test.mjs`
- Modify: `marketing/index.html` (replace form-script comment with inline `<script>`)

This is the one piece of real logic. We factor field collection into a pure `serializeForm(formData)` helper, test it in isolation with Node, then wire it into the submit handler.

- [ ] **Step 1: Write the failing test**

Create `marketing/test/form.test.mjs`:

```javascript
import assert from 'node:assert';
import { serializeForm } from '../form-helper.mjs';

// 1. normal submission produces a trimmed payload object
{
  const fd = new Map([['name', '  Ada  '], ['email', 'ada@example.com'],
    ['use_case', 'agents'], ['company_url', '']]);
  const out = serializeForm(fd);
  assert.deepStrictEqual(out, { spam: false,
    payload: { name: 'Ada', email: 'ada@example.com', use_case: 'agents' } });
}

// 2. a filled honeypot flags spam and yields no payload
{
  const fd = new Map([['name', 'Bot'], ['email', 'bot@x.com'],
    ['use_case', ''], ['company_url', 'http://spam']]);
  const out = serializeForm(fd);
  assert.strictEqual(out.spam, true);
  assert.strictEqual(out.payload, null);
}

console.log('serializeForm: all assertions passed');
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `node marketing/test/form.test.mjs`
Expected: FAIL — `Cannot find module '.../marketing/form-helper.mjs'`.

- [ ] **Step 3: Create the helper module that makes the test pass**

Create `marketing/form-helper.mjs`:

```javascript
// Pure, testable form serialization. `entries` is anything iterable of
// [key, value] pairs (FormData or a Map). Returns {spam, payload}.
export function serializeForm(entries) {
  const data = Object.fromEntries(entries);
  if ((data.company_url || '').trim() !== '') {
    return { spam: true, payload: null };
  }
  return {
    spam: false,
    payload: {
      name: (data.name || '').trim(),
      email: (data.email || '').trim(),
      use_case: (data.use_case || '').trim(),
    },
  };
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `node marketing/test/form.test.mjs`
Expected: PASS — prints `serializeForm: all assertions passed`.

- [ ] **Step 5: Add the inline submit handler to `index.html`**

Replace `<!-- form script: Task 9 -->` with the script below. It inlines the same `serializeForm` logic (the page is a single static file with no module loader; the `.mjs` copy exists so the logic stays unit-tested — keep the two in sync).

```html
  <script>
    // Paste your deployed Google Apps Script web-app URL here (see marketing/README.md).
    var LEAD_ENDPOINT = "REPLACE_WITH_APPS_SCRIPT_WEB_APP_URL";

    function serializeForm(entries) {
      var data = Object.fromEntries(entries);
      if ((data.company_url || "").trim() !== "") {
        return { spam: true, payload: null };
      }
      return { spam: false, payload: {
        name: (data.name || "").trim(),
        email: (data.email || "").trim(),
        use_case: (data.use_case || "").trim(),
      }};
    }

    (function () {
      var form = document.getElementById("lead-form");
      var ok = document.getElementById("form-success");
      var err = document.getElementById("form-error");
      if (!form) return;

      form.addEventListener("submit", function (e) {
        e.preventDefault();
        var result = serializeForm(new FormData(form).entries());
        if (result.spam) { return; }                 // silently drop bots
        if (!result.payload.name || !result.payload.email) {
          form.reportValidity();
          return;
        }
        var btn = form.querySelector(".form-submit");
        btn.disabled = true; btn.textContent = "Sending…";

        fetch(LEAD_ENDPOINT, {
          method: "POST",
          // text/plain dodges the CORS preflight against Apps Script
          headers: { "Content-Type": "text/plain;charset=utf-8" },
          body: JSON.stringify(result.payload),
        })
        .then(function (r) { if (!r.ok) throw new Error("HTTP " + r.status); return r; })
        .then(function () { form.hidden = true; err.hidden = true; ok.hidden = false; })
        .catch(function (e2) {
          // Never swallow: log with context and show the mailto fallback.
          console.error("Lead form submission failed:", e2);
          btn.disabled = false; btn.textContent = "Learn more";
          err.hidden = false;
        });
      });
    })();
  </script>
```

- [ ] **Step 6: Verify in browser (pre-endpoint)**

Reload `marketing/index.html`. With `LEAD_ENDPOINT` still the placeholder, fill Name + email and submit. Expected: the `fetch` fails (bad URL), the console shows `Lead form submission failed:` with context, the button resets to "Learn more", and the error block with the `mailto:` fallback appears. Submitting with an empty name/email triggers native validation instead. This confirms the no-silent-failure path before the real endpoint exists.

- [ ] **Step 7: Commit**

```bash
git add marketing/index.html marketing/form-helper.mjs marketing/test/form.test.mjs
git commit -m "feat(marketing): contact-form handler + serialization unit test

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 10: Google Apps Script backend + README

**Files:**
- Create: `marketing/form-backend/Code.gs`
- Create: `marketing/README.md`

- [ ] **Step 1: Write the Apps Script `doPost`**

Create `marketing/form-backend/Code.gs`:

```javascript
/**
 * Wikantik landing-page lead capture.
 * Bind this script to the target Google Sheet (Extensions → Apps Script),
 * then Deploy → New deployment → Web app, "Execute as: me",
 * "Who has access: Anyone". Copy the /exec URL into marketing/index.html
 * (LEAD_ENDPOINT). See marketing/README.md.
 *
 * Sheet columns (row 1 headers, created on first run): timestamp, name, email, use_case
 */
function doPost(e) {
  try {
    var body = JSON.parse(e.postData.contents || "{}");
    var name = String(body.name || "").slice(0, 200);
    var email = String(body.email || "").slice(0, 200);
    var useCase = String(body.use_case || "").slice(0, 2000);

    if (!name || !email) {
      return _json({ ok: false, error: "name and email required" });
    }

    var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheets()[0];
    if (sheet.getLastRow() === 0) {
      sheet.appendRow(["timestamp", "name", "email", "use_case"]);
    }
    sheet.appendRow([new Date(), name, email, useCase]);
    return _json({ ok: true });
  } catch (err) {
    // Log with context; never fail silently.
    console.error("Lead doPost failed: " + err);
    return _json({ ok: false, error: "server error" });
  }
}

function _json(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
```

- [ ] **Step 2: Write the operator README**

Create `marketing/README.md`:

```markdown
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
```

- [ ] **Step 3: Verify the test still passes**

Run: `node marketing/test/form.test.mjs`
Expected: PASS — `serializeForm: all assertions passed`.

- [ ] **Step 4: Commit**

```bash
git add marketing/form-backend/Code.gs marketing/README.md
git commit -m "feat(marketing): Apps Script lead backend + operator README

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 11: SEO/meta, social cards, JSON-LD, a11y polish

**Files:**
- Modify: `marketing/index.html` (expand `<head>`)

- [ ] **Step 1: Replace the `<head>` with the full meta set**

In `marketing/index.html`, replace everything between `<head>` and `</head>` with:

```html
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Wikantik — the knowledge base for the AI era</title>
  <meta name="description" content="A markdown wiki your team actually enjoys — wired so your AI agents can read, search, and reason over everything you know. Self-managed or fully managed.">
  <meta name="robots" content="index,follow">
  <link rel="canonical" href="https://www.wikantik.com/">
  <link rel="icon" type="image/svg+xml" href="favicon.svg">

  <!-- Open Graph -->
  <meta property="og:type" content="website">
  <meta property="og:url" content="https://www.wikantik.com/">
  <meta property="og:title" content="Wikantik — the knowledge base for the AI era">
  <meta property="og:description" content="A markdown wiki humans love and AI agents can actually use. Native MCP, hybrid search, knowledge graph.">
  <meta property="og:image" content="https://www.wikantik.com/assets/wikantik-logo-512.png">

  <!-- Twitter -->
  <meta name="twitter:card" content="summary">
  <meta name="twitter:title" content="Wikantik — the knowledge base for the AI era">
  <meta name="twitter:description" content="A markdown wiki humans love and AI agents can actually use.">
  <meta name="twitter:image" content="https://www.wikantik.com/assets/wikantik-logo-512.png">

  <link rel="stylesheet" href="styles.css">

  <script type="application/ld+json">
  {
    "@context": "https://schema.org",
    "@type": "SoftwareApplication",
    "name": "Wikantik",
    "applicationCategory": "BusinessApplication",
    "operatingSystem": "Web, Docker",
    "description": "The knowledge base for the AI era: a markdown wiki humans enjoy and AI agents can natively read, search, and reason over.",
    "url": "https://www.wikantik.com/",
    "offers": { "@type": "Offer", "price": "0", "priceCurrency": "USD" },
    "author": { "@type": "Person", "name": "Jacob Fear" }
  }
  </script>
```

- [ ] **Step 2: Verify meta + structured data**

Reload. Expected: tab title unchanged; no console errors. Validate the JSON-LD:

Run: `node -e "const m=require('fs').readFileSync('marketing/index.html','utf8').match(/<script type=\"application\/ld\+json\">([\s\S]*?)<\/script>/); JSON.parse(m[1]); console.log('JSON-LD valid')"`
Expected: prints `JSON-LD valid`.

- [ ] **Step 3: Commit**

```bash
git add marketing/index.html
git commit -m "feat(marketing): SEO meta, OG/Twitter cards, JSON-LD

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 12: Responsive breakpoint + final verification

**Files:**
- Modify: `marketing/styles.css` (append mobile breakpoint)

- [ ] **Step 1: Append the mobile breakpoint**

```css
/* Mobile */
@media (max-width: 720px) {
  .hero { padding: 56px 0 48px; }
  .hero-title { font-size: 2.2rem; }
  .hero-sub { font-size: 1rem; }
  h2.section-title { font-size: 1.6rem; }
  .nav-links { gap: 12px; }
  .nav-links > a:not(.nav-cta) { display: none; }   /* keep only the CTA on small screens */
  .pillars, .host-cards { grid-template-columns: 1fr; }
  .compare-table { font-size: 0.82rem; }
  .compare-table th[scope="row"], .compare-table td, .compare-table th[scope="col"] { padding: 9px 8px; }
  .footer-inner { flex-direction: column; align-items: flex-start; }
}
```

- [ ] **Step 2: Verify responsive layout**

Open `marketing/index.html`, open dev tools, toggle device emulation to ~375px width. Expected: hero type shrinks; nav shows only the brand + "Learn more"; pillars and hosting cards stack to one column; the comparison table stays readable; footer stacks vertically. Toggle back to desktop and confirm the 2-column grids return.

- [ ] **Step 3: Full final verification pass**

- Open the page at desktop width; scroll top to bottom and confirm all nine sections render in order: nav → hero → problem → pillars → terminal → compare → hosting → learn-more → footer.
- Confirm all anchor links work (Why Wikantik, For agents, Compare, every "Learn more").
- Confirm external links point to `https://wiki.wikantik.com/` (explore, sign-in, footer) and `mailto:jakefear@gmail.com` (contact + error fallback).
- Run the form test once more: `node marketing/test/form.test.mjs` → PASS.
- Run `mvn apache-rat:check -q` → BUILD SUCCESS (marketing excluded).
- Optional: run Lighthouse in Chrome dev tools; expect high SEO/accessibility/performance given no framework or third-party scripts. Spot-check terracotta-on-white and white-on-terracotta meet AA contrast for the text sizes used.

- [ ] **Step 4: Commit**

```bash
git add marketing/styles.css
git commit -m "feat(marketing): responsive breakpoint + final polish

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Post-implementation (operator, out of plan scope)

1. Create the lead Google Sheet, deploy `form-backend/Code.gs` as a web app, paste the `/exec` URL into `LEAD_ENDPOINT`, submit a live test, confirm the row lands.
2. Upload the bundle to the static host serving www.wikantik.com.
3. Cut DNS over from the Squarespace placeholder.
```
