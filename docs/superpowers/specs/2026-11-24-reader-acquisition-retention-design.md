# Reader Acquisition and Retention — Addendum to the Reader Engagement Loop

> **Status: draft 2026-11-24, for Jake's approval.** Extends the approved design on the live wiki page
> `SimpleAgilityReaderEngagementLoop` (SA-17, 2026-11-08). On approval this text is merged into that page
> (the canonical record, decision D8) and this file stays as the repository copy. Everything in the existing
> design still stands unless a section below says it changes.

## Intent

The engagement design answers *did readers get something once they arrived*. This addendum adds
*where readers come from* and *whether they come back*, because Jake's goal is **more readers arriving and
keeping the readers he has**, with a **modest advertising income** as the long-term payoff (more returning
readers and more pages per visit are what ad revenue is made of).

Three requirements shape it:

1. **Clean, low risk, still delivers results.** Use what Umami 3.3 already does; build only what turns the data
   into content decisions.
2. **Reusable on the blog.** The conventions and the shared SQL live at stack level, so Pressroom adopts the
   same capability without a second design.
3. **Applies to all content**, every reader-facing page of every product, not a hand-picked set.

## Decisions made in this pass (Jake's)

| # | Decision |
|---|---|
| D9 | "More users" means **more readers arriving and retaining existing readers.** Sign-in stays valued (D6) but is not the headline metric. |
| D10 | **Approach A**: Umami's own reports are where acquisition is *looked at*; Wikantik reads back only what changes content decisions. No acquisition tables or dashboards rebuilt inside the product. A Grafana combined view (suite step 5) may come later as an add-on. |
| D11 | **Retention is measured within Umami's monthly window, and loyal readers are counted as newsletter and feed subscribers.** No measurement cookie; D5 stands. A newsletter (listmonk, already in the suite) and feed measurement become a project of their own (Project 5). |

## Facts this design rests on (verified 2026-11-24 against the live suite on docker2)

- Umami's `website_event` stores, per event: `url_path`, `url_query`, `referrer_domain`/`referrer_path`,
  the five `utm_*` fields, ad click ids (`gclid`, `msclkid`, `fbclid`, `li_fat_id`, `ttclid`, `twclid`),
  `visit_id`, `session_id`, `tag`, and Core Web Vitals (`lcp`, `inp`, `cls`, `fcp`, `ttfb`). UTMs and click ids
  are captured with no configuration.
- A session id hashes website, IP, user agent and a salt that rotates **monthly by default** (Umami's
  `getSalt` supports day/week/month). So "returning" means *returning within the same calendar month*.
- A `visit_id` groups the page views of one visit; a visit's first page view is its landing page.
- The tracker supports `data-performance` (Web Vitals) and `data-tag`.
- Wikantik already emits `<link rel="canonical">` (`HeadTagWriter`) and an Atom feed (`AtomFeedServlet`);
  Content Intelligence already holds per-page, per-engine search clicks and impressions
  (`search_visibility_snapshot`).

## Goals, non-goals, anti-goals (additions)

**Goals**

1. Every visit sorted into one **channel** by one shared rule set, the same for every product.
2. Per page, per day: visits that **landed** on it, by channel and source; how many of those **continued** to a
   second page; how many were **returning** (within the month).
3. A **tagging convention** for every link Jake places anywhere (social posts, newsletter, paid campaigns,
   cross-links between blog and wiki) so campaigns are comparable without per-campaign setup.
4. A **coverage check**: search-engine clicks (from Content Intelligence) against Umami organic-search landings,
   per page and engine, so tracker loss (ad blockers, script failures) is a known number, not a hidden one.
5. Per-page **Core Web Vitals** from real readers, as an SEO input.
6. **Loyal-reader counts**: newsletter subscribers and feed subscribers, and which pages turn readers into
   subscribers (Project 5).

**Non-goals**

- Campaign-level tables in Wikantik. Campaign results (spend vs. landings, which ad, which keyword) are read in
  Umami's UTM and Attribution reports.
- Cross-month visitor identity of any kind.
- Ad-platform conversion pixels (Google Ads tag, Meta pixel). If a paid campaign runs, it is judged by Umami
  landings and on-site behaviour tagged by UTM/click id. Revisit only if a campaign's spend makes platform
  bidding optimisation worth a new third party (it would be a new decision against D5).

**Anti-goals**

