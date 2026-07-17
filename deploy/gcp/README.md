# Wikantik on GCP — single-VM Terraform reference deployment

Deliberately minimal: one Compute Engine instance, one persistent
`pd-balanced` data disk with daily snapshots, two firewall rules, one
static external IP, Secret Manager for secrets, and an optional Cloud DNS
record — all in your project's **default network**. No GKE, no managed
instance group, no multi-zone: Wikantik is a single-instance app by design
(filesystem page storage, in-JVM locks, in-RAM search index), so a fancier
topology would just add cost and failure modes without buying you
anything.

This module (`deploy/gcp/`) is the GCP twin of `deploy/aws/` and pairs
with the same shared cloud-init template at
`deploy/cloud-init/cloud-init.yaml.tftpl`, reused **unmodified** — every
GCP-specific behavior (the Secret Manager fetch over the metadata-token
curl pattern, the by-id data-disk device path) is confined to template
variables assembled in this module's `main.tf`, exactly mirroring how
`deploy/aws/main.tf` assembles its own AWS-specific values for the same
template.

> See [docs/CloudDeployment.md](../../docs/CloudDeployment.md) for the
> operator-facing overview tying this module together with the
> `docker-compose.cloud.yml` overlay, the GenAI cost tiers, and the
> pull-based update flow. This README is the canonical step-by-step
> procedure for the GCP module specifically.

> **Validation status:** this module has been format-checked
> (`terraform fmt -check`), initialized against the real `hashicorp/google`
> provider (`terraform init -backend=false`, resolved to v7.40.0), and
> validated (`terraform validate`) — all clean. Every cross-variable
> `validation` block (admin_cidr shape/`0.0.0.0/0` rejection,
> `POSTGRES_PASSWORD` required, `cloudflared` requires a tunnel token,
> `knowledge` tier requires an Anthropic key, `caddy` requires a domain,
> tag-shaped `name_prefix`, zone-within-region) has been proven to
> hard-fail against a locals-only, zero-resource, zero-provider harness
> (see `.superpowers/sdd/task-3.1-report.md` for the full transcript).
> The rendered cloud-init has been checked for correct `templatefile()`
> interpolation, valid YAML/bash syntax, and byte-for-byte embedded-file
> round-tripping across all three GenAI tiers and both ingress modes. The
> Secret Manager `fetch_secret` function has been extracted verbatim from
> the rendered script and proven to fail closed — including at the
> base64-decode step — against five distinct mocked failure modes (see the
> report). **A real `terraform plan`/`apply` against a GCP project has NOT
> been run** — deliberately deferred per this task's scope (gcloud is
> authenticated on the authoring machine, so even a resource-free `plan`
> was skipped entirely to avoid any risk of touching billable
> infrastructure). That real `apply` → HTTPS reachable, health green,
> login works, search works → `destroy` clean is a follow-up gate, same
> posture as the AWS module's Task 2.6.

## Prerequisites

- A GCP project (`var.project_id`) with billing enabled. This module
  enables the required APIs itself (`compute`, `secretmanager`, `dns`,
  `iam` — see "What gets created" below) so a fresh project works, but
  billing must already be linked.
