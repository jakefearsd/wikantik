# Frontmatter Editor UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the structured frontmatter editor live debounced validation with inline, field-scoped violations; disable Save on blocking errors (warnings stay savable); and re-base its CSS onto the app's design tokens with a denser responsive grid.

**Architecture:** A new `useFrontmatterValidation` hook debounces `POST /api/frontmatter/validate` on the *serialized* metadata (the exact bytes the save persists), returning `{violations, validating}`. `PageEditor` owns the hook, renders a `ValidationSummary`, passes violations into the existing `FrontmatterEditor`/`FieldWidget`/`RunbookBlockEditor` chain (which already renders an inline `ViolationList`), and gates the Save button. A CSS-only pass rebases `fm-*` onto real tokens and a 2-up grid. An optional server task maps runbook `FilterException`s to a structured 422.

**Tech Stack:** React 18, Vite, vitest + @testing-library/react (frontend); Java 21, JUnit 5 + Mockito, Cargo IT (optional server task).

**Spec:** `docs/superpowers/specs/2026-06-10-frontmatter-editor-ux-design.md`

---

### Task 1: `useFrontmatterValidation` hook

**Files:**
- Create: `wikantik-frontend/src/hooks/useFrontmatterValidation.js`
- Test: `wikantik-frontend/src/hooks/useFrontmatterValidation.test.js`

- [ ] **Step 1: Write the failing test**

```js
// wikantik-frontend/src/hooks/useFrontmatterValidation.test.js
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { useFrontmatterValidation } from './useFrontmatterValidation';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

const ERR = { violations: [{ field: 'type', severity: 'ERROR', code: 'x', message: 'bad' }] };

describe('useFrontmatterValidation', () => {
  it('debounces: many rapid edits coalesce into one validate call', async () => {
    const validate = vi.fn().mockResolvedValue({ violations: [] });
    const { rerender } = renderHook(({ m }) => useFrontmatterValidation(m, { validate, debounceMs: 400 }), {
      initialProps: { m: { title: 'a' } },
    });
    rerender({ m: { title: 'ab' } });
    rerender({ m: { title: 'abc' } });
    expect(validate).not.toHaveBeenCalled();
    await act(async () => { vi.advanceTimersByTime(400); });
    expect(validate).toHaveBeenCalledTimes(1);
  });

  it('drops a stale response when a newer request has started (race safety)', async () => {
    let resolveFirst;
    const validate = vi
      .fn()
      .mockImplementationOnce(() => new Promise((r) => { resolveFirst = r; }))
      .mockResolvedValueOnce(ERR);
    const { result, rerender } = renderHook(({ m }) => useFrontmatterValidation(m, { validate, debounceMs: 10 }), {
      initialProps: { m: { title: 'a' } },
    });
    await act(async () => { vi.advanceTimersByTime(10); });   // fires request #1 (pending)
    rerender({ m: { title: 'b' } });
    await act(async () => { vi.advanceTimersByTime(10); });   // fires request #2 (resolves ERR)
    await act(async () => { resolveFirst({ violations: [] }); }); // late #1 resolves empty
    await waitFor(() => expect(result.current.violations).toEqual(ERR.violations));
  });

  it('fails open: a rejected validate keeps the last violations and never throws', async () => {
    const validate = vi.fn().mockResolvedValueOnce(ERR).mockRejectedValueOnce(new Error('net'));
    const { result, rerender } = renderHook(({ m }) => useFrontmatterValidation(m, { validate, debounceMs: 10 }), {
      initialProps: { m: { title: 'a' } },
    });
    await act(async () => { vi.advanceTimersByTime(10); });
    await waitFor(() => expect(result.current.violations).toEqual(ERR.violations));
    rerender({ m: { title: 'b' } });
    await act(async () => { vi.advanceTimersByTime(10); });
    // still the prior result — outage did not clear or block
    expect(result.current.violations).toEqual(ERR.violations);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd wikantik-frontend && npx vitest run src/hooks/useFrontmatterValidation.test.js`
Expected: FAIL — `useFrontmatterValidation` is not exported / file missing.

- [ ] **Step 3: Write minimal implementation**

