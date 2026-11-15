# wikantik-work

Module notes, loaded when working under this directory.

- **wikantik-work** (work management): projects, epics/stories/tasks, comments and an append-only event log in `work_*` tables (`com.wikantik.work.*`). Depends on neither `wikantik-api` nor `wikantik-main`; all database access goes through `com.wikantik.jdbc.Jdbc`. Design: `docs/superpowers/specs/2026-11-15-work-management-design.md`.
- Feature flag `wikantik.work.enabled` (default `false`); `/api/capabilities` reports it as `work`.
- Work items never reach the page store, search index, bundles, sitemap, change feed or Page Graph. Nothing is hard-deleted; `work_event` is append-only.
