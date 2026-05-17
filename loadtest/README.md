# Wikantik load-test harness

A k6 harness that drives the **instrumented** Wikantik endpoints, so the
Grafana dashboards (and the host/infra dashboard) show real activity.

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

## Tests

The Prometheus-text parser is unit-tested:

    node --test loadtest/metrics-parse.test.mjs