- A UTM-tagged URL indexed as a separate page, or a campaign parameter fragmenting a render cache.
- Campaign parameters that survive in the address bar and get copied into shared links (they would misattribute
  every later visit from that link).
- Channel rules duplicated per product so the blog and the wiki disagree about what "social" means.
- Any rule that acts on landings or returns before the traffic gates say the numbers mean something.

## Stack-level conventions (every product with readers)

These join the five conventions in the existing design's stack section.

### 6. Link tagging

| Parameter | Rule | Examples |
|---|---|---|
| `utm_source` | lower-case platform or property, no spaces | `google`, `bing`, `reddit`, `hn`, `linkedin`, `mastodon`, `newsletter`, `feed`, `pressroom`, `wikantik` |
| `utm_medium` | **fixed set only** | `cpc`, `paid-social`, `social`, `email`, `feed`, `referral`, `blog`, `wiki` |
| `utm_campaign` | `yyyy-mm-<kebab-slug>` | `2026-12-interval-trees-launch`, `digest-2026-w49` |
| `utm_content` | optional variant, kebab-case | `headline-b` |
| `utm_term` | paid search keyword only | |

`blog` and `wiki` are for links one Simple Agility property places on another (Pressroom → wiki, wiki → blog);
Pressroom's outbound `utm_medium=blog` already matches. Organic search, AI-assistant and plain referral traffic
is never tagged; the referrer classifies it.

### 7. Channel classification — one implementation, in the suite

A suite-owned read-only view, **`analytics_acquisition`**, beside `analytics_traffic` and the planned
`analytics_engagement`, classifies each visit's landing page view by the first rule that matches:

1. **paid-search** — a `gclid`/`msclkid`, or `utm_medium = cpc`
2. **paid-social** — `fbclid`/`li_fat_id`/`ttclid`/`twclid` with `utm_medium = paid-social`, or `utm_medium = paid-social`
3. **email** — `utm_medium = email`
4. **feed** — `utm_medium = feed`
5. **cross-property** — `utm_medium in (blog, wiki)`
6. **organic-search** — referrer domain in the search-engine list (source = the engine)
7. **ai-assistant** — referrer domain in the AI-assistant list (chatgpt.com, perplexity.ai, claude.ai,
   gemini.google.com, copilot.microsoft.com, …) (source = the assistant)
8. **social** — `utm_medium = social`, or referrer domain in the social list
9. **referral** — any other external referrer (source = referrer domain)
10. **direct** — none of the above

A bare `fbclid` without UTMs (Facebook adds it to organic shares too) is **social**, not paid — that is why rule 2
requires the medium. The domain lists are a small suite-owned **table** (`acquisition_domain`: domain pattern →
channel), not code, so adding a new AI assistant is a row, and both products pick it up on the next read.
`ai-assistant` is its own channel because this wiki is built for agent retrieval; whether assistants send readers
is a question worth answering separately.

The view returns, per (UTC day, website, landing `url_path`, channel, source): landing visits, landing visits with
≥ 2 page views ("continued"), and landing visits whose session existed before that visit ("returning", within
the month). Source values are bounded: engine/assistant/social names from the table, or the referrer's
registrable domain, or `utm_source` for tagged traffic.

### 8. URL hygiene for tagged links

- After the tracker has sent the landing page view, the page removes `utm_*` and click-id parameters from the
  address bar (`history.replaceState`), so a copied link carries no campaign.
- `rel="canonical"` never contains campaign parameters (Wikantik already emits the bare page URL; the builder
  confirms it ignores the query string).
