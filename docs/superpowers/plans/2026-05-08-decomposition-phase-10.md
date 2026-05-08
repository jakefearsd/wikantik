# Phase 10: registry deletion + WikiContext decomposition + final ban

**Spec:** [docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md](../specs/2026-05-05-wikantik-main-decomposition-design.md)
**Status:** complete (2026-05-08)
**Estimated effort:** 2–3 days
**Goal:** complete the registry deletion deferred from Phase 9 and decompose WikiContext (821 LOC).

## Two independent workstreams

The two are file-disjoint, so Ckpt A1 and Ckpt B run in parallel.

### Ckpt A — Registry deletion (carried over from Phase 9)

The Opus agent in Phase 9 stopped early because deleting the `managers` map without per-class typed backing fields produces NPEs across every subsystem. Split into two:

#### Ckpt A1 — Per-class typed backing fields on WikiEngine (Sonnet, ~1 day)

For every distinct class key currently read from `managers` (across bridges' `rebuildFromManagers` and factories' `create`), add a private field on WikiEngine. Rewrite:
- All 7 `*SubsystemBridge.rebuildFromManagers(WikiEngine)` to read from typed fields.
- `WikiEngine.setManager(Class<T>, T)` to write to typed fields (switch over class).
- `WikiEngine.getManager(Class<T>)` to read from typed fields (switch over class).
- `WikiEngine.initComponent(...)` to write to typed fields after constructing.

Keep the `managers` map intact for now as a safety net (writes go to BOTH the map AND the typed field; reads go to the typed field first, fall back to the map). This way Ckpt A1 is behaviour-zero.

#### Ckpt A2 — Delete the map (Sonnet, ~half day)

Remove `WikiEngine.managers` field and its writes. `getManager` reads from typed fields only. Add ArchUnit `no_get_manager_anywhere` rule. Force every test that mocks Engine directly to use WikiEngine instead.

### Ckpt B — WikiContext decomposition (Sonnet, ~1 day)

`WikiContext` is 821 LOC. Split into:
- `RequestScope` — HTTP request, parameters, session principal
- `PageScope` — current page, page version, page metadata
- `RenderingScope` — render flags, template, view-mode

WikiContext becomes a thin facade exposing those three scopes. Existing call sites continue to work via delegated accessors.

If the split turns out to be too entangled, settle for "extract `RenderingScope` only" as the minimum win. The 500 LOC target relaxes to <600 if necessary.

### Ckpt C — close-out (Opus, half day)

Metrics, spec update, plan complete.

## Concurrency

- **Ckpt A1 and Ckpt B run in parallel** in separate worktrees (touch disjoint files).
- **Ckpt A2 runs after A1 lands** (it depends on A1's typed fields).
- **Ckpt C runs last.**

## Done when

- `WikiEngine.managers` map deleted; `getManager`/`setManager` either gone or backed only by typed fields.
- ArchUnit `no_get_manager_anywhere` rule passing.
- `WikiContext.java` < 600 LOC (or, if split is incomplete, document residual).
- Full IT reactor green.
