/**
 * Remote carets and selections for collaborative editing (docs/MultiEdit.MD A12, presence piggyback).
 *
 * A participant's selection is in its LOCAL document coordinates and carries the version it was synced to plus how many
 * of its updates were still unconfirmed. `placeAtHead` turns such a selection into a position in MY document: exact
 * only when both sides share one base (same synced version, nothing unconfirmed on the sender), mapped through my own
 * unsent edits; otherwise clamped and flagged approximate (`cm-remote-approx`) until the next presence update.
 */
import { StateEffect, StateField } from '@codemirror/state';
import { Decoration, EditorView, WidgetType } from '@codemirror/view';
import { getSyncedVersion, sendableUpdates } from '@codemirror/collab';

/** `presenceMap` is the server's full map keyed by participantId: `{displayName, color, anchor, head, version, unconfirmed}`. */
export const setRemotePresence = StateEffect.define();

const SAFE_COLOR = /^#[0-9a-fA-F]{3,8}$/;
const colorOf = (c) => (typeof c === 'string' && SAFE_COLOR.test(c) ? c : '#6b7280');

export function placeAtHead(pos, version, unconfirmed, state) {
  const len = state.doc.length;
  if (version === getSyncedVersion(state) && unconfirmed === 0) {
    const updates = sendableUpdates(state);
    if (!updates.length) return pos <= len ? { pos, approximate: false } : { pos: len, approximate: true };
    const composed = updates.map((u) => u.changes).reduce((a, b) => a.compose(b));
    if (pos <= composed.length) return { pos: Math.min(composed.mapPos(pos, 1), len), approximate: false };
  }
  return { pos: Math.max(0, Math.min(pos, len)), approximate: true };
}

class CaretWidget extends WidgetType {
  constructor(name, color, approximate) {
    super();
    this.name = name;
    this.color = color;
    this.approximate = approximate;
  }

  eq(other) { return other.name === this.name && other.color === this.color && other.approximate === this.approximate; }

  toDOM() {
    const caret = document.createElement('span');
    caret.className = `cm-remote-caret${this.approximate ? ' cm-remote-approx' : ''}`;
    caret.style.borderLeftColor = this.color;
    const label = document.createElement('span');
    label.className = 'cm-remote-caret-label';
    label.style.backgroundColor = this.color;
    label.textContent = this.name;
    caret.appendChild(label);
    return caret;
  }

  ignoreEvent() { return true; }
}

function buildDecorations(entries) {
  const ranges = [];
  for (const e of entries) {
    const color = colorOf(e.color);
    if (e.anchor !== e.head) {
      ranges.push(Decoration.mark({
        class: 'cm-remote-selection', attributes: { style: `background-color: ${color}33` },
      }).range(Math.min(e.anchor, e.head), Math.max(e.anchor, e.head)));
    }
    ranges.push(Decoration.widget({ widget: new CaretWidget(e.name, color, e.approximate), side: 1 }).range(e.head));
  }
  return Decoration.set(ranges, true);
}

function fromPresence(map, state) {
  const out = [];
  for (const [id, p] of Object.entries(map ?? {})) {
    if (p?.anchor == null || p.head == null || p.version == null) continue; // no selection yet: in the bar, no caret
    const a = placeAtHead(p.anchor, p.version, p.unconfirmed ?? 0, state);
    const h = placeAtHead(p.head, p.version, p.unconfirmed ?? 0, state);
    out.push({ id, name: p.displayName, color: p.color, anchor: a.pos, head: h.pos, approximate: a.approximate || h.approximate });
  }
  return out;
}

export const remoteCursorField = StateField.define({
  create: () => ({ entries: [], deco: Decoration.none }),
  update(value, tr) {
    let entries = null;
    for (const e of tr.effects) {
      // placed against the state the presence was received in, then carried through that transaction's own changes
      if (e.is(setRemotePresence)) entries = fromPresence(e.value, tr.startState);
    }
    if (!entries && !tr.docChanged) return value;
    entries = (entries ?? value.entries).map((e) => ({
      ...e, anchor: tr.changes.mapPos(e.anchor, 1), head: tr.changes.mapPos(e.head, 1),
    }));
    return { entries, deco: buildDecorations(entries) };
  },
  provide: (f) => EditorView.decorations.from(f, (v) => v.deco),
});

export function remoteCursors() {
  return [remoteCursorField];
}
