# Content Intelligence — Acquisition, Demand, and Effect Measurement

**Status:** Phase 0 implemented and verified locally (2026-08-16) — fact store, ingest
endpoint, shipper, and metrics are in `main`; 99,132 rows loaded. Later phases proposed.
**Date:** 2026-08-16
**Related:** [HybridRetrieval.md](../../wikantik-pages/HybridRetrieval.md),
[AgentGradeContentDesign.md](../../wikantik-pages/AgentGradeContentDesign.md),
[IndexingSupport.md](../../../IndexingSupport.md)

---

## 1. Why this exists

The content loop is open. `wiki-content-agent` writes pages, adjusts titles and summaries, and
curates the Knowledge Graph — and nothing measures whether any of it worked. There is no signal
telling the agent which page to fix next, and no signal telling it whether last month's fix helped
or hurt.

Closing that loop is the whole point of this subsystem. Everything below exists to answer three
questions, in this order:

1. **Does anyone arrive?** (acquisition)
2. **When someone asks, do we answer?** (demand and gaps)
3. **Did our change help?** (effect)

### 1.1 The measured baseline

Design choices here are shaped by what the traffic actually is, not what it might be. Measured
against prod on 2026-08-16:

| Window | Signal | Observed |
|---|---|---|
| 90 days | Distinct **human** search queries | 12 rows, ≈3 real sessions — and all of them self-generated |
| 30 days | Distinct retrieval queries, all actors | 15, every one an agent test probe from this repo's own tooling |

The human rows are visibly typeahead keystrokes (`P` → `Per` → `Personal `), not three separate
searches. So the real figure is lower than 12.

Two conclusions follow, and they drive the entire phasing:

- **Reader-behavior analytics has no denominator.** Dwell histograms, per-snippet utility scores,
  and read-velocity models need hundreds of samples per page across ~1,200 pages. At the observed
  rate they would produce confident-looking statistics from n≈0 and feed them to an agent that
  rewrites content. That is worse than measuring nothing.
- **The audience that exists today is machines.** Agents over MCP and crawlers over HTTP are the
  live consumers. Instrumenting them requires no browser telemetry at all.
- **The search engines are not interchangeable.** Bing converts ~6× better per impression from a
  first-page rank while Google accumulates impressions at position 36 (§1.2). D3 explains why
  that follows from how the wiki is built. Until the fact store existed nothing could establish
  it either way — the collector discarded delivered clicks — and closing that gap is what
  reshapes this design.

Therefore acquisition and agent-side gaps come first; reader behavior is gated behind a measured
traffic threshold (§9.6).

### 1.2 What already exists, and the gap it leaves

Search visibility is already collected. The `visibility` package in the **jakemon** repo runs a
long-lived exporter on a 6-hour poll:

| | |
|---|---|
| Engines | Google, Bing, **and Yandex** |
| Sites | 8, of which `wiki.wikantik.com` and `wikantik.com` are two |
| Detectors | `striking_distance`, `ctr_gap`, `content_gap`, `cannibalization`, `decay` |
| Ranking | Expected click uplift against a position→CTR curve, reweighted by per-type success rates |
| Output | Three Prometheus gauges (`seo_opportunity_score`, `seo_opportunities_count`, `seo_addressable_clicks`) + a per-site JSON brief |
| Dependencies | Python stdlib only |

It is well-built: pure detector functions, atomic state writes, tested, already ranking by expected
uplift against a CTR-by-position curve.

**What it retains, and what it does not.** It writes one snapshot per (engine, site, date) to a
container volume — 1,486 files as of 2026-08-16, going back to 2026-06-04, each holding `by_query`,
`by_page`, `totals`, and `coverage`. So history exists. Three things are missing:

1. **Grain.** Each snapshot is a *trailing 28-day aggregate* sampled daily, not per-day rows. Fine
   for before/after comparison at a 28-day lag; not a daily series.
2. **Queryability.** They are JSON files on a Docker volume on docker2. Nothing can ask "which
   pages lost position this month," and nothing joins them to page state.
3. **Reach.** The exported Prometheus gauges carry only derived opportunity scores, so the
   dashboard cannot show delivered clicks even though the snapshots contain them.

**What the retained history proves.** Loaded into the fact store on 2026-08-16 — 99,132 rows
across 430 snapshots, 2026-06-04 to 2026-08-14. Page-rollup rows, `wiki.wikantik.com`, most recent
snapshot:

| Engine | Clicks | Impressions | CTR | Avg position |
|---|---|---|---|---|
| google | 8 | 2,518 | 0.32% | 36.0 |
| bing | 2 | 108 | **1.85%** | **4.7** |
| yandex | — | — | — | no page dimension |

Google wins on raw volume by brute-forcing 2,518 impressions; **Bing converts roughly 6× better
per impression, from a first-page rank against Google's page four.** Bing is the healthier channel
by a wide margin, and Google's volume is mostly impressions nobody acts on.

Three months of trend, from the same store:

| Month | google | bing |
|---|---|---|
| 2026-06 | 7 | 0 |
| 2026-07 | 8 | 2 |
| 2026-08 | 8 | 2 |

Both engines are flat. That is a useful null result: nothing is drifting on its own, so a change
we make should be attributable rather than lost in noise.

**This table is also a lesson about single-snapshot reasoning.** An earlier draft of this section
read Google's clicks as *zero*, taken from one window. It was one sample, and the trend shows it
was unrepresentative. Retained history is what converts an anecdote into a measurement — which is
the entire argument for the fact store, demonstrated on the first question it was asked.

It also exposes a defect in the current scoring. `content_gap` fires at impressions ≥ 50 and
position ≥ 25, scoring uplift as `impressions × expected_ctr(3)` — assuming the page could reach
position 3. Applied to Google's 993 impressions at position 61 with zero clicks, that produces the
`seo_opportunity_score` of 25.7 on the dashboard: an opportunity that is almost entirely fictional.
Retained, queryable history is what lets a rule tell "we rank badly and could improve" apart from
"we do not rank at all" — which is §7.3's `ENGINE_DIVERGENCE`.

So the missing piece was never a collector, and it is not raw retention either. It is a
**queryable fact store** — those snapshots parsed into rows that can be aggregated, trended, and
joined against page state — plus the joins only the wiki can perform. The 72 existing snapshots per
(engine, site) mean that store starts with ten weeks of history on day one rather than from zero.

---

## 2. Design principles

1. **Decisions, not dashboards.** Every signal collected must map to a specific action a content
   agent can take. A metric with no attached action is not collected.
2. **Minimum support before action.** Every rule states a sample threshold and does not fire below
   it. Silence is a valid output.
3. **Closed loop.** Every optimization records a baseline and is evaluated after a fixed window.
   An optimization that is never measured is not finished.
4. **One system.** Data lands in the wiki's own database and is read through the wiki's own agent
   surface. New services, stores, and protocols must earn their place against that default.
5. **No client-side storage.** No cookies, no `localStorage`, no device identifier. This is what
   makes the consent-banner-free posture defensible rather than merely asserted (§8).
6. **Operational metrics and content metrics are different things.** Health lives in Prometheus at
   bounded cardinality; content decisions live in SQL. They never share a store (D2).

---

## 3. Scope

