import { StateEffect, StateField, Facet } from '@codemirror/state';
import { ViewPlugin, Decoration, EditorView } from '@codemirror/view';
import { syntaxTree } from '@codemirror/language';
import { activeLinesOf, livePreviewSpecs, blockCandidates, updateBlockCandidates } from './ranges';
import { widgetFor } from './widgets';

const warned = new Set();
/** console.warn once per distinct (message, error message): a failing builder runs on every keystroke/scroll. */
function warnOnce(msg, err) {
  const key = `${msg}|${err?.message || err}`;
  if (warned.has(key)) return;
  warned.add(key);
  console.warn(msg, err?.message || err, err);
}

export const setLiveMode = StateEffect.define();
export const refreshLivePreview = StateEffect.define();

/** Live mode on/off. A field (not a Compartment): its value survives react-codemirror's full reconfigures. */
export const liveModeField = StateField.define({
  create: () => false,
  update: (value, tr) => tr.effects.reduce((v, e) => (e.is(setLiveMode) ? !!e.value : v), value),
});

export const livePreviewConfig = Facet.define({ combine: (values) => values[0] ?? { getContext: () => ({}) } });

const hidden = Decoration.replace({});
const lineDecos = new Map();
const markDecos = new Map();
const cached = (map, cls, make) => { if (!map.has(cls)) map.set(cls, make(cls)); return map.get(cls); };

const blockDeco = (s, context) => Decoration.replace({ widget: widgetFor(s.widget, context), block: true, lpWidget: s.widget });

function toDecorations(specs, context) {
  const ranges = [];
  for (const s of specs) {
    if (s.kind === 'line') ranges.push(cached(lineDecos, s.cls, (c) => Decoration.line({ class: c })).range(s.from));
    else if (s.kind === 'mark') ranges.push(cached(markDecos, s.cls, (c) => Decoration.mark({ class: c })).range(s.from, s.to));
    else if (s.kind === 'hide') ranges.push(hidden.range(s.from, s.to));
    else if (s.kind === 'widget') ranges.push(Decoration.replace({ widget: widgetFor(s.widget, context) }).range(s.from, s.to));
    else if (s.kind === 'block') ranges.push(blockDeco(s, context).range(s.from, s.to));
  }
  // sort=true: specs come from several passes; Decoration.set orders by (from, startSide) for us.
  return Decoration.set(ranges, true);
}

const getContext = (state) => state.facet(livePreviewConfig).getContext() || {};
const touchesMode = (tr) => tr.effects.some((e) => e.is(setLiveMode) || e.is(refreshLivePreview));
const treeChanged = (a, b) => syntaxTree(a) !== syntaxTree(b);

function sameLines(a, b) {
  if (!a || a.size !== b.size) return false;
  for (const n of a) if (!b.has(n)) return false;
  return true;
}

/** Inline decorations for the visible ranges, given the active (revealed) line numbers. */
export function buildInline(view, active = activeLinesOf(view.state)) {
  const { state } = view;
  if (!state.field(liveModeField, false)) return Decoration.none;
  try {
    const context = getContext(state);
    const seen = new Set();
    const specs = [];
    for (const { from, to } of view.visibleRanges) {
      for (const s of livePreviewSpecs(state, active, { from, to, context })) {
        const key = `${s.kind}:${s.from}:${s.to}:${s.cls || s.widget?.type || ''}`;
        if (!seen.has(key)) { seen.add(key); specs.push(s); }
      }
    }
    return toDecorations(specs, context);
  } catch (err) {
    warnOnce('[live-preview] could not build decorations; showing source for this update', err);
    return Decoration.none;
  }
}

/**
 * Indices (into the ascending, non-overlapping candidates) of the blocks a selection range touches: the
 * revealed ones. Binary search per range, so a caret move costs O(ranges * log blocks), not a document walk.
 */
