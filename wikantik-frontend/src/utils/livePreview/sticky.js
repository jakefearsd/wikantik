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
 * The sticky ranges `[from, to, lineMarker]` lying on `line`, ascending, read from the state alone (a transaction filter has no
 * view, and the view's decorations describe the state before the transaction): the line's inline specs plus the block
 * field. One line's specs — a caret move does not walk the document.
 */
function stickySpansOn(state, line) {
  const spans = [];
  const add = (from, to, lineMarker) => { if (from >= line.from && to <= line.to && to > from) spans.push([from, to, lineMarker === true]); };
  try {
    const specs = livePreviewSpecs(state, activeLinesOf(state), { from: line.from, to: line.to, context: getContext(state) });
    for (const s of specs) if (s.sticky) add(s.from, s.to, s.lineMarker);
    state.field(blockField, false)?.decos.between(line.from, line.to, (from, to, value) => {
      if (value.spec.lpSticky) add(from, to, false);
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
 * Where a caret placed at `head` rests: out of a sticky range (nearer end), and never before the end of its line's
 * last marker — not before a marker, not between two, not in the indentation in front of one. Text typed there would
 * land outside the block the line shows.
 */
function restingPlace(state, head) {
  const line = state.doc.lineAt(head);
  const spans = stickySpansOn(state, line);
  if (!spans.length) return head;
  let pos = head;
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

/**
 * Caret rule: every empty range is moved to its resting place, on any transaction that can leave a caret somewhere
 * else — one that sets the selection, or one that changes the document (a change with no selection maps the caret,
 * and a marker inserted at the caret's line start, by a command or a shared-editing peer, lands after it). Non-empty
 * ranges are left alone (a selection may cut into a marker, so deleting it behaves as in Source). Nothing moves
 * during a composition — moving the caret under an IME aborts it; a caret the composition leaves before a marker is
 * corrected by the next selection or change. The rule only ever adds a selection: the transaction's changes, effects
 * and annotations pass through untouched.
 */
export const stickyCaret = EditorState.transactionFilter.of((tr) => {
  if (!(tr.selection || tr.docChanged) || !live(tr.startState) || composing(tr)) return tr;
  const { state } = tr;
  if (!live(state)) return tr;
  let moved = false;
  const ranges = state.selection.ranges.map((range) => {
    if (!range.empty) return range;
    const head = restingPlace(state, range.head);
    if (head === range.head) return range;
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
  if (!live(state)) return false;
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

export const stickyExtension = [
  viewRef,
  trackView,
  stickyAtomic,
  stickyCaret,
  // Highest: lang-markdown's own keymap binds Backspace (deleteMarkupBackward) at Prec.high and precedes this
  // extension in CodeEditor's list, so at equal precedence it would take a list or quote marker's Backspace first.
  Prec.highest(keymap.of([
    { key: 'Backspace', run: removeLineMarker },
    { key: 'ArrowLeft', run: leaveLineMarker },
    { key: 'Mod-ArrowLeft', mac: 'Alt-ArrowLeft', run: leaveLineMarker }, // word-left stalls the same way
  ])),
];
