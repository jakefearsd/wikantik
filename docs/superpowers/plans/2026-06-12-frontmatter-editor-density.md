# Frontmatter Editor Density + Collapse Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Shrink the structured frontmatter editor's default footprint by ~65% — split fields into an always-open "Common" block + a collapsible "More fields" zippy, demote read-only derived fields to a muted meta strip, and switch to dense inline labels.

**Architecture:** Presentation-only change in `wikantik-frontend`. `FrontmatterEditor` partitions the server schema's fields into Common / More / read-only buckets and renders three regions (Common grid, `<details>` More grid that auto-opens when any of its fields is populated, and a meta strip). `FieldWidget` wraps its control + violations in a `.fm-control` div so a 2-column `[label][control]` grid can be applied. All density lives in CSS.

**Tech Stack:** React (function components + hooks), Vitest + @testing-library/react, plain CSS in `src/styles/globals.css`.

**Context for the implementer:**
- The server schema is server-authoritative and unchanged. Fields arrive as `{ key, label, widget, canonicalValues, open, minLen, maxLen, ... }`. `widget` is one of `READONLY, TEXT, TEXTAREA, ENUM, TAGS, PAGE_REFS, DATE, DATETIME, TRISTATE, RUNBOOK_BLOCK`.
- The real default schema order is: `canonical_id`(READONLY), `title`, `type`, `status`, `summary`, `tags`, `cluster`, `related`, `date`, `author`, `kg_include`, `verified_at`, `verified_by`, `audience`, `confidence`(READONLY), `agent_hints`(READONLY), `runbook`. Filtering preserves this order, which is exactly what we want.
- Run a single test file with: `npx vitest run <path>` from `wikantik-frontend/`. Vitest concurrency occasionally reports a FALSE flake — if a frontmatter test fails oddly, re-run that one file alone before investigating (documented project gotcha).
- Do not use the brainstorming visual companion; this plan is text-only.

---

## File Structure

- **`wikantik-frontend/src/components/frontmatter/FieldWidget.jsx`** (modify) — wrap `{control}` + `<ViolationList>` in a single `<div className="fm-control">`. No widget-dispatch changes.
- **`wikantik-frontend/src/components/frontmatter/FrontmatterEditor.jsx`** (modify) — partition schema fields; render Common grid, collapsible More grid (auto-open one-shot), read-only meta strip; Raw tab + unknown-keys Advanced unchanged.
- **`wikantik-frontend/src/styles/globals.css`** (modify) — inline-label grid for `.fm-field`; add `.fm-control`, `.fm-more*`, `.fm-meta-*`; full-row overrides for `.fm-field-title` / `.fm-field--wide`; stacked override for `.fm-field-runbook`.
- **`wikantik-frontend/src/components/frontmatter/FieldWidget.test.jsx`** (modify) — assert the `.fm-control` wrapper.
- **`wikantik-frontend/src/components/frontmatter/FrontmatterEditor.test.jsx`** (modify) — assert Common/More partition, collapse-by-default, auto-open, meta strip.

---

## Task 1: FieldWidget control wrapper

Wrap each field's control and its violation list in one `.fm-control` element so CSS can lay out `[label][control]` as a 2-column grid with violations aligned under the control.

**Files:**
- Modify: `wikantik-frontend/src/components/frontmatter/FieldWidget.jsx:140-146`
- Test: `wikantik-frontend/src/components/frontmatter/FieldWidget.test.jsx`

- [ ] **Step 1: Write the failing test**

Append this `it(...)` block inside the `describe('FieldWidget', ...)` in `FieldWidget.test.jsx` (before the closing `});` on line 38):

```jsx
  it('wraps the control and its violations in a .fm-control element', () => {
    const { container } = render(
      <FieldWidget
        spec={{ key: 'summary', label: 'Summary', widget: 'TEXT', minLen: 50, maxLen: 160 }}
        value="too short"
        onChange={noop}
        violations={[{ field: 'summary', severity: 'WARNING', code: 'x', message: 'too short msg' }]}
      />,
    );
    const field = container.querySelector('[data-field="summary"]');
    const control = field.querySelector('.fm-control');
    expect(control).toBeTruthy();
    // both the input and the violation text live inside the wrapper, not the label column
    expect(control.querySelector('input')).toBeTruthy();
    expect(control.textContent).toContain('too short msg');
  });
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd wikantik-frontend && npx vitest run src/components/frontmatter/FieldWidget.test.jsx`
Expected: FAIL — `control` is `null` (no `.fm-control` element yet), so `control.querySelector` throws / assertion fails.

