/** Eight callout tokens; the red ones (failure, danger, bug) are left out so a label never looks like the bug marker. */
export const LABEL_PALETTE = [
  'var(--callout-note)', 'var(--callout-abstract)', 'var(--callout-tip)', 'var(--callout-success)',
  'var(--callout-question)', 'var(--callout-warning)', 'var(--callout-example)', 'var(--callout-quote)',
];

/** A label's colour is a pure function of its name, so it is the same everywhere. */
export function labelColor(name) {
  let h = 0;
  const s = String(name);
  for (let i = 0; i < s.length; i += 1) h = (h * 31 + s.charCodeAt(i)) >>> 0;
  return LABEL_PALETTE[h % LABEL_PALETTE.length];
}
