---
cluster: security
canonical_id: 01KQ0P44R0P72PA8ZA375J1HBH
title: Identity and Access Management
type: article
tags:
- security
- identity
- iam
- rbac
- abac
status: active
date: 2025-05-15
summary: Technical guide to IAM architectures. Covers authentication flows, authorization models (RBAC/ABAC), and the principle of least privilege.
auto-generated: false
---

# Identity and Access Management (IAM): Secure Governance

IAM is the discipline of ensuring that the right individuals (and machines) have access to the right resources for the right reasons at the right time.

## 1. Authentication (AuthN): The Trust Handshake

Proving identity across distributed systems typically uses **OpenID Connect (OIDC)** and **OAuth 2.0**.
*   **MFA (Multi-Factor Authentication):** Mandatory for all human-facing portals. Use FIDO2/WebAuthn (Hardware keys) over SMS to prevent SIM-swapping.
*   **SSO (Single Sign-On):** Centralizes AuthN to a single provider (e.g., Okta, Google, Azure AD). 
*   **SAML vs. OIDC:** SAML is XML-based and common in legacy enterprise; OIDC is JSON/REST-based and preferred for modern cloud apps.

## 2. Authorization (AuthZ): The Permission Matrix

| Model | Implementation | Strength |
| :--- | :--- | :--- |
| **RBAC** | `User -> Role -> Permission` | Simple, scalable for org charts. |
| **ABAC** | `If (user.IP == vpn AND doc.label == secret) ALLOW` | Highly granular, context-aware. |
| **Least Privilege**| Deny by Default | Minimizes blast radius of compromise. |

**Concrete Implementation:** In AWS IAM, a policy is a JSON document. To follow Least Privilege, never use `Resource: *`. Specify the exact ARN: `arn:aws:s3:::my-bucket/*`.

## 3. Service Identities: Machine-to-Machine

Machine identities do not have passwords; they have **Roles** and **Tokens**.
*   **Instance Profiles:** Allow an EC2 instance or Pod to assume a role and call APIs without storing keys on the disk.
*   **Workload Identity:** Uses short-lived JWTs to authenticate a container to a database or secrets vault ([ConfigurationManagement](ConfigurationManagement)).

## 4. Governance and Audit

*   **Access Reviews:** Periodic automated triggers to verify if a user still needs a specific role.
*   **CloudTrail / Activity Logs:** Every IAM "Access Denied" error must be logged and monitored. A spike in "Access Denied" for a specific service account is a primary indicator of a **Credential Leak**.

---
**See Also:**
- [Authentication And Authorization](AuthenticationAndAuthorization) — Deep dive into JWTs.
- [Api Security Patterns](ApiSecurityPatterns) — Securing the API perimeter.
- [Configuration Management](ConfigurationManagement) — Managing secrets for IAM.
