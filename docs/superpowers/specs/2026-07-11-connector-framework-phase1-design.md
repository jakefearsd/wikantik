# External Source Connector Framework — Phase 1 Design

**Status:** approved 2026-07-11
**Roadmap brief:** `RoadmapConnectorFramework` (wiki) · **Architecture:** `RoadmapTargetArchitecture` (wiki)
**Scope:** the `SourceConnector` SPI + sync-state migration + one read-only filesystem fixture connector. No OAuth, no scheduler, no webhooks, no real external service — those are Phase 2.

## Goal

Give Wikantik the *source side* of ingestion: a pull-based connector SPI that syncs an external corpus into **derived pages** incrementally (change-detected, resumable, tombstone-aware). Phase 1 proves the SPI and the sync mechanics against a filesystem fixture; real-service connectors and scheduling are gated on this surviving review.

## Why this is small: the substrate already exists

`com.wikantik.derived.DerivedPageIngestionService.ingest(...)` (in `wikantik-main`) already turns source bytes + options into a derived page, talks to the wiki only through narrow interfaces (`PageReader`, `PageWriter`, `AttachmentStore`, `PageDeleter`), and already returns `IngestResult.Status.UNCHANGED` on a content-hash match. So the item→derived-page mapping **and** idempotent re-sync are already built. Phase 1 adds only: (a) a source-side SPI, (b) a sync orchestrator, (c) per-connector sync state.

## Architecture

### Module & dependency boundary (invariant #6: `wikantik-main` grows no new package)

```
wikantik-api        contracts only — com.wikantik.api.connectors:
                      SourceConnector (SPI), SourceItem, SyncBatch, SyncCursor,
                      SyncStateStore (port), DerivedPageSink (port), IngestOutcome

wikantik-connectors NEW module, depends on wikantik-api ONLY:
                      SyncOrchestrator          — the sync loop
                      JdbcSyncStateStore        — Postgres DAO (owns the migration semantics)
                      FilesystemSourceConnector — the Phase-1 fixture connector

wikantik-main       NO new package — one thin adapter in the EXISTING com.wikantik.derived:
                      DerivedPageSinkAdapter    — implements DerivedPageSink by delegating
                                                  to DerivedPageIngestionService (+ its PageDeleter
                                                  for tombstones)
                    + thin WikiEngine wiring: construct the orchestrator, register connectors,
                      inject the sink adapter + JdbcSyncStateStore
```

The orchestrator reaches the wiki only through the `DerivedPageSink` **port in `wikantik-api`**, so `wikantik-connectors` never depends on `wikantik-main`. This is the provider-pattern idiom the target-architecture says to copy. `wikantik-main`'s only growth is one adapter class + one minimal field on `IngestOptions`, both in the package that already owns derived pages (in-place growth of an existing package — permitted by invariant #6; no new package).

### One in-place change to the existing derived seam (page-name / provenance decoupling)

`DerivedPage.pageNameFor(filename)` derives the page name from the **basename only** and `DerivedPageIngestionService.ingest` sets `derived_from = filename`. Both are correct for single-file uploads but break for a connector syncing a *tree*: two files `a/x.md` and `b/x.md` collide to page "x", and `derived_from` can't be the full connector URI independently of the page name. Fix — the single change to `wikantik-main`:

- Add one optional field to `IngestOptions`: `String derivedFrom` (`null` → current behavior: `derived_from = filename`). One line in `ingest` honors it: `meta.put(DERIVED_FROM, opts.derivedFrom() != null ? opts.derivedFrom() : filename)`. Backward-compatible (every existing caller passes the 2-arg form / `null`).

The `DerivedPageSinkAdapter` then maps a `SourceItem` to `ingest(content, flatName, contentType, new IngestOptions(force=false, author, derivedFrom=sourceUri))`, where `flatName` is the connector's collision-free page-name seed (the relative path with `/`→`-`, e.g. `a/x.md`→`a-x.md`) and `derivedFrom` is the true `sourceUri`. This makes `derived_from` carry the connector URI (DoD #1) while page names stay unique across a tree.

### Contracts (`wikantik-api`, `com.wikantik.api.connectors`)

