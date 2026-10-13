---
cluster: security
type: article
tags:
- oauth2
- oidc
- jwt
- sso
title: Google SSO
canonical_id: 01KQ0P44QPXCZXWT5V96221F36
summary: Google SSO via OAuth 2.0 and OIDC — Authorization Code flow, mandatory JWT
  validation steps (sig, iss, aud, exp), and secure session establishment.
auto-generated: false
date: '2025-05-15'
---

# Google SSO: OAuth 2.0, OIDC, and Enterprise Identity Management

Implementing Google Single Sign-On (SSO) for enterprise applications requires significantly more engineering rigor than merely dropping a "Login with Google" button onto a frontend framework. In modern distributed systems, identity is the new security perimeter. A robust integration demands a deep understanding of the **OpenID Connect (OIDC)** layer, the underlying **OAuth 2.0** protocol, cryptographic verification of JSON Web Tokens (JWTs), and secure session management.

This comprehensive guide details the server-to-server handshakes, cryptographic mechanics, real-world architectural considerations, and the non-negotiable steps for validation that distinguish an enterprise-grade SSO integration from a vulnerable prototype.

## I. The Evolution: OAuth 2.0 vs. OpenID Connect (OIDC)

Historically, OAuth 2.0 was designed as a *delegated authorization* framework. It allowed an application to access resources (like fetching a user's calendar) on behalf of the user, without seeing the user's password. However, OAuth 2.0 does not inherently provide *authentication* (verifying who the user is). 

Developers famously misused OAuth 2.0 for authentication by assuming that if an application could access an API, the user must be authenticated. This led to widespread "confused deputy" attacks. To solve this, OpenID Connect (OIDC) was introduced as an identity layer on top of OAuth 2.0. OIDC standardizes the issuance of an `id_token`—a cryptographically signed JWT that securely asserts the user's identity.

### Why Google SSO?
Organizations often adopt Google Workspace, making Google the central Identity Provider (IdP). By relying on Google SSO, engineering teams offload the immense risks associated with credential storage, brute-force mitigation, and Multi-Factor Authentication (MFA) enforcement. 

The financial rationale is stark: developing a proprietary, compliant authentication system from scratch can easily incur \$150K to \$300K in engineering and audit costs. Furthermore, dealing with a security breach due to mismanaged credentials can cost an enterprise upwards of \$4.5M in damages and regulatory fines. In contrast, securely wrapping Google SSO into an enterprise architecture might require an initial investment of \$20K to \$50K in architectural design, yielding a structurally superior security posture.

## II. The OIDC Handshake (Authorization Code Flow)

For web applications (both Single Page Applications and server-rendered apps), the **Authorization Code Flow** (often augmented with PKCE) is the only secure method for establishing a session. The Implicit Flow is deprecated due to token leakage vulnerabilities in the browser history and referer headers.

### Step-by-Step Execution

1.  **Redirection (The Ask):** The application backend or frontend redirects the user's browser to Google's authorization server: `https://accounts.google.com/o/oauth2/v2/auth`. Critical parameters include:
    *   `client_id`: The public identifier of your application.
    *   `response_type=code`: Specifies the Authorization Code flow.
    *   `scope=openid email profile`: Requests identity claims rather than resource access.
    *   `redirect_uri`: The exact callback URL registered in the Google Cloud Console.
    *   `state`: A cryptographically random, unguessable string tied to the user's initial session to prevent Cross-Site Request Forgery (CSRF).

2.  **Consent & Authentication (The Proof):** Google assumes control. It challenges the user for credentials and MFA, and asks for consent to share their email and profile with your application.

3.  **The Callback (The Hand-off):** Google redirects the browser back to your `redirect_uri` with two query parameters: `code` (a short-lived authorization code) and `state`. The application immediately verifies that the returned `state` exactly matches the one generated in step 1.

4.  **Token Exchange (The Secure Backchannel):** The application backend makes a direct, server-to-server `POST` request to `https://oauth2.googleapis.com/token`, passing the `code`, `client_id`, `client_secret`, and `redirect_uri`. 
    
    Google responds with a JSON payload containing:
    *   `access_token`: A token used to call Google APIs (if scopes were requested).
    *   `id_token`: A signed JWT asserting the user's identity.
    *   `expires_in`: The lifespan of the access token.

## III. Cryptographic Anatomy and JWT Validation

The `id_token` is the cornerstone of OIDC. It consists of three Base64Url-encoded parts separated by dots: `Header.Payload.Signature`.

### The Mathematics of RSA Signatures

Google signs the `id_token` using the RS256 algorithm (RSA Signature with SHA-256). In an RSA-based PKI (Public Key Infrastructure), Google holds the private key ($d$) and publishes the public key ($e, n$) via a JSON Web Key Set (JWKS) endpoint.

The mathematical relationship ensuring non-repudiation is grounded in modular exponentiation. When Google signs the token message $M$, it computes a cryptographic hash $H(M)$ and applies the private key:

$$
\begin{aligned}
s &= (H(M))^d \pmod n
\end{aligned}
$$

Your application, acting as the relying party, must retrieve Google's public key $(e, n)$ and verify the signature $s$. To do so, the system computes the verification value $m'$ and checks for equality with the original hash:

$$
\begin{aligned}
m' &= s^e \pmod n \\
\text{Valid} &= 
\begin{cases} 
\text{True}, & \text{if } m' = H(M) \\ 
\text{False}, & \text{otherwise} 
\end{cases}
\end{aligned}
$$

If the $\text{Valid}$ state evaluates to True, cryptographic integrity is assured. The payload has not been tampered with since Google generated it.

### The Four Pillars of Token Validation

Cryptographic integrity alone is insufficient. The backend MUST NOT trust the `id_token` without verifying four specific claims. A failure in any step is a critical vulnerability that can lead to complete account takeover.

1.  **Signature Verification:** 
    Fetch Google's public keys from `https://www.googleapis.com/oauth2/v3/certs`. Match the `kid` (Key ID) in the JWT header with a key from the JWKS, and execute the RSA verification described above. Never hardcode public keys; they are rotated frequently. Implement a robust caching layer with a Time-To-Live (TTL) that respects the `Cache-Control` headers from Google's endpoint to prevent rate-limiting while ensuring keys stay fresh.

2.  **Issuer (`iss`) Claim:** 
    Verify that the token was actually issued by Google. The `iss` claim must be exactly `https://accounts.google.com` or `accounts.google.com`. Attackers might attempt to feed your application a valid JWT signed by a rogue Identity Provider they control. By strictly enforcing the issuer, your system neutralizes this substitution vector.

3.  **Audience (`aud`) Claim:** 
    Verify that the `aud` claim exactly matches your application's **Client ID**. This mitigates the "Confused Deputy" attack. If an attacker tricks a user into logging into the attacker's malicious application (App A) using Google SSO, App A receives a valid Google JWT for that user. If the attacker replays that identical JWT to your application (App B), your backend must reject it because the `aud` claim will point to App A, not App B.

4.  **Expiration (`exp`) Claim:** 
    Verify that the current server time is strictly before the `exp` timestamp. To account for distributed system clock skew across different physical nodes, authorization libraries typically allow a small "leeway" window (e.g., 30 to 60 seconds). A token that is past its expiration plus the allowed leeway must be explicitly discarded.

## IV. Establishing the Session

Once the `id_token` is fully validated across all four pillars, the application can definitively trust the user's identity, which is uniquely represented by the `sub` (Subject ID) claim.

### Mapping Identity to Local State

The `sub` claim is a globally unique, immutable identifier for the Google user. **Do not use the email address as the primary key for identity mapping.** Email addresses can be changed by the user, reassigned by a Google Workspace administrator, or temporarily spoofed in complex edge cases. Always bind your local application user database record to the persistent Google `sub` ID.

### Managing the Application Session

SSO authenticates the user at a singular point in time; it is the relying application's responsibility to maintain that session continuously. There are two dominant architectures:

1.  **Stateful Sessions (Recommended for Web Apps):** 
    The backend generates a cryptographically secure, random session ID, stores it in a high-speed data store like Redis (mapped to the user's `sub` ID), and issues a session cookie to the client browser. The cookie must be decorated with `HttpOnly` (preventing XSS JavaScript access), `Secure` (enforcing HTTPS transmission), and `SameSite=Lax` or `SameSite=Strict` (preventing CSRF). This stateful architecture allows for immediate, server-side revocation of access by simply deleting the Redis key.
    
2.  **Stateless JWT Sessions (Microservices Architectures):** 
    The backend mints its own internal JWT, signs it with a deeply guarded private key, and sends it to the client. This internal JWT is then transmitted with every subsequent API request. While this is highly scalable and reduces database load, stateless tokens cannot be easily revoked before they expire. If utilizing this approach, engineers must keep the internal JWT lifespans extremely short (e.g., 5 to 15 minutes) and rely on a stateful refresh token to periodically renew access and periodically check the user's active status.

## V. Post-Authentication Scope Management & Enterprise Best Practices

Once the core SSO flow is fully operational, enterprise integrations require careful attention to authorization boundaries and continuous monitoring.

### The Principle of Least Privilege (PoLP)

Only request the scopes strictly necessary for authentication (typically just `openid`, `email`, and `profile`). If your application needs to manipulate a user's Google Drive files, send emails via Gmail, or read their Google Calendar, those elevated scopes must be explicitly requested. However, requesting invasive scopes during the initial login creates high user friction and exponentially increases the risk of users abandoning the flow out of privacy concerns. Instead, implement **Incremental Authorization**: ask for calendar access only at the precise moment the user clicks a specific "Sync with Calendar" button within the application UI.

### Handling Long-Lived Refresh Tokens

If your application requests offline access to Google APIs (meaning it needs to act when the user is not actively logged in), Google will issue a `refresh_token` during the initial authorization exchange. This token is effectively a long-lived password granting continuous access to the user's Google resources. 
*   It must be encrypted at rest in your database (e.g., using AES-256-GCM or a dedicated Key Management Service like AWS KMS).
*   It must never be exposed to the client browser interface.
*   Your system must handle `invalid_grant` errors gracefully. Users can revoke your application's access via their Google Account Security dashboard at any time. When this happens, your backend API calls will fail. Your application must catch this specific error, systematically wipe the stored refresh token, and prompt the user to re-authorize the next time they log in.

### Observability, Anomalies, and Scaling

An enterprise deployment of Google SSO must be highly observable. Log all authentication events in your SIEM (Security Information and Event Management) system, paying particular attention to failed validation checks. 
*   Spikes in failed `state` parameter validations may indicate an active CSRF attack attempting to hijack sessions. 
*   Anomalies in `aud` claim mismatches almost certainly indicate a coordinated token-replay attack from a compromised third-party application.

When scaling to thousands of authentications per minute, be hyper-aware of Google's endpoint quotas. Fetching the JWKS endpoint on every single login request will quickly exhaust your rate limits and add hundreds of milliseconds of unnecessary latency to the login flow. Implement an aggressive but safe caching strategy for Google's public certificates, ensuring your system falls back to fetching fresh keys via a network request only when an incoming token's `kid` cannot be found in the local cache.

## VI. Using Google SSO in Wikantik

Wikantik delegates the OIDC handshake to [pac4j](https://www.pac4j.org/) (authorization-code flow, discovery document, `client_secret_basic` client authentication) rather than hand-rolling it, and wiki.wikantik.com runs it against Google in production. The implementation is in `wikantik-main/src/main/java/com/wikantik/auth/sso/`; the operator reference is `docs/admin/SingleSignOn.md` in the repository. A minimal Google setup in `wikantik-custom.properties`:

```properties
wikantik.baseURL = https://wiki.example.com/
wikantik.sso.enabled = true
wikantik.sso.type = oidc
wikantik.sso.oidc.discoveryUri = https://accounts.google.com/.well-known/openid-configuration
wikantik.sso.oidc.clientId = <client-id>.apps.googleusercontent.com
wikantik.sso.oidc.clientSecret = <client-secret>
wikantik.sso.oidc.scope = openid profile email
wikantik.sso.claimMapping.loginName = email
```

*   **Redirect URI:** register `wikantik.baseURL` + `/sso/callback` in the Google Cloud Console. It must match exactly, so `wikantik.baseURL` has to be the public HTTPS origin; a `redirect_uri_mismatch` error means one of the two is wrong.
*   **Login name:** Google sends no `preferred_username` claim, so map the login name to `email` as above. Without that mapping the wiki login name ends up as the numeric `sub`.
*   **Identity binding:** as Section IV advises, Wikantik links an IdP identity to a local account on `sub` by default (`wikantik.sso.identityClaim`). Auto-provisioned profiles carry an `sso.subject` marker, and SSO never adopts an existing local account of the same name; the login is refused instead.
*   **Sessions:** the container's Tomcat context sets cookies to `SameSite=Lax`, not `Strict`, so the session survives the cross-site return from Google to `/sso/callback`. A successful SSO login rotates the HTTP session.
*   **Docker:** set the `WIKANTIK_SSO_*` variables (for example `WIKANTIK_SSO_ENABLED=true` and `WIKANTIK_SSO_CLAIM_LOGIN_NAME=email`) in the deployment's `.env`; `docker/entrypoint.sh` renders them into the properties above. See [Docker Deployment](DockerDeployment).
*   **Failure mode to know:** if every login ends at `/login?error=sso_redirect_failed`, look in the boot log for an `OIDC discovery self-check FAILED` line. It means the host cannot reach the discovery URL (egress, DNS or TLS), because pac4j fetches the discovery document lazily on the first login attempt.

[OAuth Implementation](OAuthImplementation) is the superseded pre-implementation design for the same feature.

## VII. Conclusion: Identity as a Service Boundary

Google SSO fundamentally shifts the massive burden of initial authentication, password lifecycle management, and MFA enforcement to a highly secure, globally distributed Identity Provider. However, the critical responsibilities of **Authorization**, **Session Integrity**, and rigorous **Token Validation** remain squarely on the shoulders of your application developers. 

By meticulously adhering to the OIDC specification, cryptographically verifying signatures against published JWKS keys, and architecting secure, easily revocable session states, organizations can leverage Google's robust security posture to build highly resilient, enterprise-grade distributed systems. Failure to respect the strict bounds of this protocol negates the benefits of SSO and exposes the enterprise to catastrophic breach vectors.
