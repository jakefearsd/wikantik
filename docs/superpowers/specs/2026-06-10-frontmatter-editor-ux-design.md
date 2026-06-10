# Frontmatter Editor UX — Live Validation, Save Gating, Density & Token Consistency

**Date:** 2026-06-10
**Status:** Approved — ready for implementation plan
**Scope:** `wikantik-frontend` structured frontmatter editor, plus one optional server-side
hardening task in `wikantik-rest` / `wikantik-main`.

## Background & Motivation

A page save that only changed a date failed with an opaque `500`-style banner reading
`Runbook page '…' has invalid frontmatter: [RELATED_TOOL_INVALID …]`. The date was a red
herring: the page already carried a pre-existing invalid `related_tools` entry, and the
runbook validator hard-rejects the *whole* save. Two distinct UX gaps surfaced:

1. **No live validation.** The editor's `violations` state is populated only from a *failed
   save response*, so a pre-existing error stays invisible until an unrelated save trips it.
2. **Runbook errors bypass the structured channel.** `SchemaDrivenFrontmatterValidator`
   errors flow through the inline `violations` UI, but `RunbookValidationPageFilter` throws a
   `FilterException` that `PageResource` returns as a generic error banner — not inline,
   not field-scoped.

Separately, the editor's CSS references **undefined** design vars (`var(--muted)`,
`var(--warn)`, `var(--error)`) and silently falls back to hardcoded hex, so it is off the
app's design tokens; and the form is a single-column stack (`gap: 0.85rem`) — sparse for a
~15-field, information-heavy surface.

## Goals

- **Live, debounced validation** as the user edits the structured form, surfacing blocking
  errors and advisory warnings inline and continuously — before Save is ever clicked.
- **Save gating:** disable Save when ERROR-severity violations exist; WARNINGS never block
  (preserves the deliberate "warnings are always savable" philosophy — the corpus has 586+
  advisory warnings that must still save).
- **Never an opaque error:** runbook errors render inline against the right sub-field.
- **Visual consistency:** re-base the `fm-*` CSS onto the real design tokens and `ui/`
  primitives.
- **Density:** a responsive grid suited to information-heavy editing.

## Non-Goals (YAGNI)

- Field grouping / collapsible sections (considered as approach "B", rejected — collapsing
  hides information and fights the density goal).
- Any auto-fix beyond the existing "apply suggestion" affordance.
- Changing the warnings-are-savable philosophy.
- Re-sourcing or restructuring the schema itself.

## Current State (reference)

- `wikantik-frontend/src/components/frontmatter/FrontmatterEditor.jsx` — Form ⇄ Raw YAML
  tabs; renders one `FieldWidget` per schema field; `violationsFor(key)` already buckets
  `key` and `key.*` violations to a field.
- `FieldWidget.jsx` — per-widget controls; `ViolationList` renders inline violations with an
  "apply suggestion" button. Does **not** forward violations into `RunbookBlockEditor`.
- `RunbookBlockEditor.jsx` — bare textareas, one per sub-field; **no** violation display.
- `PageEditor.jsx` — owns `violations` state (populated on `422` save responses only); Save
  button at line ~508 is `disabled={saving}`.
- `utils/frontmatterUtils.js#metadataToYaml`, `api/client.js#validateFrontmatter` exist.
- Server: `POST /api/frontmatter/validate` runs `SchemaDrivenFrontmatterValidator`, which
  delegates the `runbook:` block to `FrontmatterRunbookValidator` and maps issues to
  field paths (`runbook.related_tools`, `runbook.when_to_use`, `runbook.steps`,
  `runbook.pitfalls`, `runbook.references`). So the dry-run endpoint already returns runbook
  errors as structured `FieldViolation`s — the editor simply never calls it live.

## Design

### New unit: `useFrontmatterValidation(metadata, { enabled })`

A focused hook (no existing generic debounce hook to reuse).

- Serializes `metadata` → YAML via `metadataToYaml` and debounces ~400 ms after a change,
  then calls `api.validateFrontmatter({ frontmatter: yaml })`. Validating the **serialized
  YAML** (not the raw object) matches the exact representation the save path persists, so
  live results cannot disagree with the save gate (this is the class of bug where a scalar
  vs. list round-trips differently).
- Returns `{ violations, validating }`.
- **Race-safe:** a monotonic request counter; responses older than the latest request are
  discarded (same pattern as the drift status poll).
- **Fail-open:** on a network/server error the hook keeps the last successful result and
  never synthesizes blocking errors. A validation outage must not lock the user out of
  saving; the server remains the final gate.
- `enabled` lets callers/tests suspend validation.

### Data flow

```
metadata edits ─▶ useFrontmatterValidation (debounced, race-safe) ─▶ violations[]
                         │
        ┌────────────────┼─────────────────────────┐
        ▼                ▼                          ▼
  ValidationSummary  FieldWidget (per field)    PageEditor save gate
   (counts + jump)   (inline, by field path)    disabled = saving || hasBlockingErrors
```

