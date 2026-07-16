#
# deploy/gcp/variables.tf — inputs for the GCP single-VM Terraform reference
# deployment (see README.md). Mirrors deploy/aws/variables.tf's surface
# (region -> region/zone, tier, ingress, secrets map, wikantik_image, admin_cidr)
# adjusted for GCP-specific concepts (project, network/subnetwork, DNS zone,
# SSH via metadata key instead of an EC2 key pair name).
#

variable "project_id" {
  description = <<-EOT
    GCP project ID to deploy into. No default — every resource in this
    module (instance, disks, secrets, service account, firewall rules)
    lives in exactly one project, unlike AWS's account-wide default VPC
    assumption.
  EOT
  type        = string
}

variable "region" {
  description = "GCP region to deploy into."
  type        = string
  default     = "us-central1"
}

variable "zone" {
  description = <<-EOT
    GCP zone to deploy the instance and its data disk into. Must be a zone
    within var.region (validated) — GCP zone names are always
    "<region>-<letter>", e.g. "us-central1-a".
  EOT
  type        = string
  default     = "us-central1-a"

  validation {
    condition     = startswith(var.zone, "${var.region}-")
    error_message = "var.zone must be a zone within var.region, e.g. region \"us-central1\" -> zone \"us-central1-a\"."
  }
}

variable "name_prefix" {
  description = <<-EOT
    Prefix applied to every named GCP resource (firewall rules, service
    account, static address, disk, resource policy, secret IDs, network
    tag) so this module can be instantiated more than once in the same
    project without collisions. GCP resource names are far stricter than
    AWS's (lowercase letters/digits/hyphens only, must start with a
    letter) — validated accordingly, since it also doubles as the network
    tag firewall rules match on.
  EOT
  type        = string
  default     = "wikantik"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]*$", var.name_prefix))
    error_message = "name_prefix must start with a lowercase letter and contain only lowercase letters, digits, and hyphens (GCP resource-name and network-tag rules)."
  }
}

variable "network_name" {
  description = <<-EOT
    Name of the VPC network to deploy into. Default "default" — every GCP
    project has an auto-mode default network (with one auto-created
    subnet per region) unless deliberately deleted, mirroring the AWS
    module's default-VPC assumption. Override only if you have a
    non-default network you want this reference deployment to use.
  EOT
  type        = string
  default     = "default"
}

variable "subnetwork_name" {
  description = <<-EOT
    Name of the subnetwork (within var.network_name, var.region) to attach
    the instance's NIC to. Default "default" — the auto-created subnet
    name in every region of an auto-mode network.
  EOT
  type        = string
  default     = "default"
}

variable "machine_type" {
  description = <<-EOT
    GCE machine type. e2-standard-2 (2 vCPU / 8 GB) is the default —
    parity with AWS's t3.large default, comfortably covers the
    `search`/`knowledge` tiers (the ollama-embed CPU sidecar alongside the
    app + bundled DB). Pass `-var machine_type=e2-medium` (2 vCPU
    burstable / 4 GB, the floor this module supports) for the `core` tier
    (BM25-only, no embeddings sidecar) — see the cost table in README.md.
  EOT
  type        = string
  default     = "e2-standard-2"
}

variable "domain" {
  description = <<-EOT
    Public domain name for the wiki (e.g. wiki.example.com). Required when
    var.ingress = "caddy" (Caddy requests a Let's Encrypt cert for it, or
    falls back to an internal cert for "localhost") and used as the record
    name when var.dns_managed_zone is set. May be left "" when
    var.ingress = "cloudflared" and you are not asking this module to
    manage DNS (the Cloudflare Tunnel supplies its own routing).
  EOT
  type        = string
  default     = ""

  # Cross-variable validation (var.ingress) requires Terraform >= 1.9 —
  # see the required_version constraint in main.tf.
  validation {
    condition     = var.ingress != "caddy" || var.domain != ""
    error_message = "var.ingress = \"caddy\" requires a non-empty var.domain (set to \"localhost\" for an internal-cert smoke test)."
  }
}

variable "admin_cidr" {
  description = <<-EOT
    CIDR block allowed to reach TCP/22 (SSH). No default on purpose — you
    must explicitly choose this (e.g. "203.0.113.4/32" for a single admin
    IP). Never set this to 0.0.0.0/0.
  EOT
  type        = string

  validation {
    condition     = can(cidrhost(var.admin_cidr, 0))
    error_message = "admin_cidr must be a valid CIDR block, e.g. 203.0.113.4/32."
  }

  validation {
    condition     = !contains(["0.0.0.0/0", "::/0"], var.admin_cidr)
    error_message = "admin_cidr must not be 0.0.0.0/0 (or ::/0) — SSH open to the whole internet defeats the point of this rule; use your own address, e.g. 203.0.113.4/32."
  }
}

