# Connector Credential Encryption Design (P2.2)

**Status:** approved 2026-07-11
**Roadmap brief:** `RoadmapConnectorFramework` (wiki) — the credential-storage prerequisite for authenticated connectors.
**Builds on:** the shipped connector framework + P2.1 runtime + admin surface.

## Scope

Reversible **encryption-at-rest for connector secrets** (API keys, OAuth tokens, PATs), so a future authenticated connector can store and retrieve credentials without them ever sitting in config or logs. **This sub-project is the storage + admin layer ONLY** — no connector consumes credentials yet (that is the next sub-project, P2.3b: GitHub/Confluence/Drive).

**Decisions (owner-confirmed):** master key from **config/.env** (not a KMS); **key rotation deferred** to a follow-up. Credentials are injected via an **authed admin endpoint**, never plaintext config.

**Non-goals:** key rotation / multi-key coexistence; OAuth flows / token refresh; any authenticated connector; a secrets-manager integration; per-credential ACLs beyond the admin gate.

## Architecture (spread by responsibility — invariant #6)

```
wikantik-util   AesGcmCipher  — AES-256-GCM, JDK javax.crypto (NO new dependency).
                  AesGcmCipher(SecretKey key);  String encrypt(String plaintext);  String decrypt(String token);
                  static SecretKey keyFromBase64(String b64)  (validates 32 bytes → AES-256).
                  Token format: base64( IV(12 bytes) ‖ ciphertext ‖ GCM-tag(16 bytes) ). Random IV per encrypt.
                  GCM's auth tag gives tamper-detection: decrypt of a wrong-key or altered token throws AEADBadTag.

wikantik-api    CredentialStore (interface, com.wikantik.api.connectors) — the contract:
                  boolean enabled();  void put(String connectorId, String name, String secret);
                  Optional<String> get(String connectorId, String name);   // decrypted — for connectors only
                  List<String> list(String connectorId);                   // NAMES only
                  void delete(String connectorId, String name);

wikantik-connectors  V047 migration + JdbcCredentialStore:
                  connector_credentials( connector_id TEXT, credential_name TEXT, ciphertext TEXT,
                    created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, PRIMARY KEY(connector_id, credential_name) )
                  JdbcCredentialStore(DataSource ds, AesGcmCipher cipher)  — cipher null ⇒ enabled()=false;
                    put/get then refuse (LOG.warn, no secret logged). put upserts encrypt(secret);
                    get decrypts (decrypt failure ⇒ Optional.empty() + LOG.warn, NEVER plaintext);
                    list returns names; delete removes the row.

wikantik-rest   ConnectorCredentialsResource at /admin/connector-credentials/*  (distinct path — no
                  collision with /admin/connectors/*; AdminAuthFilter already covers /admin/*):
                    POST   /admin/connector-credentials/{id}/{name}   body = the secret → store encrypted → 201
                    GET    /admin/connector-credentials/{id}          → JSON array of credential NAMES (never values)
                    DELETE /admin/connector-credentials/{id}/{name}   → 204
                  Store disabled (no key) → 503. Resolves CredentialStore via the engine (same
                  getEngine() instanceof WikiEngine cast used by ConnectorAdminResource).

wikantik-main   ConnectorWiringHelper (existing com.wikantik.derived): build the cipher from
                  wikantik.connectors.crypto.key (base64) — absent/blank ⇒ null cipher; build
                  JdbcCredentialStore; engine.setManager(CredentialStore.class, store). Config key registered.
```

### Data flow

Operator `POST`s a secret once via the authed admin endpoint → `JdbcCredentialStore.put` → `AesGcmCipher.encrypt` → ciphertext row. A future authenticated connector calls `CredentialStore.get(id,name)` → decrypt → the plaintext secret, used to authenticate its source fetch. The secret never sits in config, never appears in a `GET` response (names only), never in a log line.

### Fail-closed / security posture