- A **durable fact store** for per-(snapshot, engine, site, page, query) search data across Google, Bing,
  and Yandex — retaining what jakemon's existing collector fetches and currently discards (§1.2).
- The ingest seam that gets those rows from jakemon into Wikantik (§7.1).
- In-process capture of retrieval demand: query text, result counts, bundle coverage, search-result
  click rank, MCP tool outcomes.
- A rule engine that turns those into a ranked, typed backlog of content opportunities.
- A change ledger that records every agent optimization and evaluates its effect 28 days later.
- Read surfaces: two MCP tools and one admin page.
- A browser collector — designed here, built only when the traffic gate opens (§9.6).

## 4. Architecture

```
  SOURCES
  ────────────────────────────────────────────────────────────────────────────
   jakemon `visibility` (EXISTING)           In-process wiki signals
   ─ polls google / bing / yandex            ─ retrieval query + result count
     every 6h across 8 sites                 ─ bundle coverage / empty result
   ─ computes 5 detectors -> seo_* gauges    ─ search result click + rank
   ─ NEW: also ships the rows it already     ─ MCP tool call + outcome
     parsed, instead of discarding them
              │                                        │
              │  push, rolling 5-day window,           │  async, fail-open,
              │  best-effort (never fails the poll)    │  never on critical path
              └────────────────────┬───────────────────┘
                                   │
                        [phase 3, traffic-gated]
                         browser beacons ─ one
                         exit event per visit
                                   │
                                   ▼
  STORE ─────────────────────────────────────────────────────────────────────
   The wiki's existing PostgreSQL database. Five new tables, one extended,
   no new engine.

     search_visibility_snapshot     acquisition facts, per engine — the
                                 history nothing currently retains
     retrieval_query_log         demand facts (extended, already exists)
     content_change_log          what the agent changed, and the baseline
     content_opportunity_snooze  declined suggestions
     telemetry_event / _daily    reader behavior            [phase 3]
                                   │
                                   ▼
  RULES ─────────────────────────────────────────────────────────────────────
   5 detectors IMPORTED from jakemon (never recomputed here)
   + 4 native rules needing page state / MCP traffic / retained history.
   Output: one merged, scored, evidence-carrying backlog.
                        ┌──────────┴──────────┐
                        ▼                     ▼
  CONSUMERS ───────────────────────────────────────────────────────────────────
   list_content_opportunities        /admin/insights
   (MCP — the agent's backlog)       (one page — the human's view)
                        │
                        ▼
              content agent acts
                        │
                        ├─── writes content_change_log with a 28-day baseline
                        │
                        └─── 28 days later ──► effect verdict
                                                improved | no_effect
                                                regressed | insufficient_data
                                                         │
                        ┌────────────────────────────────┘
                        ▼
              calibrates the rule weights that produced the suggestion
```

The last arrow is what makes this a loop rather than a suggestion generator. §7.4.4 explains how
measured effect eventually replaces the guessed priority weights.

---

## 5. Design decisions

Each decision states the requirement first and the conclusion last, so the reasoning can be
audited independently of what already happens to be running.

### D1 — Where event and metric data lives

**Requirement.** Retain per-(page, query, date) facts for 12+ months. Query shape is
group-by-entity over a date range, ordered by a computed ratio, returning tens of rows. Must be
readable from the JVM that serves the MCP surface. Must survive deploys and be backed up.

**Volume.** At an optimistic 5,000 human sessions/month the browser tier produces ~500k rows/year.
Search Console at ~1,200 pages produces at most a few hundred thousand rows/year before privacy
filtering, far fewer in practice. Agent-side signals add ~100k/year. Total ceiling: single-digit
millions of rows/year.

**Decision rationale.** The query shape — group-by-entity over a date range, ordered by a computed
ratio, returning tens of rows — is relational work, and the volume is trivial for it: a columnar
store earns its keep around 10⁸ rows and analytical scans, four orders of magnitude above this
ceiling. Postgres also puts the data one connection pool away from the JVM serving the MCP surface,
and inside whatever already backs up the wiki database.

**Decision.** PostgreSQL, in the wiki's own database.

**Revisit when** any aggregate query exceeds ~10 s, or `telemetry_event` passes ~50M rows. At that
point the correct move is a rollup table (already in this design, §6.5) before a new engine.

### D2 — What goes to Prometheus vs. SQL

**Requirement.** Two distinct questions with two distinct shapes: *"is ingestion healthy right
now"* (low cardinality, real-time, alertable) and *"which page should I fix"* (high cardinality,
historical, ranked).

**Decision.** Prometheus carries **only** bounded operational series. SQL carries everything
per-entity. The dividing invariant, which gets a test:

> **No Prometheus metric emitted by this subsystem may carry a page slug, query string, or session
> identifier as a label.**

Permitted series:

```
wikantik_insights_events_total{event_type, outcome}          # ingest health
wikantik_insights_rejected_total{reason}                     # abuse / validation visibility
wikantik_insights_queue_depth                                # backpressure
wikantik_insights_dropped_total                              # queue overflow
wikantik_insights_ingest_rows_total{engine, outcome}         # ingest health
wikantik_insights_ingest_last_success_timestamp{engine}      # staleness alerting
wikantik_retrieval_queries_total{surface, actor, outcome}    # demand volume
wikantik_mcp_tool_calls_total{tool, outcome}                 # bounded by tool count (~50)
```

Label-value sets are bounded and enumerable: `event_type` ~6, `outcome` ~4, `reason` ~8,
`tool` ~50. Worst case is low hundreds of series.

**One metrics surface.** Whatever endpoint already serves Prometheus scrapes serves these too. A
second exporter on a second port would double the scrape config and the network-exposure surface
for no benefit.

### D3 — Acquisition collection: extend, do not rebuild

**Requirement.** Know which queries put us in front of people, on **which engine**, at what
position, and how often they clicked — and keep that history.

**Why engine identity is first-class here, not a dimension added for completeness.** Two facts
about how this wiki is built make the engines behave very differently for it:

- **Submission is already asymmetric.** The wiki pushes new and changed URLs via **IndexNow**,
  which Bing, Yandex, and Seznam consume and which Google does not participate in; Google's ping
  endpoint was retired and this repo records it as dead. New pages therefore reach Bing's index in
  minutes and Google's on Google's own schedule. An engine-blind design would attribute that
  structural difference to content quality.
- **The audience is machines, and Bing's index is the machine-facing one.** Bing supplies the
  retrieval layer behind ChatGPT search and Copilot. For a wiki whose live consumers are agents
  (§1.1), ranking in Bing *is* ranking in the answer layer those agents read. "Our audience is
  machines" and "Bing is our stronger channel" are one fact seen from two directions.

A third factor — Google's posture toward young, low-backlink, machine-authored corpora — is real
and largely outside our control, which is a reason to **measure** the gap rather than assume it.

**Decision.** The collector already exists and already covers three engines and eight sites
(§1.2). **Wikantik does not build a second collector.** It gains the durable fact store the
existing one lacks, and jakemon gains one sink that persists the rows it already fetches.

Wikantik polling the webmaster APIs itself would duplicate working code, duplicate three sets of
credentials, double the API quota spend, and create two sources of truth that will disagree.

**Division of labour.**

