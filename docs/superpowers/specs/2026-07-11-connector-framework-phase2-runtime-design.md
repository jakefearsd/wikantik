# Connector Framework Phase 2.1 — Runtime & Operations Design

**Status:** approved 2026-07-11
**Roadmap brief:** `RoadmapConnectorFramework` (wiki) · **Architecture:** `RoadmapTargetArchitecture` (wiki)
**Builds on:** Phase 1 (`docs/superpowers/specs/2026-07-11-connector-framework-phase1-design.md` — shipped: `SourceConnector` SPI, `SyncOrchestrator`, `JdbcSyncStateStore`, `FilesystemSourceConnector`, `DerivedPageSinkAdapter`, migration `V046`).

## Scope

Phase 1 is a dormant library — nothing runs a sync in the deployed app. Phase 2.1 makes it **live and operable**: register a filesystem connector from config, trigger a sync, see status, and run it on a schedule. This is the first of several Phase 2 sub-projects (the others — credential encryption, real-service connectors, webhooks — are deferred and out of scope here).

**Non-goals (2.1):** credential storage/encryption; any networked or authenticated connector; webhook receivers; async job-tracking for manual triggers; a UI (admin REST only).

## Architecture

### Module & wiring boundary (invariant #6: `wikantik-main` grows no new package)

```
wikantik-connectors  (module — depends on wikantik-api ONLY; the runtime lives here)
    ConnectorRegistry       id → SourceConnector (immutable after wiring)
    ConnectorRuntime        the operable facade: syncNow(id)→SyncReport,
                            status(id)→ConnectorStatus, list()→List<ConnectorStatus>,
                            startScheduler(intervalHours), stop(); holds the
                            SyncOrchestrator + a single-thread executor for the scheduler
    ConnectorSyncScheduler  periodic all-connectors sync (modeled on
                            OntologyRebuildScheduler; interval≤0 ⇒ disabled no-op)
    ConnectorStatusReader   reads connector_sync_state (last_run, status) + count(*)
                            of connector_synced_item per connector — a NEW read-only
                            DAO, so the fixed Phase-1 SyncStateStore SPI is untouched

wikantik-main  (thin wiring in the EXISTING com.wikantik.derived package — no new package)
    ConnectorWiringHelper   reads config; builds DerivedPageSinkAdapter +
                            JdbcSyncStateStore + FilesystemSourceConnector(s);
                            populates ConnectorRegistry; constructs ConnectorRuntime;
                            registers it via engine.setManager(ConnectorRuntime.class, …);
                            starts the scheduler. Invoked once from WikiEngine startup,
                            exactly like OntologyWiringHelper.wireOntology(engine, …).

wikantik-rest
    ConnectorAdminResource  /admin/connectors/* (extends RestServletBase, modeled on
                            AdminDerivedResource); resolves ConnectorRuntime from the
                            engine; under AdminAuthFilter (AllPermission).
```

The runtime stays in the `wikantik-connectors` module (still `wikantik-api`-only). `wikantik-main` gains only the thin `ConnectorWiringHelper` in the package that already owns `DerivedPageSinkAdapter` — permitted "growth of an existing package where the code it extends already lives."

### Contracts / types

New types (in `wikantik-connectors`, package `com.wikantik.connectors.runtime`):

```java
public record ConnectorStatus(
    String connectorId, String connectorType,
    String lastRun,        // ISO instant or null if never run
    String lastStatus,     // connector_sync_state.status, or null
    int syncedItemCount ) {}

public final class ConnectorRegistry {
    // built once at wiring time; id → SourceConnector + declared type label
    public ConnectorRegistry( Map<String, SourceConnector> byId, Map<String,String> typeById );
    public Optional<SourceConnector> get( String id );
    public Set<String> ids();
    public String typeOf( String id );
}

public final class ConnectorRuntime {
    public ConnectorRuntime( ConnectorRegistry registry, SyncOrchestrator orchestrator,
                             ConnectorStatusReader statusReader );
    public SyncReport syncNow( String connectorId );          // throws if id unknown
    public ConnectorStatus status( String connectorId );
    public List<ConnectorStatus> list();
    public void startScheduler( long intervalHours );          // ≤0 ⇒ disabled
    public void stop();
}
```

`ConnectorSyncScheduler` may be a package-private helper the runtime owns, or folded into `ConnectorRuntime.startScheduler`. It uses `Executors.newSingleThreadScheduledExecutor` with a named thread (`wikantik-connector-sync-scheduler`) and `scheduleAtFixedRate(this::syncAll, interval, interval, HOURS)` — the exact `OntologyRebuildScheduler` shape. `syncAll` iterates `registry.ids()` calling `syncNow`, each wrapped so one connector's failure is logged (`LOG.warn`) and does not abort the others.

