---
canonical_id: 01KQ12YDW2GVDGKCA2B1Q8NM00
title: Oauth And Oidc Deep Dive
type: article
cluster: security
status: active
date: '2026-04-25'
tags:
- oauth
- oidc
- authentication
- authorization
- pkce
summary: OAuth 2.1 and OIDC in 2026 — which flow to pick, why PKCE is mandatory,
  and the operational pitfalls (token storage, refresh, scope creep) that
  separate good integrations from owned ones.
related:
- ApplicationSecurityFundamentals
- AuthenticationAndAuthorization
- EncryptionFundamentals
- SslTlsDeepDive
hubs:
- Security Hub
---
# OAuth and OIDC Deep Dive

OAuth 2.0 is "delegated authorization": let app A act on behalf of user U at service S, without giving A the user's password. OIDC layers identity on top: tell app A who user U is. Together they're how nearly every "Sign in with…" button works in 2026, and how nearly every API-to-API call between cloud services works.

OAuth has a reputation for being confusing because the spec accumulated flows over a decade and the names ("implicit," "password," "code") describe ten subtly different things. OAuth 2.1 (RFC drafted 2024) cleans up the deprecations. Use it.

## OAuth vs OIDC

- **OAuth 2.1** answers *what can this app do for this user?* (authorization)
- **OIDC** (OpenID Connect, on top of OAuth) answers *who is this user?* (authentication)

If you're building "Sign in with Google" you need OIDC. If you're letting a third-party app post on behalf of a user, you need OAuth. Most SSO use cases are OIDC. Most "API access between services" cases are OAuth.

The same authorization server (Auth0, Okta, Keycloak, Cognito, Azure AD) handles both.

## The flow you should pick

In 2026, **Authorization Code with PKCE** is the right answer for nearly every OAuth flow.

```
1. Client redirects user to authorization server with:
     - client_id
     - redirect_uri
     - response_type=code
     - scope=...
     - state=<random>
     - code_challenge=<SHA256 of random verifier>
     - code_challenge_method=S256

2. User authenticates with auth server (and consents to scopes).

3. Auth server redirects back to redirect_uri with ?code=AUTH_CODE&state=...

4. Client backend posts to /token with:
     - grant_type=authorization_code
     - code=AUTH_CODE
     - redirect_uri=...
     - client_id, client_secret  (if confidential client)
     - code_verifier=<original random>

5. Auth server validates (especially that SHA256(verifier) == challenge), returns:
     - access_token (used to call APIs)
     - refresh_token (used to get new access tokens)
     - id_token (OIDC; identity claim)
```