| Concern | Home | Why there and not elsewhere |
|---|---|---|
| Fetching from Google / Bing / Yandex | **jakemon** | Already built, already scheduled, serves 8 sites; engine credentials live in one place |
| Operational alerting on visibility | **jakemon** | It is a monitoring stack; this is monitoring |
| Durable per-(snapshot, engine, site, page, query) facts | **Wikantik** | Needed for calibration and effect measurement, which the poll-and-discard model cannot support |
| Rules needing page state — frontmatter, `verified_at`, cluster, KG coverage | **Wikantik** | Only the wiki holds these; shipping them to jakemon would export the content model |
| Agent-side demand — retrieval queries, bundle coverage | **Wikantik** | Only the wiki sees MCP traffic |
| Effect ledger and calibration | **Wikantik** | Requires the fact store and the change history in one place |
| The agent-facing backlog | **Wikantik** | The consumer already speaks to the wiki over MCP |

**The seam.** jakemon's poll loop gains one sink that POSTs the rows it has already parsed to a
Wikantik ingestion endpoint (§7.1). One new function in an existing loop, against one new endpoint
— versus a second collector with its own credentials, schedule, and quota. The existing Prometheus
gauges and Grafana dashboard are unaffected and keep working exactly as they do now.

**Known limitations that survive regardless of where collection lives.** Google's data lags 2–3
days and stays mutable for ~3 more, so ingestion re-sends a rolling window and the store upserts.
Every engine omits low-volume queries for privacy; on a low-traffic site that anonymized share can
be most of total impressions. Page-level totals are therefore trustworthy while query breakdowns
are a lower bound — which is why the thresholds in §7.3 read page-level rollups and use query rows
only for vocabulary work.

### D4 — Build into the wiki vs. run an analytics service

**Requirement.** Produce a ranked backlog that an autonomous agent can consume, where ranking
depends on acquisition data, retrieval outcomes, **and page state** — frontmatter tags, summary,
cluster, `verified_at`, confidence, Knowledge Graph coverage.

**Decision rationale.** External analytics — self-hosted or managed — can count pageviews, but none
of them can join a pageview against `verified_at`, against a page's frontmatter, or against whether
its Knowledge Graph node has any edges. Producing the backlog would mean exporting and rejoining
anyway.

**Decision.** Build it into the wiki as a subsystem.

The deciding argument is not convenience — it is that **the join is the product**. "This page gets
2,400 impressions a month, its CTR is half what its position predicts, its summary is 22 characters
long, and it was last verified 14 months ago" is a single actionable sentence that no external
analytics tool can produce, because three of those four facts live only here.

### D5 — Module placement

**Requirement.** Rule logic must be unit-testable without booting a wiki engine or a database.
Ingestion needs HTTP and JDBC. Read surfaces live in the REST and MCP modules.

**Decision.** A new `wikantik-insights` module depending only on the shared API module, the JSON
library, and the JDK HTTP client. It defines four ports:

| Port | Responsibility | Implementation |
|---|---|---|
| `SearchConsoleClient` | Fetch rows for a date range | In-module, HTTPS |
| `InsightsStore` | Read/write all five tables | In-module, JDBC |
| `PageFacts` | Given a slug: exists?, title, summary, tags, cluster, `verified_at`, confidence | Adapter in `wikantik-main` |
| `ContentOpportunityService` | The rule engine | In-module, pure over the above |

The rule engine is a pure function of `(acquisition rows, demand rows, page facts, config,
today)`. Every rule in §7.3 is testable with three fakes and no I/O — which matters, because rules
with arithmetic thresholds are exactly the code that rots silently.

The rule engine is a pure function of `(acquisition rows, demand rows, page facts, config, today)`.
Every rule in §7.3 is testable with three fakes and no I/O — which matters, because rules with
arithmetic thresholds are exactly the code that rots silently.

### D6 — Secrets

The Search Console OAuth2 refresh token and client secret are long-lived credentials for an
external account. They go in the encrypted credential store, never in a properties file and never
in the repo. The daily job reads them by key at run time and holds the short-lived access token in
memory only.

### D7 — The primary read surface is an MCP tool

**Requirement.** The consumer is an autonomous agent, and it must be able to ask "what should I
work on next, and on what evidence" without scraping a dashboard or parsing a file drop.

**Decision.** The backlog is exposed as **`list_content_opportunities`** on the admin MCP endpoint,
returning typed, scored, evidence-carrying items. A second tool, **`snooze_opportunity`**, records
a decision not to act so the agent does not re-propose a declined item forever.

Two new tools, not a family. No new protocol, no queue, no file interchange. The human-facing
admin page (§7.5.2) reads the same service — one rule engine, two renderings.

`get_page_performance` (per-page drill-down) is specified in §7.5.1 as optional and should be added
only if the admin page proves insufficient for debugging a suggestion.

### D8 — Identity and privacy model

**Requirement.** Deduplicate same-day activity without building a persistent profile, without
storing an IP address, and without touching the reader's device.

**Decision.**

```
session_hash = HMAC-SHA256(server_secret, client_ip ‖ "\n" ‖ user_agent ‖ "\n" ‖ utc_date)[:16]
```

- Keyed by a server-held secret, so the hash is not recomputable from the DB alone.
- Rolls at UTC midnight, so cross-day linkage is impossible by construction.
- Deterministic across restarts (unlike a random in-memory salt), so a redeploy does not split
  sessions.
- Raw IP and user-agent are used to compute the hash and are then discarded. Neither is persisted.
- No cookie, no `localStorage`, no device fingerprint, no cross-site identifier, no third party.

See §8 for the legal posture this supports, stated precisely rather than asserted.

### D9 — One exit beacon per visit

**Requirement.** Know how far into a page a reader got, and how long they spent, without a beacon
storm.

**Decision.** One `page_exit` event per visit carrying `(active_dwell_ms, max_scroll_percent)`.

Maximum scroll depth subsumes every intermediate milestone — a reader who reached 75% necessarily
passed 25% and 50% — so milestone counts are a `CASE` expression at read time rather than four
separate writes. One request per visit, one row per visit, and no partial-session reconstruction.

### D10 — Wikantik-only rules; store every site's rows

**Requirement.** jakemon already polls eight sites. Decide how much of this design is built for
Wikantik specifically.

**Decision.** Rules, joins, and read surfaces are built **for `wiki.wikantik.com` and
`wikantik.com` only**. The fact store retains rows for **every site jakemon polls**.

**Why the rules are Wikantik-specific.** Search data has identical shape for every site — query,
page, impressions, clicks, position — so there is no site-type variation in the acquisition layer
at all. All the variation lives in what metadata is available to join against and what actions
exist. The layer worth sharing is therefore already site-agnostic, and the layer worth specializing
is already specialized. Going deep on the stack with the richest metadata is what produces
transferable knowledge about which rules actually work.

**Why the store is not.** History cannot be created retroactively. A site with no retained rows
starts effect measurement from zero on the day someone wants it. The schema is site-agnostic by
construction — `engine` and `site_host` are columns, not assumptions — so storing everything is a
configuration default rather than a design change, and it makes the Wikantik work no shallower.
Volume across all eight sites is on the order of 10⁶ rows/year, which is immaterial (D1).

**What keeps that split cheap.** The `PageFacts` port (D5) is the seam. Rules read
"high-traffic content that has aged out"; only the adapter knows that "aged out" means
`verified_at`. **Do not inline wiki calls into rule bodies** — that is the one shortcut that would
make this decision expensive to revisit.


