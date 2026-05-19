# Retire the Observability Overlay — Design

**Date:** 2026-05-19
**Status:** Approved — ready for implementation plan
**Scope:** jspwiki repo only (jakemon-repo follow-ups explicitly deferred)

## Background

jspwiki ships an opt-in observability overlay — `docker-compose.observability.yml`
— that runs its own Prometheus, Grafana, cAdvisor, node-exporter, and
postgres-exporter alongside the wikantik container. The new `jakemon` project
(`/home/jakefear/source/jakemon`) is a centralized, push-based observability
stack: a Grafana Alloy agent on each host ships metrics and logs to a central
Prometheus + Loki + Grafana + Alertmanager on host `inference`.

jakemon's docker1 agent already covers everything the overlay does:

- Host metrics — `job="integrations/unix"`
- Container metrics — `job="integrations/cadvisor"`
- The wikantik app — `job="wikantik"`, scraped at `host.docker.internal:8080`
  (`up=1`, verified)
- Container logs — shipped to Loki
- PostgreSQL — `job="integrations/postgres"` exporter wired with the real
  `wikantik_exporter` DSN, but `pg_up=0`: the wikantik DB sits only on the
  wikantik compose network, unreachable from the agent container.

This is jakemon BRINGUP-STATUS follow-up 8. Retiring the overlay eliminates the
duplication. jspwiki keeps only its own `/metrics` endpoint (the
`wikantik-observability` module), which jakemon scrapes.

## Goals

1. Delete the overlay and its supporting config from the jspwiki repo.
2. Publish the wikantik DB on a host port reachable from the jakemon agent
   container via `host.docker.internal:5432`, bound to the Docker bridge
   gateway so it stays off the LAN.
3. Tear down the overlay's running containers on docker1 — plus the two
   standalone `cadvisor`/`node-exporter` containers — and confirm jakemon's
   `pg_up` flips to `1`.

## Non-goals

- jakemon-repo work stays as jakemon's follow-up 8: porting the overlay's
  custom `queries.yaml` (`wikantik_pg_table_io`) into Alloy's
  `prometheus.exporter.postgres` custom-queries support, and
  relabeling/rescoping the wikantik Grafana dashboards for `integrations/*`
  job labels. Not touched here.
- No jakemon redeploy. Once the DB is reachable, jakemon's already-deployed
  docker1 agent picks up `pg_up=1` on its next scrape with no config change.
- The `wikantik-observability` module and the app's `/metrics` endpoint stay.

## Design

### Part 1 — Repo changes (jspwiki)

**Delete:**

- `docker-compose.observability.yml`
- `docker/grafana/` — provisioning (`datasources/`, `dashboards/`) and the two
  dashboard JSONs (`wikantik-overview.json`, `wikantik-host.json`)
- `docker/prometheus/` — `prometheus.yml`
- `docker/postgres-exporter/` — `queries.yaml`

The deleted dashboards and `queries.yaml` remain in git history; jakemon's
follow-up 8 sources them from there.

**Edit:**

- `bin/container.sh` — remove the `WIKANTIK_OBSERVABILITY` block that appends
  `-f docker-compose.observability.yml` to `COMPOSE_FILES` (around lines
  241–244).
- `bin/remote.sh` — drop `docker-compose.observability.yml` from the two
  compose-file lists (around lines 328 and 441).
- `docker-compose.prod.yml` — change the `db` service `ports:` from
  `!reset []` to:
  ```yaml
  ports:
    - "${DB_HOST_BIND:-172.17.0.1}:5432:5432"
  ```
  `DB_HOST_BIND` defaults to the standard docker0 bridge gateway and is
  env-overridable so the teardown step can correct it if docker1's gateway
  differs.
