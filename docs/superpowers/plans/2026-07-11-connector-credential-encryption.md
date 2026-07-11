# Connector Credential Encryption Implementation Plan (P2.2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reversible encryption-at-rest for connector secrets — an `AesGcmCipher` primitive, a `connector_credentials` table + `JdbcCredentialStore`, and an admin endpoint to inject secrets — so a future authenticated connector can store/retrieve credentials that never sit in config or logs. Storage + admin only.

**Architecture:** `AesGcmCipher` (AES-256-GCM, JDK, no new dep) in `wikantik-util`; `CredentialStore` contract in `wikantik-api`; `JdbcCredentialStore` + migration `V047` in `wikantik-connectors`; `ConnectorCredentialsResource` at `/admin/connector-credentials/*` in `wikantik-rest`; thin wiring in `wikantik-main`'s existing `com.wikantik.derived`. Fail-closed: no master key ⇒ disabled.

**Tech Stack:** Java 25, Maven, JUnit 5, JDK `javax.crypto` (AES/GCM), H2 (DAO test).

**Spec:** `docs/superpowers/specs/2026-07-11-connector-credential-encryption-design.md`

## Global Constraints

- **Fixed Phase-1 SPI + P2.1 runtime unchanged** — credential storage is orthogonal; no `SourceConnector`/orchestrator/`ConnectorRuntime` change. `CredentialStore` is a NEW additive contract.
- **Invariant #6:** cipher in `wikantik-util`, contract in `wikantik-api`, impl in `wikantik-connectors`, resource in `wikantik-rest`; `wikantik-main` only gains `ConnectorWiringHelper` growth. No new dependency.
- **Fail-closed / secret hygiene:** no master key ⇒ store `enabled()==false` (put/get refuse + `LOG.warn`), admin → 503. Decrypt failure ⇒ `Optional.empty()` + `LOG.warn`. **NEVER** log a secret, ciphertext, or the key. No empty catch.
- **PostgreSQL-first:** one idempotent numbered migration `V047__connector_credentials.sql`.
- **TDD:** failing test first; run only the task's targeted test.

---

### Task 1: `AesGcmCipher` (wikantik-util)

**Files:**
- Create: `wikantik-util/src/main/java/com/wikantik/util/AesGcmCipher.java`
- Test: `wikantik-util/src/test/java/com/wikantik/util/AesGcmCipherTest.java`

**Interfaces:**
- Produces: `AesGcmCipher(SecretKey key)`; `String encrypt(String plaintext)` → base64(IV‖ciphertext‖tag); `String decrypt(String token) throws GeneralSecurityException` (throws on tamper/wrong-key — GCM tag); `static SecretKey keyFromBase64(String b64)` (validates 32 bytes → AES-256, else `IllegalArgumentException`).

- [ ] **Step 1: Write the failing test**
```java
package com.wikantik.util;

import org.junit.jupiter.api.Test;
import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class AesGcmCipherTest {
    private static SecretKey randomKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes( k );
        return AesGcmCipher.keyFromBase64( Base64.getEncoder().encodeToString( k ) );
    }

    @Test void roundTrips() throws Exception {
        AesGcmCipher c = new AesGcmCipher( randomKey() );
        String secret = "ghp_ExampleGitHubToken_1234567890";
        assertEquals( secret, c.decrypt( c.encrypt( secret ) ) );
    }

    @Test void ivIsRandomSoCiphertextsDiffer() {
        AesGcmCipher c = new AesGcmCipher( randomKey() );
        assertNotEquals( c.encrypt( "same" ), c.encrypt( "same" ) );   // random IV per encrypt
    }

    @Test void tamperedTokenFailsToDecrypt() {
        AesGcmCipher c = new AesGcmCipher( randomKey() );
        String token = c.encrypt( "secret" );
        byte[] raw = Base64.getDecoder().decode( token );
        raw[ raw.length - 1 ] ^= 0x01;                                 // flip a bit in the tag/ciphertext
        String tampered = Base64.getEncoder().encodeToString( raw );
        assertThrows( java.security.GeneralSecurityException.class, () -> c.decrypt( tampered ) );
    }

    @Test void wrongKeyFailsToDecrypt() {
        String token = new AesGcmCipher( randomKey() ).encrypt( "secret" );
        assertThrows( java.security.GeneralSecurityException.class, () -> new AesGcmCipher( randomKey() ).decrypt( token ) );
    }

    @Test void keyFromBase64RejectsWrongLength() {
        assertThrows( IllegalArgumentException.class,
            () -> AesGcmCipher.keyFromBase64( Base64.getEncoder().encodeToString( new byte[16] ) ) );  // 16 ≠ 32
    }
}
```

