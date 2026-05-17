# Load-testing capability + host observability — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a repeatable k6 load-testing harness for Wikantik plus a host/container/PostgreSQL observability stack, so a release can be load-tested and the strain on the system and its host observed on one timeline.

**Architecture:** Three phases. **Phase B** adds node_exporter, cAdvisor, and postgres_exporter to the opt-in observability overlay and a new "Wikantik — Host & Infra" Grafana dashboard. **Phase A** adds a k6 project under `loadtest/` plus a `bin/loadtest.sh` wrapper with `smoke`/`load`/`stress` profiles and a `--verify` metrics-delta gate. **Phase C** wires k6 metrics into Prometheus via remote-write, adds a query-embedder latency Timer, and adds Grafana run annotations.

**Tech Stack:** k6 (JS, ES modules), Docker Compose, Prometheus, Grafana, PostgreSQL, Java 21 / Micrometer, Bash, Node.js (`node --test`).

**Spec:** `docs/superpowers/specs/2026-05-17-load-testing-and-observability-design.md`

**Conventions that apply throughout:**
- Sole developer — commit directly to `main`, no feature branches.
- Stage files by exact name in `git add` — never `git add -A`.
- Every commit message ends with the `Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>` trailer.
- After Phase C, each landed commit's subject is logged in `docs/wikantik-pages/News.md` per the project convention (Task 17).

---

## File Structure

**Created:**
- `bin/db/migrations/V031__monitoring_role.sql` — creates the `wikantik_exporter` DB role
- `docker/postgres-exporter/queries.yaml` — postgres_exporter custom queries (per-table IO/scan stats)
- `docker/grafana/dashboards/wikantik-host.json` — the new dashboard
- `loadtest/lib/metrics.js` — pure Prometheus-text parser + verify-report builder
- `loadtest/lib/config.js` — env parsing, profile definitions, verify-target list
- `loadtest/lib/endpoints.js` — per-surface k6 request helpers
- `loadtest/lib/slugs.js` — bundled page-slug list loader
- `loadtest/slugs.sample.txt` — bundled slug list
- `loadtest/wikantik-load.js` — k6 entry point
- `loadtest/metrics-parse.test.mjs` — `node --test` over `metrics.js`
- `loadtest/loadtest.env.example` — credentials template
- `loadtest/README.md` — usage docs
- `bin/loadtest.sh` — wrapper script

**Modified:**
- `bin/db/migrate.sh` — thread `DB_EXPORTER_PASSWORD` into a psql variable
- `docker-compose.observability.yml` — three exporter services; Prometheus remote-write flag + loopback port
- `docker/prometheus/prometheus.yml` — three scrape jobs
- `wikantik-main/src/main/java/com/wikantik/search/hybrid/QueryEmbedder.java` — latency Timer
- `wikantik-main/src/main/java/com/wikantik/search/hybrid/HybridMetricsBridge.java` — register + inject the Timer
- `wikantik-main/src/test/java/com/wikantik/search/hybrid/QueryEmbedderTest.java` — Timer test
- `wikantik-main/src/test/java/com/wikantik/search/hybrid/HybridMetricsBridgeTest.java` — Timer registration test
- `.gitignore` — ignore `loadtest.env`
- `CLAUDE.md`, `docs/DockerDeployment.md` — load-testing + host-dashboard notes
- `docs/wikantik-pages/News.md` — commit-log entries

---

# Phase B — Host & infra observability

## Task 1: PostgreSQL monitoring role migration

**Files:**
- Create: `bin/db/migrations/V031__monitoring_role.sql`
- Modify: `bin/db/migrate.sh:47-53` (the `psql_args` array)

- [ ] **Step 1: Thread the exporter password env var into migrate.sh**

In `bin/db/migrate.sh`, find the `psql_args` array (around line 47-53). It currently ends with the `app_user` line. Add one line after it so the array becomes:

```bash
psql_args=(
    --no-psqlrc
    --quiet
    --tuples-only
    -v ON_ERROR_STOP=1
    -v "app_user=${DB_APP_USER}"
    -v "exporter_password=${DB_EXPORTER_PASSWORD:-}"
)
```

(Keep any existing flags between these lines exactly as they are — only add the `exporter_password` line.)

- [ ] **Step 2: Write the migration**

Create `bin/db/migrations/V031__monitoring_role.sql` with the standard ASF license header (copy the 16-line header verbatim from `bin/db/migrations/V030__kg_relationship_type_check_generalizes.sql`), then:

```sql
-- V031: Create a dedicated, least-privilege monitoring role for
-- postgres_exporter. Membership in the built-in pg_monitor role grants full
-- pg_stat_* visibility without superuser. Used only by the opt-in
-- observability overlay; the application never connects as this role.
--
-- Idempotent: the role is created only if absent; the grant is a no-op when
-- already held. The password is supplied at apply time via the psql variable
-- :exporter_password (threaded from $DB_EXPORTER_PASSWORD by migrate.sh) so no
-- secret is committed. When the variable is empty the password step is
-- skipped, leaving any existing password untouched.

DO $$
BEGIN
    IF NOT EXISTS ( SELECT 1 FROM pg_roles WHERE rolname = 'wikantik_exporter' ) THEN
        CREATE ROLE wikantik_exporter LOGIN;
    END IF;
END
$$;

GRANT pg_monitor TO wikantik_exporter;

SELECT :'exporter_password' <> '' AS have_exporter_password \gset
\if :have_exporter_password
ALTER ROLE wikantik_exporter PASSWORD :'exporter_password';
\endif
```

- [ ] **Step 3: Apply the migration against the local DB and verify it succeeds**

Run (substitute the local DB password from `tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml`):

```bash
DB_NAME=wikantik DB_APP_USER=jspwiki DB_EXPORTER_PASSWORD='exporter-local-dev' \
  PGHOST=localhost PGUSER=jspwiki PGPASSWORD='<db-password>' bin/db/migrate.sh
```

Expected: output lists `V031__monitoring_role.sql` as applied with no error.

- [ ] **Step 4: Verify idempotency — re-run and confirm V031 is skipped**

Run the same command again.
Expected: `V031` is reported as already applied; no error.

- [ ] **Step 5: Verify the role exists with pg_monitor**

Run:

```bash
PGPASSWORD='<db-password>' psql -h localhost -U jspwiki -d wikantik -tAc \
  "SELECT r.rolname, m.rolname FROM pg_auth_members am
     JOIN pg_roles r ON r.oid = am.member
     JOIN pg_roles m ON m.oid = am.roleid
    WHERE r.rolname = 'wikantik_exporter';"
```

Expected output: `wikantik_exporter|pg_monitor`

- [ ] **Step 6: Commit**

```bash
git add bin/db/migrations/V031__monitoring_role.sql bin/db/migrate.sh
git commit -m "$(cat <<'EOF'
feat(observability): add wikantik_exporter monitoring role migration

V031 creates a least-privilege pg_monitor role for postgres_exporter;
migrate.sh threads DB_EXPORTER_PASSWORD into a psql variable so no
secret is committed.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: postgres_exporter custom queries

**Files:**
- Create: `docker/postgres-exporter/queries.yaml`

- [ ] **Step 1: Write the custom queries file**

Create `docker/postgres-exporter/queries.yaml`:

```yaml
# Custom postgres_exporter queries for Wikantik. Loaded via
# --extend.query-path. Default postgres_exporter collectors already cover
# pg_stat_database / pg_stat_activity; this file adds per-table size, scan,
# and buffer-cache stats so the vector/embedding tables stop being a
# performance blindspot. The Host & Infra dashboard filters these series by
# relname to the embedding tables.
wikantik_pg_table_io:
  query: |
    SELECT st.relname AS relname,
           pg_total_relation_size(st.relid) AS total_bytes,
           st.n_live_tup       AS live_rows,
           st.seq_scan         AS seq_scan,
           COALESCE(st.idx_scan, 0)        AS idx_scan,
           COALESCE(io.heap_blks_hit, 0)   AS heap_blks_hit,
           COALESCE(io.heap_blks_read, 0)  AS heap_blks_read,
           COALESCE(io.idx_blks_hit, 0)    AS idx_blks_hit,
           COALESCE(io.idx_blks_read, 0)   AS idx_blks_read
      FROM pg_stat_user_tables st
      JOIN pg_statio_user_tables io USING (relid)
  master: true
  metrics:
    - relname:        { usage: "LABEL",   description: "Table name" }
    - total_bytes:    { usage: "GAUGE",   description: "Total relation size including indexes and TOAST, in bytes" }
    - live_rows:      { usage: "GAUGE",   description: "Estimated live row count" }
    - seq_scan:       { usage: "COUNTER", description: "Sequential scans initiated on the table" }
    - idx_scan:       { usage: "COUNTER", description: "Index scans initiated on the table" }
    - heap_blks_hit:  { usage: "COUNTER", description: "Heap blocks found in the buffer cache" }
    - heap_blks_read: { usage: "COUNTER", description: "Heap blocks read from disk" }
    - idx_blks_hit:   { usage: "COUNTER", description: "Index blocks found in the buffer cache" }
    - idx_blks_read:  { usage: "COUNTER", description: "Index blocks read from disk" }
