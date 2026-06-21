---
status: active
date: '2026-05-15'
summary: 'Zero Trust implementation: workload identity via SPIFFE/SPIRE, mTLS for
  service-to-service auth, and policy-driven microsegmentation with OPA.'
tags:
- zero-trust
- mtls
- spiffe
- spire
- opa
- microsegmentation
type: article
auto-generated: false
canonical_id: 01KQ0P44ZBTB26G1T0B1NJ68D9
cluster: security
title: Zero Trust Architecture
---

# Zero Trust Architecture

Zero Trust Architecture (ZTA) is a security framework based on the realization that traditional perimeter-based security is obsolete. It operates on the principle of "Never Trust, Always Verify," requiring strict identity verification for every person and device trying to access resources on a private network, regardless of whether they are sitting inside or outside of the network perimeter.

## Workload Identity: The SPIFFE/SPIRE Standard

In dynamic, cloud-native environments, IP addresses are ephemeral and cannot serve as reliable identity markers. ZTA shifts identity to the **Workload** level using the **SPIFFE** (Secure Production Identity Framework for Everyone) standard.

### SPIFFE ID and SVID
-   **SPIFFE ID:** A structured URI that uniquely identifies a workload (e.g., `spiffe://example.org/ns/billing/sa/payment-processor`).
-   **SVID (SPIFFE Verifiable Identity Document):** The cryptographically signed document (typically an X.509 certificate) that proves the workload's identity.

### SPIRE: The Implementation Engine
SPIRE (the SPIFFE Runtime Environment) automates the issuance and rotation of SVIDs. It consists of:
1.  **SPIRE Server:** Manages identities and attests the nodes (using TPM, AWS IAM, etc.).
2.  **SPIRE Agent:** Runs on the node and performs **Workload Attestation** (verifying the process's UID, GID, or container image hash) before handing it an SVID.

## Mutual TLS (mTLS) Implementation

ZTA mandates that all service-to-service communication is encrypted and mutually authenticated. Unlike standard TLS (where only the server proves its identity), mTLS requires both parties to present certificates.

### The Handshake Flow in Service Mesh
1.  **Identity Bootstrapping:** A sidecar proxy (e.g., Envoy) retrieves its SVID from the SPIRE agent via the Workload API.
2.  **Connection Request:** Service A initiates a TLS handshake with Service B.
3.  **Mutual Authentication:** Service A presents its certificate to B; B presents its certificate to A. Both verify against a common Root CA.
4.  **Secure Tunnel:** Once verified, the connection is established. Traffic is encrypted with ephemeral session keys.

## Policy-Driven Microsegmentation

Network-level firewalls are too coarse for ZTA. Instead, we use **Microsegmentation** driven by a **Policy Decision Point (PDP)** such as **Open Policy Agent (OPA)**.

### Policy as Code: Rego Example
OPA uses the **Rego** language to define fine-grained authorization policies. This allows decoupling security logic from the application code.

```rego
package envoy.authz

import input.attributes.request.http as http_request

default allow = false

# Allow access to the payment API only if:
# 1. The caller has a valid SPIFFE ID from the 'billing' namespace
# 2. The HTTP method is POST
# 3. The caller's certificate is not expired
allow {
    is_post
    is_billing_service
    not certificate_expired
}

is_post = http_request.method == "POST"

is_billing_service {
    # Extract SPIFFE ID from the X-Forwarded-Client-Cert header
    cert_data := http_request.headers["x-forwarded-client-cert"]
    contains(cert_data, "URI=spiffe://example.org/ns/billing/")
}

certificate_expired {
    # Check current time vs cert expiration (simplified logic)
    time.now_ns() > input.attributes.source.certificate_expiration
}
```

## Hardware Root of Trust (TPM/HSM)

To prevent identity theft, the private keys used for SVIDs should never be stored in plaintext on the file system.
-   **TPM (Trusted Platform Module):** A dedicated microcontroller that secures hardware through integrated cryptographic keys. SPIRE can use TPM-based Node Attestation to prove the node's physical integrity.
-   **HSM (Hardware Security Module):** Used by the SPIRE Server or Root CA to protect the signing keys for the entire trust domain.

## Continuous Monitoring and Adaptive Risk

ZTA is not a "once and done" check. It requires **Continuous Verification**:
1.  **Session Expiry:** Short-lived certificates (minutes or hours) force frequent re-authentication.
2.  **Threat Intelligence Integration:** If an identity's risk score increases (e.g., detected anomalous behavior in the SIEM), the PDP can immediately revoke access by refusing to authorize further requests, even if the certificate is still valid.
3.  **Observability:** Every access decision (Permit/Deny) and mTLS handshake must be logged to a centralized telemetry sink for audit and forensic analysis.

## Further Reading
- [AuthenticationAndAuthorization](AuthenticationAndAuthorization)
- [PkiAndCertificates](PkiAndCertificates)
- [ServiceMeshArchitecture](ServiceMeshArchitecture)
- [NetworkSegmentation](NetworkSegmentation)
