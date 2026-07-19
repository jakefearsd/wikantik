# Wikantik Deployment on AWS / GCP

How to run Wikantik on a cloud VM — AWS EC2 or GCP Compute Engine — as a
single-instance Docker Compose stack, with a GHCR-published image, a
cost-conscious GenAI tier, and pull-based updates. This is the **cloud
reference deployment**; it reuses the same compose stack and container image
as the on-prem path.

> For the **docker1-style** path (build-locally, ssh-push, host bind mounts,
> jakemon monitoring), see [DockerDeployment.md](DockerDeployment.md) instead.
> For the **bare-metal** path (local PostgreSQL + Tomcat 11), see
> [PostgreSQLLocalDeployment.md](PostgreSQLLocalDeployment.md).

This guide is the operator-facing overview tying the pieces together. The
Terraform modules themselves are the canonical step-by-step procedure — see
[deploy/aws/README.md](../deploy/aws/README.md) and
[deploy/gcp/README.md](../deploy/gcp/README.md).

## Why a separate path from docker1

Wikantik is a single-instance app by design — filesystem page storage,
in-JVM locks, an in-RAM Lucene HNSW index — so the cloud reference
deployment is deliberately **one VM + Docker Compose**, not ECS/GKE/a
multi-AZ topology. What's different from docker1 is what a stranger with a
cloud account and no access to Jake's LAN/NAS/inference host needs instead:
a registry image (no local build step), a cost-bounded GenAI tier so
inference spend is opt-in, Terraform to stand up the VM/disk/firewall/
secrets, and a pull-based update flow that works without ssh-streaming a
multi-GB image.

## Topology

```
Terraform (deploy/aws/ or deploy/gcp/)
  → one VM (EC2 / GCE) + one persistent data disk + daily snapshots
  → cloud-init: mount disk, relocate Docker's data-root onto it, install
    Docker, fetch secrets from SSM/Secret Manager, docker login to GHCR,
    docker compose up -d
  → docker-compose.yml + docker-compose.cloud.yml
      wikantik (pulled from ghcr.io/jakefearsd/wikantik:<tag>)
      db (bundled pgvector, or a managed endpoint)
      backup (pg_dump + pages tarball, daily/weekly/monthly)
      caddy | cloudflared (ingress, pick one)
      ollama-embed (optional CPU embedding sidecar)
```

The shared cloud-init template is
[deploy/cloud-init/cloud-init.yaml.tftpl](../deploy/cloud-init/cloud-init.yaml.tftpl) —
identical orchestration logic on both clouds; only the secret-fetch
mechanism and the data-device path are cloud-specific template variables
(AWS: SSM Parameter Store + an IMDSv2 region lookup + an NVMe by-id device
path; GCP: Secret Manager over the metadata-token curl pattern + a
`google-<disk-name>` by-id path). Both Terraform modules relocate Docker's
**entire data-root** onto the persistent volume (`/srv/wikantik/docker` via
`/etc/docker/daemon.json`) before Docker's first-ever start — that's what
makes every compose-managed named volume (the page tree, `pgdata`, TLS
certs, the embedding model cache) survive instance replacement, not just
the nightly backup tarball.

## GenAI cost tiers

Inference spend is bounded by a single ceiling property,
**`wikantik.genai.mode`** (`full` | `embeddings-only` | `none`,
`com.wikantik.api.config.GenAiMode`), plus the independent
**`wikantik.knowledge.enabled`** subsystem flag. Both Terraform modules
expose a `tier` variable (`core` | `search` | `knowledge`) that sets the
matching `.env` preset and compose `--profile` flags:

| Tier | `WIKANTIK_GENAI_MODE` | `WIKANTIK_KNOWLEDGE_ENABLED` | Extra `.env` | Compose profile added |
|---|---|---|---|---|
| `core` | `none` | `false` | — | — (BM25-only, zero inference infra) |
| `search` | `embeddings-only` | `false` | `WIKANTIK_EMBEDDING_BASE_URL=http://ollama-embed:11434` | `embeddings` |
| `knowledge` | `full` | `true` | `WIKANTIK_EXTRACTOR_BACKEND=claude`, `WIKANTIK_EMBEDDING_BASE_URL=...` (+ `ANTHROPIC_API_KEY` from `secrets`) | `embeddings` |

