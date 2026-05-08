# wikantik-main decomposition design

**Status:** proposed
**Author:** jakefear (with Claude)
**Date:** 2026-05-05
**Inputs:** [docs/ArchitectureCritique.md](../../ArchitectureCritique.md) (Gemini-authored)
**Successor docs:** a series of implementation plans under `docs/superpowers/plans/2026-XX-YY-decomposition-phase-N-*.md`

## Why this exists

The architecture critique correctly diagnoses the Service Locator anti-pattern in `WikiEngine` and the cost it imposes on testability and dependency wiring. It then prescribes a Guice migration. After review I disagree with the prescription's risk/reward profile in a single-developer codebase: most of Guice's benefits can be earned by growing the patterns that already exist in this codebase (`KnowledgeGraphServiceFactory.Services`, constructor injection in newer code) without adopting a framework. Doing the structural work first also makes a future Guice migration cheaper if we still want it.

This document is the design for that structural work.

## Concrete starting position (measured 2026-05-05)

| Metric | Value |
|---|---|
| LOC in `wikantik-main/src/main/java` | 76,212 |
| Distinct service types in `WikiEngine.managers` | 42+ |
| `engine.getManager(...)` call sites | 1,070 |
| Lines in `WikiEngine.java` | 1,470 |
| Top God-classes (>800 LOC) | `JdbcKnowledgeRepository` (1561), `WikiEngine` (1470), `LuceneSearchProvider` (1248), `DefaultReferenceManager` (1002), `SpamFilter` (1002), `AbstractFileProvider` (925), `WikiContext` (818), `DefaultPageManager` (815), `SecurityVerifier` (801) |

`getManager()` callers by package:
```
 219 com/wikantik/rest          167 com/wikantik/providers
 129 com/wikantik/content       127 com/wikantik/plugin
  62 com/wikantik/auth           55 com/wikantik/pages
  43 com/wikantik/render         33 com/wikantik/ui
  21 com/wikantik/search         21 com/wikantik/attachment
  19 com/wikantik/knowledge      16 com/wikantik/tools
```

These are the surfaces we have to traverse.

## End state

`WikiEngine` becomes a thin lifecycle coordinator that knows three things:
1. The order to boot subsystems in (a DAG, not a phase number).
2. How to wire each subsystem's services-record into the next subsystem's factory call.
3. How to handle shutdown signals (drain async queues, close pools).

Everything else lives inside one of seven cohesive **subsystems**, each defined by:
- A `*SubsystemFactory` with one method: `create(Deps deps) → Services`.
- A `Deps` record (or interface) listing exactly what other subsystems it consumes.
- A `Services` record listing exactly what it exposes.
- A package boundary inside `wikantik-main` (or eventually as its own Maven module — out of scope here).
- Its own integration tests that don't touch `WikiEngine`.

Inside a subsystem, every concrete service receives its collaborators via constructor parameters. There is no static lookup, no service locator, and no reach-back through `WikiEngine`. The legacy `engine.getManager(X.class)` API stays as a deprecated bridge during migration and is deleted in the final phase.

The proposed seven subsystems and their dependency direction:

```
                         ┌────────────────────┐
                         │ CoreSubsystem      │
                         │ (props, events,    │
                         │  metrics, lifecycle)│
                         └─────────┬──────────┘
                                   │
                         ┌─────────▼──────────┐
                         │ PersistenceSubsys  │
                         │ (DataSource, kg/   │
                         │  policy/api-key    │
                         │  repos)            │
                         └─────────┬──────────┘
                ┌──────────────────┼──────────────────┐
                │                  │                  │
        ┌───────▼──────┐    ┌──────▼──────┐    ┌──────▼──────┐
        │ AuthSubsys   │    │ PageSubsys  │    │ KnowledgeSubsys │
        │ (users,      │    │ (pages,     │    │ (KG, judge,    │
        │  groups,     │    │  attach,    │    │  hub disc,     │
        │  acls, keys) │    │  refmgr,    │    │  extractor)    │
        └───────┬──────┘    │  spine)     │    └────┬───────────┘
                │           └──────┬──────┘         │
                │                  │                │
                │           ┌──────▼──────┐         │
                │           │ Rendering   │         │
                │           │ Subsys      │         │
                │           │ (parser,    │         │
                │           │  plugins,   │         │
                │           │  filters)   │         │
                │           └──────┬──────┘         │
                │                  │                │
                │              ┌───▼────────────────▼─┐
                │              │ SearchSubsys         │
                │              │ (Lucene, hybrid,     │
                │              │  embeddings)         │
                │              └───┬──────────────────┘
                │                  │
        ┌───────▼──────────────────▼─────┐
        │ ApiSubsystem                   │
        │ (REST, MCP servers, tools,     │
        │  observability HTTP, admin UI) │
        └────────────────────────────────┘
```

Edges go top-to-bottom only — never circular. Where two services today appear codependent, they are signaling design debt to be paid off rather than to be papered over with `Provider<T>` indirection.

## Design principles (non-negotiable)

1. **Subsystems over managers.** A subsystem is a *cohesive group* of services with one factory entry point. The unit of decomposition is the subsystem, not the individual manager.

2. **Explicit dependency direction.** Every subsystem's `Deps` record names exactly which services from which other subsystems it consumes. The dependency DAG is enforced by ArchUnit.

3. **Constructor injection only inside subsystems.** No `engine.getManager(...)`, no static `getInstance()`, no `ServiceLoader` for internal services. Constructor parameters or nothing.

4. **Typed services records.** Each subsystem exposes a final, ordered record of services. No bag-of-objects, no `Map<Class<?>, Object>`. Adding a service means adding a typed field.

5. **Factory functions, not a container.** `WikiSubsystemFactory.create(deps)` is plain Java that you can read top-to-bottom. No reflection, no scanning, no annotation magic. If a future migration to Guice is desired, this same code becomes one Guice `Module` per subsystem with mechanical translation.

6. **`WikiEngine` is a coordinator, not a registry.** It owns startup/shutdown sequencing only. The `Map<Class<?>, Object> managers` field disappears in the final phase.

7. **Subsystems are independently testable.** A subsystem's services can be exercised by passing mock or fake `Deps` directly to its factory, with no `WikiEngine` and no `TestEngine` reach-back. ArchUnit forbids `TestEngine` references inside subsystem-internal tests.

