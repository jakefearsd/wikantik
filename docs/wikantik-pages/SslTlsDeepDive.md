---
canonical_id: 01KQ12YDWQAX1JYG96XFXANHQE
title: SSL/TLS Deep Dive
type: article
cluster: security
status: active
date: '2026-05-15'
tags:
- tls
- ssl
- cryptography
- pki
- https
auto-generated: false
summary: Engineering secure transport in 2026. Handshake analysis, cipher suite
  optimization for TLS 1.3, and automated PKI management with ACME.
related:
- EncryptionFundamentals
- PkiAndCertificates
- ApplicationSecurityFundamentals
- ZeroTrustArchitecture
hubs:
- SecurityHub
---
# SSL/TLS Deep Dive

Transport Layer Security (TLS) 1.3 is the mandated standard for all Wikantik services. This deep dive covers the mechanics of the handshake, certificate lifecycle automation, and the operational troubleshooting needed for high-availability secure systems.

## 1. Handshake Evolution: TLS 1.3 vs 1.2

TLS 1.3 (RFC 8446) reduced the handshake latency by one full Round Trip Time (RTT) and eliminated multiple insecure cryptographic primitives.

| Feature | TLS 1.2 | TLS 1.3 |
|---|---|---|
| **Handshake Latency** | 2-RTT | 1-RTT (or 0-RTT on resumption) |
| **Cipher Suites** | Over 300 (many insecure) | 5 (all AEAD) |
| **RSA Key Exchange** | Supported (Lack of Forward Secrecy) | Removed (PFS mandatory) |
| **Handshake Signature** | Negotiable (prone to downgrade) | Mandatory hashing of all previous msgs |
| **Extensions** | Plaintext | Encrypted |

## 2. Operational Verification

To verify the security of a live endpoint, use `openssl` directly. 

### Checking for OCSP Stapling
OCSP Stapling prevents the "privacy leak" where a client must contact the CA to verify a certificate.
```bash
# Verify if a server is stapling its OCSP response
openssl s_client -connect wikantik.example.com:443 -status 2>&1 | grep -A 17 "OCSP response"
```

### Investigating Certificate Chains
A common misconfiguration is sending the "Leaf" certificate without the "Intermediate" chain.
```bash
# Display the full certificate chain
openssl s_client -showcerts -connect wikantik.example.com:443 < /dev/null
```

## 3. Automated PKI with ACME

In 2026, manual certificate rotation is a technical debt. We use the **Automatic Certificate Management Environment (ACME)** protocol.

*   **Public Services:** Let's Encrypt or ZeroSSL.
*   **Internal Services:** Smallstep `step-ca` or HashiCorp Vault.

### Caddy (Recommended Edge Server)
Caddy manages ACME automatically by default. 
```caddy
# Caddyfile example
wikantik.example.com {
    reverse_proxy localhost:8080
    tls {
        dns cloudflare {env.CLOUDFLARE_API_TOKEN}
    }
}
```

## 4. Cipher Suite Hardening

If you must support TLS 1.2 for legacy clients, use only **Authenticated Encryption with Associated Data (AEAD)** suites with **Elliptic Curve Diffie-Hellman Ephemeral (ECDHE)** for perfect forward secrecy.

**Mandated TLS 1.2 List:**
1.  `ECDHE-ECDSA-AES128-GCM-SHA256`
2.  `ECDHE-RSA-AES128-GCM-SHA256`
3.  `ECDHE-ECDSA-CHACHA20-POLY1305`

## 5. Security Pitfalls: HSTS and Pinning

### HSTS (HTTP Strict Transport Security)
Without HSTS, a user's first request is over HTTP, vulnerable to an `sslstrip` attack.
*   **Fix:** Send the header: `Strict-Transport-Security: max-age=31536000; includeSubDomains; preload`.

### The Death of Certificate Pinning
Public Key Pinning (HPKP) is effectively dead. It was too easy to "brick" a site by losing the pinned keys. 
*   **2026 Strategy:** Use **Certificate Transparency (CT)** monitoring instead. Use tools like `crt.sh` to alert whenever a certificate for your domain is issued by a CA you don't control.

## Further Reading
* [EncryptionFundamentals](EncryptionFundamentals)
* [PkiAndCertificates](PkiAndCertificates)
* [ZeroTrustArchitecture](ZeroTrustArchitecture)
