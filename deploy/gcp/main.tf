#
# deploy/gcp/main.tf — GCP single-VM Terraform reference deployment for
# Wikantik (Task 3.1, docs/superpowers/plans/2026-07-16-aws-gcp-deployment-
# readiness.md Phase 3). Deliberately minimal: default network, one Compute
# Engine instance, one persistent data disk + daily snapshot schedule, two
# firewall rules, one static external IP, an optional Cloud DNS record, and
# Secret Manager for secrets. No GKE/MIG/multi-zone ambitions — see
# README.md. This is the GCP twin of deploy/aws/main.tf; read that file's
# header first if you haven't.
#
# A real `terraform apply` is DEFERRED for this task (creating any billable
# GCP resource is out of scope here — see README.md's Validation section
# for exactly what was and wasn't exercised).
#

terraform {
  # >= 1.9 for cross-variable `validation` blocks (variables.tf: var.secrets
  # and var.domain validations reference var.ingress/var.tier) — same
  # constraint as deploy/aws.
  required_version = ">= 1.9"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = ">= 5.0"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
  zone    = var.zone
}

# -----------------------------------------------------------------------
# Required APIs. Unlike AWS (where every service is always "on" for an
# account), a fresh GCP project has most APIs disabled by default —
# without enabling these, the very first `terraform apply` against a new
# project fails with "API not enabled" errors before creating anything.
# disable_on_destroy = false: this module's `terraform destroy` must not
# disable APIs that other resources in the same (possibly shared) project
# still depend on.
# -----------------------------------------------------------------------

resource "google_project_service" "required" {
  for_each = toset([
    "compute.googleapis.com",
    "secretmanager.googleapis.com",
    "dns.googleapis.com",
    "iam.googleapis.com",
  ])

  project            = var.project_id
  service            = each.value
  disable_on_destroy = false
}

# -----------------------------------------------------------------------
# Default network + subnetwork (deliberately minimal — no custom VPC/
# subnetting; every GCP project has an auto-mode "default" network with
# one auto-created subnet per region unless deliberately deleted).
# -----------------------------------------------------------------------

data "google_compute_network" "selected" {
  name    = var.network_name
  project = var.project_id
}

data "google_compute_subnetwork" "selected" {
  name    = var.subnetwork_name
  region  = var.region
  project = var.project_id
}

# Ubuntu 24.04 LTS (Noble Numbat), amd64 — Canonical's official image
# family on the ubuntu-os-cloud public project.
data "google_compute_image" "ubuntu" {
  family  = "ubuntu-2404-lts-amd64"
  project = "ubuntu-os-cloud"
}

# -----------------------------------------------------------------------
# Firewall rules — 22/admin_cidr, 80+443/world, tag-scoped to this
# instance, nothing else. Egress is wide open by GCP's implicit
# allow-all-egress default rule (no explicit egress firewall rule needed,
# unlike AWS security groups) — docker pulls from GHCR/Docker Hub, the
# Secret Manager REST API, apt, and — for tier=knowledge — the Anthropic
# API all need arbitrary outbound HTTPS.
# -----------------------------------------------------------------------

resource "google_compute_firewall" "ssh" {
  name    = "${var.name_prefix}-allow-ssh"
  network = data.google_compute_network.selected.self_link
  project = var.project_id

  allow {
    protocol = "tcp"
    ports    = ["22"]
  }

  source_ranges = [var.admin_cidr]
  target_tags   = [var.name_prefix]

  depends_on = [google_project_service.required]
}

resource "google_compute_firewall" "web" {
  name    = "${var.name_prefix}-allow-web"
  network = data.google_compute_network.selected.self_link
  project = var.project_id

  allow {
    protocol = "tcp"
    ports    = ["80", "443"]
  }

  source_ranges = ["0.0.0.0/0"]
  target_tags   = [var.name_prefix]

  depends_on = [google_project_service.required]
}

# -----------------------------------------------------------------------
# Service account — attached to the instance with the broad "cloud-platform"
# OAuth scope (current GCP guidance: scope the *capability* via IAM roles,
# not via a narrow legacy access scope). The IAM bindings below grant
# secretAccessor on exactly the secrets this module creates, nothing else
# — the SA can fetch those secret values and nothing more.
# -----------------------------------------------------------------------

resource "google_service_account" "instance" {
  account_id   = "${var.name_prefix}-instance"
  display_name = "Wikantik single-VM instance (${var.name_prefix})"
  project      = var.project_id

  depends_on = [google_project_service.required]
}

