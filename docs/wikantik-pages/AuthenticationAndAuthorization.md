---
date: '2025-05-15T00:00:00Z'
summary: Technical analysis of identity management in distributed systems. Covers
  JWT anatomy, refresh token rotation, and RBAC vs ABAC models.
cluster: security
auto-generated: false
canonical_id: 01KQ0P44M5M2DTE4DVXXG55H81
type: article
title: Authentication and Authorization
status: active
tags:
- security
- identity
- jwt
- oauth2
- openid-connect
hubs:
- AuthenticationAndAuthorizationHub
---

# Authentication vs. Authorization: Systems Identity

Identity management is the foundation of secure distributed systems. To build modern identity and access management (IAM), it is vital to treat OAuth 2.0, OpenID Connect (OIDC), and JSON Web Tokens (JWT) not as competing alternatives, but as a complementary stack.

Identity is divided into two distinct processes: **AuthN** (Who) and **AuthZ** (What).

| Feature | OAuth 2.0 | OpenID Connect (OIDC) | JWT |
| :--- | :--- | :--- | :--- |
| **Primary Goal** | **Authorization** (Access) | **Authentication** (Identity) | **Data Format** (Transmission) |
| **Question Answered** | "What is the client allowed to do?" | "Who is this user?" | "How do I securely carry this data?" |
| **Key Artifacts** | Access Tokens, Refresh Tokens | ID Tokens (always JWTs) | Header, Payload, Signature |

## 1. Authentication (AuthN): Proving Identity

Modern systems use **OpenID Connect (OIDC)** as the identity layer built directly **on top of OAuth 2.0**. It adds an identity layer, allowing the client to verify "who" the user is. Because raw OAuth 2.0 Access Tokens are meant for APIs, they don't natively describe the user. OIDC solves this by introducing the **ID Token**.

*   An **Access Token** is for the API (Resource Server). The client application shouldn't parse it.
*   An **ID Token** is for the Client Application. It is always a JWT containing standard claims (`sub` for subject, `iss` for issuer, `exp` for expiration) that prove the user's identity and authentication event.

OIDC standardizes the `/userinfo` endpoint, allowing applications to fetch additional profile data without overloading the ID token size.

### JWT Anatomy (JSON Web Token)
JWTs are the industry standard for stateless authentication and act as a secure data container.

A massive architectural misconception is that standard JWTs are encrypted. They are generally only **signed** (JWS) and base64Url encoded.
*   **Header:** Specifies algorithm (e.g., `RS256`). Ensure your JWT validation library strictly rejects the "none" algorithm.
*   **Payload:** Contains **Claims** (`sub`: UserID, `exp`: Expiry, `iat`: Issued At).
*   **Signature:** Prevents tampering. 

**Warning:** Payloads in a standard JWS are fully visible to anyone who decodes it. Never put PII or sensitive data in a standard JWS payload. If sensitive data must be passed, use JWE (JSON Web Encryption), or store a reference ID in the token and fetch the sensitive data securely on the backend.

### Strict JWT Security Best Practices
1.  **Asymmetric Signing (RS256, ES256):** Always use asymmetric keys in microservices. The Authorization Server signs tokens with a Private Key, and APIs verify them using a Public Key (fetched via JWKS).
2.  **Reject `alg: none`:** Prevent bypass attacks.
3.  **Short TTLs:** Keep Access Tokens short-lived (e.g., 5-15 minutes) to minimize the blast radius of a stolen token.
4.  **Validate All Claims:** Always explicitly verify `iss` (issuer), `aud` (audience), and `exp` (expiration).

### Token Lifecycle
*   **Access Tokens:** Short-lived (5–15 mins). Used for API requests.
*   **Refresh Tokens:** Long-lived. Used to get new Access Tokens.
*   **Refresh Token Rotation:** Mandatory for security. Every time a refresh token is used, the server issues a *new* refresh token and invalidates the old one. This detects token theft immediately.

## 2. OAuth 2.0: The Authorization Framework

OAuth 2.0 enables **delegated access**. It issues Access Tokens so clients can call APIs (Resource Servers) without ever seeing the user's password.

### The PKCE Revolution (Authorization Code Flow)
The **Proof Key for Code Exchange (PKCE)** is the modern gold standard for the Authorization Code flow. Originally designed for "public clients" (SPAs, mobile apps), it is now mandatory in OAuth 2.1 for *all* clients.
1.  **Dynamic Secret:** The client generates a random string (`code_verifier`) and hashes it (`code_challenge`) using SHA-256.
2.  **Authorization Request:** The client sends the `code_challenge` to the Authorization Server and the user logs in.
3.  **Code Issuance:** The server stores the challenge and returns an `authorization_code`.
4.  **Token Exchange:** To get the Access Token, the client must send the `authorization_code` **along with the original, un-hashed `code_verifier`**.
5.  **Verification:** The server hashes the `code_verifier` and compares it. This completely mitigates code interception attacks.

### Machine-to-Machine (M2M) with Client Credentials Flow
For microservices acting on their own behalf, the Client Credentials Flow is the industry standard. Every microservice must have its own unique `client_id` and `client_secret`, strictly scope permissions, and cache tokens.

## 3. Authorization (AuthZ): Managing Permissions

Once identity is verified, the system must enforce permissions.

| Model | Logic | Use Case |
| :--- | :--- | :--- |
| **RBAC (Role-Based)** | User $\to$ Role $\to$ Permission | Simple organizational structures (Admin, Editor). |
| **ABAC (Attribute-Based)**| Rule(Subject, Resource, Env) | Complex logic (e.g., "Allow delete if owner AND office hours"). |
| **ReBAC (Relationship-Based)**| User $\to$ Relation $\to$ Resource | Google Zanzibar style (e.g., "User is a member of Folder X"). |

**Concrete Example (ABAC):** An API policy might state: *ALLOW POST to /reports IF user.department == 'Finance' AND request.ip IN (corporate_vpn_range).*

## 4. Session Management vs. Stateless Tokens

*   **Stateful Sessions:** Server stores session ID in Redis/DB. Pros: Immediate revocation. Cons: Scaling bottlenecks.
*   **Stateless Tokens (JWT):** Client stores token. Pros: High scalability. Cons: Revocation is difficult (requires a "Blacklist" of `jti` claims in Redis).

**Recommendation:** Use a **Hybrid Approach**. Use stateless JWTs for short-lived access and stateful storage for long-lived refresh tokens.

### Modern Architectural Pattern: The Backend-for-Frontend (BFF)
For SPAs, the **BFF Pattern** has replaced storing tokens in the browser because the browser is a hostile environment (susceptible to XSS). 
1.  A dedicated backend (the BFF) acts as a confidential client.
2.  The BFF handles the OAuth 2.0 PKCE flow and stores tokens in a server-side session.
3.  The SPA never sees the tokens; it gets a strongly encrypted, `HttpOnly`, `Secure`, `SameSite=Strict` session cookie.
4.  When the SPA calls an API, the BFF proxy attaches the Access Token from its secure memory.

---
**See Also:**
- [Api Security Patterns](ApiSecurityPatterns) — Securing the transmission layer.
- [Google SSO](GoogleSSO) — Implementing federated identity.
- [Identity And Access Management](IdentityAndAccessManagement) — Enterprise-scale governance.
