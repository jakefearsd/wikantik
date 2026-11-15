// Line diff for item revisions (LCS). Work revisions are not wiki page versions, so the page diff endpoint
// does not apply. Past MAX_CELLS the table is not built and the result is a plain remove-all / add-all.

const MAX_CELLS = 4_000_000;

const lines = (s) => (s === '' || s == null ? [] : String(s).split('\n'));

/** @returns {{type: 'same'|'add'|'del', text: string}[]} */
export function lineDiff(before, after) {
  const a = lines(before);
  const b = lines(after);
  if ((a.length + 1) * (b.length + 1) > MAX_CELLS) {
    return [...a.map((text) => ({ type: 'del', text })), ...b.map((text) => ({ type: 'add', text }))];
  }
  const w = b.length + 1;
  const t = new Uint32Array((a.length + 1) * w);
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      t[i * w + j] = a[i] === b[j] ? t[(i + 1) * w + j + 1] + 1 : Math.max(t[(i + 1) * w + j], t[i * w + j + 1]);
    }
  }
  const out = [];
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) { out.push({ type: 'same', text: a[i] }); i++; j++; }
    else if (t[(i + 1) * w + j] >= t[i * w + j + 1]) { out.push({ type: 'del', text: a[i++] }); }
    else { out.push({ type: 'add', text: b[j++] }); }
  }
  while (i < a.length) out.push({ type: 'del', text: a[i++] });
  while (j < b.length) out.push({ type: 'add', text: b[j++] });
  return out;
}