- [ ] **Step 3: Implement the wrapper**

In `FieldWidget.jsx`, replace the final `return` block (currently lines 140-146):

```jsx
  return (
    <div className={`fm-field fm-field-${key}${isWideField(spec) ? ' fm-field--wide' : ''}`} data-field={key}>
      <label className="fm-label">{label}</label>
      {control}
      <ViolationList violations={ownViolations} onApplySuggestion={onApplySuggestion} />
    </div>
  );
```

with:

```jsx
  return (
    <div className={`fm-field fm-field-${key}${isWideField(spec) ? ' fm-field--wide' : ''}`} data-field={key}>
      <label className="fm-label">{label}</label>
      <div className="fm-control">
        {control}
        <ViolationList violations={ownViolations} onApplySuggestion={onApplySuggestion} />
      </div>
    </div>
  );
```

- [ ] **Step 4: Run the FieldWidget tests to verify they pass**

Run: `cd wikantik-frontend && npx vitest run src/components/frontmatter/FieldWidget.test.jsx`
Expected: PASS (all 5 tests, including the new one).

- [ ] **Step 5: Run the FrontmatterEditor tests to confirm no regression**

Run: `cd wikantik-frontend && npx vitest run src/components/frontmatter/FrontmatterEditor.test.jsx`
Expected: PASS (all existing tests; the wrapper is queried by label/role/text so structure changes don't matter).

- [ ] **Step 6: Commit**

```bash
git add wikantik-frontend/src/components/frontmatter/FieldWidget.jsx \
        wikantik-frontend/src/components/frontmatter/FieldWidget.test.jsx
git commit -m "feat(frontmatter): wrap field control + violations in .fm-control"
```

---

## Task 2: Partition fields into Common / More / meta strip

Replace `FrontmatterEditor`'s flat field list with three regions: an always-open Common grid, a collapsible "More fields" `<details>` (auto-opens when any of its fields already has a value), and a muted read-only meta strip. Raw tab and unknown-keys Advanced are unchanged.

**Files:**
- Modify: `wikantik-frontend/src/components/frontmatter/FrontmatterEditor.jsx` (whole file — replace with the version below)
- Test: `wikantik-frontend/src/components/frontmatter/FrontmatterEditor.test.jsx`

- [ ] **Step 1: Write the failing tests**

Append these four `it(...)` blocks inside `describe('FrontmatterEditor', ...)` in `FrontmatterEditor.test.jsx` (before the closing `});`):

```jsx
  it('renders Common fields inline and collapses the rest into "More fields"', () => {
    const schema = {
      fields: [
        { key: 'title', label: 'Title', widget: 'TEXT' },
        { key: 'type', label: 'Type', widget: 'ENUM', canonicalValues: ['article'], open: true },
        { key: 'audience', label: 'Audience', widget: 'ENUM', canonicalValues: ['both'], open: false },
        { key: 'runbook', label: 'Runbook', widget: 'RUNBOOK_BLOCK' },
      ],
    };
    const { container } = render(
      <FrontmatterEditor schema={schema} metadata={{ title: 'X' }} onChange={() => {}} />,
    );
    // Common fields live in the first (always-open) .fm-form grid
    const commonGrid = container.querySelector('.fm-form');
    expect(commonGrid.querySelector('[data-field="title"]')).toBeTruthy();
    expect(commonGrid.querySelector('[data-field="type"]')).toBeTruthy();
    // Non-common editable fields live inside a closed "More fields" disclosure
    const more = container.querySelector('details.fm-more');
    expect(more).toBeTruthy();
    expect(more.open).toBe(false);
    expect(more.querySelector('[data-field="audience"]')).toBeTruthy();
    expect(more.querySelector('[data-field="runbook"]')).toBeTruthy();
  });

  it('auto-opens "More fields" when a non-common field already has a value', () => {
    const schema = {
      fields: [
        { key: 'title', label: 'Title', widget: 'TEXT' },
        { key: 'audience', label: 'Audience', widget: 'ENUM', canonicalValues: ['both'], open: false },
      ],
    };
    const { container } = render(
      <FrontmatterEditor schema={schema} metadata={{ title: 'X', audience: 'both' }} onChange={() => {}} />,
    );
    expect(container.querySelector('details.fm-more').open).toBe(true);
  });

  it('shows populated read-only fields in the meta strip and hides empty ones', () => {
    const schema = {
      fields: [
        { key: 'title', label: 'Title', widget: 'TEXT' },
        { key: 'confidence', label: 'Confidence', widget: 'READONLY' },
        { key: 'agent_hints', label: 'Agent hints', widget: 'READONLY' },
      ],
    };
    const { container } = render(
      <FrontmatterEditor schema={schema} metadata={{ title: 'X', confidence: 0.82 }} onChange={() => {}} />,
    );
    const strip = container.querySelector('.fm-meta-strip');
    expect(strip).toBeTruthy();
    expect(strip.textContent).toContain('Confidence');
    expect(strip.textContent).toContain('0.82');
    expect(strip.textContent).not.toContain('Agent hints');
    // read-only fields must NOT also render as editable field widgets
    expect(container.querySelector('[data-field="confidence"]')).toBeFalsy();
  });

  it('renders no meta strip when no read-only field has a value', () => {
    const schema = {
      fields: [
        { key: 'title', label: 'Title', widget: 'TEXT' },
        { key: 'confidence', label: 'Confidence', widget: 'READONLY' },
      ],
    };
    const { container } = render(
      <FrontmatterEditor schema={schema} metadata={{ title: 'X' }} onChange={() => {}} />,
    );
    expect(container.querySelector('.fm-meta-strip')).toBeFalsy();
  });
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd wikantik-frontend && npx vitest run src/components/frontmatter/FrontmatterEditor.test.jsx`
Expected: FAIL — the four new tests fail (`details.fm-more` and `.fm-meta-strip` don't exist; read-only fields currently render as widgets). Existing tests still pass.

- [ ] **Step 3: Replace `FrontmatterEditor.jsx` with the partitioned version**

Replace the ENTIRE contents of `wikantik-frontend/src/components/frontmatter/FrontmatterEditor.jsx` with:

```jsx
// FrontmatterEditor.jsx
// The structured frontmatter surface that shares the editor pane with the (body-only) CodeMirror.
// Fields are split into an always-open "Common" block, a collapsible "More fields" disclosure for
// the rarer/specialized fields (runbook, verification, etc.), and a muted meta strip for the
// derived READONLY fields. An Advanced area lists unknown keys (preserved verbatim), and a
// Form ⇄ Raw YAML break-glass toggle stays available. The parsed metadata OBJECT is canonical;
// this component never enforces — it renders the server's violations inline and lets the user save.
//
// Props:
//   metadata      the current frontmatter object (canonical)
//   onChange      (nextMetadata) => void
//   violations    server-returned [{field,severity,code,message,suggestion}] from the last save/dry-run
//   schema        optional injected schema (tests); otherwise fetched via schemaClient
//   validateRaw   optional ({frontmatter}) => Promise<{metadata,violations}> for Raw→Form sync
//   pageSearch    optional (query) => Promise<options> for the related-pages picker
import { useEffect, useMemo, useRef, useState } from 'react';
import Tabs from '../ui/Tabs';
import FieldWidget from './FieldWidget';
import { getSchema } from './schemaClient';
import { metadataToYaml } from '../../utils/frontmatterUtils';
import { api } from '../../api/client';

// Always-open "Common" block. Everything else editable falls into the "More fields" disclosure;
// READONLY fields are surfaced (read-only) in the compact meta strip instead of the grid.
const COMMON_KEYS = ['title', 'type', 'status', 'summary', 'tags', 'cluster'];

function hasValue(v) {
  return !(v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0));
}

function fmtMeta(v) {
  if (v == null) return '';
  if (Array.isArray(v)) return v.join(', ');
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}

function AdvancedKeyValues({ metadata, unknownKeys, onChange }) {
  if (unknownKeys.length === 0) return null;
  const removeKey = (k) => {
    const next = { ...metadata };
    delete next[k];
    onChange(next);
  };
  return (
    <details className="fm-advanced">
      <summary>
        Advanced ({unknownKeys.length} other {unknownKeys.length === 1 ? 'key' : 'keys'})
      </summary>
      <ul className="fm-advanced-list">
        {unknownKeys.map((k) => (
          <li key={k}>
            <code>{k}</code>: <code>{JSON.stringify(metadata[k])}</code>
            <button type="button" aria-label={`Remove ${k}`} onClick={() => removeKey(k)}>
              ×
            </button>
          </li>
        ))}
      </ul>
      <p className="fm-advanced-note">Edit these via the Raw YAML tab.</p>
    </details>
  );
}

function RawYaml({ metadata, onChange, validateRaw }) {
  const [text, setText] = useState(() => metadataToYaml(metadata));
  const [error, setError] = useState(null);
  const dirty = useRef(false);

  const sync = () => {
    if (!dirty.current) return;
    dirty.current = false;
    Promise.resolve(validateRaw({ frontmatter: text }))
      .then((res) => {
        if (res && res.metadata) {
          setError(null);
          onChange(res.metadata);
        } else {
          const yaml = (res?.violations || []).find((v) => v.field === '__yaml__');
          setError(yaml ? yaml.message : 'Could not parse YAML.');
        }
      })
      .catch(() => setError('Could not parse YAML.'));
  };

  return (
    <div className="fm-raw">
      <textarea
        className="fm-raw-textarea"
        aria-label="Raw frontmatter YAML"
        value={text}
        onChange={(e) => {
          dirty.current = true;
          setText(e.target.value);
        }}
        onBlur={sync}
      />
      {error && <p className="fm-raw-error">{error}</p>}
    </div>
  );
}

export default function FrontmatterEditor({
  metadata,
  onChange,
  violations = [],
  schema: schemaProp,
  validateRaw = (payload) => api.validateFrontmatter(payload),
  pageSearch,
}) {
  const [schema, setSchema] = useState(schemaProp ?? null);
  const [tab, setTab] = useState('form');
  const [moreOpen, setMoreOpen] = useState(false);
  const moreInit = useRef(false);

  useEffect(() => {
    if (schemaProp) {
      setSchema(schemaProp);
      return undefined;
    }
    let alive = true;
    getSchema()
      .then((s) => {
        if (alive) setSchema(s);
      })
      .catch(() => {
        /* schema fetch failed; editor stays in loading state — surfaced by the caller's error path */
      });
    return () => {
      alive = false;
    };
  }, [schemaProp]);

  // Partition the schema's fields once per schema: Common (always open), More (collapsible),
  // and the READONLY derived fields (meta strip). Order is preserved from the schema.
  const { commonFields, moreFields, readonlyFields } = useMemo(() => {
    const fields = schema?.fields || [];
    const common = [];
    const more = [];
    const readonly = [];
    for (const f of fields) {
      if (f.widget === 'READONLY') readonly.push(f);
      else if (COMMON_KEYS.includes(f.key)) common.push(f);
      else more.push(f);
    }
    return { commonFields: common, moreFields: more, readonlyFields: readonly };
  }, [schema]);

  // One-shot: the first time metadata is populated, open "More" if any of its fields already
  // has a value (so editing an existing runbook/verified page never hides populated data).
  // Strictly additive — once the user collapses it manually, re-renders never re-open it.
  useEffect(() => {
    if (moreInit.current) return;
    if (metadata && Object.keys(metadata).length > 0) {
      moreInit.current = true;
      if (moreFields.some((f) => hasValue(metadata[f.key]))) setMoreOpen(true);
    }
  }, [metadata, moreFields]);

  if (!schema) return <div className="fm-editor-loading">Loading editor…</div>;

  const knownKeys = new Set((schema.fields || []).map((f) => f.key));
  const unknownKeys = Object.keys(metadata || {}).filter((k) => !knownKeys.has(k));

  const setField = (key, val) => {
    const next = { ...(metadata || {}) };
    const empty =
      val === undefined || val === null || val === '' || (Array.isArray(val) && val.length === 0);
    if (empty) delete next[key];
    else next[key] = val;
    onChange?.(next);
  };

  const violationsFor = (key) =>
    violations.filter((v) => v.field === key || (v.field && v.field.startsWith(key + '.')));

  const renderField = (f) => (
    <FieldWidget
      key={f.key}
      spec={f}
      value={metadata?.[f.key]}
      onChange={(v) => setField(f.key, v)}
      violations={violationsFor(f.key)}
      onApplySuggestion={(s) => setField(f.key, s)}
      pageSearch={pageSearch}
    />
  );

  const metaItems = readonlyFields
    .filter((f) => hasValue(metadata?.[f.key]))
    .map((f) => ({ key: f.key, label: f.label, value: fmtMeta(metadata[f.key]) }));

  return (
    <div className="fm-editor">
      <Tabs
        tabs={[{ id: 'form', label: 'Form' }, { id: 'raw', label: 'Raw YAML' }]}
        active={tab}
        onChange={setTab}
      >
        {tab === 'form' ? (
          <div className="fm-form-wrap">
            <div className="fm-form">{commonFields.map(renderField)}</div>

            {moreFields.length > 0 && (
              <details
                className="fm-more"
                open={moreOpen}
                onToggle={(e) => setMoreOpen(e.currentTarget.open)}
              >
                <summary className="fm-more-summary">More fields</summary>
                <div className="fm-form">{moreFields.map(renderField)}</div>
              </details>
            )}

            {metaItems.length > 0 && (
              <div className="fm-meta-strip">
                {metaItems.map((m) => (
                  <span key={m.key} className="fm-meta-item" title={`${m.label}: ${m.value}`}>
                    <span className="fm-meta-label">{m.label}</span>: {m.value}
                  </span>
                ))}
              </div>
            )}

            <AdvancedKeyValues metadata={metadata || {}} unknownKeys={unknownKeys} onChange={onChange} />
          </div>
        ) : (
          <RawYaml metadata={metadata} onChange={onChange} validateRaw={validateRaw} />
        )}
      </Tabs>
    </div>
  );
}
```

- [ ] **Step 4: Run the FrontmatterEditor tests to verify they pass**

Run: `cd wikantik-frontend && npx vitest run src/components/frontmatter/FrontmatterEditor.test.jsx`
Expected: PASS — all tests (the 7 original + 4 new). If one fails oddly, re-run this single file once before investigating (vitest concurrency flake).

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/frontmatter/FrontmatterEditor.jsx \
        wikantik-frontend/src/components/frontmatter/FrontmatterEditor.test.jsx
git commit -m "feat(frontmatter): split fields into Common / More zippy / meta strip"
```

---

## Task 3: Dense inline-label styling

Convert `.fm-field` from label-above-control to a 2-column `[label][control]` grid, span title + wide fields full-row, keep runbook stacked, and style the new More disclosure + meta strip. This is the change that produces the visible size reduction.

**Files:**
- Modify: `wikantik-frontend/src/styles/globals.css:2187-2219` (the density-grid + fm-field block) and add the new rules.

- [ ] **Step 1: Replace the density-grid block**

In `globals.css`, replace the block currently at lines 2187-2202 (from the `/* Density grid... */` comment through the `.fm-readonly` rule):

```css
/* Density grid: short scalars 2-up, wide fields full row */
.fm-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-sm) var(--space-md); }
.fm-field { display: flex; flex-direction: column; gap: var(--space-xs); min-width: 0; }
.fm-field--wide { grid-column: 1 / -1; }
@media (max-width: 720px) { .fm-form { grid-template-columns: 1fr; } }

