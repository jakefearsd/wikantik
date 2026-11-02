import { EditorView } from '@codemirror/view';
import { ensureSyntaxTree, syntaxTree } from '@codemirror/language';
import { decidePaste } from './pasteDecision';

/** How long a Mod-Shift-V press marks the next paste as plain text (the paste event follows the keydown at once). */
const PLAIN_PASTE_WINDOW_MS = 1000;
const CODE_NODES = new Set(['FencedCode', 'CodeBlock', 'InlineCode']);

/** True when `pos` lies strictly inside a code node (fence, indented block, inline code) — its edges are outside. */
export function inCodeAt(state, pos) {
  const tree = ensureSyntaxTree(state, pos, 50) ?? syntaxTree(state);
  for (const side of [-1, 1]) {
    for (let node = tree.resolveInner(pos, side); node; node = node.parent) {
      if (CODE_NODES.has(node.name) && node.from < pos && pos < node.to) return true;
    }
  }
  return false;
}

/** Ctrl+Shift+V / Cmd+Shift+V (and Cmd+Option+Shift+V, the Mac "paste and match style"). */
const isPlainPasteKey = (e) => (e.ctrlKey || e.metaKey) && e.shiftKey && (e.code === 'KeyV' || e.key === 'v' || e.key === 'V');

/** Inserts `text` over the selection as one paste transaction (an undo step of its own) and scrolls it into view. */
export function insertPaste(view, text) {
  view.dispatch(view.state.update(view.state.replaceSelection(text), { userEvent: 'input.paste', scrollIntoView: true }));
}

/**
 * Paste-as-Markdown: rich clipboard HTML is converted to Markdown, a URL pasted over a selection links it, and
 * everything else (plain text, code-editor HTML, a paste inside code, a Mod-Shift-V paste) falls through to
 * CodeMirror's own plain-text paste. The keydown never prevents the browser's paste: it only flags the paste event
 * that follows. Must come after the file-paste handler so pasted images still upload.
 *
 *   getOrigin()            this wiki's origin, for rewriting links to its own pages
 *   isEnabled()            false turns conversion off (legacy wiki-markup pages): every paste is plain
 *   onConverted(decision)  called after a converted paste with { kind, insert, droppedImages }
 *   placeImages(images, text)  embedded images ({ file, token }) ride in `text` as tokens; returns `{ text, begin }` —
 *                          the text to insert (tokens swapped for upload placeholders) and a `begin()` to call once it
 *                          is in the document. Without it the tokens are removed and the images count as dropped.
 */
export function markdownPaste({ getOrigin = () => window.location.origin, isEnabled = () => true, onConverted, placeImages } = {}) {
  let plainUntil = 0;
  return EditorView.domEventHandlers({
    keydown(event) {
      if (isPlainPasteKey(event)) plainUntil = Date.now() + PLAIN_PASTE_WINDOW_MS;
      return false;
    },
    paste(event, view) {
      const plain = Date.now() < plainUntil;
      plainUntil = 0;
      const data = event.clipboardData;
      if (!data || !isEnabled()) return false;
      const { from, to } = view.state.selection.main;
      const decision = decidePaste({
        text: data.getData('text/plain'),
        html: data.getData('text/html'),
        selection: view.state.sliceDoc(from, to),
        inCode: inCodeAt(view.state, from),
        plain,
        origin: getOrigin(),
      });
      if (!decision) return false;
      event.preventDefault();
      const { images = [] } = decision;
      let { insert, droppedImages } = decision;
      let begin;
      const placed = images.length ? placeImages?.(images, insert) : null;
      if (placed) {
        ({ text: insert, begin } = placed);
      } else if (images.length) {
        insert = images.reduce((text, { token }) => text.split(token).join(''), insert);
        droppedImages += images.length;
      }
      insertPaste(view, insert);
      begin?.();
      onConverted?.({ ...decision, insert, droppedImages });
      return true;
    },
  });
}
