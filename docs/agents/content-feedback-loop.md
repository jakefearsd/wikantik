# Where content agents should focus

**Audience:** `wiki-content-agent` and any agent doing content or SEO work on this wiki.
**Status:** current as of 2026-08-16, derived from measured production data.
**Source of truth for the mechanism:** [Content Intelligence design](../superpowers/specs/2026-08-16-content-intelligence-design.md).

This document says what to work on and — more importantly — what *not* to. It exists because
the obvious SEO instincts are wrong for this site in ways only the data reveals.

---

## 1. The situation, in numbers

All measured on 2026-08-16 from `search_visibility_snapshot` and Google Search Console.

| Fact | Value | What it means |
|---|---|---|
| Indexed (Google) | **988** | Discovery works. The sitemap and robots setup are fine. |
| Not indexed | **389** | Of which **373 are Google's choice** — "discovered/crawled, currently not indexed" |
| Not indexed, our fault | **16** | 9× 404, 3× robots.txt, 4× canonical. That's all. |
| Google page-1 pages | 106 | out of 457 with impressions |
| Google pages at position >20 | **307** | 175 at 20–50, 132 beyond 50 |
| Google impressions at position >20 | **2,154 of 2,351 (92%)** | Almost all our Google exposure is where nobody looks |
| Bing pages on page 1 | **54 of 54 (100%)** | Everything Bing indexes, it ranks well |
| Bing impressions | 108 | Low volume, perfect placement |
| CTR: Google / Bing | 0.32% / **1.85%** | Bing converts ~6× better per impression |
| Human search sessions, 90 days | ~3 | All self-generated |
| Orphaned pages | **159** | 11.6% of the corpus has zero inbound internal links |
| Broken links | **79** | Wasted crawl budget on a rationed crawl budget |

### The single most important inference

**The constraint is authority, not content quality, not snippets, and not discovery.**

Google indexes our pages and declines to rank them. It has stopped indexing new ones (293
"discovered – currently not indexed"). Both are the same signal: the site does not have enough
authority to justify more of Google's attention.

---

## 2. What NOT to do

These are the natural instincts, and each is actively counterproductive here.

### Do not rewrite titles or meta descriptions for low-ranking pages

A page at position 30 gets no clicks regardless of its title, because nobody scrolls to position
30. `MavenMultiModuleProjects` has 154 impressions and 0 clicks at position 29.7 — that is not a
snippet failure, it is a ranking failure, and no amount of copywriting fixes it.

**Rule of thumb: only treat CTR as the problem when position ≤ 10.** Above that, position is the
problem. The `ENGINE_DIVERGENCE` rule exists to enforce this automatically and will suppress
CTR-type suggestions for affected pages — but do not wait for the tool to protect you from it.

### Do not bulk-publish new pages right now

293 pages are already discovered and declined. New pages queue behind the same authority ceiling.
Publishing more currently **dilutes crawl budget across a larger corpus without improving any of
it**. This directly inverts the instinct of a content-generation pipeline, and it is the single
most expensive mistake available.

### Do not act on `seo_opportunity_score` from the Grafana dashboard uncritically

jakemon's `content_gap` detector scores uplift as `impressions × expected_ctr(3)` — it assumes a
page could reach position 3. Applied to 993 Google impressions at position 61, that produced a
confident "25.7 clicks available" that is almost entirely fictional. Trust the position first.

### Do not trust `indexed_pages` from the visibility pipeline

jakemon's Google provider reads `contents[].indexed` from the Sitemaps API, a field Google
deprecated years ago and now always returns as `0`. It reported 0 indexed while Search Console
showed 988. Until that provider is fixed, treat Google `indexed_pages` as absent, not as zero.

---

## 3. What to do, in priority order

### Priority 1 — internal link graph (owned by another agent as of 2026-08-16)