- [ ] **Step 2: Run — FAIL** (`mvn test -pl wikantik-util -Dtest=AesGcmCipherTest -q`).

- [ ] **Step 3: Implement** (Apache header):
```java
package com.wikantik.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Reversible authenticated encryption for small secrets (connector credentials), using AES-256-GCM
 * from the JDK. Token = base64( IV(12 bytes) ‖ ciphertext ‖ GCM tag(16 bytes) ), a fresh random IV
 * per {@link #encrypt}. GCM's auth tag means {@link #decrypt} fails (throws) on any tamper or wrong key.
 * Sibling to the hash-only {@link CryptoUtil}. Never logs or exposes key/plaintext.
 */
public final class AesGcmCipher {

    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int AES_256_KEY_BYTES = 32;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmCipher( final SecretKey key ) {
        this.key = key;
    }

    /** @return base64(IV‖ciphertext‖tag). */
    public String encrypt( final String plaintext ) {
        try {
            final byte[] iv = new byte[ IV_BYTES ];
            random.nextBytes( iv );
            final Cipher cipher = Cipher.getInstance( TRANSFORM );
            cipher.init( Cipher.ENCRYPT_MODE, key, new GCMParameterSpec( TAG_BITS, iv ) );
            final byte[] ct = cipher.doFinal( plaintext.getBytes( StandardCharsets.UTF_8 ) );  // includes tag
            final byte[] out = new byte[ iv.length + ct.length ];
            System.arraycopy( iv, 0, out, 0, iv.length );
            System.arraycopy( ct, 0, out, iv.length, ct.length );
            return Base64.getEncoder().encodeToString( out );
        } catch ( final GeneralSecurityException e ) {
            // an encrypt failure is a configuration/JVM bug, not attacker input — never happens with a valid key
            throw new IllegalStateException( "AES-GCM encrypt failed", e );
        }
    }

    /** @throws GeneralSecurityException on a tampered token or wrong key (GCM auth-tag mismatch). */
    public String decrypt( final String token ) throws GeneralSecurityException {
        final byte[] all = Base64.getDecoder().decode( token );
        if ( all.length <= IV_BYTES ) {
            throw new GeneralSecurityException( "credential token too short" );
        }
        final byte[] iv = Arrays.copyOfRange( all, 0, IV_BYTES );
        final byte[] ct = Arrays.copyOfRange( all, IV_BYTES, all.length );
        final Cipher cipher = Cipher.getInstance( TRANSFORM );
        cipher.init( Cipher.DECRYPT_MODE, key, new GCMParameterSpec( TAG_BITS, iv ) );
        return new String( cipher.doFinal( ct ), StandardCharsets.UTF_8 );   // doFinal throws on tag mismatch
    }

    /** Build an AES-256 key from a base64-encoded 32-byte value. */
    public static SecretKey keyFromBase64( final String base64 ) {
        final byte[] k = Base64.getDecoder().decode( base64 );
        if ( k.length != AES_256_KEY_BYTES ) {
            throw new IllegalArgumentException( "AES-256 key must be 32 bytes; got " + k.length );
        }
        return new SecretKeySpec( k, "AES" );
    }
}
```

- [ ] **Step 4: Run — PASS** (`mvn test -pl wikantik-util -Dtest=AesGcmCipherTest -q`).

- [ ] **Step 5: Commit**
```bash
git add wikantik-util/src/main/java/com/wikantik/util/AesGcmCipher.java wikantik-util/src/test/java/com/wikantik/util/AesGcmCipherTest.java
git commit -m "feat(util): AesGcmCipher — AES-256-GCM reversible encryption for secrets"
```

