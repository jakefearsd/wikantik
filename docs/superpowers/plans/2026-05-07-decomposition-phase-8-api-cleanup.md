# Phase 8: ApiSubsystem cleanup — implementation plan

**Spec:** [docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md](../specs/2026-05-05-wikantik-main-decomposition-design.md)
**Status:** complete (2026-05-07)
**Estimated effort:** 1.5 days (smaller than the spec's 3-day estimate — Phases 1–7 trimmed REST faster than predicted)
**Goal:** REST + MCP + tools + observability modules contain zero `engine.getManager()` calls outside their bootstrap. Consumers reach the typed `WikiSubsystems` bundle via `getSubsystems()` (servlets) or `*SubsystemBridge.fromLegacyEngine(engine)` (non-servlets).

## Scope

Production callsite inventory (May 2026):

| Module | Production files | Notes |
|--------|-----------------|-------|
| `wikantik-rest/src/main` | 16 files (~75 callsites) | All have `RestServletBase.getSubsystems()` available |
| `wikantik-admin-mcp/src/main` | 2 callsites | `McpServerInitializer`, `McpToolRegistry` |
| `wikantik-knowledge/src/main` | 6 callsites | `DefaultContextRetrievalService`, `KnowledgeMcpInitializer` |
| `wikantik-tools/src/main` | 1 callsite | `SearchWikiTool` |
| `wikantik-observability/src/main` | 1 callsite | `SearchIndexHealthCheck` |

**Out:**
- Tests stay on the legacy API for now (Phase 9 task — `TestEngine` overhaul is its own effort).
- The bootstrap classes that *build* the subsystems (`*SubsystemFactory`, `*SubsystemBridge`, `WikiEngine.initialize`) keep their `getManager` lookups — that's where the bundle is constructed.
- No new `Subsystem.Services` records — Phase 8 is consumer migration only.

## Migration rules

- **REST resources** (`wikantik-rest/src/main`): `getSubsystems().<sub>().<x>()`. `RestServletBase.getSubsystems()` already returns the typed bundle.
- **MCP initializers + non-servlet code** (`*-mcp`, `wikantik-knowledge`, `wikantik-tools`, `wikantik-observability`): `XSubsystemBridge.fromLegacyEngine(engine).<x>()`.
- **Defensive fallback** — if the bridge returns `null` for a service that isn't required at boot (e.g. a hybrid retrieval service that's only present when properly wired), match the existing null-handling and log via `LOG.warn` per CLAUDE.md.

## Checkpoints

| Ckpt | Duration | Concurrency | Model | Description |
|------|----------|-------------|-------|-------------|
| 1 — Migrate all callsites | 1 day | single | Sonnet | All 5 modules in one commit; 1 IT reactor |
| 2 — ArchUnit + close-out | half day | single | Opus | Tighten subsystem-isolation rules; close-out metrics |

Why one Ckpt for the migration: the work is mechanical, the modules are disjoint, and running the IT reactor only once (instead of per-module) is faster and avoids port-collision risk.

### Checkpoint 1 — Migrate consumers (Sonnet)

For each of the 5 modules above, replace every production `engine.getManager(X.class)` call:
- REST → `getSubsystems().<sub>().<x>()` (already supported on `RestServletBase`)
- Non-servlet → `XSubsystemBridge.fromLegacyEngine(engine).<x>()`

For each manager class, pick the right bridge from existing precedent:
- `PageManager`, `PageRenamer`, `AttachmentManager` → `PageSubsystemBridge`
- `RenderingManager`, `FilterManager`, `PluginManager`, `EditorManager`, `TemplateManager` → `RenderingSubsystemBridge`
- `SearchManager`, `SearchProvider`, `HybridSearchService`, `GraphRerankStep`, `ChunkVectorIndex`, `FrontmatterMetadataCache` → `SearchSubsystemBridge`
- `KnowledgeGraphService`, `ContextRetrievalService`, `StructuralIndexService`, `ForAgentProjectionService` → `KnowledgeSubsystemBridge`
- `AuthorizationManager`, `AuthenticationManager`, `UserManager`, `GroupManager`, `AclManager` → `AuthSubsystemBridge`
- `VariableManager`, `Properties`, `WorkflowManager`, `ProgressManager` → `CoreSubsystemBridge` or `PersistenceSubsystemBridge`
- `ReferenceManager` → `PageSubsystemBridge` (or whichever owns it; check existing wiring)

Behaviour: zero. Cache the bridge result locally if used multiple times in a method (mirror existing patterns from Phases 4–7).

Run **the full IT reactor** before commit:
```
cd /home/jakefear/source/jspwiki && mvn clean install -Pintegration-tests -fae
```

Commit:
```
phase 8 ckpt 1: migrate all REST + MCP + tools + observability getManager callers

REST -> getSubsystems().<sub>().<x>()
non-servlet -> <X>SubsystemBridge.fromLegacyEngine(engine).<x>()
```

### Checkpoint 2 — ArchUnit guards + close-out (Opus)

1. Add an ArchUnit rule that forbids `engine.getManager(...)` calls in `wikantik-rest`, `wikantik-*-mcp`, `wikantik-tools`, `wikantik-observability` (anywhere except their bootstrap classes that explicitly construct the subsystem bridges). Use the existing `FreezingArchRule` pattern; freeze any unavoidable bootstrap exceptions.
2. Run `bin/metrics/measure.sh --label phase_8_close` (clean up `.claude/worktrees/agent-*` first per saved memory).
3. Update spec § Phase 8 status to "complete (2026-05-07)" with deltas.
4. Mark plan complete.
5. Run full IT reactor and push.

## Done when

- Zero `engine.getManager(` calls in production code under `wikantik-rest`, `wikantik-admin-mcp`, `wikantik-knowledge`, `wikantik-tools`, `wikantik-observability` (excluding bootstrap classes).
- ArchUnit guard catches new offenders.
- `get_manager_callers_repo_wide` drops by ~80 callsites.
- Phase 9 (`WikiEngine` simplification + registry deletion) plan can begin.