- [Terraform](https://developer.hashicorp.com/terraform/downloads) >= 1.9
  (cross-variable `validation` blocks in `variables.tf` need 1.9+).
- GCP credentials available to the provider — Application Default
  Credentials (`gcloud auth application-default login`), a service
  account key, or Workload Identity — with permission to manage Compute
  Engine/Secret Manager/IAM/DNS resources in the target project. Project
  Editor (or Owner) is the simplest starting point for a reference
  deployment like this.
- A GHCR (GitHub Container Registry) **read token** for
  `ghcr.io/jakefearsd/wikantik` — the image is private; ask the
  maintainer for one (a GitHub PAT with `read:packages`, or a
  fine-grained token scoped to just that package).
- A domain you control, if using the `caddy` ingress profile (for
  Let's Encrypt) or `var.dns_managed_zone` (to have this module manage
  DNS). Not required for `cloudflared` ingress.
- An SSH public key (e.g. `~/.ssh/id_ed25519.pub`'s contents) if you want
  SSH access — see `var.ssh_public_key`.
- The default network with an auto-created subnet in `var.region` — every
  GCP project has one unless you've deliberately deleted it or the
  project was created with a custom-mode-only network.

## What gets created

| Resource | Purpose |
|---|---|
| `google_project_service` (×4) | Enables `compute`, `secretmanager`, `dns`, `iam` APIs — `disable_on_destroy = false` so tearing down this module never disables APIs a shared project's other resources depend on |
| `google_compute_instance` | Single VM (default `e2-standard-2`; Ubuntu 24.04 LTS via `data.google_compute_image.ubuntu`) |
| `google_compute_disk` + `google_compute_attached_disk` | Persistent `pd-balanced` disk mounted at `/srv/wikantik` on the instance — standalone attachment resource (mirroring AWS's separate `aws_volume_attachment`), see "Persistence model" below |
| `google_compute_resource_policy` (+ `google_compute_disk_resource_policy_attachment`) | Daily snapshots of that disk, `var.snapshot_retention_days` retention |
| `google_compute_firewall` (×2) | `wikantik-allow-ssh` (22/`var.admin_cidr`), `wikantik-allow-web` (80+443/world) — both tag-scoped to the instance; egress is GCP's implicit allow-all (no explicit rule needed) |
| `google_compute_address` | Static external IP |
| `google_dns_record_set` (optional) | `A` record for `var.domain`, only created when `var.dns_managed_zone != ""` |
| `google_secret_manager_secret` + `google_secret_manager_secret_version` (one per secret) | `var.secrets` entries + `var.ghcr_user`/`var.ghcr_token`, fetched by cloud-init at boot |
| `google_service_account` + `google_secret_manager_secret_iam_member` (one per secret) | Instance identity scoped to `roles/secretmanager.secretAccessor` on **exactly** the secrets this module created — never project-wide |

## Persistence model — why `/srv/wikantik`

Identical rationale to the AWS module: `docker-compose.cloud.yml` (an
input this module embeds via cloud-init, not a file it edits) declares
`wikantik-pages`, `pgdata` (when `bundled-db` is active), `caddy_data`,
`ollama-models`, etc. as plain Docker-managed **named volumes** — not host
bind mounts. The only lever that makes the *live* working tree survive
instance replacement, not just the nightly backup tarball, is relocating
**Docker's entire data-root** onto the persistent disk. Cloud-init does
exactly that: it mounts the `pd-balanced` disk at `/srv/wikantik` and
writes `/etc/docker/daemon.json` with `"data-root": "/srv/wikantik/docker"`
**before** Docker is ever installed or started, so every named volume —
including the live wiki page tree and `pgdata` — physically lives on that
disk from the very first boot.

The backup sidecar (`docker-compose.cloud.yml`'s unmodified `backup`
service) independently tars up `/var/wikantik/pages` and `pg_dump`s the DB
into `$BACKUP_DIR`, which cloud-init points at `/srv/wikantik/backups` — a
second, human-inspectable, `tar`/`psql`-restorable copy on the same disk.

**Same caveat as the AWS module:** any future change to
`docker-compose.cloud.yml` that narrows persistence to a pages-only bind
mount MUST also give `pgdata` (and `caddy_data`/`ollama-models`) a durable
home on `/srv/wikantik`, or it silently reintroduces total DB loss on
instance replacement.

### Disk usage & image pruning

Same guidance as AWS: monitor `df -h /srv/wikantik`, prune old image
layers with `docker image prune -a --filter "until=168h"` once an update
has proven itself (mind `wikantik:rollback`), and size
`data_volume_size_gb` up front if you expect a large corpus.

### Boot-image updates are pinned on purpose

`data.google_compute_image.ubuntu` resolves the `ubuntu-2404-lts-amd64`
family to whatever Canonical's most recent publish currently is — but the
instance carries `lifecycle { ignore_changes =
[boot_disk[0].initialize_params[0].image] }`: without it, every
`terraform apply` after a new 24.04 image drops (frequent) would propose
**replacing the instance** (GCE, like AWS, requires instance replacement
to change a running boot disk's source image) as a surprise side effect
of an unrelated change. To deliberately move to the newest image, run
`terraform apply -replace=google_compute_instance.wikantik` — safe,
because of the persistence model above (the data disk is detached and
reattached to the new instance, and cloud-init's mkfs guard leaves it
untouched).

### Instance replacement runbook (sketch)

1. `terraform apply` with the same `data_volume_size_gb`/name (the data
   disk and its snapshot schedule are independent of the instance and are
   not destroyed by replacing it).
2. If the instance itself needs replacing (e.g. `terraform apply
   -replace=google_compute_instance.wikantik` or a forced replacement from
   a variable change), Terraform detaches `google_compute_attached_disk.data`
   from the old instance and reattaches it to the new one as part of the
   same apply (the attachment is a standalone resource, independent of the
   instance's own lifecycle).
3. The new instance's cloud-init runs from scratch: mounts the
   already-formatted, already-populated disk (the mkfs guard in
   `01-mount-data.sh` skips formatting when `blkid` finds an existing
   filesystem), writes the same `daemon.json`, installs Docker fresh —
   which then finds the pre-populated data-root and resumes with every
   named volume (and thus the live page tree) intact.

## Variables

| Variable | Type | Default | Notes |
|---|---|---|---|
| `project_id` | string | *(none — required)* | GCP project ID |
| `region` | string | `us-central1` | GCP region |
| `zone` | string | `us-central1-a` | Must be a zone within `region` (**validated**) |
| `name_prefix` | string | `wikantik` | Prefix for every named resource — also the network tag firewall rules match on. Must be lowercase-letters/digits/hyphens (**validated**, GCP resource-name rules are stricter than AWS's) |
| `network_name` | string | `default` | VPC network to deploy into |
| `subnetwork_name` | string | `default` | Subnetwork (within `network_name`, `region`) for the instance's NIC |
| `machine_type` | string | `e2-standard-2` | Pass `e2-medium` (the floor this module supports) for the `core` tier (see cost table) |
| `domain` | string | `""` | Required (validated) when `ingress = "caddy"` |
| `admin_cidr` | string | *(none — required)* | SSH source CIDR, e.g. `203.0.113.4/32`. `0.0.0.0/0`/`::/0` are **rejected by validation** |
| `ssh_public_key` | string | `""` | OpenSSH public key content; written to instance metadata as `ubuntu:<key>`. Omit to boot with no SSH key |
| `ghcr_user` | string | *(none — required)* | GHCR username |
| `ghcr_token` | string, sensitive | *(none — required)* | GHCR read token |
| `wikantik_image` | string | *(none — required)* | Full ref **incl. tag** (validated), e.g. `ghcr.io/jakefearsd/wikantik:1.4.2`. Tag-style refs only — no `@sha256` digest pins |
| `tier` | string | `core` | `core` \| `search` \| `knowledge` — see tier table below |
| `ingress` | string | `caddy` | `caddy` \| `cloudflared` |
| `secrets` | map(string), sensitive | *(none — required)* | Must include `POSTGRES_PASSWORD`; `CLOUDFLARE_TUNNEL_TOKEN` iff `ingress = "cloudflared"`; `ANTHROPIC_API_KEY` iff `tier = "knowledge"` (all three enforced by `validation` blocks) |
| `dns_managed_zone` | string | `""` | Cloud DNS managed zone **name** (not DNS name) to create an A record in |
| `snapshot_retention_days` | number | `14` | Daily-snapshot retention |
| `data_volume_size_gb` | number | `50` | `/srv/wikantik` disk size — same default as AWS |
| `wikantik_mem_limit` | string | `2G` | Container memory limit (`WIKANTIK_MEM_LIMIT`) |

### GenAI tier matrix

Identical to the AWS module — mirrors `docker-compose.cloud.yml`'s header
table exactly:

| Tier | `WIKANTIK_GENAI_MODE` | `WIKANTIK_KNOWLEDGE_ENABLED` | Extra `.env` | Compose profile added |
|---|---|---|---|---|
| `core` | `none` | `false` | — | — (BM25-only, zero inference infra) |
| `search` | `embeddings-only` | `false` | `WIKANTIK_EMBEDDING_BASE_URL=http://ollama-embed:11434` | `embeddings` |
| `knowledge` | `full` | `true` | `WIKANTIK_EXTRACTOR_BACKEND=claude`, `WIKANTIK_EMBEDDING_BASE_URL=...` (+ `ANTHROPIC_API_KEY` from `secrets`) | `embeddings` |

`bundled-db` is always activated (this module has no managed-Cloud-SQL
variant yet, mirroring AWS's no-RDS-yet posture); the ingress profile
(`caddy`/`cloudflared`) is always added to match `var.ingress`.

## Secret delivery — the metadata-token curl pattern

Rather than installing the full `google-cloud-cli` (a large Python-based
SDK requiring an apt-key/repo add or a snap — heavier than this reference
deployment needs), cloud-init's `fetch_secret` function talks to Secret
Manager directly over its REST API, authenticated via the instance's
attached service account:

1. Ask the GCE metadata server for a short-lived OAuth access token for
   the default service account
   (`http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token`,
   `Metadata-Flavor: Google` header) — no gcloud, no ADC file, just curl.
2. Call
   `https://secretmanager.googleapis.com/v1/projects/{project}/secrets/{secret_id}/versions/latest:access`
   with that token as a Bearer credential.
3. The response's `payload.data` field is **base64-encoded** per the
   Secret Manager API contract — decoded with `base64 -d` as the last
   step inside `fetch_secret` itself (not by the caller), so a corrupted
   or truncated payload fails the whole function, not just the decode.

`jq` (a tiny, purpose-built JSON parser — the only new package this step
installs; `curl` is already present from the Docker-install step) pulls
`.access_token` and `.payload.data` out of the two JSON responses. Every
step is **individually exit-status-checked** with an explicit `|| return
1` rather than relying on inherited `set -e` propagation through a
function body invoked via command substitution:

```bash
fetch_secret() {
  local _wk_token _wk_payload
  _wk_token=$(curl -sf -H "Metadata-Flavor: Google" "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token" | jq -r '.access_token') || return 1
  [ -n "$_wk_token" ] && [ "$_wk_token" != "null" ] || return 1
  _wk_payload=$(curl -sf -H "Authorization: Bearer $_wk_token" "https://secretmanager.googleapis.com/v1/projects/$GCP_PROJECT_ID/secrets/$1/versions/latest:access" | jq -r '.payload.data') || return 1
  [ -n "$_wk_payload" ] && [ "$_wk_payload" != "null" ] || return 1
  printf '%s' "$_wk_payload" | base64 -d || return 1
}
```

This was proven fail-closed against five distinct mocked failure modes
(token-fetch network error, null/missing `access_token`, secret-fetch
network/permission error, null/missing `payload.data`, and — the
important one — a **corrupted/non-base64 payload**, where `base64 -d`
itself exits non-zero and the caller's `secret_X="$(fetch_secret ...)" ||
fatal ...` aborts the whole boot) — see
`.superpowers/sdd/task-3.1-report.md` for the exact transcripts. `curl
-sf` itself also fails closed on any HTTP error status (`-f` treats
4xx/5xx as a curl failure, not just a non-2xx body to parse).

`GCP_PROJECT_ID` is resolved once, before any secret fetch, from the
metadata server's `project/project-id` endpoint (mirroring the AWS
module's dynamic IMDSv2 region lookup, rather than baking
`var.project_id` in as a literal) — a plain assignment statement, so the
already-active `set -e` aborts the boot immediately if that lookup fails.

## Walkthrough

```bash
cd deploy/gcp

terraform init

# Fill these in (or use a .tfvars file — see below):
terraform plan \
  -var 'project_id=my-gcp-project' \
  -var 'admin_cidr=203.0.113.4/32' \
  -var 'domain=wiki.example.com' \
  -var 'ghcr_user=your-github-username' \
  -var 'ghcr_token=ghp_xxx' \
  -var 'wikantik_image=ghcr.io/jakefearsd/wikantik:1.4.2' \
  -var 'tier=search' \
  -var 'ssh_public_key=ssh-ed25519 AAAA... you@example.com' \
  -var 'secrets={"POSTGRES_PASSWORD":"a-real-password"}'

terraform apply   # same -var flags
```

Or, more conveniently, a `terraform.tfvars` (gitignored — it will contain
secrets):

```hcl
project_id     = "my-gcp-project"
admin_cidr     = "203.0.113.4/32"
domain         = "wiki.example.com"
ghcr_user      = "your-github-username"
ghcr_token     = "ghp_xxx"
wikantik_image = "ghcr.io/jakefearsd/wikantik:1.4.2"
tier           = "search"
ssh_public_key = "ssh-ed25519 AAAA... you@example.com"

secrets = {
  POSTGRES_PASSWORD = "a-real-password"
}
```

then just `terraform plan` / `terraform apply` with no `-var` flags.

First boot takes a few minutes (cloud-init: mount + format the data disk,
install Docker from its official apt repo, fetch secrets from Secret
Manager, `docker login`, `docker compose ... up -d`, which itself
cold-pulls the image and — for `search`/`knowledge` — the embedding
sidecar's model). Watch progress with `cloud-init status --wait` over SSH,
or `/var/log/cloud-init-output.log`.

Secret fetching is **fail-closed**: if any Secret Manager fetch fails
(permission denied, misnamed secret, malformed payload), the boot script
aborts with a `FATAL: failed to fetch ...` line in
`/var/log/cloud-init-output.log` and the stack is never started — it will
*not* silently fall back to compose's built-in `CHANGEME` defaults. Fix
the cause and re-run
`/opt/wikantik/cloud-init.d/03-fetch-secrets-and-launch.sh` (idempotent).

Once healthy: `terraform output wikantik_url`.

### SSH access

```bash
terraform output ssh_command
# ssh ubuntu@<external-ip>
```

Only reachable from `var.admin_cidr`. If you didn't set
`ssh_public_key`, you have no way in short of GCP console tooling this
module doesn't wire up (the serial console, or enabling OS Login) — set
it before `apply` if you'll need shell access.

### Updating to a new image tag

Routine tag-to-tag upgrades should use `wikantik-update`
(`deploy/bin/wikantik-update.sh`, installed by cloud-init at
`/usr/local/bin/wikantik-update`, config at `/etc/wikantik-update.conf`)
rather than re-running `terraform apply` with a new `wikantik_image` —
the script pulls, retags the current image as a rollback point, brings
the stack up, health-polls, and auto-rolls-back on failure:

```bash
ssh ubuntu@<external-ip>
sudo wikantik-update 1.4.3
```

(Re-applying Terraform with a new `wikantik_image` also works — it
rewrites the static `.env` content and replaces the instance metadata
`user-data`, which triggers a full cloud-init re-run on next boot. GCE
does not auto-reboot an instance on metadata change the way AWS's
`user_data_replace_on_change` forces an instance replacement, so you
would additionally need to reset/restart the instance for the new
`user-data` to take effect — a much bigger hammer than `wikantik-update`,
though harmless given the persistence model above. Prefer
`wikantik-update` for routine upgrades.)

### Restore from a snapshot (outline)

1. In the Cloud Console (or `gcloud compute snapshots list`), find the
   daily snapshot of the `${name_prefix}-data` disk for the date you want.
2. Create a new persistent disk from that snapshot in the same zone as
   your instance (`terraform output data_disk_id` tells you the current
   disk; check its zone).
3. Stop the instance, detach the current data disk
   (`gcloud compute instances detach-disk`), attach the restored disk
   with the same `device-name` (`${name_prefix}-data`), start the
   instance.
4. Alternatively, restore just the page tree / DB without touching the
   whole disk: attach the snapshot-derived disk at a scratch path and use
   `docker/backup/restore.sh` against the `pages.tar.gz`/`db.sql.gz`
   inside `/srv/wikantik/backups/daily/<date>/` — see that script's own
   `--help` for the exact invocation.
5. To rebuild the whole VM from scratch against an existing data disk
   (e.g. after an unrecoverable instance failure), point
   `google_compute_disk.data`'s replacement at the existing disk via
   `terraform import`, or simply re-run `terraform apply` — cloud-init's
   mkfs guard (`blkid` check in `01-mount-data.sh`) will detect the
   existing filesystem and skip formatting, so no data is lost either
   way.

### Teardown

```bash
terraform destroy
```

This is a **deliberately clean, fully destroyable** module — no
`prevent_destroy` lifecycle guards (the `google_project_service` resources
use `disable_on_destroy = false`, so destroying this module never disables
APIs a shared project's other resources might still need — but everything
else this module created IS destroyed). If the data matters, take a
manual snapshot first (or just wait for the next 03:00 daily one) — the
disk/snapshots are NOT excluded from `destroy`.

## Cost (rough, us-central1, on-demand pricing — verify current pricing before relying on this)

| Item | `core` (e2-medium) | `search`/`knowledge` (e2-standard-2) |
|---|---|---|
| Instance | ~$25/mo | ~$50/mo |
| pd-balanced 50 GiB | ~$5/mo | ~$5/mo |
| Static external IP (attached) | $0 | $0 |
| Daily snapshots (14-day retention, incremental) | a few $/mo | a few $/mo |
| **Floor total** | **~$30–35/mo** | **~$55–60/mo** |

`knowledge` tier additionally incurs Anthropic API usage (pay-per-token,
outside this module's cost model) and, if `dns_managed_zone` is set,
Cloud DNS's per-zone/query charges.

## AWS parity note

This module reuses `deploy/cloud-init/cloud-init.yaml.tftpl` **completely
unmodified** — no new template variables were needed. The four
cloud-specific template vars the shared template already exposes map onto
GCP as follows:

| Template var | AWS value | GCP value |
|---|---|---|
| `data_device` | `/dev/disk/by-id/nvme-Amazon_Elastic_Block_Store_<volid>` | `/dev/disk/by-id/google-<device_name>` |
| `secret_fetch_prereqs` | `apt-get install awscli` | `apt-get install jq` |
| `secret_fetch_setup` | IMDSv2 token + region export | Project-ID export from the metadata server (no token step — GCE's metadata server needs only the `Metadata-Flavor: Google` header, no separate token exchange for metadata reads) |
| `secret_fetch_cmd` | `aws ssm get-parameter --with-decryption ...` | metadata-token curl → Secret Manager REST `:access` → `jq` → `base64 -d`, all fail-closed (see above) |

Everything else — the mount/format sequencing, the Docker data-root
relocation, the `write_files` list, the three-script `runcmd` split, the
`append_env`/`conf_secret` hardening — carries over unchanged, exactly as
`deploy/aws/README.md`'s own "GCP parity note" anticipated.
