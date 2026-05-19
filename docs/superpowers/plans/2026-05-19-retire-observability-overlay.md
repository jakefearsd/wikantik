# Retire the Observability Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove jspwiki's self-hosted observability overlay and publish the wikantik DB on a host port so the jakemon monitoring agent takes over fully.

**Architecture:** Delete `docker-compose.observability.yml` and its supporting config; strip the overlay wiring from `bin/container.sh` and `bin/remote.sh`; publish the prod DB on the docker0 bridge gateway via a new `DB_HOST_BIND` env var; repoint loadtest's k6 remote-write at jakemon's central Prometheus; update docs. Then tear down the running overlay containers on docker1 over ssh.

**Tech Stack:** Docker Compose, Bash, k6, PostgreSQL. No application code changes — these files are not referenced by the Maven build.

---

## Spec

Design spec: `docs/superpowers/specs/2026-05-19-retire-observability-overlay-design.md`

## Files

- Delete: `docker-compose.observability.yml`, `docker/grafana/` (tree), `docker/prometheus/` (tree), `docker/postgres-exporter/queries.yaml`
- Modify: `bin/container.sh`, `bin/remote.sh`, `docker-compose.prod.yml`, `.env.example`, `loadtest/loadtest.env.example`, `loadtest/README.md`, `CLAUDE.md`, `README.md`, `docs/DockerDeployment.md`, `docs/ObservabilityDesign.md`, `docs/wikantik-pages/News.md`
- Untouched (intentionally): `bin/db/migrations/V031__monitoring_role.sql`, the `wikantik-observability` module, `bin/loadtest.sh` (fully env-driven — no overlay reference in the script itself)

---

### Task 1: Remove the overlay from the repo

**Files:**
- Delete: `docker-compose.observability.yml`
- Delete: `docker/grafana/` (provisioning + dashboards), `docker/prometheus/`, `docker/postgres-exporter/queries.yaml`
- Modify: `bin/container.sh:241-245`
- Modify: `bin/remote.sh:328`, `bin/remote.sh:441`

- [ ] **Step 1: Delete the overlay files**

```bash
git rm docker-compose.observability.yml
git rm -r docker/grafana docker/prometheus
git rm docker/postgres-exporter/queries.yaml
```

If `docker/postgres-exporter/` is now empty, `git rm` of the only file already removes it. Confirm with `ls docker/postgres-exporter 2>/dev/null` — expect no such directory.

- [ ] **Step 2: Strip the overlay block from `bin/container.sh`**

Remove these five lines (currently at `bin/container.sh:241-245`):

```bash
# Opt-in observability overlay (Prometheus + Grafana). Enable per-invocation
# with WIKANTIK_OBSERVABILITY=1. Not applicable to the standalone test env.
if [[ "${WIKANTIK_OBSERVABILITY:-}" == "1" && "${ENV}" != "test" ]]; then
    COMPOSE_FILES+=(-f docker-compose.observability.yml)
fi
```

Also remove the now-orphaned blank line so `esac` is followed by one blank line then the `# ---------- Subcommand dispatch` comment.

- [ ] **Step 3: Remove the overlay from `bin/remote.sh` rsync lists**

At `bin/remote.sh:328`, change:

```bash
        docker-compose.yml docker-compose.prod.yml docker-compose.observability.yml \
```

to:

```bash
        docker-compose.yml docker-compose.prod.yml \
```

At `bin/remote.sh:441`, change:

```bash
    local files=(docker-compose.yml docker-compose.prod.yml docker-compose.observability.yml)
```

to:

```bash
    local files=(docker-compose.yml docker-compose.prod.yml)
```

- [ ] **Step 4: Verify nothing else references the overlay**

Run: `grep -rn "observability\|WIKANTIK_OBSERVABILITY" bin/ docker-compose*.yml`
Expected: no matches (docs are handled in Task 4).

- [ ] **Step 5: Verify the scripts still parse**

Run: `bash -n bin/container.sh && bash -n bin/remote.sh && bin/container.sh --help >/dev/null && echo OK`
Expected: `OK`

- [ ] **Step 6: Commit**

```bash
git add bin/container.sh bin/remote.sh
git commit -m "chore: remove the self-hosted observability overlay

Monitoring moves to the jakemon central stack. Drops the overlay
compose file, its Grafana/Prometheus/postgres-exporter config, and
the WIKANTIK_OBSERVABILITY wiring in container.sh / remote.sh.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

(The `git rm` deletions from Step 1 are already staged; `git add` of the modified scripts completes the changeset.)

---

### Task 2: Publish the prod DB on the bridge gateway

**Files:**
- Modify: `docker-compose.prod.yml` (the `db` service `ports:` key)
- Modify: `.env.example:40-43`

- [ ] **Step 1: Publish the DB port in `docker-compose.prod.yml`**

In the `db:` service, change:

```yaml
  db:
    restart: always
    ports: !reset []
