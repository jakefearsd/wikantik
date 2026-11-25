# Back-Office Suite: Reader Analytics Views Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Wikantik a read-only, host-restricted login on the suite's Umami database that can read three suite-owned views (engagement, acquisition, vitals) plus the existing `analytics_traffic`, and nothing else.

**Architecture:** All work is in `~/source/backoffice` (repo: github.com/jakefearsd/backoffice). New SQL lives beside `sql/umami-views.sql` and is applied by the existing `umami-provision` one-shot. `umami-db` gets a LAN-only published port and a templated `pg_hba` that lets `wikantik_ro` in from docker1 only. The role copies the `jakemon_etl` pattern (`scripts/etl-access.sh`, `sql/etl-role.sql`).

**Tech Stack:** Docker Compose, postgres:18 (digest-pinned, stock), bash, psql, python3 invariant checks, shellcheck.

**Spec:** `docs/superpowers/specs/2026-11-24-reader-acquisition-retention-design.md` (in the jspwiki repo), plus the base design on the wiki page `SimpleAgilityReaderEngagementLoop`.

**Precondition:** the suite's working tree has an uncommitted jakemon ETL feature (`scripts/etl-access.sh`, `sql/etl-*.sql`, edits to `docker-compose.yml`, `tests/compose_invariants.py`, `scripts/umami-provision.sh`, `bin/deploy.sh`). **Do not start until Jake has committed it**, then branch from that commit. Never commit or push in that repo without Jake's go-ahead (its CLAUDE.md).

## Global Constraints

- Test first: every compose change starts as a failing check in `tests/compose_invariants.py`. `tests/run.sh` and `tests/smoke.sh --fresh` pass before any commit touching compose, scripts or SQL.
- Stock images only, digest-pinned; no `build:`. Every service keeps the json-file log cap.
- Secrets are generated on the host, mode 600, never printed. A password reaches psql only through `\getenv`.
- The backup log lines `Back-office backup complete.` / `Back-office backup INCOMPLETE` never change.
- Views are `CREATE OR REPLACE VIEW`; tables `CREATE TABLE IF NOT EXISTS`; seed rows `INSERT … ON CONFLICT DO NOTHING`. Every provision run is idempotent.
- `wikantik_ro` can `SELECT` only `analytics_traffic`, `analytics_engagement`, `analytics_acquisition`, `analytics_page_vitals`. It can never read `website_event`, `session`, `event_data` or any Umami table.
- Event names (kebab-case) and property names (lower_snake): `page-exit` {`canonical_id`, `cluster`, `active_s`, `max_scroll_pct`}, `code-copy` {`canonical_id`, `lang`}, `wikilink-click` {`canonical_id`, `target`}, `outbound-click` {`canonical_id`, `dest`}, `diagram-interact` {`canonical_id`, `kind`}, `toc-click` {`canonical_id`}, `sign-in-click` {`canonical_id`}.
- Channel values: `paid-search`, `paid-social`, `email`, `feed`, `cross-property`, `organic-search`, `ai-assistant`, `social`, `referral`, `direct`. "Day" is the UTC calendar day.

## Review Focus

1. A superuser (`UMAMI_DB_USER`) or `jakemon_etl` connecting to the published port from a LAN host must be rejected; only `wikantik_ro` from `WIKANTIK_RO_CIDR` gets in from outside Docker. (Task 1 smoke check)
2. A bare `fbclid` with no UTMs is `social`, not `paid-social`; `gclid` without UTMs is `paid-search`. (Task 3 smoke check)
3. A visit whose landing view carries `?utm_medium=email` but whose referrer is `google.com` is `email` (rule order). (Task 3)
4. A referrer subdomain (`www.bing.com`, `m.facebook.com`) matches its parent domain row; an unknown host becomes `referral` with source = that host. (Task 3)
5. A `page-exit` with no `active_s` property (a malformed beacon) is counted in exits but ignored by the percentiles, not turned into a zero. (Task 2)

---

### Task 1: Published umami-db port and templated pg_hba

