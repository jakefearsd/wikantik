import { EditorState, EditorSelection, Prec, RangeSet, StateField } from '@codemirror/state';
import { EditorView, ViewPlugin, keymap } from '@codemirror/view';
import { liveModeField, blockField, livePreviewPlugin, getContext } from './plugin';
import { activeLinesOf, livePreviewSpecs } from './ranges';

/**
 * Sticky ranges: live-preview decorations that stay hidden while the caret is on their line (a heading's `## `, a
 * list marker). Hidden text under the caret needs three rules to be usable: the range is atomic (motion and clicks
 * skip it), the caret never rests before or inside one that starts its line, and Backspace right after such a line
 * marker removes the marker as a whole. All of it is inert outside live mode, and while nothing is sticky.
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
 * The sticky ranges `[from, to]` touching `pos`, read from the state alone (a transaction filter has no view, and
 * the view's decorations describe the state before the transaction): the inline specs of pos's line plus the block
 * field. One line's specs — a caret move does not walk the document.
 */
function stickySpansAt(state, pos) {
  const spans = [];
  try {
    const line = state.doc.lineAt(pos);
    const specs = livePreviewSpecs(state, activeLinesOf(state), { from: line.from, to: line.to, context: getContext(state) });
    for (const s of specs) if (s.sticky && s.from <= pos && pos <= s.to) spans.push([s.from, s.to]);
    state.field(blockField, false)?.decos.between(pos, pos, (from, to, value) => {
      if (value.spec.lpSticky) spans.push([from, to]);
    });
  } catch (err) {
    console.warn('[rich] could not read the sticky ranges; the caret is left where it is', err?.message || err);
  }
  return spans;
}

/** The sticky range that starts pos's line and ends exactly at pos (the caret sits right after a line marker). */
function lineMarkerBefore(state, pos) {
  const lineStart = state.doc.lineAt(pos).from;
  return stickySpansAt(state, pos).find(([from, to]) => from === lineStart && to === pos && to > from) || null;
}

/** Where a caret placed at `head` rests: out of a sticky range (nearer end), and never before one that starts its line. */
function restingPlace(state, head) {
  for (const [from, to] of stickySpansAt(state, head)) {
    let pos = head;
    if (pos > from && pos < to) pos = pos - from < to - pos ? from : to;
    if (pos === from && to > from && from === state.doc.lineAt(from).from) pos = to;
    if (pos !== head) return pos;
  }
  return head;
}

/**
 * A transaction filter cannot see the view, but "an IME composition is running" is view state (compositionstart
 * precedes the first compose transaction). The field holds one mutable reference per state lineage, which the plugin
 * points at the view showing it.
 */
const viewRef = StateField.define({ create: () => ({ view: null }), update: (ref) => ref });
const trackView = ViewPlugin.define((view) => {
  const ref = view.state.field(viewRef, false);
  if (ref) ref.view = view;
  return { destroy() { if (ref && ref.view === view) ref.view = null; } };
});
function composing(tr) {
  if (tr.isUserEvent('input.type.compose')) return true;
  const view = tr.startState.field(viewRef, false)?.view;
  return !!view && (view.composing || view.compositionStarted);
}

/**
 * Caret rule: when a transaction sets the selection, every empty range is moved to its resting place. Non-empty
 * ranges are left alone (a selection may cut into a marker, so deleting it behaves as in Source), and nothing moves
 * during a composition — moving the caret under an IME aborts it.
 */
export const stickyCaret = EditorState.transactionFilter.of((tr) => {
  if (!tr.selection || !live(tr.startState) || composing(tr)) return tr;
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
 * trailing space) and nothing else. Declines (false) everywhere else, so the default Backspace runs.
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
 * ArrowLeft (or word-left) with the caret right after a line marker: go to the end of the previous line. CodeMirror's own motion
 * stops at the marker's start (atomic), where the caret rule sends it straight back — the caret could never leave
 * the line leftwards. Declines on the first line (there is nowhere to go; the default motion then stays put).
 */
export function leaveLineMarker(view) {
  const { state } = view;
  if (!live(state)) return false;
  const head = soleCaret(state);
  const marker = head < 0 ? null : lineMarkerBefore(state, head);
  if (!marker || marker[0] === 0) return false;
  view.dispatch({ selection: EditorSelection.cursor(marker[0] - 1), userEvent: 'select', scrollIntoView: true });
  return true;
}

export const stickyExtension = [
  viewRef,
  trackView,
  stickyAtomic,
  stickyCaret,
  // Above the default keymap, whose Backspace/ArrowLeft would otherwise run first.
  Prec.high(keymap.of([
    { key: 'Backspace', run: removeLineMarker },
    { key: 'ArrowLeft', run: leaveLineMarker },
    { key: 'Mod-ArrowLeft', mac: 'Alt-ArrowLeft', run: leaveLineMarker }, // word-left stalls the same way
  ])),
];
