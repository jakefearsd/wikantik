# wikantik-work

Module notes, loaded when working under this directory.

- **wikantik-work** (work management): projects, epics/stories/tasks, comments and an append-only event log in `work_*` tables (`com.wikantik.work.*`). Depends on neither `wikantik-api` nor `wikantik-main`; all database access goes through `com.wikantik.jdbc.Jdbc`. Design: `docs/superpowers/specs/2026-11-15-work-management-design.md`.
- Feature flag `wikantik.work.enabled` (default `false`); `/api/capabilities` reports it as `work`.
- Work items never reach the page store, search index, bundles, sitemap, change feed or Page Graph. Nothing is hard-deleted; `work_event` is append-only.

Rules a future change must not break:

- No dependency on `wikantik-api` or `wikantik-main`. The runtime wiring and text rendering live in `wikantik-main` (`com.wikantik.work.runtime`).
- Every caller goes through `WorkService`; `WorkStore` has no visibility check (`WorkAccessArchTest` enforces it). `WorkServiceVisibilityPostgresTest` classifies every public `WorkService` method as a read or a write and drives each read as a non-member: a new public method must be classified there.
- Non-members get **404, never 403**, and lists omit the project. Wiki Admins see everything.
- Nothing is hard-deleted; `work_event` is append-only.
- `blocked_by` links are same-project only.
- Item text renders in the "links as typed" mode (`Context.VAR_LINKS_AS_TYPED`) with page filters off, and must never do a page, title, alias, heading or attachment lookup.
- No project, product or item labels on metrics (`WorkMetrics`): cardinality and leakage.
- The activity cursor is the pair `(at, id)`, never `at` alone.
- Sprints must stay addable as a new table plus nullable `work_item` columns; do not encode "no sprints" into queries, the board model or event types.
- The policy columns (`default_autonomy`, `autonomy_ceiling`, `deploy_gate`, `autonomy`, `done_means`, `allow_direct`) are stored and returned but inert until phases 3–4.
