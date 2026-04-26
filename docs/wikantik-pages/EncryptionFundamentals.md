---
title: Encryption Fundamentals
type: article
cluster: security
status: active
date: '2026-04-25'
tags:
- encryption
- cryptography
- aes
- rsa
- ecc
- aead
summary: Symmetric vs asymmetric, AEAD ciphers, RSA vs ECC, hashing, KDFs — the
  primitives, when to reach for each, and the rules ("don't roll your own crypto")
  that genuinely matter.
related:
- SslTlsDeepDive
- PkiAndCertificates
- NumberTheory
- AbstractAlgebra
hubs:
- Security Hub
---
# Encryption Fundamentals

Encryption keeps secrets secret and detects when someone has tampered with data. The cryptographic primitives are mature and standardised; the way to ruin them is almost always at the application layer — wrong mode of operation, reused nonces, predictable IVs, hand-rolled key derivation.

This page is the working set: which primitive solves which problem, and the operational rules that distinguish "secure" from "looks secure."

## Two big families

| | Symmetric | Asymmetric |
|---|---|---|
| **Keys** | One shared secret | Public + private pair |
| **Speed** | Fast (GBs/sec on commodity CPUs) | Slow (KBs/sec for RSA, faster for ECC) |
| **Use** | Bulk data encryption | Key exchange, signatures, identity |
| **Examples** | AES, ChaCha20 | RSA, ECDSA, ECDH |

The standard composition: asymmetric to establish a shared symmetric key; symmetric to encrypt the actual data. TLS, Signal, every secure protocol does this. Asymmetric is too slow for bulk; symmetric needs a shared key, which asymmetric provides.

## Symmetric encryption

### AEAD ciphers

Modern symmetric encryption is AEAD (Authenticated Encryption with Associated Data). One operation provides confidentiality *and* integrity. Don't use older constructions (CBC mode + separate HMAC) for new code.

The two AEAD primitives that matter:

- **AES-GCM** — AES in Galois/Counter Mode. Hardware-accelerated on every modern CPU (AES-NI, ARM crypto extensions). The default for HTTPS, S3 server-side encryption, etc.
- **ChaCha20-Poly1305** — software-fast (no AES-NI needed), constant-time implementations are easy. Used in TLS 1.3 alongside AES-GCM, default in WireGuard.

For new code: AES-GCM if hardware acceleration is available, ChaCha20-Poly1305 otherwise. Both are excellent.

### Key sizes

- **AES-128** — 128-bit key. 2^128 brute force; secure for the foreseeable future against classical attackers.
- **AES-256** — 256-bit key. Quantum-resistant against Grover's algorithm (which halves the effective key length); recommended for long-confidentiality data.
- **ChaCha20** — 256-bit key.

Don't use AES-192. It's a strange middle that exists for historical reasons and isn't widely used; AES-128 or AES-256.

### Nonce handling: where everyone trips

AEAD ciphers take a nonce (number used once) per encryption. Reusing a nonce with the same key destroys the security of GCM and CCM specifically. ChaCha20-Poly1305 too.

Three correct nonce strategies:

- **Counter** — start at 0, increment per message. Simple, requires you don't crash and reset.
- **Random** — generate from CSPRNG. 96-bit nonces (GCM standard) are safe up to ~2^32 messages per key under birthday-bound logic.
- **Extended-nonce variants** (XChaCha20-Poly1305, XSalsa20-Poly1305) use 192-bit random nonces; collision probability vanishingly small. Use these when you can't reliably manage counters.

Nonce reuse is the single most common AEAD failure. Use a library that manages nonces correctly; don't hand-roll.

### Modes you should not use

- **ECB** — deterministic, leaks structural information. Famous for revealing the Linux penguin in encrypted images.
- **CBC without authentication** — vulnerable to padding oracles; malleable.
- **CTR without authentication** — same; ciphertext can be modified meaningfully.
- **Old hash-based MAC + cipher constructions** — easy to combine wrong (encrypt-then-MAC vs MAC-then-encrypt vs encrypt-and-MAC each have different security properties).