```

- [ ] **Step 2: Commit**

```bash
git add docker/postgres-exporter/queries.yaml
git commit -m "$(cat <<'EOF'
feat(observability): add postgres_exporter per-table IO custom query

Exposes size, scan, and buffer-cache hit/read counters per table so the
embedding/vector tables can be tracked on the Host & Infra dashboard.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Add exporter services to the observability overlay

**Files:**
- Modify: `docker-compose.observability.yml` (add three services under `services:`, before the `volumes:` block)

- [ ] **Step 1: Add the three exporter services**

In `docker-compose.observability.yml`, insert these three services after the `grafana:` service block and before the `volumes:` key:

```yaml
  node-exporter:
    image: quay.io/prometheus/node-exporter:v1.8.2
    restart: unless-stopped
    pid: host
    command:
      - --path.rootfs=/host
      - --path.procfs=/host/proc
      - --path.sysfs=/host/sys
      - --collector.filesystem.mount-points-exclude=^/(sys|proc|dev|host|etc)($$|/)
    volumes:
      - /:/host:ro,rslave
    deploy:
      resources:
        limits:
          memory: 128M

  cadvisor:
    image: gcr.io/cadvisor/cadvisor:v0.49.1
    restart: unless-stopped
    privileged: true
    devices:
      - /dev/kmsg
    volumes:
      - /:/rootfs:ro
      - /var/run:/var/run:ro
      - /sys:/sys:ro
      - /var/lib/docker/:/var/lib/docker:ro
      - /dev/disk/:/dev/disk:ro
    deploy:
      resources:
        limits:
          memory: 256M

  postgres-exporter:
    image: quay.io/prometheuscommunity/postgres-exporter:v0.16.0
    restart: unless-stopped
    depends_on:
      - db
    environment:
      DATA_SOURCE_NAME: "postgresql://wikantik_exporter:${DB_EXPORTER_PASSWORD}@db:5432/${DB_NAME:-wikantik}?sslmode=disable"
    command:
      - --extend.query-path=/etc/postgres-exporter/queries.yaml
    volumes:
      - ./docker/postgres-exporter/queries.yaml:/etc/postgres-exporter/queries.yaml:ro
    deploy:
      resources:
        limits:
          memory: 128M
```

Note: the `$$` in the node-exporter `--collector.filesystem...` line is intentional — Docker Compose requires `$$` to emit a literal `$`.

- [ ] **Step 2: Set DB_EXPORTER_PASSWORD in the prod env file**

The exporter password must be present in the gitignored prod env file `.env.prod` (and `.env` for dev) at the repo root. Add a line (choose a strong value; this is the same value passed to `migrate.sh` in Task 1):

```
DB_EXPORTER_PASSWORD=exporter-local-dev
```

This step touches gitignored files only — nothing to commit here.

- [ ] **Step 3: Commit**

```bash
git add docker-compose.observability.yml
git commit -m "$(cat <<'EOF'
feat(observability): add node, cAdvisor, and postgres exporters

Three exporters join the opt-in observability overlay so host,
container, and PostgreSQL strain can be observed during load tests.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Add Prometheus scrape jobs

**Files:**
- Modify: `docker/prometheus/prometheus.yml` (add three jobs under `scrape_configs:`)

- [ ] **Step 1: Add the scrape jobs**

In `docker/prometheus/prometheus.yml`, append these three jobs to the `scrape_configs:` list (after the existing `prometheus` job):

```yaml
  # Host metrics — CPU, memory, disk, network, load average of the docker host.
  - job_name: node
    static_configs:
      - targets: ['node-exporter:9100']

  # Per-container resource usage.
  - job_name: cadvisor
    static_configs:
      - targets: ['cadvisor:8080']

  # PostgreSQL: pg_stat_* plus the Wikantik per-table IO custom query.
  - job_name: postgres
    static_configs:
      - targets: ['postgres-exporter:9187']
```

- [ ] **Step 2: Commit**

```bash
git add docker/prometheus/prometheus.yml
git commit -m "$(cat <<'EOF'
feat(observability): scrape node, cAdvisor, and postgres exporters

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Bring up the overlay and verify all targets scrape

**Files:** none (verification task)

- [ ] **Step 1: Build and start the dev stack with the observability overlay**

```bash
mvn clean install -Dmaven.test.skip -T 1C
WIKANTIK_OBSERVABILITY=1 bin/container.sh up -d
```

Expected: all containers report healthy/up, including `node-exporter`, `cadvisor`, `postgres-exporter`.

- [ ] **Step 2: Verify every Prometheus target is UP**

Wait ~40s for the first scrape, then query Prometheus from inside the Grafana container (Prometheus has no published port yet):

```bash
docker compose -p repo exec grafana \
  wget -qO- 'http://prometheus:9090/api/v1/query?query=up' \
  | grep -o '"job":"[a-z]*"[^}]*"value":\["[0-9.]*","[01]"\]'
```

Expected: a line for each of `wikantik`, `prometheus`, `node`, `cadvisor`, `postgres`, each ending `,"1"]` (value 1 = UP).

- [ ] **Step 3: If postgres target is DOWN**

Check `docker compose -p repo logs postgres-exporter`. The usual cause is a password mismatch — the `DB_EXPORTER_PASSWORD` in `.env` must equal the value passed to `migrate.sh` in Task 1 Step 3. Re-run the Task 1 migration with the matching password, then `docker compose -p repo restart postgres-exporter` and repeat Step 2.

- [ ] **Step 4: No commit** — this task only verifies.

---

## Task 6: The "Wikantik — Host & Infra" dashboard

**Files:**
- Create: `docker/grafana/dashboards/wikantik-host.json`

- [ ] **Step 1: Build the dashboard JSON**

Create `docker/grafana/dashboards/wikantik-host.json`. Follow the exact structure of `docker/grafana/dashboards/wikantik-overview.json` — same top-level keys (`uid`, `title`, `tags`, `schemaVersion: 39`, `refresh: "30s"`, `time: {from: "now-1h", to: "now"}`), every panel carrying `"datasource": { "type": "prometheus", "uid": "wikantik-prometheus" }`, and `row` panels separating sections. Set `"uid": "wikantik-host"` and `"title": "Wikantik — Host & Infra"`.

Build these panels, grouped by `row`. Each non-row panel is `type: "timeseries"` unless noted; use the listed PromQL as the single target `expr` (add more targets only where multiple expressions are listed). Lay panels out two-per-row (`w: 12`).

**Row "Scrape health"** — one `type: "stat"` panel:
- *Targets up* — `expr: up`, `legendFormat: "{{job}}"`, value mappings `0→DOWN(red)`, `1→UP(green)`, `colorMode: background`.

**Row "Host"**:
- *CPU utilisation* — `expr: 1 - avg(rate(node_cpu_seconds_total{job="node",mode="idle"}[5m]))`, unit `percentunit`.
- *Load average* — three targets: `node_load1{job="node"}`, `node_load5{job="node"}`, `node_load15{job="node"}`, legends `1m`/`5m`/`15m`, unit `short`.
- *Memory* — targets `node_memory_MemTotal_bytes{job="node"}` (legend `total`), `node_memory_MemTotal_bytes{job="node"} - node_memory_MemAvailable_bytes{job="node"}` (legend `used`), unit `bytes`.
- *Disk I/O throughput* — targets `rate(node_disk_read_bytes_total{job="node"}[5m])` (legend `read {{device}}`), `rate(node_disk_written_bytes_total{job="node"}[5m])` (legend `write {{device}}`), unit `Bps`.
- *Disk space used* — `expr: 1 - (node_filesystem_avail_bytes{job="node",fstype!~"tmpfs|overlay"} / node_filesystem_size_bytes{job="node",fstype!~"tmpfs|overlay"})`, `legendFormat: "{{mountpoint}}"`, unit `percentunit`.
- *Network throughput* — targets `rate(node_network_receive_bytes_total{job="node",device!="lo"}[5m])` (legend `rx {{device}}`), `rate(node_network_transmit_bytes_total{job="node",device!="lo"}[5m])` (legend `tx {{device}}`), unit `Bps`.
- *Open file descriptors* — `expr: node_filefd_allocated{job="node"}`, unit `short`.

