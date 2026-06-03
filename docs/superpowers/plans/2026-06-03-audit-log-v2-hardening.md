# Audit Log v2 Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the `audit_log` locked grant in CI against a real non-superuser role, and decouple audit-subsystem init from Knowledge-Graph init so a KG failure can't silently disable auditing.

**Architecture:** Two independent hardening changes to existing code. Item 1 rewrites one IT method (`AuditLogIT.app_role_cannot_update_audit_log`) to create a dedicated `NOSUPERUSER` role with the V036 grants and assert writes are denied as that role. Item 2 moves `WikiEngine.initAuditSubsystem(...)` out of `initKnowledgeGraph`'s try block into its own top-level init step that resolves its own dependencies.

**Tech Stack:** Java 21, JUnit 5, JDBC/PostgreSQL (IT), `WikiEngine` init lifecycle, ArchUnit (freeze store).

**Spec:** `docs/superpowers/specs/2026-06-03-audit-log-v2-hardening-design.md`

**Build/verify note:** the single-shot `mvn clean install -Pintegration-tests -fae` cannot complete in this sandbox (session wall-limit kills it in `wikantik-main`). Use the two-halves fallback: `mvn clean install -T 1C -DskipITs`, then each IT module sequentially with `-pl … -am`. For these two changes the relevant IT module is `wikantik-it-test-rest` (contains `AuditLogIT` and exercises full engine startup).

---

## Task 1: Prove the locked grant against a non-superuser role (Item 1)

**Files:**
- Modify: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/AuditLogIT.java` — replace the body of the existing `@Order(8) app_role_cannot_update_audit_log()` method (currently at lines 503–552).

**Background — current behavior (the thing being fixed):** the existing method connects to the IT PostgreSQL as `it.db.user` (= `jspwiki`, a Docker `POSTGRES_USER` **superuser**), detects `usesuper = true`, and **skips** the immutability check — so `REVOKE UPDATE/DELETE` from V036 is never proven. The IT DB is reachable via JDBC at `jdbc:postgresql://localhost:${it.db.port}/${it.db.name}` using the `it.db.user`/`it.db.password` system properties (already set in the failsafe run).

- [ ] **Step 1: Replace the test method**

Replace the entire existing `app_role_cannot_update_audit_log()` method (lines 503–552, the `@Test @Order(8)` one) with the version below. It keeps the same `@Order(8)` slot (audit rows already exist by then) and the credentials guard, but now uses the superuser connection only to **set up** a dedicated `NOSUPERUSER` role carrying the exact V036 grants, then asserts write denial **as that role**.