`hasBlockingErrors = violations.some(v => v.severity === 'ERROR')`. Save-response `422`
violations continue to merge in as a fallback for non-frontmatter failures.

### Components

- **`ValidationSummary`** (new, small): rendered at the top of the editor. Three states —
  `Checking…` (reuse `Spinner`), `✓ Frontmatter valid`, or `N errors · M warnings` using the
  `Badge` primitive. Each count is a button that scrolls to / focuses the first field of that
  severity (fields carry a `data-field={key}` attribute for lookup).
- **`PageEditor`**: instantiate the hook; render `ValidationSummary`; gate the Save button —
  `disabled={saving || hasBlockingErrors}` with `title` describing the block ("2 blocking
  errors — fix highlighted fields"). The Save handler's existing `422 → setViolations` path
  remains as a fallback.
- **`RunbookBlockEditor`**: accept a `violations` prop; for each sub-field, filter
  `runbook.<subfield>` and render the existing `ViolationList` against that control.
- **`FrontmatterEditor`**: forward the `runbook.*`-scoped violations to `RunbookBlockEditor`
  (via `FieldWidget` for the `RUNBOOK_BLOCK` widget); add `data-field` to each field wrapper.

### Styling & density (CSS-only, in `styles/globals.css`)

- **Token rebase:** `--muted`→`--text-secondary`/`--text-muted`, `--warn`→`--warning`,
  `--error`→`--danger`, spacing literals→`--space-*`, borders→`--border`; severity badge
  backgrounds use `--danger-bg` / `--warning-bg`.
- **Density grid:** `.fm-form` becomes
  `display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: var(--space-sm) var(--space-md)`.
  Wide fields span the row via `.fm-field--wide { grid-column: 1 / -1 }`. A field is **wide**
  iff its widget is one of `TEXTAREA`, `TAGS`, `PAGE_REFS`, `RUNBOOK_BLOCK`, **or** it is a
  `TEXT` widget with `spec.maxLen >= 80` (covers `summary`, maxLen 160); all other fields are
  2-up. This predicate lives in one helper (`isWideField(spec)`) shared by the component and
  its test. Label→control gap tightens to `--space-xs`.
- **Narrow collapse:** the editor shares the pane with CodeMirror; a breakpoint (media or
  container query) drops to a single column when narrow.

### Server task C (optional, low priority — belt-and-suspenders)

With Save disabled on errors, the opaque rejection path is unreachable from the SPA, so this
hardens non-UI clients (REST/MCP) only.

- Introduce `FrontmatterValidationException extends FilterException` carrying the structured
  issues (`List<FieldViolation>` or the runbook `Issue` list mapped to field paths).
- `RunbookValidationPageFilter` throws that subtype instead of a bare `FilterException`.
- `PageResource.doPut` catches it specifically and emits **HTTP 422** with
  `{ violations: [...] }` in the same shape as schema/YAML errors. Null-safe; no change to the
  schema-warning sink.

## Testing

- **vitest (frontend):**
  - `useFrontmatterValidation`: debounce coalesces to one call; stale responses are dropped
    (race-safety); fail-open keeps prior result on error and never blocks.
  - `ValidationSummary`: renders all three states; jump button targets the first
    same-severity field.
  - `RunbookBlockEditor`: a `runbook.related_tools` violation renders against the
    `related_tools` control, not elsewhere.
  - `FrontmatterEditor` / `PageEditor`: Save disabled when an ERROR is present, enabled when
    only warnings are present; live violations populate field widgets; wide-class assignment
    by widget type.
  - Tests must pass when run in isolation (project note: vitest concurrency can false-fail;
    re-run the single file before chasing).
- **Java (task C only):** mockito unit for the structured exception + `PageResource` 422
  mapping; a REST IT asserting the 422 violations shape for a runbook-invalid save.
- **Manual:** load the editor against the local Tomcat and eyeball density + token colors
  (verification-before-completion).

## Risks & Mitigations

- **Live-validation chattiness / load:** debounce (~400 ms) + race-safety + single in-flight
  request bound the call rate; the endpoint is already cheap (advisory dry-run).
- **Live/save disagreement:** mitigated by validating the serialized YAML — the same bytes the
  save persists.
- **Validation outage locking out save:** mitigated by fail-open (never synthesize blocking
  errors; server stays the gate).
- **CSS regressions elsewhere:** the `fm-*` selectors are editor-scoped; the token rebase is
  mechanical and confined to that block.

## Rollout

Frontend changes ship together (hook + components + CSS); no flag needed — the behavior is
strictly additive (live feedback + a disabled-state on already-blocked saves). Task C is an
independent, optional follow-up. Full IT reactor gates any server-code commit before the
eventual production push.