---

## 6. Data model

Five new tables and one additive extension to an existing one. All DDL is idempotent, grants to the
application role, and contains **no data backfills** — per repo convention, backfills are one-shot
scripts outside the migration sequence.

Migration numbers below assume V050 is the next free slot at time of writing; confirm and shift as
a block if others land first.

### 6.1 `search_visibility_snapshot` — V050 (Phase 0)

```sql
CREATE TABLE IF NOT EXISTS search_visibility_snapshot (
    snapshot_date DATE        NOT NULL,                -- window END date, from the snapshot filename
    window_days   SMALLINT    NOT NULL,                -- trailing window the aggregate covers (28)
    engine        TEXT        NOT NULL,                -- 'google' | 'bing' | 'yandex'
    site_host     TEXT        NOT NULL,                -- as keyed in jakemon's sites.json
    page_path     TEXT        NOT NULL,                -- normalised, no scheme/host/fragment
    query_text    TEXT        NOT NULL,                -- '' for the page-level rollup row
    impressions   INTEGER     NOT NULL,
    clicks        INTEGER     NOT NULL,
    position      NUMERIC(6,2),                        -- average position, NULL if unreported
    fetched_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (snapshot_date, engine, site_host, page_path, query_text)
);

CREATE INDEX IF NOT EXISTS idx_sv_daily_day    ON search_visibility_snapshot (snapshot_date);
CREATE INDEX IF NOT EXISTS idx_sv_daily_engine ON search_visibility_snapshot (engine, snapshot_date);
CREATE INDEX IF NOT EXISTS idx_sv_daily_page       ON search_visibility_snapshot (site_host, page_path, snapshot_date);
CREATE INDEX IF NOT EXISTS idx_sv_daily_query      ON search_visibility_snapshot (query_text, snapshot_date);

GRANT SELECT, INSERT, UPDATE, DELETE ON search_visibility_snapshot TO :app_user;
```

Notes:

- The primary key makes the rolling re-fetch an idempotent `INSERT … ON CONFLICT DO UPDATE`. A day
  re-fetched five times converges rather than duplicating.
- `query_text = ''` marks the **page-level rollup**, fetched as a separate `dimensions:[date,page]`
  request. This row is authoritative for page totals; summing the query rows undercounts by the
  anonymized share (D3). Every threshold in §7.3 that reads impressions reads the rollup row.
- `page_path` is normalised on write: strip scheme and host, strip fragment, strip trailing slash,
  percent-decode. Without this, `/wiki/Foo`, `/wiki/Foo/`, and `/wiki/Foo#bar` become three pages.
- No PII. Query strings are user-authored and covered by the retention policy in §8.3.

### 6.2 `retrieval_query_log` extension — V051 (Phase 1)

The demand log already exists with `(query_text, actor_type, source_surface, result_count,
created_at)`. Three additive columns close the gaps the rules need:

```sql
ALTER TABLE retrieval_query_log ADD COLUMN IF NOT EXISTS session_hash    VARCHAR(16);
ALTER TABLE retrieval_query_log ADD COLUMN IF NOT EXISTS clicked_rank    INTEGER;
ALTER TABLE retrieval_query_log ADD COLUMN IF NOT EXISTS coverage        TEXT;

CREATE INDEX IF NOT EXISTS idx_rql_session
    ON retrieval_query_log (session_hash, created_at)
    WHERE session_hash IS NOT NULL;
```

- `session_hash` — computed per D8. Enables reformulation pairing (§7.3, rule 4). Nullable: agent
  surfaces have no session.
- `clicked_rank` — 1-based rank of the result the user opened, `NULL` if nothing was clicked. This
  is the single most valuable missing field: it separates "search returned 20 results" from
  "search returned 20 results and none of them were the answer".
- `coverage` — bundle coverage confidence (`strong` / `partial` / `weak` / `unknown`) for bundle
  surfaces. A non-empty bundle with `weak` coverage is a content gap that a raw result count hides.

**Also in Phase 1: fix the typeahead write amplification.** The log currently records one row per
keystroke, which is why the 90-day human sample reads `P` / `Per` / `Personal `. Only *submitted*
queries are logged; incremental typeahead requests are not. Until this is fixed, every
frequency-based rule is measuring keyboard mechanics.

### 6.3 `content_change_log` — V052 (Phase 2)

```sql
CREATE TABLE IF NOT EXISTS content_change_log (
    id                    BIGSERIAL   PRIMARY KEY,
    page_path             TEXT        NOT NULL,
    change_type           TEXT        NOT NULL,  -- title|summary|tags|body|new_page|internal_links
    opportunity_type      TEXT,                  -- rule that motivated it, NULL if manual
    applied_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    applied_by            TEXT        NOT NULL,  -- login or agent identifier
    note                  TEXT,

    baseline_start        DATE        NOT NULL,
    baseline_end          DATE        NOT NULL,
    baseline_impressions  INTEGER     NOT NULL,
    baseline_clicks       INTEGER     NOT NULL,
    baseline_ctr          NUMERIC(8,5),
    baseline_position     NUMERIC(6,2),

    evaluated_at          TIMESTAMPTZ,
    effect                TEXT,                  -- improved|no_effect|regressed|insufficient_data
    effect_ctr_delta      NUMERIC(8,5),          -- site-adjusted, see 7.4.3
    effect_position_delta NUMERIC(6,2),
    effect_detail         JSONB
);

CREATE INDEX IF NOT EXISTS idx_ccl_page      ON content_change_log (page_path, applied_at DESC);
CREATE INDEX IF NOT EXISTS idx_ccl_pending   ON content_change_log (applied_at)
    WHERE evaluated_at IS NULL;

GRANT SELECT, INSERT, UPDATE ON content_change_log TO :app_user;
GRANT USAGE, SELECT ON SEQUENCE content_change_log_id_seq TO :app_user;
```

The baseline is captured **at write time**, not reconstructed later. Reconstructing it later works
only until retention or a GSC restatement moves the ground under it.

### 6.4 `content_opportunity_snooze` — V053 (Phase 2)

```sql
CREATE TABLE IF NOT EXISTS content_opportunity_snooze (
    opportunity_type TEXT        NOT NULL,
    target           TEXT        NOT NULL,   -- page_path or query_text
    snoozed_until    DATE        NOT NULL,
    reason           TEXT        NOT NULL,
    snoozed_by       TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (opportunity_type, target)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON content_opportunity_snooze TO :app_user;
```

`reason` is `NOT NULL` deliberately: a declined suggestion with no recorded reason is
indistinguishable from a bug, and six months later nobody remembers which it was.

### 6.5 `telemetry_event` and `telemetry_page_daily` — V054 (Phase 3 only)

