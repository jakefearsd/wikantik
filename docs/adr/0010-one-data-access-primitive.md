# One data-access primitive, one schema definition

Production code touches the database through exactly one primitive — `com.wikantik.jdbc.Jdbc`
(module `wikantik-jdbc`) — and tests run against the real migrations, never a hand-written
schema. `Jdbc.inTransaction` is the only transaction boundary in the codebase: it rolls back
on **any** `Throwable` and restores the connection's previous auto-commit state only *after*
commit or rollback has run. `JdbcSupport` is an `extends` convenience over the same instance.
Both rules are enforced mechanically: `wikantik-war` `JdbcAccessArchTest` (rule J-1) forbids
`DataSource.getConnection`, `DriverManager.getConnection` and
`Connection.{prepareStatement, prepareCall, createStatement, setAutoCommit, commit, rollback}`
outside `com.wikantik.jdbc..`; `TestSchemaSingleSourceTest` forbids `CREATE TABLE` in any test
source. One permanent carve-out: `JDBCPlugin` (page-authored SQL over its own `DriverManager`
lifecycle, disabled by default) — a different problem, deliberately left alone.

Rationale: the 2026-08-22 audit found 56 classes executing raw SQL in four coexisting
transaction idioms, a shared helper used by 8 of them, 13 files hand-rolling `setAutoCommit`,
and the schema defined in four places (57 migrations, four legacy `postgresql*.ddl` snapshots,
a 24-table `postgresql-test.sql`, and 30 tests with their own `CREATE TABLE`). Release 2.4.19
had just shipped three transaction-boundary fixes, each written differently, and its comments
fix *added* a fourth private helper instead of converging. The latent shape —
`catch (SQLException)` only, so an unchecked exception skips `rollback()` and the
`finally { setAutoCommit(prev) }` **commits** the partial transaction per the JDBC contract —
was still live in `CitationRepository`, `DriftSnapshotRepository`, `KgProposalRepository`
(whose `updateTierByProvenance` ran two UPDATEs with no transaction at all), the audit log's
partition-DDL path, and the three embedding writers (which additionally missed `Error`).
H2 could not execute the `ON CONFLICT`/`::vector` statements, so the unit layer structurally
could not see any of it; the Postgres tests that could were `disabledWithoutDocker = true`
and skipped silently.

What the migrated-schema fixture surfaced on its first run is the strongest argument for it:
hub discovery/proposal/overview had been querying `kg_edges` for the literals `related` /
`links_to` that the V027/V030 `relationship_type` CHECK vocabulary had made unreachable — the
readers returned nothing in production; and `api_keys_scope_chk` (V010) did not admit
`mcp_read`, so the 2.4.18 read-only MCP key could not be minted on PostgreSQL at all (V058).
Neither was visible to a test that defined its own tables.

Consequences, accepted deliberately: every database test now needs Docker (one container per
JVM, migrations applied once, `PostgresTestDb.truncate(...)` for isolation; locally an absent
daemon skips with a reason, CI passes `-Dtests.requireDocker=true` so it fails
instead); `wikantik-insights` and `wikantik-connectors` gained a dependency on the JDBC-only
`wikantik-jdbc` module (no wiki types — their engine-free posture is unchanged); a few
connection-sharing optimisations are expressed through `withConnection`/`inTransaction`
bodies rather than a raw `Connection`; generated keys go through `insertReturningKey` so SQL
text never has to change to `RETURNING`; and the distinction between "this primitive" and
"a new transaction helper" is policed by the ratchet, not by review. Adding a repository now
means: hold a `Jdbc`, write the migration first, test against `PostgresTestDb`, and give any
multi-statement write a `FaultInjectingDataSource` rollback test.

Plan and execution record: `docs/superpowers/plans/2026-08-22-one-way-to-touch-the-database.md`.