- `.env.example` and the gitignored `.env.prod` on the remote — remove the
  now-dead `GRAFANA_ADMIN_PASSWORD`, `GRAFANA_HOST_PORT`, and
  `WIKANTIK_OBSERVABILITY`; add `DB_HOST_BIND` with a documented default and a
  comment explaining the bridge-gateway bind. **Keep `DB_EXPORTER_PASSWORD`** —
  migration V031 still threads it to give the `wikantik_exporter` role `LOGIN`,
  and jakemon's `PG_EXPORTER_DSN_docker1` must use the same value.
- `bin/loadtest.sh` and `loadtest/loadtest.env.example` — repoint the
  `--verify` `/metrics` scrape and the k6 `experimental-prometheus-rw`
  remote-write target from the overlay's local Prometheus to a configurable
  `JAKEMON_PROM_URL`, default `http://192.168.0.10:9090` (jakemon's central
  Prometheus, which already runs with `--web.enable-remote-write-receiver`).
- Docs — strike the overlay and state that monitoring is jakemon's
  responsibility: `CLAUDE.md` (the observability-overlay paragraph under
  "Container deployment" and the load-testing note), `README.md`,
  `docs/DockerDeployment.md` §3, `docs/ObservabilityDesign.md`,
  `loadtest/README.md`.

**Keep untouched:**

- `bin/db/migrations/V031__monitoring_role.sql` and the `wikantik_exporter`
  PostgreSQL role — jakemon's agent connects as this role.
- The `wikantik-observability` module and the `/metrics` endpoint.

### Part 2 — docker1 teardown (over ssh)

Executed after the repo changes land:

1. **Inventory.** `docker ps -a` on docker1 to identify the overlay's
   `prometheus`, `grafana`, `cadvisor`, `node-exporter`, and
   `postgres-exporter` containers, and the two standalone `cadvisor` /
   `node-exporter` containers. Confirm the full set before removing anything;
   surface anything unexpected rather than deleting blind.
2. **Remove.** Stop and remove all of the above, plus the overlay's
   `prometheus-data` and `grafana-data` named volumes.
3. **Verify the gateway.** `ip addr show docker0` on docker1 to read the
   actual bridge gateway IP. If it is not `172.17.0.1`, set `DB_HOST_BIND` in
   `.env.prod` accordingly before redeploying.
4. **Redeploy.** Rebuild and redeploy wikantik with the updated prod compose
   (via `bin/remote.sh deploy` or the equivalent) so the DB publishes on
   `<gateway>:5432`.
5. **Confirm.** Verify Postgres is reachable on the gateway address, and that
   jakemon's `pg_up` for docker1 reads `1` (query jakemon's central Prometheus
   at `192.168.0.10:9090`). No jakemon redeploy.

## Risks and mitigations

- **`host-gateway` may not equal `172.17.0.1` on docker1.** Docker resolves the
  `host.docker.internal` → `host-gateway` mapping to the default bridge
  gateway, normally `172.17.0.1`, but this is configurable. Teardown step 3
  reads the real value and corrects `DB_HOST_BIND` before the DB bind is
  committed.
- **An unseen scrape config may depend on the standalone exporters.** The
  inventory step (1) lists every container and its origin before deletion, so
  an unexpected dependency surfaces before anything is removed.
- **Exporter password drift.** `DB_EXPORTER_PASSWORD` (jspwiki `.env.prod`,
  consumed by V031) and jakemon's `PG_EXPORTER_DSN_docker1` password must
  match. The teardown notes this; if `pg_up` stays `0` after step 5 with a
  reachable DB, a password mismatch is the first suspect.

## Verification

- jspwiki: `mvn clean install -T 1C -DskipITs` builds clean after the deletes
  and edits (no compose file is referenced by Java, but the build is the
  standard gate); `bin/container.sh --help` and `bin/remote.sh --help` still
  run; `docker compose -f docker-compose.yml -f docker-compose.prod.yml config`
  validates.
- docker1: overlay + standalone exporter containers gone (`docker ps`);
  `psql -h <gateway> -U wikantik` connects; jakemon `pg_up{host="docker1"} == 1`.
