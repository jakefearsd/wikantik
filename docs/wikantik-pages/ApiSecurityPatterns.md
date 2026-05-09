---
cluster: security
canonical_id: 01KQ0P44KYCVPR6BBQNNKMVZ2V
title: Api Security Patterns
type: article
tags:
- security
- api
- authentication
- authorization
- rate-limiting
status: active
date: 2025-05-15
summary: Technical patterns for securing APIs. Covers OAuth 2.0 PKCE, JWT validation, and distributed rate limiting algorithms.
auto-generated: false
---

# API Security: Technical Implementation

Securing an API requires a defense-in-depth approach spanning identity, volume control, and payload validation.

## 1. Authentication: OAuth 2.0 and PKCE

For modern applications, standard OAuth 2.0 with shared secrets is insufficient for public clients (Mobile/SPA).
*   **PKCE (Proof Key for Code Exchange):** Mandate PKCE to prevent authorization code injection. The client generates a `code_verifier` and a `code_challenge`, ensuring that only the originator can exchange the code for an access token.
*   **M2M (Machine-to-Machine):** Use the **Client Credentials Flow** for internal service-to-service calls, utilizing short-lived (e.g., 1-hour) tokens.

## 2. Authorization: Scopes and Claims

Authentication (Who) must be followed by granular Authorization (What).
*   **Scope-Based Access:** Limit tokens to specific scopes (e.g., `read:orders`, `write:profile`). 
*   **Claim Validation:** Beyond checking the signature, the resource server must validate specific JWT claims.
*   **Concrete Example:** A request to `DELETE /orders/567` must verify that the `sub` (Subject) claim in the JWT matches the `owner_id` of order 567 in the database (Broken Object Level Authorization - BOLA protection).

## 3. Rate Limiting: Distributed Algorithms

To protect against DoS and credential stuffing, implement rate limiting at the [API Gateway](ApiGatewayPatterns).

| Algorithm | Pros | Cons |
| :--- | :--- | :--- |
| **Fixed Window** | Low memory, fast. | Allows bursts at window boundaries. |
| **Token Bucket** | Handles bursts gracefully. | More complex to synchronize in distributed systems. |
| **Sliding Window Log**| Perfect accuracy. | High memory (stores every timestamp). |

**Concrete Implementation:** Use a **Redis-backed Sliding Window**. For each request, store the timestamp in a Redis Sorted Set (`ZSET`). Remove entries older than 60 seconds (`ZREMRANGEBYSCORE`). If `ZCARD` < Limit, allow the request.

## 4. Payload and Transport Security

*   **mTLS (Mutual TLS):** For high-security internal environments, use mTLS to authenticate both the client and the server via certificates, preventing lateral movement if one service is compromised.
*   **Content-Type Enforcement:** Reject any request not matching `application/json` to prevent some classes of CSRF and injection attacks.
*   **Scanning:** Use tools like `OWASP ZAP` or `Burp Suite` in CI/CD to scan for common API vulnerabilities (SQLi, XSS in JSON fields).

---
**See Also:**
- [Authentication And Authorization](AuthenticationAndAuthorization) — JWT vs Session deep dive.
- [Api Gateway Patterns](ApiGatewayPatterns) — Centralizing security controls.
- [Identity And Access Management](IdentityAndAccessManagement) — Managing service accounts.