**Files:**
- Modify: `tests/compose_invariants.py` (`EXPECTED_PORTS`, the "only umami and listmonk may publish" check)
- Modify: `docker-compose.yml` (`umami-db`: `ports`, `command`, a read-only mount of `./config`)
- Create: `config/umami-pg_hba.conf.tmpl`
- Modify: `.env.example` (`UMAMI_DB_PORT=8087`, `WIKANTIK_RO_CIDR=192.168.5.51/32`)
- Modify: `tests/smoke.sh` (connection checks)

**Interfaces:**
- Produces: `umami-db` reachable at `${BACKOFFICE_BIND}:${UMAMI_DB_PORT:-8087}`; `WIKANTIK_RO_CIDR` env var; the template placeholder `@WIKANTIK_RO_CIDR@`.

- [ ] **Step 1: Write the failing invariant.** In `compose_invariants.py`, `EXPECTED_PORTS` gains `"umami-db": ("5432", "8087")`, published on `BIND` like the other two. Add a check that `umami-db`'s `command` contains `hba_file=` and that `./config` is mounted read-only.
- [ ] **Step 2: Run `tests/run.sh`.** Expected: FAIL naming `umami-db`.
- [ ] **Step 3: Write the template and the compose change.** Template rules, in order:
  ```
  local   all          all                                  trust
  host    all          wikantik_ro  @WIKANTIK_RO_CIDR@       scram-sha-256
  host    all          wikantik_ro  0.0.0.0/0               reject
  host    all          all          172.16.0.0/12           scram-sha-256
  host    all          all          0.0.0.0/0               reject
  ```
  (`172.16.0.0/12` covers Docker's bridge networks: Umami, the one-shots, backup and jakemon's ETL network. Before deploying, check `docker network inspect` on docker2 and widen it if a suite network sits outside that range.) The `umami-db` command renders the template with `sed` into `/tmp/pg_hba.conf`, then `exec docker-entrypoint.sh postgres -c hba_file=/tmp/pg_hba.conf`. Escape `$` for compose as `$$`. Use the default `127.0.0.1/32` when `WIKANTIK_RO_CIDR` is unset.
- [ ] **Step 4: Add smoke checks** (both modes):
  - `psql "host=$BIND port=$UMAMI_DB_PORT user=$UMAMI_DB_USER"` from the host fails with `pg_hba.conf rejects` (Review Focus 1). In `--fresh` mode, set `WIKANTIK_RO_CIDR` to the address the host's connection arrives from, i.e. the Docker gateway for a host-local connection, as `inet_client_addr()` reports it.
  - Task 2 adds the `wikantik_ro` login check.
- [ ] **Step 5: Run `tests/run.sh` and `tests/smoke.sh --fresh`.** Expected: PASS.
- [ ] **Step 6: Commit** (only once Jake has approved commits in this repo): `git add tests/compose_invariants.py docker-compose.yml config/umami-pg_hba.conf.tmpl .env.example tests/smoke.sh`, message `feat: publish umami-db on the LAN behind a templated pg_hba`.

### Task 2: `wikantik_ro` role, secret, and the engagement, vitals and traffic grants

**Files:**
- Create: `sql/product-ro-role.sql` (modelled on `sql/etl-role.sql`, parameterised by `:role`)
- Create: `sql/analytics-engagement.sql`, `sql/analytics-vitals.sql`
- Create: `scripts/product-ro-access.sh` (modelled on `scripts/etl-access.sh`)
- Modify: `scripts/umami-provision.sh` (apply the two views after `umami-views.sql`, then call `product-ro-access.sh`)
- Modify: `docker-compose.yml` (secret `wikantik_ro` ← `./secrets/wikantik-ro`, held by `umami-provision` only)
- Modify: `tests/compose_invariants.py` (only `umami-provision` holds `wikantik_ro`)
- Modify: `bin/deploy.sh` (generate `secrets/wikantik-ro` if missing, as for `jakemon-etl`)
- Modify: `tests/smoke.sh`