variable "ssh_public_key" {
  description = <<-EOT
    OpenSSH public key content (e.g. the contents of ~/.ssh/id_ed25519.pub)
    to grant SSH access, written to the instance's "ssh-keys" metadata as
    "ubuntu:<key>" (Ubuntu's cloud-init default user, matching the AWS
    module's "ubuntu" login). Leave "" (default) to boot with no SSH key
    at all — same posture as AWS's var.ssh_key_name = null: you would then
    need an out-of-band recovery path (e.g. the Cloud Console's serial
    console, or GCP OS Login — neither wired by this minimal module) to
    reach the instance.
  EOT
  type        = string
  default     = ""
}

variable "ghcr_user" {
  description = "GHCR (GitHub Container Registry) username used to pull the private wikantik image."
  type        = string
}

variable "ghcr_token" {
  description = <<-EOT
    GHCR read token (a GitHub PAT with read:packages scope, or a fine-
    grained token scoped to the wikantik package) used to pull the
    private wikantik image. Stored as a Secret Manager secret version,
    never written to Terraform state in plaintext (state itself should
    still be encrypted/access-controlled per your backend).
  EOT
  type        = string
  sensitive   = true
}

variable "wikantik_image" {
  description = <<-EOT
    Full image reference INCLUDING TAG, e.g.
    "ghcr.io/jakefearsd/wikantik:1.4.2". No default — pin an explicit
    tag rather than ":latest" so `terraform apply` is reproducible; use
    deploy/bin/wikantik-update.sh (installed on the VM) for routine
    tag-to-tag upgrades instead of re-applying Terraform each time.
    Tag-style references only (repo:tag) — @sha256 digest pins are not
    supported by the repo/tag split this module performs for
    /etc/wikantik-update.conf.
  EOT
  type        = string

  validation {
    # A trailing ":tag" (no '/' after the colon) must be present — this is
    # what makes main.tf's repo/tag split (regex "^(.*):[^:/]+$")
    # well-defined, including for registry:port/repo:tag references.
    condition     = can(regex("^(.*):[^:/]+$", var.wikantik_image))
    error_message = "wikantik_image must include an explicit tag, e.g. ghcr.io/jakefearsd/wikantik:1.4.2 (\":latest\" is legal but discouraged)."
  }
}

variable "tier" {
  description = <<-EOT
    GenAI cost tier — mirrors the matrix documented in
    docker-compose.cloud.yml's header:
      core      WIKANTIK_GENAI_MODE=none, WIKANTIK_KNOWLEDGE_ENABLED=false
                (BM25-only search, zero inference infra)
      search    WIKANTIK_GENAI_MODE=embeddings-only, WIKANTIK_KNOWLEDGE_ENABLED=false,
                WIKANTIK_EMBEDDING_BASE_URL=http://ollama-embed:11434
                (dense+BM25 hybrid retrieval via the CPU embeddings sidecar)
      knowledge WIKANTIK_GENAI_MODE=full, WIKANTIK_KNOWLEDGE_ENABLED=true,
                WIKANTIK_EXTRACTOR_BACKEND=claude, WIKANTIK_EMBEDDING_BASE_URL=http://ollama-embed:11434
                (full KG extraction via the Claude API — requires
                var.secrets["ANTHROPIC_API_KEY"])
    "search" and "knowledge" both activate the compose `embeddings`
    profile (the ollama-embed sidecar); "core" does not.
  EOT
  type        = string
  default     = "core"

  validation {
    condition     = contains(["core", "search", "knowledge"], var.tier)
    error_message = "tier must be one of: core, search, knowledge."
  }
}

variable "ingress" {
  description = <<-EOT
    Ingress method — activates the matching docker-compose.cloud.yml
    profile:
      caddy       Let's Encrypt (or internal cert for var.domain =
                  "localhost") reverse proxy on 80/443. Requires
                  var.domain.
      cloudflared Cloudflare Tunnel sidecar — no public ports needed on
                  the firewall side, but 80/443 stay open in the firewall
                  rules regardless (deliberately minimal — see README).
                  Requires var.secrets["CLOUDFLARE_TUNNEL_TOKEN"].
  EOT
  type        = string
  default     = "caddy"

  validation {
    condition     = contains(["caddy", "cloudflared"], var.ingress)
    error_message = "ingress must be one of: caddy, cloudflared."
  }
}