```java
public interface SourceConnector {
    String connectorId();                 // stable id; namespaces item URIs + sync-state rows
    SyncBatch poll(SyncCursor cursor);    // cursor == null → full initial sync
}

public record SyncBatch(
    List<SourceItem> items,               // items new/changed since the cursor
    List<String>     tombstonedUris,      // sourceUris deleted at source since the cursor
    SyncCursor       nextCursor,          // opaque checkpoint to persist after this batch
    boolean          complete             // true when the source is fully drained this run
) {}

public record SourceItem(
    String              sourceUri,        // connector-scoped stable URI (e.g. "file:docs/a.md")
    byte[]              content,
    String              contentType,      // MIME; drives the Tika extractor path
    Map<String,Object>  sourceMetadata,   // authorship/origin/update-history → derived frontmatter
    List<String>        aclRefs,          // source ACL references — CARRIED, UNENFORCED in Phase 1
    String              contentHash        // caller-computed sha256 hex of content
) {}

public record SyncCursor(String value) {} // opaque, connector-defined

// Port implemented by wikantik-main; consumed by the orchestrator.
public interface DerivedPageSink {
    IngestOutcome ingest(SourceItem item);   // create/update the derived page; body machine-owned
    void          delete(String pageName);   // tombstone → remove/archive the derived page
}
public record IngestOutcome(String pageName, Status status) {   // maps IngestResult.Status
    public enum Status { CREATED, UPDATED, UNCHANGED, FAILED }
}

// Port implemented by wikantik-connectors (JdbcSyncStateStore); consumed by the orchestrator.
public interface SyncStateStore {
    Optional<SyncCursor> loadCursor(String connectorId);
    void                 saveCursor(String connectorId, SyncCursor cursor);
    Optional<String>     syncedHash(String connectorId, String sourceUri);   // for hash-dedup
    void                 recordSynced(String connectorId, String sourceUri,
                                      String contentHash, String pageName, List<String> aclRefs);
    Optional<String>     pageNameFor(String connectorId, String sourceUri);  // for tombstone delete
    List<String>         knownUris(String connectorId);                      // for tombstone diff (option A)
    void                 removeSynced(String connectorId, String sourceUri);
}
```