- Server-side render caches must not key on campaign parameters (builder confirms for Wikantik's SSR path).

## Project 1 changes (Wikantik)

Project 1 keeps everything already specified and adds:

1. **Tracker attributes**: `data-performance` on (Web Vitals). `data-do-not-track` stays off (D3: all traffic counts).
2. **URL hygiene** as in convention 8.
3. **A second rollup table, `reader_acquisition_daily`** — UTC day, site host, `canonical_id`, channel, source,
   landings, continued landings, returning landings. One row per (day, site, page, channel, source); replaced on
   re-read like `reader_engagement_daily`; typed columns; numbered migration, DDL only. Landing paths are mapped
   to `canonical_id` the same way page views are. Campaign names are not stored here (non-goal).
4. **Web Vitals columns** on `reader_engagement_daily`: p75 LCP, p75 INP, p75 CLS (p75 is Google's own
   threshold measure). These come from the suite's `analytics_engagement` view, so that cross-repo item grows by
   three percentile columns.
5. **Coverage check** on `/admin/insights`: for each page and engine over 28 days, search clicks (Content
   Intelligence) beside organic-search landings (Umami), and a site-wide ratio. Shown, not acted on.
6. **Read surface**: landings by channel, continuation rate, returning share and vitals appear as columns on
   `/admin/insights` and on the existing admin MCP read path, beside the engagement columns.

**Cross-repo items for the back-office suite** (Project 1 cannot verify in production without them):
`analytics_acquisition` view + `acquisition_domain` table (seeded with the engines, assistants and social sites
above); the vitals percentiles on `analytics_engagement`; a `wikantik` line in `secrets/umami-accounts`; read
grants for Wikantik's role on both views.

**Project 1 is also done when** (additions): a visit from a Bing search result, a ChatGPT link, a tagged
`utm_medium=social` link, and a typed-in URL each land in the right channel in `reader_acquisition_daily`; a
landing URL with `?utm_source=…` shows the bare URL in the address bar after load and in `rel="canonical"`;
vitals columns fill for pages with data.

## Project 2 changes (sketch) — rules that grow arrivals and retention

Added to the two engagement rules, same gating discipline (per-page minimum plus a site gate; suppressed rules
report measured vs. required):

- **Lands but doesn't continue** — many landings, low continuation. Suggested work: onward links, "next in this
  cluster", related pages placed where readers stop scrolling. This is the main retention lever on the page itself.
- **Slow page** — p75 LCP or INP outside Google's "good" threshold. Suggested work: whatever the page carries that
  is heavy (diagrams, images, embedded math).
- **Assistant-referred** — pages AI assistants send readers to; informational at first (what makes a page cited),
  promoted to a rule only if the data shows a pattern.
- `EffectEvaluator` gains **continuation rate** and **returning share** as outcome measures.

## Project 5 (new) — Newsletter and feed: the loyal-reader channel

Sketched here; gets its own short design pass before building.

- **Newsletter on listmonk.** A "wiki digest" list in the suite's listmonk. A sign-up block on reader pages (end of
  article and footer) posts to **Wikantik**, which calls listmonk's public subscription endpoint server-side with
  its own throttle — the Pressroom pattern; no third-party script, CSP unchanged. listmonk owns double opt-in and
  unsubscribe.
- **Event** `newsletter-subscribe` (`canonical_id`) and an Umami goal on it, plus a `subscribes` column on
  `reader_engagement_daily` → "pages that make subscribers".
- **Digest**: a scheduled job drafts a weekly campaign in listmonk from new and substantially updated pages, every
  link tagged `utm_source=newsletter&utm_medium=email&utm_campaign=digest-yyyy-wNN`. **Jake sends it** (D7: human
  in the loop); nothing is sent automatically.
- **Feed**: Atom entry links tagged `utm_source=feed&utm_medium=feed`, so feed arrivals are classified. Feed
  aggregators report subscriber counts in their User-Agent (`… N subscribers …`); the feed servlet records the
  latest count per aggregator as a `/metrics` gauge (observation → jakemon, SA-1).
- **Subscriber total** from listmonk read nightly and exposed as a `/metrics` gauge (observation → jakemon).
- Reusable: Pressroom already integrates listmonk; the digest and tagging conventions apply to its lists too.

## Project order (revised)

| # | Project | Why here |
|---|---|---|
| 1 | Instrumentation + engagement **and acquisition** read-back | Everything depends on it; history cannot be backfilled |
| 5 | Newsletter and feed | Jake's retention goal; the only durable measure of loyal readers; starts building a list early |
| 2 | Engagement + acquisition rules, outcome measures | Needs accumulated data and the gates |
| 4 | AdSense | Needs a clean pre-ads baseline of reading, continuation and returning share |
| 3 | Sign-in funnel | Valued (D6) but behind arrivals and retention (D9) |

## Ads tie-in (for Project 4)

The acquisition rollup gives **pages per landing by channel**, which is the ad-impression yield of each channel;
the ad-free holdout's `ads` property on `page-exit` extends naturally to continuation and returning share, so
the cost of ads is measured on retention, not only on reading time. Revenue capture stays jakemon's (SA-16).

## Where each new piece of data lives (SA-1)

