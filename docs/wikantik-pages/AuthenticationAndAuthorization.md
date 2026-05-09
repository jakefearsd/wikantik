---
cluster: security
canonical_id: 01KQ0P44M5M2DTE4DVXXG55H81
title: Authentication and Authorization
type: article
tags:
- security
- identity
- jwt
- oauth2
- openid-connect
status: active
date: 2025-05-15
summary: Technical analysis of identity management in distributed systems. Covers JWT anatomy, refresh token rotation, and RBAC vs ABAC models.
auto-generated: false
---

# Authentication vs. Authorization: Systems Identity

Identity management is the foundation of secure distributed systems. It is divided into two distinct processes: **AuthN** (Who) and **AuthZ** (What).

## 1. Authentication (AuthN): Proving Identity

Modern systems use **OpenID Connect (OIDC)** as the identity layer on top of OAuth 2.0.

### JWT Anatomy (JSON Web Token)
JWTs are the industry standard for stateless authentication.
*   **Header:** Specifies algorithm (e.g., `RS256`).
*   **Payload:** Contains **Claims** (`sub`: UserID, `exp`: Expiry, `iat`: Issued At).
*   **Signature:** Prevents tampering. **Warning:** Payloads are Base64 encoded, NOT encrypted. Never put PII or secrets in a JWT payload.

### Token Lifecycle
*   **Access Tokens:** Short-lived (5–15 mins). Used for API requests.
*   **Refresh Tokens:** Long-lived. Used to get new Access Tokens.
*   **Refresh Token Rotation:** Mandatory for security. Every time a refresh token is used, the server issues a *new* refresh token and invalidates the old one. This detects token theft immediately.

## 2. Authorization (AuthZ): Managing Permissions

Once identity is verified, the system must enforce permissions.

| Model | Logic | Use Case |
| :--- | :--- | :--- |
| **RBAC (Role-Based)** | User $\to$ Role $\to$ Permission | Simple organizational structures (Admin, Editor). |
| **ABAC (Attribute-Based)**| Rule(Subject, Resource, Env) | Complex logic (e.g., "Allow delete if owner AND office hours"). |
| **ReBAC (Relationship-Based)**| User $\to$ Relation $\to$ Resource | Google Zanzibar style (e.g., "User is a member of Folder X"). |

**Concrete Example (ABAC):** An API policy might state: *ALLOW POST to /reports IF user.department == 'Finance' AND request.ip IN (corporate_vpn_range).*

## 3. Session Management vs. Stateless Tokens

*   **Stateful Sessions:** Server stores session ID in Redis/DB. Pros: Immediate revocation. Cons: Scaling bottlenecks.
*   **Stateless Tokens (JWT):** Client stores token. Pros: High scalability. Cons: Revocation is difficult (requires a "Blacklist" of `jti` claims in Redis).

**Recommendation:** Use a **Hybrid Approach**. Use stateless JWTs for short-lived access and stateful storage for long-lived refresh tokens to allow for immediate account lockout or global logout.

---
**See Also:**
- [Api Security Patterns](ApiSecurityPatterns) — Securing the transmission layer.
- [Google SSO](GoogleSSO) — Implementing federated identity.
- [Identity And Access Management](IdentityAndAccessManagement) — Enterprise-scale governance.