```sql
CREATE TABLE IF NOT EXISTS telemetry_event (
    id                 BIGSERIAL   PRIMARY KEY,
    event_type         TEXT        NOT NULL,  -- page_view|page_exit|code_copy|search_click
    site_host          TEXT        NOT NULL,
    page_path          TEXT        NOT NULL,
    session_hash       VARCHAR(16) NOT NULL,
    country_code       CHAR(2),
    is_bot             BOOLEAN     NOT NULL DEFAULT FALSE,
    active_dwell_ms    INTEGER,               -- page_exit
    max_scroll_percent SMALLINT,              -- page_exit
    code_language      TEXT,                  -- code_copy
    code_char_count    INTEGER,               -- code_copy
    clicked_rank       SMALLINT,              -- search_click
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tel_page_day ON telemetry_event (page_path, created_at);
CREATE INDEX IF NOT EXISTS idx_tel_created  ON telemetry_event (created_at);

CREATE TABLE IF NOT EXISTS telemetry_page_daily (
    day               DATE     NOT NULL,
    site_host         TEXT     NOT NULL,
    page_path         TEXT     NOT NULL,
    views             INTEGER  NOT NULL,
    unique_sessions   INTEGER  NOT NULL,
    median_dwell_ms   INTEGER,
    p90_dwell_ms      INTEGER,
    reached_75_pct    INTEGER  NOT NULL,
    code_copies       INTEGER  NOT NULL,
    PRIMARY KEY (day, site_host, page_path)
);

GRANT SELECT, INSERT, DELETE ON telemetry_event TO :app_user;
GRANT USAGE, SELECT ON SEQUENCE telemetry_event_id_seq TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON telemetry_page_daily TO :app_user;
```

**Typed columns, no free-form JSON payload.** This is a deliberate constraint on an unauthenticated
public endpoint: a JSON column accepts whatever an attacker sends, and a typed column does not. It
also forces every new event type through a schema review rather than appearing silently in a blob.

`telemetry_event` is raw and pruned at 90 days; `telemetry_page_daily` is the permanent rollup.
One scheduled statement per night does the rollup, one does the prune (§8.3).

---

## 7. Component design

### 7.1 Acquisition — retaining what jakemon already fetches

No collector is built here. jakemon's poll loop already parses normalized rows for every
(engine, site); it currently discards them after computing opportunities. It gains one sink that
ships them, and Wikantik gains one endpoint that stores them.

**jakemon side — one function in an existing loop.** After each successful poll, POST the already-
parsed `by_query`, `by_page`, and `query_page` rows for the wikantik sites to Wikantik. Stdlib
only, matching the repo's no-third-party-deps rule; `http_json` in `visibility/common.py` already
does exactly this shape of request. The sink is **best-effort and must never fail the poll** — a
Wikantik outage may not stop metrics being exported or opportunities being computed, because that
pipeline serves seven other sites. On failure: log, increment a counter, move on.

**Wikantik side — `POST /admin/insights/ingest`.**

| | |
|---|---|
| Auth | Admin bearer credential, LAN-only. Not public, unlike the Phase 3 collector. |
| Body | `{ engine, site, fetched_at, by_page: [...], by_query: [...], query_page: [...] }` |
| Semantics | Idempotent upsert keyed by `(day, engine, site_host, page_path, query_text)`; re-sending a window converges rather than duplicating |
| Validation | Engine and site against an allowlist; row counts capped; `page_path` normalised on write (strip scheme/host/fragment/trailing slash, percent-decode) |
| Response | `{ rows_upserted, rows_rejected }` — so a silent no-op is visible from the caller |

**Why push rather than pull.** Wikantik pulling from jakemon would need a jakemon-side query API
that does not exist, and would still leave Wikantik guessing when a poll had completed. The
producer already knows when it has fresh rows.

**Rolling window.** jakemon re-sends the last 5 days on every poll. Google restates recent data for
~3 days, so a single-shot send would freeze the first, least accurate version of every day. The
upsert key makes re-sending free.

**Failure posture.** Ingestion failures log with context and increment
`wikantik_insights_ingest_rows_total{outcome="error"}`; the last-success timestamp is left
untouched. Alerting is a staleness threshold on that timestamp, not an error count — a pipeline
that stops delivering is the condition worth waking up for.

**No new credentials.** The three engine credentials stay in jakemon, where they already are and
where seven other sites depend on them. Wikantik never holds a webmaster-console credential — a
meaningful reduction in secret sprawl.

### 7.2 Demand — in-process signal capture

All four capture points are async, fail-open, and off the request critical path. **No content
signal may ever fail a page render, a save, or a search.** Each writes through a bounded queue
with a single drain thread doing batched inserts; a full queue drops the event and increments
`wikantik_insights_dropped_total`.

| Signal | Where it is captured | New field |
|---|---|---|
| Search query + result count | Search endpoint, on **submitted** queries only | `session_hash` |
| Search result click + rank | New lightweight endpoint hit on result activation | `clicked_rank` |
| Bundle coverage | Bundle and briefing endpoints, from the existing coverage record | `coverage` |
| MCP tool call + outcome | MCP dispatch layer → Prometheus counter only, no row | — |

**Click capture.** Result activation posts `{query, rank, target}` to the same demand path, which
updates the most recent matching log row for that session. This is one small endpoint, not a
tracking redirect — a redirect would put a network hop between the reader and the page they asked
for, in exchange for the same datum.

**MCP outcomes go to Prometheus only.** Tool-call volume is operational, bounded by tool count, and
has no per-page dimension worth storing. The *content* signal from the MCP path — an empty or weak
bundle — is already captured as a `retrieval_query_log` row with `coverage`.

### 7.3 The opportunity engine

Wikantik does **not** recompute what jakemon already detects. Its engine holds only the rules that
need data jakemon cannot see.

**Imported from jakemon, unchanged.** These five arrive as opportunities via the ingest payload and
are merged into the backlog as-is. Re-deriving them in Java would create a second implementation of
the same arithmetic, guaranteed to drift:

| Imported detector | What it finds |
|---|---|
| `striking_distance` | Page-2 queries with real demand — a small rank push wins the most clicks |
| `ctr_gap` | Ranks well, converts badly — the title/meta snippet underperforms |
| `content_gap` | Demand exists, best position is deep — no strong page covers the term |
| `cannibalization` | Two or more pages rank for one query, splitting equity |
| `decay` | Average position worsened materially against the prior window |

jakemon's `EXPECTED_CTR` curve stays the single place the CTR model is defined. Wikantik's
contribution is to make it **calibratable** for the first time by retaining the history it needs
(§7.4.4), then feeding the measured curve back.

**Native to Wikantik.** Four rules, each requiring something only the wiki holds. Each names its
trigger, minimum support, priority formula, and how it resolves; a rule that cannot state a
minimum support does not ship.

All windows are 28 days unless stated. All thresholds are configuration keys (§10) with the
defaults shown, chosen conservatively — under-firing is recoverable, over-firing trains an
autonomous agent on noise.

#### Rule 1 — `AGENT_GAP`   *(needs: MCP traffic)*

| | |
|---|---|
| **Trigger** | A retrieval on an agent surface returning zero sections, or `coverage ∈ {weak, unknown}`. |
| **Minimum support** | 2 occurrences in 28 days, from ≥ 2 distinct calls. |
| **Priority** | `occurrences × 2` |
| **Action** | Curate the Knowledge Graph relations, or write the missing section. |
| **Resolves when** | The same query returns `coverage ∈ {strong, partial}`. |

Today this is the **only rule in the entire design with a live denominator**, and it is invisible
to jakemon, which cannot see MCP traffic. Build it first.

#### Rule 2 — `ENGINE_DIVERGENCE`   *(needs: retained multi-engine history)*