| Data | Lives in | Why |
|---|---|---|
| Raw UTMs, click ids, referrers, vitals | Umami | Observation |
| Channel rules and domain lists | Suite (`acquisition_domain`, `analytics_acquisition`) | One implementation for every product |
| Per-page landings / continuation / returning by channel | Wikantik `reader_acquisition_daily` | Functional input to rules and effect evaluation |
| Per-page vitals | Wikantik `reader_engagement_daily` | Functional input to the slow-page rule |
| Newsletter and feed subscriber totals | jakemon (`/metrics`) | Observation |
| Per-page subscribes | Wikantik `reader_engagement_daily` | Functional input ("pages that make subscribers") |
| Campaign performance | Umami reports | Looked at, not acted on by the product |

## Privacy disclosure (addition)

The privacy page adds: where a visit came from (referring site, campaign tags) is recorded; page-speed timings
are recorded; newsletter addresses are held in a self-hosted list with double opt-in and one-click unsubscribe
(Project 5's release).

## Calls made in this addendum (not yet Jake's)

- Classification lives in a **suite view + domain table**, not in each product.
- `ai-assistant` and `cross-property` are channels of their own.
- Bare `fbclid` counts as social, not paid.
- Campaign names are not stored in Wikantik.
- Web Vitals are collected (p75) and feed a slow-page rule.
- UTMs are stripped from the address bar after the landing view.
- Project order 1 → 5 → 2 → 4 → 3 (sign-in funnel moves last).
- Ad-platform conversion pixels are out unless a future decision reopens D5.

## Settled after the builder review

- **How Wikantik reads the views** (*my call*). A direct read-only connection to the suite's `umami-db`, as a
  login role `wikantik_ro` that may `SELECT` only `analytics_engagement` and `analytics_acquisition` (never
  `website_event`). This needs one change to a suite rule: `umami-db` gets published on `${BACKOFFICE_BIND}`
  (LAN only, like Umami and listmonk), with `pg_hba` limiting `wikantik_ro` to docker1's address. Umami's HTTP API
  can't return percentiles or per-visit landing logic, so the API is not an alternative. Wikantik gets a second,
  optional DataSource with its own config keys (blank = read-back off), and timeouts on connect and statement.
  The `wikantik` Umami *account* is still needed to register the website and for Jake's dashboards.
- **Website scope.** One Umami website per Wikantik instance. Its id is a Wikantik property, blank by default
  (blank = tracker and read-back off). Production is the only instance with an id; dev, test and the docker2
  instance stay off unless someone sets one. Tests run against a stub view in `PostgresTestDb`.
- **"Returning" is a within-month figure.** The suite computes it as "this session had an event earlier in the
  same calendar month, before this visit". Because every reader looks new on the 1st, measures built on
  returning share (Project 2's outcome measures, any rule) **skip the first 7 days of each month**, and the admin
  panel labels the figure "returning this month".
- **Re-read window.** Each nightly run replaces the trailing 3 UTC days (configurable), which absorbs late events.
  Rows older than Umami's 730-day retention are never re-read, so the rollups outlive the raw data by design.
- **Paths that aren't pages.** A landing on a path that doesn't resolve to a page (`/login`, `/search`, a deleted
  page, a historical path the index can't resolve) is kept in a site row with an empty `canonical_id`. Site
  totals, the site gate and the coverage ratio all count it. Old paths resolve through the page index's rename
  history where it exists.
- **Engine names match Content Intelligence.** `acquisition_domain` uses the same engine keys as
  `search_visibility_snapshot` (`google`, `bing`, …), and the coverage check joins on them. A test checks that
  every engine key in the snapshot exists in the domain table.
- **`analytics_engagement` doesn't exist yet** (the suite has only `analytics_traffic`). The suite work (both views,
  the domain table, the role and the published port) comes first in the plan, as one cross-repo item.
- **URL cleanup ordering.** The page sends the landing page view itself (`data-auto-track="false"` plus
  `umami.track()`), and removes campaign parameters once that call resolves. This applies to the SPA shell and
  the server-rendered `/wiki/*` pages alike.

## Left to the builder

The SQL of `analytics_acquisition` (including how "returning" is computed from `session.created_at` vs. the
visit's first event); the initial domain lists; the registrable-domain normalisation; the exact column names;
where the sign-up block sits in the layout; the digest selection rule ("substantially updated").
