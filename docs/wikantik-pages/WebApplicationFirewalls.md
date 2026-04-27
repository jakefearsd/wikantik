---
canonical_id: 01KQ0P44YR0BXN56PBQWNHB5VV
title: Web Application Firewalls
type: article
cluster: security
status: active
date: '2026-04-26'
summary: What WAFs actually do — rule-based filtering, OWASP Top 10 protection, rate
  limiting — the major options (AWS WAF, Cloudflare, Imperva), and the cases where
  they help vs. where they're security theater.
tags:
- waf
- web-security
- owasp
- ddos-protection
- security
related:
- NetworkSecurityFundamentals
- CdnArchitecture
- CloudSecurityFundamentals
- VulnerabilityManagement
---
# Web Application Firewalls

A Web Application Firewall (WAF) sits at the edge of your application; inspects HTTP traffic; blocks malicious requests. Common defenses: SQL injection, XSS, OWASP Top 10 patterns, bot traffic, DDoS.

WAFs are useful but often oversold. They're one layer of defense, not a replacement for secure code.

## What a WAF does

### Rule-based filtering

Pattern-match against known attack signatures:

- SQL injection: `' OR 1=1 --`
- XSS: `<script>...`
- Path traversal: `../../etc/passwd`
- Command injection: `; cat /etc/passwd`

Block requests matching the patterns.

### OWASP Core Rule Set (CRS)

A standard ruleset for WAFs. Covers most well-known web attacks. Foundation that most WAFs build on.

### Rate limiting

Limit requests per client. Mitigates brute force, scraping, basic DoS.

### Bot detection

Distinguish humans from bots. Block scrapers, scanners, credential stuffers.

### IP reputation

Block requests from known-bad IPs (TOR exits, recently-abusive ranges).

### Custom rules

Organization-specific rules: "block requests with this header"; "rate-limit /login by IP."

## Major WAFs

### AWS WAF

Native to AWS. Integrates with CloudFront, ALB, API Gateway. Rule-based; managed rules available; pay per request.

For AWS-hosted apps, the default choice.

### Cloudflare

CDN with integrated WAF. Strong on DDoS protection; large managed rule library.

For sites in Cloudflare CDN, often the right answer.

### Imperva

Enterprise-focused. Comprehensive; expensive; common in regulated industries.

### Akamai

Enterprise CDN with WAF. Similar position to Imperva.

### F5

Hardware/cloud WAF. Enterprise.

### ModSecurity

Open-source WAF library. Used in many products including the AWS Marketplace WAF rules.

For most cloud apps, AWS WAF or Cloudflare. For self-hosted, ModSecurity behind Nginx.

## When WAF helps

### Defense in depth

Even with secure code, WAF catches:
- 0-day attacks (vulnerability disclosed but not yet patched in your code)
- Bots scanning for known vulnerabilities
- Mass attacks against widely-used libraries

The WAF buys time during patching.

### DDoS mitigation

CDN-integrated WAFs absorb large attacks before they hit your infrastructure. AWS Shield Advanced, Cloudflare DDoS protection.

### Compliance

Some frameworks (PCI-DSS) require a WAF or equivalent.

### Bot mitigation

Credential stuffing, scraping, scalping. WAFs detect and block.

## When WAF is theater

### Security via WAF only

WAF as a substitute for secure coding. Eventually fails; the original vulnerability is still in the code.

WAF is one layer. Secure code, dependency management, vulnerability management — these matter more.

### Tuning ignored

WAF deployed; default rules applied; never tuned. False positives accumulate; real attacks slip through (because rules are too generic or too disabled).

### Performance ignored

WAF inspection adds latency. At scale, the cost is real. Not a reason to skip WAF, but plan for it.

## False positives

The hardest part of WAF operation. Default rules block too much:

- Legitimate user inputs that look like attacks
- API requests that resemble injection attempts
- Specific application patterns

Tuning:
- Whitelist legitimate patterns
- Adjust rule sensitivity
- Mode: detect-only initially; block once tuned

A noisy WAF gets bypassed by frustrated developers. A WAF in detect-only mode forever provides no protection.

## Specific patterns

### Detect, then block

Run new rules in detect-only first. Watch for false positives. Switch to block when confident.

### Per-route configuration

Different routes have different threat profiles. `/api/upload` accepts file uploads; `/login` is rate-limit-sensitive. Tune per route.

### Geographic blocking

If your service is US-only, blocking traffic from other countries reduces attack surface. Not a primary defense; complementary.

### Custom rules for known threats

Recently exploited vulnerability in your stack? Custom WAF rule blocks it while you patch.

### Logging and analytics

WAF logs are useful: what's being blocked? What attacks are common? Feed to SIEM.

## The OWASP Top 10

WAFs primarily defend against the OWASP Top 10:

1. Broken Access Control
2. Cryptographic Failures
3. Injection (SQL, command, etc.)
4. Insecure Design
5. Security Misconfiguration
6. Vulnerable Components
7. Identification and Authentication Failures
8. Software and Data Integrity Failures
9. Security Logging and Monitoring Failures
10. Server-Side Request Forgery

Note: WAF helps most with #3 (injection), partially with #6 (vulnerable components — virtual patching). Other categories require different controls.

## Common failure patterns

- **WAF as the only defense.** Secure code matters more.
- **Default rules without tuning.** Blocks legitimate; misses real.
- **WAF behind nothing.** No CDN; WAF capacity hit by DDoS.
- **No log analysis.** WAF blocking; no learning from it.
- **Bypassed by attackers.** WAF circumvented; attack continues.
- **Compliance theater.** Box checked; not actually protecting.

## A reasonable approach

For typical web apps:

1. CDN with integrated WAF (Cloudflare, AWS WAF + CloudFront)
2. Default managed rules (OWASP CRS)
3. Initial detect-only mode; tune for false positives
4. Switch to block mode once tuned
5. Custom rules for app-specific threats
6. Logs to SIEM or analytics
7. Treat WAF as one layer; not the only one

## Further Reading

- [NetworkSecurityFundamentals](NetworkSecurityFundamentals) — Broader network security
- [CdnArchitecture](CdnArchitecture) — Edge layer
- [CloudSecurityFundamentals](CloudSecurityFundamentals) — Cloud-specific
- [VulnerabilityManagement](VulnerabilityManagement) — Adjacent practice
