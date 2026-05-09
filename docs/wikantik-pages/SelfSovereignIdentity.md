---
title: Self-Sovereign Identity
type: article
cluster: blockchain-tech
status: active
date: 2026-05-15
summary: Deep dive into Decentralized Identity (DID). Analysis of DID Documents and the mathematics of Zero-Knowledge Proofs for privacy-preserving verification.
auto-generated: false
kg_include: true
---

# Self-Sovereign Identity: Decentralized Trust

**Self-Sovereign Identity (SSI)** is a model for digital identity that gives individuals full control over their data. Unlike centralized models (e.g., Google/Facebook Login), SSI relies on Decentralized Identifiers (DIDs) and Verifiable Credentials (VCs) anchored to a blockchain.

## 1. Decentralized Identifiers (DIDs)

A **DID** is a new type of identifier that is globally unique, resolvable with high availability, and cryptographically verifiable. Unlike a username or email, it is not "rented" from a service provider.

### Concrete Example: W3C DID Document (JSON-LD)
When a DID is resolved (e.g., `did:example:123456789abcdefghi`), the system returns a **DID Document** which contains public keys and service endpoints.

```json
{
  "@context": [
    "https://www.w3.org/ns/did/v1",
    "https://w3id.org/security/suites/ed25519-2020/v1"
  ],
  "id": "did:example:123456789abcdefghi",
  "verificationMethod": [{
    "id": "did:example:123456789abcdefghi#keys-1",
    "type": "Ed25519VerificationKey2020",
    "controller": "did:example:123456789abcdefghi",
    "publicKeyMultibase": "z6MkmjY8GnVqcY8N9TjE5Y8N9TjE5Y8N9TjE5Y8N9TjE"
  }],
  "authentication": [
    "did:example:123456789abcdefghi#keys-1"
  ],
  "service": [{
    "id": "did:example:123456789abcdefghi#vcs",
    "type": "VerifiableCredentialService",
    "serviceEndpoint": "https://example.com/vc-issuer"
  }]
}
```
**Engineering Note**: The `verificationMethod` allows a verifier to check a digital signature from the controller of the DID without querying a central database.

## 2. Zero-Knowledge Proofs (zk-SNARKs)

The most advanced feature of SSI is the ability to prove a claim without revealing the underlying data. This is achieved using **Zero-Knowledge Succinct Non-Interactive Arguments of Knowledge (zk-SNARKs)**.

### Concrete Example: Age Verification (The "Greater Than X" Proof)
Suppose a user wants to prove they are over 18 without revealing their exact birthdate.

1.  **The Statement**: "I possess a credential signed by the Government (Public Key $P_G$) that contains a birthdate $D$, where $D \le (\text{Current Date} - 18\text{ years})$."
2.  **The Math**:
    *   The user generates a proof $\pi$ using a circuit $C$.
    *   $C$ takes private inputs (the birthdate $D$ and the Government signature $\sigma$) and public inputs (the "threshold" date $T$ and the Government's public key $P_G$).
    *   The circuit verifies:
        1.  $\text{VerifySignature}(D, \sigma, P_G) == \text{true}$
        2.  $D \le T$
3.  **The Proof**: The user sends only the proof $\pi$ to the verifier (e.g., an online liquor store).
4.  **Verification**: The verifier runs $\text{Verify}(\pi, T, P_G)$. If it returns `true`, the verifier is mathematically certain the user is over 18, but they **never see the birthdate $D$ or the signature $\sigma$**.

**Succinctness**: In 2026, these proofs are typically < 300 bytes and can be verified in milliseconds, making them practical for web-scale applications.