.fm-label { font-size: 0.8rem; font-weight: 600; color: var(--text-secondary); }
.fm-input { width: 100%; }
.fm-textarea {
  font: inherit; width: 100%; min-height: 4rem; padding: var(--space-sm);
  border: 1px solid var(--border); border-radius: 4px; background: var(--bg-elevated); color: var(--text);
}
.fm-text { display: flex; align-items: center; gap: var(--space-sm); }
.fm-counter { font-size: 0.75rem; color: var(--text-muted); white-space: nowrap; }
.fm-counter-warn { color: var(--warning); font-weight: 600; }
.fm-readonly { font-family: var(--font-mono); color: var(--text-muted); font-size: 0.85rem; }
```

with:

```css
/* Density grid: short scalars 2-up with inline [label][control] rows; wide fields full row. */
.fm-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-xs) var(--space-md); }
.fm-field {
  display: grid;
  grid-template-columns: minmax(4.5rem, 6.5rem) 1fr;
  align-items: center;
  gap: var(--space-sm);
  min-width: 0;
}
.fm-control { min-width: 0; display: flex; flex-direction: column; gap: var(--space-xs); }
/* Long-content fields span the full row; their label top-aligns against the tall control. */
.fm-field--wide { grid-column: 1 / -1; align-items: start; }
.fm-field--wide > .fm-label { padding-top: 0.3rem; }
/* Title always gets the full width even though it is a short TEXT control. */
.fm-field-title { grid-column: 1 / -1; }
/* The runbook block is a multi-field sub-editor — keep its label stacked above it. */
.fm-field-runbook { display: flex; flex-direction: column; align-items: stretch; gap: var(--space-xs); }
.fm-field-runbook > .fm-label { text-align: left; }
@media (max-width: 720px) {
  .fm-form { grid-template-columns: 1fr; }
  .fm-field { grid-template-columns: minmax(4.5rem, 6.5rem) 1fr; }
}

