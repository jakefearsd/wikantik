# Wikantik Deployment with Docker

How to deploy Wikantik as a Docker Compose stack — locally and on a remote
host (docker1-style: build locally, push over ssh, host bind mounts) — and
how to upgrade it for each release.

> For the **bare-metal** path (local PostgreSQL + Tomcat 11, used for
> development and single-host installs), see
> [PostgreSQLLocalDeployment.md](PostgreSQLLocalDeployment.md) instead.
> For a **cloud VM** (AWS/GCP, GHCR image pull, Terraform, pull-based
> updates, cost-bounded GenAI tiers), see
> [CloudDeployment.md](CloudDeployment.md) instead — it reuses this same
> compose stack and container image, layered with
> `docker-compose.cloud.yml`.

The repository ships the real deployment artifacts; this guide explains how
to drive them. Do not hand-write compose files.

- `docker-compose.yml` + `docker-compose.{dev,prod,test}.yml` — the stack
  (`docker-compose.cloud.yml` is the cloud overlay — see CloudDeployment.md)
- `docker/entrypoint.sh` — renders container config from env vars at start
- `bin/container.sh` — local `docker compose` wrapper
- `bin/remote.sh` — ssh-driven remote deploy/admin
- `bin/cut-release.sh`, `bin/deploy-release.sh` — release/upgrade wrappers

**Base images:** the runtime is `tomcat:11.0.22-jdk25-temurin` (Tomcat 11 /
Java 25); the build stage is `maven:3.9-eclipse-temurin-25`. Both are pinned
in the top-level `Dockerfile`; the Tomcat tag must stay in lockstep with
`bin/deploy-local.sh`'s `TOMCAT_VERSION` so the bare-metal and container
install paths run the identical Tomcat patch.

## Application Endpoints

| Path | Description |
|------|-------------|
| `/` | React SPA (reader, editor, search) |
| `/page-graph` | Page Graph viewer (wikilink edges) |
| `/knowledge-graph` | Knowledge Graph viewer (LLM-extracted entities) |
| `/admin/` | Admin panel (user, content, and security management) |
| `/api/` | REST API (pages, attachments, search, history, knowledge graph) |
| `/wiki/{slug}?format=md\|json` | Raw content for crawlers and RAG ingestion |
| `/api/changes?since=…` | Incremental change feed for sync pipelines |
| `/wikantik-admin-mcp` | Admin MCP server (writes + analytics + verification stamping) — 26 tools |
| `/knowledge-mcp` | Knowledge MCP server (hybrid retrieval + Knowledge Graph + structural-spine + agent-projection) — 20 tools |
| `/tools/*` | OpenAPI 3.1 tool server (OpenWebUI-compatible) — 2 tools |
| `/api/health` | Application health checks |
| `/metrics` | Prometheus-compatible metrics (IP-restricted via `InternalNetworkFilter`) |

## 1. Configuration — the `.env` file

The stack reads its configuration from a single `.env` file in the deployment
directory; Docker Compose loads it both for variable substitution and as the
`wikantik` service's `env_file`. The container `entrypoint.sh` renders
`wikantik-custom.properties`, `ROOT.xml`, and `wikantik-mcp.properties` from
these variables **on every start** — so configuration changes mean editing
`.env` and redeploying, never editing files inside a running container.

Copy `.env.example` and fill it in. Key variables:

| Variable | Purpose |
|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | Container Postgres credentials. `POSTGRES_PASSWORD` must not be left at `CHANGEME`. |
| `WIKANTIK_BASE_URL` | External base URL — sitemap, canonical links, IndexNow. |
| `WIKANTIK_PAGES_DIR` | **Host** path bind-mounted as the page tree (prod overlay). |
| `BACKUP_DIR` / `BACKUP_RETENTION_DAYS` | Host backup path + retention for the sidecar. |
| `MCP_ACCESS_KEYS` | Comma-separated MCP bearer keys (DB-backed `api_keys` also work). |
| `MAIL_SMTP_*` / `MAIL_FROM` | SMTP; leave `MAIL_SMTP_HOST` empty to disable email. |
| `WIKANTIK_HOST_PORT` | Published host port (default `8080`). |