**Row "Containers"**:
- *Container CPU* — `expr: sum by (name) (rate(container_cpu_usage_seconds_total{job="cadvisor",name=~".+"}[5m]))`, `legendFormat: "{{name}}"`, unit `percentunit`.
- *Container memory* — `expr: container_memory_working_set_bytes{job="cadvisor",name=~".+"}`, `legendFormat: "{{name}}"`, unit `bytes`.
- *Container network* — targets `sum by (name) (rate(container_network_receive_bytes_total{job="cadvisor",name=~".+"}[5m]))` (legend `rx {{name}}`), `sum by (name) (rate(container_network_transmit_bytes_total{job="cadvisor",name=~".+"}[5m]))` (legend `tx {{name}}`), unit `Bps`.
- *Container block I/O* — `expr: sum by (name) (rate(container_fs_writes_bytes_total{job="cadvisor",name=~".+"}[5m]))`, `legendFormat: "{{name}}"`, unit `Bps`.

**Row "PostgreSQL"**:
- *Connections* — targets `sum(pg_stat_activity_count{job="postgres"})` (legend `current`), `pg_settings_max_connections{job="postgres"}` (legend `max`), unit `short`.
- *Transaction rate* — targets `rate(pg_stat_database_xact_commit{job="postgres",datname="wikantik"}[5m])` (legend `commit`), `rate(pg_stat_database_xact_rollback{job="postgres",datname="wikantik"}[5m])` (legend `rollback`), unit `ops`.
- *Cache hit ratio* — `expr: sum(rate(pg_stat_database_blks_hit{job="postgres",datname="wikantik"}[5m])) / (sum(rate(pg_stat_database_blks_hit{job="postgres",datname="wikantik"}[5m])) + sum(rate(pg_stat_database_blks_read{job="postgres",datname="wikantik"}[5m])))`, unit `percentunit`.
- *Deadlocks* — `expr: rate(pg_stat_database_deadlocks{job="postgres",datname="wikantik"}[5m])`, unit `ops`.
- *Database size* — `expr: pg_database_size_bytes{job="postgres",datname="wikantik"}`, unit `bytes`.

**Row "Hybrid / Vector search"**:
- *Embedding table size* — `expr: wikantik_pg_table_io_total_bytes{job="postgres",relname=~".*embedding.*|kg_content_chunks"}`, `legendFormat: "{{relname}}"`, unit `bytes`.
- *Embedding table cache hit ratio* — `expr: rate(wikantik_pg_table_io_heap_blks_hit{job="postgres",relname=~".*embedding.*|kg_content_chunks"}[5m]) / (rate(wikantik_pg_table_io_heap_blks_hit{job="postgres",relname=~".*embedding.*|kg_content_chunks"}[5m]) + rate(wikantik_pg_table_io_heap_blks_read{job="postgres",relname=~".*embedding.*|kg_content_chunks"}[5m]))`, `legendFormat: "{{relname}}"`, unit `percentunit`.
- *Query embedder calls* — `expr: sum by (result) (rate(wikantik_search_hybrid_embedder_calls_total{job="wikantik"}[5m]))`, `legendFormat: "{{result}}"`, unit `ops`.
- *Embedder circuit state* — `expr: wikantik_search_hybrid_embedder_circuit_state{job="wikantik"}`, unit `short` (0=closed,1=half-open,2=open).

(The *Query embedder latency* panel is added in Task 14, once the Timer exists.)

- [ ] **Step 2: Validate the JSON**

Run:

```bash
python3 -m json.tool docker/grafana/dashboards/wikantik-host.json > /dev/null && echo "JSON OK"
```

Expected: `JSON OK`

- [ ] **Step 3: Reload Grafana and confirm the dashboard renders**

```bash
docker compose -p repo restart grafana
```

Open `http://localhost:3000`, log in, open "Wikantik — Host & Infra". Confirm every panel shows data (the stack from Task 5 is running). The "Targets up" panel should show 5 green UP entries.

- [ ] **Step 4: Commit**

```bash
git add docker/grafana/dashboards/wikantik-host.json
git commit -m "$(cat <<'EOF'
feat(observability): add Wikantik Host & Infra Grafana dashboard

Host, container, PostgreSQL, and hybrid/vector-search rows, scraping
the new exporters. Auto-provisioned alongside the Overview dashboard.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

# Phase A — k6 load harness

## Task 7: Prometheus-text parser (`metrics.js`) — TDD

**Files:**
- Create: `loadtest/lib/metrics.js`
- Test: `loadtest/metrics-parse.test.mjs`

- [ ] **Step 1: Write the failing test**

Create `loadtest/metrics-parse.test.mjs`:

```javascript
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parsePromText, sumSeries, buildVerifyReport } from './lib/metrics.js';

const SAMPLE = `
# HELP wikantik_page_views_total Total page view requests
# TYPE wikantik_page_views_total counter
wikantik_page_views_total 7.0
http_server_requests_seconds_count{uri="/api/search",status="200"} 3.0
http_server_requests_seconds_count{uri="/api/pages/{id}",status="200"} 11.0
http_server_requests_seconds_count{uri="/knowledge-mcp",status="401"} 5.0
wikantik_search_hybrid_vector_index_size 12252.0
`;

test('parsePromText extracts name, labels, and value', () => {
  const parsed = parsePromText(SAMPLE);
  const view = parsed.find(s => s.name === 'wikantik_page_views_total');
  assert.equal(view.value, 7);
  assert.deepEqual(view.labels, {});
  const search = parsed.find(s =>
    s.name === 'http_server_requests_seconds_count' && s.labels.uri === '/api/search');
  assert.equal(search.value, 3);
  assert.equal(search.labels.status, '200');
});

test('sumSeries sums matching series and ignores comments', () => {
  const parsed = parsePromText(SAMPLE);
  assert.equal(sumSeries(parsed, 'http_server_requests_seconds_count'), 19);
  assert.equal(
    sumSeries(parsed, 'http_server_requests_seconds_count', { uri: '/api/search' }), 3);
  assert.equal(
    sumSeries(parsed, 'http_server_requests_seconds_count',
      { uri: /knowledge-mcp|wikantik-admin-mcp/ }), 5);
  assert.equal(sumSeries(parsed, 'does_not_exist'), 0);
});

test('buildVerifyReport flags a metric that did not move', () => {
  const before = parsePromText('wikantik_page_views_total 7.0\n');
  const after = parsePromText('wikantik_page_views_total 9.0\n');
  const report = buildVerifyReport(before, after, [
    { label: 'Page views', name: 'wikantik_page_views_total', mode: 'increased' },
    { label: 'Edits', name: 'wikantik_page_edits_total', mode: 'increased' },
  ]);
  assert.equal(report.allPass, false);
  assert.equal(report.rows[0].pass, true);
  assert.equal(report.rows[0].delta, 2);
  assert.equal(report.rows[1].pass, false);
});

