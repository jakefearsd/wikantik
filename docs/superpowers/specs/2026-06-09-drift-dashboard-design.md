# Drift Dashboard — Design

**Status:** Approved 2026-06-09.
**Goal:** Make vocabulary drift measurable. The frontmatter validator already warns
per-page on edit, and the SHACL gate already counts non-conformant edges — but there
is no aggregate view, no trend, and no way to know when it is safe to ratchet a
warning to an error (`wikantik.frontmatter.enum.nonCanonical.severity`). This feature
adds a scheduled corpus-wide validation sweep, persisted aggregate snapshots, and an
admin dashboard with a burn-down view.

Companion to:
- [2026-06-08-structured-page-curation-design.md](2026-06-08-structured-page-curation-design.md) — the validator/schema this sweeps with
- [2026-06-08-wiki-ontology-design.md](2026-06-08-wiki-ontology-design.md) — the rebuild scheduler + SHACL gate this piggybacks on
- [docs/OntologyManagement.md](../../OntologyManagement.md) — gets a short "Measuring drift" section when this ships

## Decisions (made with the user)

1. **Aggregate snapshots, not full detail.** Each sweep persists one row per
   `(family, code, severity)` with a count. Per-code page lists are computed live on
   demand (the corpus is small; a full validation pass takes seconds). History stays
   tiny forever; only aggregates are historical, current offenders are always exact.
2. **Frontmatter + SHACL in one dashboard.** Two drift families: `frontmatter`
   (field violations from the corpus sweep) and `shacl` (non-conformant KG edges,
   reusing the existing `OntologyShaclValidator` conformance check behind
   `/admin/ontology?action=violations`, counted by shape).
3. **Sweep piggybacks on the nightly ontology rebuild** via a post-rebuild completion
   hook on `OntologyRebuildCoordinator`, plus a manual `POST /admin/drift/sweep`.
   No new schedule or config property. Accepted consequence: with the rebuild
   disabled (`wikantik.ontology.rebuild.interval.hours=0`) automatic sweeps stop;
   the manual trigger still works, and a manual sweep with the ontology subsystem
   off persists frontmatter counts with the SHACL family absent for that sweep.

## Architecture

```
OntologyRebuildCoordinator ──(post-rebuild hook)──▶ DriftSweepService ──▶ drift_sweeps
POST /admin/drift/sweep    ──(manual trigger)─────▶   (single-flight)      drift_snapshot_counts
                                                        │
                              PageManager.getAllPages() ┤ frontmatter family
                              SchemaDrivenFrontmatterValidator.validate(metadata, ctx)
                              OntologyShaclValidator violations ┘ shacl family
```

### DriftSweepService (`com.wikantik.drift`, wikantik-main)

One public entry point, `runSweep(trigger)`:

1. **Single-flight guard.** An `AtomicBoolean`; a second caller gets a
   `SweepAlreadyRunningException` (mirrors `OntologyRebuildCoordinator.ConflictException`).
2. **Frontmatter pass.** Iterate `PageManager.getAllPages()`; for each page, parse
   frontmatter and run `SchemaDrivenFrontmatterValidator.validate(metadata, ctx)`
   with the same `ValidationCtx` the save path uses (real `pageResolves`, real
   trusted-author predicate, configured non-canonical-enum severity). Aggregate the
   returned `FieldViolation`s by `(code, severity)`. A page whose YAML fails to
   parse is counted under code `yaml.malformed` / ERROR and the sweep continues.
3. **SHACL pass.** Ask the existing SHACL conformance check (same call
   `AdminOntologyResource.handleViolations` uses) for the violation list; count by
   shape identifier → family `shacl`, code = shape IRI/local name, severity ERROR.
   If the ontology subsystem is disabled or the check throws, log a `LOG.warn` with
   context and persist the sweep without the `shacl` family.
4. **Persist** one `drift_sweeps` row + its `drift_snapshot_counts` rows in a single
   transaction. A failed sweep persists nothing.

The service also exposes `currentPageList(code)` for the live per-code drill-down:
re-runs the frontmatter pass (no persistence) and returns
`(pageName, field, severity, message, suggestion)` for violations matching `code`.
For `shacl` codes it returns the live violation list filtered by shape.

### Wiring

`KnowledgeSubsystemFactory` / `OntologyWiringHelper` construct the service and
register the post-rebuild hook: `OntologyRebuildCoordinator` gains
`onRebuildComplete(Runnable)` (invoked after a successful rebuild, exceptions from
the hook caught + warn-logged so a sweep failure can never fail a rebuild).

## Data model — migration `bin/db/migrations/V038__drift_snapshots.sql` (DDL-only, idempotent)

