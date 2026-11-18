import { EditorState, EditorSelection, Prec, RangeSet, StateField } from '@codemirror/state';
import { EditorView, ViewPlugin, keymap } from '@codemirror/view';
import { liveModeField, blockField, livePreviewPlugin, getContext } from './plugin';
import { activeLinesOf, livePreviewSpecs } from './ranges';

/**
 * Sticky ranges: live-preview decorations that stay hidden while the caret is on their line (a heading's `## `, a
 * list marker). Hidden text under the caret needs three rules to be usable: the range is atomic (motion and clicks
 * skip it), the caret never rests inside one nor before the markers that start its line, and Backspace right after
 * such a line marker removes the marker as a whole. All of it is inert outside live mode, and while nothing is sticky.
 *
 * A sticky range can also be all its line shows (`hiddenLine`: a fence's header line, its hidden closing line). The
 * caret does not rest on such a line: it goes on to the next line that shows text (restingPlace), the arrow keys step
 * over it (verticalMotion.js), and Backspace/Delete do not join a neighbouring line onto it (keepHiddenLine).
 *
 * Two sources, on purpose: the atomic ranges (stickyRangesOf) are the decorations actually drawn, while the caret
 * rule and the key commands read the state (stickySpansOn: one line's specs plus the block field), because a
 * transaction filter has no view and the drawn decorations describe the state before the transaction. The two can
 * differ while the parse is incomplete or outside the drawn viewport; the caret is then simply not corrected.
 *
 * Nothing here may dispatch from a composition event (or any DOM input event): every view.dispatch makes CodeMirror
 * discard the DOM mutations it has not read yet, and at compositionend it has only just scheduled the read of the
 * IME's final text — a dispatch there silently loses what the user typed, in Source mode too.
 */

const live = (state) => state.field(liveModeField, false) === true;

const filtered = new WeakMap(); // decoration set -> its sticky subset (sets are immutable, so the subset never goes stale)
function stickyOnly(set) {
  if (!set || !set.size) return RangeSet.empty;
  let out = filtered.get(set);
  if (!out) {
    out = set.update({ filter: (_from, _to, value) => value.spec.lpSticky === true });
    filtered.set(set, out);
  }
  return out;
}

/** The sticky decorations now drawn: those of the inline plugin and of the block field. Empty outside live mode. */
export function stickyRangesOf(view) {
  if (!live(view.state)) return RangeSet.empty;
  const inline = stickyOnly(view.plugin(livePreviewPlugin)?.decorations);
  const block = stickyOnly(view.state.field(blockField, false)?.decos);
  if (!block.size) return inline;
  return inline.size ? RangeSet.join([inline, block]) : block;
}

/**
 * The sticky ranges, minus any that a non-empty selection cuts into. CodeMirror's delete commands widen a deleted
 * selection to cover every atomic range it cuts, so a selection ending inside a marker (a search match on `#`, a
 * programmatic selection — pointer and Shift-motion selections are snapped outward and never cut) would delete more
 * than is selected; with the cut range left non-atomic the deletion is the one Source mode makes.
 */
function atomicStickyRanges(view) {
  const set = stickyRangesOf(view);
  if (!set.size) return set;
  const edges = [];
  for (const r of view.state.selection.ranges) if (!r.empty) edges.push(r.from, r.to);
  const cutBy = (from, to) => edges.some((e) => e > from && e < to);
  let cut = false;
  for (const e of edges) set.between(e, e, (from, to) => { if (cutBy(from, to)) cut = true; });
  return cut ? set.update({ filter: (from, to) => !cutBy(from, to) }) : set;
}

export const stickyAtomic = EditorView.atomicRanges.of(atomicStickyRanges);

/**
 * The sticky ranges `[from, to, lineMarker, hiddenLine]` lying on `line`, ascending, read from the state alone (a
 * transaction filter has no view, and the view's decorations describe the state before the transaction): the line's
 * inline specs plus the block field. One line's specs — a caret move does not walk the document.
 */