# -----------------------------------------------------------------------
# Secret Manager — one secret (+ single version) per entry in
# local.all_secrets, PLUS the GHCR credentials (folded into the same map
# so the service account's IAM bindings and the cloud-init fetch loop
# both treat them uniformly). Secret Manager secret_ids don't allow "/"
# (unlike AWS SSM's path-style parameter names), so
# "${name_prefix}-secrets-${key}" is used instead of AWS's
# "/${name_prefix}/secrets/${key}".
# -----------------------------------------------------------------------

locals {
  all_secrets = merge(var.secrets, {
    GHCR_USER  = var.ghcr_user
    GHCR_TOKEN = var.ghcr_token
  })
}

resource "google_secret_manager_secret" "secret" {
  for_each  = local.all_secrets
  secret_id = "${var.name_prefix}-secrets-${each.key}"
  project   = var.project_id

  replication {
    auto {}
  }

  depends_on = [google_project_service.required]
}

resource "google_secret_manager_secret_version" "secret" {
  for_each    = local.all_secrets
  secret      = google_secret_manager_secret.secret[each.key].id
  secret_data = each.value
}

resource "google_secret_manager_secret_iam_member" "accessor" {
  for_each  = local.all_secrets
  project   = google_secret_manager_secret.secret[each.key].project
  secret_id = google_secret_manager_secret.secret[each.key].secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.instance.email}"
}

# -----------------------------------------------------------------------
# Persistent data disk — /srv/wikantik. Docker's data-root is relocated
# here (see cloud-init template) so the LIVE wikantik-pages/pgdata/etc.
# named volumes survive instance replacement, not just the nightly backup
# tarball (which also lands here, via BACKUP_DIR). Attached via a
# STANDALONE google_compute_attached_disk resource (mirroring AWS's
# separate aws_volume_attachment) rather than an inline `attached_disk`
# block on the instance, so the attachment is independent of the
# instance's own lifecycle.
# -----------------------------------------------------------------------

locals {
  # Explicit device_name so the by-id symlink below is stable regardless
  # of what the disk resource itself happens to be named.
  data_device_name = "${var.name_prefix}-data"
}

resource "google_compute_disk" "data" {
  name    = "${var.name_prefix}-data"
  zone    = var.zone
  type    = "pd-balanced"
  size    = var.data_volume_size_gb
  project = var.project_id

  depends_on = [google_project_service.required]
}

resource "google_compute_attached_disk" "data" {
  disk        = google_compute_disk.data.id
  instance    = google_compute_instance.wikantik.id
  device_name = local.data_device_name
}

resource "google_compute_resource_policy" "snapshot_schedule" {
  name    = "${var.name_prefix}-daily-snapshot"
  region  = var.region
  project = var.project_id

  snapshot_schedule_policy {
    schedule {
      daily_schedule {
        days_in_cycle = 1
        start_time    = "03:00"
      }
    }

    retention_policy {
      max_retention_days    = var.snapshot_retention_days
      on_source_disk_delete = "KEEP_AUTO_SNAPSHOTS"
    }
  }

  depends_on = [google_project_service.required]
}

resource "google_compute_disk_resource_policy_attachment" "data" {
  name    = google_compute_resource_policy.snapshot_schedule.name
  disk    = google_compute_disk.data.name
  zone    = var.zone
  project = var.project_id
}

# -----------------------------------------------------------------------
# Cloud-init rendering — see deploy/cloud-init/cloud-init.yaml.tftpl for
# the full template-variable contract. Everything GCP-specific (Secret
# Manager fetch command over the metadata-token curl pattern, the
# by-id data device path) is assembled here as plain values; the template
# itself has no GCP-specific logic, matching what deploy/aws/main.tf
# already relies on.
# -----------------------------------------------------------------------