**Interfaces:**
- Consumes: Task 1's port and pg_hba.
- Produces, for Wikantik's plan:
  - login role `wikantik_ro`: `LOGIN NOSUPERUSER NOINHERIT CONNECTION LIMIT 4`, `default_transaction_read_only=on`, `statement_timeout=60s`, `idle_in_transaction_session_timeout=5min`.
  - view `analytics_engagement(day date, website_id uuid, canonical_id text, exits bigint, active_median_s numeric, active_p90_s numeric, exits_scroll75 bigint, code_copies bigint, wikilink_clicks bigint, outbound_clicks bigint, diagram_interacts bigint, toc_clicks bigint, sign_in_clicks bigint)`.
  - view `analytics_page_vitals(day date, website_id uuid, url_path text, samples bigint, lcp_p75 numeric, inp_p75 numeric, cls_p75 numeric)`.
  - `SELECT` on the existing `analytics_traffic(website_id, path, entry_anchor, day, sessions, views)`.

- [ ] **Step 1: Write the failing checks.**
  - Invariant: `wikantik_ro` secret is held only by `umami-provision`.
  - Smoke, `--fresh` block:
    - send a `page-exit` beacon whose `data` is `{"canonical_id":"01TEST","active_s":40,"max_scroll_pct":80}`, a second one with `active_s` 20 / scroll 10, and a third with no `active_s`;
    - then as `wikantik_ro` over the published port: `SELECT exits, active_median_s, exits_scroll75 FROM analytics_engagement WHERE canonical_id='01TEST'` returns `3 | 30 | 1` (Review Focus 5);
    - `SELECT 1 FROM website_event LIMIT 1` fails with `permission denied`;
    - `SELECT count(*) FROM analytics_traffic` succeeds.
- [ ] **Step 2: Run `tests/run.sh` and `tests/smoke.sh --fresh`.** Expected: FAIL.
- [ ] **Step 3: Implement.**
  - Engagement view: join `website_event` (`event_type = 2`, `event_name` in the vocabulary) to `event_data` on `website_event_id`.
    - `canonical_id` comes from the `string_value` where `data_key='canonical_id'`.
    - The percentiles use `percentile_cont(0.5|0.9) WITHIN GROUP (ORDER BY number_value)` over rows with `data_key='active_s'`.
    - `exits_scroll75` counts `page-exit` events whose `max_scroll_pct` `number_value >= 75`.
    - `day = (created_at AT TIME ZONE 'UTC')::date`.
  - Vitals view: `website_event` rows with `lcp IS NOT NULL OR inp IS NOT NULL OR cls IS NOT NULL`, `percentile_cont(0.75)` per (day, website, `url_path`).
    - Before writing it, check which `event_type` the pinned tracker's `data-performance` send produces, by sending one from a real browser against the fresh stack. Record the finding in the SQL header.
  - Role and grants: `GRANT CONNECT ON DATABASE`, `GRANT USAGE ON SCHEMA public`, and `GRANT SELECT` on exactly the four views.
- [ ] **Step 4: Run `tests/run.sh` and `tests/smoke.sh --fresh`.** Expected: PASS.
- [ ] **Step 5: Commit**: `feat: wikantik_ro read-only role with engagement, vitals and traffic views`.

### Task 3: `acquisition_domain` and `analytics_acquisition`

**Files:**
- Create: `sql/acquisition.sql` (table, seed and view)
- Modify: `scripts/umami-provision.sh` (apply it; grant `SELECT` on the view to `wikantik_ro` via `product-ro-access.sh`)
- Modify: `tests/smoke.sh`

**Interfaces:**
- Produces:
  - table `acquisition_domain(domain text PRIMARY KEY, channel text NOT NULL CHECK (channel IN ('organic-search','ai-assistant','social')), source text NOT NULL)`;
  - view `analytics_acquisition(day date, website_id uuid, url_path text, channel text, source text, landings bigint, continued bigint, returning bigint)`.
  - Engine `source` keys must equal Content Intelligence's `search_visibility_snapshot.engine` values (`google`, `bing`; confirm the full set in jspwiki `bin/db/migrations/V050*` and the jakemon shipper).

