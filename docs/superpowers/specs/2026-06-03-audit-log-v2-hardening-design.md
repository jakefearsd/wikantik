# Audit Log v2 — Hardening Design

**Status:** approved 2026-06-03
**Predecessor:** [AuditLogDesign](../../wikantik-pages/AuditLogDesign.md) (v1, shipped)
**Scope:** two hardening follow-ups from the v1 design's "Open items" — no new
feature, no schema change, no migration.

## Goal

Close the one compliance gap the v1 IT could not prove, and make audit
initialization independent of Knowledge-Graph init. Both raise the robustness of
what already shipped; neither adds user-facing surface.

Explicitly **out of scope** (deferred): retention purge / partition-management
job, durable staging for zero-loss, outbound WORM/SIEM forwarding.

---

## Item 1 — Non-superuser grant proof

### Problem

V036 makes `audit_log` `INSERT`/`SELECT`-only to the app role (`UPDATE`/`DELETE`
revoked) — the database-level half of the tamper-evidence guarantee. The
integration test cannot prove it: the IT's PostgreSQL container is started with
`POSTGRES_USER=jspwiki`, which makes `jspwiki` a **superuser**, and `REVOKE` is a
no-op for superusers. `AuditLogIT` therefore detects the superuser and *skips* the
immutability assertion. The locked grant has no automated proof.

### Approach (in-test restricted role)

Add the proof entirely within `AuditLogIT`, using its existing superuser JDBC
access to the IT database:

1. **Setup** (as the superuser `jspwiki`): create a throwaway login role that is
   explicitly `NOSUPERUSER`, then apply the *exact same* grant statements V036
   applies to `:app_user`:
   ```sql
   CREATE ROLE audit_ro LOGIN PASSWORD 'audit_ro' NOSUPERUSER;
   GRANT  SELECT, INSERT ON audit_log TO audit_ro;
   REVOKE UPDATE, DELETE ON audit_log FROM audit_ro;
   ```
   (Use `CREATE ROLE … ` guarded so a re-run is clean — drop-if-exists first, or
   `DO $$ … $$` existence check. The role name is test-local; pick one unlikely to
   collide, e.g. `audit_ro`.)
2. **Assert** by opening a *second* JDBC connection **as `audit_ro`** (not the
   superuser) and checking:
   - `SELECT` from `audit_log` succeeds.
   - `UPDATE audit_log SET event_type = 'x' WHERE seq = <some existing seq>`
     throws `SQLException` (insufficient privilege).
   - `DELETE FROM audit_log WHERE seq = <…>` throws `SQLException`.
3. **Teardown:** close the restricted connection; optionally `DROP ROLE audit_ro`
   (after reassigning/REVOKE if needed) so repeated IT runs stay clean. If drop is
   awkward (owned grants), leave the role — it is idempotently re-created.

The previous superuser-detection skip is **removed** (or kept only as a guard that
now should never trigger, logging if it does).

### Why this approach

- Self-contained: no change to the IT pom, docker-maven-plugin, or CI plumbing —
  the assertion lives next to the rest of the audit IT.
- Proves the grant model exactly as production enforces it (a non-superuser role
  with the V036 grants applied), which is the thing auditors care about.

### Rejected alternatives

- **`pre-integration-test` SQL step in the IT pom** to create the role — more
  moving parts (pom + a SQL resource) for the same single assertion.
- **Run the whole IT app as a non-superuser role** — a much larger, riskier change
  to the container/datasource wiring for no additional proof.

### Testing

The assertion *is* the test. Verified by re-running the rest IT module
(`mvn clean install -Pintegration-tests -fae -pl wikantik-it-tests/wikantik-it-test-rest -am`).
Confirm `AuditLogIT` now executes (not skips) the immutability check and it passes.

---

## Item 2 — Decouple audit init from Knowledge-Graph init

### Problem

`initAuditSubsystem(...)` is currently the final statement inside
`initKnowledgeGraph(props)`'s `try` block. Consequences:

- A `RuntimeException` anywhere earlier in KG setup throws to the shared
  `catch`, **skipping audit init entirely** — a compliance subsystem silently
  disabled by an unrelated KG failure.
- Audit availability is coupled to KG init success.

### Approach (own top-level init step)

Extract audit initialization into its own top-level method invoked directly from
`initialize()`, in its **own `try/catch`**:

- New `private void initAuditSubsystem()` (no longer taking KG-scoped locals)
  resolves its three inputs independently:
  - `DataSource` — via the same JNDI lookup `initKnowledgeGraph` performs
    (`java:comp/env` → the configured datasource name). Factor the lookup so both
    call sites share it, or repeat the small lookup; either way audit no longer
    borrows KG's local `ds`.
  - `PageManager` and `StructuralIndexService` — via `getManager(...)` / the
    existing subsystem accessors.
- **Sequencing:** call it from `initialize()` **after** the auth subsystem (it
  registers the listener against authn/authz/user/group managers), the page
  manager, and the page-graph/structural-index subsystem are built — but
  **independent of** `initKnowledgeGraph`. Placing the call immediately after the
  page-graph subsystem is wired (and after `initKnowledgeGraph`, so order in the
  happy path is unchanged) keeps current behavior on success while removing the
  failure coupling.
- Its `try/catch` logs `LOG.warn` on any failure (never swallow; never break
  engine startup) and `LOG.warn` when `ds == null` (audit disabled — unchanged).
  On success it logs the existing `Audit subsystem initialized` line.

Net behavior change: **audit initializes whenever a datasource is present,
regardless of KG-init outcome.**

### Risks / care

- The listener must still be retained in a `WikiEngine` field (the v1 fix for the
  `WeakHashMap` GC issue) — preserve that.
- The DataSource JNDI lookup can throw `NamingException` when no datasource is
  configured; handle it the same way (treat as "audit disabled", `LOG.warn`), not
  as a fatal.
- The `ArchUnit` freeze store (`DecompositionArchTest`) will shift line numbers for
  the moved `getManager(...)` calls; update the freeze store and confirm the test
  is green (do not let a failing run prune it — restore from git and re-freeze if
  needed).

### Testing

- Compile + the existing `AuditLogIT` end-to-end run prove the listener still fires
  and `verify` passes after the move.
- If cheap, add a focused check that audit init is reached independent of KG — e.g.
  assert via the IT that audit rows are produced (already covered by `AuditLogIT`);
  a dedicated unit test of `WikiEngine` ordering is not warranted given the engine's
  init complexity.

---

## Build / verification

Per project rules, gate on the full IT reactor before committing prod-code changes.
In this sandbox the single-shot `mvn clean install -Pintegration-tests -fae` cannot
complete (session wall-limit); use the documented two-halves fallback
(`mvn clean install -T 1C -DskipITs`, then each IT module sequentially with
`-pl … -am`). See [[reference_full_it_reactor_execution]].
