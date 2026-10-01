import { foldNodeProp, foldService, foldState, foldedRanges, unfoldEffect } from '@codemirror/language';

const isFence = (text) => text != null && text.trim() === '---';
export const YAML_KEY = /^[A-Za-z_][\w-]*\s*:/;
/** How far ahead the parser looks for a closing fence; a larger leading block is not treated as frontmatter. */
const FRONTMATTER_SCAN_CHARS = 64 * 1024;

/**
 * 1-based line number of the closing {@code ---} of a leading frontmatter block, or 0 when there is none.
 * A leading {@code ---} only opens frontmatter when the very next line is a YAML {@code key:} line and a closing
 * {@code ---} follows; anything else (a lone rule, two rules around prose, an unclosed block) is ordinary
 * markdown. {@code lineText(n)} returns line n (1-based).
 */
export function frontmatterCloseLine(lineText, lineCount) {
  if (lineCount < 3 || !isFence(lineText(1)) || !YAML_KEY.test(lineText(2))) return 0;
  for (let n = 3; n <= lineCount; n += 1) if (isFence(lineText(n))) return n;
  return 0;
}

/** Whether the parse input starts with a frontmatter block (looked up front: a block parser cannot rewind). */
function inputStartsWithFrontmatter(cx) {
  const input = cx.input; // BlockContext's parse input; absent only in an unexpected @lezer/markdown build
  if (!input || typeof input.read !== 'function') return false;
  const lines = input.read(0, Math.min(input.length, FRONTMATTER_SCAN_CHARS)).split('\n');
  return frontmatterCloseLine((n) => lines[n - 1], lines.length) > 0;
}

/**
 * Markdown parser extensions that limit fold markers to headings, the leading frontmatter block and fenced code:
 *  - a leading frontmatter block (see {@link frontmatterCloseLine}) parses as one {@code Frontmatter} node, so its
 *    last key/value line is no longer a setext heading with its own fold marker. A leading {@code ---} that is not
 *    frontmatter falls through to the normal HorizontalRule parser;
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
        if (cx.lineStart !== 0 || !isFence(line.text) || !inputStartsWithFrontmatter(cx)) return false;
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
  if (lineStart !== 0) return null;
  const close = frontmatterCloseLine((n) => state.doc.line(n).text, state.doc.lines);
  return close ? { from: state.doc.line(1).to, to: state.doc.line(close).to } : null;
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
