# Wikantik load-test harness

A k6 harness that drives the **instrumented** Wikantik endpoints. k6 remote-writes
its own metrics into jakemon's central Prometheus on host `docker2`
(`192.168.0.5:9090`) so offered load and host response share a timeline in
Grafana.

> **This document is the tactical reference** — install, configure, run.
> For the methodology — when to run a load test, how to isolate variables,
> how to pair k6 with JFR to find contention, common patterns and pitfalls —
> see [`docs/developer/LoadTesting.md`](../docs/developer/LoadTesting.md).

## Prerequisites

- [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/)
- `loadtest/loadtest.env` — copy from `loadtest.env.example` and fill in.

## Usage

    bin/loadtest.sh smoke              # ~2 min, hits every endpoint
    bin/loadtest.sh smoke --verify     # + assert each dashboard panel moved
    bin/loadtest.sh smoke --writes     # + authenticated edit/delete/login
    bin/loadtest.sh load               # sustained ramping load
    bin/loadtest.sh stress             # ramp past capacity
    bin/loadtest.sh smoke --dry-run    # print the k6 command only

## `--verify`

Scrapes `/metrics` before and after the run and fails (non-zero exit) if any
target panel's metric did not move. `/metrics` is firewalled to internal IPs,
so `--verify` only works from inside the network. To verify a remote target,
run the harness on that host, or pass `--metrics-url http://localhost:8080/metrics`.

## Seeding test data

A fresh Wikantik stack has only the bootstrap `admin` user and no API keys,
so `--writes` logins and MCP/tools calls fail with 401/403, breaking the run.
Run this once after standing up a new stack:

    loadtest/seed-loadtest-data.sh --docker-project jspwiki

This provisions:
- A `testbot` admin user (password from `test.properties`), hashed with
  CryptoUtil's `{SHA-256}` SSHA scheme and upserted (`ON CONFLICT (login_name)
  DO UPDATE` — re-running the script refreshes the password rather than
  no-op'ing).
- An API key with scope `all` (covers both `/wikantik-admin-mcp` and `/tools/*`),
  derived from `test.api.key` in `test.properties`, inserted `ON CONFLICT DO
  NOTHING`. The DB stores only the SHA-256 hex hash of the plaintext token.

At the end the script prints the exact `loadtest/loadtest.env` values to use.
Copy those into `loadtest/loadtest.env` (gitignored, copied from `.env.example`).

If the DB is published to a host port (e.g. 15432), use direct mode instead:

    loadtest/seed-loadtest-data.sh --db-port 15432

Run `loadtest/seed-loadtest-data.sh --help` for all options.

**Verification smoke-check** after seeding:

    curl -s -o /dev/null -w '%{http_code}' \
      -H "Authorization: Bearer $(grep test.api.key test.properties | cut -d= -f2)" \
      http://localhost:18080/wikantik-admin-mcp
    # Should print 400 (key accepted, request body missing) — NOT 401/403.

    curl -s -o /dev/null -w '%{http_code}' \
      -u "testbot:$(grep test.user.password test.properties | cut -d= -f2)" \
      http://localhost:18080/api/pages/Main
    # Should print 200.

## Collaborative editing: `collab-load.js`

A standalone k6 scenario for the real-time editing surface (`/api/collab/*`, MultiEdit design section 13.4). It
drives N simulated editors (default 15) over M pages (default 5): each joins its page's shared draft, pushes a small
insertion at typing cadence (about one per second), resyncs on a 409 stale-base, and publishes about every 50th push;
one listener per page long-polls like an idle viewer. Run it against a **local** deployment with collaboration enabled
(`wikantik.collab.enabled=true`), never prod: it creates and deletes pages, drafts and page versions.

    k6 run -e BASE_URL=http://localhost:8080 \
           -e LOADTEST_ADMIN_USER=testbot -e LOADTEST_ADMIN_PASS="$(grep test.user.password test.properties | cut -d= -f2)" \
           loadtest/collab-load.js

Knobs: `EDITORS`, `PAGES`, `LISTENERS_PER_PAGE`, `DURATION` (default 2m), `PUBLISH_EVERY` (0 = never publish). Every VU
shares the one login, and one account holds at most 4 seats in a session (`CollabSessionRegistry.MAX_SEATS_PER_AUTHOR`),
so keep `EDITORS / PAGES + LISTENERS_PER_PAGE` at 4 or under or the extra joins answer 429 `session-full`.
It reports `collab_push_latency` (threshold p95 < 150 ms; measured by the client, so it includes the local round trip),
`collab_push_409_rate`, `collab_push_429_rate`, `collab_publish_latency` and `collab_pull_latency`. For the
server-side figure read `wikantik_collab_push_latency_seconds` from `/metrics` before and after the run.

An editor VU is single-threaded: it pushes, and only pulls after a 409, where a real editor keeps a long-poll open and
rebases locally. So `collab_push_409_rate` (and the server's `rebase` share) overstate real contention; read push
latency and the request rate, not the conflict rate.

Recorded result (2026-11-07, MultiEdit Phase 3 close-out; local Tomcat 11, single node, local PostgreSQL 18, 16 cores,
defaults: 15 editors and 5 listeners over 5 pages, 2 minutes): 0 failed and 0 throttled pushes out of 1,819; client push
latency p95 6.3 ms (median 0.7 ms); publish p95 44 ms; 25.5 requests/s in total, about 1.3 requests/s per editor at
typing cadence (a real editor adds its long-poll, so budget about 2 requests/s per active editor when sizing the per-IP
`WIKANTIK_RATELIMIT_SESSION_PERCLIENT` bucket: the default 100/s covers about 50 active editors behind one address).

## Tests

The k6-free helpers are unit-tested (CI runs these in the `shell-tests` job): the Prometheus-text
parser, and `lib/mcp-result.js` — the MCP-reply check (`mcpToolOk`, which also fails a batch
write/delete that refused a page) and the math write bodies, which must keep display math isolated
or the server refuses every write:

    node --test loadtest/*.test.mjs
