import { EditorSelection, Prec } from '@codemirror/state';
import { keymap } from '@codemirror/view';
import { liveModeField, blockField } from './plugin';

/**
 * The shown (not revealed) block widget a vertical move from `line` would jump over: one starting on the next line
 * (forward) or ending on the previous line (backward). CodeMirror's own vertical motion treats a block replace
 * decoration as one tall unit and lands past it, so the block could never be entered from the keyboard.
 */
function blockBeside(state, line, forward) {
  const blocks = state.field(blockField, false);
  if (!blocks) return false;
  const { doc } = state;
  const target = line.number + (forward ? 1 : -1);
  if (target < 1 || target > doc.lines) return false;
  const t = doc.line(target);
  let hit = false;
  blocks.decos.between(t.from, t.to, (from, to) => {
    if (doc.lineAt(forward ? from : to).number === target) {
      hit = true;
      return false;
    }
    return undefined;
  });
  return hit;
}

/** CodeMirror's own ArrowUp/ArrowDown result for one range (cursorLineDown/selectLineDown semantics). */
function defaultMove(view, range, forward, extend) {
  if (extend) return EditorSelection.range(range.anchor, view.moveVertically(range, forward).head);
  if (!range.empty) return EditorSelection.cursor(forward ? range.to : range.from);
  return view.moveVertically(range, forward);
}

/**
 * ArrowDown/ArrowUp (Shift: extend) in live mode: a range whose head sits on the last (first) visual row of a line
 * directly above (below) a shown block widget moves to the start of the block's first (last) line, which reveals its
 * source. Returns false — CodeMirror's own motion runs — when no range has such a block in the way, or outside live
 * mode. Other ranges of a multi-range selection move exactly as CodeMirror would move them.
 */
export function moveIntoBlock(view, forward, extend) {
  const { state } = view;
  if (!state.field(liveModeField, false)) return false;
  let redirected = false;
  const ranges = state.selection.ranges.map((range) => {
    if (!range.empty && !extend) return null;
    const line = state.doc.lineAt(range.head);
    // A wrapped line moves within itself first: only the row at the line's edge is next to the block.
    const rowEdge = view.moveToLineBoundary(EditorSelection.cursor(range.head, range.assoc), forward).head;
    if (rowEdge !== (forward ? line.to : line.from) || !blockBeside(state, line, forward)) return null;
    redirected = true;
    const head = state.doc.line(line.number + (forward ? 1 : -1)).from;
    return extend ? EditorSelection.range(range.anchor, head) : EditorSelection.cursor(head);
  });
  if (!redirected) return false;
  const moved = ranges.map((r, i) => r || defaultMove(view, state.selection.ranges[i], forward, extend));
  view.dispatch({
    selection: EditorSelection.create(moved, state.selection.mainIndex),
    scrollIntoView: true,
    userEvent: 'select',
  });
  return true;
}

/** Above the default keymap, so the redirect runs before CodeMirror's cursorLineDown/Up; declines everywhere else. */
export const verticalMotionKeymap = Prec.high(keymap.of([
  { key: 'ArrowDown', run: (v) => moveIntoBlock(v, true, false), shift: (v) => moveIntoBlock(v, true, true) },
  { key: 'ArrowUp', run: (v) => moveIntoBlock(v, false, false), shift: (v) => moveIntoBlock(v, false, true) },
]));
