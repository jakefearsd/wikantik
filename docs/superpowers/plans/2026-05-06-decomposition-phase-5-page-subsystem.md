# Phase 5: PageSubsystem extraction + DefaultPageManager decomposition — implementation plan

**Spec:** [docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md](../specs/2026-05-05-wikantik-main-decomposition-design.md)
**Status:** complete (2026-05-07)
**Estimated effort:** 7–10 days
**Goal:** wall off page lifecycle (read / save / delete / lock / version / rename) and attachments behind a typed `PageSubsystem.Services` surface. Decompose `DefaultPageManager` (816 LOC) along its four real roles. Move every `engine.getManager(PageManager|AttachmentManager|PageRenamer.class)` consumer (~75 call sites across seven modules) onto the typed accessor.

This is the largest decomposition phase and also the highest-blast-radius — `PageManager` is the most-referenced manager in the codebase. Two structural rules govern the work:

1. **The public surface is sacred.** `PageManager`, `AttachmentManager`, `PageRenamer` are all `wikantik-api` interfaces; their public method signatures DO NOT MOVE. Implementations rearrange behind them.
2. **Behaviour change is zero across every checkpoint.** Save-time filter ordering, event firing order, lock semantics, version semantics — all preserved bit-for-bit. The Selenide IT suite is the regression net.

## Scope

**In:**
1. **`PageSubsystem.Deps` + `.Services`** records under `com.wikantik.page.subsystem.*`.
2. **`PageSubsystemFactory.create(Deps) → Services`** — pure factory consumed by `WikiEngine.initialize()` after Core / Persistence / Auth.
3. **Decompose `DefaultPageManager` (816 LOC)** along the four roles already segregated in the file:
   - **`PageRepository`** — read / write / version / exists. Wraps the configured `PageProvider` chain (Caching → Logging → Metrics → Versioning → FileSystem).
   - **`PageLifecycle`** — `saveText(Context, String)` orchestrator that runs the FilterManager `preSave` / `postSave` chain around `PageRepository.put`, fires `WikiPageEvent`s, manages page-save context. (~120 LOC moves out of `DefaultPageManager`.)
   - **`PageLockService`** — `lockPage` / `unlockPage` / `getCurrentLock` / `getActiveLocks`. Cleanly self-contained today; ~70 LOC moves out.
   - **`DefaultPageManager` facade** — public surface unchanged, delegates to the three new components. Target shrink: 816 → ~250 LOC.
4. **Move `AttachmentManager` / `DefaultAttachmentManager`** wiring into `PageSubsystemFactory`. The interface is in `wikantik-api`; the impl construction moves.
5. **Move `PageRenamer` / `DefaultPageRenamer`** wiring into `PageSubsystemFactory`.
6. **Migrate consumers** — every `engine.getManager(PageManager|AttachmentManager|PageRenamer.class)` callsite outside the Page subsystem itself moves to `getSubsystems().page().xxx()` (servlets) or `PageSubsystemBridge.fromLegacyEngine(engine).xxx()` (non-servlet). Expected: ~75 callsites across 7 modules — wikantik-main, wikantik-rest, wikantik-knowledge, wikantik-admin-mcp, wikantik-tools, wikantik-observability, wikantik-api.
7. **Subsystem-isolation test** for `PageSubsystemFactory` against a Testcontainers Postgres + a real on-disk `pages/` dir. Verifies the four-way split functions end-to-end: round-trip a page through Lifecycle → Repository → Provider, lock/unlock through LockService, attachment upload through AttachmentManager.

**Out:**
- Splitting `wikantik-page` into a separate Maven module. Keep new types in `wikantik-main` under `com.wikantik.page.subsystem.*`.
- Page Graph (`com.wikantik.pagegraph.*` — `ReferenceManager`, structural index, page-to-page links). That's a future phase; the current Phase 5 only owns the page lifecycle, not the link graph derived from page bodies.
- The `RenderingManager` — Phase 6 territory.
- Refactoring the JAAS-callback `PageSaveHelper` further. Move it; don't reshape it.

## Design

### `PageSubsystem` shape

