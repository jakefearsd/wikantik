# Setting Up a New Azure Account for Wikantik

How to take a brand-new (or unused) Microsoft Azure account from zero to
ready for a Wikantik deployment: account and subscription setup, cost
guardrails, credentials, and installation of the command-line tools on
**macOS** and **Ubuntu** — plus how the reference deployment topology maps
onto Azure resources.

> **There is no `deploy/azure/` Terraform module yet.** Unlike
> [AWS](AwsAccountSetup.md) ([deploy/aws](../deploy/aws/README.md)) and
> [GCP](GcpAccountSetup.md) ([deploy/gcp](../deploy/gcp/README.md)),
> deploying on Azure currently means provisioning the VM/disk/firewall
> yourself and bringing up the same cloud compose overlay by hand —
> section 8 below maps every piece across. The compose stack itself
> (`docker-compose.yml` + `docker-compose.cloud.yml`) is cloud-agnostic;
> see [CloudDeployment.md](CloudDeployment.md) for how it fits together.

## 1. Create the account and subscription

1. Sign up at [azure.microsoft.com](https://azure.microsoft.com/free/)
   with a Microsoft account. Signing up creates a **Microsoft Entra ID
   tenant** (the identity directory) and a **subscription** (the billing
   container all resources live in — Azure's rough analogue of an AWS
   account / GCP project).
2. New accounts start on a free tier with credits; after that, convert to
   **Pay-As-You-Go**. The single-VM deployment below is well within a
   normal personal budget (see section 4).

## 2. Secure the account

- **Enable multi-factor authentication.** New tenants have Entra
  **security defaults** on, which require MFA registration — don't turn
  that off. Verify under
  [mysignins.microsoft.com/security-info](https://mysignins.microsoft.com/security-info).
- The account you signed up with is a **Global Administrator** of the
  tenant and (typically) **Owner** of the subscription. For a personal
  reference deployment that's fine to use directly — the Azure CLI's
  browser-based login below issues short-lived tokens, so there's no
  long-lived key file on disk by default. Avoid creating service
  principals with client secrets unless you need unattended automation
  (they're the long-lived-credential liability, same trade-off as AWS
  access keys).

## 3. Credentials for the CLI (and Terraform, if used)

After installing the Azure CLI (section 5):

```bash
az login                    # opens a browser to authenticate
az account list --output table
az account set --subscription "<subscription-name-or-id>"
```

Terraform's `azurerm` provider picks up the Azure CLI's login
automatically — no extra credential step needed for interactive use.

## 4. Cost guardrails

Matching the AWS/GCP reference deployments' footprint (~$35–70/month
floor — one 2-vCPU VM, a 50 GiB data disk, a static IP, snapshots),
before creating any infrastructure:

1. Portal → **Cost Management + Billing** → **Budgets** → create a
   monthly budget at ~2× your expected cost (e.g. $150), with email
   alerts at 80% actual and 100% forecast.
2. Budgets **alert, they don't cap** — nothing is shut off at 100%. The
   alert is your cue to investigate (usually "I forgot to delete the
   experiment's resource group").

Azure's cleanup ergonomics are actually the best of the three clouds
here: put everything in one **resource group** and
`az group delete --name wikantik` tears it all down.

## 5. Install the command-line tools

| Tool | Version | Why |
|---|---|---|
| Azure CLI (`az`) | current | Auth, resource provisioning, snapshots |
| Terraform | >= 1.9 | Only if you write your own `azurerm` config (or contribute the `deploy/azure/` module — see section 9); the hand-provisioned path in section 8 needs only `az` |

### macOS (Homebrew)

```bash
brew install azure-cli
brew tap hashicorp/tap
brew install hashicorp/tap/terraform
```

### Ubuntu

**Azure CLI** — Microsoft's install script (adds their apt repo and
installs in one step):

```bash
curl -sL https://aka.ms/InstallAzureCLIDeb | sudo bash
```

(Prefer to inspect each step? The manual apt-repo procedure is in
[Microsoft's docs](https://learn.microsoft.com/cli/azure/install-azure-cli-linux) —
the script above is the same repo add + `apt-get install azure-cli`.)

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
az version
terraform version     # if installed — v1.9.0 or newer
```

## 6. Verify account access

```bash
az account show --output table       # the subscription you set in section 3
az group list --output table         # empty on a fresh subscription — but proves access
```

## 7. Gather the remaining inputs

Same non-cloud inputs as the AWS/GCP paths:

- **GHCR read token** — the `ghcr.io/jakefearsd/wikantik` image is
  private; ask the maintainer for a token (a GitHub PAT with
  `read:packages`, or a fine-grained token scoped to the package).
- **Your public IP** for the SSH firewall rule:

  ```bash
  echo "$(curl -s https://checkip.amazonaws.com)/32"
  ```

- A domain you control (for Caddy/Let's Encrypt ingress), or a
  Cloudflare tunnel token (for `cloudflared` ingress — no public ports
  needed).
- An SSH key: `ssh-keygen -t ed25519 -f ~/.ssh/wikantik` if you don't
  have one.

## 8. Deploying: mapping the reference topology onto Azure

The AWS/GCP modules build the same minimal shape; here is the Azure
equivalent of each piece. Provision these (portal or `az` CLI) inside one
resource group:

| Reference piece (AWS / GCP) | Azure equivalent |
|---|---|
| EC2 instance / GCE instance (2 vCPU, 8 GiB; Ubuntu 24.04 LTS) | VM, e.g. `Standard_B2ms` (or `Standard_B2s` at 4 GiB for the `core` tier) with the `Ubuntu Server 24.04 LTS` image |
| EBS gp3 / pd-balanced 50 GiB data volume | Managed data disk (Standard SSD or Premium SSD, 50 GiB), attached as a data disk |
| Daily snapshots (DLM / resource policy) | Azure Backup vault with a daily VM/disk policy, or scheduled `az snapshot create` |
| Security group / firewall rules | Network Security Group: allow 22 from *your* CIDR only, 80+443 from anywhere |
| Elastic IP / static external IP | Static Public IP (Standard SKU) |
| SSM Parameter Store / Secret Manager | Azure Key Vault — or, for a first deployment, just a root-owned `.env` on the VM |
| Route53 / Cloud DNS record (optional) | Azure DNS A record (optional) |

Then, on the VM (this is what the AWS/GCP cloud-init automates — see
[deploy/cloud-init/cloud-init.yaml.tftpl](../deploy/cloud-init/cloud-init.yaml.tftpl)
as the authoritative sequence):

1. **Format and mount the data disk at `/srv/wikantik`**, and — *before
   installing Docker* — write `/etc/docker/daemon.json` with
   `{"data-root": "/srv/wikantik/docker"}`. This relocation is what makes
   every compose named volume (the live page tree, `pgdata`, TLS certs,
   the embedding model cache) survive VM replacement — don't skip it.
2. **Install Docker** from its official apt repo.
3. **Fetch the repo's deploy files** onto the VM (e.g. clone the repo to
   `/opt/wikantik`, or copy `docker-compose.yml`,
   `docker-compose.cloud.yml`, `deploy/config/`, and `docker/`).
4. **Write `.env`** — `WIKANTIK_IMAGE` (pinned tag), `POSTGRES_PASSWORD`,
   the GenAI tier variables (`WIKANTIK_GENAI_MODE`,
   `WIKANTIK_KNOWLEDGE_ENABLED`, …), and
   `PROXY_REMOTE_IP_HEADER=X-Forwarded-For` if using the `caddy` ingress
   profile. The tier presets and every entrypoint variable are documented
   in [CloudDeployment.md](CloudDeployment.md) and
   [DockerDeployment.md](DockerDeployment.md).
5. **`docker login ghcr.io`** with the GHCR read token, then bring up the
   stack:

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.cloud.yml \
                  --profile caddy --profile bundled-db \
                  up -d
   # add --profile embeddings for the search/knowledge tiers;
   # swap --profile caddy for --profile cloudflared for tunnel ingress
   ```

6. For later upgrades, install
   [deploy/bin/wikantik-update.sh](../deploy/bin/wikantik-update.sh) as
   `/usr/local/bin/wikantik-update` and write
   `/etc/wikantik-update.conf` (repo dir, compose files/profiles, GHCR
   credentials) — the script is cloud-agnostic: pull → retag rollback →
   `compose up` → health-poll → auto-rollback. See
   [CloudDeployment.md — Pull-based updates](CloudDeployment.md#pull-based-updates).

Health check once up: `curl -fsS https://<your-domain>/api/health`, and
`GET /api/capabilities` to confirm the GenAI tier took effect.

## 9. Contributing `deploy/azure/`

The shared cloud-init template
(`deploy/cloud-init/cloud-init.yaml.tftpl`) was deliberately built
cloud-agnostic — the AWS and GCP modules differ only in four template
variables (data-disk device path, secret-fetch prereqs/setup/command; see
the "parity note" sections in either module README). An `azurerm`
Terraform module supplying Azure values for those same variables (Key
Vault fetch via the VM's managed identity + IMDS token, a
`/dev/disk/azure/scsi1/lun0` device path) would slot straight in.
Contributions welcome — mirror the structure and README conventions of
[deploy/aws/](../deploy/aws/README.md) and
[deploy/gcp/](../deploy/gcp/README.md).