```java
    /**
     * Step 8: Prove the V036 locked grant. The IT PostgreSQL superuser
     * (Docker {@code POSTGRES_USER=jspwiki}) bypasses privilege checks, so we
     * cannot demonstrate {@code REVOKE} against it directly. Instead we use the
     * superuser connection to create a dedicated {@code NOSUPERUSER} role with the
     * SAME grants V036 applies to the app role, then connect AS that role and
     * assert that {@code SELECT} works but {@code UPDATE}/{@code DELETE} on
     * {@code audit_log} are denied — exactly as production (non-superuser app
     * role) enforces it.
     */
    @Test
    @Order( 8 )
    void non_superuser_role_cannot_mutate_audit_log() throws Exception {
        final String suUser   = System.getProperty( "it.db.user" );
        final String suPass   = System.getProperty( "it.db.password" );
        final String port     = System.getProperty( "it.db.port", "55432" );
        final String dbName   = System.getProperty( "it.db.name", "wikantik" );

        if ( suUser == null || suUser.isBlank() || suPass == null || suPass.isBlank() ) {
            fail( "it.db.user / it.db.password system properties not set — cannot run "
                    + "the audit_log immutability proof (they are set by the IT failsafe run)" );
        }

        final String jdbcUrl = "jdbc:postgresql://localhost:" + port + "/" + dbName;
        final String roRole = "audit_ro";
        final String roPass = "audit_ro";

        // 1. Setup as superuser: create the NOSUPERUSER role + V036 grants (idempotent).
        try ( Connection su = DriverManager.getConnection( jdbcUrl, suUser, suPass );
              Statement st = su.createStatement() ) {
            st.execute( "DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='" + roRole + "') THEN "
                    + "CREATE ROLE " + roRole + " LOGIN PASSWORD '" + roPass + "' NOSUPERUSER; "
                    + "END IF; END $$;" );
            st.execute( "GRANT CONNECT ON DATABASE \"" + dbName + "\" TO " + roRole );
            st.execute( "GRANT USAGE ON SCHEMA public TO " + roRole );
            // Exactly the V036 grant statements:
            st.execute( "GRANT  SELECT, INSERT ON audit_log TO " + roRole );
            st.execute( "REVOKE UPDATE, DELETE ON audit_log FROM " + roRole );
        }

        // 2. Assert as the NOSUPERUSER role.
        try ( Connection ro = DriverManager.getConnection( jdbcUrl, roRole, roPass ) ) {
            // SELECT works (proves the role can read — denial below is write-specific).
            try ( Statement s = ro.createStatement();
                  ResultSet rs = s.executeQuery( "SELECT count(*) FROM audit_log" ) ) {
                assertTrue( rs.next(), "SELECT count(*) should return a row" );
            }
            // UPDATE denied.
            final SQLException up = assertThrows( SQLException.class, () -> {
                try ( Statement s = ro.createStatement() ) {
                    s.execute( "UPDATE audit_log SET event_type='x' WHERE seq < 0" );
                }
            }, "UPDATE on audit_log must be denied for the NOSUPERUSER role" );
            assertPermissionDenied( up );
            // DELETE denied.
            final SQLException del = assertThrows( SQLException.class, () -> {
                try ( Statement s = ro.createStatement() ) {
                    s.execute( "DELETE FROM audit_log WHERE seq < 0" );
                }
            }, "DELETE on audit_log must be denied for the NOSUPERUSER role" );
            assertPermissionDenied( del );
            System.out.println( "[AuditLogIT] DB-immutability check PASSED: SELECT allowed, "
                    + "UPDATE + DELETE denied for NOSUPERUSER role '" + roRole + "'" );
        } finally {
            // 3. Best-effort teardown so a persistent DB stays clean across re-runs.
            try ( Connection su = DriverManager.getConnection( jdbcUrl, suUser, suPass );
                  Statement st = su.createStatement() ) {
                st.execute( "DROP OWNED BY " + roRole );
                st.execute( "DROP ROLE IF EXISTS " + roRole );
            } catch ( final SQLException e ) {
                System.out.println( "[AuditLogIT] teardown of role '" + roRole
                        + "' failed (non-fatal): " + e.getMessage() );
            }
        }
    }

    /** Asserts a SQLException is a PostgreSQL insufficient-privilege error. */
    private static void assertPermissionDenied( final SQLException e ) {
        final String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        // SQLState 42501 = insufficient_privilege.
        assertTrue( msg.contains( "permission denied" ) || msg.contains( "42501" )
                        || "42501".equals( e.getSQLState() ),
                "Expected an insufficient-privilege SQL error, got: " + e.getMessage()
                        + " (SQLState=" + e.getSQLState() + ")" );
    }
```

Notes for the implementer:
- The method is renamed `non_superuser_role_cannot_mutate_audit_log`; keep the `@Order( 8 )`.
- Imports `DriverManager`, `Connection`, `Statement`, `ResultSet`, `SQLException` already exist at the top of the file. `assertThrows`, `assertTrue`, `fail` are JUnit 5 static imports already present (the file uses `fail`/`assertTrue` today). If `assertThrows` is not yet statically imported, add `import static org.junit.jupiter.api.Assertions.assertThrows;`.
- Do **not** change any other test method.

- [ ] **Step 2: Compile the IT module's test sources**

Run: `mvn -q -pl wikantik-it-tests/wikantik-it-test-rest test-compile -am -DskipTests`
Expected: BUILD SUCCESS (test compiles; `assertThrows` import resolved).

- [ ] **Step 3: Run the IT and confirm the proof executes (not skips)**

Run: `mvn clean install -Pintegration-tests -fae -pl wikantik-it-tests/wikantik-it-test-rest -am`
Expected: BUILD SUCCESS; `AuditLogIT` passes; the failsafe stdout contains
`DB-immutability check PASSED: SELECT allowed, UPDATE + DELETE denied for NOSUPERUSER role 'audit_ro'`
and no longer contains a `SKIPPING DB-immutability check` line.

- [ ] **Step 4: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/AuditLogIT.java
git commit -m "test(audit): prove locked grant against a NOSUPERUSER role (close CI gap)"
```

---

## Task 2: Decouple audit init from Knowledge-Graph init (Item 2)

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java`
  - Remove the `initAuditSubsystem( props, ds, structuralIndex, pageManager );` call (and its 2-line comment) currently at ~line 1591–1593, inside `initKnowledgeGraph`'s try block.
  - Change `initAuditSubsystem`'s signature to `initAuditSubsystem( final Properties props )` and resolve `ds` / `structuralIndex` / `pageManager` internally.
  - Add an `initAuditSubsystem( props )` call in `initialize()` immediately AFTER the existing `initKnowledgeGraph( props );` call (~line 760), wrapped in its own `try/catch`.