test('buildVerifyReport mode "positive" checks the after value', () => {
  const before = parsePromText('');
  const after = parsePromText('wikantik_search_hybrid_vector_index_size 12252.0\n');
  const report = buildVerifyReport(before, after, [
    { label: 'Vector index', name: 'wikantik_search_hybrid_vector_index_size', mode: 'positive' },
  ]);
  assert.equal(report.allPass, true);
  assert.equal(report.rows[0].after, 12252);
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `node --test loadtest/metrics-parse.test.mjs`
Expected: FAIL — `Cannot find module './lib/metrics.js'`.

- [ ] **Step 3: Implement `metrics.js`**

Create `loadtest/lib/metrics.js`:

```javascript
/*
 * Pure Prometheus-text utilities for the Wikantik load harness.
 *
 * This module imports NOTHING from k6 so it can be unit-tested with
 * `node --test`. The k6 entry point does the HTTP scrape and passes the
 * raw response body to parsePromText().
 */

/** Parse Prometheus text exposition into [{ name, labels, value }]. */
export function parsePromText(text) {
  const series = [];
  for (const raw of String(text).split('\n')) {
    const line = raw.trim();
    if (line === '' || line.startsWith('#')) continue;
    const match = line.match(/^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+(.+)$/);
    if (!match) continue;
    const value = Number(match[3].trim().split(/\s+/)[0]);
    if (Number.isNaN(value)) continue;
    series.push({ name: match[1], labels: parseLabels(match[2]), value });
  }
  return series;
}

function parseLabels(blob) {
  const labels = {};
  if (!blob) return labels;
  const body = blob.slice(1, -1);
  const re = /([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"/g;
  let m;
  while ((m = re.exec(body)) !== null) {
    labels[m[1]] = m[2].replace(/\\"/g, '"').replace(/\\n/g, '\n').replace(/\\\\/g, '\\');
  }
  return labels;
}

/**
 * Sum the value of every series named `name` whose labels satisfy `match`.
 * A matcher value may be a string (exact equality) or a RegExp.
 */
export function sumSeries(parsed, name, match) {
  let total = 0;
  for (const s of parsed) {
    if (s.name !== name) continue;
    if (match && !labelsMatch(s.labels, match)) continue;
    total += s.value;
  }
  return total;
}

function labelsMatch(labels, match) {
  for (const key of Object.keys(match)) {
    const want = match[key];
    const got = labels[key];
    if (want instanceof RegExp) {
      if (got === undefined || !want.test(got)) return false;
    } else if (got !== want) {
      return false;
    }
  }
  return true;
}

/**
 * Compare two parsed scrapes against a list of verify targets.
 * Each target: { label, name, match?, mode }. mode is 'increased'
 * (after-before > 0) or 'positive' (after > 0).
 * Returns { allPass, rows: [{ label, before, after, delta, pass }] }.
 */
export function buildVerifyReport(before, after, targets) {
  const rows = targets.map((t) => {
    const b = sumSeries(before, t.name, t.match);
    const a = sumSeries(after, t.name, t.match);
    const delta = a - b;
    const pass = t.mode === 'positive' ? a > 0 : delta > 0;
    return { label: t.label, before: b, after: a, delta, pass };
  });
  return { allPass: rows.every((r) => r.pass), rows };
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `node --test loadtest/metrics-parse.test.mjs`
Expected: PASS — 4 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add loadtest/lib/metrics.js loadtest/metrics-parse.test.mjs
git commit -m "$(cat <<'EOF'
feat(loadtest): add Prometheus-text parser and verify-report builder

Pure, k6-free module unit-tested with node --test; powers the
--verify metrics-delta gate.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Harness config and profiles (`config.js`)

**Files:**
- Create: `loadtest/lib/config.js`

- [ ] **Step 1: Implement `config.js`**

Create `loadtest/lib/config.js`:

```javascript
/*
 * Configuration for the Wikantik load harness: reads k6 __ENV, defines the
 * smoke/load/stress profiles, and lists the --verify targets. Imported by
 * the k6 entry point (init context).
 */

/** Resolve runtime config from k6 environment variables. */
export function readConfig(env) {
  const baseUrl = (env.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
  return {
    baseUrl,
    metricsUrl: (env.METRICS_URL || baseUrl + '/metrics').replace(/\/+$/, ''),
    profile: env.PROFILE || 'smoke',
    verify: env.VERIFY === '1',
    writes: env.WRITES === '1',
    adminUser: env.LOADTEST_ADMIN_USER || '',
    adminPass: env.LOADTEST_ADMIN_PASS || '',
    mcpKey: env.LOADTEST_MCP_KEY || '',
    toolsKey: env.LOADTEST_TOOLS_KEY || '',
    durationOverride: env.K6_DURATION || '',
    vusOverride: env.K6_VUS ? Number(env.K6_VUS) : 0,
  };
}

/**
 * k6 `options` for a profile. `read` is the main traffic scenario; `writes`
 * is added only when WRITES=1. Thresholds make k6 exit non-zero on breach.
 */
export function buildOptions(cfg) {
  // --vus / --duration arrive as K6_VUS / K6_DURATION (see readConfig).
  const peak = cfg.vusOverride > 0 ? cfg.vusOverride : null;
  const profiles = {
    smoke: {
      executor: 'constant-vus',
      vus: peak || 5,
      duration: cfg.durationOverride || '2m',
    },
    load: {
      executor: 'ramping-vus', startVUs: 0,
      stages: [
        { duration: '2m', target: peak || 30 },
        { duration: cfg.durationOverride || '5m', target: peak || 30 },
        { duration: '1m', target: 0 },
      ],
    },
    stress: {
      executor: 'ramping-vus', startVUs: 0,
      stages: [
        { duration: '2m', target: Math.round((peak || 200) * 0.25) },
        { duration: '2m', target: Math.round((peak || 200) * 0.5) },
        { duration: '2m', target: peak || 200 },
        { duration: '2m', target: 0 },
      ],
    },
  };
  const read = profiles[cfg.profile];
  if (!read) throw new Error(`unknown profile: ${cfg.profile}`);

  const scenarios = { read: { ...read, exec: 'readScenario' } };
  if (cfg.writes) {
    scenarios.writes = {
      executor: 'constant-vus', vus: 1,
      duration: read.duration || '10m',
      exec: 'writeScenario',
    };
  }
  return {
    scenarios,
    thresholds: {
      http_req_failed: ['rate<0.10'],
      http_req_duration: ['p(95)<2000'],
      verify_failures: ['count==0'],
    },
  };
}

/** Verify targets — one row per dashboard panel the harness must light up. */
export function verifyTargets(cfg) {
  const targets = [
    { label: 'Page views', name: 'wikantik_page_views_total', mode: 'increased' },
    { label: 'Searches (/api/search)', name: 'http_server_requests_seconds_count',
      match: { uri: '/api/search' }, mode: 'increased' },
    { label: 'Hybrid embedder calls', name: 'wikantik_search_hybrid_embedder_calls_total',
      mode: 'increased' },
    { label: 'Vector index size', name: 'wikantik_search_hybrid_vector_index_size',
      mode: 'positive' },
    { label: 'Agent/admin traffic', name: 'http_server_requests_seconds_count',
      match: { uri: /knowledge-mcp|wikantik-admin-mcp/ }, mode: 'increased' },
  ];
  if (cfg.writes) {
    targets.push(
      { label: 'Page edits', name: 'wikantik_page_edits_total', mode: 'increased' },
      { label: 'Page deletes', name: 'wikantik_page_deletes_total', mode: 'increased' },
      { label: 'Logins', name: 'wikantik_auth_logins_total',
        match: { result: 'success' }, mode: 'increased' });
  }
  return targets;
}
```

- [ ] **Step 2: Commit**

```bash
git add loadtest/lib/config.js
git commit -m "$(cat <<'EOF'
feat(loadtest): add harness config, profiles, and verify targets

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Bundled slug list (`slugs.js`)

**Files:**
- Create: `loadtest/slugs.sample.txt`
- Create: `loadtest/lib/slugs.js`

- [ ] **Step 1: Create the bundled slug list**

Create `loadtest/slugs.sample.txt` by copying the existing curated list:

```bash
cp slugs.txt loadtest/slugs.sample.txt
```

If `slugs.txt` is absent, create `loadtest/slugs.sample.txt` with one slug per line:

```
Main
ADR-001
HybridRetrieval
PageGraphVsKnowledgeGraph
StructuralSpineDesign
AgentGradeContentDesign
KgInclusionPolicy
```

- [ ] **Step 2: Implement `slugs.js`**

Create `loadtest/lib/slugs.js`:

```javascript
/*
 * Loads the page-slug pool the read scenario walks. k6's open() runs only in
 * init context, so loadSlugs() must be called at module top level.
 */

const FALLBACK = ['Main', 'HybridRetrieval', 'PageGraphVsKnowledgeGraph'];

/**
 * Read newline-delimited slugs from `path` (default the bundled sample).
 * Returns a non-empty array — falls back to FALLBACK if the file is empty
 * or unreadable.
 */
export function loadSlugs(path) {
  const file = path || './slugs.sample.txt';
  try {
    const slugs = open(file)
      .split('\n')
      .map((s) => s.trim())
      .filter((s) => s !== '' && !s.startsWith('#'));
    return slugs.length > 0 ? slugs : FALLBACK;
  } catch (e) {
    return FALLBACK;
  }
}

/** Deterministic-ish random pick. */
export function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}
```

- [ ] **Step 3: Commit**

```bash
git add loadtest/slugs.sample.txt loadtest/lib/slugs.js
git commit -m "$(cat <<'EOF'
feat(loadtest): add bundled page-slug pool for the read scenario

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: Endpoint request helpers (`endpoints.js`)

**Files:**
- Create: `loadtest/lib/endpoints.js`

- [ ] **Step 1: Implement `endpoints.js`**

Create `loadtest/lib/endpoints.js`:

```javascript
/*
 * Per-surface k6 request helpers. Every helper drives an INSTRUMENTED
 * endpoint (the reason the old crawl scripts produced empty dashboards):
 * page views via /api/pages, search via /api/search, MCP via JSON-RPC,
 * tools via /tools, and the authenticated write cycle.
 */
import http from 'k6/http';
import { check } from 'k6';

const SEARCH_TERMS = [
  'logistics', 'optimization', 'algorithm', 'monitoring', 'latency',
  'throughput', 'scalability', 'retrieval', 'embedding', 'cluster',
];

/** GET /api/pages/{slug} — fires PAGE_REQUESTED -> wikantik_page_views_total. */
export function viewPage(cfg, slug) {
  const res = http.get(`${cfg.baseUrl}/api/pages/${encodeURIComponent(slug)}`,
    { tags: { surface: 'page' } });
  check(res, { 'page view 200/404': (r) => r.status === 200 || r.status === 404 });
  return res;
}

/** GET /api/search?q=... — drives the /api/search timer and the query embedder. */
export function search(cfg) {
  const term = SEARCH_TERMS[Math.floor(Math.random() * SEARCH_TERMS.length)];
  const res = http.get(`${cfg.baseUrl}/api/search?q=${encodeURIComponent(term)}&limit=20`,
    { tags: { surface: 'search' } });
  check(res, { 'search 200': (r) => r.status === 200 });
  return res;
}

/** POST a JSON-RPC tools/list to an MCP endpoint. */
export function mcpCall(cfg, path) {
  const body = JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/list' });
  const res = http.post(`${cfg.baseUrl}${path}`, body, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${cfg.mcpKey}`,
    },
    tags: { surface: 'mcp' },
  });
  // 401 is recorded traffic too, but warn so a bad key is not silent.
  if (res.status === 401) {
    console.warn(`MCP ${path} returned 401 — check LOADTEST_MCP_KEY scope/validity`);
  }
  check(res, { 'mcp reached app': (r) => r.status !== 0 });
  return res;
}

/** GET the OpenAPI tool server. */
export function toolsCall(cfg) {
  const res = http.get(`${cfg.baseUrl}/tools/search_wiki?q=monitoring`, {
    headers: cfg.toolsKey ? { Authorization: `Bearer ${cfg.toolsKey}` } : {},
    tags: { surface: 'tools' },
  });
  check(res, { 'tools reached app': (r) => r.status !== 0 });
  return res;
}

/** POST /api/auth/login — returns the response (sets a session cookie in the VU jar). */
export function login(cfg) {
  const res = http.post(`${cfg.baseUrl}/api/auth/login`,
    JSON.stringify({ username: cfg.adminUser, password: cfg.adminPass }),
    { headers: { 'Content-Type': 'application/json' }, tags: { surface: 'auth' } });
  check(res, { 'login 200': (r) => r.status === 200 });
  return res;
}

/**
 * Authenticated create -> edit -> delete cycle on a scoped LoadTest page.
 * Fires POST_SAVE x2 and PAGE_DELETED. The VU must have logged in first
 * (cookie is held in the per-VU jar).
 */
export function writeCycle(cfg, vu, iter) {
  const name = `LoadTest/k6-${vu}-${iter}`;
  const url = `${cfg.baseUrl}/api/pages/${encodeURIComponent(name)}`;
  const headers = { 'Content-Type': 'application/json' };
  const create = http.put(url,
    JSON.stringify({ content: `Load test page ${name}, created.` }),
    { headers, tags: { surface: 'write' } });
  check(create, { 'write create 200/201': (r) => r.status === 200 || r.status === 201 });
  const edit = http.put(url,
    JSON.stringify({ content: `Load test page ${name}, edited.` }),
    { headers, tags: { surface: 'write' } });
  check(edit, { 'write edit 200': (r) => r.status === 200 });
  const del = http.del(url, null, { headers, tags: { surface: 'write' } });
  check(del, { 'write delete 200/204': (r) => r.status === 200 || r.status === 204 });
}
```

- [ ] **Step 2: Commit**

```bash
git add loadtest/lib/endpoints.js
git commit -m "$(cat <<'EOF'
feat(loadtest): add k6 request helpers for the instrumented endpoints

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: k6 entry point (`wikantik-load.js`)

**Files:**
- Create: `loadtest/wikantik-load.js`

- [ ] **Step 1: Implement the k6 entry point**

Create `loadtest/wikantik-load.js`:

```javascript
/*
 * Wikantik load-test entry point. Run via bin/loadtest.sh, which sets the
 * PROFILE / BASE_URL / credential env vars. Drives a weighted mix of the
 * instrumented endpoints; with VERIFY=1 it scrapes /metrics before and
 * after and gates on a per-panel delta.
 */
import { sleep } from 'k6';
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';

import { readConfig, buildOptions, verifyTargets } from './lib/config.js';
import { loadSlugs, pick } from './lib/slugs.js';
import { viewPage, search, mcpCall, toolsCall, login, writeCycle } from './lib/endpoints.js';
import { parsePromText, buildVerifyReport } from './lib/metrics.js';

const cfg = readConfig(__ENV);
const slugs = loadSlugs(__ENV.SLUGS_FILE);
export const options = buildOptions(cfg);

// Incremented in teardown for every verify target that did not move.
// The verify_failures threshold (config.js) turns count>0 into a non-zero exit.
const verifyFailures = new Counter('verify_failures');

/** setup(): scrape the baseline /metrics when VERIFY=1. */
export function setup() {
  if (!cfg.verify) return { baseline: null };
  const res = http.get(cfg.metricsUrl);
  if (res.status !== 200) {
    throw new Error(
      `--verify: metrics endpoint ${cfg.metricsUrl} returned ${res.status}. ` +
      'Run from inside the network or pass --metrics-url.');
  }
  return { baseline: res.body };
}

/** Read scenario: weighted mix over the instrumented surfaces. */
export function readScenario() {
  const r = Math.random();
  if (r < 0.55) viewPage(cfg, pick(slugs));
  else if (r < 0.80) search(cfg);
  else if (r < 0.95) mcpCall(cfg, Math.random() < 0.5 ? '/knowledge-mcp' : '/wikantik-admin-mcp');
  else toolsCall(cfg);
  sleep(0.5 + Math.random());
}

/** Write scenario: one logged-in VU running create/edit/delete cycles. */
export function writeScenario() {
  if (!cfg.adminUser || !cfg.adminPass) {
    throw new Error('WRITES=1 requires LOADTEST_ADMIN_USER and LOADTEST_ADMIN_PASS');
  }
  login(cfg);
  writeCycle(cfg, exec.vu.idInTest, exec.vu.iterationInScenario);
  sleep(2);
}

/** teardown(): re-scrape /metrics, diff, print PASS/FAIL, gate on the delta. */
export function teardown(data) {
  if (!cfg.verify || !data.baseline) return;
  const after = http.get(cfg.metricsUrl);
  if (after.status !== 200) {
    verifyFailures.add(1);
    console.error(`--verify: post-run scrape failed (${after.status})`);
    return;
  }
  const report = buildVerifyReport(
    parsePromText(data.baseline), parsePromText(after.body), verifyTargets(cfg));

  console.log('=== Verify: metrics delta ===');
  for (const row of report.rows) {
    const mark = row.pass ? 'PASS' : 'FAIL';
    console.log(
      `  [${mark}] ${row.label}: before=${row.before} after=${row.after} delta=${row.delta}`);
    if (!row.pass) verifyFailures.add(1);
  }
  console.log(report.allPass
    ? '=== Verify: all targets moved ==='
    : '=== Verify: FAILED — see FAIL rows above ===');
}
```

- [ ] **Step 2: Sanity-check the script parses**

Requires k6 installed (`k6 version`). If k6 is absent, install per <https://grafana.com/docs/k6/latest/set-up/install-k6/>, then run:

```bash
cd loadtest && k6 inspect wikantik-load.js && cd ..
```

Expected: k6 prints the resolved `options` JSON with no parse error.

- [ ] **Step 3: Commit**

```bash
git add loadtest/wikantik-load.js
git commit -m "$(cat <<'EOF'
feat(loadtest): add k6 entry point with weighted mix and --verify gate

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: Wrapper script, env template, README, gitignore

**Files:**
- Create: `bin/loadtest.sh`
- Create: `loadtest/loadtest.env.example`
- Create: `loadtest/README.md`
- Modify: `.gitignore`

- [ ] **Step 1: Write the env template**

Create `loadtest/loadtest.env.example`:

```bash
# Copy to loadtest/loadtest.env (gitignored) and fill in. bin/loadtest.sh
# sources this file; values not set here fall back to test.properties for
# the admin credentials.

# Target under test. Use the internal URL when running on the host so
# --verify can reach /metrics.
BASE_URL=http://localhost:8080

# Optional: scrape metrics from a different URL than BASE_URL (for --verify
# against prod, run on docker1 and point this at http://localhost:8080/metrics).
# METRICS_URL=http://localhost:8080/metrics

# Admin account for --writes (create/edit/delete + login metric).
LOADTEST_ADMIN_USER=testbot
LOADTEST_ADMIN_PASS=

# API keys for the MCP and OpenAPI tool surfaces.
LOADTEST_MCP_KEY=
LOADTEST_TOOLS_KEY=

# Grafana annotation posting (optional — Phase C). Token needs the Annotation
# writer role.
GRAFANA_URL=http://localhost:3000
GRAFANA_TOKEN=

# Prometheus remote-write endpoint for k6's own metrics (optional — Phase C).
K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write
```

- [ ] **Step 2: Write the wrapper script**

Create `bin/loadtest.sh`:

```bash
#!/usr/bin/env bash
#
# loadtest.sh — run the Wikantik k6 load harness.
#
# Usage:
#   bin/loadtest.sh <profile> [options]
#
# Profiles:
#   smoke    ~2 min, low concurrency; hits every instrumented endpoint.
#   load     sustained ramping load for steady-state performance testing.
#   stress   staged ramp past capacity until thresholds break.
#
# Options:
#   --verify        scrape /metrics before+after and gate on per-panel deltas
#   --writes        add the authenticated create/edit/delete + login cycle
#   --metrics-url U scrape metrics from U instead of BASE_URL/metrics
#   --duration D    override the run duration (load/stress)
#   --vus N         override peak VUs (load/stress)
#   --dry-run       print the k6 command without running it
#   -h | --help     this help
#
# Credentials: loadtest/loadtest.env (copy from loadtest.env.example), with
# admin credentials falling back to test.properties. No secrets are embedded.
set -euo pipefail

REPO_ROOT="$( cd "$( dirname "${BASH_SOURCE[0]}" )/.." && pwd )"
LOADTEST_DIR="${REPO_ROOT}/loadtest"

usage() { sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//; $d'; }

[[ "${1:-}" == "-h" || "${1:-}" == "--help" || -z "${1:-}" ]] && { usage; exit 0; }

PROFILE="$1"; shift
case "${PROFILE}" in smoke|load|stress) ;; *)
  echo "ERROR: unknown profile '${PROFILE}' (expected smoke|load|stress)" >&2; exit 2 ;;
esac

VERIFY=0 WRITES=0 DRY_RUN=0 DURATION="" VUS="" METRICS_URL=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --verify)      VERIFY=1; shift ;;
    --writes)      WRITES=1; shift ;;
    --metrics-url) METRICS_URL="$2"; shift 2 ;;
    --duration)    DURATION="$2"; shift 2 ;;
    --vus)         VUS="$2"; shift 2 ;;
    --dry-run)     DRY_RUN=1; shift ;;
    -h|--help)     usage; exit 0 ;;
    *) echo "ERROR: unknown option '$1'" >&2; exit 2 ;;
  esac
done

if ! command -v k6 >/dev/null 2>&1; then
  echo "ERROR: k6 is not installed." >&2
  echo "Install it: https://grafana.com/docs/k6/latest/set-up/install-k6/" >&2
  exit 2
fi

# Load credentials: loadtest.env first, then test.properties for admin creds.
[[ -f "${LOADTEST_DIR}/loadtest.env" ]] && set -a && \
  . "${LOADTEST_DIR}/loadtest.env" && set +a
if [[ -f "${REPO_ROOT}/test.properties" ]]; then
  : "${LOADTEST_ADMIN_USER:=$(grep -E '^test.user.login=' "${REPO_ROOT}/test.properties" | cut -d= -f2-)}"
  : "${LOADTEST_ADMIN_PASS:=$(grep -E '^test.user.password=' "${REPO_ROOT}/test.properties" | cut -d= -f2-)}"
fi
: "${BASE_URL:=http://localhost:8080}"

# Fail fast on missing credentials a chosen mode needs.
if [[ "${WRITES}" == 1 && ( -z "${LOADTEST_ADMIN_USER:-}" || -z "${LOADTEST_ADMIN_PASS:-}" ) ]]; then
  echo "ERROR: --writes needs LOADTEST_ADMIN_USER and LOADTEST_ADMIN_PASS" >&2
  echo "       set them in loadtest/loadtest.env or test.properties" >&2
  exit 2
fi

K6_ARGS=(run
  -e "PROFILE=${PROFILE}"
  -e "BASE_URL=${BASE_URL}"
  -e "VERIFY=${VERIFY}"
  -e "WRITES=${WRITES}"
  -e "LOADTEST_ADMIN_USER=${LOADTEST_ADMIN_USER:-}"
  -e "LOADTEST_ADMIN_PASS=${LOADTEST_ADMIN_PASS:-}"
  -e "LOADTEST_MCP_KEY=${LOADTEST_MCP_KEY:-}"
  -e "LOADTEST_TOOLS_KEY=${LOADTEST_TOOLS_KEY:-}"
)
[[ -n "${METRICS_URL}" ]] && K6_ARGS+=(-e "METRICS_URL=${METRICS_URL}")
[[ -n "${DURATION}" ]] && K6_ARGS+=(-e "K6_DURATION=${DURATION}")
[[ -n "${VUS}" ]] && K6_ARGS+=(-e "K6_VUS=${VUS}")
K6_ARGS+=(wikantik-load.js)

if [[ "${DRY_RUN}" == 1 ]]; then
  echo "cd ${LOADTEST_DIR} && k6 ${K6_ARGS[*]}"
  exit 0
fi
cd "${LOADTEST_DIR}"
exec k6 "${K6_ARGS[@]}"
```

Make it executable:

```bash
chmod +x bin/loadtest.sh
```

Note: `--duration` / `--vus` are passed to k6 as the `K6_DURATION` / `K6_VUS` env vars, which `config.js` `readConfig` reads and `buildOptions` applies (smoke run duration / VUs, load hold-duration / peak, stress peak).

- [ ] **Step 3: Write the README**

Create `loadtest/README.md`:

```markdown
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
```

- [ ] **Step 4: Add loadtest.env to .gitignore**

Append to `.gitignore`:

```
# Load-test credentials (never commit)
loadtest/loadtest.env
```

- [ ] **Step 5: Verify the wrapper**

```bash
bin/loadtest.sh --help
bin/loadtest.sh smoke --dry-run
```

Expected: help text prints; `--dry-run` prints a `cd … && k6 run -e PROFILE=smoke …` command.

- [ ] **Step 6: Commit**

```bash
git add bin/loadtest.sh loadtest/loadtest.env.example loadtest/README.md .gitignore
git commit -m "$(cat <<'EOF'
feat(loadtest): add bin/loadtest.sh wrapper, env template, and README

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: End-to-end smoke run against local deploy

**Files:** none (verification task)

- [ ] **Step 1: Ensure a local deploy is running with the observability overlay**

From Task 5 the stack is up. Confirm: `curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/health` prints `200`.

- [ ] **Step 2: Fill in local credentials**

Copy `loadtest/loadtest.env.example` to `loadtest/loadtest.env` and set `BASE_URL=http://localhost:8080`. Set `LOADTEST_ADMIN_PASS` from `test.properties`. Set `LOADTEST_MCP_KEY` to a valid local API key (create one at `/admin` if needed).

- [ ] **Step 3: Run the smoke profile with verification**

```bash
bin/loadtest.sh smoke --verify
```

Expected: k6 runs ~2 min; the teardown prints the `=== Verify: metrics delta ===` table; `Page views`, `Searches`, `Hybrid embedder calls`, `Vector index size`, and `Agent/admin traffic` all show `[PASS]`; k6 exits 0.

- [ ] **Step 4: Run with writes**

```bash
bin/loadtest.sh smoke --verify --writes
```

Expected: the verify table additionally shows `[PASS]` for `Page edits`, `Page deletes`, and `Logins`. Confirm no `LoadTest/*` pages remain: `curl -s http://localhost:8080/api/pages/LoadTest%2Fk6-1-1` returns 404.

- [ ] **Step 5: No commit** — verification only. If a target FAILs, the cause is a real gap (e.g. the deployed build predates the page-view fix) — investigate before proceeding.

---

# Phase C — Integration

## Task 14: Query-embedder latency Timer — TDD

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/search/hybrid/QueryEmbedder.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/search/hybrid/HybridMetricsBridge.java:89-101`
- Test: `wikantik-main/src/test/java/com/wikantik/search/hybrid/QueryEmbedderTest.java`
- Test: `wikantik-main/src/test/java/com/wikantik/search/hybrid/HybridMetricsBridgeTest.java`

- [ ] **Step 1: Write the failing QueryEmbedder test**

In `QueryEmbedderTest.java`, add this test method (and add `import io.micrometer.core.instrument.Timer;` and `import io.micrometer.core.instrument.simple.SimpleMeterRegistry;` to the imports):

```java
    @Test
    void embedRecordsLatencyWhenTimerSet() {
        final FakeClient client = new FakeClient( new float[]{ 0.1f } );
        embedder = new QueryEmbedder( client, testConfig(), clock );
        final Timer timer = Timer.builder( "wikantik.search.hybrid.embedder.latency" )
                .register( new SimpleMeterRegistry() );
        embedder.setLatencyTimer( timer );

        embedder.embed( "a query" );

        assertEquals( 1, timer.count(),
            "embed() must record one sample to the injected latency Timer" );
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=QueryEmbedderTest#embedRecordsLatencyWhenTimerSet`
Expected: compilation FAILS — `cannot find symbol: method setLatencyTimer`.

- [ ] **Step 3: Add the Timer to QueryEmbedder**

In `QueryEmbedder.java`:

(a) Add to the imports: `import io.micrometer.core.instrument.Timer;` and `import java.util.concurrent.TimeUnit;` (skip either if already imported).

(b) Add a field after the `breaker` field (around line 75):

```java
    /** Optional latency Timer — injected by HybridMetricsBridge, null in tests/unwired. */
    private volatile Timer latencyTimer;
```

(c) Add a setter near `metrics()` (around line 175):

```java
    /**
     * Inject the Micrometer Timer that {@link #embed} records client-call
     * latency to. Null until {@code HybridMetricsBridge} wires it; embed()
     * is null-safe so this is optional.
     */
    public void setLatencyTimer( final Timer timer ) {
        this.latencyTimer = timer;
    }
```

(d) In `embed()`, capture the start time after the breaker admits the call. Immediately before `boolean success = false;` (line ~134) add:

```java
        final long startNanos = System.nanoTime();
```

(e) In the existing `finally` block of `embed()` (line ~163-166), add the Timer record after the `breaker.afterCall(...)` call:

```java
        } finally {
            breaker.afterCall( success, admit == Breaker.Admittance.PROBE,
                    this::noteBreakerOpen, this::noteBreakerClose );
            final Timer t = latencyTimer;
            if ( t != null ) {
                t.record( System.nanoTime() - startNanos, TimeUnit.NANOSECONDS );
            }
        }
```

- [ ] **Step 4: Run the QueryEmbedder test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=QueryEmbedderTest#embedRecordsLatencyWhenTimerSet`
Expected: PASS.

- [ ] **Step 5: Write the failing HybridMetricsBridge test**

In `HybridMetricsBridgeTest.java`, add:

```java
    @Test
    void registersEmbedderLatencyTimer() {
        final MeterRegistry reg = new SimpleMeterRegistry();
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        when( embedder.metrics() ).thenReturn( fixedMetrics() );
        when( embedder.circuitState() ).thenReturn( CircuitState.CLOSED );

        HybridMetricsBridge.register( reg, embedder, null, null );

        assertNotNull( reg.find( "wikantik.search.hybrid.embedder.latency" ).timer(),
            "embedder latency Timer must be registered" );
    }
```

- [ ] **Step 6: Run it to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=HybridMetricsBridgeTest#registersEmbedderLatencyTimer`
Expected: FAIL — `find(...).timer()` returns null → assertion fails.

- [ ] **Step 7: Register and inject the Timer in HybridMetricsBridge**

In `HybridMetricsBridge.java`, add `import io.micrometer.core.instrument.Timer;` to the imports. At the end of `registerEmbedder(...)` (after the `circuit_state` Gauge, around line 131), add:

```java
        final Timer latency = Timer.builder( PFX + ".embedder.latency" )
            .description( "Query embedder client-call latency" )
            .publishPercentileHistogram()
            .register( registry );
        embedder.setLatencyTimer( latency );
```

`.publishPercentileHistogram()` is required: it makes Micrometer emit
`wikantik_search_hybrid_embedder_latency_seconds_bucket` series, which the
Grafana `histogram_quantile(...)` panel in Task 15 needs. A plain Timer
publishes only `_count`/`_sum`/`_max`.

- [ ] **Step 8: Run both hybrid tests to verify they pass**

Run: `mvn test -pl wikantik-main -Dtest=QueryEmbedderTest,HybridMetricsBridgeTest`
Expected: PASS — all tests in both classes green.

- [ ] **Step 9: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/search/hybrid/QueryEmbedder.java \
        wikantik-main/src/main/java/com/wikantik/search/hybrid/HybridMetricsBridge.java \
        wikantik-main/src/test/java/com/wikantik/search/hybrid/QueryEmbedderTest.java \
        wikantik-main/src/test/java/com/wikantik/search/hybrid/HybridMetricsBridgeTest.java
git commit -m "$(cat <<'EOF'
feat(metrics): add query-embedder latency Timer

The embedder exposed only counts; wikantik_search_hybrid_embedder_latency
is the missing latency distribution for the vector-search hot path.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 15: Add the embedder-latency panel to the host dashboard

**Files:**
- Modify: `docker/grafana/dashboards/wikantik-host.json`

- [ ] **Step 1: Add the latency panel**

In the "Hybrid / Vector search" row of `wikantik-host.json`, add one more `type: "timeseries"` panel:

- *Query embedder latency* — two targets:
  - `histogram_quantile(0.95, sum(rate(wikantik_search_hybrid_embedder_latency_seconds_bucket{job="wikantik"}[5m])) by (le))`, legend `p95`
  - `histogram_quantile(0.50, sum(rate(wikantik_search_hybrid_embedder_latency_seconds_bucket{job="wikantik"}[5m])) by (le))`, legend `p50`
  - unit `s`.

Keep `gridPos` consistent with the two-per-row layout used elsewhere.

- [ ] **Step 2: Validate the JSON**

Run: `python3 -m json.tool docker/grafana/dashboards/wikantik-host.json > /dev/null && echo "JSON OK"`
Expected: `JSON OK`

- [ ] **Step 3: Commit**

```bash
git add docker/grafana/dashboards/wikantik-host.json
git commit -m "$(cat <<'EOF'
feat(observability): add query-embedder latency panel

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 16: k6 → Prometheus remote-write and Grafana annotations

**Files:**
- Modify: `docker-compose.observability.yml` (prometheus `command:` + `ports:`)
- Modify: `bin/loadtest.sh` (remote-write output + annotation posting)

- [ ] **Step 1: Enable the Prometheus remote-write receiver and a loopback port**

In `docker-compose.observability.yml`, in the `prometheus:` service: add the remote-write flag to `command:` and a loopback-only published port. The service becomes:

```yaml
  prometheus:
    image: prom/prometheus:v3.5.3
    restart: unless-stopped
    volumes:
      - ./docker/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prometheus-data:/prometheus
    command:
      - --config.file=/etc/prometheus/prometheus.yml
      - --storage.tsdb.path=/prometheus
      - --storage.tsdb.retention.time=30d
      - --web.enable-remote-write-receiver
    # Published on loopback only — lets a host-side k6 remote-write its
    # metrics in. Not LAN- or internet-facing.
    ports:
      - "127.0.0.1:9090:9090"
    deploy:
      resources:
        limits:
          memory: 512M
```

- [ ] **Step 2: Add remote-write output and annotations to the wrapper**

In `bin/loadtest.sh`, replace the final block (from `if [[ "${DRY_RUN}" == 1 ]]` to the end) with:

```bash
# k6 remote-writes its own metrics (offered RPS, VUs, latency) to Prometheus
# when K6_PROMETHEUS_RW_SERVER_URL is set.
K6_OUT_ARGS=()
if [[ -n "${K6_PROMETHEUS_RW_SERVER_URL:-}" ]]; then
  K6_OUT_ARGS+=(--out experimental-prometheus-rw)
fi

# Best-effort Grafana region annotation around the run.
post_annotation() {
  [[ -z "${GRAFANA_URL:-}" || -z "${GRAFANA_TOKEN:-}" ]] && return 0
  curl -s -o /dev/null -X POST "${GRAFANA_URL}/api/annotations" \
    -H "Authorization: Bearer ${GRAFANA_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "$1" || echo "WARN: Grafana annotation POST failed" >&2
}

if [[ "${DRY_RUN}" == 1 ]]; then
  echo "cd ${LOADTEST_DIR} && k6 ${K6_ARGS[*]} ${K6_OUT_ARGS[*]}"
  exit 0
fi

START_MS=$(( $(date +%s) * 1000 ))
post_annotation "{\"time\":${START_MS},\"tags\":[\"loadtest\",\"${PROFILE}\"],\"text\":\"loadtest ${PROFILE} start\"}"

cd "${LOADTEST_DIR}"
set +e
k6 "${K6_ARGS[@]}" "${K6_OUT_ARGS[@]}"
K6_EXIT=$?
set -e

END_MS=$(( $(date +%s) * 1000 ))
post_annotation "{\"time\":${START_MS},\"timeEnd\":${END_MS},\"tags\":[\"loadtest\",\"${PROFILE}\"],\"text\":\"loadtest ${PROFILE} finished (exit ${K6_EXIT})\"}"
exit "${K6_EXIT}"
```

The wrapper reads `K6_PROMETHEUS_RW_SERVER_URL` from `loadtest.env` (already in
the template from Task 12) — k6 inherits it because the wrapper sources that
file with `set -a`. Version note: if `k6 run` reports `experimental-prometheus-rw`
is an unknown output, this k6 build has promoted it — use `--out prometheus-rw`
instead.

- [ ] **Step 3: Restart the overlay and verify the receiver**

```bash
docker compose -p repo restart prometheus
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:9090/-/healthy
```

Expected: `200` (Prometheus reachable on the loopback port).

- [ ] **Step 4: Verify k6 metrics reach Prometheus**

```bash
bin/loadtest.sh smoke
```

After it finishes, query Prometheus:

```bash
curl -s 'http://localhost:9090/api/v1/query?query=k6_http_reqs_total' | grep -o '"status":"success"'
```

Expected: `"status":"success"` and a non-empty `result` — k6's own metrics are in Prometheus under the `k6_*` names.

- [ ] **Step 5: Add the "Load test" row to the host dashboard**

In `docker/grafana/dashboards/wikantik-host.json`, add a new `row` panel titled "Load test (k6)" as the **first** content row (just under any header), with two `timeseries` panels:

- *Offered request rate* — `expr: sum(rate(k6_http_reqs_total[1m]))`, unit `reqps`.
- *Active VUs* — `expr: k6_vus`, unit `short`.

(k6's trend metrics under remote-write are emitted as summary stats, not
histogram buckets, so the k6 row uses the reliably-named counter and gauge
above. Server-observed latency already lives on the Overview dashboard.)

Validate: `python3 -m json.tool docker/grafana/dashboards/wikantik-host.json > /dev/null && echo "JSON OK"`.

- [ ] **Step 6: Commit**

```bash
git add docker-compose.observability.yml bin/loadtest.sh docker/grafana/dashboards/wikantik-host.json
git commit -m "$(cat <<'EOF'
feat(loadtest): remote-write k6 metrics to Prometheus + Grafana annotations

Offered load and host strain now share a timeline; each run posts a
Grafana region annotation.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 17: Documentation and News log

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/DockerDeployment.md`
- Modify: `docs/wikantik-pages/News.md`

- [ ] **Step 1: Document the harness in CLAUDE.md**

In `CLAUDE.md`, after the "Container deployment" section's observability paragraph (the one mentioning `docker-compose.observability.yml`), add:

```markdown
### Load testing

`bin/loadtest.sh <smoke|load|stress>` runs the k6 harness in `loadtest/`
against an instrumented set of endpoints. `--verify` scrapes `/metrics`
before and after and fails if a target dashboard panel did not move;
`--writes` adds an authenticated edit/delete cycle. The observability
overlay's new "Wikantik — Host & Infra" dashboard shows host, container,
PostgreSQL, and vector-search strain; with the overlay up, k6 remote-writes
its own metrics into Prometheus so offered load and host response share a
timeline. See `loadtest/README.md`.
```

- [ ] **Step 2: Document the host dashboard + exporters in DockerDeployment.md**

In `docs/DockerDeployment.md`, in the observability section (§3, referenced from `CLAUDE.md`), add a paragraph noting the three new exporters (node, cAdvisor, postgres) join the overlay, the new "Wikantik — Host & Infra" dashboard, that `DB_EXPORTER_PASSWORD` must be set in the prod env file, and that the `V031` migration provisions the `wikantik_exporter` role.

- [ ] **Step 3: Log the commits in News.md**

In `docs/wikantik-pages/News.md`, following the existing commit-log convention (one entry per commit subject), add an entry for each commit landed in Tasks 1-17. Match the formatting of the most recent existing entries.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md docs/DockerDeployment.md docs/wikantik-pages/News.md
git commit -m "$(cat <<'EOF'
docs: document the load-test harness and host observability

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 18: Full verification build

**Files:** none (verification task)

- [ ] **Step 1: Run the parser unit test**

Run: `node --test loadtest/metrics-parse.test.mjs`
Expected: PASS.

- [ ] **Step 2: Run the full unit-test build**

Run: `mvn clean install -T 1C -DskipITs`
Expected: BUILD SUCCESS — the new `QueryEmbedderTest` and `HybridMetricsBridgeTest` cases pass and nothing else regressed.

- [ ] **Step 3: Run the integration-test reactor**

Run: `mvn clean install -Pintegration-tests -fae`
Expected: BUILD SUCCESS (no `-T` flag — integration tests must run sequentially).

- [ ] **Step 4: Final harness smoke**

Run: `bin/loadtest.sh smoke --verify --writes`
Expected: every verify row `[PASS]`; k6 exits 0; no `LoadTest/*` pages remain.

- [ ] **Step 5: No commit** — verification only.

---

## Notes for the implementer

- **k6 install:** the harness needs the `k6` binary. Tasks 11+ cannot be verified without it. Install per <https://grafana.com/docs/k6/latest/set-up/install-k6/>.
- **Phase ordering:** B → A → C is required — Phase A's `--verify` smoke (Task 13) needs the overlay from Phase B, and Task 16 needs both the harness and the overlay.
- **Prod application:** this plan builds and verifies against the local deploy. Rolling the exporters and the new dashboard to docker1 is a separate deploy step (`bin/remote.sh deploy` with `WIKANTIK_OBSERVABILITY=1`), out of scope here. The `V031` migration applies automatically on the next deploy via `migrate.sh`.
- **Deviation from spec §5.3:** the spec described pulling page slugs live from `/api/changes`. This plan uses a bundled `loadtest/slugs.sample.txt` (with a `SLUGS_FILE` override) instead — simpler, deterministic, no dependency on the change-feed response shape. Flag for review if live slugs are wanted.
```
