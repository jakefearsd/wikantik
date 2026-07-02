# ADR-0008: Late-bound service registration via EngineServiceRegistry

**Status:** accepted (2026-07-02)

## Context
The wikantik-main decomposition (Phases 0–12, concluded 2026-05-14) left
getManager/setManager on WikiEngine as a stable service-locator, backed by 78
typed mgr_* fields + two 78-entry static dispatch maps. Each new late-bound
service needed 4–5 coordinated WikiEngine edits, and WikiEngine regrew
1909 -> 2337 LOC post-"completion". PMD flagged WikiEngine as the top efferent-
coupling hotspot (CBO 143).

## Decision
Service *storage* moves to `com.wikantik.core.registry.EngineServiceRegistry` —
a generic `Map<Class<?>,Object>` referencing no concrete service type.
getManager/setManager stay on WikiEngine as thin delegators (the 107 ArchUnit-
frozen callers and bridge paths are unchanged). The hand-curated per-class
snapshot-rebuild map (SNAPSHOT_REBUILDERS) stays in WikiEngine verbatim — it
encodes multi-subsystem, cross-package, and deliberate-no-op behavior that no
package heuristic reproduces. No Guice / DI framework; revisit only on a
demonstrated large benefit.

## How to add a late-bound service (the path forward)
1. Construct it in the owning *WiringHelper (as today).
2. Register it: `engine.setManager(MyService.class, impl);`
3. Consume via `engine.getManager(MyService.class)` or, preferably, the subsystem
   Services record.
Do NOT add a mgr_* field or register* setter to WikiEngine — ArchUnit R-5
(wikiengine_holds_no_mgr_fields, wikiengine_has_no_register_setters) fails the
build. Only add a SNAPSHOT_REBUILDERS entry if the service is hot-swapped in
tests and its subsystem snapshot must refresh (most services need nothing).

## Consequences
- WikiEngine CBO dropped 143 -> 86 and LOC 2337 -> 1894 (measured 2026-07-02
  via the PMD coupling ruleset); new services need no field/reader/writer edit.
- Retiring the 8 bridges entirely remains a conditional future step gated on a
  Maven split of wikantik-main — out of scope here.