`aclRefs` in the DTO and the `acl_refs` column exist from day one but are **not enforced** in Phase 1 — the enforcement semantics are decided in `RoadmapExternalAclInheritance`. Carrying them now is the co-design constraint (DoD #4).

### Sync-state schema — `bin/db/migrations/V046__connector_sync_state.sql`

Idempotent DDL (`CREATE TABLE IF NOT EXISTS`, `:app_user` grants, re-apply is a no-op):

```sql
CREATE TABLE IF NOT EXISTS connector_sync_state (
    connector_id TEXT PRIMARY KEY,
    cursor       TEXT,
    last_run     TIMESTAMPTZ,
    status       TEXT
);
CREATE TABLE IF NOT EXISTS connector_synced_item (
    connector_id TEXT NOT NULL,
    source_uri   TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    page_name    TEXT NOT NULL,
    acl_refs     TEXT NOT NULL DEFAULT '[]',   -- JSON array string; TEXT (not JSONB) keeps the DAO
    first_synced TIMESTAMPTZ NOT NULL DEFAULT now(),  -- H2-unit-testable and Phase 1 never queries into it.
    last_synced  TIMESTAMPTZ NOT NULL DEFAULT now(),  -- The ACL-enforcement phase migrates TEXT→JSONB when it
    PRIMARY KEY (connector_id, source_uri)            -- needs to index/query the refs.
);
```

### Orchestrator loop (`SyncOrchestrator.sync(SourceConnector)`)

```
cursor = stateStore.loadCursor(connectorId)         // resume point (empty → full sync)
loop:
    batch = connector.poll(cursor)
    for item in batch.items:
        if stateStore.syncedHash(id, item.sourceUri) == item.contentHash:  continue   // UNCHANGED
        outcome = sink.ingest(item)
        if outcome.status != FAILED:
            stateStore.recordSynced(id, item.sourceUri, item.contentHash, outcome.pageName, item.aclRefs)
    for uri in batch.tombstonedUris:
        stateStore.pageNameFor(id, uri).ifPresent(sink::delete)
        stateStore.removeSynced(id, uri)
    cursor = batch.nextCursor
    stateStore.saveCursor(id, cursor)               // persist AFTER the batch → crash-resume point
    if batch.complete: break
return SyncReport(created, updated, unchanged, deleted, failed)
```

Crash mid-run → restart resumes from the last persisted `nextCursor` and hash-skips already-synced items, so no item is duplicated or re-processed (DoD #2). A `FAILED` ingest is logged (`LOG.warn`, never swallowed) and left un-recorded so the next run retries it.

### The Phase-1 fixture connector (`FilesystemSourceConnector`)

Constructed over a root directory. `poll(cursor)`:
- Full-scans the tree; each regular file → `SourceItem` (`sourceUri = "file:" + relPath`, `content = bytes`, `contentType` by extension, `sourceMetadata = {path, size, modified}`, `aclRefs = [parent-dir-name]` as a stand-in principal, `contentHash = sha256`).
- Tombstones = `source_uri`s the store has for this connector that were **not** seen in this scan (the orchestrator passes the store the current live URI set, or the connector queries the store via an injected read-only view — see Open questions).
- `complete = true` (single batch); `nextCursor` = a scan watermark (timestamp string). Full-scan-each-run is correct for a fixture; incremental watermarking is a Phase-2 connector concern.

## Testing (DoD → tests, TDD-first)

| DoD | Test | Where |
|-----|------|-------|
| 1. sync → derived pages (`derived_from` = connector URI, source metadata in frontmatter, passes schema validator) | full sync against a fixture tree using the **real** `DerivedPageSinkAdapter` + wiki | `wikantik-it-tests` scenario |
| 2. cursor-resume without dup/reprocess | orchestrator with a fake connector that throws after batch 1; restart; assert no re-ingest of batch-1 items | `wikantik-connectors` IT (IT Postgres) |
| 3. tombstone → derived page removed | sync a fixture, delete a file, re-sync; assert `sink.delete` called + row removed + page gone | `wikantik-connectors` IT + a `wikantik-it-tests` end-to-end check |
| 4. migration re-applies as no-op; `acl_refs` carried | apply `V046` twice (assert no error, stable schema); assert `connector_synced_item.acl_refs` populated from `SourceItem.aclRefs` and surfaced into derived frontmatter | migration test + orchestrator IT |

Unit tests: `SyncOrchestrator` loop (hash-dedup, tombstone, cursor persistence order) against a fake `SyncStateStore` + fake `DerivedPageSink` + fake `SourceConnector`; `FilesystemSourceConnector.poll` (URI/hash/metadata/tombstone derivation) against a temp dir; `JdbcSyncStateStore` against the IT Postgres.

New module boilerplate: `wikantik-connectors/pom.xml` must declare `mockito-core` (test scope) — every new module does, or surefire fails on the inherited javaagent.

## Invariants respected

- **#6 `wikantik-main` does not grow** — contracts in `wikantik-api`, impls in `wikantik-connectors`, one thin adapter in the existing `com.wikantik.derived` package.
- **#5 PostgreSQL-first** — sync state = two tables behind a numbered idempotent migration; no new datastore.
- **#3 async-by-design** — Phase 1 runs the orchestrator on demand (admin-triggered/test-driven); the scheduler that makes it background is Phase 2, modeled on `OntologyRebuildScheduler`.
- **Reuse, don't duplicate** — derived-pages content model, provider-pattern SPI, `IngestResult` hash-dedup.

## Non-goals (Phase 1)

OAuth/credential encryption; the sync scheduler; webhook receivers; any real-service connector; ACL *enforcement*; bidirectional (wiki→source) sync.

## Open questions (resolve during planning, not blocking)

1. **Tombstone detection wiring for a full-scan connector — resolved to Option A.** The orchestrator, on a `complete` batch, calls `stateStore.knownUris(connectorId)`, subtracts the URIs present in `batch.items` **and** those already in `batch.tombstonedUris`, and treats the remainder as deletions. This keeps the fixture connector pure (it emits items only); `SyncBatch.tombstonedUris` stays in the contract for future *incremental* connectors that learn deletions directly from the source (webhooks/change-feeds) and can't rely on a full-scan diff. Planning pins the exact orchestrator condition (only diff when `batch.complete`, never on a partial batch — a partial batch's absent URIs are "not yet seen", not deleted).
2. **Where the sync→derived-page IT lives** — a new `wikantik-connectors`-owned IT vs. a scenario in `wikantik-it-tests`. Lean on `wikantik-it-tests` for the real-sink path (it already has Cargo+Postgres); connector-local ITs use the IT Postgres directly.