| | |
|---|---|
| **Trigger** | A page ranking materially better on one engine than another across a shared query set. |
| **Minimum support** | ≥ 100 impressions on the stronger engine in 28 days, ≥ 10 shared queries, position gap ≥ 10. |
| **Priority** | `strong_engine_clicks × 0.5` |
| **Action** | **Diagnostic, usually "change nothing on this page."** |
| **Resolves when** | The gap closes below 10 positions, or it is snoozed as out of reach. |

This rule is impossible without the fact store — comparing engines requires history, and the
current pipeline keeps none. It earns its place by **suppressing false work rather than creating
it**: Bing-strong / Google-weak points at domain authority and backlinks, not at the page, and
without it every such page looks like a `ctr_gap` failure in Google's data and gets pointlessly
rewritten. It therefore runs **before** the imported `ctr_gap` and `striking_distance` rules and
suppresses them for the affected page-query pairs (§7.3 cross-rule constraints).

Given §1.2 — that delivered clicks per engine are not currently stored anywhere — this rule is
also the mechanism by which the "which engine actually delivers" question finally gets a
data-backed answer rather than two people reading two different dashboards.

#### Rule 3 — `VOCABULARY_GAP`   *(needs: page frontmatter)*

| | |
|---|---|
| **Trigger** | Either (a) a Search Console query with ≥ 5 clicks to page P whose content words appear in neither P's title, summary, nor tags; or (b) a same-session reformulation pair where the first query returned zero results or no click and the second led to a click on P. |
| **Minimum support** | (a) 5 clicks. (b) 2 occurrences of the pair, second query within 60 s of the first. |
| **Priority** | `impressions × 0.05` (a), `occurrences × 3` (b) |
| **Action** | Add the term as a tag or alias; tighten the summary to include the reader's vocabulary. |
| **Resolves when** | The term appears in P's frontmatter. |

Case (b) is the "our words aren't their words" signal, and it is the reason `session_hash` was
added in §6.2. Both cases produce the same fix, so they share a rule.

#### Rule 4 — `STALE_HIGH_TRAFFIC`   *(needs: verification age)*

| | |
|---|---|
| **Trigger** | A page with real traffic whose verification has aged out. |
| **Minimum support** | ≥ 200 impressions in 28 days **and** `verified_at` older than 180 days (or confidence not `authoritative`). |
| **Priority** | `impressions × 0.02` |
| **Action** | Re-verify or refresh. |
| **Resolves when** | `verified_at` is refreshed. |

This is the rule that makes traffic data pay for the verification model already in the corpus:
staleness everywhere is a burn-down list, but staleness *on pages people actually read* is a queue.

#### Cross-rule constraints

Applied to the merged backlog — imported and native alike.

- **Divergence suppression.** `ENGINE_DIVERGENCE` runs first. Where it fires, the imported
  `ctr_gap` and `striking_distance` opportunities for the same page are suppressed on the weak
  engine, because the weakness is already explained and rewriting the page will not fix it.
- **Cooldown.** No page may generate an automatic optimization more than once per 60 days.
  Prevents thrash and — more importantly — keeps the 28-day measurement windows in §7.4 clean.
  Two changes inside one window make the effect unattributable.
- **Snooze.** A `(type, target)` under an unexpired snooze is filtered out before scoring.
- **Global floor.** No rule fires on fewer than 10 impressions or 2 occurrences, whatever its own
  threshold says. A backstop against a misconfigured threshold. jakemon's own `MIN_IMPRESSIONS`
  floor of 50 is stricter and applies upstream of the imported types.
- **Bot exclusion.** Rows flagged `is_bot` are excluded from every rule by default.

#### Ranking across types

Imported and native opportunities sort into one list. jakemon already expresses its five as
`expected_uplift` in **expected incremental clicks**, so the native four adopt the same unit rather
than inventing a second scale — the backlog is comparable end to end.

The native conversion weights (`× 0.5`, `× 0.05`, `× 0.02`, `× 2`) are **heuristics with no
empirical basis at design time**, exactly like jakemon's per-type `confidence` values. They are
configuration, not code, because they are guesses — and §7.4.4 is the mechanism that retires them.

### 7.4 Effect measurement

#### 7.4.1 Recording

When the agent (or a human) applies a content change, it writes a `content_change_log` row
capturing the page, the change type, the motivating opportunity, and the **28-day pre-change
baseline** from `search_visibility_snapshot`.

#### 7.4.2 Evaluating

A nightly job picks up rows where `applied_at ≤ today − 28` and `evaluated_at IS NULL`, and
compares the 28 days after against the recorded 28 days before.

To control for query-mix drift — a page can gain CTR purely because it stopped ranking for a bad
query — the comparison is **restricted to the intersection of queries present in both windows**.

#### 7.4.3 Controlling for confounds

A before/after comparison is not a controlled experiment. Seasonality, ranking-algorithm updates,
and competitors all move CTR without anyone touching the page. The mitigation is a cheap
difference-in-differences against the site's own trend:

```
adjusted_ctr_delta = (page_ctr_after − page_ctr_before)
                   − (site_ctr_after − site_ctr_before)
```

Verdicts, on the adjusted delta:

| Condition | Verdict |
|---|---|
| `baseline_impressions < 100` | `insufficient_data` |
| adjusted delta ≥ +15% relative to baseline CTR | `improved` |
| adjusted delta ≤ −15% relative to baseline CTR | `regressed` |
| otherwise | `no_effect` |

**This is a weak quasi-experiment and the design says so.** With one site, no control group, and
no randomization, a single verdict is suggestive, not conclusive. Its value is in aggregate: fifty
verdicts across a change type tell you whether that class of edit works, even though any one of
them might be noise. `insufficient_data` is expected to be the majority verdict for a long time,
and that is the honest answer rather than a failure of the mechanism.

#### 7.4.4 Self-calibration — how the guesses get retired

Once ≥ 20 evaluated changes exist for an opportunity type, the engine can compare **predicted**
priority against **realized** click delta and adjust that type's weight toward the observed ratio.

This is the closing arc of the whole design. The weights in §7.3 are guesses today; the ledger is
the mechanism by which they stop being guesses. Until 20 verdicts exist for a type, its weight
stays at the configured default and the admin page displays it as *uncalibrated* — the reader
should always be able to see which numbers have been earned and which are still assumed.

### 7.5 Read surfaces

#### 7.5.1 MCP tools

**`list_content_opportunities`** — required.

```
Input:  { type?: string, limit?: int (default 20), min_priority?: number,
          include_snoozed?: bool (default false) }
Output: { opportunities: [ {
            type, target, priority, evidence: { ... rule-specific numbers ... },
            suggested_action, first_seen, calibrated: bool
          } ], count, generated_at, uncalibrated_types: [...] }
```

`evidence` carries the actual numbers that fired the rule, so the agent can sanity-check a
suggestion instead of trusting a score. `calibrated` is deliberately visible: an agent should weigh
an uncalibrated suggestion less.

**`snooze_opportunity`** — required.

```
Input:  { type: string, target: string, days: int, reason: string (required) }
Output: { snoozed_until, previously_snoozed: bool }
```

**`get_page_performance`** — optional, Phase 2, only if the admin page proves insufficient for
debugging a suggestion. Returns per-page acquisition, demand, and change history for a window.

