---
hubs:
- AuthenticationAndAuthorizationHub
date: 2025-05-15T00:00:00Z
summary: Google SSO via OAuth 2.0 and OIDC — Authorization Code flow, mandatory JWT
  validation steps (sig, iss, aud, exp), and secure session establishment.
auto-generated: false
type: article
tags:
- oauth2
- oidc
- jwt
- sso
cluster: security
canonical_id: 01KQ0P44QPXCZXWT5V96221F36
title: Google SSO
---

# Google SSO: OAuth 2.0, OIDC, and JWT Validation

Implementing Google Single Sign-On (SSO) for enterprise applications requires more than just a "Login with Google" button. It requires a rigorous implementation of the **OpenID Connect (OIDC)** layer on top of **OAuth 2.0**. This article details the server-to-server handshake and the non-negotiable steps for JWT validation.

## I. The OIDC Handshake (Authorization Code Flow)

For web applications, the **Authorization Code Flow** is the only secure method for establishing a session.

1.  **Redirection:** The application redirects the user to `accounts.google.com/o/oauth2/v2/auth` with `scope=openid email profile` and a cryptographically random `state` parameter.
2.  **Consent:** Google authenticates the user and returns an `authorization_code` to the application's `redirect_uri`.
3.  **Token Exchange:** The application backend sends a POST request to `oauth2.googleapis.com/token`, exchanging the `authorization_code` and the `client_secret` for:
    *   `access_token`: To call Google APIs.
    *   `id_token`: A JSON Web Token (JWT) containing the user's identity claims.

## II. Mandatory JWT Validation Steps

The backend MUST NOT trust the `id_token` without performing these four validation steps:

### 1. Signature Verification
Fetch Google's public keys from the JWKS (JSON Web Key Set) endpoint: `https://www.googleapis.com/oauth2/v3/certs`.
*   Match the `kid` (Key ID) in the JWT header with a key from the JWKS.
*   Verify the signature using the RS256 algorithm.

### 2. Issuer (`iss`) Claim
Verify that the `iss` claim is exactly `https://accounts.google.com` or `accounts.google.com`.

### 3. Audience (`aud`) Claim
Verify that the `aud` claim matches your application's **Client ID**. If the token was intended for a different application, it must be rejected.

### 4. Expiration (`exp`) Claim
Verify that the current time is before the `exp` time. To account for clock skew, allow a small "leeway" (e.g., 30-60 seconds).

## III. Establishing the Session

Once the `id_token` is validated, the application can trust the user's identity (`sub` claim) and email.

*   **Session Binding:** Map the Google `sub` (Subject ID) to your local user database.
*   **Secure Cookies:** Issue a session cookie with `HttpOnly`, `Secure`, and `SameSite=Lax/Strict` flags.
*   **State Management:** Always verify the `state` parameter on the callback to prevent Cross-Site Request Forgery (CSRF).

## IV. Post-Authentication: Scope Management

Follow the **Principle of Least Privilege (PoLP)**.
*   Request only the `openid` and `email` scopes unless you specifically need to access the user's Google Drive or Calendar.
*   Store the `refresh_token` securely (see [SecretsManagement](SecretsManagement)) if you require long-term access to Google APIs without user re-authentication.

## V. Conclusion: Identity as a Service Boundary

Google SSO moves the burden of authentication and MFA to a trusted provider, but the burden of **Authorization** and **Session Integrity** remains with your application. A failure in any step of the JWT validation protocol is a vulnerability that can lead to total account takeover.

For further details on securing service-to-service communication, see [Cybersecurity](Cybersecurity).