`ConnectorStatusReader(DataSource ds)` — two queries: `SELECT last_run, status FROM connector_sync_state WHERE connector_id=?` and `SELECT count(*) FROM connector_synced_item WHERE connector_id=?`. Read-only; no schema change.

### Admin surface (`/admin/connectors/*`, wikantik-rest)

| Method + path | Behavior |
|---|---|
| `GET /admin/connectors` | JSON array of `ConnectorStatus` for every registered connector (via `runtime.list()`). |
| `POST /admin/connectors/{id}/sync` | Run the sync **synchronously**, return the `SyncReport` JSON (200). Unknown id → 404. Synchronous is acceptable in 2.1 (only the fast filesystem connector exists) and makes the admin IT deterministic. A networked connector (later sub-project) will move this to async submit + poll `status`. |
| `GET /admin/connectors/{id}/status` | The persistent `ConnectorStatus` (from `connector_sync_state` + item count). Unknown id → 404. |

Resolved from the engine like `AdminDerivedResource` resolves its derived services. Registered as a servlet at `/admin/connectors/*` in `web.xml` (mirroring how other admin servlets mount). Under `AdminAuthFilter` (`/admin/*`).

### Configuration (all default-off — zero behavior change until an operator opts in)

| Property | Default | Meaning |
|---|---|---|
| `wikantik.connectors.enabled` | `false` | Master switch. When false, `ConnectorWiringHelper` is a no-op (no runtime registered; admin resource returns an empty list / 503). |
| `wikantik.connectors.sync.interval.hours` | `0` | Scheduler interval; `0`/≤0 ⇒ scheduler disabled (manual trigger only). |
| `wikantik.connectors.filesystem.<id>.root` | — | Registers a `FilesystemSourceConnector` with id `<id>` rooted at the path. Zero such keys ⇒ no connectors registered. |

`ConnectorWiringHelper` discovers filesystem connectors by scanning property keys matching `wikantik.connectors.filesystem.*.root`. The `<id>` becomes the connector id (namespacing its sync-state rows + item URIs).

### Error handling / invariants

- **Async invariant:** the scheduler runs off the request thread. The manual trigger is synchronous by deliberate 2.1 scope (fast connector); documented as a change point for networked connectors.
- **Fail-closed:** a connector sync failure is caught + `LOG.warn`'d (never swallowed silently); one connector failing never aborts the scheduler's other connectors. The admin trigger surfaces a failure as a 500 with a message (the orchestrator itself already logs FAILED ingests and continues).
- **Invariant #6:** runtime in `wikantik-connectors`; only a thin wiring helper added to `wikantik-main`'s existing `com.wikantik.derived` package.
- **PostgreSQL-first:** no new tables; status derives from the existing V046 tables.
- **Fixed Phase-1 surface:** no change to `SourceConnector`, `SyncOrchestrator`, `SyncStateStore`, `DerivedPageSink`, or `V046`.

## Testing

| Unit / IT | What it proves | Where |
|---|---|---|
| `ConnectorRuntime` unit | `register → syncNow → SyncReport`; `status` reflects a run; `list` covers all ids; unknown id throws/404-maps | `wikantik-connectors` (fakes) |
| Scheduler unit | `interval≤0` ⇒ scheduler never starts (no-op); `>0` ⇒ single-thread executor scheduled; `syncAll` isolates per-connector failures | `wikantik-connectors` |
| `ConnectorStatusReader` | reads last_run/status + item count from the V046 tables | `wikantik-connectors` (H2, like JdbcSyncStateStoreTest) |
| `ConnectorWiringHelper` config | connectors built from `wikantik.connectors.filesystem.*.root`; `enabled=false`/no keys ⇒ empty registry, no scheduler | `wikantik-main` (fakes) |
| Admin end-to-end IT | configured filesystem root → `POST /admin/connectors/{id}/sync` → 200 + report; derived pages exist; `GET /admin/connectors/{id}/status` shows the run; non-admin → 403 | `wikantik-it-tests` (Cargo + Postgres) |

## Open questions (resolve during planning, not blocking)

1. **Reaching `ConnectorRuntime` from the admin resource.** Mirror `AdminDerivedResource`'s resolution path exactly (whether that's `engine.getManager(...)` on the closed allow-list or a subsystem accessor). If it's `getManager`, the plan adds `ConnectorRuntime` to the `DecompositionArchTest` R-2 allow-list the same way `OntologyRebuildCoordinator` is registered. Planning inspects `AdminDerivedResource` and copies its pattern.
2. **web.xml vs annotation registration** for the new admin servlet — copy whatever the existing admin servlets (e.g. `AdminProfilingServlet`) use.