function activeBlockKey(state, candidates) {
  if (!candidates.length) return '';
  const hit = new Set();
  for (const r of state.selection.ranges) {
    const a = state.doc.lineAt(r.from).from;
    const b = state.doc.lineAt(r.to).to;
    let lo = 0;
    let hi = candidates.length;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (candidates[mid].to < a) lo = mid + 1; else hi = mid;
    }
    for (let i = lo; i < candidates.length && candidates[i].from <= b; i += 1) hit.add(i);
  }
  return [...hit].sort((x, y) => x - y).join(',');
}

const sameWidget = (a, b) => a.type === b.type && a.tex === b.tex && a.display === b.display
  && a.target === b.target && a.section === b.section;

/** True when `decos` holds exactly the `shown` block specs (same ranges and widget data, in order). */
function decosMatch(decos, shown) {
  let i = 0;
  for (const it = decos.iter(); it.value; it.next(), i += 1) {
    const s = shown[i];
    if (!s || it.from !== s.from || it.to !== s.to || !sameWidget(it.value.spec.lpWidget, s.widget)) return false;
  }
  return i === shown.length;
}

const NO_BLOCKS = { decos: Decoration.none, candidates: null, key: '' };

/**
 * The block-widget field value: the shown decorations, every candidate block (caret-independent), and the
 * key of the revealed ones. A caret move that reveals/hides no block returns the same value (no rebuild). A
 * document change re-examines only the top-level blocks around the edit (updateBlockCandidates) and, when the
 * shown set is intact, maps the decorations (their unchanged chunks are shared, so the view's decoration diff
 * stays cheap) instead of rebuilding every widget. A tree-only change (background parse) rescans fully.
 */
function nextBlocks(prev, tr, state) {
  if (!state.field(liveModeField, false)) return prev.candidates ? NO_BLOCKS : prev;
  try {
    const mode = tr ? touchesMode(tr) : true;
    const rescan = mode || !prev.candidates || tr.docChanged || treeChanged(tr.startState, state);
    if (!rescan && !tr.selection) return prev;
    const context = getContext(state);
    let candidates = prev.candidates;
    if (rescan) {
      candidates = !mode && prev.candidates && tr.docChanged
        ? updateBlockCandidates(prev.candidates, tr, context) : blockCandidates(state, context);
    }
    const key = activeBlockKey(state, candidates);
    if (!rescan && key === prev.key) return prev;
    const revealed = new Set(key ? key.split(',').map(Number) : []);
    const shown = candidates.filter((_, i) => !revealed.has(i));
    if (!mode && prev.candidates) {
      const base = tr.docChanged ? prev.decos.map(tr.changes) : prev.decos;
      if (decosMatch(base, shown)) return { decos: base, candidates, key };
    }
    return { decos: toDecorations(shown, context), candidates, key };
  } catch (err) {
    warnOnce('[live-preview] could not build block widgets; showing source for this update', err);
    return NO_BLOCKS;
  }
}

/** Block widgets (display math, page embeds) must come from a StateField: plugins may not provide block decorations. */
export const blockField = StateField.define({
  create: (state) => nextBlocks(NO_BLOCKS, null, state),
  // Not on tr.reconfigured: react-codemirror reconfigures when a config prop (theme, onChange…) changes; context changes arrive as
  // refreshLivePreview instead.
  update: (value, tr) => nextBlocks(value, tr, tr.state),
  provide: (f) => EditorView.decorations.from(f, (v) => v.decos),
});

export const livePreviewPlugin = ViewPlugin.fromClass(class {
  constructor(view) {
    this.active = activeLinesOf(view.state);
    this.decorations = buildInline(view, this.active);
  }
  update(u) {
    const rebuild = u.docChanged || u.viewportChanged || treeChanged(u.startState, u.state)
      || u.transactions.some(touchesMode);
    if (!rebuild && !u.selectionSet) return;
    const active = activeLinesOf(u.state);
    // A caret move that leaves the revealed lines unchanged cannot change any inline decoration.
    if (!rebuild && sameLines(this.active, active)) return;
    this.active = active;
    this.decorations = buildInline(u.view, active);
  }
}, { decorations: (v) => v.decorations });

export const liveAttributes = EditorView.editorAttributes.compute([liveModeField],
  (state) => (state.field(liveModeField) ? { class: 'cm-live-preview' } : {}));