```java
package com.wikantik.page.subsystem;

public final class PageSubsystem {

    public record Deps(
        CoreSubsystem.Services core,
        PersistenceSubsystem.Services persistence,   // not currently used; allocated for V025+ schema migrations that may give Page direct DAO access
        AuthSubsystem.Services auth,                  // for ACL enforcement on save / delete
        Engine engine                                  // legacy seam — FilterManager + WikiEvents still go through it for now
    ) {}

    public record Services(
        // Manager-level interfaces (wikantik-api):
        PageManager           pages,
        AttachmentManager     attachments,
        PageRenamer           pageRenamer,
        PageSaveHelper        pageSaveHelper,

        // Decomposed page lifecycle (Phase 5 Ckpt 3 — internal interfaces, not in wikantik-api):
        PageRepository        pageRepository,
        PageLifecycle         pageLifecycle,
        PageLockService       pageLockService,

        // Provider chain (escape hatch — some callers want raw provider stats):
        PageProvider          pageProvider
    ) {}
}
```

### `WikiSubsystems` evolution

Order: `(core, persistence, auth, page, knowledge)`. Knowledge already references `PageManager` / `PageSaveHelper` — Ckpt 5 of this phase moves those edges to `PageSubsystem.Services` references on `KnowledgeSubsystem.Deps`.

### `DefaultPageManager` decomposition

Today the class fans out into four concerns the 816 LOC already half-segregates:

```
DefaultPageManager
├─ Provider lifecycle        ── (constructor + initProviders)
├─ Read methods              ── getPage, getPageText, getPureText, getText, getAllPages, etc.
├─ Write methods             ── saveText (filter chain), putPageText, deletePage, deleteVersion
├─ Lock methods              ── lockPage, unlockPage, getCurrentLock, getActiveLocks
└─ Event listening           ── actionPerformed (handles WikiEngineEvent.SHUTDOWN)
```

The decomposition target:

#### `PageRepository` (new internal interface; impl in `com.wikantik.page.subsystem.DefaultPageRepository`)
```java
public interface PageRepository {
    Page getPage( String name, int version );
    Page getPageInfo( String name, int version ) throws ProviderException;
    String getPageText( String name, int version ) throws ProviderException;
    String getPureText( String name, int version );
    Collection<Page> getAllPages() throws ProviderException;
    boolean pageExists( String name, int version ) throws ProviderException;
    <T extends Page> List<T> getVersionHistory( String name );
    int getTotalPageCount();
    Set<Page> getRecentChanges();
    Set<Page> getRecentChanges( Date since );
    void putPageText( Page page, String content ) throws ProviderException;
    void deletePage( Page page ) throws ProviderException;
    void deleteVersion( Page page ) throws ProviderException;
    PageProvider getProvider();
}
```
Owns the `PageProvider` chain and every read/write call that doesn't touch the filter chain. ~330 LOC.

#### `PageLifecycle` (new internal interface; impl in `com.wikantik.page.subsystem.DefaultPageLifecycle`)
```java
public interface PageLifecycle {
    void saveText( Context context, String text ) throws WikiException;
    /* fires WikiPageEvent and wraps PageRepository.putPageText with the FilterManager pre/post chain */
}
```
Takes `PageRepository` + `FilterManager` + `WikiEventBus` as collaborators. ~120 LOC.

#### `PageLockService` (new internal interface; impl in `com.wikantik.page.subsystem.DefaultPageLockService`)
```java
public interface PageLockService {
    PageLock lockPage( Page page, String user );
    void unlockPage( PageLock lock );
    PageLock getCurrentLock( Page page );
    List<PageLock> getActiveLocks();
}
```
Self-contained; existing in-memory map state moves over verbatim. ~70 LOC.

#### `DefaultPageManager` facade (rewrite — same package)
Holds the three new components. Public methods one-line-delegate. Keeps WikiEventListener `actionPerformed` for shutdown handling (the event-listener registration is part of the engine boot sequence and the SHUTDOWN event has to flush locks + invalidate provider caches). ~250 LOC.

### Bridge pattern

`PageSubsystemBridge.fromLegacyEngine(Engine)` mirrors the Auth / Knowledge / Core bridges. Production servlets reach the typed bundle; non-servlet callers and test fixtures use the bridge.

### `WikiEngine.getManager` typed fallback extension

When the bundle exists, `getManager(PageManager.class)` resolves through `pageSubsystem.pages()` (and similarly for `AttachmentManager.class` / `PageRenamer.class`) — same fallback pattern Phases 2 and 4 established. Existing test fixtures keep working transparently.

## Checkpoint plan

Each checkpoint = one commit + the full IT reactor before commit. Concurrent subagents (Sonnet ≥ 4.6 for code that touches multiple files; Haiku where the change is purely sed-mechanical) split the consumer-migration sweeps along non-overlapping module seams.