variable "secrets" {
  description = <<-EOT
    Map of secret KEY => VALUE, one google_secret_manager_secret (+ single
    version) per entry (plus var.ghcr_user/var.ghcr_token, stored the same
    way). These become env vars in the VM's /opt/wikantik/.env via a
    boot-time Secret Manager fetch over the metadata-token curl pattern
    (never baked into the image, instance metadata, or Terraform state in
    plaintext-at-rest — state encryption is still your responsibility).

    Expected keys:
      POSTGRES_PASSWORD      always required (the bundled pgvector
                              container's app password)
      CLOUDFLARE_TUNNEL_TOKEN required iff var.ingress = "cloudflared"
      ANTHROPIC_API_KEY       required iff var.tier = "knowledge"
    Extra keys are passed through as Secret Manager secrets + .env entries
    unmodified (e.g. WIKANTIK_SCIM_TOKEN, WIKANTIK_CONNECTORS_CRYPTO_KEY),
    which is useful but not required for the reference deployment.
  EOT
  type        = map(string)
  sensitive   = true

  # nonsensitive() only strips the sensitivity marking from the KEY LIST
  # (field names, never secret material) so these conditions can be
  # evaluated and their (static-text-only) error messages printed.
  validation {
    condition     = contains(nonsensitive(keys(var.secrets)), "POSTGRES_PASSWORD")
    error_message = "var.secrets must include a POSTGRES_PASSWORD entry (the bundled pgvector container's app password)."
  }

  # Keys become .env entries AND shell variable names inside the
  # cloud-init secret-fetch loop, PLUS Secret Manager secret_id suffixes —
  # enforce env-var-legal names so the generated script can never be
  # syntactically broken by a key (Secret Manager's own secret_id charset,
  # [a-zA-Z0-9_-]+, is a superset of this, so no separate check is needed
  # for that side).
  validation {
    condition     = alltrue([for k in nonsensitive(keys(var.secrets)) : can(regex("^[A-Za-z_][A-Za-z0-9_]*$", k))])
    error_message = "every var.secrets key must be a valid environment-variable name ([A-Za-z_][A-Za-z0-9_]*)."
  }

  # Cross-variable validation (var.ingress) requires Terraform >= 1.9 —
  # see the required_version constraint in main.tf.
  validation {
    condition     = var.ingress != "cloudflared" || contains(nonsensitive(keys(var.secrets)), "CLOUDFLARE_TUNNEL_TOKEN")
    error_message = "var.ingress = \"cloudflared\" requires var.secrets to include a CLOUDFLARE_TUNNEL_TOKEN entry."
  }

  # Cross-variable validation (var.tier) requires Terraform >= 1.9 — see
  # the required_version constraint in main.tf.
  validation {
    condition     = var.tier != "knowledge" || contains(nonsensitive(keys(var.secrets)), "ANTHROPIC_API_KEY")
    error_message = "var.tier = \"knowledge\" requires var.secrets to include an ANTHROPIC_API_KEY entry (Claude extractor backend)."
  }
}

variable "dns_managed_zone" {
  description = <<-EOT
    Name (not DNS name) of an existing Cloud DNS managed zone to create an
    A record in for var.domain, pointing at the instance's static external
    IP. Leave "" (default) to manage DNS yourself (e.g. point an existing
    record at the output external_ip).
  EOT
  type        = string
  default     = ""
}

variable "snapshot_retention_days" {
  description = "How many daily snapshots of the /srv/wikantik data disk to retain."
  type        = number
  default     = 14
}

variable "data_volume_size_gb" {
  description = <<-EOT
    Size (GiB) of the persistent pd-balanced disk mounted at
    /srv/wikantik, which holds the entire relocated Docker data-root (so
    wikantik-pages, pgdata when var.tier uses bundled-db, etc. all
    survive instance replacement) plus the backup sidecar's tarball/pg_dump
    output. Default matches the AWS module's default (50 GiB).
  EOT
  type        = number
  default     = 50
}

variable "wikantik_mem_limit" {
  description = "Memory limit passed to the wikantik container (docker-compose.cloud.yml's WIKANTIK_MEM_LIMIT)."
  type        = string
  default     = "2G"
}

# Cross-variable enforcement lives inline as `validation` blocks on
# var.secrets and var.domain above (Terraform >= 1.9 allows a variable's
# validation to reference other variables) — deliberately NOT `check`
# blocks: `check` only ever produces a warning, never blocks
# plan/apply, and these constraints (e.g. "cloudflared ingress requires a
# tunnel-token secret") are meant to be hard requirements. Same posture as
# deploy/aws/variables.tf.
