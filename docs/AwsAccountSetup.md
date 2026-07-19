# Setting Up a New AWS Account for Wikantik

How to take a brand-new (or unused) AWS account from zero to ready for the
[single-VM Terraform reference deployment](../deploy/aws/README.md): account
creation and hardening, an admin identity for the CLI, billing guardrails,
and installation of the required command-line tools on **macOS** and
**Ubuntu**.

> This page ends where [deploy/aws/README.md](../deploy/aws/README.md)
> begins — that README is the canonical step-by-step for the Terraform
> module itself. For the operator-facing overview (compose overlay, GenAI
> cost tiers, pull-based updates), see
> [CloudDeployment.md](CloudDeployment.md). Different cloud? See
> [GcpAccountSetup.md](GcpAccountSetup.md) or
> [AzureAccountSetup.md](AzureAccountSetup.md).

## 1. Create the account

1. Sign up at [aws.amazon.com](https://aws.amazon.com/). The email you use
   becomes the **root user** — prefer an address you'll retain long-term
   (a role/distribution address rather than a personal one, if this account
   might ever be shared).
2. Choose a support plan (Basic/free is fine for this deployment).
3. Pick your **home region** now and stay consistent — the Terraform module
   defaults to `us-east-1` (`var.region`). Everything the module creates is
   regional except IAM.

The module deploys into the account's **default VPC**. Every region has one
unless it was deliberately deleted; on a fresh account you don't need to do
anything. (If it's ever missing: `aws ec2 create-default-vpc`.)

## 2. Secure the root user

Do this immediately, before anything else:

- **Enable MFA on the root user** (IAM console → root user → MFA). A
  passkey/authenticator app is fine; anything beats nothing.
- **Never create root access keys.** The root user is for account-level
  emergencies (billing, account closure, recovering the admin identity) —
  daily work happens through the admin identity below.

## 3. Create an admin identity for the CLI

Terraform needs credentials with permission to manage
**EC2 / EBS / DLM / IAM / SSM / Route53** (the resources listed in
[deploy/aws/README.md — What gets created](../deploy/aws/README.md#what-gets-created)).
For a reference deployment like this, an administrator identity is the
simplest starting point. Two options:

### Option A — IAM Identity Center (recommended)

Short-lived credentials, no long-lived access key on disk:

1. Console → **IAM Identity Center** → Enable (accept the default
   organization instance).
2. Create a **user** for yourself (this is a separate login from the root
   user, with its own MFA — enable that too).
3. Create a **permission set** from the predefined `AdministratorAccess`
   managed policy, and **assign** the user + permission set to the account.
4. Note the **AWS access portal URL** shown on the Identity Center
   dashboard (`https://<something>.awsapps.com/start`).

After installing the AWS CLI (section 5), wire it up:

```bash
aws configure sso
# SSO session name: wikantik
# SSO start URL:    https://<something>.awsapps.com/start
# SSO region:       <the region Identity Center lives in>
# → browser opens to authorize; pick the account + AdministratorAccess
# CLI default region: us-east-1   (match var.region)
# Profile name:       wikantik
```

Then before any Terraform work:

```bash
aws sso login --profile wikantik
export AWS_PROFILE=wikantik
```

### Option B — IAM user with an access key (simpler, less safe)

If Identity Center feels like too much ceremony for a personal account:

1. Console → **IAM** → Users → Create user (e.g. `wikantik-admin`), no
   console access needed.
2. Attach the `AdministratorAccess` managed policy directly.
3. Create an **access key** (use case: CLI), and store the secret somewhere
   real (password manager) — it's shown once.

```bash
aws configure --profile wikantik
# AWS Access Key ID / Secret Access Key: from step 3
# Default region name: us-east-1
export AWS_PROFILE=wikantik
```

Long-lived keys are a standing liability — rotate them periodically, and
prefer Option A if the account will live for years.

## 4. Billing guardrails

The reference deployment's floor cost is roughly **$35–70/month** depending
on tier (see the [cost table](../deploy/aws/README.md#cost-rough-us-east-1-on-demand-pricing--verify-current-pricing-before-relying-on-this));
the `knowledge` tier adds pay-per-token Anthropic API usage on top. Before
creating any infrastructure:

1. Console → **Billing and Cost Management** → **Budgets** → create a
   monthly cost budget at ~2× your expected tier cost (e.g. $150), with an
   email alert at 80% actual and 100% forecast.
2. Optionally enable **Cost Anomaly Detection** (same console section) —
   free, and it catches "I forgot to `terraform destroy` the experiment"
   class mistakes.

## 5. Install the command-line tools

You need two tools on the machine you'll run Terraform from:

| Tool | Version | Why |
|---|---|---|
| AWS CLI | v2 | Credentials/SSO, key pair creation, snapshot/restore operations |
| Terraform | **>= 1.9** | The module uses cross-variable `validation` blocks introduced in 1.9 |

### macOS (Homebrew)

```bash
brew install awscli
brew tap hashicorp/tap
brew install hashicorp/tap/terraform
```

(No Homebrew? AWS also ships a signed `.pkg` installer —
[docs](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html) —
and Terraform a plain binary zip from
[developer.hashicorp.com/terraform/install](https://developer.hashicorp.com/terraform/install).)

### Ubuntu

**AWS CLI v2** — the `awscli` package in Ubuntu's own archive is the
outdated v1; use the official installer instead:

```bash
sudo apt-get update && sudo apt-get install -y curl unzip
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o /tmp/awscliv2.zip
unzip -q /tmp/awscliv2.zip -d /tmp
sudo /tmp/aws/install
```

(On an ARM machine, substitute `awscli-exe-linux-aarch64.zip`.)

**Terraform** — from HashiCorp's apt repository:

```bash
sudo apt-get install -y gnupg software-properties-common
wget -O- https://apt.releases.hashicorp.com/gpg | \
  gpg --dearmor | sudo tee /usr/share/keyrings/hashicorp-archive-keyring.gpg > /dev/null
echo "deb [signed-by=/usr/share/keyrings/hashicorp-archive-keyring.gpg] \
  https://apt.releases.hashicorp.com $(lsb_release -cs) main" | \
  sudo tee /etc/apt/sources.list.d/hashicorp.list
sudo apt-get update && sudo apt-get install -y terraform
```

### Verify

```bash
aws --version         # aws-cli/2.x — v1 will not do SSO properly
terraform version     # Terraform v1.9.0 or newer
```

## 6. Verify account access

With `AWS_PROFILE` set (section 3):

```bash
aws sts get-caller-identity     # your account ID + admin identity ARN
aws ec2 describe-vpcs --region us-east-1 \
  --filters Name=is-default,Values=true \
  --query 'Vpcs[0].VpcId' --output text   # a vpc-… id, not "None"
```

If `get-caller-identity` fails, your credentials/SSO session are the
problem — fix that before touching Terraform.

## 7. Create an EC2 key pair (for SSH access)

The module's `var.ssh_key_name` references an **existing** key pair — and
without one you have no shell access to the VM (the module doesn't wire up
EC2 Instance Connect or SSM Session Manager). Create one in your target
region:

```bash
aws ec2 create-key-pair --key-name wikantik --key-type ed25519 \
  --region us-east-1 \
  --query 'KeyMaterial' --output text > ~/.ssh/wikantik.pem
chmod 600 ~/.ssh/wikantik.pem
```

You'll pass `ssh_key_name = "wikantik"` to Terraform, and later
`ssh -i ~/.ssh/wikantik.pem ubuntu@<eip>`.

## 8. Gather the remaining inputs

Two things the Terraform module needs that don't come from AWS:

- **GHCR read token** — the `ghcr.io/jakefearsd/wikantik` image is private;
  ask the maintainer for a token (a GitHub PAT with `read:packages`, or a
  fine-grained token scoped to the package).
- **Your public IP** for `admin_cidr` (SSH is locked to this CIDR;
  `0.0.0.0/0` is rejected by validation):

  ```bash
  echo "$(curl -s https://checkip.amazonaws.com)/32"
  ```

Plus a domain you control if you're using the `caddy` ingress profile
(Let's Encrypt needs it), or a Cloudflare tunnel token for `cloudflared`.

## Next step

Continue with **[deploy/aws/README.md](../deploy/aws/README.md)** — the
variable table, `terraform.tfvars` example, walkthrough, persistence model,
and restore/teardown runbooks.
