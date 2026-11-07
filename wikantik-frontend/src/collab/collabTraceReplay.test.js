/**
 * F3: replays the Java SimClient's recorded traces (`__fixtures__/collab-traces/*.json`, written by
 * `CollabTraceCorpusDriftTest -Dcollab.traces.write=true`) through the real @codemirror/collab and asserts the same
 * document and the same sendable updates at every recorded point (documents and rebased lists are compared by hash). The library is the truth: a disagreement means the Java
 * model (or the export) is wrong.
 */
import { describe, it, expect } from 'vitest';
import { EditorState, ChangeSet } from '@codemirror/state';
import { collab, receiveUpdates, sendableUpdates, getSyncedVersion } from '@codemirror/collab';

const files = import.meta.glob('./__fixtures__/collab-traces/*.json', { eager: true, import: 'default' });
// 4 profiles x 50 seeds, 12 extra LOSSY seeds that reach a resync, 2 crafted traces, 1 committed regression trace.
const EXPECTED_TRACES = 4 * 50 + 12 + 2 + 1;

const ENTRY_TYPES = new Set(['join', 'resync', 'local', 'receive', 'sendable', 'gap', 'push']);

function join(entry) {
  return EditorState.create({
    doc: entry.doc,
    extensions: [collab({ startVersion: entry.version, clientID: entry.clientId })],
  });
}

/** FNV-1a over UTF-16 code units as `<length>-<8 hex>`; mirrors CollabTraceCorpusDriftTest.textHash. */
function textHash(text) {
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i += 1) {
    h = Math.imul(h ^ text.charCodeAt(i), 0x01000193);
  }
  return `${text.length}-${(h >>> 0).toString(16).padStart(8, '0')}`;
}

/** Canonical text of arrays / integers / strings; mirrors CollabTraceCorpusDriftTest.canon. */
function canon(v) {
  if (Array.isArray(v)) return `[${v.map((x) => `${canon(x)},`).join('')}]`;
  if (typeof v === 'string') return `${v.length}:${v}`;
  return String(v);
}

const canonSendable = (list) => `[${list.map((u) => `{${canon(u.clientID)}|${canon(u.changes)}},`).join('')}]`;
const snapshot = (state) => sendableUpdates(state).map((u) => ({ clientID: u.clientID, changes: u.changes.toJSON() }));

/** True for entries that make the replay compare something against the library's state. */
const isCheck = (e) => e.type === 'sendable' || (e.type === 'local' && e.dh !== undefined);

function checkState(state, e, at) {
  expect(getSyncedVersion(state), `${at}: version`).toBe(e.version);
  expect(textHash(state.doc.toString()), `${at}: doc "${state.doc.toString()}"`).toBe(e.dh);
  if (e.type === 'local') {
    expect(sendableUpdates(state).length, `${at}: sendable count`).toBe(e.sn);
  } else {
    expect(textHash(canonSendable(snapshot(state))), `${at}: sendable ${JSON.stringify(snapshot(state))}`).toBe(e.sh);
  }
}

/** Replays one client's entries; returns the number of comparisons made. */
function replayClient(entries, table, label) {
  let state = null;
  let compared = 0;
  entries.forEach((e, i) => {
    const at = `${label} entry ${i} (${e.type})`;
    if (!ENTRY_TYPES.has(e.type)) throw new Error(`${at}: unknown entry type`);
    switch (e.type) {
      case 'join':
      case 'resync':
        state = join(e);
        break;
      case 'local':
        state = state.update({ changes: ChangeSet.fromJSON(e.changes) }).state;
        break;
      case 'receive': {
        const version = getSyncedVersion(state);
        if (e.fromVersion > version) break; // a gap: not applied (E11)
        const fresh = e.updates.map((x) => table[x]).slice(version - e.fromVersion);
        if (fresh.length === 0) break;
        const tr = receiveUpdates(
          state,
          fresh.map((u) => ({ clientID: u.clientID, changes: ChangeSet.fromJSON(u.changes) })),
        );
        state = state.update(tr).state;
        break;
      }
      default:
        break; // sendable (checked below), gap, push: informational
    }
    if (isCheck(e)) {
      checkState(state, e, at);
      compared += 1;
    }
  });
  return compared;
}

describe('replay harness', () => {
  const joinEntry = { type: 'join', participantId: 'p', clientId: 'a', version: 0, doc: 'abc' };

  it('fails when the recorded document disagrees with the library', () => {
    const entries = [joinEntry, { type: 'sendable', version: 0, dh: textHash('WRONG'), sh: textHash('[]') }];
    expect(() => replayClient(entries, [], 'synthetic')).toThrow();
  });

  it('passes when the recorded values agree', () => {
    const entries = [joinEntry, { type: 'sendable', version: 0, dh: textHash('abc'), sh: textHash('[]') }];
    expect(replayClient(entries, [], 'synthetic')).toBe(1);
  });

  it('throws on an unknown entry type', () => {
    expect(() => replayClient([joinEntry, { type: 'mystery' }], [], 'synthetic')).toThrow(/unknown entry type/);
  });
});

describe('Java SimClient traces replayed through @codemirror/collab', () => {
  const traces = Object.entries(files).sort(([a], [b]) => a.localeCompare(b));
  const entriesOf = (t) => t.clients.flat();

  it('has the expected corpus and the cases that matter', () => {
    expect(traces).toHaveLength(EXPECTED_TRACES);
    const has = (type) => traces.filter(([, t]) => entriesOf(t).some((e) => e.type === type)).length;
    ['join', 'local', 'receive', 'sendable'].forEach((k) => expect(has(k), k).toBeGreaterThan(0));
    expect(has('resync')).toBeGreaterThanOrEqual(10);
    expect(has('gap')).toBeGreaterThanOrEqual(1);
    const names = traces.map(([n]) => n);
    expect(names.some((n) => n.includes('crafted-foreign-then-own'))).toBe(true);
    expect(names.some((n) => n.includes('regression-'))).toBe(true);
  });

  it('agrees with the library at every recorded point', () => {
    const expectedChecks = traces.reduce((n, [, t]) => n + entriesOf(t).filter(isCheck).length, 0);
    let compared = 0;
    for (const [path, trace] of traces) {
      trace.clients.forEach((entries, c) => {
        compared += replayClient(entries, trace.updates, `${path} client ${c}`);
      });
    }
    expect(expectedChecks).toBeGreaterThan(0);
    expect(compared).toBe(expectedChecks);
  });
});
