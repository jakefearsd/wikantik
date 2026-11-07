/**
 * F3: replays the Java SimClient's recorded traces (`__fixtures__/collab-traces/*.json`, written by
 * `CollabTraceCorpusDriftTest -Dcollab.traces.write=true`) through the real @codemirror/collab and asserts the same
 * document and the same sendable updates after every step. The library is the truth: a disagreement means the Java
 * model (or the export) is wrong.
 */
import { describe, it, expect } from 'vitest';
import { EditorState, ChangeSet } from '@codemirror/state';
import { collab, receiveUpdates, sendableUpdates, getSyncedVersion } from '@codemirror/collab';

const files = import.meta.glob('./__fixtures__/collab-traces/*.json', { eager: true, import: 'default' });
const EXPECTED_TRACES = 200;

function join(entry) {
  return EditorState.create({
    doc: entry.doc,
    extensions: [collab({ startVersion: entry.version, clientID: entry.clientId })],
  });
}

const snapshot = (state) => sendableUpdates(state).map((u) => ({ clientID: u.clientID, changes: u.changes.toJSON() }));

/** Replays one client's entries; returns the number of sendable snapshots compared. */
function replayClient(entries, label) {
  let state = null;
  let compared = 0;
  entries.forEach((e, i) => {
    const at = `${label} entry ${i} (${e.type})`;
    switch (e.type) {
      case 'join':
        state = join(e);
        break;
      case 'resync':
        state = join(e);
        break;
      case 'local':
        state = state.update({ changes: ChangeSet.fromJSON(e.changes) }).state;
        break;
      case 'receive': {
        const version = getSyncedVersion(state);
        if (e.fromVersion > version) break; // a gap: not applied (E11)
        const skip = version - e.fromVersion;
        const fresh = e.updates.slice(skip);
        if (fresh.length === 0) break;
        const tr = receiveUpdates(
          state,
          fresh.map((u) => ({ clientID: u.clientID, changes: ChangeSet.fromJSON(u.changes) })),
        );
        state = state.update(tr).state;
        break;
      }
      case 'sendable':
        expect(getSyncedVersion(state), `${at}: version`).toBe(e.version);
        expect(state.doc.toString(), `${at}: doc`).toBe(e.doc);
        expect(snapshot(state), `${at}: sendable`).toEqual(
          e.sendable.map((u) => ({ clientID: u.clientID, changes: u.changes })),
        );
        compared += 1;
        break;
      default:
        break; // gap, push: informational
    }
  });
  return compared;
}

// The seeded corpus (1..50 x 4 profiles x 120 steps) never produces a gap or a resync, so those replay branches are
// pinned by hand-built entries in the exact export shape.
describe('replay of the cases the seeded corpus does not reach', () => {
  const upd = (clientID, changes) => ({ clientID, changes });
  it('applies the E11 skip rule, ignores a gap, and re-seeds on resync', () => {
    const entries = [
      { type: 'join', participantId: 'p', clientId: 'a', version: 0, doc: 'abc' },
      { type: 'receive', fromVersion: 0, updates: [upd('b', [0, [0, 'X'], 3])] },
      { type: 'sendable', version: 1, doc: 'Xabc', sendable: [] },
      // overlaps by one: only the second update is new
      { type: 'receive', fromVersion: 0, updates: [upd('b', [0, [0, 'X'], 3]), upd('b', [4, [0, 'Y'], 0])] },
      { type: 'sendable', version: 2, doc: 'XabcY', sendable: [] },
      // a gap: fromVersion beyond the version, nothing applied
      { type: 'receive', fromVersion: 5, updates: [upd('b', [5, [0, 'Z'], 0])] },
      { type: 'gap', fromVersion: 5, version: 2 },
      { type: 'sendable', version: 2, doc: 'XabcY', sendable: [] },
      { type: 'resync', participantId: 'p2', clientId: 'a2', version: 7, doc: 'fresh', unsynced: 0 },
      { type: 'sendable', version: 7, doc: 'fresh', sendable: [] },
    ];
    expect(replayClient(entries, 'synthetic')).toBe(4);
  });

  it('fails loudly when the recorded document disagrees with the library', () => {
    const entries = [
      { type: 'join', participantId: 'p', clientId: 'a', version: 0, doc: 'abc' },
      { type: 'sendable', version: 0, doc: 'WRONG', sendable: [] },
    ];
    expect(() => replayClient(entries, 'synthetic')).toThrow();
  });
});

describe('Java SimClient traces replayed through @codemirror/collab', () => {
  const traces = Object.entries(files).sort(([a], [b]) => a.localeCompare(b));

  it('has the expected corpus', () => {
    expect(traces).toHaveLength(EXPECTED_TRACES);
    const types = new Set(traces.flatMap(([, t]) => t.clients.flat().map((e) => e.type)));
    ['join', 'local', 'receive', 'sendable'].forEach((k) => expect(types.has(k)).toBe(true));
  });

  it('agrees with the library on every document and sendable snapshot', () => {
    let compared = 0;
    for (const [path, trace] of traces) {
      trace.clients.forEach((entries, c) => {
        compared += replayClient(entries, `${path} client ${c}`);
      });
    }
    expect(compared).toBeGreaterThan(10000);
  });
});
