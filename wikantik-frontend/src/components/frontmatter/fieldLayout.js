// A field spans the full grid row when it is a multi-value/long-text widget,
// or a TEXT field whose maxLen is large enough to need the width (e.g. summary).
const WIDE_WIDGETS = new Set(['TEXTAREA', 'TAGS', 'PAGE_REFS', 'RUNBOOK_BLOCK']);

export function isWideField(spec) {
  if (!spec) return false;
  if (WIDE_WIDGETS.has(spec.widget)) return true;
  return spec.widget === 'TEXT' && typeof spec.maxLen === 'number' && spec.maxLen >= 80;
}
