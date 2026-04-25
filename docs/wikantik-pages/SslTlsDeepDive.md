---
canonical_id: 01KQ12YDWQAX1JYG96XFXANHQE
title: Ssl Tls Deep Dive
type: article
cluster: security
status: active
date: '2026-04-25'
tags:
- tls
- ssl
- cryptography
- pki
- https
summary: TLS 1.3 handshake, the certificate ecosystem, and the operational
  decisions (cipher suites, key rotation, OCSP, certificate transparency)
  that distinguish a secure deployment from a checkbox one.
related:
- EncryptionFundamentals
- PkiAndCertificates
- ApplicationSecurityFundamentals
- ZeroTrustArchitecture
hubs:
- Security Hub
---
# TLS Deep Dive

TLS is the protocol that turns "internet" into "internet you can do banking on." TLS 1.3 (2018) is what you should be deploying in 2026; TLS 1.2 is acceptable as a fallback; anything older is a vulnerability. SSL is the historical name and is fully dead — calling current versions "SSL" is a mistake that surfaces in old documentation and bad config defaults.

This page is the working understanding: what the handshake actually does, what knobs matter operationally, and where teams misconfigure.

## What TLS provides

Three guarantees, each from a different cryptographic primitive:

- **Confidentiality** — what you send can't be read by anyone in the middle. From symmetric encryption (AES-GCM, ChaCha20-Poly1305).
- **Integrity** — what you send can't be modified in flight without detection. From an AEAD (Authenticated Encryption with Associated Data) construction.
- **Authentication** — you're talking to who you think you're talking to. From asymmetric crypto + certificates (X.509) + a trust hierarchy (CAs).

Authentication is the one most people get wrong, because confidentiality and integrity are mostly automatic once you turn on TLS, and authentication is where mismatched configurations and stale certs live.

## TLS 1.3 handshake, briefly

The big improvement over 1.2: one round-trip for the handshake instead of two, plus several historically-exploited features removed (RSA key exchange, CBC mode ciphers, MD5/SHA1 in signatures, renegotiation).

```
Client                                            Server
  ─── ClientHello ──────────────────────────────▶
      (supported ciphers, key share, extensions)
                          ◀───────── ServerHello, Certificate,
                                     CertificateVerify, Finished ───
      (handshake complete, application data flowing in same RTT)
  ─── Application data ──────────────────────────▶
                          ◀───────── Application data ─
```

Net result: web requests over TLS 1.3 land roughly 100ms sooner than 1.2 on a typical transatlantic path. 0-RTT mode shaves another round-trip on resumption (useful and slightly risky — replay attacks possible on idempotent requests).

Use TLS 1.3 wherever possible. Disable 1.2 only if you've confirmed no client needs it; TLS 1.0/1.1 should be off everywhere.

## Certificates and the trust chain

A TLS certificate binds a public key to a domain name. The signature on the cert is the assertion of trust:

```
Server cert (signed by) → Intermediate CA cert (signed by) → Root CA cert
```

Your browser ships with a list of trusted root CAs (~150 of them, varies by OS). The chain of signatures must terminate at one of those roots for the connection to be trusted.

Certificate fields that matter:

- **CN (Common Name) and SAN (Subject Alternative Name)** — the names this cert is valid for. Modern best practice: name only in SAN; CN is legacy.
- **NotBefore / NotAfter** — validity window. Public CA certs are now max 90 days (after the 2024 industry shift down from 398).
- **Public key** — the key being authenticated. RSA-2048 minimum, RSA-3072 preferred, or ECDSA P-256.
- **Signature algorithm** — SHA-256 or stronger; SHA-1 is forbidden.

## Getting certificates: in 2026

- **Let's Encrypt + ACME** — the default. Free, automated, 90-day certs. Use a tool (Certbot, Caddy, lego, cert-manager for K8s) to handle renewal.
- **Public CA paid** — DigiCert, GlobalSign, etc. Useful when you need EV (no longer browser-distinguished but still useful for some compliance), wildcard-certs at higher rate, or specific support contracts.
- **Internal CA** — for internal-only services. Run your own (with HashiCorp Vault, AWS Private CA, smallstep) and distribute the root to internal trust stores.
- **ACME for internal CAs** — with `step-ca` or Vault PKI, you can run ACME internally too. Strongly recommended at scale.

If your team is still manually rotating certs, that's a liability. Automate ACME everywhere it works.

## Cipher suites and what to actually pick

A cipher suite specifies the key-exchange, signature, encryption, and MAC algorithms. TLS 1.3 simplified this: only AEAD ciphers, only PFS-providing key exchanges. Five cipher suites total in the spec; defaults are sane.

For TLS 1.2 (which you may still need to support):

```
TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256
TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
```