If you find yourself needing one of these, you almost certainly want AEAD instead.

## Asymmetric encryption and signatures

### RSA

The classic. Encryption / signing in the multiplicative group mod a 2048-bit (or larger) composite number.

- **Key size**: 2048-bit minimum, 3072-bit preferred for new keys. Smaller (1024-bit) is broken.
- **Signature**: RSA-PSS for new signatures (PSS, not the older PKCS#1 v1.5 padding). RSA-PKCS#1 v1.5 verification is needed for legacy compatibility.
- **Encryption**: RSA-OAEP padding. Don't use raw RSA or PKCS#1 v1.5 padding — both have known attacks.

In 2026, RSA persists for compatibility but ECC is preferred for new keys.

### Elliptic-curve cryptography (ECC)

Same operations (encryption, signatures, key exchange) over an elliptic curve group. Smaller keys for equivalent security:

- **256-bit ECC** ≈ **3072-bit RSA** in security level.
- **384-bit ECC** ≈ **7680-bit RSA**.

Faster, smaller, lower bandwidth. The default for new asymmetric crypto.

Curves to use:

- **Curve25519** (X25519 for ECDH, Ed25519 for signatures) — modern, fast, well-analysed, no obscure parameters. The right default.
- **NIST P-256 (secp256r1)** — older but ubiquitous; required for some compliance regimes (FIPS).
- **NIST P-384, P-521** — for higher security levels.

Avoid:

- **P-224, P-192, B-163** — too short or deprecated.
- **secp256k1** — used by Bitcoin; not the right pick for general crypto (P-256 or Curve25519 instead).
- **Custom curves** — unless you have a specific reason and know what you're doing.

### Post-quantum

Shor's algorithm breaks RSA, ECDSA, and ECDH if a sufficiently large quantum computer exists. NIST standardised post-quantum algorithms in 2024:

- **ML-KEM** (Module-Lattice KEM, formerly Kyber) — key encapsulation. Replaces RSA/ECDH for key exchange.
- **ML-DSA** (Module-Lattice DSA, formerly Dilithium) — signatures. Replaces RSA/ECDSA.

For most teams: continue using RSA/ECC; watch for hybrid PQ-classical libraries to mature; plan migration over the next several years. For high-value or long-confidentiality (decades-from-now relevant) data, start adopting hybrid PQ where libraries support it now.

## Hash functions

A hash maps arbitrary input to a fixed-length output, ideally with three properties:

- **Pre-image resistance** — given hash `h`, hard to find input `x` with `H(x) = h`.
- **Second pre-image resistance** — given input `x`, hard to find `x' ≠ x` with `H(x) = H(x')`.
- **Collision resistance** — hard to find any `x ≠ x'` with `H(x) = H(x')`.

The collision-resistance bound is roughly half the bit length: SHA-256 has 128-bit collision resistance.

In 2026:

- **SHA-256** — universal default. Hardware-accelerated.
- **SHA-512** — when 256-bit isn't enough; sometimes faster on 64-bit CPUs.
- **SHA-3** (Keccak) — different construction; backup if SHA-2 is ever broken (no signs of that).
- **BLAKE3** — fastest; strong, parallel-friendly. Not yet standardised everywhere but widely deployed.

Avoid: MD5 (broken since 2004), SHA-1 (collisions demonstrated in 2017), SHA-256 truncated to fewer than 128 bits (collision resistance drops below safe threshold).

### What hashes are NOT for

- **Password storage.** Use a key derivation function (Argon2id, scrypt, bcrypt). Hashing alone is too fast; brute force is feasible.
- **Encryption.** Hashes are one-way; encryption is reversible. Different problems.
- **Message integrity over a network with an active attacker.** Use HMAC or AEAD. Plain hash + plaintext is vulnerable to length-extension on SHA-2 (use HMAC-SHA-256).

## Key derivation

Turn a password or a high-entropy secret into one or more cryptographic keys.

For passwords: **Argon2id**, with appropriate parameters (memory cost, time cost). Parameters tuned to make brute-force expensive while not crippling legitimate logins.

For derivation from already-strong secrets: **HKDF** (HMAC-based Key Derivation Function). Cheap, well-understood, the right choice for "I have a 256-bit secret; I need three 256-bit keys for different purposes."

For older code: PBKDF2 was the standard before Argon2; bcrypt is fine for password hashing if Argon2 isn't available.

Never:

- Use SHA-256 of the password directly. Too fast; brute-force feasible at consumer hardware rates.
- Reuse the master secret as multiple keys. Use HKDF to derive per-purpose keys.

## Random number generation

CSPRNG — Cryptographically Secure Pseudo-Random Number Generator. Backed by the OS:

- **`/dev/urandom`** on Linux.
- **`getrandom()`** on modern Linux / glibc.
- **`SecRandomCopyBytes`** on macOS / iOS.
- **`BCryptGenRandom`** on Windows.

Language standard libraries usually wrap these:

- Python: `secrets` module.
- Go: `crypto/rand`.
- Rust: `rand::rngs::OsRng`.
- Java: `SecureRandom` (be careful — older `Random` is not secure).
- JS: `crypto.getRandomValues`.

Never use `rand()`, `Math.random()`, or any non-CSPRNG for cryptographic purposes. They're predictable; they're for simulations and games.

## The "don't roll your own" rule

The most often-cited rule in cryptography. It means three different things:

1. **Don't invent your own primitives.** Don't design a new cipher. The state of the art is the result of collective public review by professionals; your hobby cipher will lose.
2. **Don't reimplement existing primitives in production.** Use libraries. Hand-rolled AES will leak side-channel information; the library version is constant-time.
3. **Don't combine primitives in non-standard ways.** Don't invent your own protocol. Use TLS, Signal, age, libsodium constructions. Custom protocols mostly fail to subtle attacks.

What you can do:

- **Use** existing primitives correctly.
- **Compose** them via standardised, well-reviewed protocols.
- **Read** about cryptography to understand what you're using.

Libraries that make doing the right thing easy:

- **libsodium** — Tink, age — high-level APIs that prevent misuse.
- **rustls / BoringSSL / OpenSSL 3.x** — for TLS specifically.
- **Cryptography (Python)** — well-curated set of primitives.
- **Tink (Java/Go/Python/C++)** — Google's misuse-resistant API.

## A "what should I use for X" cheat sheet

| Need | Use |
|---|---|
| Encrypt a file with a password | age (`age -p`), or libsodium's `crypto_secretstream` with Argon2id key |
| Encrypt a payload with a known key | AES-GCM or ChaCha20-Poly1305, with a CSPRNG nonce |
| Encrypt for a known recipient | age (`age -r <recipient>`), or libsodium's `crypto_box` |
| Sign data | Ed25519 (`crypto_sign_detached` in libsodium) |
| Authenticate that data hasn't changed | HMAC-SHA-256 |
| Hash a password for storage | Argon2id |
| Generate a session token | 256+ bits from CSPRNG; base64url-encode |
| Hash a file for integrity | SHA-256 or BLAKE3 |
| Establish a shared key over insecure channel | TLS 1.3 if it's a real network; libsodium's `crypto_kx` for in-app |
| Derive multiple keys from a master | HKDF-SHA-256 |

## Further reading

- [SslTlsDeepDive] — TLS as the primary application of these primitives
- [PkiAndCertificates] — how the asymmetric trust model is structured
- [NumberTheory] — the math behind RSA / ECC
- [AbstractAlgebra] — the deeper math behind ECC specifically
