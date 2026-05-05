---
status: official
cluster: wikantik-development
type: reference
title: Cryptography Fundamentals
date: '2026-05-04'
summary: An overview of Wikantik's cryptographic implementation, password hashing
  standards, and API key management.
canonical_id: 01KQTD4FEDPE3AD46XNDE57TXS
verified_at: '2026-05-04T21:10:44.598011331Z'
verified_by: gemini-cli-mcp-client
---
# Cryptography Fundamentals in Wikantik

Wikantik prioritizes the security of user data and system integrity through robust cryptographic practices. The core logic for these operations resides in the `wikantik-util` module within `CryptoUtil.java`.

## Password Hashing

Wikantik uses salted password hashing to protect user credentials. When a user creates or updates a password, the system generates a random salt, combines it with the password, and hashes the result.

### Supported Algorithms
The system supports two primary hashing formats, prefixed for identification:

1. **`{SHA256}` (Recommended):** The modern standard for Wikantik. It uses SHA-256 with a salt, providing strong resistance against collision and pre-image attacks.
2. **`{SSHA}` (Legacy):** Salted SHA-1. This is maintained primarily for backward compatibility with legacy JSPWiki installations but is deprecated for new user accounts.

### Storage Format
Hashed passwords are stored as Base64-encoded strings of the combined hash and salt. The algorithm prefix allows `CryptoUtil.verifySaltedPassword` to dynamically choose the correct extraction and verification logic.

## API Key Management

Wikantik interacts with several external services that require API keys:

- **Inference Services:** Keys for Claude (Anthropic), Ollama, or OpenAI used by the retrieval judges and entity extractors.
- **SMTP Relays:** API keys for Brevo, SendGrid, or AWS SES.
- **MCP Servers:** Access keys for securing the `/wikantik-admin-mcp` and `/knowledge-mcp` endpoints.

### Best Practices
- **Environment Variables:** Credentials should never be hardcoded or committed to the repository. Use environment variables (e.g., `ANTHROPIC_API_KEY`) which are read by the system at runtime.
- **Context Configuration:** For production deployments, prefer using Tomcat's JNDI resources (e.g., `ROOT.xml`) to inject sensitive configuration into the application.

## Transport Security (TLS)

Wikantik is designed to run behind a TLS-terminating reverse proxy (like Nginx, Apache, or Cloudflare). 
- **Internal Communication:** While internal module communication is typically via method calls or local HTTP, any network-traversing requests (e.g., to the embedding backend) should use `https` where available.
- **Secure Cookies:** When running over HTTPS, Wikantik sets the `Secure` and `HttpOnly` flags on session cookies to prevent interception and XSS-based theft.

## See Also
- [Relational User Database](RelationalUserDatabase) — How users and hashed passwords are stored in PostgreSQL.
- [Sending Email from the Wiki](SendingEmailFromTheWiki) — Configuration for SMTP relay credentials.
- [Application Security](ApplicationSecurityFundamentals) — Broader security patterns in the platform.
