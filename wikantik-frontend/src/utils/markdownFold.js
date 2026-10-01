import { foldNodeProp, foldService, foldState, foldedRanges, unfoldEffect } from '@codemirror/language';

const isFence = (text) => text.trim() === '---';

/**
 * Markdown parser extensions that limit fold markers to headings, the leading frontmatter block and fenced code:
 *  - a leading {@code ---} block parses as one {@code Frontmatter} node (until the closing fence, or the end of an
 *    unclosed block), so its last key/value line is no longer a setext heading with its own fold marker;
 *  - every block node except {@code FencedCode} loses its node-based fold, so paragraphs, blockquotes and list
 *    items get none. Headings fold through lang-markdown's section fold service; frontmatter through
 *    {@link frontmatterFold}.
 * Pass as {@code markdown({ extensions: editorFoldConfig })}.
 */
export const editorFoldConfig = [
  {
    defineNodes: [{ name: 'Frontmatter', block: true }],
    parseBlock: [{
      name: 'Frontmatter',
      before: 'HorizontalRule',
      parse(cx, line) {
        if (cx.lineStart !== 0 || !isFence(line.text)) return false;
        let end = line.text.length;
        while (cx.nextLine()) {
          end = cx.lineStart + line.text.length;
          if (isFence(line.text)) {
            cx.nextLine();
            break;
          }
        }
        cx.addElement(cx.elt('Frontmatter', 0, end));
        return true;
      },
    }],
  },
  {
    props: [foldNodeProp.add((type) => (type.is('Block') && type.name !== 'FencedCode' ? null : undefined))],
  },
];

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