```

to:

```yaml
  db:
    restart: always
    # Published so the jakemon monitoring agent's Postgres exporter can
    # reach the DB via host.docker.internal:5432. DB_HOST_BIND defaults to
    # the docker0 bridge gateway, keeping the DB off the LAN.
    ports:
      - "${DB_HOST_BIND:-172.17.0.1}:5432:5432"
```

- [ ] **Step 2: Replace the dead Grafana vars in `.env.example`**

Replace lines 40-43 (the `# Observability overlay …` comment and the `GRAFANA_ADMIN_PASSWORD` / `GRAFANA_HOST_PORT` lines):

```
# Observability overlay (docker-compose.observability.yml) — only consumed
# when the stack is brought up with WIKANTIK_OBSERVABILITY=1.
GRAFANA_ADMIN_PASSWORD=CHANGE_ME
GRAFANA_HOST_PORT=3000
```

with:

```
# Container DB host-port publishing. docker-compose.prod.yml publishes
# PostgreSQL on this address so the jakemon monitoring agent can reach it
# via host.docker.internal:5432. Defaults to the docker0 bridge gateway,
# which keeps the DB off the LAN. Set to 0.0.0.0 only to expose it
# LAN-wide. DB_EXPORTER_PASSWORD (below) stays — migration V031 uses it to
# give the wikantik_exporter role LOGIN for that agent.
DB_HOST_BIND=172.17.0.1
```

Note: `DB_EXPORTER_PASSWORD` is not currently in `.env.example`; if a later grep shows it absent, leave it absent (it lives only in the gitignored `.env.prod`). The comment references it for the operator's benefit.

- [ ] **Step 3: Verify the prod compose still resolves**

Run: `DB_HOST_BIND=172.17.0.1 docker compose -f docker-compose.yml -f docker-compose.prod.yml config >/dev/null && echo OK`
Expected: `OK`, and the rendered `db` service shows `published: "5432"` bound to `host_ip: 172.17.0.1`.

- [ ] **Step 4: Commit**

```bash
git add docker-compose.prod.yml .env.example
git commit -m "feat: publish the prod DB on the docker0 bridge gateway

DB_HOST_BIND (default 172.17.0.1) lets the jakemon monitoring agent
reach PostgreSQL via host.docker.internal:5432 without exposing it
to the LAN.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 3: Repoint loadtest at jakemon's central Prometheus

**Files:**
- Modify: `loadtest/loadtest.env.example:27-33`

`bin/loadtest.sh` itself needs no change — it reads `K6_PROMETHEUS_RW_SERVER_URL` and `GRAFANA_URL`/`GRAFANA_TOKEN` purely from the sourced env file. Only the example defaults and comments change.

- [ ] **Step 1: Update the Prometheus / Grafana endpoints in `loadtest.env.example`**

Replace lines 27-33:

```
# Grafana annotation posting (optional — Phase C). Token needs the Annotation
# writer role.
GRAFANA_URL=http://localhost:3000
GRAFANA_TOKEN=

# Prometheus remote-write endpoint for k6's own metrics (optional — Phase C).
K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write
```

with:

```
# Grafana annotation posting (optional). Points at the jakemon central
# Grafana on host `inference`. Token needs the Annotation writer role.
GRAFANA_URL=http://192.168.0.10:3000
GRAFANA_TOKEN=

# Prometheus remote-write endpoint for k6's own metrics (optional). Points
# at jakemon's central Prometheus, which runs with the remote-write
# receiver enabled. Run loadtest from a host on the LAN to reach it.
K6_PROMETHEUS_RW_SERVER_URL=http://192.168.0.10:9090/api/v1/write
```

- [ ] **Step 2: Confirm no stale local-Prometheus default remains**

Run: `grep -n "localhost:9090\|localhost:3000" loadtest/loadtest.env.example`
Expected: no matches.

- [ ] **Step 3: Commit**

```bash
git add loadtest/loadtest.env.example
git commit -m "chore: point loadtest k6 metrics at jakemon's central Prometheus

The overlay's local Prometheus/Grafana are gone; repoint the k6
remote-write and Grafana annotation endpoints at the jakemon central
stack on host inference (192.168.0.10).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 4: Update documentation

**Files:**
- Modify: `CLAUDE.md` (the observability-overlay paragraph + the load-testing `--verify` note under "Container deployment" / "Load testing")
- Modify: `README.md`, `docs/DockerDeployment.md`, `docs/ObservabilityDesign.md`, `loadtest/README.md`