```js
// wikantik-frontend/src/hooks/useFrontmatterValidation.js
import { useEffect, useRef, useState } from 'react';
import { metadataToYaml } from '../utils/frontmatterUtils';
import { api } from '../api/client';

// Debounced, race-safe, fail-open live frontmatter validation. Validates the SERIALIZED
// YAML (the exact bytes the save persists) so live results cannot disagree with the save gate.
export function useFrontmatterValidation(metadata, {
  enabled = true,
  debounceMs = 400,
  validate = api.validateFrontmatter,
} = {}) {
  const [violations, setViolations] = useState([]);
  const [validating, setValidating] = useState(false);
  const reqIdRef = useRef(0);

  const yaml = enabled ? metadataToYaml(metadata || {}) : null;

  useEffect(() => {
    if (!enabled) {
      setViolations([]);
      setValidating(false);
      return undefined;
    }
    const handle = setTimeout(() => {
      const myId = ++reqIdRef.current;
      setValidating(true);
      Promise.resolve(validate({ frontmatter: yaml }))
        .then((res) => {
          if (myId !== reqIdRef.current) return; // a newer request superseded this one
          setViolations((res && res.violations) || []);
        })
        .catch(() => {
          // fail-open: keep the last violations; the server stays the final gate
        })
        .finally(() => {
          if (myId === reqIdRef.current) setValidating(false);
        });
    }, debounceMs);
    return () => clearTimeout(handle);
  }, [yaml, enabled, debounceMs, validate]);

  return { violations, validating };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd wikantik-frontend && npx vitest run src/hooks/useFrontmatterValidation.test.js`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/hooks/useFrontmatterValidation.js wikantik-frontend/src/hooks/useFrontmatterValidation.test.js
git commit -m "feat(frontmatter): debounced race-safe fail-open validation hook"
```

---

### Task 2: `isWideField` layout helper

**Files:**
- Create: `wikantik-frontend/src/components/frontmatter/fieldLayout.js`
- Test: `wikantik-frontend/src/components/frontmatter/fieldLayout.test.js`

- [ ] **Step 1: Write the failing test**

```js
// wikantik-frontend/src/components/frontmatter/fieldLayout.test.js
import { describe, it, expect } from 'vitest';
import { isWideField } from './fieldLayout';