`wikantik.genai.mode` is a **ceiling**, not a switch — it never turns a
feature on, it only forces an already-enabled feature off when the mode
disallows it (e.g. `core`'s `mode=none` forces the embedding client
disabled regardless of `wikantik.search.hybrid.enabled`'s own default-true
value). `wikantik.knowledge.enabled=false` skips constructing the Knowledge
Graph subsystem entirely — no KG services, no KG MCP tools, `/admin/knowledge-graph/*`
and `/api/page-knowledge/*` 503 naming the flag — but chunking and the
embedding/dense-retrieval pipeline stay independent of it and keep running.

Full detail — exact env vars, `wikantik-custom.properties` equivalents for
the bare-metal path, how to verify a tier is actually enforced
(`/admin/llm-activity`, warn-log lines) — lives in
**[docs/CostTiers.md](CostTiers.md)**. Don't duplicate that reasoning here;
this page only covers how the cloud Terraform/compose surface sets the tier.

### `GET /api/capabilities` reflects the effective state

The anonymous `GET /api/capabilities` endpoint
(`com.wikantik.rest.CapabilitiesResource`) is what the React SPA reads to
gate its own navigation before login. Most fields are raw property
pass-throughs (`knowledgeGraph`, `ontology`, `connectors`, `citations`), but
**`hybridSearch` is ceiling-adjusted** — the raw `wikantik.search.hybrid.enabled`
flag ANDed with `mode.allowsEmbeddings()` — so a `core`-tier deployment
reports `hybridSearch: false` even though the underlying property still
defaults to `true`. Use this endpoint to confirm what a tier actually did,
rather than inferring it from `.env` alone.

## The cloud compose overlay

`docker-compose.cloud.yml` is layered on top of the base
`docker-compose.yml` (never edited — `docker-compose.prod.yml`, docker1's
overlay, is untouched by any of this):

```bash
docker compose -f docker-compose.yml -f docker-compose.cloud.yml \
               --profile caddy --profile embeddings --profile bundled-db \
               up -d
```

It switches the `wikantik` service from a local `build:` to a registry
`image:` (`WIKANTIK_IMAGE`, mandatory), adds a memory limit
(`WIKANTIK_MEM_LIMIT`, default `2G`), and gates four optional pieces behind
compose `--profile` flags:

| Profile | Adds | Notes |
|---|---|---|
| `caddy` | `caddy:2-alpine` reverse proxy on 80/443 | Let's Encrypt for a real `WIKANTIK_DOMAIN`, or an internal cert when `WIKANTIK_DOMAIN=localhost`. Requires `PROXY_REMOTE_IP_HEADER=X-Forwarded-For` in `.env` (Caddy's `reverse_proxy` sets that header by default — see [deploy/config/Caddyfile](../deploy/config/Caddyfile)). |
| `cloudflared` | `cloudflare/cloudflared:latest` tunnel sidecar | No public ports needed; leave `PROXY_REMOTE_IP_HEADER` unset so it stays at the default `CF-Connecting-IP`. Requires `CLOUDFLARE_TUNNEL_TOKEN`. |
| `bundled-db` | `pgvector/pgvector:pg18` container, named volume, no host port | Omit for a managed database (RDS/Cloud SQL) — point `WIKANTIK_CLOUD_DB_HOST`/`WIKANTIK_CLOUD_DB_PORT` at the managed endpoint instead. These are **dedicated cloud vars**, not the shared `POSTGRES_HOST` (which `.env.example` sets to `localhost` for the bare-metal path). |
| `embeddings` | `ollama-embed` — CPU-only Ollama sidecar | Pulls `WIKANTIK_EMBEDDING_MODEL_TAG` (default `qwen3-embedding:0.6b`) on start; point `WIKANTIK_EMBEDDING_BASE_URL=http://ollama-embed:11434` at it in `.env`. |

