#
# deploy/aws/main.tf — AWS single-VM Terraform reference deployment for
# Wikantik (Task 2.3, docs/superpowers/plans/2026-07-16-aws-gcp-deployment-
# readiness.md Phase 2). Deliberately minimal: default VPC, one EC2
# instance, one persistent EBS data volume + daily DLM snapshots, one
# security group, one EIP, an optional Route53 record, and SSM Parameter
# Store for secrets. No ECS/ALB/multi-AZ ambitions — see README.md.
#
# A real `terraform apply` is DEFERRED for this task (no AWS credentials on
# the authoring machine) — see README.md's Validation section for exactly
# what was and wasn't exercised.
#

terraform {
  # >= 1.9 for cross-variable `validation` blocks (variables.tf: var.secrets
  # and var.domain validations reference var.ingress/var.tier).
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
  }
}

provider "aws" {
  region = var.region
}

# -----------------------------------------------------------------------
# Default VPC + subnet (deliberately minimal — no custom VPC/subnetting).
# -----------------------------------------------------------------------

data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

data "aws_subnet" "selected" {
  id = data.aws_subnets.default.ids[0]
}

# Ubuntu 24.04 LTS (Noble Numbat), amd64, HVM/gp3 — Canonical's official
# owner ID.
data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"]

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }

  filter {
    name   = "root-device-type"
    values = ["ebs"]
  }
}

# -----------------------------------------------------------------------
# Security group — 22/admin_cidr, 80+443/world, nothing else. Egress is
# wide open (docker pulls from GHCR/Docker Hub, SSM API, apt, and — for
# tier=knowledge — the Anthropic API all need arbitrary outbound HTTPS).
# -----------------------------------------------------------------------

resource "aws_security_group" "wikantik" {
  name        = "${var.name_prefix}-sg"
  description = "Wikantik single-VM reference deployment (22/admin, 80+443/world)"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "SSH (admin only)"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.admin_cidr]
  }

  ingress {
    description = "HTTP"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.name_prefix}-sg"
  }
}

# -----------------------------------------------------------------------
# IAM — instance role scoped to exactly the SSM parameters this module
# creates, plus kms:Decrypt on the SSM default key (SecureString params
# with no explicit KeyId use "alias/aws/ssm").
# -----------------------------------------------------------------------

