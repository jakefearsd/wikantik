# Frontmatter editor — density + collapsible redesign

**Date:** 2026-06-12
**Status:** Design approved, pending implementation
**Component:** `wikantik-frontend` structured frontmatter editor (`src/components/frontmatter/*`)

## Problem

The structured `FrontmatterEditor` in the article editing view consumes far too much
vertical screen real estate. It renders **all 17 schema fields, always**, in a 2-column
grid where every field stacks its label *above* its control (doubling each row's height),
and four fields (`summary`, `tags`, `related`, `runbook`) force full-width rows. The
`runbook` field is an always-expanded multi-field sub-editor. The three derived read-only
fields (`canonical_id`, `confidence`, `agent_hints`) occupy prime grid space but are never
edited.

A plain article page uses ~5 of those 17 fields yet pays the full vertical cost of all of
them — roughly 700–900px before any typing. Target: a **large** reduction (~65%), not an
incremental tweak.

## Goals

- Default (collapsed) height ≈ 200–260px for a typical article — a ~65% reduction.
- Everyday fields stay immediately visible; rare/specialized fields move behind a
  disclosure ("zippy").
- Nothing already populated is ever hidden from the user without a cue.
- Denser per-field styling without harming usability or accessibility.

## Non-goals

- No change to the server-authoritative `FrontmatterSchema`, validation semantics, or the
  save path. This is presentation-only.
- No change to the Raw YAML break-glass tab or the unknown-keys "Advanced" disclosure
  (beyond living alongside the new structure).
- No change to the `ValidationSummary` strip rendered above the editor in `PageEditor`.

## Design

### Field grouping (three buckets, schema-driven)

| Bucket | Fields | Default state |
|---|---|---|
| **Common** | `title, type, status, summary, tags, cluster` | always visible |
| **More fields** (zippy) | everything not Common and not read-only: `related, date, author, audience, kg_include, verified_at, verified_by, runbook` | collapsed `<details>`; **auto-opens at load** if any of its fields already has a value |
| **Meta strip** | the read-only trio `canonical_id, confidence, agent_hints` | one muted line at the bottom; renders only the fields that have values; renders nothing if none do |
| **Advanced (raw keys)** | unknown passthrough keys | existing `<details>`, unchanged |

- **Common** is the single hardcoded list (`COMMON_KEYS`).
- **More** is *derived*: any schema field whose key is not in `COMMON_KEYS` and whose
  `widget !== 'READONLY'`. Adding a schema field later auto-lands it in More rather than
  silently dropping it.
- **Meta strip** = schema fields with `widget === 'READONLY'`.

### Density — inline labels

Each field changes from label-above-control to a 2-column grid row: `[label] [control]`,
vertically centered.

- To keep violation messages aligned under the control (not in the label column),
  `FieldWidget` wraps the control + its `ViolationList` in a single
  `<div className="fm-control">`. So `.fm-field` becomes a clean two-child grid:
  `[label] [control-wrapper]`.
- **Short scalars** (`type, status, cluster, date, author, audience, kg_include,
  verified_at, verified_by`) pack **2-up** with inline labels.
- **Full-row** fields (`grid-column: 1 / -1`): `title` (titles are long), plus the wide
  widgets `summary`, `tags`, `related`. These keep the inline label with the control
  spanning the remaining width.
- **`runbook`** stays **stacked** (label above its sub-editor block) — it is a multi-field
  block, not a single control. Targeted via `.fm-field-runbook`.
- Tighter grid gaps (`--space-xs` row / `--space-md` column).

Long labels in the More section (e.g. "Include in Knowledge Graph") wrap within a fixed
label column; acceptable because they are behind the disclosure.

### Default-state mockup

```
[Form] [Raw]                                   ✓ valid
Title    [________________________________________]
Type     [article ▾]          Status  [draft ▾]
Cluster  [______________]
Summary  [____________________________________] 50/160
Tags     [react][css][+]
▸ More fields (related · date · author · audience · KG · verification · runbook)
canon: a1b2c3 · confidence 0.82 · hints: api, auth        ← muted meta strip
▸ Advanced (raw keys)
```

### Behavior details

- **More auto-open** is one-way at load time. On the first render where `metadata` is
  non-empty, if any More field has a value, the disclosure opens. After that the user
  controls it — manual collapse is respected and never re-opened by re-renders. Implemented
  with `useState(false)` + a one-shot `useRef` guard in an effect keyed on `metadata`.
- **Meta strip** renders `{label}: {value}` items (joined by ` · `) only for read-only
  fields whose value is non-empty. `agent_hints` (potentially long / an array) gets a
  per-item max-width + ellipsis + `title` tooltip so the strip stays one line tall.
- A field is considered to "have a value" when it is not `undefined`/`null`/`''` and not an
  empty array — matching the existing `setField` empty-collapse rule in `FrontmatterEditor`.

## Components / files touched

- **`src/components/frontmatter/FrontmatterEditor.jsx`** — partition `schema.fields` into
  Common / More / readonly; render Common fields, then the More `<details>` (controlled
  open state), then the meta strip, then the existing unknown-keys Advanced disclosure. Raw
  tab unchanged.
- **`src/components/frontmatter/FieldWidget.jsx`** — wrap `{control}` + `<ViolationList>` in
  `<div className="fm-control">`. No change to widget dispatch.
- **`src/styles/globals.css`** — rewrite `.fm-field` to the inline-label grid; add
  `.fm-control`, `.fm-more` (+ summary), `.fm-meta-strip`; add full-width overrides for
  `.fm-field-title` and `--wide` fields, stacked override for `.fm-field-runbook`. Tighten
  `.fm-form` gaps.

## Testing (TDD)

Extend `src/components/frontmatter/FrontmatterEditor.test.jsx`:

1. Common fields (`title, type, status, summary, tags, cluster`) render in the form by
   default.
2. More fields (e.g. `runbook`, `date`, `verified_at`) are **not** in the accessibility tree
   until the More disclosure is expanded (closed `<details>` by default for an
   all-Common-only page).
3. When a More field has a value at load (e.g. `verified_at` set), the More disclosure is
   open on first render.
4. The meta strip shows only populated read-only fields (set `confidence`, omit
   `agent_hints` → strip shows confidence, not hints), and renders nothing when none are
   set.
5. Existing tests (Form ⇄ Raw toggle, violation rendering, unknown-keys Advanced) still
   pass — the `.fm-control` wrapper must not break label/role-based queries.

Run with `npx vitest run src/components/frontmatter/FrontmatterEditor.test.jsx` (re-run the
single file alone if vitest concurrency reports a false flake).

## Risks

- The `.fm-control` wrapper is a DOM-structure change; existing tests query by label
  text/role and should be unaffected, but this is verified by re-running the suite.
- Inline labels with long More-section labels could wrap awkwardly; mitigated by a fixed
  label column and the fact they live behind the disclosure.