---

### Task 2: `CredentialStore` contract + `V047` migration + `JdbcCredentialStore`

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/connectors/CredentialStore.java`
- Create: `bin/db/migrations/V047__connector_credentials.sql`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/credential/JdbcCredentialStore.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/credential/JdbcCredentialStoreTest.java`

**Interfaces:**
- Consumes: `AesGcmCipher` (T1, via `wikantik-util`, on `wikantik-connectors`'s classpath transitively); `javax.sql.DataSource`.
- Produces:
  - `CredentialStore` (wikantik-api): `boolean enabled()`; `void put(String connectorId, String name, String secret)`; `Optional<String> get(String connectorId, String name)`; `List<String> list(String connectorId)`; `void delete(String connectorId, String name)`.
  - `JdbcCredentialStore(DataSource ds, AesGcmCipher cipher)` — `cipher == null` ⇒ `enabled()==false` and `put`/`get` refuse (`LOG.warn`, no secret in message). `put` upserts `encrypt(secret)`; `get` decrypts (failure ⇒ `Optional.empty()` + `LOG.warn`); `list` returns names; `delete` removes.

- [ ] **Step 1: Create the contract** `CredentialStore.java` (Apache header):
```java
package com.wikantik.api.connectors;
import java.util.List;
import java.util.Optional;
/** Encrypted-at-rest storage for connector secrets. Disabled (enabled()==false) when no master key is set. */
public interface CredentialStore {
    boolean enabled();
    void put( String connectorId, String name, String secret );
    Optional< String > get( String connectorId, String name );   // decrypted — for connectors, never admin GET
    List< String > list( String connectorId );                   // names only
    void delete( String connectorId, String name );
}
```

- [ ] **Step 2: Write the migration** `bin/db/migrations/V047__connector_credentials.sql` (idempotent, `:app_user` grants):
```sql
-- Encrypted connector credentials (CredentialEncryption P2.2, 2026-07-11).
-- ciphertext is a base64 AES-256-GCM token (iv‖ct‖tag); the master key lives in config, never here.
CREATE TABLE IF NOT EXISTS connector_credentials (
    connector_id    TEXT NOT NULL,
    credential_name TEXT NOT NULL,
    ciphertext      TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (connector_id, credential_name)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON connector_credentials TO :app_user;
```

- [ ] **Step 3: Write the failing test** (H2, mirrors `JdbcSyncStateStoreTest`; creates the table itself):
```java
package com.wikantik.connectors.credential;

import com.wikantik.util.AesGcmCipher;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.crypto.SecretKey;
import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.Connection;
import java.util.Base64;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JdbcCredentialStoreTest {

    private DataSource ds;
    private AesGcmCipher cipher;

    @BeforeEach void schema() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:creds;DB_CLOSE_DELAY=-1;MODE=PostgreSQL" );
        ds = h2;
        try ( Connection c = ds.getConnection(); var s = c.createStatement() ) {
            s.execute( "DELETE FROM connector_credentials WHERE 1=1" );  // idempotent across methods
        } catch ( final Exception ignore ) { /* table may not exist yet */ }
        try ( Connection c = ds.getConnection(); var s = c.createStatement() ) {
            s.execute( "CREATE TABLE IF NOT EXISTS connector_credentials (connector_id VARCHAR NOT NULL, credential_name VARCHAR NOT NULL, ciphertext VARCHAR NOT NULL, created_at TIMESTAMP WITH TIME ZONE DEFAULT now(), updated_at TIMESTAMP WITH TIME ZONE DEFAULT now(), PRIMARY KEY (connector_id, credential_name))" );
            s.execute( "DELETE FROM connector_credentials" );
        }
        byte[] k = new byte[32]; new SecureRandom().nextBytes( k );
        cipher = new AesGcmCipher( AesGcmCipher.keyFromBase64( Base64.getEncoder().encodeToString( k ) ) );
    }

    @Test void putGetRoundTripsAndStoresCiphertextNotPlaintext() throws Exception {
        JdbcCredentialStore store = new JdbcCredentialStore( ds, cipher );
        assertTrue( store.enabled() );
        store.put( "gh1", "token", "ghp_secret_value" );
        assertEquals( "ghp_secret_value", store.get( "gh1", "token" ).orElseThrow() );
        try ( Connection c = ds.getConnection();
              var rs = c.createStatement().executeQuery( "SELECT ciphertext FROM connector_credentials WHERE connector_id='gh1'" ) ) {
            rs.next();
            assertNotEquals( "ghp_secret_value", rs.getString( 1 ), "must store ciphertext, not plaintext" );
        }
    }

    @Test void listReturnsNamesAndDeleteRemoves() {
        JdbcCredentialStore store = new JdbcCredentialStore( ds, cipher );
        store.put( "gh1", "token", "a" );
        store.put( "gh1", "webhook", "b" );
        assertEquals( List.of( "token", "webhook" ), store.list( "gh1" ).stream().sorted().toList() );
        store.delete( "gh1", "token" );
        assertEquals( List.of( "webhook" ), store.list( "gh1" ) );
    }

    @Test void disabledWhenNoCipher() {
        JdbcCredentialStore store = new JdbcCredentialStore( ds, null );
        assertFalse( store.enabled() );
        store.put( "gh1", "token", "x" );                 // refuses (no throw)
        assertTrue( store.get( "gh1", "token" ).isEmpty() );
        assertTrue( store.list( "gh1" ).isEmpty() );
    }
}
```

- [ ] **Step 4: Run — FAIL** (`mvn test -pl wikantik-connectors -Dtest=JdbcCredentialStoreTest -q`).

- [ ] **Step 5: Implement `JdbcCredentialStore`** (Apache header; JDBC idiom from `JdbcSyncStateStore` — `try(Connection)`, `LOG.warn` on `SQLException`, UPDATE-then-INSERT upsert since H2-PG-mode rejects `ON CONFLICT DO UPDATE` per the earlier connector work):
```java
package com.wikantik.connectors.credential;

import com.wikantik.api.connectors.CredentialStore;
import com.wikantik.util.AesGcmCipher;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** PostgreSQL/H2 {@link CredentialStore}; secrets stored as AES-GCM tokens. Disabled when cipher is null. */
public final class JdbcCredentialStore implements CredentialStore {

    private static final Logger LOG = LogManager.getLogger( JdbcCredentialStore.class );
    private final DataSource ds;
    private final AesGcmCipher cipher;   // null ⇒ disabled

    public JdbcCredentialStore( final DataSource ds, final AesGcmCipher cipher ) {
        this.ds = ds;
        this.cipher = cipher;
    }

    @Override public boolean enabled() { return cipher != null; }

    @Override
    public void put( final String connectorId, final String name, final String secret ) {
        if ( cipher == null ) { LOG.warn( "credential put refused for {}/{}: no master key configured", connectorId, name ); return; }
        final String token = cipher.encrypt( secret );
        try ( Connection c = ds.getConnection() ) {
            try ( PreparedStatement up = c.prepareStatement(
                    "UPDATE connector_credentials SET ciphertext=?, updated_at=now() WHERE connector_id=? AND credential_name=?" ) ) {
                up.setString( 1, token ); up.setString( 2, connectorId ); up.setString( 3, name );
                if ( up.executeUpdate() == 0 ) {
                    try ( PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO connector_credentials (connector_id, credential_name, ciphertext) VALUES (?,?,?)" ) ) {
                        ins.setString( 1, connectorId ); ins.setString( 2, name ); ins.setString( 3, token );
                        ins.executeUpdate();
                    }
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "credential put failed for {}/{}: {}", connectorId, name, e.getMessage() );  // no secret
        }
    }

    @Override
    public Optional< String > get( final String connectorId, final String name ) {
        if ( cipher == null ) { LOG.warn( "credential get refused for {}/{}: no master key configured", connectorId, name ); return Optional.empty(); }
        String token = null;
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "SELECT ciphertext FROM connector_credentials WHERE connector_id=? AND credential_name=?" ) ) {
            ps.setString( 1, connectorId ); ps.setString( 2, name );
            try ( ResultSet rs = ps.executeQuery() ) { if ( rs.next() ) token = rs.getString( 1 ); }
        } catch ( final SQLException e ) {
            LOG.warn( "credential get failed for {}/{}: {}", connectorId, name, e.getMessage() );
            return Optional.empty();
        }
        if ( token == null ) return Optional.empty();
        try {
            return Optional.of( cipher.decrypt( token ) );
        } catch ( final Exception e ) {   // GCM tag mismatch / wrong key / corrupt token
            LOG.warn( "credential decrypt failed for {}/{}: {}", connectorId, name, e.getMessage() );  // no plaintext/ciphertext
            return Optional.empty();
        }
    }

    @Override
    public List< String > list( final String connectorId ) {
        final List< String > out = new ArrayList<>();
        if ( cipher == null ) return out;
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "SELECT credential_name FROM connector_credentials WHERE connector_id=?" ) ) {
            ps.setString( 1, connectorId );
            try ( ResultSet rs = ps.executeQuery() ) { while ( rs.next() ) out.add( rs.getString( 1 ) ); }
        } catch ( final SQLException e ) {
            LOG.warn( "credential list failed for {}: {}", connectorId, e.getMessage() );
        }
        return out;
    }

    @Override
    public void delete( final String connectorId, final String name ) {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "DELETE FROM connector_credentials WHERE connector_id=? AND credential_name=?" ) ) {
            ps.setString( 1, connectorId ); ps.setString( 2, name );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "credential delete failed for {}/{}: {}", connectorId, name, e.getMessage() );
        }
    }
}
```

- [ ] **Step 6: Run — PASS** (`mvn test -pl wikantik-connectors -Dtest=JdbcCredentialStoreTest -q`; install wikantik-api/util first if needed).

- [ ] **Step 7: Commit**
```bash
git add wikantik-api/src/main/java/com/wikantik/api/connectors/CredentialStore.java bin/db/migrations/V047__connector_credentials.sql wikantik-connectors/src/main/java/com/wikantik/connectors/credential/JdbcCredentialStore.java wikantik-connectors/src/test/java/com/wikantik/connectors/credential/JdbcCredentialStoreTest.java
git commit -m "feat(connectors): CredentialStore + V047 + JdbcCredentialStore (encrypted secrets, fail-closed)"
```

---

### Task 3: `ConnectorCredentialsResource` (admin) + web.xml

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/ConnectorCredentialsResource.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` (servlet + mapping `/admin/connector-credentials/*`)
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/ConnectorCredentialsResourceTest.java`

**Interfaces:**
- Consumes: `CredentialStore` (resolved via `getEngine() instanceof WikiEngine we ? we.getManager(CredentialStore.class) : null`, mirroring `ConnectorAdminResource.resolveRuntime`); extends `RestServletBase`.
- Routing on `getPathInfo()` after `/admin/connector-credentials`:
  - `GET  /{id}` → `store.list(id)` → JSON array of NAMES. (Store disabled → 503.)
  - `POST /{id}/{name}` → body = the secret (read raw via `request.getReader()`; reject blank / > 8 KB) → `store.put(id,name,secret)` → 201. Response echoes `{connectorId, name}` — NEVER the secret.
  - `DELETE /{id}/{name}` → `store.delete(id,name)` → 204.
  - store null/disabled → 503; malformed path → 404.

- [ ] **Step 1: Write the failing test** — model on `ConnectorAdminResourceTest` (mock request/response, inject a stub `CredentialStore` via a `protected resolveStore()` override). Cover: POST stores + returns 201 without the secret in the body; GET lists the name (asserts the VALUE is absent from the response); DELETE 204; disabled store → 503; unknown path → 404. (Read `ConnectorAdminResourceTest` for the exact mocking + a `protected` seam to override.)
```java
// Skeleton — implementer fills request/response mocking per ConnectorAdminResourceTest:
// - POST /admin/connector-credentials/gh1/token body="ghp_x" → 201, response body has "token" but NOT "ghp_x"
// - GET  /admin/connector-credentials/gh1 (store has {token}) → 200, array contains "token", no secret value
// - DELETE /admin/connector-credentials/gh1/token → 204
// - resolveStore() returns a disabled store (enabled()==false) → GET/POST → 503
// - POST with blank body → 400
```

- [ ] **Step 2: Run — FAIL**.

- [ ] **Step 3: Implement `ConnectorCredentialsResource`** (extends `RestServletBase`; `resolveStore()` = the WikiEngine cast; path parse; raw-body read via `request.getReader()` with a length cap + blank check; every failure path `sendError` with a code; a disabled/absent store → `sendError(503, ...)`; Apache header). Follow `ConnectorAdminResource`'s structure for `doGet`/`doPost`/`doDelete`, path parsing, and JSON. **Never** put the secret in a response or log line.

- [ ] **Step 4: Register the servlet** in `web.xml` (copy the `ConnectorAdminResource` `<servlet>` + `<servlet-mapping>` blocks; name/class `ConnectorCredentialsResource`, url-pattern `/admin/connector-credentials/*`).

- [ ] **Step 5: Run — PASS** (`mvn test -pl wikantik-rest -Dtest=ConnectorCredentialsResourceTest -q`).

- [ ] **Step 6: Commit**
```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/ConnectorCredentialsResource.java wikantik-war/src/main/webapp/WEB-INF/web.xml wikantik-rest/src/test/java/com/wikantik/rest/ConnectorCredentialsResourceTest.java
git commit -m "feat(connectors): /admin/connector-credentials admin resource (inject/list/delete secrets)"
```

---

### Task 4: Wire the store in `ConnectorWiringHelper` + config key

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/derived/ConnectorWiringHelper.java`
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties`
- Test: `wikantik-main/src/test/java/com/wikantik/derived/ConnectorWiringHelperTest.java` (extend)

**Interfaces:**
- Consumes: `AesGcmCipher` (util), `JdbcCredentialStore` (connectors), `CredentialStore` (api).
- Produces: a package-visible `static AesGcmCipher cipherFrom(Properties props)` — reads `wikantik.connectors.crypto.key` (base64); blank/absent → `null`; invalid (not 32 bytes / bad base64) → `null` + `LOG.warn` (no key material logged). In `wireConnectors` (or a dedicated wiring point that runs even when no connectors are configured — see note), build `new JdbcCredentialStore(ds, cipherFrom(props))` and `engine.setManager(CredentialStore.class, store)` so the admin resource can always resolve it (enabled or not).

> **Wiring note:** the credential store must be registered **independently of** `wikantik.connectors.enabled` and of whether any connector is configured — an operator sets credentials BEFORE wiring a connector. So register the `CredentialStore` at the top of the connector wiring path (or a sibling method invoked from `WikiEngine` startup regardless of the connectors-enabled flag). The store itself is fail-closed (disabled without a key), so always-registering it is safe. Confirm the exact call site in `WikiEngine` (near the existing `ConnectorWiringHelper.wireConnectors` call).

- [ ] **Step 1: Write the failing test** (extend `ConnectorWiringHelperTest`):
```java
    @Test void cipherFromValidKeyBuildsCipher() {
        Properties p = new Properties();
        byte[] k = new byte[32]; new java.security.SecureRandom().nextBytes( k );
        p.setProperty( "wikantik.connectors.crypto.key", java.util.Base64.getEncoder().encodeToString( k ) );
        assertNotNull( ConnectorWiringHelper.cipherFrom( p ) );
    }
    @Test void cipherFromAbsentOrInvalidKeyIsNull() {
        assertNull( ConnectorWiringHelper.cipherFrom( new Properties() ) );                       // absent
        Properties bad = new Properties();
        bad.setProperty( "wikantik.connectors.crypto.key", "not-base64!!" );
        assertNull( ConnectorWiringHelper.cipherFrom( bad ) );                                    // invalid → null (no throw)
        Properties short_ = new Properties();
        short_.setProperty( "wikantik.connectors.crypto.key", java.util.Base64.getEncoder().encodeToString( new byte[16] ) );
        assertNull( ConnectorWiringHelper.cipherFrom( short_ ) );                                 // 16 bytes → null
    }
```

- [ ] **Step 2: Run — FAIL** (`mvn test -pl wikantik-main -Dtest=ConnectorWiringHelperTest -q`).

- [ ] **Step 3: Implement** `cipherFrom(Properties)`:
```java
    static AesGcmCipher cipherFrom( final Properties props ) {
        final String b64 = props.getProperty( PREFIX + "crypto.key" );
        if ( b64 == null || b64.isBlank() ) return null;
        try {
            return new AesGcmCipher( AesGcmCipher.keyFromBase64( b64.trim() ) );
        } catch ( final RuntimeException e ) {
            LOG.warn( "wikantik.connectors.crypto.key is invalid (need base64 32-byte AES-256 key) — "
                + "credential storage disabled: {}", e.getMessage() );   // never log the key value
            return null;
        }
    }
```
Then register the store from the connector wiring path (regardless of connectors-enabled), and add `import`s. Register: `engine.setManager( CredentialStore.class, new JdbcCredentialStore( ds, cipherFrom( props ) ) );`.

- [ ] **Step 4: Register the config key** in `wikantik.properties`:
```properties
# Connector credential encryption (P2.2). Base64-encoded 32-byte AES-256 master key. Absent/blank ⇒
# credential storage DISABLED (admin /admin/connector-credentials returns 503). Generate:
#   openssl rand -base64 32
#wikantik.connectors.crypto.key =
```

- [ ] **Step 5: Run — PASS** (`mvn test -pl wikantik-main -Dtest=ConnectorWiringHelperTest -q`) + compile-check (`mvn -q -pl wikantik-main -am compile`).

- [ ] **Step 6: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/derived/ConnectorWiringHelper.java wikantik-main/src/main/resources/ini/wikantik.properties wikantik-main/src/test/java/com/wikantik/derived/ConnectorWiringHelperTest.java
git commit -m "feat(connectors): wire CredentialStore from wikantik.connectors.crypto.key (fail-closed)"
```

---

## Post-implementation (controller)

- Full reactor unit build: `mvn clean install -DskipITs` **with `WIKANTIK_*` env UNSET**.
- Per-module RAT clean on `wikantik-util`/`wikantik-connectors`/`wikantik-rest` (all new `.java` carry the ASF header).
- Apply `V047` against the local/IT Postgres + `bin/db/migrate.sh --status` to confirm idempotency.
- Whole-branch review (opus): **secret hygiene** (no plaintext/ciphertext/key in any log or response — grep the diff for it); fail-closed (no key ⇒ disabled ⇒ admin 503; decrypt failure ⇒ empty+log); GCM correctness (random IV per encrypt, tamper-detection); the store registered independently of connectors-enabled (so credentials can be set before a connector exists); invariant #6 (cipher in util, contract in api, impl in connectors, resource in rest, wiring-only in main); no Phase-1 SPI / P2.1 runtime change (`CredentialStore` is additive).

## Self-review notes

- **Spec coverage:** cipher (T1), contract+migration+store (T2), admin surface (T3), wiring+config (T4). All mapped. No live IT (per spec).
- **Fail-closed:** no key ⇒ `enabled()==false` (T2 disabled test), admin 503 (T3), decrypt failure ⇒ empty (store) — all tested.
- **Secret hygiene:** no `LOG.warn` includes a secret/ciphertext/key; admin GET returns names only; POST echoes id+name not the secret.
- **Invariant #6 / no new dep:** cipher→util, contract→api, impl→connectors, resource→rest, wiring→main's existing package; JDK crypto only.
- **Type consistency:** `AesGcmCipher(SecretKey)`/`encrypt`/`decrypt`/`keyFromBase64`, `CredentialStore` 5 methods, `JdbcCredentialStore(DataSource,AesGcmCipher)`, `cipherFrom(Properties)` — identical across T1→T4.