#### 7.5.2 Admin page

One SPA route, `/admin/insights`, three panels reading the same service as the MCP tools:

1. **Acquisition** — impressions/clicks/CTR trend, top queries, top pages, sync freshness.
2. **Demand** — zero-result and weak-coverage queries; the reformulation pairs behind rule 4.
3. **Backlog** — the ranked opportunity list with evidence, snooze control, and the change ledger
   with effect verdicts.

No Grafana, no BI tool. Operational dashboards stay where operational metrics already are; this
page is for content decisions and reads exclusively from SQL.

### 7.6 The browser collector (Phase 3, gated)

Specified now so the gate has something concrete to open onto. **Not built until §9.6 passes.**

#### 7.6.1 Events

Four. That is the complete list.

| Event | Fires when | Carries |
|---|---|---|
| `page_view` | Page load and each SPA route change | page path |
| `page_exit` | `visibilitychange → hidden`, or `pagehide` | `active_dwell_ms`, `max_scroll_percent` |
| `code_copy` | Copy control activated on a code block | `language`, `char_count` |
| `search_click` | Search result activated | `rank` |

#### 7.6.2 Single-page-application semantics

This is the largest cost in the collector. The reader-facing wiki is a client-routed SPA:
**route changes are not page loads.** The collector must therefore:

- Treat a route change as a virtual page view: flush the previous page's `page_exit` first, then
  start a fresh visit.
- Pause the dwell timer on `blur` and resume on `focus`, so a backgrounded tab does not inflate
  dwell.
- Use `visibilitychange → hidden` as the primary exit signal and `pagehide` as the backstop —
  `beforeunload` is unreliable on mobile and suppresses back/forward cache.
- Handle back/forward cache restores (`pageshow` with `persisted`) as a new visit.
- Send at most one `page_exit` per visit, guarded against the exit path firing twice.

#### 7.6.3 Client budget and delivery

- ≤ 2 KB minified, zero dependencies, **bundled into the existing SPA build** rather than injected
  as a separate script tag. This sidesteps the Content-Security-Policy nonce problem entirely: no
  inline script, no new external source.
- Delivery via `sendBeacon`, falling back to `fetch(…, {keepalive: true})`.
- Scheduled in idle time; no synchronous work on the main thread; no layout reads during scroll
  (passive listener, `requestAnimationFrame`-throttled max-scroll tracking).

#### 7.6.4 Endpoint and hardening

`POST /api/telemetry` → `204 No Content`, unauthenticated. This is a new public write surface and
is treated as one:

| Control | Rule |
|---|---|
| Body size | ≤ 4 KB, ≤ 20 events per request; oversize rejected without parsing |
| Event type | Enum allowlist; unknown types rejected, counted under `rejected{reason="unknown_event"}` |
| Page path | Must resolve to a real page in the index; unknown paths dropped. **This is the control that prevents unbounded row and label injection.** |
| Payload | Typed columns only — no free-form JSON is ever stored (§6.5) |
| Numeric fields | Range-clamped server-side (`dwell ≤ 4 h`, `scroll ∈ [0,100]`) |
| Rate limit | 60 events/min per session hash, 600/min per IP hash |
| Filter chain | Explicitly exempt from CSRF synchronizer-token checking — an unauthenticated cross-origin beacon cannot carry a token. The endpoint is write-only, stores no user-controlled free text, and performs no state change on any user's behalf, so there is nothing for CSRF to forge. Recorded here because a CSRF exemption should always be a documented decision, never an oversight. |
| `Sec-GPC: 1` / `DNT: 1` | Honored: `204` returned, nothing stored, and the client skips the beacon |
| Bot filtering | User-agent matched against a known-crawler list; flagged `is_bot`, excluded from all rules |

## 8. Privacy and legal posture

Stated precisely, because the consent-free posture is a claim that has to be earned.

### 8.1 Posture

- **No cookie banner is required.** Consent under ePrivacy attaches to *storing or accessing
  information on a user's terminal equipment*. This design stores nothing on the device: no cookie,
  no `localStorage`, no `sessionStorage`, no fingerprint. The consent trigger is therefore absent —
  not waived, absent.
- **No third party receives data.** No external analytics, ad network, or CDN-hosted script.
- **No cross-session or cross-site profile exists.** The session hash rolls at UTC midnight, and
  the marketing and wiki hosts share no identifier.

### 8.3 Retention

| Data | Retention | Rationale |
|---|---|---|
| `search_visibility_snapshot` | Indefinite | Aggregate, no personal data, and long trends are the point |
| `retrieval_query_log` | 400 days, then deleted | Query text is user-authored and can contain personal data |
| `telemetry_event` (raw) | 90 days, then deleted | Session-linked; only needed until rolled up |
| `telemetry_page_daily` | Indefinite | Aggregate, no session linkage |
| `content_change_log` | Indefinite | Operational record of our own edits |

Two scheduled statements per night: one rollup, one prune. The prune is unconditional — retention
that depends on someone remembering to run something is not retention.

### 8.4 Disclosure

A public privacy page, authored as a wiki page, stating in plain language what is collected, what
is not, retention periods, and the absence of cookies and third parties. Linked from the footer of
both sites. It is the same claim as §8.1 written for a reader rather than a lawyer, and having it
live as content means it is versioned and reviewable like everything else here.

---

## 9. Phases

Each phase is independently valuable and independently shippable. Nothing in a later phase is
required for an earlier one to pay off.

### Phase 0 — Stop throwing the data away (highest value, smallest surface)

**Goal.** Retain per-(snapshot, engine, site, page, query) facts so that, for the first time, the
delivered-traffic question can be answered per engine, the CTR curve can be calibrated, and an
effect can be measured.

**Work.** `search_visibility_snapshot` + V050 · `POST /admin/insights/ingest` with idempotent upsert ·
one best-effort sink in jakemon's poll loop · ingest-health metrics · the acquisition panel of
`/admin/insights` with an engine breakdown.

**Acceptance.**
- One poll cycle populates rows for all three engines across both wikantik sites.
- Re-sending the same window changes no row count (idempotent upsert).
- Page-level rollup rows exist and are used for totals (query rows are a lower bound, §D3).
- **Killing the Wikantik endpoint does not fail jakemon's poll** — metrics still export, the other
  seven sites are unaffected, and the failure appears as a counter and a stale timestamp.
- The admin panel renders impressions, clicks, CTR, and position for 28 days, **split by engine**.
- A hand-computed fixture confirms delivered clicks per engine now come out of SQL — the question
  §1.2 says is currently unanswerable.

**Why first.** Every other phase depends on retained history, and this is the smallest change that
produces it: one table, one endpoint, one function in an existing loop. It also opens the Phase 3
gate by making the click count measurable.

### Phase 1 — Demand signals

**Goal.** Capture what was asked and whether it was answered.

**Work.** V051 columns · **typeahead debounce fix** (§6.2) · session hash derivation · click-rank
capture endpoint and client hook · coverage recording on bundle surfaces · MCP tool-outcome
counters · the demand panel.

**Acceptance.**
- One log row per *submitted* search; typing a 20-character query produces one row, not twenty.
- A result click records a rank on the correct row.
- A weak-coverage bundle is distinguishable from a strong one in SQL.
- Every capture path is verified to fail open: with the log service unavailable, search, bundle,
  and briefing all still return `200`.