function stickySpansOn(state, line) {
  const spans = [];
  const add = (from, to, lineMarker, hiddenLine) => {
    if (from >= line.from && to <= line.to && to > from) spans.push([from, to, lineMarker === true, hiddenLine || null]);
  };
  try {
    const specs = livePreviewSpecs(state, activeLinesOf(state), { from: line.from, to: line.to, context: getContext(state) });
    for (const s of specs) if (s.sticky) add(s.from, s.to, s.lineMarker, s.hiddenLine);
    state.field(blockField, false)?.decos.between(line.from, line.to, (from, to, value) => {
      if (value.spec.lpSticky) add(from, to, false, null);
    });
  } catch (err) {
    console.warn('[rich] could not read the sticky ranges; the caret is left where it is', err?.message || err);
  }
  return spans.sort((x, y) => x[0] - y[0] || x[1] - y[1]);
}

/**
 * The line markers among a line's sticky ranges: those whose spec says so (`lineMarker: true`, set for block markers
 * in ranges.js), in the leading run with nothing but whitespace before and between them (`> - [ ] x` has two, the
 * quote mark and the checkbox; an indented `  - x` has one, after its indentation). A sticky range of any other kind
 * ends the run, also at the line start.
 */
function lineMarkersOf(state, line, spans) {
  const markers = [];
  let at = line.from;
  for (const [from, to, lineMarker] of spans) {
    if (!lineMarker || from < at || /\S/.test(state.doc.sliceString(at, from))) break;
    markers.push([from, to]);
    at = to;
  }
  return markers;
}

/** The line marker that ends exactly at pos — the innermost one when the caret is where the line's text starts. */
function lineMarkerBefore(state, pos) {
  const line = state.doc.lineAt(pos);
  return lineMarkersOf(state, line, stickySpansOn(state, line)).find(([, to]) => to === pos) || null;
}

/**
 * A hidden line: one whose spec says the caret cannot rest on it at all (`hiddenLine`, set in ranges.js: 'open' for a
 * fence's header line, 'close' for its hidden closing line). Nothing on it is text to edit, and typing there would
 * break the fence unseen. `hiddenKind` is that value, or null for any other line.
 */
const hiddenKind = (spans) => spans.find((span) => span[3])?.[3] || null;
const hidesLine = (spans) => hiddenKind(spans) !== null;
export const isHiddenLine = (state, line) => live(state) && hidesLine(stickySpansOn(state, line));

/** Consecutive hidden lines: a closing fence, the next block's header. Past that many, the caret is left alone. */
const MAX_HIDDEN_RUN = 4;

/**
 * Past `line` in direction `dir` (1 down, -1 up), over hidden lines — where an arrow key gets to: `{ line, spans }`
 * for the nearest line that is not hidden, `{ edge: true }` when the hidden lines run to the document's edge (a fence
 * on its last or first line), null when the run is too long.
 */
export function lineBeyondHidden(state, line, dir) {
  let at = line;
  for (let hops = 0; hops < MAX_HIDDEN_RUN; hops += 1) {
    const n = at.number + dir;
    if (n < 1 || n > state.doc.lines) return { edge: true };
    at = state.doc.line(n);
    const spans = stickySpansOn(state, at);
    if (!hidesLine(spans)) return { line: at, spans };
  }
  return null;
}
const shownLineBeyond = (state, line, dir) => { const found = lineBeyondHidden(state, line, dir); return found?.line ? found : null; };

/**
 * Where a caret placed at `head` rests: out of a sticky range (nearer end), and never before the end of its line's
 * last marker — not before a marker, not between two, not in the indentation in front of one. Text typed there would
 * land outside the block the line shows.
 *
 * On a hidden line it goes on in the direction it came (`origin`: where the caret was): down to the start of the next
 * shown line, up to the end of the previous one, and the other way when the document ends there. A caret that did not
 * come from anywhere — it was already on the line (an edit left it there), or `inward` (a click) — goes into the
 * block: down from an opening line, up from a closing one.
 */