.fm-label { font-size: 0.8rem; font-weight: 600; color: var(--text-secondary); text-align: right; }
.fm-input { width: 100%; }
.fm-textarea {
  font: inherit; width: 100%; min-height: 4rem; padding: var(--space-sm);
  border: 1px solid var(--border); border-radius: 4px; background: var(--bg-elevated); color: var(--text);
}
.fm-text { display: flex; align-items: center; gap: var(--space-sm); }
.fm-counter { font-size: 0.75rem; color: var(--text-muted); white-space: nowrap; }
.fm-counter-warn { color: var(--warning); font-weight: 600; }
.fm-readonly { font-family: var(--font-mono); color: var(--text-muted); font-size: 0.85rem; }
```

- [ ] **Step 2: Add the More-disclosure and meta-strip rules**

Immediately AFTER the `.fm-advanced-note` rule (currently line 2217), insert:

```css
/* "More fields" disclosure */
.fm-more { margin-top: var(--space-sm); }
.fm-more-summary {
  cursor: pointer; font-size: 0.72rem; text-transform: uppercase; letter-spacing: 0.06em;
  color: var(--text-muted); padding: var(--space-xs) 0; list-style-position: inside;
}
.fm-more[open] > .fm-form { margin-top: var(--space-xs); }

/* Read-only derived fields, surfaced as a single muted strip at the bottom */
.fm-meta-strip {
  display: flex; flex-wrap: wrap; align-items: baseline; margin-top: var(--space-sm);
  font-size: 0.72rem; color: var(--text-muted);
}
.fm-meta-item { max-width: 22rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fm-meta-item:not(:last-child)::after { content: '·'; margin: 0 var(--space-sm); color: var(--border); }
.fm-meta-label { font-weight: 600; color: var(--text-secondary); }
```

- [ ] **Step 3: Build the frontend to confirm the CSS compiles**

Run: `cd wikantik-frontend && npm run build`
Expected: build succeeds (`vite build` exits 0, "✓ built in ..."). A CSS syntax error would fail the build here.

- [ ] **Step 4: Run the full frontmatter test directory to confirm no regression**

Run: `cd wikantik-frontend && npx vitest run src/components/frontmatter/`
Expected: PASS — all frontmatter component tests (FieldWidget, FrontmatterEditor, RunbookBlockEditor, ValidationSummary, fieldLayout).

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/styles/globals.css
git commit -m "style(frontmatter): inline-label density grid + More/meta-strip styling"
```

---

## Task 4: Full verification

Confirm the whole frontend test suite is green and the WAR builds, then do a quick manual visual check.

**Files:** none (verification only).

- [ ] **Step 1: Run the full frontend test suite**

Run: `cd wikantik-frontend && npm test`
Expected: PASS — entire Vitest suite green. (If a single frontmatter file flakes under concurrency, re-run it alone with `npx vitest run <file>` to confirm it's a false flake, per the documented gotcha.)

- [ ] **Step 2: Build the WAR (bundles the React build) to confirm integration**

Run: `mvn -q -pl wikantik-frontend -am package -DskipTests`
Expected: BUILD SUCCESS. (This re-runs the Vite build inside the Maven frontend module; a JSX/CSS error would fail here.)

- [ ] **Step 3: Manual visual check (optional but recommended)**

Deploy locally (`bin/redeploy.sh` after the WAR build, or `cd wikantik-frontend && npm run dev`), open the editor for an existing article, and confirm:
- The Common block (title/type/status/summary/tags/cluster) shows by default with inline labels.
- "More fields" is collapsed for a plain article, and auto-expanded when editing a runbook or verified page.
- The read-only meta strip (canonical_id / confidence / agent_hints) shows as one muted line only when those have values.
- Overall the section is markedly shorter than before (~200–260px collapsed).

- [ ] **Step 4: No commit needed** — all code is already committed in Tasks 1-3.

---

## Self-Review Notes (for the author — not a step)

- **Spec coverage:** Common set (Task 2), More zippy + auto-open (Task 2), meta strip (Task 2), inline-label density + title/wide/runbook layout (Task 3), `.fm-control` wrapper (Task 1), tests (all tasks), verification (Task 4). All spec sections covered.
- **Type/name consistency:** `COMMON_KEYS`, `hasValue`, `fmtMeta`, `renderField`, `moreOpen`/`moreInit`, classes `.fm-control`/`.fm-more`/`.fm-more-summary`/`.fm-meta-strip`/`.fm-meta-item`/`.fm-meta-label`/`.fm-field-title`/`.fm-field-runbook` are used identically across the JSX and CSS tasks.
- **No placeholders:** every code/CSS step shows complete content.