locals {
  # Stable by-id path — see the device_name comment above. GCE creates a
  # /dev/disk/by-id/google-<device_name> symlink for every attached
  # persistent disk, regardless of attachment order.
  data_device = "/dev/disk/by-id/google-${local.data_device_name}"

  # Lightest fetch dependency: jq (a tiny, purpose-built JSON parser) to
  # pull `.access_token` / `.payload.data` out of the metadata-server and
  # Secret Manager REST responses — versus installing the full
  # google-cloud-cli (a large Python-based SDK requiring an apt-key/repo
  # add or a snap). curl is already installed by 02-install-docker.sh
  # (which runs before this step), so it isn't repeated here.
  secret_fetch_prereqs = <<-EOT
    apt-get update -y
    apt-get install -y --no-install-recommends jq
  EOT

  # Run once before any fetch_secret call: resolve the instance's own
  # project ID via the metadata server (not baked in by Terraform, mirroring
  # AWS's dynamic IMDSv2 region lookup) so the rendered image stays correct
  # even if reused outside this exact module invocation. A plain assignment
  # statement (not part of if/&&/||), so `set -e` (already active in the
  # calling script) aborts the whole boot if this curl fails — same
  # fail-closed posture as AWS's IMDS token/region lookup.
  secret_fetch_setup = <<-EOT
    export GCP_PROJECT_ID=$(curl -sf -H "Metadata-Flavor: Google" "http://metadata.google.internal/computeMetadata/v1/project/project-id")
  EOT

  # $1 = Secret Manager secret_id; must print the decrypted (base64-
  # decoded) value to stdout. Every step is explicitly exit-status-checked
  # (`|| return 1`) rather than relying on implicit `set -e` propagation
  # through a function body invoked via command substitution — the same
  # "never trust an implicit exit-status path" posture the AWS side's
  # fail-closed fix was built on. The base64 decode is the last step and is
  # itself checked: GNU `base64 -d` (no --ignore-garbage) exits non-zero on
  # malformed input, so a corrupted/truncated payload aborts here rather
  # than handing the boot script garbage.
  secret_fetch_cmd = <<-EOT
    local _wk_token _wk_payload
    _wk_token=$(curl -sf -H "Metadata-Flavor: Google" "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token" | jq -r '.access_token') || return 1
    [ -n "$_wk_token" ] && [ "$_wk_token" != "null" ] || return 1
    _wk_payload=$(curl -sf -H "Authorization: Bearer $_wk_token" "https://secretmanager.googleapis.com/v1/projects/$GCP_PROJECT_ID/secrets/$1/versions/latest:access" | jq -r '.payload.data') || return 1
    [ -n "$_wk_payload" ] && [ "$_wk_payload" != "null" ] || return 1
    printf '%s' "$_wk_payload" | base64 -d || return 1
  EOT

  # Two lines per secret, sorted for a deterministic rendered script (and
  # thus a stable `terraform plan` diff when secrets don't change): fetch
  # into a shell variable with an EXPLICIT exit-status check, then append
  # via the template's append_env helper. Same naive-pattern-avoidance
  # rationale as deploy/aws/main.tf: an `echo "K=$(cmd)" >> file` line does
  # NOT trip `set -e` on a failed command substitution (echo's own exit
  # status wins), so a throttled/denied Secret Manager call would silently
  # write an empty value, and compose's POSTGRES_PASSWORD:-CHANGEME
  # fallback would boot the stack "healthy" on a known default password.
  # Fail closed instead. The secret KEY doubles as part of a shell
  # variable name, which var.secrets' key-shape validation guarantees is
  # legal.
  fetch_secrets_block = join("\n", flatten([
    for k in sort(keys(local.all_secrets)) : [
      "secret_${k}=\"$(fetch_secret '${google_secret_manager_secret.secret[k].secret_id}')\" || fatal \"failed to fetch ${google_secret_manager_secret.secret[k].secret_id}\"",
      "append_env '${k}' \"$secret_${k}\"",
    ]
  ]))

  compose_profiles = compact(concat(
    ["bundled-db", var.ingress],
    var.tier == "core" ? [] : ["embeddings"],
  ))
  compose_profile_args  = join(" ", [for p in local.compose_profiles : "--profile ${p}"])
  compose_profile_names = join(" ", local.compose_profiles)

  wikantik_repo_dir = "/opt/wikantik"
  # Registry+repo without the tag, for /etc/wikantik-update.conf's
  # WIKANTIK_IMAGE_REPO. Strips only a trailing ":tag" (colon + chars with
  # no '/' at end-of-string) — a naive split(":")[0] would truncate a
  # registry:port/repo:tag reference at the port. var.wikantik_image's
  # validation guarantees such a tag suffix exists, so regex() can't fail.
  wikantik_image_repo = regex("^(.*):[^:/]+$", var.wikantik_image)[0]
  health_url          = "http://localhost:8080/api/health"

  # Non-secret .env content — GenAI tier preset (matching
  # docker-compose.cloud.yml's header table) + ingress preset + the image
  # ref + BACKUP_DIR pointed at the persistent disk. Secrets are appended
  # on top of this at boot (see fetch_secrets_block above).
  tier_env = {
    core = {
      WIKANTIK_GENAI_MODE        = "none"
      WIKANTIK_KNOWLEDGE_ENABLED = "false"
    }
    search = {
      WIKANTIK_GENAI_MODE         = "embeddings-only"
      WIKANTIK_KNOWLEDGE_ENABLED  = "false"
      WIKANTIK_EMBEDDING_BASE_URL = "http://ollama-embed:11434"
    }
    knowledge = {
      WIKANTIK_GENAI_MODE         = "full"
      WIKANTIK_KNOWLEDGE_ENABLED  = "true"
      WIKANTIK_EXTRACTOR_BACKEND  = "claude"
      WIKANTIK_EMBEDDING_BASE_URL = "http://ollama-embed:11434"
    }
  }

  ingress_env = var.ingress == "caddy" ? {
    WIKANTIK_DOMAIN        = var.domain
    PROXY_REMOTE_IP_HEADER = "X-Forwarded-For"
  } : {}

  base_env = {
    WIKANTIK_IMAGE     = var.wikantik_image
    WIKANTIK_MEM_LIMIT = var.wikantik_mem_limit
    BACKUP_DIR         = "/srv/wikantik/backups"
  }

  env_static_map     = merge(local.base_env, local.tier_env[var.tier], local.ingress_env)
  env_static_content = join("\n", [for k in sort(keys(local.env_static_map)) : "${k}=${local.env_static_map[k]}"])

  cloud_init_rendered = templatefile("${path.module}/../cloud-init/cloud-init.yaml.tftpl", {
    data_device           = local.data_device
    secret_fetch_prereqs  = local.secret_fetch_prereqs
    secret_fetch_setup    = local.secret_fetch_setup
    secret_fetch_cmd      = local.secret_fetch_cmd
    fetch_secrets_block   = local.fetch_secrets_block
    compose_profile_args  = local.compose_profile_args
    compose_profile_names = local.compose_profile_names
    wikantik_repo_dir     = local.wikantik_repo_dir
    wikantik_image_repo   = local.wikantik_image_repo
    health_url            = local.health_url

    docker_compose_yml_b64       = base64encode(file("${path.module}/../../docker-compose.yml"))
    docker_compose_cloud_yml_b64 = base64encode(file("${path.module}/../../docker-compose.cloud.yml"))
    caddyfile_b64                = base64encode(file("${path.module}/../../deploy/config/Caddyfile"))
    backup_sh_b64                = base64encode(file("${path.module}/../../docker/backup/backup.sh"))
    restore_sh_b64               = base64encode(file("${path.module}/../../docker/backup/restore.sh"))
    backup_crontab_b64           = base64encode(file("${path.module}/../../docker/backup/crontab"))
    wikantik_update_script_b64   = base64encode(file("${path.module}/../../deploy/bin/wikantik-update.sh"))
    env_static_b64               = base64encode(local.env_static_content)
  })
}