Pick one ingress profile (`caddy` or `cloudflared`), not both. The `backup`
sidecar (`postgres:18-alpine`, `pg_dump` + pages tarball) is included
unconditionally, matching docker1's posture, and writes its Prometheus
textfile metrics to a named volume rather than a jakemon-specific host
path (jakemon isn't part of the cloud v1 topology).

Both Terraform modules set `bundled-db` and the ingress profile
automatically from `var.tier`/`var.ingress`; `search`/`knowledge` tiers also
add `embeddings`. A managed-DB Terraform variant is future work (not yet
shipped) — for now, omit `bundled-db` and set the `WIKANTIK_CLOUD_DB_*` vars
by hand if you're pointing at RDS/Cloud SQL.

## GHCR image

`.github/workflows/release.yml` publishes
`ghcr.io/jakefearsd/wikantik:{version,latest}` on every tag push (multi-arch).
The image is **private** — set `GHCR_USER`/`GHCR_TOKEN` (a GitHub PAT with
`read:packages`, or a fine-grained token scoped to the package) so
cloud-init's `docker login ghcr.io` can pull it. Pin an explicit tag in
`WIKANTIK_IMAGE`/`wikantik_image` (e.g. `ghcr.io/jakefearsd/wikantik:2.3.7`)
rather than `:latest`, so `terraform apply` stays reproducible.

## Terraform quickstart

Full walkthroughs, variable tables, cost estimates, persistence model,
restore/teardown procedures, and validation status live in the module
READMEs — this is only the shape of it:

```bash
cd deploy/aws   # or deploy/gcp
terraform init
terraform apply \
  -var 'admin_cidr=203.0.113.4/32' \
  -var 'domain=wiki.example.com' \
  -var 'ghcr_user=your-github-username' \
  -var 'ghcr_token=ghp_xxx' \
  -var 'wikantik_image=ghcr.io/jakefearsd/wikantik:2.3.7' \
  -var 'tier=search' \
  -var 'secrets={"POSTGRES_PASSWORD":"a-real-password"}'
  # AWS also wants -var 'ssh_key_name=...'; GCP wants -var 'project_id=...'
  #   and -var 'ssh_public_key=...'
```

Each module is deliberately minimal — one VM, one persistent data
disk/volume with daily snapshots, a security group/firewall, a static IP,
a secrets store (SSM Parameter Store / Secret Manager), and an optional DNS
record — all in the default VPC/network. See
**[deploy/aws/README.md](../deploy/aws/README.md)** and
**[deploy/gcp/README.md](../deploy/gcp/README.md)** for the exact variable
list, the `core`/`search`/`knowledge` instance-size and cost tables, the
persistence model (why `/srv/wikantik`), the AMI/boot-image pinning
rationale, and the restore/teardown runbooks. Secret fetching is
**fail-closed** on both clouds: a failed SSM/Secret Manager call aborts the
boot with a `FATAL:` line rather than silently falling back to compose's
`CHANGEME` default password.

## Pull-based updates

Routine tag-to-tag upgrades on a cloud VM should use **`wikantik-update`**
rather than re-running Terraform. Cloud-init installs it at
`/usr/local/bin/wikantik-update` (source:
[deploy/bin/wikantik-update.sh](../deploy/bin/wikantik-update.sh)), configured
by `/etc/wikantik-update.conf` (also written by cloud-init, with
`WIKANTIK_REPO_DIR=/opt/wikantik`, the compose files/profiles for the
tier+ingress this VM was provisioned with, and GHCR credentials):

```bash
ssh ubuntu@<vm-ip>
sudo wikantik-update 2.3.8
```

It runs on the VM itself, so there's no local build and no
`docker save | ssh docker load` transfer — the image already lives in
GHCR. Flow: acquire a non-blocking `flock` (a concurrent update fails fast
with exit 2 instead of interleaving `.env`/compose mutations) → `docker
login` if `GHCR_USER`/`GHCR_TOKEN` are set → `docker pull` the target image
→ tag the currently-running image `wikantik:rollback` → back up `.env` to
`.env.bak` and rewrite `WIKANTIK_IMAGE` → `docker compose up -d` → poll
`HEALTH_URL` (default `/api/health`) every 3s up to `HEALTH_TIMEOUT`
(default 180s). On success, exit 0. **On failure**, it restores the
previous `WIKANTIK_IMAGE` value in `.env` (captured in memory, not from the
`.env.bak` file — that file is a manual-recovery convenience only),
force-recreates just the `wikantik` service, prints the last 50 log lines,
and exits 1.

`--dry-run` prints every step without touching Docker or the filesystem —
useful for validating a config before it runs for real. Bare tags are
validated against the Docker tag grammar before any side effect; a full
image reference (containing `/`) is used as-is, so you can point one
invocation at a different registry/repo.

This mirrors `bin/remote.sh deploy --pull TAG`'s discipline for docker1
(non-blocking deploy lock, retag-then-swap, health-poll, auto-rollback) —
`remote.sh` is still the ssh-driven front end for docker1's LAN-topology
deploy; `wikantik-update` is the local-to-the-VM equivalent for a cloud
target with its own registry access. You can also drive a cloud VM through
`remote.sh` itself: `REMOTE_ENV_FILE=remote-aws.env bin/remote.sh deploy
--pull 2.3.8` rsyncs the compose files + `.env` over ssh first, then runs
the same pull-and-swap on the remote — see
[WikantikOperations.md](WikantikOperations.md) for that flow's runbook.

## Required secrets / env vars

Every Terraform `secrets` map entry becomes an SSM SecureString / Secret
Manager secret version, fetched by cloud-init into `.env` at boot, never
written to Terraform state in plaintext or baked into the AMI/instance
metadata:

| Key | Required when | Notes |
|---|---|---|
| `POSTGRES_PASSWORD` | always | Bundled pgvector container's app password. Enforced by a Terraform `validation` block on both clouds. |
| `CLOUDFLARE_TUNNEL_TOKEN` | `ingress = "cloudflared"` | Enforced by `validation`. |
| `ANTHROPIC_API_KEY` | `tier = "knowledge"` | Claude extractor backend. Enforced by `validation`. Read directly from the process environment by `EntityExtractorFactory` — never a properties-file entry. |
| `WIKANTIK_SCIM_TOKEN` | optional | SCIM bearer token. Read as a JVM system property (`-Dwikantik.scim.token`), not a properties file — pass through `secrets` if you want SCIM provisioning; absent means SCIM denies all requests. Must match `[A-Za-z0-9+/=_-]+` (hex/base64) — `docker/entrypoint.sh` refuses to boot on a violating value. |
| `WIKANTIK_CONNECTORS_CRYPTO_KEY` | optional | Base64-encoded 32-byte AES-256 key for the connector credential store (GitHub token / Confluence API token / Google Drive client secret+refresh token at rest). Absent leaves the credential store disabled. See [docs/Connectors.md](Connectors.md) for the connector framework itself. |

Extra `secrets` keys pass through unmodified as additional `.env` entries —
useful for anything else `docker/entrypoint.sh` reads from the environment.

Separately (Terraform variables, not the `secrets` map): `ghcr_user` /
`ghcr_token` (GHCR read credentials, also stored in the secrets backend),
and `admin_cidr` (SSH source CIDR — `0.0.0.0/0`/`::/0` are rejected by
Terraform validation on both clouds).

## RemoteIpValve and ingress

`docker/config/server.xml`'s `RemoteIpValve` resolves the real client IP
from a configurable header — `PROXY_REMOTE_IP_HEADER`, injected
unconditionally via `CATALINA_OPTS` by `docker/entrypoint.sh`. It defaults
to `CF-Connecting-IP` (docker1's Cloudflare-fronted topology). The `caddy`
profile's ingress needs this changed to `X-Forwarded-For` (Caddy's
`reverse_proxy` sets that header by default — see the [Caddyfile](../deploy/config/Caddyfile));
the `cloudflared` profile keeps the `CF-Connecting-IP` default. Both
Terraform modules set the right value automatically from `var.ingress`; if
you're hand-rolling `.env` for the compose overlay directly, set
`PROXY_REMOTE_IP_HEADER=X-Forwarded-For` yourself when using `caddy`.
See [DockerDeployment.md](DockerDeployment.md) for the full entrypoint
env-var reference.

## See also

- [docs/AwsAccountSetup.md](AwsAccountSetup.md) /
  [docs/GcpAccountSetup.md](GcpAccountSetup.md) /
  [docs/AzureAccountSetup.md](AzureAccountSetup.md) — preparing a
  brand-new cloud account (hardening, billing guardrails, CLI + Terraform
  installation on macOS/Ubuntu) before touching the Terraform modules;
  the Azure article also maps the reference topology onto hand-provisioned
  resources, since no `deploy/azure/` module exists yet.
- [docs/CostTiers.md](CostTiers.md) — the GenAI ceiling in full detail.
- [docs/DockerDeployment.md](DockerDeployment.md) — the docker1-style
  container path and the full entrypoint env-var table.
- [docs/production-container-architecture.md](production-container-architecture.md) —
  topology/lifecycle view (docker1-focused, cloud overlay noted).
- [docs/WikantikOperations.md](WikantikOperations.md) — operator runbooks,
  including the pull-based update flow via `bin/remote.sh --pull`.
- [docs/Connectors.md](Connectors.md) — the connector framework (uses
  `WIKANTIK_CONNECTORS_CRYPTO_KEY` above).
- [docs/superpowers/plans/2026-07-16-aws-gcp-deployment-readiness.md](superpowers/plans/2026-07-16-aws-gcp-deployment-readiness.md) —
  the decision record and phased implementation plan behind this program.