- Modify (regenerate, do not hand-edit): the `DecompositionArchTest` ArchUnit freeze store entry for `WikiEngine` (line numbers of the moved `getManager(...)` calls shift).

**Background:** `initAuditSubsystem` was added by the audit feature as the last statement inside `initKnowledgeGraph(props)`'s try block (so a KG RuntimeException skips it). `initKnowledgeGraph` catches its own `NamingException | RuntimeException` and returns normally, so a call placed *after* `initKnowledgeGraph(props)` in `initialize()` runs regardless of KG outcome — which is the decoupling we want. By that point the auth managers, `PageManager`, and (on the happy path) the structural index are built. The v1 method already resolves `PageManager` via `getManager(...)` and retains the listener in a field (the `WeakHashMap`-GC fix) — both behaviors MUST be preserved.

- [ ] **Step 1: Read the current method and call site**

Read `WikiEngine.java` around lines 1585–1700 (the `initAuditSubsystem` call site + method body) and around lines 755–765 (the `initKnowledgeGraph( props );` call in `initialize()`). Confirm exact line numbers before editing (they may have shifted).

- [ ] **Step 2: Remove the in-KG call**

Delete these lines from inside `initKnowledgeGraph` (the comment + call, ~1591–1593):

```java
            // Audit subsystem — built here because the JNDI DataSource, the
            // structural index, and the PageManager are all in scope.
            initAuditSubsystem( props, ds, structuralIndex, pageManager );
```

- [ ] **Step 3: Change the method signature + resolve dependencies internally**

Change the method declaration from
`private void initAuditSubsystem( final Properties props, final javax.sql.DataSource ds, final com.wikantik.api.pagegraph.StructuralIndexService structuralIndex, final PageManager pageManager )`
to `private void initAuditSubsystem( final Properties props )`, and at the TOP of the method body resolve the three former parameters as locals (everything below them in the method — the `ds == null` guard, the lambdas, the listener registration, the field assignments — stays unchanged and now references these locals):

```java
    private void initAuditSubsystem( final Properties props ) {
        // Resolve the DataSource independently of Knowledge-Graph init (same JNDI
        // lookup initKnowledgeGraph uses). Null when no datasource is configured.
        javax.sql.DataSource ds = null;
        try {
            final String datasource = props.getProperty(
                    AbstractJDBCDatabase.PROP_DATASOURCE, AbstractJDBCDatabase.DEFAULT_DATASOURCE );
            final javax.naming.Context initCtx = new javax.naming.InitialContext();
            final javax.naming.Context ctx = ( javax.naming.Context ) initCtx.lookup( "java:comp/env" );
            ds = ( javax.sql.DataSource ) ctx.lookup( datasource );
        } catch ( final javax.naming.NamingException e ) {
            LOG.warn( "Audit subsystem: no JNDI DataSource resolved ({}); audit log disabled.",
                    e.getMessage() );
        }
        // Resolve the page manager + structural index from the registry (may be null
        // if their subsystems failed to build — read-gating degrades, audit still runs).
        final PageManager pageManager = getManager( PageManager.class );
        final com.wikantik.api.pagegraph.StructuralIndexService structuralIndex =
                getManager( com.wikantik.api.pagegraph.StructuralIndexService.class );

        // --- everything below here is the EXISTING method body, unchanged ---
        if ( ds == null ) {
            LOG.warn( "Audit subsystem disabled — no JNDI DataSource resolved; "
                    + "security and page events will not be written to the audit log." );
            return;
        }
        // ... (existing AuditSubsystemFactory.build, frontmatterByPage/clusterByPage
        //      lambdas, auditReadPolicy, listener creation + registration against the
        //      5 managers, field assignments, LOG.info) stays as-is ...
    }
```

IMPORTANT: keep the existing body verbatim from the `if ( ds == null )` guard onward — including the line that stores the `AuditEventListener` in a `WikiEngine` field (the `WeakHashMap`-GC fix) and the `auditService`/`auditWriter`/`auditReadPolicy` field assignments. Only the parameter list and the new dependency-resolution prologue change.

- [ ] **Step 4: Add the decoupled call in `initialize()`**

Immediately after the existing `initKnowledgeGraph( props );` line in `initialize()`, add:

```java
        // Audit subsystem is initialized independently of Knowledge-Graph init so a
        // KG failure cannot silently disable auditing. initKnowledgeGraph swallows
        // its own exceptions, so this runs on both the happy and the KG-failed path.
        try {
            initAuditSubsystem( props );
        } catch ( final RuntimeException e ) {
            // Never break engine startup because of auditing; never swallow silently.
            LOG.warn( "Audit subsystem initialization failed; continuing without audit.", e );
        }
```

