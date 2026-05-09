# Security Policy

## Reporting a vulnerability

If you believe you've found a security vulnerability in Wikantik, **please
report it privately**. Do not open a public GitHub issue or post to a
mailing list.

**Preferred channel:** GitHub's private vulnerability reporting at
<https://github.com/jakefearsd/wikantik/security/advisories/new>.

**Alternate:** email `jakefear@gmail.com` with the subject line
`[wikantik-security]`. PGP / GPG-encrypted email is welcomed; ask for
the public key in your first message if you'd like one.

What to include:

- A clear description of the issue, including the affected component
  (REST endpoint, MCP tool, JAAS module, etc.).
- The conditions under which the vulnerability triggers (auth state,
  configuration, attacker capability).
- A proof-of-concept request, payload, or repro script.
- The Wikantik version (commit SHA or release tag) you tested against.
- Your assessment of impact and severity.
- Whether you'd like credit in the advisory (and how you'd like to be
  named).

I will acknowledge receipt within **72 hours** and provide a remediation
timeline within **7 days** of acknowledgement. Critical issues will be
prioritised; expect a fix or mitigation within **30 days** for high-severity
findings.

## Disclosure

Coordinated disclosure is the norm. Once a fix is available:

1. The fix lands on `main` and is included in the next release.
2. A GitHub Security Advisory is published, crediting the reporter (if
   they consent).
3. The CHANGELOG entry for the release identifies the fix and the CVE
   (if assigned).

## Supported versions

Wikantik is pre-1.0 / early-1.x. Until the project reaches 2.0, only
the most recent minor release receives security updates.

| Version | Supported? |
|---------|------------|
| `main` (development) | Yes |
| Latest released minor (e.g. `1.x` once tagged) | Yes |
| Older releases | No — please upgrade |

## Threat model in scope

Wikantik treats the following as in-scope:

- Authentication bypass (JAAS, OAuth/SSO via pac4j, JDBC user database).
- Authorisation bypass (page ACLs, policy_grants, group membership,
  admin-only endpoints, MCP access filters).
- CSRF / SSRF / XSS / injection (SQL, HTML, JNDI, expression-language).
- Insecure deserialization. We use `ObjectInputFilter` whitelists on
  every `ObjectInputStream` use; bypasses are in scope.
- Broken cryptography (password hashing, session tokens, API key issuance).
- Information disclosure via error responses, logs, or unauthenticated
  endpoints.
- Rate-limit / DoS evasion against the MCP servers, login endpoints, or
  the entity-extraction pipeline.

## Out of scope

- Vulnerabilities in third-party dependencies that the Wikantik team
  cannot fix without an upstream patch — please report those upstream;
  we'll consume the upstream fix.
- Issues that require a privileged operator to behave maliciously
  (e.g., an admin uploading a malicious page). Operators with
  `AllPermission` are intentionally trusted; the threat model is
  external attackers + lower-trust authenticated users.
- Self-XSS that requires the victim to paste attacker-controlled content
  into their own browser console.
- Missing security headers on `/wiki/{slug}?format=md|json` raw-content
  responses (these are intentionally permissive — they're public RAG
  ingestion endpoints).

## Hardening defaults

For reference, current production defaults include:

- TLS termination at Cloudflare / a reverse proxy in front of Tomcat.
- `<CookieProcessor sameSiteCookies="strict"/>` on the JSESSIONID cookie.
- `wikantik.admin.bootstrap` admin override only effective on first run.
- NIST 800-63B password validation with a common-password blocklist.
- `ObjectInputFilter` whitelists on every `ObjectInputStream`.
- IP-restricted `/metrics` endpoint (Prometheus); IP-restricted `/api/health`
  detail.
- Bearer-token / API-key auth on both MCP servers (`McpAccessFilter`,
  `KnowledgeMcpAccessFilter`).
- Database-backed policy grants — file-based `wikantik.policy` is a
  fallback only.

## Thank you

Security researchers who report responsibly will be credited (with their
permission) in the corresponding GitHub Security Advisory and CHANGELOG
entry.