Estimated per-checkpoint duration:
| Ckpt | Duration | Concurrency |
|------|----------|-------------|
| 1 — Scaffold (Opus) | half day | single agent |
| 2 — Provider construction lift (Opus) | half day | single agent |
| 3 — Decompose `DefaultPageManager` (Opus) | 1.5 days | single agent (high-risk surgery) |
| 4 — Migrate page-manager consumers (parallel ×2 Sonnet) | 1 day | 2 concurrent agents |
| 5 — Migrate attachment-manager + page-renamer consumers (parallel ×2 Sonnet/Haiku) | half day | 2 concurrent agents |
| 6 — Knowledge subsystem consumes Page (Opus) | half day | single agent |
| 7 — Verification + close-out (Opus) | half day | single agent |

### Checkpoint 1 — Scaffold (Opus)

- `com.wikantik.page.subsystem.PageSubsystem` (`Deps` + `Services` records, with `PageRepository` / `PageLifecycle` / `PageLockService` typed as the existing `PageManager` interface for now — the records reference `pages()` and the three internal slots are all the same delegate object to start).
- `PageSubsystemFactory.create(Deps)` reads the four manager-level objects from the engine's legacy registry initially (Auth pattern). Returns a populated `Services`.
- `PageSubsystemBridge.fromLegacyEngine(Engine)` mirroring `AuthSubsystemBridge`.
- `WikiSubsystems` adds `page()` field, ordered `(core, persistence, auth, page, knowledge)`.
- `WikiEngine.initialize()` builds Page after Auth (still inside `initialize()`'s try block).
- `WikiEngine.setManager()` invalidates the snapshot when a `PageManager` / `AttachmentManager` / `PageRenamer` is hot-swapped.
- `RestServletBase.getSubsystems()` reads the typed accessor + bridge fallback.
- `PageSubsystemFactoryTest` — Testcontainers Postgres + a tmpdir page provider. Round-trips one page through `services.pages().saveText` → `services.pages().getPage` → `services.pages().deletePage`.

**Behaviour change:** zero. Decomposition is deferred to Ckpt 3; for Ckpt 1, `PageRepository`/`Lifecycle`/`LockService` are placeholder typed views over the same `DefaultPageManager` instance.

**Verification:** full IT green; ArchUnit unchanged.

### Checkpoint 2 — Provider construction lift (Opus)

Today `DefaultPageManager`'s constructor builds the provider chain (`CachingProvider` → `LoggingPageProviderDecorator` → `MetricsPageProviderDecorator` → `VersioningFileProvider` → `FileSystemProvider`). Move that wiring into `PageSubsystemFactory`:

```java
// Inside PageSubsystemFactory.create:
final PageProvider provider = buildProviderChain( deps.core().properties(), deps.core().meterRegistry() );
final DefaultPageManager pages = new DefaultPageManager( deps.engine(), props, provider );
```

The new `DefaultPageManager` ctor takes a pre-built `PageProvider` (already exists today as a test seam — see line 165 in the current file). Delete `DefaultPageManager.initProviders` entirely. The factory now owns the chain.

**Behaviour change:** zero. Same chain, same order, same configuration knobs.

**Verification:** full IT green; same Selenide tests pass.

### Checkpoint 3 — Decompose `DefaultPageManager` (Opus, high-risk surgery)

The big move. Approach:

1. **Create the three internal interfaces + default impls** (`DefaultPageRepository`, `DefaultPageLifecycle`, `DefaultPageLockService`). Move methods + their private fields/helpers verbatim. SQL/event/filter-chain code is untouched — only the surrounding class boundary changes.
2. **`DefaultPageManager` becomes a facade.** Constructor takes the three new collaborators. Public methods one-line-delegate. The `actionPerformed` shutdown handler stays on the facade; it forwards to `LockService.flushAll()` + `Repository.shutdown()` (small new methods on the impls).
3. **`PageSubsystemFactory.create`** builds the three impls and the facade, exposing all four on `Services`.

Cross-cutting consideration: `PageLifecycle.saveText` invokes `FilterManager.doPreSaveFiltering` and `doPostSaveFiltering`. The `FilterManager` instance has to come from somewhere — Phase 5 keeps the legacy `engine.getManager(FilterManager.class)` path and feeds it into `Lifecycle` at construction time (the factory pulls it off the engine just like the auth managers in Phase 4). Phase 6 (RenderingSubsystem) will redo this edge with a typed `FilterManager` reference.

**Behaviour change:** zero. `saveText` triggers identical filter ordering, identical event firing.

**Verification:**
- `mvn test -pl wikantik-main` (unit tests — page-manager tests are the heaviest).
- Full IT reactor (the Selenide flow exercises page save → render → recent-articles refresh).
- Manual smoke: bring up Tomcat, save a page, confirm it persists and renders correctly. (Local dev validation is the highest-leverage signal here — automated coverage is good but not exhaustive.)

### Checkpoint 4 — Migrate page-manager consumers (parallel ×2 Sonnet)

55 production `engine.getManager(PageManager.class)` callsites (excluding `WikiEngine.java` and `DefaultPageManager.java` itself) split along the natural module seam:

**Agent A — REST / MCP / tools / knowledge / observability** (~25 callsites):
- wikantik-rest/src/main/java/com/wikantik/rest/*Resource.java
- wikantik-admin-mcp/src/main/java/com/wikantik/mcp/**
- wikantik-tools/src/main/java/com/wikantik/tools/**
- wikantik-knowledge/src/main/java/com/wikantik/knowledge/**
- wikantik-observability/src/main/java/com/wikantik/observability/**
- wikantik-api (a small handful of default-method callers — leave any that look bootstrap-time-sensitive)

For RestServletBase subclasses → `getSubsystems().page().pages()`. For non-servlet → `PageSubsystemBridge.fromLegacyEngine(engine).pages()`.

**Agent B — wikantik-main interior** (~30 callsites):
- wikantik-main/src/main/java/com/wikantik/{plugin,filter,search,frontmatter,diff,blog,content,ui,attachment,parser,knowledge,pagegraph,variables}/**
- WikiContext.java + WikiPage.java (these run early in request lifecycle — verify the bridge path is safe).

For all of these → `PageSubsystemBridge.fromLegacyEngine(engine).pages()`.

**SKIP list (bootstrap-order or out-of-scope):**
- DefaultPageManager.java (facade itself)
- PageSubsystemFactory.java + PageSubsystemBridge.java (already correct)
- WikiEngine.java (engine-internal; `getManager` lookups inside `initialize()` happen before `pageSubsystem` is built)
- Anything inside `wikantik-extract-cli` (separate boot path, no engine)

**Verification:** zero non-skip-list callers of `engine.getManager(PageManager.class)` in production; full IT green.

### Checkpoint 5 — Migrate attachment + rename consumers (parallel ×2 Sonnet/Haiku)

Smaller surface than Ckpt 4. Two concurrent agents:

**Agent A — `AttachmentManager` callers** (18 files):
Same module split as Ckpt 4 but the callsite count per module is small. Mechanical: `engine.getManager(AttachmentManager.class)` → `getSubsystems().page().attachments()` or bridge. Haiku-suitable (sed-level mechanical replacement).

**Agent B — `PageRenamer` callers** (2 files):
Tiny job. Migrate to `getSubsystems().page().pageRenamer()` / bridge. Haiku-suitable; pair this with the Ckpt 5 Agent A work into a single Haiku invocation if quota economics favour that.

**Verification:** full IT green.

### Checkpoint 6 — Knowledge subsystem consumes Page (Opus)

Today `KnowledgeSubsystem.Deps` declares:
```java
public record Deps(
    DataSource dataSource,
    PersistenceSubsystem.Services persistence,
    CoreSubsystem.Services core,
    PageManager pageManager,                   // ← Phase 5 target
    PageSaveHelper pageSaveHelper,             // ← Phase 5 target
    HubOverviewService.LuceneMlt luceneMlt
);
```

After Ckpt 6:
```java
public record Deps(
    DataSource dataSource,
    PersistenceSubsystem.Services persistence,
    CoreSubsystem.Services core,
    PageSubsystem.Services page,                // ← replaces pageManager + pageSaveHelper
    HubOverviewService.LuceneMlt luceneMlt
);
```

`KnowledgeSubsystemFactory.create(Deps)` reads `deps.page().pages()` and `deps.page().pageSaveHelper()`. `WikiEngine.initialize()` wires the new shape. The third cross-subsystem dependency edge after Knowledge → Core (Ph 2) and Knowledge → Persistence (Ph 3).

`KnowledgeSubsystemFactoryTest` updates its setup to build a `PageSubsystem.Services` (a small helper alongside the existing `core(props)` and `persistence()` helpers).

**Verification:** full IT green; `KnowledgeSubsystemFactoryTest` passes against the new shape.

### Checkpoint 7 — Verification + close-out (Opus)

- Re-run the metrics: `bin/metrics/measure.sh --label phase_5_close`.
- Update spec § Phase 5 with status + deltas.
- Mark this plan complete.
- Refreeze ArchUnit baseline if migrations changed the violation set materially.
- Spot-check the four largest god-classes after the phase to set up Phase 6 expectations.

## Risks

1. **`saveText` filter-chain ordering.** The 11 page-save filters (`StructuralSpinePageFilter` at -1003, `RunbookValidationPageFilter` at -1003, `ChunkProjector` at -1005, `FrontmatterDefaultsFilter` at -1004, plus `ReferenceManager` at -1001 and `SearchManager` at -1002) all register with `FilterManager` at engine boot. Phase 5 doesn't move filter registration — the registration sites stay where they are. But `PageLifecycle.saveText` has to invoke the filter chain in *exactly* the same order with *exactly* the same `Context` object. The decomposition copies the body verbatim; verification is the Selenide IT plus a manual save-render-verify cycle. If a filter fires at a different point or sees a different context, the structural-spine + KG indexes drift silently — a corpus-level regression that's much harder to find than a unit-test failure.

2. **Page lock state.** `PageLockService` holds an in-memory map. The shutdown event clears it. If the `actionPerformed` handler doesn't see the SHUTDOWN event after the decomposition (because event registration on the facade vs. the impl shifts), locks survive past shutdown and reappear stale on next boot. Mitigation: keep `actionPerformed` on the facade, registered exactly as `DefaultPageManager` registers today.

3. **`PageProvider` chain construction order.** `CachingProvider` wraps `LoggingPageProviderDecorator` wraps `MetricsPageProviderDecorator` wraps `VersioningFileProvider` wraps `FileSystemProvider`. The metrics decorator measures a slice of the call hierarchy that depends on its position. Move the chain construction verbatim — same nesting, same wrap order. `wikantik.pageProvider` property selection (file vs versioning) lives in `DefaultPageManager.initProviders` today; copy that selection logic verbatim into `PageSubsystemFactory`.

4. **Test surface impact.** ~75 production callsites + many test callsites that mock `engine.getManager(PageManager.class)`. Tests that mock the facade method directly need to be updated to mock `PageSubsystem.Services` or the bridge. Mitigation: extend `WikiEngine.getManager`'s typed fallback to include `PageManager` / `AttachmentManager` / `PageRenamer`, and let the bridge fall through to the legacy registry on miss — same as Auth in Phase 4. Test fixtures that use `TestEngine.setManager(PageManager.class, mock)` keep working without modification.

5. **`PageSaveHelper`'s engine reference.** It's passed `Engine` at construction (`new PageSaveHelper(this)` in `WikiEngine.initialize`). The helper calls back into `engine.getManager(PageManager.class)` to trigger saves with options. After Ckpt 4 migration, that lookup goes through the typed fallback. After Ckpt 6 it goes through the typed bundle directly. The ordering matters: Ckpt 4 must land before Ckpt 6 so the helper has a working code path at every point.

6. **Frontend admin pages.** A handful of admin JSPs call `engine.getManager(PageManager.class)` directly (admin templates haven't been audited yet). These DON'T extend RestServletBase; they're JSP scriptlets. Audit during Ckpt 4 Agent B; if they exist, they migrate to `PageSubsystemBridge.fromLegacyEngine(engine).pages()` since JSPs already have `engine` in scope.

## Done when

- `PageSubsystem.Services` produces the four manager-level objects + the three decomposition impls + the raw provider escape hatch.
- `DefaultPageManager` is decomposed: ~250-LOC facade + `DefaultPageRepository` + `DefaultPageLifecycle` + `DefaultPageLockService`.
- `god_classes_over_800` drops by ~1 (DefaultPageManager from 816 LOC to ~250 LOC), and the four sub-impls are individually well under the threshold.
- 75 production `engine.getManager(PageManager|AttachmentManager|PageRenamer.class)` callsites are migrated; auth-internal-style bootstrap-order exemptions are documented.
- `KnowledgeSubsystem.Deps` declares `PageSubsystem.Services page` instead of separate `pageManager` + `pageSaveHelper` fields.
- The Selenide IT suite + the wikantik-it-test-rest cargo flow + the manual save→render verification all pass.
- Phase 6 (RenderingSubsystem) plan can begin against the new Page foundation.