All ECDHE (forward secrecy), all GCM or ChaCha20-Poly1305 (AEAD), no RC4, no 3DES, no CBC.

Mozilla's SSL Configuration Generator publishes maintained recommendations for nginx, Apache, HAProxy, etc. Use it; don't hand-pick cipher lists.

## Forward secrecy

The property that capturing today's encrypted traffic doesn't let an attacker decrypt it later, even if they steal the server's private key.

Achieved by ephemeral key exchange — ECDHE in modern deployments. The server's long-term key is used only for *signing* the ephemeral key share; the actual encryption keys are derived from the ephemeral exchange and discarded.

TLS 1.3 makes this mandatory; in 1.2 it requires the right cipher suites (the ECDHE_* ones above). Forward secrecy is the reason "harvest now, decrypt later" attacks against modern TLS don't trivially work.

## OCSP, CRL, certificate revocation

When a certificate is compromised, you want to revoke it. Two mechanisms:

- **CRL (Certificate Revocation List)** — CA publishes a list of revoked certs. Clients download it. Slow, large lists, often skipped by browsers.
- **OCSP (Online Certificate Status Protocol)** — client queries the CA for one cert's status. Faster but adds a network call to handshake.

The reality: revocation checking is broken in practice. Browsers often skip it; CRL lists go stale; OCSP responders fail. Modern mitigation is **OCSP stapling** — the server attaches a recent OCSP response to its handshake, so the client doesn't have to ask the CA. Configure it; most modern web servers support it.

For high-stakes contexts, **OCSP must-staple** (a cert extension) tells clients to refuse if the staple is missing — closing the soft-fail loophole.

For internal services where you control both sides, short-lived certs (1-7 day validity) effectively replace revocation. The cert expires before revocation matters.

## Certificate Transparency

Every public CA must log every cert it issues to public CT logs. Anyone can monitor logs for unauthorised issuance.

Practical use: monitor CT logs for your domains via crt.sh or commercial tools. Alert on unexpected certs — usually a precursor to a phishing attack or a misconfigured internal CA accidentally publishing internal cert names.

## TLS pitfalls in production

**Cert chain incomplete.** Server sends only the leaf cert; client doesn't have the intermediate; client errors. Always send the full chain except the root. Verify with `openssl s_client -connect host:443 -showcerts`.

**Wrong SAN.** Cert valid for `example.com` but you accessed `www.example.com`. Add both to SAN.

**Expired cert.** Surprisingly common. Monitoring cert expiry is non-negotiable. Alert at 30 days; auto-renew long before.

**Mixed-content / partial TLS.** Page loaded over HTTPS includes resources over HTTP. Browsers block; users see broken pages. Audit your assets.

**HSTS not configured.** Without HSTS, an attacker on the path can downgrade HTTPS to HTTP for the first request. `Strict-Transport-Security: max-age=31536000; includeSubDomains; preload` and submit to the HSTS preload list.

**SNI required but not sent.** Multi-tenant load balancers route by SNI; clients without SNI get the default vhost cert. Almost no modern client lacks SNI; legacy IoT might.

**TLS termination at LB but plaintext to backend.** Common pattern. Acceptable in many threat models; disastrous in zero-trust environments. Re-encrypt to backends if your model demands it.

**Client cert auth confusion.** Mutual TLS for client authentication is powerful but operationally tricky. Cert distribution and rotation become production concerns. Use it where it fits (service-to-service, IoT) but understand the operational cost.

## Quantum threat horizon

Shor's algorithm breaks RSA and ECC if a sufficiently large quantum computer ever exists. As of 2026 it doesn't, but harvest-now-decrypt-later is a real concern for high-value long-lived data.

NIST's post-quantum standardisation produced ML-KEM (key encapsulation, formerly Kyber) and ML-DSA (signatures, formerly Dilithium). TLS 1.3 hybrid modes combining classical and PQ key exchange are starting to deploy in 2026.

For most teams: continue with current TLS, watch for hybrid-PQ TLS support landing in your libraries, plan a migration over the next few years. For high-value or long-confidentiality data, start adopting hybrid-PQ where libraries support it.

## Testing your TLS

- **SSL Labs server test** (ssllabs.com/ssltest) — most thorough; gives a letter grade; spot-checks misconfigurations.
- **`testssl.sh`** — open source equivalent; runs locally.
- **`openssl s_client` and `nmap --script ssl-enum-ciphers`** — for command-line investigation.
- **Internal CT log monitor** — for unexpected cert issuance.

Aim for SSL Labs A+. Anything below A is missing something fixable.

## Further reading

- [EncryptionFundamentals] — the symmetric and asymmetric primitives TLS uses
- [PkiAndCertificates] — the CA/cert ecosystem in depth
- [ApplicationSecurityFundamentals] — broader app-sec context
- [ZeroTrustArchitecture] — TLS in zero-trust models