- [ ] **Step 1: Find every doc reference**

Run: `grep -rn "observability overlay\|docker-compose.observability\|WIKANTIK_OBSERVABILITY\|GRAFANA" CLAUDE.md README.md docs/DockerDeployment.md docs/ObservabilityDesign.md loadtest/README.md`

Record every hit — each must be resolved in Step 2.

- [ ] **Step 2: Rewrite each reference**

For every hit, apply this rule:

- A paragraph describing the **overlay** (how to bring it up, `WIKANTIK_OBSERVABILITY=1`, the auto-provisioned Grafana dashboards): replace it with one sentence —
  > Monitoring is handled by the external **jakemon** stack (a Grafana Alloy agent on each host pushing to a central Prometheus + Loki + Grafana). The wikantik container exposes `/metrics`; jakemon scrapes it. There is no in-repo observability stack.
- A reference to the overlay's **Prometheus for k6** (`loadtest/README.md`, `docs/DockerDeployment.md §3`): point it at jakemon's central Prometheus (`192.168.0.10:9090`), matching `loadtest/loadtest.env.example`.
- The CLAUDE.md **"Container deployment"** observability paragraph and the **"Load testing"** `--verify`/overlay sentence: trim to the one-sentence jakemon pointer above; keep the `--verify`/`/metrics` description (the app `/metrics` endpoint is unchanged), drop only the overlay-bring-up wording.
- `docs/ObservabilityDesign.md`: add a note at the top — `> Superseded 2026-05-19: the self-hosted overlay was retired; see docs/superpowers/specs/2026-05-19-retire-observability-overlay-design.md. This document is kept for historical context.` — and leave the body intact.

Do not touch `audits/`, `docs/superpowers/plans/`, `docs/superpowers/specs/` (other than the new spec), or `docs/wikantik-pages/*` topic pages — those are historical or unrelated content.

- [ ] **Step 3: Verify no live doc still tells the reader to use the overlay**

Run: `grep -rn "WIKANTIK_OBSERVABILITY\|docker-compose.observability" CLAUDE.md README.md docs/DockerDeployment.md loadtest/README.md`
Expected: no matches.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md README.md docs/DockerDeployment.md docs/ObservabilityDesign.md loadtest/README.md
git commit -m "docs: monitoring is now jakemon's; drop overlay instructions

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 5: Full verification

**Files:** none — verification only.

- [ ] **Step 1: Validate both deployable compose stacks render**

Run:
```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml config >/dev/null && echo "dev OK"
DB_HOST_BIND=172.17.0.1 docker compose -f docker-compose.yml -f docker-compose.prod.yml config >/dev/null && echo "prod OK"
```
Expected: `dev OK` then `prod OK`.

- [ ] **Step 2: Confirm the scripts run**

Run: `bin/container.sh --help >/dev/null && bin/remote.sh --help >/dev/null && bin/loadtest.sh --help >/dev/null && echo OK`
Expected: `OK`

- [ ] **Step 3: Confirm the retained pieces are still present**

Run: `test -f bin/db/migrations/V031__monitoring_role.sql && ls wikantik-observability/pom.xml && echo "retained OK"`
Expected: `retained OK` — the monitoring role migration and the `wikantik-observability` module are deliberately kept.

- [ ] **Step 4: Confirm the working tree is clean**

Run: `git status --short`
Expected: empty — Tasks 1-4 are all committed.

---

### Task 6: Log the work in News.md

**Files:**
- Modify: `docs/wikantik-pages/News.md`

Per the project convention, each prod commit subject is logged in News.md as its own content commit (the News commit never self-logs).

- [ ] **Step 1: Add News.md entries**

Read the top of `docs/wikantik-pages/News.md` to match the existing entry format, then add entries for the four commits from Tasks 1-4:
- `chore: remove the self-hosted observability overlay`
- `feat: publish the prod DB on the docker0 bridge gateway`
- `chore: point loadtest k6 metrics at jakemon's central Prometheus`
- `docs: monitoring is now jakemon's; drop overlay instructions`

Frame them for a reader: monitoring has moved to the external jakemon stack; the in-repo Prometheus/Grafana overlay is gone; the production database is now published to the Docker bridge gateway for the monitoring agent.

- [ ] **Step 2: Commit**

