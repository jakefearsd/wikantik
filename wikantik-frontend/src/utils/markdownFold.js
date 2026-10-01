import { foldService, foldState, foldedRanges, unfoldEffect } from '@codemirror/language';

/** Fold range for a leading `---` frontmatter block: end of the opening fence to the end of the closing fence. */
export function frontmatterFoldRange(state, lineStart) {
  const first = state.doc.line(1);
  if (lineStart !== first.from || first.text.trim() !== '---') return null;
  for (let n = 2; n <= state.doc.lines; n += 1) {
    const line = state.doc.line(n);
    if (line.text.trim() === '---') return { from: first.to, to: line.to };
  }
  return null;
}

export const frontmatterFold = foldService.of((state, lineStart) => frontmatterFoldRange(state, lineStart));

/** Unfold effects that make {@code pos} visible (empty when nothing hides it). */
export function revealEffects(state, pos) {
  if (state.field(foldState, false) === undefined) return [];
  const effects = [];
  foldedRanges(state).between(pos, pos, (from, to) => {
    if (from <= pos && pos <= to) effects.push(unfoldEffect.of({ from, to }));
  });
  return effects;
}