### Phase 2 — The loop

**Goal.** Turn signals into a ranked backlog, and measure whether acting on it helps.

**Work.** `wikantik-insights` module and its four ports · the six rules · V052/V053 ·
`list_content_opportunities` and `snooze_opportunity` · effect evaluation job · backlog and ledger
panels.

**Acceptance.**
- Each rule has unit tests at, just below, and just above its threshold — including a test that it
  stays silent on insufficient support.
- The global floor overrides a deliberately misconfigured per-rule threshold.
- A snoozed opportunity is absent until its expiry, then returns.
- The cooldown blocks a second automatic change on a page inside 60 days.
- An evaluated change with `baseline_impressions < 100` yields `insufficient_data`, not a verdict.
- Site-adjusted delta is verified against a hand-computed fixture.
- Uncalibrated types are reported as such in both surfaces.

### Phase 3 — Reader behavior (gated)

**Gate — mechanical, not a judgment call:**

> `search_visibility_snapshot` shows **≥ 1,000 clicks in a rolling 28-day window** to
> `wiki.wikantik.com`.

Phase 0 produces this measurement, so the system tells you when to build the next part. Below the
gate the collector cannot produce statistically meaningful per-page numbers, and building it early
means maintaining a public write endpoint for no return.

**Work.** V054 · the collector (§7.6) · the ingestion endpoint and its hardening · nightly rollup
and prune · behavior columns in the backlog panel.

**Acceptance.**
- SPA route change produces exactly one exit and one view, with dwell attributed to the correct page.
- A backgrounded tab accrues no dwell.
- An unknown page path is rejected and counted.
- A 5 KB body is rejected without parsing.
- `Sec-GPC: 1` returns `204` and stores nothing.
- No Prometheus series gains a page label (the D2 invariant test).

## 10. Configuration

| Key | Default | Meaning |
|---|---|---|
| `wikantik.insights.enabled` | `true` | Kill switch for the whole subsystem |
| `wikantik.insights.ingest.enabled` | `true` | Accept rows from jakemon |
| `wikantik.insights.ingest.engines` | `google,bing,yandex` | Engine allowlist |
| `wikantik.insights.ingest.sites` | `*` | Sites whose rows are **stored** (default: every site jakemon polls — D10) |
| `wikantik.insights.rules.sites` | `wiki.wikantik.com,wikantik.com` | Sites the rule engine **acts on** (D10) |
| `wikantik.insights.ingest.max_rows` | `50000` | Per-request row cap |
| `wikantik.insights.ingest.stale.hours` | `12` | Staleness alert threshold on last success |
| `wikantik.insights.opportunity.min_impressions` | `100` | `ENGINE_DIVERGENCE`, `STALE_HIGH_TRAFFIC` |
| `wikantik.insights.opportunity.min_occurrences` | `2` | `AGENT_GAP` |
| `wikantik.insights.opportunity.divergence.min_gap` | `10` | `ENGINE_DIVERGENCE` position gap |
| `wikantik.insights.opportunity.stale.days` | `180` | `STALE_HIGH_TRAFFIC` |
| `wikantik.insights.opportunity.weight.<TYPE>` | see §7.3 | Per-type priority weight; overwritten by calibration |
| `wikantik.insights.effect.window.days` | `28` | Before/after window |
| `wikantik.insights.effect.min_baseline_impressions` | `100` | Below this → `insufficient_data` |
| `wikantik.insights.change.cooldown.days` | `60` | Max one auto-change per page per window |
| `wikantik.insights.retention.query.days` | `400` | `retrieval_query_log` prune |
| `wikantik.insights.retention.raw.days` | `90` | `telemetry_event` prune |
| `wikantik.telemetry.enabled` | `false` | Phase 3 collector + endpoint |
| `wikantik.telemetry.gate.clicks28d` | `1000` | Documented gate; advisory, not enforced in code |

---

## 11. Risks

| Risk | Containment |
|---|---|
| **Agent acts on statistical noise** — the primary risk, because the consumer is autonomous | Per-rule minimum support, distinct-session requirement, global floor, 60-day cooldown, `calibrated` flag exposed to the agent, and effect measurement that catches a bad change class within a month |
| **CTR optimization degrades accuracy** — titles drift toward clickbait | Title/summary changes must preserve the page's claims; verification status is never silently downgraded; every change is logged and reviewable in the ledger |
| **GSC query privacy filtering hides most queries** | Page-level rollup rows are authoritative for all thresholds; query rows are used only for vocabulary work, where a lower bound is still useful |
| **Effect verdicts are confounded** | Difference-in-differences against the site trend, query-intersection restriction, `insufficient_data` as an honest and expected verdict, and aggregate-over-single interpretation stated in §7.4.3 |
| **Public write endpoint abused** (Phase 3) | Page-path allowlisting against the real index, typed columns with no free-form JSON, size and rate caps, bot flagging |
| **Prometheus cardinality explosion** | The D2 invariant, enforced by test |
| **The subsystem breaks the wiki** | Every path async, fail-open, off the critical path; kill switch; bounded queues that drop rather than block |
| **Nobody looks at it** | The backlog is delivered through the surface the agent already calls, not a dashboard someone has to remember to open |

---

## 12. Implementation notes for this repository

Details that will silently break a build or a gate if missed:

- **New MCP tools** must be added to the MCP instructions resource **and** to the protocol IT's
  expected-tool list, or the instructions-registry drift test, the MCP instructions IT, and the
  protocol IT all go red. The admin tool count moves 27 → 29.
- **New Maven module** must declare the mocking library in test scope, or surefire fails VM
  initialization on the inherited javaagent.
- **New SPA route** (`/admin/insights`) requires dual registration — the deployment descriptor
  **and** the SPA routing filter's exact-match array. Either one alone yields a 404.
- **Migrations** are DDL-only and idempotent, with grants via the app-user variable. The rollup and
  prune jobs are runtime code, not migration steps — no data operations in the migration sequence.
- **Integration tests** for the ingestion endpoint must not assume the unrestricted-access
  configuration used by the MCP IT environment; the endpoint is unauthenticated by design, so the
  tests that matter are the *rejection* paths.
- **Gate on the canonical suite** (`bin/run-tests.sh --parallel 4`) before committing, not a
  targeted `-Dtest=` run — the module additions and web-descriptor edits here are exactly the kind
  of change that breaks a different module.
- **Corpus caution.** The privacy page (§8.4) is content and must be published through the live
  wiki, not the repository checkout — the two corpora are not copies of each other.

---

## 13. Open questions

1. **Does the marketing site warrant its own Search Console breakdown in the admin page,** or is
   one blended acquisition view enough? Deferred until there is data to look at.
2. **Should `AGENT_GAP` distinguish agent identity?** Knowing *which* agent hit an empty bundle
   would help, but it introduces an identity dimension with its own retention question. Deferred.
3. **Calibration sample size.** 20 evaluated changes per type is a guess at where a weight update
   becomes better than the prior. It should itself be revisited once the first type reaches it.
4. **Yandex's role.** It is collected today and the store keeps it, but no rule currently
   distinguishes it and its relevance to an English-language technical wiki is unproven.
   `ENGINE_DIVERGENCE` will produce the evidence either way; until then it is retained, not acted
   on.

---
