# Setting Up a New GCP Account for Wikantik

How to take a brand-new (or unused) Google Cloud account from zero to ready
for the [single-VM Terraform reference deployment](../deploy/gcp/README.md):
account and project creation, billing guardrails, credentials for
Terraform, and installation of the required command-line tools on
**macOS** and **Ubuntu**.

> This page ends where [deploy/gcp/README.md](../deploy/gcp/README.md)
> begins — that README is the canonical step-by-step for the Terraform
> module itself. For the operator-facing overview (compose overlay, GenAI
> cost tiers, pull-based updates), see
> [CloudDeployment.md](CloudDeployment.md). AWS instead? See
> [AwsAccountSetup.md](AwsAccountSetup.md).

## 1. Create the account and a billing account

1. Sign in (or sign up) at
   [console.cloud.google.com](https://console.cloud.google.com/) with a
   Google account. Unlike AWS, there is no separate "root user" — your
   Google account *is* the identity, so **enable 2-Step Verification on
   it** ([myaccount.google.com/security](https://myaccount.google.com/security))
   before putting infrastructure behind it.
2. Create a **billing account** (Console → Billing) and attach a payment
   method. New accounts typically get free-trial credits; the deployment
   below runs fine inside them.

## 2. Create a project

GCP scopes everything to a **project** (the module's required
`var.project_id`). Create a dedicated one rather than reusing a grab-bag
project:

```bash
gcloud projects create my-wikantik-project --name="Wikantik"
gcloud billing accounts list          # note the billing account ID
gcloud billing projects link my-wikantik-project \
  --billing-account=XXXXXX-XXXXXX-XXXXXX
gcloud config set project my-wikantik-project
```

(Or do all of this in the console — the only hard requirements are that
the project exists and **billing is linked**. The Terraform module enables
the APIs it needs — `compute`, `secretmanager`, `dns`, `iam` — itself, so
a fresh project works with no manual API enabling.)

The module deploys into the project's **default network**. Every new
project gets one automatically unless an organization policy disables
auto-created networks — on a personal (no-organization) account you don't
need to do anything. Verify later with `gcloud compute networks list`.

## 3. Credentials for Terraform

For a reference deployment, your own Google account with **Project
Editor** (or Owner — which you are, on a project you created) is the
simplest starting point; the module needs permission to manage Compute
Engine, Secret Manager, IAM, and Cloud DNS resources in the project.

After installing the gcloud CLI (section 5), authenticate twice — once
for the CLI itself, once to mint the **Application Default Credentials**
that Terraform's Google provider reads:

```bash
gcloud auth login                       # the gcloud CLI's own credential
gcloud auth application-default login   # ADC — what Terraform uses
```

Both open a browser to authorize. ADC lands in
`~/.config/gcloud/application_default_credentials.json`; no service
account key file is needed (and for a personal setup, prefer not creating
one — downloaded keys are long-lived liabilities, the same trade-off as
AWS access keys).

## 4. Billing guardrails

The reference deployment's floor cost is roughly **$30–60/month**
depending on tier (see the
[cost table](../deploy/gcp/README.md#cost-rough-us-central1-on-demand-pricing--verify-current-pricing-before-relying-on-this));
the `knowledge` tier adds pay-per-token Anthropic API usage on top.
Before creating any infrastructure:

1. Console → **Billing** → **Budgets & alerts** → create a monthly budget
   at ~2× your expected tier cost (e.g. $120), with email alerts at 50%,
   90%, and 100%.
2. Note that GCP budgets **alert, they don't cap** — nothing is shut off
   at 100%. The alert is your cue to investigate (usually "I forgot to
   `terraform destroy` the experiment").

## 5. Install the command-line tools

You need two tools on the machine you'll run Terraform from:

| Tool | Version | Why |
|---|---|---|
| gcloud CLI | current | Auth/ADC for Terraform, project setup, snapshot/restore operations |
| Terraform | **>= 1.9** | The module uses cross-variable `validation` blocks introduced in 1.9 |

### macOS (Homebrew)

```bash
brew install --cask gcloud-cli
brew tap hashicorp/tap
brew install hashicorp/tap/terraform
```

(No Homebrew? Google ships an interactive installer —
`curl https://sdk.cloud.google.com | bash`, then restart your shell — and
Terraform a plain binary zip from
[developer.hashicorp.com/terraform/install](https://developer.hashicorp.com/terraform/install).)

### Ubuntu

**gcloud CLI** — from Google's apt repository:

```bash
sudo apt-get update && sudo apt-get install -y apt-transport-https ca-certificates gnupg curl
curl https://packages.cloud.google.com/apt/doc/apt-key.gpg | \
  sudo gpg --dearmor -o /usr/share/keyrings/cloud.google.gpg
echo "deb [signed-by=/usr/share/keyrings/cloud.google.gpg] \
  https://packages.cloud.google.com/apt cloud-sdk main" | \
  sudo tee /etc/apt/sources.list.d/google-cloud-sdk.list
sudo apt-get update && sudo apt-get install -y google-cloud-cli
```

(`sudo snap install google-cloud-cli --classic` also works if you prefer
snaps.)

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
gcloud version
terraform version     # Terraform v1.9.0 or newer
```

## 6. Verify project access

```bash
gcloud auth list                        # your account, marked ACTIVE
gcloud config get-value project         # my-wikantik-project
gcloud projects describe my-wikantik-project   # confirms you can read it
gcloud compute networks list            # "default" should appear
```

The last command may prompt to enable the Compute API — that's fine
(the module would enable it anyway). If `networks list` comes back empty,
the project has no default network (organization policy); create one or
pass your own via `var.network_name`/`var.subnetwork_name`.

## 7. SSH key (for shell access)

Unlike the AWS module (which references a cloud-side EC2 key pair), the
GCP module takes an **OpenSSH public key string** directly
(`var.ssh_public_key`) and writes it to instance metadata — no cloud-side
resource to create. If you don't already have a key:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/wikantik -C "wikantik"
cat ~/.ssh/wikantik.pub    # this content is the value for ssh_public_key
```

Without it you have no shell access short of GCP console tooling the
module doesn't wire up (serial console / OS Login) — set it before
`apply` if you'll need SSH.

## 8. Gather the remaining inputs

Two things the Terraform module needs that don't come from GCP:

- **GHCR read token** — the `ghcr.io/jakefearsd/wikantik` image is
  private; ask the maintainer for a token (a GitHub PAT with
  `read:packages`, or a fine-grained token scoped to the package).
- **Your public IP** for `admin_cidr` (SSH is locked to this CIDR;
  `0.0.0.0/0` is rejected by validation):

  ```bash
  echo "$(curl -s https://checkip.amazonaws.com)/32"
  ```

Plus a domain you control if you're using the `caddy` ingress profile
(Let's Encrypt needs it), or a Cloudflare tunnel token for `cloudflared`.

## Next step

Continue with **[deploy/gcp/README.md](../deploy/gcp/README.md)** — the
variable table, `terraform.tfvars` example, walkthrough, persistence
model, and restore/teardown runbooks.
