---
cluster: devops-sre
canonical_id: 01KQ0P44NTN9GANWWFT7RPQD8H
title: Configuration Management
type: article
tags:
- devops
- configuration
- secrets-management
- infrastructure-as-code
- security
status: active
date: 2025-05-15
summary: Technical patterns for managing application configuration and secrets. Covers dynamic secret generation, Vault integration, and environment segmentation.
auto-generated: false
---

# Configuration Management: Secure State Externalization

Resilient systems must decouple their operational logic from their configuration parameters and sensitive secrets.

## 1. The Configuration Hierarchy

Applications should load configuration in a prioritized sequence (The 12-Factor App model):
1.  **Defaults:** Baked into the code.
2.  **Files:** `config.yaml` (non-sensitive).
3.  **Environment Variables:** `DB_HOST`, `LOG_LEVEL`.
4.  **Remote Provider:** Consul, etcd, or Spring Cloud Config.

## 2. Secrets Management: The Vault Pattern

Never store secrets (API keys, DB passwords, certificates) in source control or environment variables that persist in memory.

*   **Hardware-Backed Trust:** Use **HashiCorp Vault** or AWS Secrets Manager. 
*   **Machine Identity:** Authenticate to the vault using an IAM Role (AWS) or a Service Account Token (Kubernetes), eliminating the need for a "master secret" in the application code.
*   **Dynamic Secrets:** The gold standard. Instead of a static DB password, the application requests a credential from Vault. Vault generates a **unique, time-limited user** in the database and provides it to the app. Vault automatically drops the user after 15 minutes or when the app pod restarts.

## 3. Configuration as Code (IaC)

*   **Terraform/Pulumi:** Manage the infrastructure *and* the configuration of that infrastructure (e.g., creating the S3 bucket and then writing its name to a parameter store).
*   **Validation:** Use `dry-run` and policy engines (e.g., OPA - Open Policy Agent) to ensure configuration changes meet security standards before they are applied.

## 4. Environment Segmentation

*   **Promotion Flow:** Config should move from `dev` $\to$ `staging` $\to$ `prod`.
*   **Concrete Tip:** Use **SOPS (Secrets Operations)** to encrypt secrets within Git repositories using KMS keys. This allows developers to version-control the encrypted secret, while only the CI/CD runner has the IAM permissions to decrypt and apply it to the production environment.

---
**See Also:**
- [Infrastructure As Code](InfrastructureAsCode) — Managing the underlying resources.
- [Api Security Patterns](ApiSecurityPatterns) — Protecting keys in transit.
- [Authentication And Authorization](AuthenticationAndAuthorization) — Managing service identities.