function restingPlace(state, head, origin, inward) {
  let line = state.doc.lineAt(head);
  let spans = stickySpansOn(state, line);
  if (!spans.length) return head;
  let pos = head;
  if (hidesLine(spans)) {
    const down = inward || (origin >= line.from && origin <= line.to) ? hiddenKind(spans) === 'open' : origin < line.from;
    const shown = shownLineBeyond(state, line, down ? 1 : -1) || shownLineBeyond(state, line, down ? -1 : 1);
    if (!shown) return head;
    const landedBelow = shown.line.number > line.number;
    ({ line, spans } = shown);
    pos = landedBelow ? line.from : line.to;
    if (!spans.length) return pos;
  }
  const inside = spans.find(([from, to]) => pos > from && pos < to);
  if (inside) pos = pos - inside[0] < inside[1] - pos ? inside[0] : inside[1];
  const markers = lineMarkersOf(state, line, spans);
  if (markers.length) pos = Math.max(pos, markers[markers.length - 1][1]);
  return pos;
}

/**
 * Whether an IME composition is running is view state, which a transaction filter cannot see. The field holds one
 * mutable reference per state lineage, pointed by the plugin at the view showing it — read-only plumbing: neither
 * dispatches anything. Only `view.composing` is consulted (`compositionStarted` is true far too broadly on Android).
 */
const viewRef = StateField.define({ create: () => ({ view: null }), update: (ref) => ref });
const trackView = ViewPlugin.define((view) => {
  const ref = view.state.field(viewRef, false);
  if (ref) ref.view = view;
  return { destroy() { if (ref && ref.view === view) ref.view = null; } };
});
const composing = (tr) => tr.isUserEvent('input.type.compose') || tr.startState.field(viewRef, false)?.view?.composing === true;

/** Whether the caret stayed on its line through a change that made that line hidden (it was not, before). */
function justHidden(tr, origin, head) {
  const line = tr.state.doc.lineAt(head);
  if (origin < line.from || origin > line.to || !isHiddenLine(tr.state, line)) return false;
  // Neither end of the line was on a hidden line before: a line joined onto a fence line (a deleted break) is not "just hidden".
  const before = tr.startState;
  const back = tr.changes.invertedDesc;
  const first = before.doc.lineAt(back.mapPos(line.from));
  const last = before.doc.lineAt(back.mapPos(line.to));
  return !isHiddenLine(before, first) && (last.number === first.number || !isHiddenLine(before, last));
}

/**
 * Caret rule: every empty range is moved to its resting place, on any transaction that can leave a caret somewhere
 * else — one that sets the selection, or one that changes the document (a change with no selection maps the caret,
 * and a marker inserted at the caret's line start, by a command or a shared-editing peer, lands after it). Non-empty
 * ranges are left alone (a selection may cut into a marker, so deleting it behaves as in Source). Nothing moves
 * during a composition — moving the caret under an IME aborts it; a caret the composition leaves before a marker is
 * corrected by the next selection or change. The rule only ever adds a selection: the transaction's changes, effects
 * and annotations pass through untouched.
 *
 * One caret is left on a hidden line: the one whose own typing just made the line hidden (the third backtick of a
 * closing fence). Enter then continues below the block; moved, the caret could not get there on a last line.
 */
export const stickyCaret = EditorState.transactionFilter.of((tr) => {
  if (!(tr.selection || tr.docChanged) || !live(tr.startState) || composing(tr)) return tr;
  const { state } = tr;
  if (!live(state)) return tr;
  let moved = false;
  const before = tr.startState.selection;
  const paired = before.ranges.length === state.selection.ranges.length;
  const inward = tr.isUserEvent('select.pointer');
  const ranges = state.selection.ranges.map((range, i) => {
    if (!range.empty) return range;
    const was = (paired ? before.ranges[i] : before.main).head;
    const origin = tr.changes.mapPos(was, 1);
    const head = restingPlace(state, range.head, origin, inward);
    if (head === range.head || (tr.docChanged && justHidden(tr, origin, range.head))) return range;
    moved = true;
    return EditorSelection.cursor(head);
  });
  if (!moved) return tr;
  return [tr, { selection: EditorSelection.create(ranges, state.selection.mainIndex), sequential: true }];
});

