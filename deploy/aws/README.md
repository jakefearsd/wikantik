# Wikantik on AWS — single-VM Terraform reference deployment

Deliberately minimal: one EC2 instance, one persistent EBS data volume with
daily snapshots, one security group, one Elastic IP, SSM Parameter Store
for secrets, and an optional Route53 record — all in your account's
**default VPC**. No ALB, no ECS, no multi-AZ: Wikantik is a single-instance
app by design (filesystem page storage, in-JVM locks, in-RAM search index),
so a fancier topology would just add cost and failure modes without
buying you anything.

This module (`deploy/aws/`) pairs with the shared cloud-init template at
`deploy/cloud-init/cloud-init.yaml.tftpl`, which GCP's future module
(Task 3.1) is expected to reuse unmodified.

> **Validation status:** this module has been format-checked
> (`terraform fmt -check`), initialized against the real `hashicorp/aws`
> provider (`terraform init -backend=false`), and validated
> (`terraform validate`) — all clean. The rendered cloud-init has been
> checked for correct `templatefile()` interpolation and valid YAML/bash
> syntax across all three GenAI tiers and both ingress modes (see
> `.superpowers/sdd/task-2.3-report.md` for the full transcript and a
> pasted rendering). **A real `terraform plan`/`apply` against an AWS
> account has NOT been run** — no AWS credentials exist on the authoring
> machine. That is the Task 2.6 gate: a real `apply` → HTTPS reachable,
> health green, login works, search works → `destroy` clean.

## Prerequisites

