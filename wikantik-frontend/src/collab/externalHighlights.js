/**
 * Highlights the ranges changed from outside the editor (an agent or a disk save merged by the session). The ranges
 * arrive in the coordinates of the document at the event's version, so they are placed through `placeAtHead`, follow
 * every later transaction, and fade 10 s after they appear.
 */
import { StateEffect, StateField } from '@codemirror/state';
import { Decoration, EditorView, ViewPlugin } from '@codemirror/view';
import { placeAtHead } from './remoteCursors.js';

export const FADE_MS = 10000;
/** `{ranges: [[from, to]], version, id}` */
export const addExternalHighlight = StateEffect.define();
export const removeExternalHighlight = StateEffect.define();

export const externalHighlightField = StateField.define({
  create: () => Decoration.none,
  update(value, tr) {
    let next = value.map(tr.changes);
    for (const e of tr.effects) {
      if (e.is(addExternalHighlight)) {
        const { ranges, version, id } = e.value;
        const marks = [];
        for (const [from, to] of ranges ?? []) {
          // placed against the pre-transaction state, then carried through this transaction's own changes
          const a = placeAtHead(from, version, 0, tr.startState).pos;
          const b = placeAtHead(to, version, 0, tr.startState).pos;
          const f = tr.changes.mapPos(Math.min(a, b), 1);
          const t = tr.changes.mapPos(Math.max(a, b), -1);
          if (t > f) marks.push(Decoration.mark({ class: 'cm-external-change', id }).range(f, t));
        }
        if (marks.length) next = next.update({ add: marks, sort: true });
      } else if (e.is(removeExternalHighlight)) {
        next = next.update({ filter: (_f, _t, d) => d.spec.id !== e.value });
      }
    }
    return next;
  },
  provide: (f) => EditorView.decorations.from(f),
});

const fadePlugin = ViewPlugin.fromClass(class {
  constructor(view) {
    this.view = view;
    this.timers = new Set();
    this.dead = false;
  }

  update(u) {
    for (const tr of u.transactions) {
      for (const e of tr.effects) {
        if (!e.is(addExternalHighlight)) continue;
        const id = e.value.id;
        const t = setTimeout(() => {
          this.timers.delete(t);
          if (!this.dead) this.view.dispatch({ effects: removeExternalHighlight.of(id) });
        }, FADE_MS);
        this.timers.add(t);
      }
    }
  }

  destroy() {
    this.dead = true;
    this.timers.forEach(clearTimeout);
    this.timers.clear();
  }
});

export function externalHighlights() {
  return [externalHighlightField, fadePlugin];
}