# -----------------------------------------------------------------------
# Instance + static external IP + optional Cloud DNS record.
# -----------------------------------------------------------------------

resource "google_compute_address" "wikantik" {
  name    = "${var.name_prefix}-address"
  region  = var.region
  project = var.project_id

  depends_on = [google_project_service.required]
}

resource "google_compute_instance" "wikantik" {
  name         = var.name_prefix
  machine_type = var.machine_type
  zone         = var.zone
  project      = var.project_id
  tags         = [var.name_prefix]

  boot_disk {
    initialize_params {
      image = data.google_compute_image.ubuntu.self_link
      size  = 20
      type  = "pd-balanced"
    }
  }

  network_interface {
    subnetwork = data.google_compute_subnetwork.selected.self_link

    access_config {
      nat_ip = google_compute_address.wikantik.address
    }
  }

  service_account {
    email  = google_service_account.instance.email
    scopes = ["cloud-platform"]
  }

  metadata = merge(
    { user-data = local.cloud_init_rendered },
    var.ssh_public_key == "" ? {} : { ssh-keys = "ubuntu:${var.ssh_public_key}" },
  )

  lifecycle {
    # data.google_compute_image.ubuntu resolves to whatever Canonical's
    # ubuntu-2404-lts-amd64 family currently points at — Canonical
    # publishes fresh 24.04 images frequently, and without this pin every
    # `terraform apply` after a new image release would propose replacing
    # the instance (changing an instance's boot image requires
    # replacement on GCE, same constraint as AWS's AMI) as a surprise side
    # effect of an unrelated change. Keep the running instance on the
    # image it booted from; opt into an image refresh deliberately with
    # `terraform apply -replace=google_compute_instance.wikantik` (safe —
    # see README's persistence model).
    ignore_changes = [boot_disk[0].initialize_params[0].image]
  }

  depends_on = [google_project_service.required]
}

resource "google_dns_record_set" "wikantik" {
  count = var.dns_managed_zone == "" ? 0 : 1

  name         = "${trimsuffix(var.domain, ".")}."
  type         = "A"
  ttl          = 300
  managed_zone = var.dns_managed_zone
  project      = var.project_id
  rrdatas      = [google_compute_address.wikantik.address]

  depends_on = [google_project_service.required]
}