PKCE (Proof Key for Code Exchange) was introduced for mobile apps (where the client secret can't be kept secret) and has been promoted to a universal requirement in OAuth 2.1. Use it always, even for confidential clients with secrets.

## Flows that were once recommended and now aren't

- **Implicit flow.** Returns the access token directly in the redirect URL. Vulnerable to token leakage, replay attacks. Deprecated in OAuth 2.1; do not use.
- **Resource owner password credentials.** The user gives their password to the third-party app. Defeats the entire point of OAuth. Deprecated.
- **Client credentials in the URL.** Don't.

If a tutorial tells you to use these, the tutorial is out of date. Use authorization code + PKCE.

## Confidential vs public clients

- **Confidential client** can keep a secret. Backend services. Server-side rendered web apps. Get a `client_secret` from the auth server, use it on the token endpoint.
- **Public client** can't keep a secret. SPAs. Mobile apps. CLIs. No `client_secret`; PKCE is the substitute.

Both use authorization code + PKCE. The only difference is whether the token endpoint call carries `client_secret`.

## Tokens and what they are

- **Access token** — opaque or JWT; presented to API endpoints to authorize a call. Typical lifetime 15 minutes to 1 hour. Short by design.
- **Refresh token** — obtained alongside the access token; used to get a new access token without user interaction. Lifetime hours to weeks. Treat as sensitive; it's a long-term credential.
- **ID token** (OIDC) — JWT carrying claims about the user (sub, email, name, etc.). For authentication, not authorization. Short-lived.

Common confusion: people use the ID token as an access token. Don't. The ID token tells you *who*; the access token authorizes *what*.

## JWT access tokens and validation

Many auth servers issue access tokens as JWTs. The receiving API validates:

1. **Signature** against the issuer's public key (fetched from `/.well-known/jwks.json`, cached).
2. **`iss`** matches the expected issuer.
3. **`aud`** includes your API's identifier.
4. **`exp`** is in the future.
5. **`scope`** or `scp` includes the required permission.

Use a library. Hand-rolled JWT validation is one of the most common security bugs in the wild. The classic flaws:

- Accepting `alg: none`. Yes, the JWT spec allowed unsigned tokens. Reject any non-expected algorithm explicitly.
- Confusing the signing-key type. RS256 (asymmetric) vs HS256 (symmetric) — if you accept either, an attacker can forge tokens by treating your public key as the HMAC secret. Whitelist algorithms.
- Trusting the JWT's own claim about what key signed it. Anyone can claim anything. Look up the key via `kid` from a trusted JWKS endpoint.

## Refresh tokens

Refresh tokens are powerful. A leaked refresh token gives an attacker continued access until you detect and revoke it.

Defences:

- **Refresh token rotation.** Each use returns a new refresh token; the old one is invalidated. Detection: if an old (already-rotated) refresh token is used, treat as compromise — invalidate the entire token family. OAuth 2.1 mandates this.
- **Sender-constrained tokens.** DPoP (Demonstrating Proof of Possession) binds the token to a key the client holds. A leaked token without the key is useless. Spec maturing in 2025-2026; adopt where supported.
- **Short refresh-token lifetimes.** Hours, not weeks, for high-value applications.
- **Storage hygiene.** Refresh tokens stored in browser localStorage are vulnerable to XSS. Use httpOnly secure cookies or a server-side session.

## Scope design

Scopes describe what an access token can do. Bad scope design:

- One scope per app ("read everything") — no granularity.
- Scopes named after API endpoints (`/v1/users` scope) — couples scope vocabulary to API surface.
- Scopes that combine read and write ("manage_orders") — can't separate concerns.

Better:

- **Resource + action.** `orders:read`, `orders:write`, `payments:read`.
- **One scope per logically distinct permission.** You can always combine; you can't split after.
- **Scope names that are stable** — they appear in user consent screens and become public commitment.

Apps request the minimum scopes they need. Users see the consent screen; ask for the minimum.

## OIDC specifics

ID tokens are JWTs with a defined claim set:

- `iss` — the issuer.
- `sub` — the unique user ID at the issuer. Stable; the right thing to use as your internal user ID.
- `aud` — your client ID.
- `exp`, `iat` — expiry, issued at.
- `email`, `name`, `picture` — standard profile claims (only if you requested the corresponding scopes).
- `nonce` — replay protection; you generate it, the auth server echoes it.

The `userinfo` endpoint returns the same claims (and possibly more) and is queryable with the access token. Useful for fresh data that might have changed since the ID token was issued.

## Common mistakes

- **Using OAuth for authentication only.** "I just want to know who the user is" → use OIDC, not raw OAuth. The ID token is what you need; the access token is irrelevant.
- **Storing tokens in localStorage.** XSS-accessible. Use httpOnly cookies or server-side sessions.
- **Skipping PKCE.** Everywhere, always.
- **Wildcard redirect URIs.** Auth server should match exactly. Wildcards are an open redirect waiting to happen.
- **Trusting `email_verified=false`.** A user can claim any email; OIDC providers require explicit verification before `email_verified=true`. Check the flag before using email as identity.
- **Using `email` as primary key.** Emails change; users merge accounts at the IdP; don't key on email. Key on `(iss, sub)`.
- **Single-page-app token in URL fragment.** Implicit flow remnant. Don't.

## Operational concerns

- **Auth server downtime.** Your app is down. Plan for it: cache JWKS aggressively, accept tokens valid before the outage, design degraded modes.
- **Key rotation.** JWKS endpoints expose multiple keys; signers rotate. Your validation library needs to handle rotation without you doing anything; verify with key rotation testing.
- **Token revocation.** Access tokens are stateless; you can't revoke before expiry without introspection. Some providers support introspection endpoints (`POST /token/introspect`). Use it for high-stakes operations; the cost is one extra round-trip.
- **Multi-region auth servers.** Latency to Auth0 / Okta from a remote region can dominate request latency. Check; consider provider region or self-hosted Keycloak nearby.

## When OAuth/OIDC isn't the answer

- **Service-to-service inside a private network** with full TLS and short-lived workload identity (SPIFFE / Workload Identity) — often simpler than running an OAuth server.
- **Internal-only tools** with VPN + IdP integration — direct SAML or OIDC is fine; don't build an OAuth surface for tools nobody outside accesses.
- **Pure user authentication with no third-party access** — OIDC is the right pick; OAuth is overkill if no API delegation is involved.

## Further reading

- [ApplicationSecurityFundamentals] — broader auth context
- [AuthenticationAndAuthorization] — broader concepts
- [EncryptionFundamentals] — JWT signing primitives
- [SslTlsDeepDive] — the transport TLS underneath
