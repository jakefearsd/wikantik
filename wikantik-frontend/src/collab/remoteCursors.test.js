import { describe, it, expect } from 'vitest';
import { EditorState } from '@codemirror/state';
import { collab, getSyncedVersion, receiveUpdates } from '@codemirror/collab';
import { ChangeSet } from '@codemirror/state';
import { placeAtHead, remoteCursors, remoteCursorField, setRemotePresence } from './remoteCursors.js';

const mk = (doc = 'Hello world', startVersion = 3) =>
  EditorState.create({ doc, extensions: [collab({ startVersion, clientID: 'me' }), remoteCursors()] });
const entry = (over = {}) => ({ displayName: 'bob', color: '#c2410c', anchor: 2, head: 2, version: 3, unconfirmed: 0, ...over });
const entries = (s) => s.field(remoteCursorField).entries;

describe('placeAtHead', () => {
  it('aSelectionAtTheSyncedVersionIsPlacedExactlyThroughMyUnsyncedEdits', () => {
    let s = mk();
    s = s.update({ changes: { from: 0, insert: 'XY' } }).state;
    expect(getSyncedVersion(s)).toBe(3);
    expect(placeAtHead(5, 3, 0, s)).toEqual({ pos: 7, approximate: false });
  });

  it('aSelectionWithUnconfirmedUpdatesIsClampedAndMarkedApproximate', () => {
    const s = mk('abc');
    expect(placeAtHead(2, 3, 1, s)).toEqual({ pos: 2, approximate: true });
    expect(placeAtHead(99, 3, 1, s)).toEqual({ pos: 3, approximate: true });
    expect(placeAtHead(2, 2, 0, s)).toEqual({ pos: 2, approximate: true });
    expect(placeAtHead(99, 7, 0, s)).toEqual({ pos: 3, approximate: true });
  });
});

describe('remoteCursors field', () => {
  it('cursorsFollowEveryTransaction', () => {
    let s = mk();
    s = s.update({ effects: setRemotePresence.of({ 'pt-2': entry({ anchor: 5, head: 5 }) }) }).state;
    expect(entries(s)[0]).toMatchObject({ id: 'pt-2', head: 5, anchor: 5 });
    s = s.update({ changes: { from: 0, insert: '>>' } }).state;
    expect(entries(s)[0].head).toBe(7);
    const remote = receiveUpdates(s, [{ clientID: 'other', changes: ChangeSet.of({ from: 0, insert: 'ZZZ' }, 11) }]);
    s = s.update(remote).state;
    expect(entries(s)[0].head).toBe(10);
  });

  it('aParticipantWithoutASelectionHasNoCaretButIsInTheBar', () => {
    let s = mk();
    const nul = { displayName: 'cy', color: '#15803d', anchor: null, head: null, version: null, unconfirmed: null };
    s = s.update({ effects: setRemotePresence.of({ 'pt-3': nul }) }).state;
    expect(entries(s)).toEqual([]);
    expect(s.field(remoteCursorField).deco.size).toBe(0);
  });

  it('aLeftParticipantsCaretIsRemoved', () => {
    let s = mk();
    s = s.update({ effects: setRemotePresence.of({ a: entry(), b: entry({ displayName: 'cy' }) }) }).state;
    expect(entries(s)).toHaveLength(2);
    expect(s.field(remoteCursorField).deco.size).toBe(2);
    s = s.update({ effects: setRemotePresence.of({ b: entry({ displayName: 'cy' }) }) }).state;
    expect(entries(s).map((e) => e.id)).toEqual(['b']);
  });

  it('draws a selection mark and flags approximate entries', () => {
    let s = mk();
    s = s.update({ effects: setRemotePresence.of({ a: entry({ anchor: 1, head: 4 }), b: entry({ version: 1 }) }) }).state;
    const found = [];
    s.field(remoteCursorField).deco.between(0, 100, (from, to, v) => { found.push([from, to, v.spec.class ?? v.spec.widget?.constructor?.name]); });
    expect(found.some(([f, t]) => f === 1 && t === 4)).toBe(true);
    expect(entries(s).find((e) => e.id === 'b').approximate).toBe(true);
  });
});
