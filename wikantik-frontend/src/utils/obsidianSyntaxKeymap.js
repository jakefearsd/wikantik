import { keymap } from '@codemirror/view';
import { Prec } from '@codemirror/state';
import { toggleWrap } from './markdownFormat';

/**
 * Obsidian-syntax editing keys, bound in the editor extension itself (not through the command registry's `format`):
 *  - `Mod-Shift-H` wraps or unwraps the selection in `==…==` (Highlight);
 *  - `Mod-/` wraps or unwraps it in `%%…%%` (Toggle comment). `@codemirror/commands`' default `Mod-/` is
 *    `toggleComment`, which would write an HTML `<!-- -->` into Markdown; this keymap is Prec.highest, so it wins.
 */
function toggleMarker(view, marker) {
  const { state } = view;
  const { from, to } = state.selection.main;
  const text = state.doc.toString();
  const next = toggleWrap({ text, selStart: from, selEnd: to }, marker);
  // One contiguous change: everything between the common prefix and the common suffix of the two texts.
  let start = 0;
  const shortest = Math.min(text.length, next.text.length);
  while (start < shortest && text[start] === next.text[start]) start += 1;
  let end = 0;
  while (end < shortest - start && text[text.length - 1 - end] === next.text[next.text.length - 1 - end]) end += 1;
  view.dispatch({
    changes: { from: start, to: text.length - end, insert: next.text.slice(start, next.text.length - end) },
    selection: { anchor: next.selStart, head: next.selEnd },
    userEvent: 'input.format',
    scrollIntoView: true,
  });
  return true;
}

export const toggleHighlight = (view) => toggleMarker(view, '==');
export const toggleComment = (view) => toggleMarker(view, '%%');

export const obsidianSyntaxKeymap = Prec.highest(keymap.of([
  { key: 'Mod-Shift-h', preventDefault: true, run: toggleHighlight },
  { key: 'Mod-/', preventDefault: true, run: toggleComment },
]));
