import { Prec } from '@codemirror/state';
import { ViewPlugin, Decoration } from '@codemirror/view';
import { syntaxTree } from '@codemirror/language';
import { MARKER, styleOf } from './remarkCallouts';

/**
 * The {@code [!type]} (+ optional {@code +}/{@code -} fold marker) that opens a callout: the first text of a
 * blockquote's first paragraph, as the preview's remarkCallouts reads it. Returns {from, to, style} per marker
 * within [from, to], where style is the preview's colour style for the type (aliases resolved, unknown → note).
 */
export function calloutMarkerRanges(state, from = 0, to = state.doc.length) {
  const ranges = [];
  syntaxTree(state).iterate({
    from, to,
    enter: (node) => {
      if (node.name !== 'Blockquote') return;
      let child = node.node.firstChild;
      while (child && child.name === 'QuoteMark') child = child.nextSibling;
      if (!child || child.name !== 'Paragraph') return;
      const m = MARKER.exec(state.sliceDoc(child.from, Math.min(child.to, child.from + 80)));
      if (!m) return;
      const [, rawType, fold] = m;
      ranges.push({ from: child.from, to: child.from + rawType.length + 3 + fold.length, style: styleOf(rawType) });
    },
  });
  return ranges;
}

const markCache = new Map();
function markFor(style) {
  if (!markCache.has(style)) {
    markCache.set(style, Decoration.mark({ class: `cm-callout-marker cm-callout-marker-${style}` }));
  }
  return markCache.get(style);
}

/**
 * Marks callout markers in the source as {@code .cm-callout-marker} so they read as callout syntax rather
 * than links. Lowest precedence: CodeMirror nests higher-precedence marks INSIDE lower ones, so this mark becomes
 * one outer element wrapping the syntax-highlight (and Ctrl-hover link) spans, letting CSS neutralise the link
 * underline and colour on everything inside it.
 */
export const calloutMarkers = Prec.lowest(ViewPlugin.fromClass(class {
  constructor(view) { this.decorations = this.build(view); }
  update(u) {
    if (u.docChanged || u.viewportChanged || syntaxTree(u.startState) !== syntaxTree(u.state)) this.decorations = this.build(u.view);
  }
  build(view) {
    const marks = [];
    const seen = new Set(); // a blockquote straddling two visible ranges is reported by both
    for (const { from, to } of view.visibleRanges) {
      for (const r of calloutMarkerRanges(view.state, from, to)) {
        if (!seen.has(r.from)) marks.push(markFor(r.style).range(r.from, r.to));
        seen.add(r.from);
      }
    }
    return Decoration.set(marks, true);
  }
}, { decorations: (v) => v.decorations }));
