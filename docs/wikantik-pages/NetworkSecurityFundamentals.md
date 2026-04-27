---
canonical_id: 01KQ0P44SZQC20CSFG3GG5S1JM
title: Network Security Fundamentals
type: article
cluster: security
status: active
date: '2026-04-26'
summary: The network security basics — perimeter vs. zero-trust, segmentation, encryption
  in transit, and the operational practices that prevent the most common network-level
  attacks.
tags:
- network-security
- zero-trust
- segmentation
- tls
- security
related:
- VulnerabilityManagement
- WebApplicationFirewalls
- TcpIpFundamentals
- CloudSecurityFundamentals
---
# Network Security Fundamentals

Network security is the discipline of protecting data in transit and controlling access between systems. The traditional perimeter model (firewall around the trusted inside) has been giving way to zero-trust (don't trust anything; verify everything). Modern networks combine both.

This page covers the fundamentals.

## Perimeter security (the old model)

The traditional model:
- Firewall at the network boundary
- Trusted inside; untrusted outside
- VPN for remote access
- Internal services trust each other

Pros: simple; cheap to operate.
Cons: attacker who gets inside has free rein; doesn't fit cloud or mobile.

For most modern environments, perimeter alone is insufficient.

## Zero-trust (the modern model)

Don't trust anything based on network location. Authenticate and authorize every request, including internal ones.

Principles:
- **Verify identity** for every connection
- **Authorize per request**, not per session
- **Encrypt** all traffic, even internal
- **Least privilege** by default
- **Monitor** continuously

Implementation:
- Service-to-service mTLS
- Identity-aware proxies
- Per-request authorization checks
- Network segmentation

This is significant work but reduces lateral movement risk after compromise.

## Segmentation

Divide the network into segments. Limit lateral movement.

### VPCs / VLANs

Logical network boundaries. Different VPCs don't see each other unless explicitly peered.

### Security groups (cloud)

Instance-level firewall rules. "Only the load balancer can talk to the application instances on port 8080."

### Service mesh

mTLS + identity-based authorization between services. See [ServiceMeshArchitecture](ServiceMeshArchitecture).

### Database segmentation

Database servers in private subnet. Only application servers can connect. No direct internet access.

## Encryption in transit

All traffic should be TLS-encrypted, even internal.

### TLS basics

- Server presents certificate
- Client validates certificate against trusted CAs
- Encrypted session established

For modern apps, TLS 1.2 minimum; 1.3 preferred. Older versions (SSL, TLS 1.0/1.1) are deprecated and have known vulnerabilities.

### Certificate management

Let's Encrypt for public certs (free, automated). AWS Certificate Manager / GCP Certificate Manager for managed renewal.

For internal services: private CA with automated rotation. Cert lifecycle is operational work; automate it.

### mTLS

Mutual TLS: both sides authenticate. The server presents a cert; the client also presents a cert. Both verify.

For service-to-service auth, mTLS is more secure than API keys. Service meshes provide it transparently.

## Common attacks and defenses

### DDoS

Distributed denial of service: many sources flooding your service.

Defenses:
- Cloud DDoS protection (AWS Shield, Cloudflare, Google Cloud Armor)
- CDN buffering
- Rate limiting at edge
- Capacity sufficient for the expected attack scale

### Man-in-the-middle

Attacker intercepts traffic between client and server.

Defenses:
- TLS (encrypted; certificate validates server)
- Certificate pinning for high-stakes apps
- HSTS to prevent TLS downgrade

### Network reconnaissance

Attacker scans your network for vulnerabilities.

Defenses:
- Don't expose unnecessary services to the internet
- Firewall by default; allow specific
- Cloud security groups
- Disable unused ports/services

### Phishing-derived access

Attacker phishes a user; uses their credentials to access internal network.

Defenses:
- MFA universally
- Conditional access (location, device posture)
- Zero-trust (compromised user has limited blast radius)

## Patterns

### Defense in depth

Multiple layers of security. Perimeter firewall + segmentation + mTLS + authorization. Compromise of one layer doesn't grant full access.

### Principle of least privilege

Each component has the minimum permissions needed. Don't grant broad access "in case it's needed later."

### Public/private subnets

Public subnets: load balancers, bastion hosts. Private subnets: application servers, databases.

Internet traffic enters public; internal traffic stays in private.

### Bastion hosts vs. SSM Session Manager

Old: bastion host (jump server) for SSH access.
Modern: AWS SSM Session Manager — no SSH; session through AWS APIs. More auditable; no port 22 exposed.

### VPN vs. zero-trust networking

Old: VPN to access internal services.
Modern: zero-trust apps (each user authenticates per app); no flat internal network.

## Cloud-specific patterns

### Security groups (AWS)

Instance-level firewall. Stateful. Reference other security groups: "the database SG allows the application SG."

### NACLs (AWS)

Subnet-level. Stateless. Used for broad rules (block specific IP ranges).

### VPC endpoints

Access AWS services without going through internet. Reduces exposure.

### PrivateLink

Expose specific services to specific accounts without internet routing.

### WAF

Web Application Firewall at the edge. See [WebApplicationFirewalls](WebApplicationFirewalls).

## Common failure patterns

- **Default-allow firewall rules.** Compromised hosts have free rein.
- **No internal encryption.** Once inside the network, traffic is plaintext.
- **Old TLS versions.** Known vulnerabilities.
- **Self-signed certs accepted.** Defeats TLS purpose.
- **MFA only on some services.** Attacker uses MFA-less services.
- **No network monitoring.** Attacks unnoticed.

## A reasonable baseline

For a typical web app:

1. Public subnet for load balancers; private for everything else
2. Security groups: deny by default; allow specific
3. TLS 1.2+ for all external traffic; mTLS or TLS for internal
4. MFA for all human access
5. Cloud-managed DDoS protection
6. WAF for HTTP-facing services
7. Monitoring + alerting

## Further Reading

- [VulnerabilityManagement](VulnerabilityManagement) — Adjacent practice
- [WebApplicationFirewalls](WebApplicationFirewalls) — Edge security
- [TcpIpFundamentals](TcpIpFundamentals) — Network basics
- [CloudSecurityFundamentals](CloudSecurityFundamentals) — Cloud-specific