```sql
CREATE TABLE IF NOT EXISTS drift_sweeps (
    id            BIGSERIAL PRIMARY KEY,
    swept_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    pages_scanned INT         NOT NULL,
    duration_ms   BIGINT      NOT NULL,
    triggered_by  TEXT        NOT NULL,          -- 'scheduled' | 'manual'  (not "trigger": keyword in H2)
    shacl_checked BOOLEAN     NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS drift_snapshot_counts (
    sweep_id  BIGINT NOT NULL REFERENCES drift_sweeps(id) ON DELETE CASCADE,
    family    TEXT   NOT NULL,                   -- 'frontmatter' | 'shacl'
    code      TEXT   NOT NULL,                   -- violation code or shape name
    severity  TEXT   NOT NULL,                   -- 'ERROR' | 'WARNING'
    count     INT    NOT NULL,
    PRIMARY KEY (sweep_id, family, code, severity)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON drift_sweeps, drift_snapshot_counts TO :app_user;
GRANT USAGE, SELECT ON SEQUENCE drift_sweeps_id_seq TO :app_user;
```

`shacl_checked=false` marks sweeps where the SHACL family is absent (subsystem off),
so the trend chart can render a gap instead of a false zero. No retention policy:
one sweep is ~a dozen count rows.

## REST surface — `AdminDriftResource` (wikantik-rest, `/admin/drift/*`, behind `AdminAuthFilter`)

| Endpoint | Behavior |
|---|---|
| `GET /admin/drift/summary` | Latest sweep: `sweptAt`, `pagesScanned`, `triggeredBy`, `shaclChecked`, and per `(family, code, severity)` the `count` plus `delta` vs. the previous sweep (null when no previous sweep has that code). Empty-state shape (`sweptAt: null`) before the first sweep. |
| `GET /admin/drift/trend?days=N` | Time series: for each sweep in the window, `sweptAt` + counts per `(family, code)`. Default `days=30`. |
| `GET /admin/drift/pages?code=X&family=Y` | Live-computed offender list for one code: `[{pageName, field, severity, message, suggestion}]`. Not persisted, always current. |
| `POST /admin/drift/sweep` | Manual trigger, runs async; `202` with the in-progress marker, `409` if a sweep is already running. |

JSON is camelCase (house wire convention). Registered in `web.xml`.

## Admin UI — `AdminDriftPage.jsx` (route `/admin/drift`, sidebar entry "Drift")

- **Header:** last sweep time + trigger, pages scanned, total errors/warnings with
  delta arrows, **Run sweep now** button (disabled while running; on `202`, poll
  `summary` until `sweptAt` advances). A "SHACL not checked" badge when
  `shaclChecked=false`.
- **Trend chart:** hand-rolled SVG line chart, one line per `(family, code)`,
  30-day window — follows the `AdminRetrievalQualityPage` precedent (no charting
  library exists in the frontend; do not add one).
- **Burn-down table:** family | code | severity | count | Δ. Expanding a row fetches
  `pages?code=...` live; each entry links to the page editor (`/edit/{page}`) and
  shows the validator's suggestion verbatim — the same machine-actionable suggestion
  a future agent cleanup sweep will consume.
- Empty state before the first sweep prompts the manual run.
- Reuse `src/components/ui/` primitives; route registration copies the most recently
  added admin page (verify against the SPA dual-registration gotcha — `web.xml` +
  `SpaRoutingFilter.SPA_EXACT` — though `/admin/*` pages ride the existing prefix).

## Error handling

- Per-page failures (YAML parse, provider read) never abort the sweep — counted
  (`yaml.malformed`) or warn-logged and skipped (`pages_scanned` reflects pages
  actually validated).
- Sweep persistence is all-or-nothing; on failure the dashboard keeps the last good
  sweep. All failures logged with context (no empty catch blocks).
- Hook exceptions are isolated: a sweep crash never fails the nightly rebuild.
- `409` from concurrent triggers; the UI surfaces "sweep already running".

## Testing

- **Unit (wikantik-main):** aggregation correctness (seeded pages with known
  violations → expected `(family, code, severity, count)` rows), `yaml.malformed`
  counting, single-flight guard, post-rebuild hook fires + hook exception isolation,
  SHACL-absent path (`shacl_checked=false`), repository round-trip on H2,
  `currentPageList` filtering.
- **Unit (wikantik-rest):** `AdminDriftResource` — summary with/without previous
  sweep (delta), empty state, trend windowing, 202/409, admin gating.
- **Frontend (vitest):** summary render + deltas, empty state, expand-row live
  fetch, run-now disable/poll, SHACL badge.
- **IT (wikantik-it-test-rest):** seed drifting pages → `POST /admin/drift/sweep` →
  poll → `GET summary` reflects them; `GET pages?code=...` lists the seeded page.
- Full sequential IT reactor (`mvn clean install -Pintegration-tests -fae`) gates
  the prod-code commit.

## Out of scope (explicitly)

- Agent-driven cleanup sweep consuming the suggestions (next step after this).
- Ratcheting any warning to error (this dashboard provides the evidence; the
  config lever already exists).
- Retention/pruning of snapshots; alerting/Prometheus export of drift counts.