const soleCaret = (state) => (state.selection.ranges.length === 1 && state.selection.main.empty ? state.selection.main.head : -1);

/**
 * Backspace with the caret right after a line marker: delete exactly the marker's text (the marker and its one
 * trailing space) and nothing else — on a line with several (`> - x`), the innermost one; indentation stays.
 * Declines (false) everywhere else, so the default Backspace runs.
 */
export function removeLineMarker(view) {
  const { state } = view;
  if (!live(state) || state.readOnly) return false;
  const head = soleCaret(state);
  const marker = head < 0 ? null : lineMarkerBefore(state, head);
  if (!marker) return false;
  view.dispatch({ changes: { from: marker[0], to: marker[1] }, userEvent: 'delete.backward', scrollIntoView: true });
  return true;
}

/**
 * ArrowLeft (or word-left) with the caret right after a line's last marker: go to the end of the previous line.
 * CodeMirror's own motion stops at the marker's start (atomic), where the caret rule sends it straight back — the
 * caret could never leave the line leftwards. Declines on the first line (there is nowhere to go; the default motion
 * then stays put).
 */
export function leaveLineMarker(view) {
  const { state } = view;
  if (!live(state)) return false;
  const head = soleCaret(state);
  const marker = head < 0 ? null : lineMarkerBefore(state, head);
  if (!marker) return false;
  const lineStart = state.doc.lineAt(head).from;
  if (lineStart === 0) return false;
  view.dispatch({ selection: EditorSelection.cursor(lineStart - 1), userEvent: 'select', scrollIntoView: true });
  return true;
}

/**
 * Backspace (`forward` false) and Delete next to a hidden line. At the start of the line below one, or the end of the
 * line above one, the key would join that line's text onto a fence nobody sees and break the block: it does nothing
 * (true) — unless the caret's own line is empty, since removing an empty line breaks nothing. With the caret on a
 * hidden line (see stickyCaret) the key does nothing either: the hidden range is atomic, so it would go as a whole.
 */
const keepHiddenLine = (forward) => (view) => {
  const { state } = view;
  if (!live(state)) return false;
  const head = soleCaret(state);
  if (head < 0) return false;
  const line = state.doc.lineAt(head);
  if (isHiddenLine(state, line)) return true;
  const beside = line.number + (forward ? 1 : -1);
  if (head !== (forward ? line.to : line.from) || !line.length || beside < 1 || beside > state.doc.lines) return false;
  return isHiddenLine(state, state.doc.line(beside));
};
const backspaceBesideHidden = keepHiddenLine(false);
const deleteBesideHidden = keepHiddenLine(true);
const removeMarkerOrKeepHidden = (view) => removeLineMarker(view) || backspaceBesideHidden(view);

export const stickyExtension = [
  viewRef,
  trackView,
  stickyAtomic,
  stickyCaret,
  // Highest: lang-markdown's own keymap binds Backspace (deleteMarkupBackward) at Prec.high and precedes this
  // extension in CodeEditor's list, so at equal precedence it would take a list or quote marker's Backspace first.
  Prec.highest(keymap.of([
    { key: 'Backspace', run: removeMarkerOrKeepHidden },
    { key: 'Mod-Backspace', run: backspaceBesideHidden }, // by word, and to the line boundary: they cross the break too
    { key: 'Alt-Backspace', run: backspaceBesideHidden },
    { key: 'Delete', run: deleteBesideHidden },
    { key: 'Mod-Delete', run: deleteBesideHidden },
    { key: 'Alt-Delete', run: deleteBesideHidden },
    { key: 'ArrowLeft', run: leaveLineMarker },
    { key: 'Mod-ArrowLeft', mac: 'Alt-ArrowLeft', run: leaveLineMarker }, // word-left stalls the same way
  ])),
];