159 orphans and 79 broken links. An orphan has no inbound internal links, so Google receives no
importance signal for it, so it declines to index — which is exactly the pattern the 293
"discovered, not indexed" shows. This is the only authority lever available without outreach, and
it is entirely within our control.

**Testable hypothesis worth confirming:** the orphan set is disproportionately represented in the
not-indexed set. If true, fixing orphans should move the indexed count within ~28 days, and
`search_visibility_snapshot` will show it.

### Priority 2 — consolidate rather than add

Thin or overlapping pages compete with each other and spend crawl budget. Merging two weak pages
into one strong one improves the corpus on every axis that currently matters. Prefer depth on
existing pages over breadth of new ones until the indexed count starts moving again.

### Priority 3 — Bing is the real opportunity

Bing ranks **100% of what it indexes on page one** and only produces 108 impressions. That is not
a ranking problem, it is a coverage problem: we are not matching enough queries. Bing also feeds
the retrieval layer behind several AI answer surfaces, which is where this wiki's actual audience
is (see §4).

Bing responds to IndexNow, which this wiki already pushes to and which Google does not participate
in. That asymmetry in submission is part of why the two engines look so different.

### Priority 4 — agent retrieval quality

See §4. This is the only feedback loop with real traffic today.

---

## 4. The audience is machines — optimise accordingly

Human search traffic is approximately zero: 12 query-log rows in 90 days, all self-generated. Any
loop built on human behaviour has no denominator and will produce confident statistics from n≈0.

The live audience is **agents** — MCP callers, `/api/bundle` consumers, and AI crawlers. That has
two consequences:

1. **`AGENT_GAP` is the only rule with a live denominator.** When a retrieval returns zero
   sections, or a bundle comes back with `coverage` of `weak`/`unknown`, that is a real, observed
   content gap with a real consumer behind it. Prioritise those over any SEO opportunity.
2. **Ranking in Bing is ranking in the answer layer.** Optimising for the engine that feeds AI
   assistants is optimising for the readers we actually have.

---

## 5. How to use the loop once it exists

When `list_content_opportunities` is available on the admin MCP endpoint:

1. **Call it before deciding what to work on.** Do not pick pages by intuition; the backlog is
   ranked by estimated recoverable clicks/answers and carries the evidence that fired each rule.
2. **Read the `evidence` block, don't just trust `priority`.** Every rule's numbers are exposed
   precisely so a suggestion can be sanity-checked rather than obeyed.
3. **Weight `calibrated: false` down.** Until a rule type has 20 evaluated outcomes, its priority
   weight is a guess. The tool says which are guesses.
4. **Record every change in `content_change_log`** with its baseline. A change that is not
   recorded cannot be evaluated, and an unevaluated change teaches nothing.
5. **Respect the 60-day per-page cooldown.** Two changes inside one measurement window make the
   effect unattributable — you lose the ability to learn from either.
6. **Snooze with a reason** when declining a suggestion, so it stops being re-proposed. The reason
   field is mandatory: a declined suggestion with no recorded reason is indistinguishable from a
   bug six months later.

### Interpreting effect verdicts honestly

Effect measurement is a **weak quasi-experiment**: one site, no control group, no randomisation.
A single `improved` verdict is suggestive, not conclusive. `insufficient_data` will be the
majority verdict for a long time and that is the honest answer, not a failure.

Value accrues in aggregate: fifty verdicts across one change type tell you whether that class of
edit works, even though any individual verdict might be noise.

---

## 6. Standing cautions

- **Never reason from a single snapshot.** An earlier read of one window showed "Google delivers
  zero clicks"; the trend showed that window was unrepresentative. Always check the trend before
  concluding.
- **A parsed number is not a measurement.** The `indexed_pages: 0` bug returned a structurally
  valid value from a successful API call and was completely meaningless. When a number is
  surprising, cross-check it against the source system before building on it.
- **Silence is a valid output.** Every rule has a minimum-support threshold and will decline to
  fire below it. An empty backlog means "not enough evidence," not "nothing to do."