- [ ] **Step 5: Compile-check**

Run: `mvn -q -pl wikantik-main compile`
Expected: BUILD SUCCESS. (If `PageManager` is not already imported in `WikiEngine`, it is referenced elsewhere via `com.wikantik.api.managers.PageManager` — use the same reference the v1 code used; do not add a duplicate import if one exists.)

- [ ] **Step 6: Update the ArchUnit freeze store + run the test**

The moved `getManager(...)` calls change line numbers, so `DecompositionArchTest`'s freeze store has stale entries. Run the test; if it fails only due to line-number/method-location shifts for `WikiEngine.initAuditSubsystem`/`initialize` `getManager` calls, let the freeze store re-record (ArchUnit's freezing store auto-updates on run), then run again to confirm green.

Run: `mvn -q -pl wikantik-main test -Dtest=DecompositionArchTest`
Expected: PASS (3 tests). If the first run mutated the store, run it once more and confirm green. Do NOT leave the store in a half-pruned state — if anything looks off, `git checkout` the store file under `wikantik-main/src/test/resources/` (or wherever `DecompositionArchTest` stores it) and re-run cleanly. (See memory: ArchUnit freeze store self-prunes on failing runs.)

- [ ] **Step 7: Run the audit unit tests + the rest IT to confirm no regression**

Run: `mvn -q -pl wikantik-main test -Dtest='AuditEntryTest,AuditChainHasherTest,InMemoryAuditRepositoryTest,DefaultAuditServiceTest,AuditEventListenerTest,AuditEventListenerPageTest,AuditReadPolicyTest,DecompositionArchTest'`
Expected: all PASS.

Then: `mvn clean install -Pintegration-tests -fae -pl wikantik-it-tests/wikantik-it-test-rest -am`
Expected: BUILD SUCCESS; `AuditLogIT` passes (proves the listener still fires and `verify` still returns ok after the move — i.e. audit init still works from its new call site).

- [ ] **Step 8: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/WikiEngine.java
# include the ArchUnit store file if it was regenerated:
git add wikantik-main/src/test/resources/  # only the DecompositionArchTest store file actually changed
git commit -m "feat(audit): decouple audit-subsystem init from Knowledge-Graph init"
```

---

## Task 3: Update the design doc open items

**Files:**
- Modify: `docs/wikantik-pages/AuditLogDesign.md` — in the "Open items / v2" section, mark the two now-resolved items as done.

- [ ] **Step 1: Edit the open-items list**

In `AuditLogDesign.md`'s "Open items / v2" section, update the two addressed bullets to reflect they are now resolved:
- The "CI does not yet prove the locked grant" bullet → note it is now proven via a dedicated `NOSUPERUSER` role in `AuditLogIT` (v2).
- The "Audit init is nested in `initKnowledgeGraph`" bullet → note audit init is now its own top-level engine step (v2).
Leave the remaining open items (retention purge, `ensurePartition` CREATE privilege, durable staging, SIEM forwarding) unchanged.

- [ ] **Step 2: Commit**

```bash
git add docs/wikantik-pages/AuditLogDesign.md
git commit -m "docs(audit): mark non-superuser grant proof + decoupled init as resolved (v2)"
```

---

## Final verification

- [ ] **Full gate (two-halves fallback):**
  - `mvn clean install -T 1C -DskipITs` → BUILD SUCCESS (all unit modules).
  - `mvn clean install -Pintegration-tests -fae -pl wikantik-it-tests/wikantik-it-test-rest -am` → BUILD SUCCESS, `AuditLogIT` green with the immutability proof executing.
  - (The sso / sso-saml / custom-jdbc IT modules are unaffected by these changes — Item 2 only moves the audit-init call site within engine startup, which every module already exercises and the rest IT covers. Re-run them only if time permits.)

---

## Self-review

- **Spec coverage:** Item 1 (non-superuser grant proof) → Task 1; Item 2 (decouple audit init) → Task 2; the design's "mark resolved" housekeeping → Task 3. Both spec items have a task; out-of-scope items (retention, durable staging, SIEM) are correctly absent.
- **Placeholder scan:** Task 1 contains the full replacement method; Task 2 shows the exact prologue + call-site code and explicitly says the rest of the method body is unchanged (not a "similar to" hand-wave — the changed parts are shown in full, the unchanged parts are identified by their existing guard `if ( ds == null )`). No TBD/TODO.
- **Type/name consistency:** method renamed to `non_superuser_role_cannot_mutate_audit_log` (Task 1) used consistently; `initAuditSubsystem(Properties)` signature used consistently between Step 2/3/4; `getManager(PageManager.class)` / `getManager(StructuralIndexService.class)` match the v1 wiring's accessors.
