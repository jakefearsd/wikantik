---
title: Cryptography Fundamentals
date: '2026-05-04'
cluster: wikantik-development
status: active
verified_at: '2026-05-04T21:10:44.598011331Z'
type: reference
verified_by: gemini-cli-mcp-client
canonical_id: 01KQTD4FEDPE3AD46XNDE57TXS
summary: An overview of Wikantik's cryptographic implementation, password hashing
  standards, and API key management.
tags:
- cryptography
- password-hashing
- api-keys
- security
- wikantik-development
---
# Cryptography Fundamentals in Wikantik

Wikantik prioritizes the security of user data and system integrity through robust cryptographic practices. The core logic for these operations resides in the `wikantik-util` module within `CryptoUtil.java`.

## Password Hashing

Wikantik uses salted password hashing to protect user credentials. When a user creates or updates a password, the system generates a random salt, combines it with the password, and hashes the result.

### Supported Algorithms
The system supports two hashing formats, prefixed for identification:

1. **`{bcrypt}` (Current default):** Used for new and re-hashed passwords. The work factor is 12 (`DEFAULT_BCRYPT_COST`), roughly 150 to 250 ms per hash on current hardware, and it is embedded in each stored hash, so raising it later affects only newly written hashes. Passwords longer than 72 bytes are truncated, as bcrypt does. A legacy hash is transparently re-hashed to bcrypt on the owner's next successful login.
2. **`{SHA-256}` (Legacy, still verifiable):** Salted SHA-256, kept working so existing accounts migrate transparently rather than being locked out.

Salted SHA-1 (`{SSHA}`) and unsalted SHA-1 (`{SHA}`) are **no longer supported** — a
stored value in either format never verifies, and it is left untouched (not
migrated) on a failed attempt.

### Storage Format
A bcrypt entry is stored as `{bcrypt}` followed by the standard `$2a$` bcrypt string, which carries its own salt and cost. A legacy `{SHA-256}` entry is the Base64 encoding of the hash followed by the salt. The algorithm prefix lets `CryptoUtil.verifySaltedPassword` choose the matching verification logic.

### Password policy
New passwords are checked by `PasswordValidator`, which follows NIST 800-63B: length rather than composition rules, plus a blocklist of common passwords. The settings are `wikantik.password.minLength` (default 8), `wikantik.password.maxLength` (default 128) and `wikantik.password.blocklist.enabled` (default true). Repeated failed logins are slowed per account by `wikantik.login.throttling` (default true).

## API Key Management

Wikantik has its own bearer API keys and also holds credentials for a few external services.

- **Wikantik API keys (MCP and `/tools/*`):** A key is `wkk_` followed by 32 random bytes (base64url) from a `SecureRandom`. Only its SHA-256 hash is stored, in the `api_keys` table; the plaintext is shown once, when the key is created. SHA-256 rather than bcrypt is deliberate: the token carries 256 bits of entropy, so a slow hash adds cost without protection. Each key has a scope (`mcp_read`, `mcp_admin`, `tools` or `all`), and issuing a key is written to the audit log (as are rotation and revocation through the self-service Preferences page). See [Connecting Claude Code to the Wikantik MCP Servers](ConnectingClaudeCodeToWikantikMcp) for the client side and `docs/admin/ApiKeys.md` for the admin reference.
- **Inference services:** The Claude-backed entity extractor and proposal judge read `ANTHROPIC_API_KEY` from the JVM environment. A local Ollama backend needs no key.
- **SMTP relays:** Outgoing mail uses the `mail.smtp.*` properties or a JNDI `mail/Session` resource (see `wikantik.mail.jndiname`) with whatever credentials your relay (Brevo, SendGrid, Amazon SES and so on) issues.
- **Other:** `wikantik.indexnow.apiKey` for IndexNow pings, and the SSO client secret and SAML keystore passwords under `wikantik.sso.*`.

### Best Practices
- **Environment Variables:** Credentials should never be hardcoded or committed to the repository. Use environment variables (e.g., `ANTHROPIC_API_KEY`) which are read by the system at runtime.
- **Context Configuration:** For production deployments, prefer using Tomcat's JNDI resources (e.g., `ROOT.xml`) to inject sensitive configuration into the application.

## Transport Security (TLS)

Wikantik is designed to run behind a TLS-terminating reverse proxy (like Nginx, Apache, or Cloudflare). 
- **Internal Communication:** While internal module communication is typically via method calls or local HTTP, any network-traversing requests (e.g., to the embedding backend) should use `https` where available.
- **Session Cookie:** `web.xml` marks the `JSESSIONID` cookie `HttpOnly` and cookie-only (no URL rewriting) so script injection cannot read it. It deliberately does not set `Secure`, because the right value depends on the deployment. `Secure` is applied only when Tomcat knows the request arrived over HTTPS, either because TLS reaches Tomcat directly or because the proxy sends `X-Forwarded-Proto: https` and the `RemoteIpValve` is configured to read it. The Tomcat context template also sets `SameSite=Lax`.
- **Remember-me cookie:** When `wikantik.cookieAuthentication` is on (default off), the `WikantikUID` cookie is `HttpOnly`, `SameSite=Lax` and `Secure` on HTTPS, and holds an opaque, server-validated token.
- **HSTS:** `STSFilter` adds a `Strict-Transport-Security` header to responses.

## See Also
- [Relational User Database](RelationalUserDatabase) — How users and hashed passwords are stored in PostgreSQL.
- [Sending Email from the Wiki](SendingEmailFromTheWiki) — Configuration for SMTP relay credentials.
- [Application Security](ApplicationSecurityFundamentals) — Broader security patterns in the platform.