```bash
git add docs/wikantik-pages/News.md
git commit -m "content: log the observability-overlay retirement in News

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 7: Tear down the overlay on docker1 (ssh ops)

**Files:** none — this runs against the live docker1 host. Requires `remote.env` configured and ssh access to docker1.

This task changes a production host. Each destructive step is preceded by an inventory/confirmation step — stop and surface anything unexpected rather than proceeding.

- [ ] **Step 1: Inventory docker1's running containers**

Run: `bin/remote.sh status` (or `ssh <docker1> 'docker ps -a --format "table {{.Names}}\t{{.Image}}\t{{.Status}}"'`)

Identify: the overlay's `prometheus`, `grafana`, `cadvisor`, `node-exporter`, `postgres-exporter` containers, **and** the two standalone `cadvisor` / `node-exporter` containers named in the spec. Record the exact container names. If the set differs from the spec's expectation, stop and report before removing anything.

- [ ] **Step 2: Read the docker0 gateway IP on docker1**

Run: `ssh <docker1> "ip -4 addr show docker0 | awk '/inet /{print \$2}'"`
Expected: a CIDR such as `172.17.0.1/16`. If the address is **not** `172.17.0.1`, the `DB_HOST_BIND` value in the remote `.env.prod` must be set to the actual gateway IP in Step 4.

- [ ] **Step 3: Stop and remove the overlay + standalone exporter containers**

Using the exact names from Step 1:

```bash
ssh <docker1> 'docker rm -f <prometheus> <grafana> <cadvisor> <node-exporter> <postgres-exporter> <standalone-cadvisor> <standalone-node-exporter>'
ssh <docker1> 'docker volume ls --format "{{.Name}}" | grep -E "prometheus-data|grafana-data"'
```

Remove the overlay's data volumes shown by the second command:

```bash
ssh <docker1> 'docker volume rm <prometheus-data-volume> <grafana-data-volume>'
```

- [ ] **Step 4: Set `DB_HOST_BIND` on docker1 if the gateway differs**

If Step 2 showed a gateway other than `172.17.0.1`, set `DB_HOST_BIND` in the remote `.env.prod` to that IP. The deployed `docker-compose.prod.yml` defaults to `172.17.0.1`, so if the gateway matches, no `.env.prod` edit is needed.

- [ ] **Step 5: Redeploy wikantik with the DB published**

Run: `bin/remote.sh deploy`
This builds the image locally, ships compose + `.env`, and runs `up -d`. The new `docker-compose.prod.yml` publishes the DB on `<gateway>:5432`.

- [ ] **Step 6: Verify the DB is reachable on the gateway**

Run: `ssh <docker1> "docker run --rm postgres:18-alpine pg_isready -h <gateway-ip> -p 5432"`
Expected: `<gateway-ip>:5432 - accepting connections`

- [ ] **Step 7: Confirm jakemon picked up the DB**

Query jakemon's central Prometheus (no jakemon redeploy needed):

```bash
ssh inference 'curl -s localhost:9090/api/v1/query --data-urlencode "query=pg_up{host=\"docker1\"}"'
```
Expected: a result with `"value":[<ts>,"1"]`.

If `pg_up` is still `0` with a reachable DB, suspect a password mismatch between jspwiki's `DB_EXPORTER_PASSWORD` (consumed by migration V031) and jakemon's `PG_EXPORTER_DSN_docker1` — reconcile the two.

- [ ] **Step 8: Confirm the overlay containers are gone**

Run: `ssh <docker1> 'docker ps --format "{{.Names}}"'`
Expected: only `repo-wikantik-1` and `repo-db-1` (and the jakemon `alloy` agent) — no `prometheus`, `grafana`, `cadvisor`, `node-exporter`, or `postgres-exporter`.

---

## Self-Review

- **Spec coverage:** Part 1 deletes → Task 1; `bin/container.sh`/`bin/remote.sh` edits → Task 1; `docker-compose.prod.yml` DB port → Task 2; `.env.example` → Task 2; loadtest repoint → Task 3; docs → Task 4; `wikantik_exporter`/V031 + `wikantik-observability` kept → verified in Task 5 Step 3; Part 2 teardown (inventory, remove, gateway verify, redeploy, confirm) → Task 7. News.md logging → Task 6 (project convention). All spec sections mapped.
- **Spec deviation noted:** the spec lists `bin/loadtest.sh` as a file to edit, but the script reads its endpoints entirely from the sourced env file — only `loadtest/loadtest.env.example` needs changing. Task 3 states this explicitly so the executor does not hunt for a non-existent edit.
- **Placeholder scan:** none — `<docker1>`, `<gateway-ip>`, and the container-name placeholders in Task 7 are runtime values discovered by that task's own inventory steps, not unfilled plan content.
- **Consistency:** `DB_HOST_BIND` default `172.17.0.1` is identical across `docker-compose.prod.yml`, `.env.example`, and Task 7; the jakemon Prometheus address `192.168.0.10:9090` matches the spec.