data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "instance" {
  name               = "${var.name_prefix}-instance"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

data "aws_kms_alias" "ssm" {
  name = "alias/aws/ssm"
}

data "aws_iam_policy_document" "ssm_read" {
  statement {
    sid       = "GetWikantikSecrets"
    actions   = ["ssm:GetParameter", "ssm:GetParameters"]
    resources = [for p in aws_ssm_parameter.secret : p.arn]
  }

  statement {
    sid       = "DecryptWithSsmDefaultKey"
    actions   = ["kms:Decrypt"]
    resources = [data.aws_kms_alias.ssm.target_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["ssm.${var.region}.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "ssm_read" {
  name   = "${var.name_prefix}-ssm-read"
  role   = aws_iam_role.instance.id
  policy = data.aws_iam_policy_document.ssm_read.json
}

resource "aws_iam_instance_profile" "instance" {
  name = "${var.name_prefix}-instance"
  role = aws_iam_role.instance.name
}

# -----------------------------------------------------------------------
# SSM Parameter Store — one SecureString per secret, PLUS the GHCR
# credentials (folded into the same map so the instance role's policy and
# the cloud-init fetch loop both treat them uniformly).
# -----------------------------------------------------------------------

locals {
  all_secrets = merge(var.secrets, {
    GHCR_USER  = var.ghcr_user
    GHCR_TOKEN = var.ghcr_token
  })
}

resource "aws_ssm_parameter" "secret" {
  for_each = local.all_secrets

  name  = "/${var.name_prefix}/secrets/${each.key}"
  type  = "SecureString"
  value = each.value

  tags = {
    Name = "${var.name_prefix}-${each.key}"
  }
}

# -----------------------------------------------------------------------
# Persistent data volume — /srv/wikantik. Docker's data-root is relocated
# here (see cloud-init template) so the LIVE wikantik-pages/pgdata/etc.
# named volumes survive instance replacement, not just the nightly backup
# tarball (which also lands here, via BACKUP_DIR). Tagged for the DLM
# policy below.
# -----------------------------------------------------------------------

resource "aws_ebs_volume" "data" {
  availability_zone = data.aws_subnet.selected.availability_zone
  size              = var.data_volume_size_gb
  type              = "gp3"

  tags = {
    Name                        = "${var.name_prefix}-data"
    "${var.name_prefix}-backup" = "true"
  }
}

resource "aws_volume_attachment" "data" {
  # The device_name hint is required by the API but Nitro-based instance
  # families (t3 included) expose EBS volumes as NVMe devices whose kernel
  # name (/dev/nvme1n1, /dev/nvme2n1, ...) is not reliably predictable from
  # this hint alone — the cloud-init template instead addresses the volume
  # via the stable /dev/disk/by-id/nvme-Amazon_Elastic_Block_Store_<volid>
  # symlink (local.data_device below), which AWS guarantees regardless of
  # attachment order.
  device_name = "/dev/sdf"
  volume_id   = aws_ebs_volume.data.id
  instance_id = aws_instance.wikantik.id
}

resource "aws_iam_role" "dlm" {
  name = "${var.name_prefix}-dlm"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = "sts:AssumeRole"
      Principal = {
        Service = "dlm.amazonaws.com"
      }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "dlm" {
  role       = aws_iam_role.dlm.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSDataLifecycleManagerServiceRole"
}

resource "aws_dlm_lifecycle_policy" "data_volume" {
  description        = "${var.name_prefix} daily snapshots of the /srv/wikantik data volume"
  execution_role_arn = aws_iam_role.dlm.arn
  state              = "ENABLED"

  policy_details {
    resource_types = ["VOLUME"]

    target_tags = {
      "${var.name_prefix}-backup" = "true"
    }

    schedule {
      name = "daily"

      create_rule {
        interval      = 24
        interval_unit = "HOURS"
        times         = ["03:00"]
      }

      retain_rule {
        count = var.snapshot_retention_days
      }

      tags_to_add = {
        SnapshotCreator = "dlm"
      }

      copy_tags = true
    }
  }
}

# -----------------------------------------------------------------------
# Cloud-init rendering — see deploy/cloud-init/cloud-init.yaml.tftpl for
# the full template-variable contract. Everything AWS-specific (SSM
# fetch command, IMDSv2 region lookup, the NVMe by-id data device path) is
# assembled here as plain values; the template itself has no AWS-specific
# logic so GCP's module (Task 3.1) can reuse it unmodified.
# -----------------------------------------------------------------------

locals {
  # Stable NVMe-by-id path — see the device_name comment above.
  data_device = "/dev/disk/by-id/nvme-Amazon_Elastic_Block_Store_${replace(aws_ebs_volume.data.id, "-", "")}"

  # Lightest headless SSM client: the `awscli` apt package (Python-based)
  # needs one apt-get install, versus AWS CLI v2's curl+unzip+installer
  # dance for equivalent functionality here.
  secret_fetch_prereqs = <<-EOT
    apt-get update -y
    apt-get install -y --no-install-recommends awscli
  EOT

  # Run once before any fetch_secret call: resolve the instance's own
  # region via IMDSv2 (not baked in by Terraform) so the rendered image
  # stays correct even if reused outside this exact module invocation.
  secret_fetch_setup = <<-EOT
    IMDS_TOKEN=$(curl -sf -X PUT "http://169.254.169.254/latest/api/token" -H "X-aws-ec2-metadata-token-ttl-seconds: 21600")
    export AWS_DEFAULT_REGION=$(curl -sf -H "X-aws-ec2-metadata-token: $IMDS_TOKEN" "http://169.254.169.254/latest/meta-data/placement/region")
  EOT

  # $1 = SSM parameter name; must print the decrypted value to stdout.
  secret_fetch_cmd = "aws ssm get-parameter --with-decryption --name \"$1\" --query 'Parameter.Value' --output text"

  # One `echo KEY=$(fetch_secret 'ssm-name') >> $ENV_FILE` line per secret,
  # sorted for a deterministic rendered script (and thus a stable
  # `terraform plan` diff when secrets don't actually change).
  fetch_secrets_block = join("\n", [
    for k in sort(keys(local.all_secrets)) :
    "echo \"${k}=$(fetch_secret '${aws_ssm_parameter.secret[k].name}')\" >> \"$ENV_FILE\""
  ])

  compose_profiles = compact(concat(
    ["bundled-db", var.ingress],
    var.tier == "core" ? [] : ["embeddings"],
  ))
  compose_profile_args  = join(" ", [for p in local.compose_profiles : "--profile ${p}"])
  compose_profile_names = join(" ", local.compose_profiles)

  wikantik_repo_dir   = "/opt/wikantik"
  wikantik_image_repo = split(":", var.wikantik_image)[0]
  health_url          = "http://localhost:8080/api/health"

  # Non-secret .env content — GenAI tier preset (matching
  # docker-compose.cloud.yml's header table) + ingress preset + the image
  # ref + BACKUP_DIR pointed at the persistent volume. Secrets are
  # appended on top of this at boot (see fetch_secrets_block above).
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
# Instance + EIP + optional Route53 record.
# -----------------------------------------------------------------------

resource "aws_instance" "wikantik" {
  ami                    = data.aws_ami.ubuntu.id
  instance_type          = var.instance_type
  subnet_id              = data.aws_subnet.selected.id
  vpc_security_group_ids = [aws_security_group.wikantik.id]
  iam_instance_profile   = aws_iam_instance_profile.instance.name
  key_name               = var.ssh_key_name

  # Required per the task brief — refuses IMDSv1, closing the classic
  # SSRF-to-credentials path.
  metadata_options {
    http_endpoint = "enabled"
    http_tokens   = "required"
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 20
  }

  # gzip'd: the assembled cloud-init (compose files + Caddyfile + backup
  # scripts + wikantik-update.sh, all base64'd, plus the shell scaffolding)
  # comfortably exceeds the conservative 16 KiB raw EC2 user-data limit;
  # cloud-init auto-detects and decompresses gzip-magic user-data on boot.
  user_data_base64            = base64gzip(local.cloud_init_rendered)
  user_data_replace_on_change = true

  tags = {
    Name = var.name_prefix
  }
}

resource "aws_eip" "wikantik" {
  domain   = "vpc"
  instance = aws_instance.wikantik.id

  tags = {
    Name = "${var.name_prefix}-eip"
  }
}

resource "aws_route53_record" "wikantik" {
  count = var.route53_zone_id == "" ? 0 : 1

  zone_id = var.route53_zone_id
  name    = var.domain
  type    = "A"
  ttl     = 300
  records = [aws_eip.wikantik.public_ip]
}