- An AWS account with a default VPC in your target region (every region
  has one unless you've deliberately deleted it).
- [Terraform](https://developer.hashicorp.com/terraform/downloads) >= 1.9
  (cross-variable `validation` blocks in `variables.tf` need 1.9+).
- AWS credentials available to the provider (env vars, `~/.aws/config`
  profile, or SSO) with permission to manage EC2/EBS/DLM/IAM/SSM/Route53
  in the target account — an administrator role is the simplest starting
  point for a reference deployment like this.
- A GHCR (GitHub Container Registry) **read token** for
  `ghcr.io/jakefearsd/wikantik` — the image is private; ask the
  maintainer for one (a GitHub PAT with `read:packages`, or a
  fine-grained token scoped to just that package).
- A domain you control, if using the `caddy` ingress profile (for
  Let's Encrypt) or `var.route53_zone_id` (to have this module manage
  DNS). Not required for `cloudflared` ingress.
- An existing EC2 key pair (`aws ec2 create-key-pair` or the console) if
  you want SSH access — see `var.ssh_key_name`.

## What gets created

| Resource | Purpose |
|---|---|
| `aws_instance` | Single VM (default `t3.large`; Ubuntu 24.04 LTS via `data.aws_ami.ubuntu`; IMDSv2 required) |
| `aws_ebs_volume` + `aws_volume_attachment` | Persistent gp3 volume mounted at `/srv/wikantik` on the instance — see "Persistence model" below |
| `aws_dlm_lifecycle_policy` (+ its IAM role) | Daily snapshots of that volume, `var.snapshot_retention_days` retention |
| `aws_security_group` | 22/`var.admin_cidr` only, 80+443/world, egress unrestricted |
| `aws_eip` | Static public IP |
| `aws_route53_record` (optional) | `A` record for `var.domain`, only created when `var.route53_zone_id != ""` |
| `aws_ssm_parameter` (SecureString, one per secret) | `var.secrets` entries + `var.ghcr_user`/`var.ghcr_token`, fetched by cloud-init at boot |
| `aws_iam_role` + `aws_iam_instance_profile` | Scoped to `ssm:GetParameter`/`ssm:GetParameters` on **exactly** the parameters this module created, plus `kms:Decrypt` via the SSM default key (`alias/aws/ssm`) |

## Persistence model — why `/srv/wikantik`

`docker-compose.cloud.yml` (an input this module embeds via cloud-init,
not a file it edits) declares `wikantik-pages`, `pgdata` (when
`bundled-db` is active), `caddy_data`, `ollama-models`, etc. as plain
Docker-managed **named volumes** — not host bind mounts. Short of editing
that file (out of scope here), the only lever that makes the *live*
working tree survive instance replacement, not just the nightly backup
tarball, is relocating **Docker's entire data-root** onto the persistent
volume. Cloud-init does exactly that: it mounts the EBS volume at
`/srv/wikantik` and writes `/etc/docker/daemon.json` with
`"data-root": "/srv/wikantik/docker"` **before** Docker is ever installed
or started, so every named volume — including the live wiki page tree —
physically lives on that volume from the very first boot.

The backup sidecar (`docker-compose.cloud.yml`'s unmodified `backup`
service) independently tars up `/var/wikantik/pages` and `pg_dump`s the
DB into `$BACKUP_DIR`, which cloud-init points at
`/srv/wikantik/backups` — a second, human-inspectable, `tar`/`psql`-
restorable copy on the same volume.

**Handoff note:** a cleaner long-term fix would be for
`docker-compose.cloud.yml` to grow a `WIKANTIK_PAGES_DIR`-style bind-mount
override (mirroring what `docker-compose.prod.yml` already does for
docker1), matching the brief's original phrasing. That file was out of
this task's scope to edit; flagging it for whoever picks up Task 2.5/2.6.

### Instance replacement runbook (sketch)

1. `terraform apply` with the same `data_volume_size_gb`/tags (the EBS
   volume and its DLM policy are independent of the instance and are not
   destroyed by replacing it).
2. If the instance itself needs replacing (e.g. `terraform taint
   aws_instance.wikantik` or a forced replacement from a variable
   change), Terraform detaches the volume from the old instance and
   reattaches it to the new one as part of the same apply
   (`aws_volume_attachment` tracks both resources).
3. The new instance's cloud-init runs from scratch: mounts the
   already-formatted, already-populated volume (the mkfs guard in
   `01-mount-data.sh` skips formatting when `blkid` finds an existing
   filesystem), writes the same `daemon.json`, installs Docker fresh —
   which then finds the pre-populated data-root and resumes with every
   named volume (and thus the live page tree) intact.

## Variables

| Variable | Type | Default | Notes |
|---|---|---|---|
| `region` | string | `us-east-1` | AWS region |
| `name_prefix` | string | `wikantik` | Prefix for every named resource — lets you run more than one instance of this module per account |
| `instance_type` | string | `t3.large` | Pass `t3.medium` for the `core` tier (see cost table) |
| `domain` | string | `""` | Required (validated) when `ingress = "caddy"` |
| `admin_cidr` | string | *(none — required)* | SSH source CIDR, e.g. `203.0.113.4/32`. Never `0.0.0.0/0` |
| `ssh_key_name` | string | `null` | Existing EC2 key pair name; omit to boot with no SSH key |
| `ghcr_user` | string | *(none — required)* | GHCR username |
| `ghcr_token` | string, sensitive | *(none — required)* | GHCR read token |
| `wikantik_image` | string | *(none — required)* | Full ref **incl. tag**, e.g. `ghcr.io/jakefearsd/wikantik:1.4.2` |
| `tier` | string | `core` | `core` \| `search` \| `knowledge` — see tier table below |
| `ingress` | string | `caddy` | `caddy` \| `cloudflared` |
| `secrets` | map(string), sensitive | *(none — required)* | Must include `POSTGRES_PASSWORD`; `CLOUDFLARE_TUNNEL_TOKEN` iff `ingress = "cloudflared"`; `ANTHROPIC_API_KEY` iff `tier = "knowledge"` (all three enforced by `validation` blocks) |
| `route53_zone_id` | string | `""` | Set to manage a DNS record for `domain` |
| `snapshot_retention_days` | number | `14` | DLM daily-snapshot retention |
| `data_volume_size_gb` | number | `50` | `/srv/wikantik` gp3 volume size |
| `wikantik_mem_limit` | string | `2G` | Container memory limit (`WIKANTIK_MEM_LIMIT`) |

### GenAI tier matrix

Mirrors `docker-compose.cloud.yml`'s header table exactly:

| Tier | `WIKANTIK_GENAI_MODE` | `WIKANTIK_KNOWLEDGE_ENABLED` | Extra `.env` | Compose profile added |
|---|---|---|---|---|
| `core` | `none` | `false` | — | — (BM25-only, zero inference infra) |
| `search` | `embeddings-only` | `false` | `WIKANTIK_EMBEDDING_BASE_URL=http://ollama-embed:11434` | `embeddings` |
| `knowledge` | `full` | `true` | `WIKANTIK_EXTRACTOR_BACKEND=claude`, `WIKANTIK_EMBEDDING_BASE_URL=...` (+ `ANTHROPIC_API_KEY` from `secrets`) | `embeddings` |

`bundled-db` is always activated (this module has no RDS variant yet —
that's Task 2.5); the ingress profile (`caddy`/`cloudflared`) is always
added to match `var.ingress`.

## Walkthrough

```bash
cd deploy/aws

terraform init

# Fill these in (or use a .tfvars file — see below):
terraform plan \
  -var 'admin_cidr=203.0.113.4/32' \
  -var 'domain=wiki.example.com' \
  -var 'ghcr_user=your-github-username' \
  -var 'ghcr_token=ghp_xxx' \
  -var 'wikantik_image=ghcr.io/jakefearsd/wikantik:1.4.2' \
  -var 'tier=search' \
  -var 'ssh_key_name=my-keypair' \
  -var 'secrets={"POSTGRES_PASSWORD":"a-real-password"}'

terraform apply   # same -var flags
```

Or, more conveniently, a `terraform.tfvars` (gitignored — it will contain
secrets):

```hcl
admin_cidr     = "203.0.113.4/32"
domain         = "wiki.example.com"
ghcr_user      = "your-github-username"
ghcr_token     = "ghp_xxx"
wikantik_image = "ghcr.io/jakefearsd/wikantik:1.4.2"
tier           = "search"
ssh_key_name   = "my-keypair"

secrets = {
  POSTGRES_PASSWORD = "a-real-password"
}
```

then just `terraform plan` / `terraform apply` with no `-var` flags.

First boot takes a few minutes (cloud-init: mount + format the data
volume, install Docker from its official apt repo, fetch secrets from
SSM, `docker login`, `docker compose ... up -d`, which itself cold-pulls
the image and — for `search`/`knowledge` — the embedding sidecar's model).
Watch progress with `cloud-init status --wait` over SSH, or
`/var/log/cloud-init-output.log`.

Once healthy: `terraform output wikantik_url`.

### SSH access

```bash
terraform output ssh_command
# ssh ubuntu@<eip>
```

Only reachable from `var.admin_cidr`. If you didn't set `ssh_key_name`,
you have no way in short of AWS console tooling this module doesn't wire
up (EC2 Instance Connect / SSM Session Manager) — set it before `apply`
if you'll need shell access.

### Updating to a new image tag

Routine tag-to-tag upgrades should use `wikantik-update`
(`deploy/bin/wikantik-update.sh`, installed by cloud-init at
`/usr/local/bin/wikantik-update`, config at `/etc/wikantik-update.conf`)
rather than re-running `terraform apply` with a new `wikantik_image` —
the script pulls, retags the current image as a rollback point, brings
the stack up, health-polls, and auto-rolls-back on failure:

```bash
ssh ubuntu@<eip>
sudo wikantik-update 1.4.3
```

(Re-applying Terraform with a new `wikantik_image` also works — it
rewrites the static `.env` content and replaces `user_data`, which
**replaces the instance** (`user_data_replace_on_change = true`),
re-running cloud-init from scratch. That's a much bigger hammer than
`wikantik-update`, though harmless given the persistence model above —
prefer `wikantik-update` for routine upgrades.)

### Restore from a DLM snapshot (outline)

1. In the AWS console (or CLI), find the daily snapshot of the
   `${name_prefix}-data` volume (tag `${name_prefix}-backup = true`) for
   the date you want.
2. Create a new EBS volume from that snapshot in the same AZ as your
   instance (`terraform output data_volume_id` tells you the current
   volume; check its AZ).
3. Stop the instance, detach the current data volume
   (`aws ec2 detach-volume`), attach the restored volume at the same
   device (`/dev/sdf`), start the instance.
4. Alternatively, restore just the page tree / DB without touching the
   whole volume: mount the snapshot-derived volume at a scratch path and
   use `docker/backup/restore.sh` against the `pages.tar.gz`/`db.sql.gz`
   inside `/srv/wikantik/backups/daily/<date>/` — see that script's own
   `--help` for the exact invocation.
5. To rebuild the whole VM from scratch against an existing data volume
   (e.g. after an unrecoverable instance failure), point
   `aws_ebs_volume.data`'s replacement at the existing volume ID via
   `terraform import`, or simply re-run `terraform apply` — cloud-init's
   mkfs guard (`blkid` check in `01-mount-data.sh`) will detect the
   existing filesystem and skip formatting, so no data is lost either
   way.

### Teardown

```bash
terraform destroy
```

This is a **deliberately clean, fully destroyable** module — no
`prevent_destroy` lifecycle guards. If the data matters, take a manual
DLM snapshot first (or just wait for the next 03:00 daily one) — the
volume/snapshots are NOT excluded from `destroy`.

## Cost (rough, us-east-1, on-demand pricing — verify current pricing before relying on this)

| Item | `core` (t3.medium) | `search`/`knowledge` (t3.large) |
|---|---|---|
| Instance | ~$30/mo | ~$60/mo |
| EBS gp3 50 GiB | ~$4/mo | ~$4/mo |
| EIP (attached) | $0 | $0 |
| DLM snapshots (14-day retention, incremental) | a few $/mo | a few $/mo |
| **Floor total** | **~$35–40/mo** | **~$65–70/mo** |

`knowledge` tier additionally incurs Anthropic API usage (pay-per-token,
outside this module's cost model) and, if `route53_zone_id` is set,
Route53's per-hosted-zone/query charges.

## GCP parity note

`deploy/cloud-init/cloud-init.yaml.tftpl` is written to be cloud-agnostic:
every AWS-specific behavior (SSM fetch command, IMDSv2 region lookup, the
NVMe-by-id data device path) is confined to template variables assembled
in this module's `main.tf`. GCP's future module (Task 3.1) is expected to
render the same template with `gcloud secrets versions access` in place
of `aws ssm get-parameter`, no region-lookup setup step (ADI/project come
from the GCE metadata server automatically), and a `/dev/disk/by-id/
google-<disk-name>` data device — everything else (mount/format
sequencing, the Docker data-root relocation, the write_files list, the
runcmd script split) carries over unchanged.