describe('isWideField', () => {
  it('marks textarea/tags/page-refs/runbook widgets wide', () => {
    expect(isWideField({ widget: 'TEXTAREA' })).toBe(true);
    expect(isWideField({ widget: 'TAGS' })).toBe(true);
    expect(isWideField({ widget: 'PAGE_REFS' })).toBe(true);
    expect(isWideField({ widget: 'RUNBOOK_BLOCK' })).toBe(true);
  });
  it('marks a long TEXT field (maxLen >= 80) wide', () => {
    expect(isWideField({ widget: 'TEXT', maxLen: 160 })).toBe(true);
  });
  it('keeps short scalars 2-up', () => {
    expect(isWideField({ widget: 'TEXT', maxLen: 40 })).toBe(false);
    expect(isWideField({ widget: 'ENUM' })).toBe(false);
    expect(isWideField({ widget: 'DATE' })).toBe(false);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd wikantik-frontend && npx vitest run src/components/frontmatter/fieldLayout.test.js`
Expected: FAIL — `isWideField` missing.

- [ ] **Step 3: Write minimal implementation**

```js
// wikantik-frontend/src/components/frontmatter/fieldLayout.js
// A field spans the full grid row when it is a multi-value/long-text widget,
// or a TEXT field whose maxLen is large enough to need the width (e.g. summary).
const WIDE_WIDGETS = new Set(['TEXTAREA', 'TAGS', 'PAGE_REFS', 'RUNBOOK_BLOCK']);

export function isWideField(spec) {
  if (!spec) return false;
  if (WIDE_WIDGETS.has(spec.widget)) return true;
  return spec.widget === 'TEXT' && typeof spec.maxLen === 'number' && spec.maxLen >= 80;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd wikantik-frontend && npx vitest run src/components/frontmatter/fieldLayout.test.js`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/frontmatter/fieldLayout.js wikantik-frontend/src/components/frontmatter/fieldLayout.test.js
git commit -m "feat(frontmatter): isWideField grid-layout predicate"
```

---

### Task 3: `ValidationSummary` component

**Files:**
- Create: `wikantik-frontend/src/components/frontmatter/ValidationSummary.jsx`
- Test: `wikantik-frontend/src/components/frontmatter/ValidationSummary.test.jsx`

- [ ] **Step 1: Write the failing test**

```jsx
// wikantik-frontend/src/components/frontmatter/ValidationSummary.test.jsx
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import ValidationSummary from './ValidationSummary';

describe('ValidationSummary', () => {
  it('shows a valid state when there are no violations', () => {
    render(<ValidationSummary violations={[]} validating={false} />);
    expect(screen.getByText(/frontmatter valid/i)).toBeTruthy();
  });

  it('shows error and warning counts and jumps to the first error field', () => {
    const onJump = vi.fn();
    render(
      <ValidationSummary
        violations={[
          { field: 'runbook.related_tools', severity: 'ERROR', code: 'a', message: 'x' },
          { field: 'summary', severity: 'WARNING', code: 'b', message: 'y' },
          { field: 'tags', severity: 'WARNING', code: 'c', message: 'z' },
        ]}
        validating={false}
        onJump={onJump}
      />,
    );
    expect(screen.getByText('1 error')).toBeTruthy();
    expect(screen.getByText('2 warnings')).toBeTruthy();
    fireEvent.click(screen.getByText('1 error'));
    expect(onJump).toHaveBeenCalledWith('runbook.related_tools');
  });

  it('shows a checking state while validating with no prior result', () => {
    render(<ValidationSummary violations={[]} validating />);
    expect(screen.getByText(/checking/i)).toBeTruthy();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd wikantik-frontend && npx vitest run src/components/frontmatter/ValidationSummary.test.jsx`
Expected: FAIL — component missing.

- [ ] **Step 3: Write minimal implementation**

```jsx
// wikantik-frontend/src/components/frontmatter/ValidationSummary.jsx
// Compact validation status strip atop the editor. States: checking / valid / counts.
// Counts are buttons that jump to the first field of that severity.
import Badge from '../ui/Badge';
import Spinner from '../ui/Spinner';

export default function ValidationSummary({ violations = [], validating = false, onJump }) {
  const errors = violations.filter((v) => v.severity === 'ERROR');
  const warnings = violations.filter((v) => v.severity === 'WARNING');

  if (validating && violations.length === 0) {
    return (
      <div className="fm-summary fm-summary-checking">
        <Spinner size="sm" label="Checking" /> Checking…
      </div>
    );
  }
  if (errors.length === 0 && warnings.length === 0) {
    return <div className="fm-summary fm-summary-valid">✓ Frontmatter valid</div>;
  }
  const plural = (n, w) => `${n} ${w}${n === 1 ? '' : 's'}`;
  return (
    <div className="fm-summary">
      {errors.length > 0 && (
        <button type="button" className="fm-summary-count" onClick={() => onJump?.(errors[0].field)}>
          <Badge variant="danger">{plural(errors.length, 'error')}</Badge>
        </button>
      )}
      {warnings.length > 0 && (
        <button type="button" className="fm-summary-count" onClick={() => onJump?.(warnings[0].field)}>
          <Badge variant="warning">{plural(warnings.length, 'warning')}</Badge>
        </button>
      )}
    </div>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd wikantik-frontend && npx vitest run src/components/frontmatter/ValidationSummary.test.jsx`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/frontmatter/ValidationSummary.jsx wikantik-frontend/src/components/frontmatter/ValidationSummary.test.jsx
git commit -m "feat(frontmatter): ValidationSummary status strip"
```

---

### Task 4: `RunbookBlockEditor` renders per-sub-field violations

**Files:**
- Modify: `wikantik-frontend/src/components/frontmatter/RunbookBlockEditor.jsx`
- Test: `wikantik-frontend/src/components/frontmatter/RunbookBlockEditor.test.jsx` (create)

- [ ] **Step 1: Write the failing test**

```jsx
// wikantik-frontend/src/components/frontmatter/RunbookBlockEditor.test.jsx
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import RunbookBlockEditor from './RunbookBlockEditor';

describe('RunbookBlockEditor', () => {
  it('renders a runbook.related_tools violation next to the related_tools control', () => {
    render(
      <RunbookBlockEditor
        value={{ related_tools: ['/admin/x'], when_to_use: ['a'] }}
        onChange={() => {}}
        violations={[
          { field: 'runbook.related_tools', severity: 'ERROR', code: 'related_tool_invalid', message: 'bad tool' },
        ]}
      />,
    );
    const field = screen.getByLabelText('runbook related_tools').closest('.fm-runbook-field');
    expect(field.textContent).toContain('bad tool');
    // an unrelated subfield shows no violation
    const other = screen.getByLabelText('runbook when_to_use').closest('.fm-runbook-field');
    expect(other.textContent).not.toContain('bad tool');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd wikantik-frontend && npx vitest run src/components/frontmatter/RunbookBlockEditor.test.jsx`
Expected: FAIL — violations not rendered (prop ignored).

- [ ] **Step 3: Write minimal implementation** (full file replacement)

```jsx
// RunbookBlockEditor.jsx
// Nested editor for the `runbook:` block. Each sub-field is a list of strings, edited as a textarea
// with one entry per line. The server-side FrontmatterRunbookValidator remains the authority; this
// renders the per-sub-field violations it returns (field path `runbook.<key>`) inline.
import ViolationList from './ViolationList';

const RUNBOOK_FIELDS = [
  { k: 'when_to_use', label: 'When to use (≥1)' },
  { k: 'inputs', label: 'Inputs' },
  { k: 'steps', label: 'Steps (≥2)' },
  { k: 'pitfalls', label: 'Pitfalls (≥1, use "(none known)")' },
  { k: 'related_tools', label: 'Related tools' },
  { k: 'references', label: 'References' },
];

export default function RunbookBlockEditor({ value, onChange, violations = [] }) {
  const block = value && typeof value === 'object' && !Array.isArray(value) ? value : {};

  const setList = (k, text) => {
    const list = text.split('\n').map((s) => s.trim()).filter(Boolean);
    const next = { ...block };
    if (list.length === 0) delete next[k];
    else next[k] = list;
    onChange(Object.keys(next).length ? next : undefined);
  };

  const violationsFor = (k) => violations.filter((v) => v.field === `runbook.${k}`);

  return (
    <div className="fm-runbook">
      {RUNBOOK_FIELDS.map(({ k, label }) => (
        <div key={k} className="fm-runbook-field">
          <label className="fm-label">{label}</label>
          <textarea
            className="fm-textarea"
            aria-label={`runbook ${k}`}
            placeholder="one per line"
            value={(Array.isArray(block[k]) ? block[k] : []).join('\n')}
            onChange={(e) => setList(k, e.target.value)}
          />
          <ViolationList violations={violationsFor(k)} />
        </div>
      ))}
    </div>
  );
}
```

- [ ] **Step 3b: Extract `ViolationList` into its own module** (currently inlined in `FieldWidget.jsx`)

Create `wikantik-frontend/src/components/frontmatter/ViolationList.jsx`:

```jsx
// ViolationList.jsx
// Renders a list of {severity, message, suggestion} violations inline, with an optional
// "apply suggestion" affordance. Shared by FieldWidget and RunbookBlockEditor.
export default function ViolationList({ violations, onApplySuggestion }) {
  if (!violations || violations.length === 0) return null;
  return (
    <ul className="fm-violations">
      {violations.map((v, i) => (
        <li key={i} className={`fm-violation fm-violation-${(v.severity || '').toLowerCase()}`}>
          <span className="fm-violation-msg">{v.message}</span>
          {v.suggestion && onApplySuggestion && (
            <button
              type="button"
              className="fm-apply-suggestion"
              onClick={() => onApplySuggestion(v.suggestion)}
            >
              Use “{v.suggestion}”
            </button>
          )}
        </li>
      ))}
    </ul>
  );
}
```

Then in `FieldWidget.jsx`, delete the local `ViolationList` function (lines ~19-39) and add at the top with the other imports:

```jsx
import ViolationList from './ViolationList';
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd wikantik-frontend && npx vitest run src/components/frontmatter/RunbookBlockEditor.test.jsx src/components/frontmatter/FieldWidget.test.jsx`
Expected: PASS (existing FieldWidget tests still green after the extraction; new RunbookBlockEditor test green).

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/frontmatter/RunbookBlockEditor.jsx wikantik-frontend/src/components/frontmatter/RunbookBlockEditor.test.jsx wikantik-frontend/src/components/frontmatter/ViolationList.jsx wikantik-frontend/src/components/frontmatter/FieldWidget.jsx
git commit -m "feat(frontmatter): runbook sub-field violations; extract ViolationList"
```

---

### Task 5: Wire violations + data-field + wide-class through `FieldWidget` and `FrontmatterEditor`

**Files:**
- Modify: `wikantik-frontend/src/components/frontmatter/FieldWidget.jsx`
- Modify: `wikantik-frontend/src/components/frontmatter/FrontmatterEditor.jsx`
- Test: `wikantik-frontend/src/components/frontmatter/FrontmatterEditor.test.jsx` (extend)

- [ ] **Step 1: Write the failing test** (append to the existing `describe` in `FrontmatterEditor.test.jsx`)

```jsx
  it('tags each field wrapper with data-field and marks wide fields', () => {
    const schema = {
      fields: [
        { key: 'type', label: 'Type', widget: 'ENUM', canonicalValues: ['article'], open: true },
        { key: 'summary', label: 'Summary', widget: 'TEXT', minLen: 50, maxLen: 160 },
      ],
    };
    const { container } = render(
      <FrontmatterEditor schema={schema} metadata={{ type: 'article' }} onChange={() => {}} />,
    );
    expect(container.querySelector('[data-field="type"]')).toBeTruthy();
    const summary = container.querySelector('[data-field="summary"]');
    expect(summary.className).toContain('fm-field--wide');
    const type = container.querySelector('[data-field="type"]');
    expect(type.className).not.toContain('fm-field--wide');
  });

  it('routes runbook.* violations into the runbook block editor', () => {
    const schema = { fields: [{ key: 'runbook', label: 'Runbook', widget: 'RUNBOOK_BLOCK' }] };
    render(
      <FrontmatterEditor
        schema={schema}
        metadata={{ runbook: { related_tools: ['/admin/x'] } }}
        onChange={() => {}}
        violations={[{ field: 'runbook.related_tools', severity: 'ERROR', code: 'x', message: 'bad tool' }]}
      />,
    );
    const field = screen.getByLabelText('runbook related_tools').closest('.fm-runbook-field');
    expect(field.textContent).toContain('bad tool');
  });
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd wikantik-frontend && npx vitest run src/components/frontmatter/FrontmatterEditor.test.jsx`
Expected: FAIL — no `data-field`, no `fm-field--wide`, runbook violations not forwarded.

- [ ] **Step 3a: Update `FieldWidget.jsx`** — add `data-field`, wide-class, and forward violations to the runbook block.

Replace the `RUNBOOK_BLOCK` case so it forwards violations:

```jsx
    case 'RUNBOOK_BLOCK':
      control = <RunbookBlockEditor value={value} onChange={onChange} violations={violations} />;
      break;
```

Replace the component's `return (...)` wrapper with one that tags the field and, for the runbook block, shows only block-level (`field === 'runbook'`) violations in its own list (sub-field ones render inside the block):

```jsx
  const isRunbook = widget === 'RUNBOOK_BLOCK';
  const ownViolations = isRunbook ? violations.filter((v) => v.field === 'runbook') : violations;

  return (
    <div className={`fm-field fm-field-${key}${isWideField(spec) ? ' fm-field--wide' : ''}`} data-field={key}>
      <label className="fm-label">{label}</label>
      {control}
      <ViolationList violations={ownViolations} onApplySuggestion={onApplySuggestion} />
    </div>
  );
```

Add the import at the top of `FieldWidget.jsx`:

```jsx
import { isWideField } from './fieldLayout';
```

(`violations` may be undefined for fields with none — default it: change the destructure usage to `const violations = props.violations || []` or guard. Concretely, update the signature to default it.) Update the function signature line to:

```jsx
export default function FieldWidget({ spec, value, onChange, violations = [], onApplySuggestion, pageSearch }) {
```

- [ ] **Step 3b: `FrontmatterEditor.jsx`** needs no structural change** — it already passes `violationsFor(f.key)` (which includes `runbook.*`) to each `FieldWidget`. Confirm the `setField`/`violationsFor` logic is unchanged. (No edit required; this step is a verification read.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd wikantik-frontend && npx vitest run src/components/frontmatter/FrontmatterEditor.test.jsx src/components/frontmatter/FieldWidget.test.jsx`
Expected: PASS (existing + 2 new).

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/frontmatter/FieldWidget.jsx wikantik-frontend/src/components/frontmatter/FrontmatterEditor.test.jsx
git commit -m "feat(frontmatter): data-field tags, wide-class, runbook violation routing"
```

---

### Task 6: Integrate live validation + Save gating in `PageEditor`

**Files:**
- Modify: `wikantik-frontend/src/components/PageEditor.jsx`
- Test: `wikantik-frontend/src/components/PageEditor.test.jsx` (extend)

- [ ] **Step 1: Write the failing test** (append to `PageEditor.test.jsx`; mirror that file's existing render/mocking setup — it already mocks `../api/client`)

```jsx
  it('disables Save when live validation reports an ERROR, and re-enables when only warnings remain', async () => {
    const { api } = await import('../api/client');
    // first validate -> ERROR, second -> warning-only
    api.validateFrontmatter
      .mockResolvedValueOnce({ violations: [{ field: 'type', severity: 'ERROR', code: 'x', message: 'bad' }] })
      .mockResolvedValue({ violations: [{ field: 'summary', severity: 'WARNING', code: 'y', message: 'long' }] });

    renderEditor({ name: 'Sample', initialContent: '---\ntype: runbook\n---\nbody' });

    const save = await screen.findByTestId('editor-save');
    await waitFor(() => expect(save.disabled).toBe(true));   // blocked on the ERROR

    // trigger a metadata change so the second validate (warning-only) runs
    fireEvent.change(screen.getByLabelText(/summary/i), { target: { value: 'x'.repeat(10) } });
    await waitFor(() => expect(save.disabled).toBe(false));  // warnings never block
  });
```

> Note: `renderEditor` is this file's existing helper. If the existing tests use a different bootstrap, match it — the assertion of interest is `editor-save` `.disabled` toggling with severity. Use `vi.useRealTimers()` here (the hook's 400 ms debounce resolves under `waitFor`'s default 1 s timeout) or set `debounceMs` low via the editor if exposed.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd wikantik-frontend && npx vitest run src/components/PageEditor.test.jsx`
Expected: FAIL — Save is only `disabled={saving}`; never reacts to violations.

- [ ] **Step 3: Implement**

In `PageEditor.jsx`, add the import:

```jsx
import { useFrontmatterValidation } from '../hooks/useFrontmatterValidation';
import ValidationSummary from './frontmatter/ValidationSummary';
```

After the `metadata` state is established (near the other `useState`/hooks, ~line 40), add:

```jsx
  const { violations: liveViolations, validating } = useFrontmatterValidation(metadata, {
    enabled: metadata != null,
  });
  // Live validation is authoritative for frontmatter; merge any save-response violations
  // (e.g. non-frontmatter save errors) not already represented live.
  const displayViolations = (() => {
    const seen = new Set(liveViolations.map((v) => `${v.field}|${v.code}`));
    return [...liveViolations, ...violations.filter((v) => !seen.has(`${v.field}|${v.code}`))];
  })();
  const hasBlockingErrors = displayViolations.some((v) => v.severity === 'ERROR');

  const jumpToField = (field) => {
    const top = String(field || '').split('.')[0];
    document.querySelector(`[data-field="${top}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  };
```

Change the Save button (line ~508) to:

```jsx
          <button
            className="btn btn-primary"
            data-testid="editor-save"
            onClick={save}
            disabled={saving || hasBlockingErrors}
            title={hasBlockingErrors ? 'Fix the highlighted frontmatter errors before saving' : undefined}
          >
```

Render `<ValidationSummary violations={displayViolations} validating={validating} onJump={jumpToField} />` immediately above the `<FrontmatterEditor ...>` element (line ~566), and change that element's `violations={violations}` prop to `violations={displayViolations}`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd wikantik-frontend && npx vitest run src/components/PageEditor.test.jsx`
Expected: PASS (existing + new). If a pre-existing test breaks because `validateFrontmatter` now fires on mount, give it a default `mockResolvedValue({ violations: [] })` in that file's `beforeEach`.

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/PageEditor.jsx wikantik-frontend/src/components/PageEditor.test.jsx
git commit -m "feat(frontmatter): live validation, ValidationSummary, Save gating in PageEditor"
```

---

### Task 7: CSS token rebase + density grid

**Files:**
- Modify: `wikantik-frontend/src/styles/globals.css` (the `fm-*` block, ~lines 2179-2208)

- [ ] **Step 1: Replace the `fm-*` block** with token-based, denser rules and add summary styles:

```css
.fm-editor-loading { padding: var(--space-md); color: var(--text-muted); }

/* Validation summary strip */
.fm-summary { display: flex; align-items: center; gap: var(--space-sm); font-size: 0.8rem; margin-bottom: var(--space-sm); min-height: 1.5rem; }
.fm-summary-valid { color: var(--success); }
.fm-summary-checking { color: var(--text-muted); }
.fm-summary-count { background: none; border: none; padding: 0; cursor: pointer; }

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
.fm-violations { list-style: none; margin: var(--space-xs) 0 0; padding: 0; display: flex; flex-direction: column; gap: var(--space-xs); }
.fm-violation { font-size: 0.8rem; display: flex; align-items: center; gap: var(--space-sm); }
.fm-violation-error { color: var(--danger); }
.fm-violation-warning { color: var(--warning); }
.fm-apply-suggestion {
  font-size: 0.75rem; background: var(--code-bg); border: 1px solid var(--border);
  border-radius: 4px; padding: 0.1rem 0.4rem; cursor: pointer; color: var(--text); white-space: nowrap;
}
.fm-pagerefs { display: flex; flex-direction: column; gap: var(--space-xs); }
.fm-pagerefs-chips { display: flex; flex-wrap: wrap; gap: var(--space-xs); }
.fm-runbook { display: flex; flex-direction: column; gap: var(--space-sm); padding-left: var(--space-sm); border-left: 2px solid var(--border); }
.fm-runbook-field { display: flex; flex-direction: column; gap: var(--space-xs); }
.fm-advanced { margin-top: var(--space-sm); font-size: 0.85rem; }
.fm-advanced-list { list-style: none; margin: var(--space-xs) 0; padding: 0; display: flex; flex-direction: column; gap: var(--space-xs); }
.fm-advanced-note { font-size: 0.75rem; color: var(--text-muted); }
.fm-raw-textarea { font-family: var(--font-mono); width: 100%; min-height: 12rem; padding: var(--space-sm); border: 1px solid var(--border); border-radius: 4px; background: var(--bg-elevated); color: var(--text); }
.fm-raw-error { color: var(--danger); font-size: 0.8rem; }
```

- [ ] **Step 2: Build the frontend and verify no CSS/JS errors**

Run: `cd wikantik-frontend && npx vitest run src/components/frontmatter && npm run build`
Expected: vitest green; Vite build succeeds.

- [ ] **Step 3: Manual visual check** (verification-before-completion)

Rebuild the WAR and redeploy, then open the editor for a runbook page (e.g. `KgInclusionPolicy`) and confirm: 2-up grid for short fields, wide summary/tags/runbook, token colors (sage/clay accents, not generic hex), error/warning colors, and the summary strip.

```bash
cd /home/jakefear/source/jspwiki && mvn clean install -DskipTests -T 1C && bin/redeploy.sh
```

- [ ] **Step 4: Commit**

```bash
git add wikantik-frontend/src/styles/globals.css
git commit -m "style(frontmatter): rebase editor CSS on design tokens + density grid"
```

---

### Task 8 (OPTIONAL — server hardening, task C): structured 422 for runbook validation

> Low priority. With Save disabled on errors, the SPA can't reach the opaque path; this hardens REST/MCP callers. Skip if deferring. Gate any commit on the full IT reactor (`mvn clean install -Pintegration-tests -fae`) before the prod push.

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/exceptions/FrontmatterValidationException.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/agent/RunbookValidationPageFilter.java`
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/PageResource.java` (the `doPut` catch around lines 347-364)
- Test: `wikantik-rest` mockito unit for the 422 mapping; an IT under `wikantik-it-tests/.../rest` asserting the 422 violations shape.

- [ ] **Step 1: Write the failing unit test** — a `PageResource.doPut` that, when `saveText` throws `FrontmatterValidationException` carrying issues, responds `422` with a JSON `violations` array (mirror the existing `PageResource` unit-test harness/mocking in `wikantik-rest/src/test`). Assert status 422 and that the body contains the issue's field + message.

- [ ] **Step 2: Run it — FAIL** (the catch currently maps `FilterException` to a generic error).

- [ ] **Step 3: Implement**

`FrontmatterValidationException`:

```java
package com.wikantik.api.exceptions;

import java.util.List;

/** A save vetoed by frontmatter validation, carrying the structured field issues so the
 *  REST layer can return a 422 with the same violations shape as schema/YAML errors. */
public class FrontmatterValidationException extends FilterException {
    public record FieldIssue(String field, String severity, String code, String message) {}
    private final transient List<FieldIssue> issues;
    public FrontmatterValidationException(final String message, final List<FieldIssue> issues) {
        super(message);
        this.issues = issues == null ? List.of() : List.copyOf(issues);
    }
    public List<FieldIssue> issues() { return issues; }
}
```

In `RunbookValidationPageFilter.preSave`, build `FieldIssue`s from the validator result (map each `Issue.kind()` to its field path — reuse the same mapping as `SchemaDrivenFrontmatterValidator.runbookField`, or inline the switch) and throw `FrontmatterValidationException` instead of the bare `FilterException`.

In `PageResource.doPut`, add a catch BEFORE the generic handler:

```java
        } catch (final FrontmatterValidationException ve) {
            LOG.warn("Save of '{}' vetoed by frontmatter validation: {}", pageName, ve.getMessage());
            final JsonArray arr = new JsonArray();
            for (final var iss : ve.issues()) {
                final JsonObject o = new JsonObject();
                o.addProperty("field", iss.field());
                o.addProperty("severity", iss.severity());
                o.addProperty("code", iss.code());
                o.addProperty("message", iss.message());
                arr.add(o);
            }
            final JsonObject body = new JsonObject();
            body.add("violations", arr);
            sendJsonWithStatus(response, 422, body); // use this class's existing JSON-send helper
            return;
        }
```

(Match `PageResource`'s actual JSON-response helper name; if it differs from `sendJsonWithStatus`, use the one already imported in that file.)

- [ ] **Step 4: Run unit test — PASS.** Then `mvn test -pl wikantik-rest -Dtest=PageResource*`.

- [ ] **Step 5: Add + run the IT**, then `mvn clean install -Pintegration-tests -fae`.

- [ ] **Step 6: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/exceptions/FrontmatterValidationException.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/agent/RunbookValidationPageFilter.java \
        wikantik-rest/src/main/java/com/wikantik/rest/PageResource.java \
        wikantik-rest/src/test/java/... wikantik-it-tests/...
git commit -m "feat(frontmatter): structured 422 for runbook validation failures"
```

---

## Final verification (after Tasks 1-7; 8 if done)

- [ ] `cd wikantik-frontend && npx vitest run` — full frontend suite green (re-run any flaky file in isolation per project note).
- [ ] `npm run build` succeeds.
- [ ] Manual: edit `KgInclusionPolicy` in the deployed editor — break `related_tools`, confirm the error shows inline under that sub-field, the summary shows "1 error", and Save is disabled; fix it, confirm Save re-enables.
- [ ] If Task 8: `mvn clean install -Pintegration-tests -fae` green before any prod push.
