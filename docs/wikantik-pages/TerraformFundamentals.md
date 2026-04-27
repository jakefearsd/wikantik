---
canonical_id: 01KQ0P44XGNSFNP7EDMCTSFEBY
title: Terraform Fundamentals
type: article
cluster: cloud-platforms
status: active
date: '2026-04-26'
summary: How Terraform actually works — providers, state, plan/apply, modules — and
  the practical patterns for using IaC at production scale, plus the trade-offs vs.
  alternatives like Pulumi or CDK.
tags:
- terraform
- infrastructure-as-code
- iac
- cloud
related:
- AwsFundamentals
- CloudNativeApplicationDesign
- CloudSecurityFundamentals
- MonorepoVsPolyrepo
hubs:
- CloudPlatforms Hub
---
# Terraform Fundamentals

Terraform is the dominant infrastructure-as-code tool. You declare desired infrastructure in HCL (HashiCorp Configuration Language); Terraform reconciles the declaration against actual cloud state and applies changes. The model is declarative: describe what you want, not how to get there.

This page covers the core concepts and the patterns that make Terraform sustainable at scale.

## The core concepts

### Providers

A Terraform provider implements the API for a specific platform: AWS, Azure, GCP, Kubernetes, GitHub, Datadog, etc. The provider knows how to create, read, update, and delete each resource type.

```hcl
terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = "us-west-2"
}
```

Providers are versioned. Pin them; floating versions break things.

### Resources

A resource declares one piece of infrastructure:

```hcl
resource "aws_s3_bucket" "example" {
  bucket = "my-bucket-name"
  tags = {
    Environment = "production"
  }
}
```

Terraform creates the bucket if it doesn't exist; updates it if the declaration changes; deletes it if removed from config.

### State

Terraform tracks the mapping from declared resources to actual cloud resources in a state file. State is the source of truth for what Terraform manages.

State storage is critical:
- Local state: fine for solo work, breaks for teams
- Remote state (S3 + DynamoDB lock): standard for production
- Terraform Cloud: managed remote state with collaboration features

State must be shared and locked. Two engineers running `apply` simultaneously without locking corrupt the state.

### Plan and apply

```bash
terraform plan    # show what would change
terraform apply   # actually change it
```

`plan` is the safety net. Always review before applying. CI/CD typically requires plan output in PRs before apply is allowed.

### Modules

Reusable bundles of resources:

```hcl
module "vpc" {
  source = "terraform-aws-modules/vpc/aws"
  version = "~> 5.0"

  name = "my-vpc"
  cidr = "10.0.0.0/16"
}
```

The community has high-quality modules for common patterns. Use them instead of writing from scratch.

## Patterns at scale

### Workspace per environment

Terraform workspaces (or separate state files) isolate environments. Common: dev, staging, production each with its own state. The same code applies; the variables differ.

### Module composition

Larger projects compose modules:

```
infrastructure/
├── modules/
│   ├── vpc/
│   ├── ecs-cluster/
│   └── rds-postgres/
└── environments/
    ├── dev/
    │   └── main.tf  (uses the modules)
    └── prod/
        └── main.tf
```

Modules encode patterns; environments wire them together.

### Variables and outputs

Modules declare inputs (variables) and outputs:

```hcl
variable "instance_type" {
  type    = string
  default = "t3.medium"
}

output "instance_id" {
  value = aws_instance.example.id
}
```

Outputs from one module become inputs to another. This is the wiring.

### Secrets management

Don't put secrets in state files. Use:

- **Provider for secrets**: AWS Secrets Manager, HashiCorp Vault, etc.
- **Sensitive variable**: marks output as sensitive (won't show in CLI output)
- **External secret injection**: pass secrets at apply time, not stored in code

## What Terraform doesn't do well

### Imperative operations

"Run this script on the instance" — Terraform handles infrastructure, not configuration. For that, use Ansible, Chef, cloud-init, or container images.

### Application deployment

Terraform creates infrastructure; deploying application code on it is a separate concern. CI/CD tools handle that.

### Drift detection at scale

Terraform tracks what it manages; drift outside Terraform (someone clicks in the console) requires `terraform plan` to detect, and the response is awkward.

## Alternatives

### Pulumi

IaC in real programming languages (TypeScript, Python, Go, C#). More flexible than HCL; familiar control flow. Trade-off: less standardized; smaller ecosystem; more code complexity for simple cases.

### AWS CDK

Cloud Development Kit — code that generates CloudFormation. AWS-specific. For AWS-only shops, sometimes preferred over Terraform.

### CloudFormation

AWS's native IaC. Mature; tightly integrated with AWS. Less popular than Terraform because CloudFormation's evolution is slow and Terraform's multi-cloud story is appealing.

For most multi-cloud or even single-cloud usage, Terraform remains the default.

## Common failure patterns

- **Local state in team environments.** Use remote state with locking.
- **Hardcoded values everywhere.** Use variables; parameterize per environment.
- **Massive monolithic Terraform.** Refactor into modules; smaller blast radius.
- **Manual state changes.** Editing state directly is dangerous; rare cases need it.
- **No CI/CD.** Apply from local machines means no audit, no review.
- **Provider version drift.** Pin versions; upgrade deliberately.

## Further Reading

- [AwsFundamentals](AwsFundamentals) — Most common Terraform target
- [CloudNativeApplicationDesign](CloudNativeApplicationDesign) — What you provision
- [CloudSecurityFundamentals](CloudSecurityFundamentals) — Secrets, IAM
- [MonorepoVsPolyrepo](MonorepoVsPolyrepo) — Where Terraform code lives
- [CloudPlatforms Hub](CloudPlatforms+Hub) — Cluster index