- **No master key** (`wikantik.connectors.crypto.key` absent/blank/not-32-bytes) ⇒ the store is **disabled**: `enabled()==false`, `put`/`get` refuse with `LOG.warn` (no secret in the message), and the admin resource returns **503**. This is the zero-config default — the feature is off until an operator supplies a key.
- **Decrypt failure** (wrong key after a key change, or a tampered/corrupt ciphertext — GCM auth-tag mismatch) ⇒ `get` returns `Optional.empty()` + `LOG.warn` (connector id + name only, never the ciphertext or any plaintext).
- **No secret material in logs, ever** — not the plaintext, not the ciphertext, not the key.
- The master key shares the trust boundary of the DB credentials it ultimately guards access to (both in `.env`/custom.properties on the single host) — consistent with the existing posture (DB password, SSO client secret, SCIM token all live there).

### PostgreSQL-first

`V047__connector_credentials.sql` — one idempotent table (`CREATE TABLE IF NOT EXISTS`, `:app_user` grants). No other schema. `ciphertext` is `TEXT` (base64 token). H2-testable (TEXT/TIMESTAMPTZ), like the sync-state tables.

## Testing (unit-first, no network)

| Test | Proves | Where |
|------|--------|-------|
| `AesGcmCipherTest` | encrypt→decrypt round-trips; two encrypts of the same plaintext differ (random IV); a tampered token / wrong key fails to decrypt (GCM tag); `keyFromBase64` rejects a non-32-byte key | wikantik-util |
| `JdbcCredentialStoreTest` (H2) | put→get round-trips; the stored `ciphertext` column is NOT the plaintext; `list` returns names only; `delete` removes; **null cipher ⇒ `enabled()==false` and put/get refuse** | wikantik-connectors |
| `ConnectorCredentialsResourceTest` | POST stores (201) + a follow-up GET lists the name but NOT the value; DELETE (204); store-disabled → 503; unknown routes → 404; no secret in any response body except the write echo | wikantik-rest |
| `ConnectorWiringHelper` crypto-config test | a valid base64 key → cipher built + store enabled; absent key → store disabled | wikantik-main |
| migration idempotency | `V047` re-applies as a no-op | wikantik-connectors (H2 subset) |

No live IT — the P2.1 `ConnectorAdminIT` already exercises the Cargo/admin path; the credential store is fully unit-covered.

## Invariants respected

- **Fixed Phase-1 SPI + P2.1 runtime unchanged** — credential storage is orthogonal (no `SourceConnector`/orchestrator/runtime change). `CredentialStore` is a NEW contract, additive.
- **#6 `wikantik-main` grows no new package** — cipher in `wikantik-util`, store contract in `wikantik-api`, impl in `wikantik-connectors`, admin resource in `wikantik-rest`; `wikantik-main` only gains `ConnectorWiringHelper` growth (existing `com.wikantik.derived`). No new dependency (JDK crypto).
- **PostgreSQL-first** — one idempotent numbered migration.
- **Fail-closed** — disabled without a key; decrypt failures degrade to empty + log; no secret material logged.

## Open questions (resolve during planning, not blocking)

1. **`CredentialStore` reach from the admin resource** — mirror `ConnectorAdminResource`'s `getEngine() instanceof WikiEngine` → `getManager(CredentialStore.class)` (registered by `ConnectorWiringHelper` via `engine.setManager`). Confirm it doesn't trip the frozen `getManager` ArchUnit rule (wikantik-rest is not scanned by it — established by the P2.1 admin resource).
2. **POST body handling** — the secret is the raw request body (a short string); cap its length (e.g. 8 KB) and reject empty. Confirm the `RestServletBase` body-read helper.
3. **`AesGcmCipher` location** — `wikantik-util` beside `CryptoUtil` (reusable, general crypto). Confirm `wikantik-connectors` sees `wikantik-util` transitively (it does, via `wikantik-api`).