- [ ] **Step 1: Write the failing smoke checks** (`--fresh`). Send landing pageviews as separate visits, then assert each row's `(channel, source)` as `wikantik_ro`:
  - `referrer=https://www.bing.com/` → `organic-search, bing`
  - `referrer=https://chatgpt.com/` → `ai-assistant, chatgpt`
  - `url=/wiki/X?fbclid=abc`, no referrer → `social, facebook` (Review Focus 2)
  - `url=/wiki/X?gclid=abc` → `paid-search, google`
  - `url=/wiki/X?utm_medium=email&utm_source=newsletter` with `referrer=https://google.com/` → `email, newsletter` (Review Focus 3)
  - `referrer=https://news.example.org/a` → `referral, news.example.org` (Review Focus 4)
  - no referrer, no parameters → `direct, (none)`
  - one visit with two pageviews → `continued = 1`
  - a second visit in the same session → `returning = 1`
- [ ] **Step 2: Run `tests/smoke.sh --fresh`.** Expected: FAIL.
- [ ] **Step 3: Implement.**
  - Seed list: the search engines (google, bing, duckduckgo, yahoo, yandex, baidu, ecosia, brave, startpage, qwant), the AI assistants (chatgpt.com, chat.openai.com, perplexity.ai, claude.ai, gemini.google.com, copilot.microsoft.com, you.com, phind.com) and the social sites (facebook.com, fb.com, l.facebook.com, t.co, x.com, twitter.com, linkedin.com, lnkd.in, reddit.com, news.ycombinator.com, mastodon.social, bsky.app, youtube.com, instagram.com, threads.net, pinterest.com).
  - The view takes the first `event_type=1` row per `visit_id` (`DISTINCT ON (visit_id) … ORDER BY visit_id, created_at`) and classifies it with the spec's ten ordered rules.
    - Domain matching is `referrer_domain = domain OR referrer_domain LIKE '%.' || domain`. Choose the longest match.
    - A self-referral, where `referrer_domain` is the event's own `hostname`, counts as direct.
    - `continued` means the visit has ≥ 2 pageviews.
    - `returning` means the session has an event earlier than this visit's first event.
  - Check `EXPLAIN` with a `WHERE day >= current_date - 3` predicate. If it doesn't restrict the `website_event` scan, add a `created_at` lower bound inside the view (for example a rolling 400-day window) and say so in the SQL header.
- [ ] **Step 4: Run `tests/run.sh` and `tests/smoke.sh --fresh`.** Expected: PASS.
- [ ] **Step 5: Commit**: `feat: acquisition_domain table and analytics_acquisition channel view`.

### Task 4: Rule change, docs and the `wikantik` account

**Files:**
- Modify: `CLAUDE.md` (the "a product never reaches a suite database" rule becomes the amended wording in the spec's "Settled" section)
- Modify: `README.md`, `docs/onboarding-a-product.md` (the read-only login, the port, `WIKANTIK_RO_CIDR`, the views and their columns, how to add a domain row)
- Modify: `bin/deploy.sh` (on first deploy, also seed a `wikantik:` line in `secrets/umami-accounts` if absent, generated like `pressroom`)

- [ ] **Step 1: Make the edits.** `tests/run.sh` must pass (shellcheck on `deploy.sh`).
- [ ] **Step 2: Commit**: `docs: product read-only views contract; seed the wikantik account`.
- [ ] **Step 3: Hand Jake the deploy.** `bin/deploy.sh docker2` changes a running service. Give him the exact command, plus the line to add to docker2's `.env`: `WIKANTIK_RO_CIDR=192.168.5.51/32`. Then verify:
  - the host smoke passes;
  - from docker1, `psql "host=192.168.5.52 port=8087 user=wikantik_ro dbname=umami"` can `SELECT` from `analytics_acquisition`;
  - from this workstation, the same login and a superuser login are both rejected.
- [ ] **Step 4: Update the `SimpleAgilityBackOfficeSuite` wiki page** through the admin MCP. Change the client-contract section and the status table (step 3: "in progress"). It's a prod content edit, so follow the `wiki-content` skill.