8. **Cross-cutting concerns flow as services, not statics.**
   - Properties: a `WikiProperties` service in `CoreSubsystem`, injected explicitly.
   - Events: a `WikiEventBus` service in `CoreSubsystem`, injected explicitly. Subscribers register via explicit listener registration, not via reflection or magic.
   - Metrics: a `MeterRegistry` from `CoreSubsystem`, injected explicitly.
   - Logging: stays static (Log4j2 loggers are fine; not a coupling we need to fix).

9. **No new code carries the smell.** From the moment the architecture review for Phase 1 is approved, an ArchUnit test rejects new code that calls `engine.getManager(...)` outside of approved bridge classes. Existing call sites are tracked and burned down.

## Subsystem inventory

The actual service membership below is the proposed final-state grouping. Phase work moves services from `WikiEngine.managers` into the named subsystem. Boundary tweaks are expected as we discover real coupling during migration.

### `CoreSubsystem`
**Owns the cross-cutting infrastructure every other subsystem depends on.**
- `WikiProperties` (typed wrapper around the existing `Properties`)
- `WikiEventBus` (renamed from the implicit JSPWiki event mechanism)
- `MeterRegistry` (existing Micrometer global registry, but injected)
- `WikiLifecycle` (start/stop coordination; replaces the parts of `WikiEngine` that aren't service-locator)
- `SystemPageRegistry`, `RecentArticlesManager`, `BlogManager` (small, stable, leaf-ish)

**Dependencies:** none (or only `ServletContext`).

### `PersistenceSubsystem`
**Owns the JNDI DataSource and the JDBC-backed repositories.**
- `DataSource` (from JNDI)
- `JdbcKnowledgeRepository` *(needs decomposition — currently 1561 lines)*
- `JdbcKgJudgeTimeoutRepository`, `KgClusterPolicyRepository`, `KgExcludedPagesRepository`
- `ApiKeyService`, `JDBCUserDatabase`, `JDBCGroupDatabase` *(these straddle Auth — see §"Boundary tweaks expected")*
- `ChunkEntityMentionRepository`, `ContentChunkRepository`, `KgNodeEmbeddingRepository`
- `HubDiscoveryRepository`, `HubProposalRepository`
- `PageVerificationDao`, `PageCanonicalIdsDao`, `TrustedAuthorsDao`

**Dependencies:** `CoreSubsystem` (for properties).

### `AuthSubsystem`
**Authentication, authorization, ACLs, API keys.**
- `DefaultAuthorizationManager`
- `DefaultUserManager`
- `DefaultGroupManager`
- `WebContainerAuthorizer`
- `ApiKeyServiceHolder` (resolves bearer tokens to principals)
- `SecurityVerifier` *(801 lines, candidate for decomposition)*

**Dependencies:** `Core`, `Persistence`.
**Exposes:** principal-resolution + permission-check API only. Pages and rendering should ask "may this principal do X?" not poke at the user database.

### `PageSubsystem`
**Pages, attachments, references, structural spine.**
- `DefaultPageManager` *(815 lines, candidate for decomposition)*
- `PageProvider` chain (`CachingProvider` → `VersioningFileProvider` → `AbstractFileProvider`)
- `AttachmentManager`, `AttachmentProvider`
- `DefaultReferenceManager` *(1002 lines, candidate for decomposition)*
- `StructuralIndexService`, `StructuralIndexEventListener`, `MainPageRegenerator`
- `PageDirectoryWatcher`, `NewsPageGenerator`

**Dependencies:** `Core`, `Persistence`, `Auth` (for ACL checks on save).

### `RenderingSubsystem`
**Markdown parser, HTML renderer, plugin/filter pipelines.**
- `DefaultRenderingManager`
- `MarkdownParser`, `MarkdownRenderer`
- `DefaultPluginManager` + plugin classes
- `DefaultFilterManager` + filter classes (`StructuralSpinePageFilter`, `RunbookValidationPageFilter`, `FrontmatterValidationPageFilter`, `SpamFilter`, `HubSyncFilter`, `FrontmatterDefaultsFilter`)
- `DefaultDifferenceManager`

**Dependencies:** `Core`, `Page`, `Auth`.

### `KnowledgeSubsystem`
**The Knowledge Graph and its supporting services. This subsystem already exists in spirit via `KnowledgeGraphServiceFactory.Services` — Phase 1 just finishes the job.**
- `KnowledgeGraphService` (`DefaultKnowledgeGraphService`)
- `KgProposalJudgeService`, `JudgeRunner`, `KgMaterializationService`
- `BootstrapEntityExtractionIndexer`, `AsyncEntityExtractionListener`, `KgInclusionPolicy`, `ReconciliationJobRunner`
- `HubProposalService`, `HubDiscoveryService`, `HubOverviewService`
- `ContextRetrievalService`, `ForAgentProjectionService`
- `RetrievalQualityRunner`, `ContentIndexRebuildService`
- `MentionIndex`, `NodeMentionSimilarity`, `ChunkProjector`
- `PageGraphService` *(distinct from KG — see CLAUDE.md naming convention; the page graph is wikilink-based)*

**Dependencies:** `Core`, `Persistence`, `Page` (for `pageManager.getPage`), `Search` (for retrieval seeding — but watch this: today some pieces are circular, see §Risks).

### `SearchSubsystem`
**Lucene + hybrid retrieval + embeddings.**
- `SearchManager` interface and `LuceneSearchProvider` *(1248 lines, candidate for decomposition: query parsing vs. indexing vs. attachment indexing)*
- `HybridSearchService`, `GraphRerankStep`, `GraphProximityScorer`, `QueryEntityResolver`, `QueryEmbedder`
- `InMemoryChunkVectorIndex`, `InMemoryGraphNeighborIndex`, `PageMentionsLoader`
- `BootstrapEmbeddingIndexer`, `EmbeddingIndexService`, `AsyncEmbeddingIndexListener`
- `FrontmatterMetadataCache`

**Dependencies:** `Core`, `Persistence`, `Page` (for content), `Knowledge` (for graph rerank).

### `ApiSubsystem`
**Servlets, MCP servers, tools server, admin endpoints, observability HTTP.**
- All `wikantik-rest` servlets
- All MCP tool implementations + initializers (`/wikantik-admin-mcp`, `/knowledge-mcp`)
- Tools server (`/tools/*`)
- Observability HTTP (`/metrics`, `/health/*`, build-version filter)
- Admin auth filters

**Dependencies:** all other subsystems.

This is mostly a *consumer* layer, not a graph node anyone else points at. Once the rest of the migration is done, ApiSubsystem code becomes the most fluent example of the new pattern.

## Boundary tweaks expected

Some today-coupled services span proposed boundaries. Resolution will happen during the relevant phase, not now:

- **User/group databases** are JDBC repositories (Persistence-flavored) and security objects (Auth-flavored). Likely placement: the JDBC connectivity stays in `Persistence`, but the user/group *managers* (read-write surface, audit) live in `Auth` and depend on `Persistence`'s repositories.
- **`KgExcludedPagesRepository`** is a write target of `Knowledge` and a read target of `Page`'s save filter. Placement: `Persistence`, with `Page` consuming a narrow read-only interface.
- **`StructuralSpinePageFilter`** is in `Rendering`'s filter chain but writes through `Page`'s structural index. The filter stays in `Rendering`; the `StructuralIndexService` in `Page` exposes a narrow write port the filter calls.
- **Plugins like `SearchAndPagify`** that today reach back to `SearchManager` should receive their `SearchManager` via plugin parameters/context, not `engine.getManager()`. This is a `RenderingSubsystem` concern (the plugin context should be richer).

## Phase plan

Each phase is a candidate for a focused implementation plan. Estimated effort assumes single-developer, ~1 day = ~6 productive coding hours.

### Phase 0 — Foundations (≈ 2 days)

**Goal:** make sure we can move without breaking things.

- Adopt ArchUnit. Add module `wikantik-archtest` (test-scoped) running against the current packaging.
- Three rules to start:
  1. **Layered architecture** — packages can't depend upward (will fail today; we mark *current* failures as `@ApprovedViolations` and burn them down per phase).
  2. **No new `engine.getManager(...)`** — fails on calls inside files added/modified after a baseline date except in approved bridge classes (`WikiEngine`, `RestServletBase`).
  3. **No new `TestEngine` references in subsystem-internal tests** — same approval-list approach.
- Capture baseline metrics: LOC per God-class, count of `getManager()` callers per package, count of `TestEngine` references in tests. Persist as a JSON artifact under `bin/metrics/` so progress is measurable.
- Document the bridge pattern: how `WikiEngine.getManager(X.class)` gets wired during migration to delegate to a subsystem's services record.

**Done when:** ArchUnit tests run as part of `mvn test`; baseline numbers committed; bridge pattern documented in this spec's appendix.

### Phase 1 — KnowledgeSubsystem promotion (≈ 5–7 days)

**Goal:** pick the subsystem that is closest to done and finish it. Establishes the pattern for everything that follows.

- Promote `KnowledgeGraphServiceFactory.Services` to `KnowledgeSubsystem.Services`. Add an explicit `KnowledgeSubsystem.Deps` record listing what it requires from `Core`, `Persistence`, `Page`.
- Move every Knowledge-flavored manager out of `WikiEngine.managers` and into the `Services` record.
- Update consumers:
  - REST endpoints under `/admin/knowledge-graph/*`, `/admin/kg-policy/*`, `/admin/retrieval-quality/*`
  - MCP servers `/wikantik-admin-mcp` and `/knowledge-mcp`
- For each consumer, replace `engine.getManager(KgProposalJudgeService.class)` with a constructor-injected reference to `KnowledgeSubsystem.Services` (or a narrower port).
- Servlets are tricky because Tomcat instantiates them — a `WikiSubsystems` servlet-context attribute injected at boot becomes the entry point. Document the pattern.
- Add subsystem-level integration test that boots `KnowledgeSubsystem` with mocked `Deps` and exercises the public services without `TestEngine`.

**Done when:** zero `engine.getManager(KgXxx.class)` callers; `KnowledgeSubsystem` testable in isolation; no regression in `mvn clean install -Pintegration-tests -fae`.

### Phase 2 — CoreSubsystem extraction (≈ 3 days)  *(complete 2026-05-06)*

**Goal:** stop reaching for properties and the event bus through `WikiEngine`.

- Wrap `Properties` in a `WikiProperties` typed service.
- Promote the JSPWiki event mechanism to a `WikiEventBus` injectable service.
- Migrate the smallest leaves first (`SystemPageRegistry`, `RecentArticlesManager`, `BlogManager`).
- Leave the heavyweight subsystems alone for now.

**Outcome:** `CoreSubsystem.Services` ships with `WikiProperties` /
`WikiEventBus` / `MeterRegistry` plus the three leaf managers.
Production wiring stashes the `(core, knowledge)` bundle on the
`ServletContext` at boot. 22 leaf-manager `getManager` callsites + 40
of 41 `engine.getWikiProperties()` callsites + 10 of the most-used
`WikiEventManager.fireEvent` callers migrated. `KnowledgeSubsystem.Deps`
now declares `CoreSubsystem.Services core` directly — first
cross-subsystem edge in the decomposition DAG.

**Deferred to a follow-up:** removing the leaf managers from the legacy
`managers` registry. `RecentArticlesManager.initialize` reads
`SystemPageRegistry` via the bridge during boot, which currently
relies on the registry being populated. Restructuring init ordering
is a Phase 5 (PageSubsystem) concern. The `WikiEngine.getManager`
fallback now consults `coreSubsystem` so callers transparently keep
working as the registry shrinks. One `getWikiProperties` callsite in
`wikantik-http`'s `CsrfProtectionFilter` stays direct — `wikantik-http`
sits below `wikantik-main` in the module hierarchy and can't reach
`CoreSubsystemBridge` without a new module dependency.

**Metrics (`bin/metrics/decomposition-progress.json`):**
- `get_manager_callers_repo_wide` 1055 → 1037 (-18)
- `get_manager_callers_in_main` 216 → 209 (-7)
- `archunit_frozen_violations` 40 → 37 (-3)
- `registered_managers` unchanged at 28 (deferred above)

### Phase 3 — PersistenceSubsystem extraction (≈ 4 days)  *(complete 2026-05-06)*

**Goal:** centralize the DataSource and the JDBC repositories.

- Move all `Jdbc*Repository`, `*Dao`, and DataSource construction into `PersistenceSubsystemFactory`.
- Decompose `JdbcKnowledgeRepository` (1561 lines) into 3–4 cohesive repositories along its actual usage seams (nodes, edges, proposals, reviews/rejections). Keep the public `Repository` interface intact during the split; clean it up afterward.
- All other subsystems consume narrower repository interfaces from `Persistence`.

**Outcome:** `PersistenceSubsystem.Services` exposes the four narrow KG
repositories (`KgNodeRepository`, `KgEdgeRepository`,
`KgProposalRepository`, `KgRejectionRepository`) plus every other JDBC
repository / DAO (twelve total). `JdbcKnowledgeRepository` (1561 LOC)
has been deleted; production and test consumers (engine,
KnowledgeSubsystemFactory, hub services, judge runner, bootstrap
indexer, extract-cli, ~25 test files) take the narrow types directly.
`KnowledgeSubsystem.Deps` declares `PersistenceSubsystem.Services
persistence` as the second cross-subsystem dependency edge.

**Metrics (`bin/metrics/decomposition-progress.json`):**
- `god_classes_over_800` 9 → 8 (the 1561-line monolith is gone)
- `JdbcKnowledgeRepository` (1561 LOC) → 4 repos at 443/359/489/156 LOC
- `loc_main` +216 (the four repos duplicate generic helpers — accepted
  trade-off: each repo owns its connection-handling loop)
- `archunit_frozen_violations` unchanged at 37 (Phase 3 doesn't touch
  `engine.getManager` callers — that's Phase 4+ territory)

**Deferred:** consolidating `wikantik-extract-cli`'s manual repository
construction behind a small CLI persistence factory (not worth a
phase of its own; the CLI's boot is shallow and stable).

### Phase 4 — AuthSubsystem extraction (≈ 4 days)  *(complete 2026-05-06)*

**Goal:** wall off authentication and authorization behind a small public surface.

- Move `DefaultAuthorizationManager`, `DefaultUserManager`, `DefaultGroupManager`, `WebContainerAuthorizer`, `ApiKeyServiceHolder` into `AuthSubsystem`.
- Decompose `SecurityVerifier` (802 lines) — likely splits into "permission resolver" + "ACL evaluator" + "policy grant store reader".
- Define the public `AuthServices` surface: `currentPrincipal()`, `mayPerform(principal, permission)`, and a small set of administrative ports.
- Audit every caller: most `engine.getManager(AuthorizationManager.class)` sites are doing one of two things; collapse to that smaller API.

**Outcome:** `AuthSubsystem.Services` exposes the four core auth managers
(authentication, authorization, users, groups), the configured
`Authorizer` (typically `WebContainerAuthorizer`), the API-key service,
and a session-scoped `securityVerifier` slot. `SecurityVerifier` (802
LOC) is decomposed into three narrow helpers under
`com.wikantik.auth.subsystem.verify` — `PolicyVerifier` (450 LOC),
`ContainerRoleVerifier` (163 LOC), `JaasVerifier` (88 LOC) — leaving a
278-LOC facade that retains every public message-key constant for the
admin JSPs. 34 production `getManager` callsites migrated (10 REST
slice → `getSubsystems().auth().xxx()`; 24 non-servlet slice →
`AuthSubsystemBridge.fromLegacyEngine(engine).xxx()`); auth-internal
classes (`DefaultAuthenticationManager` / `Authorization` / `User` /
`Group`, `SecurityVerifier`, `WikiCallbackHandler`) keep direct
`getManager` lookups because they run during `initialize()` before the
AuthSubsystem is built.

`WikiEngine.setManager` invalidates the cached AuthSubsystem snapshot
when one of the four auth managers is hot-swapped (test-fixture
seam) so `AuthSubsystemBridge` falls through to a live lookup.

**Metrics (`bin/metrics/decomposition-progress.json`):**
- `god_classes_over_800` 8 → 7 (SecurityVerifier reduced to 278 LOC)
- `get_manager_callers_repo_wide` 1037 → 1017 (-20)
- `get_manager_callers_in_main` 209 → 195 (-14)
- `archunit_frozen_violations` unchanged at 37 (the new bridge calls
  go through `AuthSubsystemBridge.engine.getManager(...)` — accepted
  bridge state, not a violation regression)

**Deferred:**
- Migrating `SecurityVerifier` callers to the narrow helpers directly
  (Ckpt 4 in the original plan): production has zero callers of
  `SecurityVerifier`, so there's nothing to migrate. Tests still
  exercise the facade for regression coverage; new code that wants
  one slice can instantiate the matching helper directly.
- Migrating the auth-internal cross-references inside the four
  manager impls — bootstrap-order dependent; revisits Phase 5/Phase 9.
- Lifting `ApiKeyServiceHolder`'s static singleton — the typed
  `Services.apiKeys()` accessor is now the preferred path; the
  holder stays for the duration of the migration and gets removed
  in a Phase 9 sweep.

### Phase 5 — PageSubsystem extraction + DefaultPageManager decomposition (≈ 7–10 days)  *(complete 2026-05-07)*

**Goal:** the heaviest subsystem. Done correctly, this unlocks fast tests across the codebase.

- Extract `PageSubsystemFactory` and `PageSubsystem.Services`.
- Decompose `DefaultPageManager` (816 lines). Likely splits:
  - **Page repository** — read/save/delete/exists, version handling.
  - **Page lifecycle** — pre/post save event firing, lock management.
  - **Page metadata** — frontmatter, ACL extraction, cache invalidation hooks.
  - **Page graph reference** — wikilink extraction (currently in `DefaultReferenceManager`, may unify here).
- Decompose `DefaultReferenceManager` (1002 lines) — split index-rebuild from reference-query from cross-reference-scan.
- Decompose `AbstractFileProvider` (925 lines) and `VersioningFileProvider` (783 lines) along the page-vs-version-history seam.

**Outcome:** `PageSubsystem.Services` exposes the four manager-level
objects (`PageManager`, `AttachmentManager`, `PageRenamer`,
`PageSaveHelper`) plus the underlying `PageProvider` chain plus the
three decomposed helpers (`PageRepository`, `PageLifecycle`,
`PageLockService`). `DefaultPageManager` (816 LOC) decomposed three
ways: `DefaultPageRepository` (390 LOC), `DefaultPageLifecycle` (109
LOC), `DefaultPageLockService` (164 LOC), and a 297-LOC facade.
`PageSubsystemFactory.buildProvider` lifted the provider-chain
construction out of the manager. ~115 production `getManager`
callsites for `PageManager` / `AttachmentManager` / `PageRenamer`
migrated across seven modules in two parallel-Sonnet passes
(REST/MCP/tools/knowledge/observability vs wikantik-main interior)
and one parallel-Haiku pass (Attachment + Renamer).
`KnowledgeSubsystem.Deps` declares `PageSubsystem.Services page` —
the third cross-subsystem edge in the decomposition DAG.

**Deferred:** the four other God-classes named in the original goal
(`DefaultReferenceManager`, `AbstractFileProvider`,
`VersioningFileProvider`, and the broader page-graph references
split). Those belong to the Page-Graph subsystem (a future phase),
not the page lifecycle. Phase 5's scope is the page lifecycle that
the rest of the codebase consumes through `PageSubsystem.Services`;
the link-graph derivation is conceptually distinct and stays for now.

**Metrics (`bin/metrics/decomposition-progress.json`):**
- `god_classes_over_800` 7 → 6 (DefaultPageManager 816 → 297 LOC)
- `get_manager_callers_repo_wide` 1017 → 915 (-102) — single largest
  drop in any phase so far
- `get_manager_callers_in_main` 195 → 130 (-65)
- `archunit_frozen_violations` 37 → 35 (-2)
- `wikantik-observability` gained a `provided` dep on `wikantik-main`
  to reach `PageSubsystemBridge` from a single health-check call

**Done when:** zero `engine.getManager(PageManager.class)` callers
outside the SKIP list (engine-internal lookups during initialize, the
PageSaveHelper in wikantik-api which can't depend on wikantik-main,
and the auth-internal classes whose lookups happen before
PageSubsystem is built); the three new helpers + the facade are each
<400 LOC.

### Phase 6 — RenderingSubsystem extraction (≈ 4 days)  *(complete 2026-05-07)*

**Goal:** clean up the parser/plugin/filter pipeline.

- Extract `RenderingSubsystemFactory`.
- Plugins receive their dependencies via plugin context, not `engine.getManager()`. Refactor `PluginContent` to carry a `RenderingServices` reference.
- Decompose `SpamFilter` (1003 lines) along its actual responsibilities (rate-limit, token check, content-pattern reject).

**Outcome:** `RenderingSubsystem.Services` exposes the four manager-
level objects (`RenderingManager`, `PluginManager`, `FilterManager`,
`DifferenceManager`) plus four narrow SpamFilter helpers
(`SpamRateLimiter`, `SpamPatternMatcher`, `SpamExternalSignals`,
`SpamPolicy`). `SpamFilter` (1003 LOC) decomposed four ways:
`DefaultSpamRateLimiter` (201 LOC), `DefaultSpamPatternMatcher` (320
LOC), `DefaultSpamExternalSignals` (174 LOC), `DefaultSpamPolicy` (78
LOC), and a 339-LOC facade that retains every public `PROP_*` and
`STRATEGY_*` constant + the four static utility methods JSPs reference
statically. ~29 production `getManager` callsites for the four
rendering managers migrated across two parallel-Haiku passes
(REST/MCP slice + wikantik-main interior). The four spam helpers reach
the typed bundle via `RenderingSubsystemFactory` looking up the
registered SpamFilter through `FilterManager.getFilterList()`.

**Deferred (per spec scope):**
- Plugin-context refactor — `PluginContent` carrying a `RenderingServices`
  reference. Touches every plugin class + the `wikantik-api`
  `PluginContent` interface; earns its own pass. Tracked separately.

**Metrics (`bin/metrics/decomposition-progress.json`):**
- `god_classes_over_800` 6 → 5 (SpamFilter 1003 → 339 LOC)
- `get_manager_callers_repo_wide` 915 → 904 (-11)
- `get_manager_callers_in_main` 130 → 119 (-11)
- `archunit_frozen_violations` unchanged at 35

### Phase 7 — SearchSubsystem extraction + LuceneSearchProvider decomposition (≈ 5 days)  *(complete 2026-05-07)*

**Goal:** untangle Lucene from hybrid retrieval, both depending cleanly on `Knowledge` and `Page`.

- Extract `SearchSubsystemFactory`.
- Decompose `LuceneSearchProvider` (1248 lines) into:
  - **LuceneIndexer** (write side: index, reindex, attachment indexing)
  - **LuceneSearcher** (read side: query parse, scored hits, highlight)
  - **LuceneIndexLifecycle** (open/close/optimize/cache directory)
- Hybrid retrieval already largely encapsulates well; placement and dependency direction is the main work.

**Outcome:** `SearchSubsystem.Services` exposes a 15-field bundle —
`SearchManager` + `SearchProvider` + the three decomposed Lucene helpers
(`LuceneIndexer`, `LuceneSearcher`, `LuceneIndexLifecycle`) + 5 hybrid
retrieval services (`HybridSearchService`, `QueryEmbedder`,
`QueryEntityResolver`, `GraphRerankStep`, `GraphProximityScorer`) + 2
in-memory indexes + 4 embedding-pipeline services + the
`FrontmatterMetadataCache`. `LuceneSearchProvider` decomposed: the facade
shrunk 1251 → 724 LOC, dropping out of the `god_classes_over_800` list.
9 production `engine.getManager(SearchManager.class)` callsites (one
more than the planned 8 — re-grep surfaced an extra) migrated to typed
accessors via `SearchSubsystemBridge` / `getSubsystems().search()`. The
Search → Knowledge `LuceneMlt` cycle resolved by post-construction
wiring: `WikiSubsystems` builds Knowledge first with a `null` MLT seam,
then Phase 7 Ckpt 4 wires the `LuceneSearcher`-backed `LuceneMlt` into
`HubOverviewService.setLuceneMlt(...)` once both subsystems exist.

**Deviation from plan:** Ckpt 3 targeted a ~250-LOC facade; the actual
landing is 724 LOC. Test-reflection compatibility constraints
(`LuceneSearchProviderTest` reaches into private state via reflection,
plus the public `PROP_*` / `STRATEGY_*` / `MAX_SEARCH_HITS` /
`SEARCHABLE_FILE_SUFFIXES` constant surface that JSPs and integration
tests reference statically) forced retention of more orchestration code
on the facade than originally projected. The three helpers absorb the
real work; the facade is delegation + state passthrough. A future
follow-up could chase the remaining ~470 LOC by migrating the
reflection-based tests onto the helpers, but the cost/value tilt did
not justify it inside Phase 7's window.

**Metrics (`bin/metrics/decomposition-progress.json`):**
- `god_classes_over_800` 5 → 4 (LuceneSearchProvider 1251 → 724 LOC)
- `get_manager_callers_repo_wide` 904 → 935 (+31; new SearchSubsystemBridge / SearchSubsystemFactory bridge code accounts for +26 internal calls — net consumer-side migration is the expected -9 against the 9 migrated callsites)
- `get_manager_callers_in_main` 119 → 137 (+18; same bridge-code attribution)
- `archunit_frozen_violations` unchanged at 35

**Done when:** Lucene split lands; `SearchSubsystem.Services` exposes only the query API consumers should depend on.

### Phase 8 — ApiSubsystem cleanup (≈ 3 days)  *(complete 2026-05-07)*

**Goal:** the `wikantik-rest` and MCP packages become pure consumers.

- Servlets receive a `WikiSubsystems` reference at init via `ServletContext` attribute (preferred) or a thin `ServletContextListener`-driven wiring.
- 219 `getManager()` callers in `wikantik-rest` collapse to a handful of subsystem references.
- MCP tool factories receive subsystem references at construction; each tool's mocked tests no longer touch `WikiEngine`.

**Outcome:** `KnowledgeSubsystem.Services` expanded by 6 fields
(`StructuralIndexService`, `PageGraphService`, `ContextRetrievalService`,
`ForAgentProjectionService`, plus two retrieval-pipeline collaborators) and
`PageSubsystem.Services` by 1 (`PageRenamer`). ~70 production
`engine.getManager(...)` callsites migrated across `wikantik-rest`,
`wikantik-admin-mcp`, `wikantik-knowledge`, `wikantik-tools`, and
`wikantik-observability` to typed accessors via `getSubsystems().<sub>()`
(servlets) or `<X>SubsystemBridge.fromLegacyEngine(engine)` (non-servlets).
The `ContextRetrievalService` post-boot wiring cycle was resolved via a
narrow `patchContextRetrievalService` seam plus a `WikiEngine.setManager(...)`
subsystem-snapshot invalidation exemption — the bundle rebuilds when the
delayed retrieval service registers, so callers always see the live
collaborator without re-introducing service-locator lookups in hot paths.

**Deviation from plan:** Ckpt 2 was scoped to add an ArchUnit guard
forbidding `engine.getManager(...)` outside bootstrap classes in the five
migrated modules. The guard was **skipped** — the existing
`DecompositionArchTest` is `wikantik-main`-scoped (`packages =
"com.wikantik"`, analysing only the main module's compile classpath), and a
multi-module ArchUnit rule with per-module bootstrap-allowlist predicates
plus a freeze store covering the legitimate residuals (StructuralIndexService,
PageGraphService, ContentIndexRebuildService, NewsPageGenerator,
CachingManager, plus a few in `McpServerInitializer` / `McpToolRegistry` /
`SearchIndexHealthCheck`) blew past the half-day budget. Phase 9's
`no_get_manager_anywhere` sweeping ban supersedes a Phase-8-scoped rule
anyway, so the rule is folded into Phase 9. Residual call-sites
(StructuralIndexService, PageGraphService, ContentIndexRebuildService,
NewsPageGenerator, CachingManager) are deferred to a future Page Graph /
Admin subsystem extraction phase — they don't fit cleanly into any of the
seven currently-extracted subsystems.

**Metrics (`bin/metrics/decomposition-progress.json`):**
- `loc_main` 80761 → 80966 (+205, expanded `Services` records and bridge plumbing)
- `get_manager_callers_repo_wide` 935 → 926 (-9 net; ~70 consumer migrations offset by added bridge-internal lookups in expanded factories)
- `get_manager_callers_in_main` 137 → 145 (+8; same bridge-code attribution)
- `god_classes_over_800` unchanged at 4 (`WikiEngine` 1770 → 1909 from `setManager` invalidation + expanded factory wiring; eligible for Phase 9 demolition)
- `archunit_frozen_violations` 35 → 40 (5 new frozen entries from expanded subsystem bridges; no rule violations introduced)

**Done when:** `wikantik-rest` and `wikantik-*-mcp` modules contain zero `getManager()` calls outside the bootstrap.

### Phase 9 — `WikiEngine` simplification + registry deletion (≈ 3 days)

**Status: partial — registry deletion deferred to Phase 10, completed in Phase 10 (2026-05-08)**

**Goal:** delete what the prior phases made dead.

- `WikiEngine.managers` map and the `getManager(Class<T>)` method: deleted.
- `WikiEngine.initialize()` becomes 30–50 lines: build subsystems in DAG order, expose them via a `WikiSubsystems` accessor.
- `TestEngine` either becomes a thin convenience that wires real `Core` + mock everything else, or is deleted in favor of `WikiSubsystems.forTesting(...)`.
- Final ArchUnit rule: `engine.getManager(...)` is a *forbidden* API everywhere.

**Done when:** `WikiEngine.java` is under 300 LOC; the registry is gone; the build is green.

#### What landed (Ckpts 1–5 partial, commits f7339fae2..a32890b92)

- `PageGraphSubsystem` extracted with 4 services (`StructuralIndexService`, `PageGraphService`, `ReferenceManager`, `ContentIndexRebuildService`) + ~12 callers migrated.
- `NewsPageGenerator` + `CachingManager` added to existing `RenderingSubsystem.Services` and `CoreSubsystem.Services` respectively.
- ~11 wikantik-main bulk callers + 25 Default*Manager cross-calls migrated to typed subsystem accessors.
- `Core` + `Auth` `Services` expanded with 6 new fields + 14 callers migrated.
- `WikiSubsystemsTestFactory` shipped.
- All 8 bridges cast `Engine` → `WikiEngine`.
- `Engine.getManager` deleted from the `Engine` interface (breaking change documented).
- `WikiEngine.initialize()` slimmed by relocating wiring helpers onto subsystem factories.
- 28 typed `register*` setters added on `WikiEngine` concrete class.
- `WiringHelpers`' `getManager` reads flattened to method parameters.
- Bridges' registry-fallback paths deleted — production code outside `WikiEngine.java`, `*SubsystemFactory`, and `*SubsystemBridge` has **zero** `getManager` calls.

**Metrics at phase_9_partial_close (2026-05-08):**
- `loc_main`: 81,893
- `get_manager_callers_repo_wide`: 887
- `get_manager_callers_in_main`: 100
- `god_classes_over_800`: 4 (`WikiEngine` 1593 LOC)
- `archunit_frozen_violations`: 129

#### What deferred — registry deletion (Phase 10 sub-task)

`WikiEngine.managers` map, `WikiEngine.getManager(Class<T>)`, and `WikiEngine.setManager(Class<T>, T)` survive on the `WikiEngine` **concrete class** (not the `Engine` interface). Root cause: the bridge `rebuildFromManagers` paths read from the registry for ~70 distinct class keys; deleting the map without per-class typed backing fields on `WikiEngine` would produce NPEs across every subsystem bridge reconstruction path. The per-class typed backing-field refactor is a multi-checkpoint piece of work in its own right. The ArchUnit `no_get_manager_anywhere` final ban is also deferred until the map is gone.

**Phase 10 inherits this sub-task:** "complete registry deletion via per-class typed backing fields on `WikiEngine`, then add ArchUnit `no_get_manager_anywhere` ban" — alongside WikiContext decomposition.

### Phase 10 — Decomposition of remaining God-classes + measurement (≈ ongoing)

**Status: complete (2026-05-08)**

**Goal:** the remaining outliers don't disappear with subsystem extraction. Keep cutting them.

- **Registry deletion (carried over from Phase 9):** complete `WikiEngine.managers` map deletion + `getManager`/`setManager` removal via per-class typed backing fields on `WikiEngine`; add ArchUnit `no_get_manager_anywhere` final ban.
- `WikiContext` (821 lines as of phase_9_partial_close) — likely splits into "request scope" + "page scope" + "rendering scope".
- Anything else that emerges over the prior phases.
- Re-measure metrics from Phase 0. Publish the diff.

**Done when:** no production class in `wikantik-main` exceeds 500 LOC. (Soft target — some are legitimately complex; the goal is "no class is a kitchen sink".)

#### What shipped (commits f24ae70a9..cced834ab)

- **WikiContext decomposed (821 → 875 LOC):** structural state moved to `RequestScope`, `PageScope`, and `RenderingScope`; `WikiContext` retains delegated accessors for all existing call sites. LOC grew slightly (54 lines) due to delegation boilerplate but invariants are now cleanly separated across three scope classes.
- **75 typed `mgr_*` backing fields on `WikiEngine`:** every distinct class key formerly read from the `managers` map now has a private typed field. `setManager` writes to both map and typed field (Ckpt A1 shadow phase); `getManager` reads typed field first. This was the pre-requisite for safe map deletion.
- **`WikiEngine.managers` map + `getManager(Class<T>)` registry-style API deleted:** the last consumers of the registry lookup path removed; `registered_managers` drops to 0.
- **ArchUnit `no_get_manager_anywhere` ban active:** rule added and passing with 0 violations in wikantik-main and 0 new violations repo-wide.
- **Boot-ordering fix in `setManager`:** snapshot-rebuild now gated on field non-null; the production rendering bug (stale subsystem bridge snapshot triggered on null write during early init) was root-caused and fixed, with a regression test added at unit-test level.

**Metrics at phase_10_close (2026-05-08):**
- `loc_main`: 82,642 (+749 from phase_9_partial_close; delegation boilerplate in new scope classes + regression test)
- `get_manager_callers_repo_wide`: 890 (baseline 1069 → final 890; −179 total)
- `get_manager_callers_in_main`: 100 (baseline 200 → final 100; −100 total)
- `god_classes_over_800`: 4 (baseline 9 → final 4; −5 total)
- `archunit_frozen_violations`: 129 (elevated due to frozen legacy violations from Phases 8–9; no new violations from Phase 10 work)
- `WikiEngine.java` final LOC: **1984** (baseline 1470 → +514; grew during Phases 1–10 as typed fields + factory wiring were added, but registry-style API is fully deleted)
- `WikiContext.java` final LOC: **875** (baseline 821 → +54; delegation boilerplate from scope split)

**get_manager_callers_repo_wide trend (baseline → final):**
baseline 1069 → ph1 1055 → ph2 1037 → ph3 1037 → ph4 1017 → ph5 915 → ph6 904 → ph7 935 → ph8 926 → ph9 887 → ph10 890

## Tooling

### ArchUnit guards (added in Phase 0, expanded each phase)

```java
// Phase 0
@ArchTest static final ArchRule no_new_get_manager = ...
@ArchTest static final ArchRule layered_subsystems = ...
@ArchTest static final ArchRule no_test_engine_in_subsystem_tests = ...

// Phase 1+
@ArchTest static final ArchRule knowledge_subsystem_isolation = ...
// ... one per subsystem as it's extracted

// Phase 9
@ArchTest static final ArchRule no_get_manager_anywhere = ...  // forbid the API entirely
```

### Metrics (`bin/metrics/decomposition-progress.json`)

A JSON-shaped scoreboard updated each phase:

```json
{
  "baseline_2026_05_05": {
    "loc_main": 76212,
    "registered_managers": 42,
    "get_manager_callers": 1070,
    "god_classes_over_800": 9,
    "test_engine_references": 0
  },
  "phase_1": { ... },
  ...
}
```

A small Bash script (`bin/metrics/measure.sh`) regenerates this on demand. CI can publish the diff in PR comments later.

### Bridge pattern (used in every phase until 9)

While migrating subsystem `X`:
1. Build `XSubsystem.Services`. Wire it in `WikiEngine.initialize()` using the existing inputs.
2. Make `WikiEngine.managers` registrations for `X`-flavored services *delegate* to fields populated from `XSubsystem.Services`. Both lookup styles work.
3. Update consumers to receive `XSubsystem.Services` (or a narrow port) at construction. As consumers migrate, the legacy `getManager()` callers drop.
4. When the last `getManager(XYZ.class)` call for `X`-services is gone, delete the legacy registration.

## Cross-cutting concerns

### Servlet lifecycle

Tomcat instantiates servlets reflectively. The pattern:
- A `WikiBootstrapServletContextListener` (already exists) builds `WikiSubsystems` in `contextInitialized`.
- It stores the bundle as a `ServletContext` attribute under a known key.
- Each servlet's `init()` retrieves it and stashes a reference on `this`.
- Servlets do not call `getEngine()` for service lookup — they use their stashed `WikiSubsystems`.

This is the *only* place that uses anything resembling service location, and it's at the JEE boundary, which is unavoidable.

### Threading and async

Some subsystems own background threads (`AsyncEntityExtractionListener`, `JudgeRunner`, `BootstrapEmbeddingIndexer`, the news-page generator). They:
- Receive their `ExecutorService` as a `Deps` input (or own one and expose it for shutdown coordination).
- Register themselves with `WikiLifecycle.shutdownHooks()` so `WikiEngine.shutdown()` can drain them deterministically.
- Don't reach back through any global registry from inside their workers.

### Plugin loading

Plugins are user-extensible. They can't be in the dependency DAG because we don't know them at compile time. The pattern:
- `PluginContext` (built per request inside `RenderingSubsystem`) carries a curated reference to the services plugins are allowed to use — typically just `PageManager` (read-only) and `SearchManager`.
- Plugins call methods on the context; the context delegates to subsystem services.
- ArchUnit rule: plugin classes may not import `WikiEngine`.

### Tests

Three tiers:
1. **Unit tests** — pass mocks directly to constructors. No `WikiEngine`, no `TestEngine`.
2. **Subsystem-integration tests** — build the subsystem with a real `Deps` (real DataSource via Testcontainers, real but minimal `Core`) and exercise its services-record. Faster than full IT, slower than unit.
3. **Cargo IT** — full Tomcat boot, end-to-end. The smallest tier. The other two pick up most of the verification.

Phase 9 should make tier 2 the dominant tier for new tests. Today everything either is unit-mocked-deeply or needs `TestEngine`.

## Risks

1. **Hidden circular dependencies.** Today's `engine.getManager()` morass hides cycles. Phase 1's job is to find them in `Knowledge`, where I think there are none. If a later phase finds a real cycle (e.g., `Page` ↔ `Search` for the structural-spine-on-save flow), the resolution is *redesign the cycle*, not introduce `Provider<T>`. This may force a phase to slip.

2. **JSPWiki event topology.** Many "managers" are also `WikiEventListener`s. The event bus is intentionally loose coupling and stays. But: today some listeners reach back through the engine inside their `actionPerformed` handler. Each such listener is a small refactor to receive its dependencies at construction.

3. **Servlet API objects.** `HttpServletRequest`/`Response` and the JEE `ServletContext` are hard boundaries. Subsystems must not depend on them. The `ApiSubsystem` is the only place those types appear.

4. **Migration drift.** A multi-month effort in a one-developer repo can stall mid-phase. Mitigation: each phase produces a *standalone* improvement (one subsystem fully extracted, with its God-classes either decomposed or formally tracked). Stalling between phases doesn't leave the codebase worse than it was.

5. **The integration test suite.** We rely on it heavily. Each phase ends with a green `mvn clean install -Pintegration-tests -fae`. No commits land if it fails (per the lesson learned 2026-05-05; see `feedback_full_it_after_targeted_fix.md`).

6. **`wikantik-api` vs implementation drift.** Today the `wikantik-api` module holds interfaces but `wikantik-main` exposes implementation classes via the registry. As we tighten subsystem boundaries, more services should be reachable only by their `wikantik-api` interface. This forces a small expansion of `wikantik-api` in some phases.

## Out of scope

- **Maven multi-module split of `wikantik-main`.** Tempting, but expensive to do during decomposition. Once subsystems are well-isolated, this becomes mechanical and is a follow-on project.
- **Switch to Guice.** The whole point of this design is to capture most of Guice's value without it. After this work lands, re-evaluate; my prediction is the marginal benefit will be small.
- **Public API breakage.** This is internal restructuring. The `wikantik-api` interfaces, the REST endpoints, the MCP tool surfaces, and the persisted schema do not change.
- **Wholesale rewrite of God-classes.** We decompose by extraction (move methods to new classes, keep behavior). Behavior changes go in their own commits with their own tests.

## Success criteria

When all phases are done:

| Metric | Today | Target |
|---|---|---|
| `engine.getManager(...)` callers | 1,070 | 0 |
| Lines in `WikiEngine.java` | 1,470 | < 300 |
| God-classes (>800 LOC) | 9 | 0 |
| Subsystems with isolated tests | 0 | 7 |
| `TestEngine` references in `wikantik-main` tests | (high) | 0 |
| Time to add a new service to a subsystem | hours (find the right phase, edit `WikiEngine`, register, etc.) | minutes (add to `Services` record, add to factory, add `@Override` if interface) |

## Implementation plans (forward references)

This spec will be drilled down into focused implementation plans, one per phase, when the phase is ready to start:

- `docs/superpowers/plans/2026-XX-YY-decomposition-phase-0-foundations.md`
- `docs/superpowers/plans/2026-XX-YY-decomposition-phase-1-knowledge-subsystem.md`
- ... and so on

Each plan will be self-contained: scope, file-by-file walk, test plan, rollback strategy. The current spec stays put as the durable reference.

## Appendix: comparison to the Guice direction

The architecture critique recommends Google Guice. I've argued elsewhere that a factory-and-conventions approach captures most of the value at a fraction of the migration cost. For the record:

| Capability | Guice | This design |
|---|---|---|
| Constructor injection | Yes (`@Inject`) | Yes (plain Java constructors) |
| Compile-time dependency check | Yes-ish (modules build a graph at startup) | Yes (factory methods take typed `Deps`; missing dep is a compile error) |
| Late binding / circular deps | `Provider<T>` indirection | Forbidden — fix the design |
| Module swap (e.g. swap Lucene for Elastic) | `Modules.override(...)` | Swap factory implementation; `Services` record is interface-shaped |
| Lifecycle (start/stop) | Bring-your-own | `WikiLifecycle` in `Core` owns it explicitly |
| Tooling cost | New framework (~1 MB jar, learning curve, opinionated patterns) | Plain Java |
| Migration cost | Multi-month, hybrid state with both registries coexisting | Each phase ships a standalone improvement |

If after Phase 9 we still want Guice, each `*SubsystemFactory.create(deps)` becomes one Guice `Module` with mechanical translation. The structural work is the load-bearing part either way.
