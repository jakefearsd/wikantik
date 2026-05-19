# Wikantik load-test harness

A k6 harness that drives the **instrumented** Wikantik endpoints. k6 remote-writes
its own metrics into jakemon's central Prometheus (`192.168.0.10:9090`) so offered
load and host response share a timeline in Grafana.

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
  CryptoUtil's `{SHA-256}` SSHA scheme and inserted `ON CONFLICT DO NOTHING`.
- An API key with scope `all` (covers both `/wikantik-admin-mcp` and `/tools/*`),
  derived from `test.api.key` in `test.properties`. The DB stores only the
  SHA-256 hex hash of the plaintext token.

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

## Tests

The Prometheus-text parser is unit-tested:

    node --test loadtest/metrics-parse.test.mjs