### Performance / search-backend tuning (optional)

All have safe defaults. Most are baked into `ini/wikantik.properties` and the
container `entrypoint.sh` injects them into `wikantik-custom.properties` on
start; one (`WIKANTIK_MAX_INFLIGHT_REQUESTS`) is read directly from the
environment by a servlet filter. Either way, set them in `.env` only when you
want to override. All are runtime-configurable without an image rebuild —
flip in `.env`, then `bin/remote.sh deploy --skip-build` for a ~30 s restart.

| Variable | Default | Maps to | Purpose |
|---|---|---|---|
| `WIKANTIK_DENSE_BACKEND` | `lucene-hnsw` | `wikantik.search.dense.backend` | `lucene-hnsw`, `inmemory`, or `pgvector`. `lucene-hnsw` (the default since 2.3.1) is in-process Lucene HNSW ANN — it replaced the O(N) brute-force scan that was ~60 % of search CPU on docker1; `inmemory` is the exact brute-force scan (with Vector API SIMD), fine for dev/small corpora and the rollback; `pgvector` keeps the index server-side and becomes the win when you split the DB to its own host. See [ScalingCharacterization.md](ScalingCharacterization.md) for the dense-backend trade-offs. |
| `WIKANTIK_DENSE_EF_SEARCH` | `100` | `wikantik.search.dense.pgvector.ef_search` | Only used when `WIKANTIK_DENSE_BACKEND=pgvector`. HNSW recall/latency knob; higher = better recall, more CPU. |
| `WIKANTIK_LUCENE_DIRECTORY` | `nio` | `wikantik.search.lucene.directory.kind` | Lucene index backend: `nio` (NIOFSDirectory — read syscall + buffer copy) or `mmap` (MMapDirectory — page-cache-served, but pays the JVM's per-access MemorySession overhead under high concurrency). On hardware where the OS page cache already serves Lucene reads, `nio` wins; on disk-bound deploys, `mmap` is Lucene's recommended default. |
| `WIKANTIK_VERSIONING_CACHE_SIZE` | `100` | `wikantik.versioningFileProvider.cacheSize` | Page-properties cache size in `VersioningFileProvider`. Default `100` was small relative to a 12K-page corpus (load testing showed 56 % hit rate). Set to `5000` for a typical wiki (~5 MB heap cost, 99 %+ hit rate). `0` = single-entry, `-1` = disabled. |
| `WIKANTIK_MAX_INFLIGHT_REQUESTS` | `390` | `BackpressureFilter` (read directly from env, not a property) | **Graceful-degradation backpressure cap.** Maximum concurrent in-flight HTTP requests; anything over the cap gets an immediate `503 Service Unavailable` + `Retry-After: 1` instead of queueing in the Tomcat accept queue for up to 60 s. Default `390` — must stay below Tomcat `maxThreads` (400) or the cap can never fire. Set to `0` or negative to disable (pass-through). `/api/health` and `/metrics` bypass the cap entirely and are never rejected — a 503 there would make jakemon think the container is down. Deterministic by construction (rejection driven solely by the local in-flight counter — no latency window, no false alarms). See the [verification overload test](#fail-fast-backpressure) below and the metrics it exposes. |
| `WIKANTIK_RATELIMIT_DEFAULT_PERCLIENT` | `25` | `RateLimitFilter` (read directly from env) | **Public-surface per-IP rate limit — default tier** (requests/second per client IP) for `/api/*`, `/id/*`, `/export/*`. Distinct from backpressure: this sheds by *rate*, backpressure by *concurrency*. Over the limit → `429` + `Retry-After: 1`. Client IP is resolved via `RemoteIpValve` (`CF-Connecting-IP`). Setting both per-client limits ≤ 0 disables the filter. |
| `WIKANTIK_RATELIMIT_EXPENSIVE_PERCLIENT` | `3` | `RateLimitFilter` (read directly from env) | Per-IP rate limit (req/s) for the **expensive tier** — the compute-heavy paths in `WIKANTIK_RATELIMIT_EXPENSIVE_PATHS`. |
| `WIKANTIK_RATELIMIT_EXPENSIVE_GLOBAL` | `10` | `RateLimitFilter` (read directly from env) | Single-host **global** cap (req/s across all clients) for the expensive tier, so one client can't monopolise retrieval/ontology CPU. |
| `WIKANTIK_RATELIMIT_EXPENSIVE_PATHS` | `/api/bundle,/api/search,/sparql` | `RateLimitFilter` (read directly from env) | CSV path prefixes routed to the expensive tier. |
| `WIKANTIK_RATELIMIT_EXEMPT_CIDRS` | *(empty)* | `RateLimitFilter` (read directly from env) | CSV IPv4 CIDRs exempt from all rate limits. Loopback is always exempt and `/api/health` is never limited. |
| `WIKANTIK_COOKIE_AUTHENTICATION` | `false` | `wikantik.cookieAuthentication` | Enable remember-me re-auth: a successful login issues a `Lax`, `httpOnly`, scheme-`Secure` cookie so sessions survive restarts/timeouts without logging the user out. Default `false` (conservative). |
| `WIKANTIK_SSO_ENABLED` | `false` | `wikantik.sso.enabled` | Enable Single Sign-On. Set `true` to activate the SSO block below. |
| `WIKANTIK_SSO_TYPE` | `oidc` | `wikantik.sso.type` | `oidc` \| `saml` \| `both` |
| `WIKANTIK_SSO_OIDC_DISCOVERY_URI` | *(none)* | `wikantik.sso.oidc.discoveryUri` | Provider OIDC discovery URL, e.g. `https://accounts.google.com/.well-known/openid-configuration`. |
| `WIKANTIK_SSO_OIDC_CLIENT_ID` | *(none)* | `wikantik.sso.oidc.clientId` | OAuth client id from the provider console. |
| `WIKANTIK_SSO_OIDC_CLIENT_SECRET` | *(none)* | `wikantik.sso.oidc.clientSecret` | OAuth client secret — keep in `.env.prod`, never in git. |
| `WIKANTIK_SSO_OIDC_SCOPE` | `openid profile email` | `wikantik.sso.oidc.scope` | OAuth scope string. |
| `WIKANTIK_SSO_IDENTITY_CLAIM` | `sub` | `wikantik.sso.identityClaim` | IdP claim used as the stable identity key. Set to `preferred_username` only to deliberately trust a mutable claim. |
| `WIKANTIK_SSO_AUTO_PROVISION` | `true` | `wikantik.sso.autoProvision` | Auto-create a local profile on first SSO login. |
| `WIKANTIK_SSO_CLAIM_LOGIN_NAME` | `preferred_username` | `wikantik.sso.claimMapping.loginName` | IdP claim mapped to the wiki login name. Google sends no `preferred_username` — set to `email` for Google OIDC. |
| `WIKANTIK_SSO_CLAIM_FULL_NAME` | `name` | `wikantik.sso.claimMapping.fullName` | IdP claim mapped to the display name. |
| `WIKANTIK_SSO_CLAIM_EMAIL` | `email` | `wikantik.sso.claimMapping.email` | IdP claim mapped to email. |
| `WIKANTIK_GENAI_MODE` | *(ini default `full`)* | `wikantik.genai.mode` | GenAI ceiling: `full` \| `embeddings-only` \| `none`. Never turns a feature on — only forces an already-enabled feature off. See [CostTiers.md](CostTiers.md) and [CloudDeployment.md](CloudDeployment.md). |
| `WIKANTIK_KNOWLEDGE_ENABLED` | `true` when unset | `wikantik.knowledge.enabled` | `false` skips constructing the Knowledge Graph subsystem entirely (no KG services, no KG MCP tools; `/admin/knowledge-graph/*` and `/api/page-knowledge/*` 503). Chunking and the embedding/dense-retrieval pipeline stay independent of it. |
| `WIKANTIK_EMBEDDING_BASE_URL` | *(ini default, the shared inference host)* | `wikantik.search.embedding.base-url` | Dense-retrieval embedding service base URL override, e.g. `http://ollama-embed:11434` for the cloud overlay's sidecar. |
| `WIKANTIK_EXTRACTOR_BACKEND` | `ollama` | `wikantik.knowledge.extractor.backend` | KG entity-extractor backend: `ollama` \| `claude` \| `disabled`. `claude` also requires `ANTHROPIC_API_KEY` (read directly from the process environment by `EntityExtractorFactory` — never rendered into a properties file). |
| `WIKANTIK_SCIM_TOKEN` | *(none — SCIM denies all requests)* | JVM system property `wikantik.scim.token` (not `wikantik-custom.properties`) | SCIM bearer token, sensitive. Injected via `CATALINA_OPTS -D`, not the properties file, because `ScimAccessFilter` reads it exclusively as a system property. Must match `[A-Za-z0-9+/=_-]+` (hex/base64 only) — the entrypoint refuses to boot on a violating value (`catalina.sh` word-splits `CATALINA_OPTS`, so spaces/shell metacharacters would silently corrupt it). Generate with `openssl rand -hex 32`. |
| `WIKANTIK_CONNECTORS_CRYPTO_KEY` | *(none — credential store stays disabled)* | `wikantik.connectors.crypto.key` | Base64-encoded 32-byte AES-256 key for the connector credential store (GitHub token / Confluence API token / Google Drive client secret+refresh token at rest). |
| `PROXY_REMOTE_IP_HEADER` | `CF-Connecting-IP` | JVM system property `wikantik.proxy.remoteIpHeader`, consumed by `RemoteIpValve` in `docker/config/server.xml` | Reverse-proxy header carrying the real client IP. **Always injected**, unconditionally — Tomcat's `${...}` substitution has no default-value syntax, so an unset property would leave the literal `${wikantik.proxy.remoteIpHeader}` string as the header name and silently break client-IP resolution. Set to `X-Forwarded-For` for Caddy/nginx/ALB/GCLB (e.g. the cloud overlay's `caddy` ingress profile); leave at the default for Cloudflare-fronted deployments (docker1, or the cloud overlay's `cloudflared` profile). Must match `[A-Za-z0-9-]+` — the entrypoint refuses to boot on a violating value. |
| `WIKANTIK_INDEXNOW_API_KEY` | *(empty — IndexNow disabled)* | `wikantik.indexnow.apiKey` | IndexNow verification key for `ping_search_engines` (Bing/Yandex). The same value must be served publicly at `<baseURL>/<key>.txt`. |
| `MCP_ACCESS_KEYS` | *(none)* | `mcp.access.keys` in `wikantik-mcp.properties` | Comma-separated MCP bearer keys. DB-backed `api_keys` also work. |
| `MCP_USERS` | `curator` | *(env only — for compose healthcheck context)* | Informational; records which user accounts are expected MCP callers. |
| `MCP_RATE_LIMIT_GLOBAL` | `100` | `mcp.ratelimit.global` | Global request-per-minute cap across all MCP clients. |
| `MCP_RATE_LIMIT_PER_CLIENT` | `10` | `mcp.ratelimit.perClient` | Per-client request-per-minute cap. |
| `DB_HOST_BIND` | `172.17.0.1` | `docker-compose.prod.yml` port binding | Host interface PostgreSQL is published on (prod overlay only) — the docker0 bridge gateway by default, keeping the DB off the LAN. Set to `0.0.0.0` only to expose LAN-wide. |
| `DB_EXPORTER_PASSWORD` | *(none)* | passed to V031 via `migrate.sh` as `:exporter_password` | Password for the `wikantik_exporter` monitoring role created by migration V031. Required in prod to let the jakemon postgres-exporter authenticate. |
| `WIKANTIK_SEED_DEV_USERS` | `false` | entrypoint only | Set `true` to ensure the default admin (admin/admin123, must-change-on-first-login) exists on start (via `bin/db/seed-users.sql`). Fresh databases get the same flagged admin from migrations V002+V039 regardless. **Never set in production.** |

#### Base URL — four properties, one env var

`WIKANTIK_BASE_URL` (→ `wikantik.baseURL`) is the only base-URL property the
entrypoint sets from the environment (`docker/entrypoint.sh`). Three sibling
properties feed *different* subsystems and have **no** env var — a
reverse-proxy deploy that sets only `WIKANTIK_BASE_URL` can still advertise
the wrong origin from the Atom feed and the `/tools/*` OpenAPI spec.

| Property | Feeds | Env var |
|---|---|---|
| `wikantik.baseURL` | SSO callback URL, connector OAuth `redirect_uri`, admin-mcp | `WIKANTIK_BASE_URL` |
| `wikantik.sitemap.baseURL` | `/sitemap.xml` entry URLs | *(none)* |
| `wikantik.feed.baseURL` | `/feed.xml` Atom entry links | *(none)* |
| `wikantik.public.baseURL` | `/tools/*` OpenAPI server URL + tool-response citation links | *(none)* |

Left unset, the last three each fall back to deriving the origin from the
current request — fine for a single simple public hostname, but silently
wrong behind SSL-terminating or path-rewriting proxies (the documented
symptom for the sitemap: URLs come out `http://` instead of `https://`). Set
all four explicitly, via a `wikantik-custom.properties` override (§1), for
any deployment more complex than "one public hostname, no path rewriting."

#### Cross-origin requests (CORS)

`wikantik.cors.allowedOrigins` — comma-separated allow-list of browser
`Origin` values permitted to make cross-origin state-changing/API requests
(`CsrfProtectionFilter`, `RestServletBase.setCorsHeaders`). A request whose
`Origin` matches the list gets the CORS headers echoed back;
`Access-Control-Allow-Credentials` is **never** set, so the session cookie
stays same-origin regardless of this setting. Not wired to an env var — set
it via a `wikantik-custom.properties` override. Empty (the default) allows no
cross-origin callers.

#### Fail-fast backpressure

When a burst pushes concurrency past `WIKANTIK_MAX_INFLIGHT_REQUESTS`, the
`BackpressureFilter` sheds the excess as fast 503s rather than letting requests
pile up in the queue. This keeps the *admitted* requests responsive (in a
verification overload at cap=100 / N=650, the served subset held p95 ≈ 625 ms
— faster than an uncapped run at the same load, because the server stays
under-subscribed) and keeps `/api/health` answering throughout (200 in ~38 ms
under full overload).

It publishes three Prometheus metrics:

| Metric | Type | Meaning |
|---|---|---|
| `wikantik_backpressure_rejected_total` | counter | Requests 503'd by the cap. Watch the rate to see how often you're hitting capacity. A non-zero-and-climbing rate under normal load means the cap is too low (or the host needs more capacity). |
| `wikantik_backpressure_inflight` | gauge | Requests currently holding a permit. Pins near `permits_max` under saturation. |
| `wikantik_backpressure_permits_max` | gauge | The configured cap, for dashboard context. |

To exercise it deliberately, set a low cap and overload:

```bash
# Temporarily set a low cap so the overload bites at a modest VU count.
sed -i 's/^WIKANTIK_MAX_INFLIGHT_REQUESTS=.*/WIKANTIK_MAX_INFLIGHT_REQUESTS=100/' .env.prod
bin/remote.sh deploy --skip-build

# Overload it; watch the rejection counter climb while /api/health stays 200.
bin/loadtest.sh smoke --duration 3m --vus 650
curl -s http://<host>:8080/metrics | grep wikantik_backpressure

# Restore the production cap.
sed -i 's/^WIKANTIK_MAX_INFLIGHT_REQUESTS=.*/WIKANTIK_MAX_INFLIGHT_REQUESTS=390/' .env.prod
bin/remote.sh deploy --skip-build
```

For a remote deploy driven by `bin/remote.sh`, keep the production values in a
gitignored **`.env.prod`** at the repo root — `remote.sh` ships it to the
remote as `.env`, preferring it over the dev `.env` so a prod deploy never
disturbs local-dev config.

Knowledge-Graph / hybrid-retrieval backends (Ollama endpoint, model tags) are
*not* set by the entrypoint — they fall back to the baked-in defaults in
`ini/wikantik.properties` (`ollama` backend at `inference.jakefear.com:11434`).
The container needs network reach to that endpoint, or a `wikantik-custom.properties`
override, only if KG / hybrid retrieval is in use.

## 2. Data persistence

Three classes of state, with deliberately different storage:

- **PostgreSQL** (users, groups, policy grants, Knowledge Graph, API keys,
  page metadata, history) — the `db` service, in the named volume
  `<project>_pgdata`.
- **Pages + attachments** — bind-mounted from the host (`WIKANTIK_PAGES_DIR`)
  under the prod overlay, so `rsync` is the source of truth for the page tree
  independent of container lifecycle. Mounted at `/var/wikantik/pages`.
- **Work + logs** — named volumes (`wikantik-work`, `wikantik-logs`);
  regeneratable, no operator interest in their contents.
- **JFR profiling recordings** — named volume `wikantik-profiling` (prod
  overlay only), mounted at `/var/wikantik/profiling`. JFR recordings started
  via `POST /admin/profiling/jfr/start` land here; download them with
  `GET /admin/profiling/jfr/recordings/{id}`. Non-critical and not backed up.

Both the DB volume and the page bind-mount **survive container replacement**,
which is what makes a release upgrade a plain image swap (§3).

> **PostgreSQL 18+ data directory.** The `db` service uses
> `pgvector/pgvector:pg18`. From pg18 the official Postgres Docker images
> store the cluster under a version-specific subdirectory and expect the data
> volume mounted at `/var/lib/postgresql` — **not** the older
> `/var/lib/postgresql/data`. Mounting the old path makes the image refuse to
> start. If you bump the Postgres major version, re-check this mount path and
> the backup sidecar image (`pg_dump` must be ≥ the server version). Keep the
> container's Postgres major version in step with your local dev Postgres:
> `pg_dump` restores forward across versions, not backward.

## 3. Deploying

### Local / single-host

`bin/container.sh` wraps `docker compose` over the four compose files:

```bash
bin/container.sh -e prod up -d      # base + docker-compose.prod.yml
bin/container.sh logs -f
bin/container.sh -e prod down
```

Environments (passed via `-e`):

| Env | Compose files | What it adds |
|-----|--------------|--------------|
| `dev` (default) | base + `docker-compose.dev.yml` | DB on host port 15432; JDWP debug port 5005; uses `Dockerfile.dev` for hot-swap (bind-mounts `wikantik-war/target/Wikantik.war` into the container); pages mounted from `docs/wikantik-pages/` |
| `prod` | base + `docker-compose.prod.yml` | Backup sidecar; host bind-mount for pages (`WIKANTIK_PAGES_DIR`); resource limits (2G); `wikantik-profiling` volume; DB published on `${DB_HOST_BIND:-172.17.0.1}:5432` for jakemon; `start_period 90s` healthcheck |
| `test` | base + `docker-compose.test.yml`, project `wikantik-test` | Alt ports (wikantik on 18080 via `WIKANTIK_HOST_PORT`, DB on 15432); own namespaced containers + volumes so `down -v` never touches dev/prod state; used by `smoke-test` subcommand |
| `base` | base only | No overlays — useful for debugging compose variable substitution in isolation |

The base compose healthcheck `start_period` is **60 s**; the prod overlay raises it to **90 s** to accommodate migration time on a cold start.

### Running migrations without a restart

`bin/container.sh migrate` runs `migrate.sh` inside the live `wikantik` container without restarting Tomcat — useful when a new `V*.sql` is added during a long-running deployment:

```bash
bin/container.sh -e prod migrate            # apply pending migrations
bin/container.sh -e prod migrate --status   # list applied versions without applying
```

The entrypoint also runs migrations automatically on every container start, so a routine redeploy doesn't need a manual migration step.

### Remote host — first deploy

`bin/remote.sh` drives a remote Docker host over ssh. Configuration:
`remote.env` (ssh user/host + remote paths — copy from `remote.env.example`)
and a gitignored `.env.prod` (container config, §1).

The deploying OS user on the remote **must be in the `docker` group** —
`sudo usermod -aG docker <user>`, then a fresh login. `bin/remote.sh
bootstrap` verifies both that Docker is installed and that the daemon is
reachable, and tells you this fix if not.

> **Restoring from a backup snapshot (DR / clone)?** If this fresh host is
> being stood up *from a backup* rather than seeded empty, use
> `bin/dr-restore.sh <host>` instead of the manual steps below — it automates
> image + snapshot transfer, the DB restore, and a smoke test. See
> [BackupAndRecovery.md §5.1](BackupAndRecovery.md). The manual sequence here is
> for a from-scratch first deploy.

The first deploy initialises state, so it is a manual sequence — *not*
`remote.sh deploy`, which does a full `up -d` that would migrate an empty
schema:

```bash
# 1. Verify Docker + create remote dirs + ship compose/scripts/.env
bin/remote.sh bootstrap

# 2. Transfer the released image (user chose SSH transfer over a GHCR pull)
docker pull ghcr.io/jakefearsd/wikantik:X.Y.Z
docker tag  ghcr.io/jakefearsd/wikantik:X.Y.Z wikantik:latest
docker save wikantik:latest | ssh REMOTE_HOST 'docker load'

# 3. Start ONLY the database
bin/remote.sh up -d db

# 4. Initialise the DB from a dump — see §5

# 5. Push the page tree
bin/remote.sh pages-push docs/wikantik-pages

# 6. Start the app + backup sidecar (entrypoint migrates the restored schema)
bin/remote.sh up -d

# 7. Verify
curl http://REMOTE_HOST:8080/api/health
```

### Remote host — routine release upgrades

Once a host is running, every later upgrade is an image swap: the DB volume
and the page bind-mount persist, and the entrypoint applies any pending
schema migrations on start. Two wrappers capture the happy path as one
`bash` run each:

```bash
# Cut the release (run a green build first — the script does not build).
# Pushing the tag triggers .github/workflows/release.yml, which builds and
# publishes ghcr.io/jakefearsd/wikantik:X.Y.Z + creates the GitHub Release.
bin/cut-release.sh X.Y.Z

# Once release.yml is green, deploy it: pull the image, then
# bin/remote.sh deploy --skip-build  (tag :rollback → save|load → up -d →
# health-poll → auto-rollback on failure).
bin/deploy-release.sh X.Y.Z
```

`bin/remote.sh rollback` re-promotes the previous image at any time. The one
case that is **not** a plain image swap is a Postgres major-version change
(e.g. pg18→pg19) — that needs a dump/restore or `pg_upgrade`.

### Backups

The prod overlay runs a `postgres:18-alpine` backup sidecar: scheduled
`pg_dump` + `pages.tar.gz` into `${BACKUP_DIR}`, tiered retention (daily /
weekly / monthly; 30-day daily default). The procedure is encoded in
`docker/backup/backup.sh` + `docker/backup/restore.sh`.

```bash
bin/remote.sh backup-trigger [daily|weekly|monthly]   # ad-hoc backup
bin/remote.sh backup-pull [DATE]                      # fetch a snapshot locally
bin/remote.sh restore REMOTE_PATH                     # sidecar restore + restart
```

> **Full backup & recovery guide** — topology diagram, 3-2-1 model, NAS off-box pull setup,
> restore procedure, quarterly restore drill, and jakemon alert expressions:
> **[docs/BackupAndRecovery.md](BackupAndRecovery.md)**

### Monitoring

Monitoring is handled by the external **jakemon** stack — a Grafana Alloy agent on each host pushing metrics and logs to a central Prometheus + Loki + Grafana on host `inference`. The wikantik container exposes `/metrics`, which jakemon scrapes. There is no in-repo observability stack.

## 4. External services

The compose stack bundles PostgreSQL + pgvector; everything else is external:

| Service | Status | Endpoint |
|---|---|---|
| **PostgreSQL + pgvector** | Bundled (`db` service) | `db:5432` (internal) |
| **Ollama** (embeddings, extraction, judge) | External, optional | `ini/wikantik.properties` default `http://inference.jakefear.com:11434` |
| **Anthropic API** (alternative extractor backend) | External, optional | `ANTHROPIC_API_KEY` env var |
| **TLS terminator / reverse proxy** | External, optional | `RemoteIpValve` reads the client IP from `PROXY_REMOTE_IP_HEADER` (default `CF-Connecting-IP`; set to `X-Forwarded-For` for Caddy/nginx/ALB/GCLB — see the env-var table in §1) |

The baked-in default for `wikantik.search.embedding.backend` and
`wikantik.knowledge.extractor.backend` is `ollama` — the container must reach
the Ollama endpoint for KG / hybrid-retrieval features. Set both to `disabled`
(via a `wikantik-custom.properties` override) if you do not run those.

## 5. Coexisting bare-metal and container stacks

The two install paths can run side by side on one machine. The base
`docker-compose.yml` reads `WIKANTIK_HOST_PORT` (default `8080`), and
`docker-compose.test.yml` publishes on `18080:8080` + `15432:5432`:

```bash
WIKANTIK_HOST_PORT=18080 docker compose \
    -f docker-compose.yml -f docker-compose.test.yml \
    -p wikantik-test up -d --build
```

`-p wikantik-test` namespaces all volumes (`wikantik-test_pgdata`, …) so
prod / dev state is untouched. Tear down with `… -p wikantik-test down -v`.

`bin/container.sh -e test up -d` and `bin/container.sh smoke-test` are
equivalent shorthand — both run base + test overlay under `-p wikantik-test`
with `WIKANTIK_HOST_PORT` defaulting to `18080`.

## 6. Initialising the database from an existing dump

Used by the first remote deploy (§3 step 4), and for migrating a bare-metal
install into a container.

1. **Dump the source DB.** Match the DB name/user from the source
   `ROOT.xml` (or `.env`):
   ```bash
   PGPASSWORD=… pg_dump -h SRC_HOST -U SRC_USER -d SRC_DB \
       --no-owner --no-privileges -f /tmp/wikantik-dump.sql
   ```
   Use a `pg_dump` whose version is ≥ the source server. The container DB
   must be the **same Postgres major version** as the source (restores go
   forward, not backward).
2. **Start only the `db` container** so the app never connects to a
   half-initialised schema: `bin/remote.sh up -d db` (or
   `bin/container.sh -e prod up -d db` locally). Wait for it to report
   healthy.
3. **Clear the image's seed schema.** `docker/db/001-init.sql` seeds a
   skeleton `users`/`roles`/`groups` schema on first DB init; drop it so the
   restore is clean:
   ```bash
   docker exec -i <db-container> psql -U $POSTGRES_USER -d $POSTGRES_DB \
     -v ON_ERROR_STOP=1 -c \
     "DROP SCHEMA public CASCADE; CREATE SCHEMA public;
      GRANT ALL ON SCHEMA public TO $POSTGRES_USER;
      GRANT ALL ON SCHEMA public TO public;"
   ```
4. **Restore the dump:**
   ```bash
   cat /tmp/wikantik-dump.sql | docker exec -i <db-container> \
     psql -U $POSTGRES_USER -d $POSTGRES_DB -v ON_ERROR_STOP=1 -q
   ```
   The dump recreates the `vector` extension, all tables, data, and the
   populated `schema_migrations` table.
5. **Push the pages** (`bin/remote.sh pages-push docs/wikantik-pages`) and
   **start the app** (`bin/remote.sh up -d`). The entrypoint's `migrate.sh`
   sees `schema_migrations` already populated and applies only genuinely new
   migrations.
6. **Verify:** `curl http://HOST:8080/api/health` → `status: UP`; log in;
   query a known page.

The reverse (container → bare-metal) is a sidecar backup
(`bin/remote.sh backup-trigger`) restored with standard `psql` + `tar`
against the bare-metal install.
