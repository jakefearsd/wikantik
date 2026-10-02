import { StateEffect, StateField, Facet } from '@codemirror/state';
import { ViewPlugin, Decoration, EditorView } from '@codemirror/view';
import { syntaxTree } from '@codemirror/language';
import { activeLinesOf, livePreviewSpecs, blockSpecs } from './ranges';
import { widgetFor } from './widgets';

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

function toDecorations(specs, context) {
  const ranges = [];
  for (const s of specs) {
    if (s.kind === 'line') ranges.push(cached(lineDecos, s.cls, (c) => Decoration.line({ class: c })).range(s.from));
    else if (s.kind === 'mark') ranges.push(cached(markDecos, s.cls, (c) => Decoration.mark({ class: c })).range(s.from, s.to));
    else if (s.kind === 'hide') ranges.push(hidden.range(s.from, s.to));
    else if (s.kind === 'widget') ranges.push(Decoration.replace({ widget: widgetFor(s.widget, context) }).range(s.from, s.to));
    else if (s.kind === 'block') ranges.push(Decoration.replace({ widget: widgetFor(s.widget, context), block: true }).range(s.from, s.to));
  }
  // sort=true: specs come from several passes; Decoration.set orders by (from, startSide) for us.
  return Decoration.set(ranges, true);
}

const getContext = (state) => state.facet(livePreviewConfig).getContext() || {};
const touchesMode = (tr) => tr.effects.some((e) => e.is(setLiveMode) || e.is(refreshLivePreview));
const treeChanged = (a, b) => syntaxTree(a) !== syntaxTree(b);

export function buildInline(view) {
  const { state } = view;
  if (!state.field(liveModeField, false)) return Decoration.none;
  try {
    const context = getContext(state);
    const active = activeLinesOf(state);
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
    console.warn('[live-preview] could not build decorations; showing source for this update', err?.message || err);
    return Decoration.none;
  }
}

function buildBlocks(state) {
  if (!state.field(liveModeField, false)) return Decoration.none;
  try {
    const context = getContext(state);
    return toDecorations(blockSpecs(state, activeLinesOf(state), context), context);
  } catch (err) {
    console.warn('[live-preview] could not build block widgets; showing source for this update', err?.message || err);
    return Decoration.none;
  }
}

/** Block widgets (display math, page embeds) must come from a StateField: plugins may not provide block decorations. */
export const blockField = StateField.define({
  create: buildBlocks,
  update(decos, tr) {
    // Not on tr.reconfigured: react-codemirror reconfigures on every CodeEditor render; context changes arrive
    // as refreshLivePreview instead.
    const relevant = tr.docChanged || tr.selection || touchesMode(tr) || treeChanged(tr.startState, tr.state);
    return relevant ? buildBlocks(tr.state) : decos;
  },
  provide: (f) => EditorView.decorations.from(f),
});

export const livePreviewPlugin = ViewPlugin.fromClass(class {
  constructor(view) { this.decorations = buildInline(view); }
  update(u) {
    const relevant = u.docChanged || u.viewportChanged || u.selectionSet || treeChanged(u.startState, u.state)
      || u.transactions.some(touchesMode);
    if (relevant) this.decorations = buildInline(u.view);
  }
}, { decorations: (v) => v.decorations });

export const liveAttributes = EditorView.editorAttributes.compute([liveModeField],
  (state) => (state.field(liveModeField) ? { class: 'cm-live-preview' } : {}));
