import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { diffMeta, createMetaState, createMetaQueue } from './collabMeta.js';
import { createFakeCollabServer } from './fakeCollabServer.js';
import { createCollabPeerCore } from './collabPeer.js';
import { EditorState } from '@codemirror/state';
import { collab } from '@codemirror/collab';

describe('diffMeta', () => {
  it('diffMetaTurnsSetFieldChangesIntoAddsAndRemoves', () => {
    const edits = diffMeta({ tags: ['a', 'b'], related: ['R'] }, { tags: ['b', 'c'], related: [], aliases: ['Al'] });
    expect(edits).toEqual(expect.arrayContaining([
      { field: 'tags', remove: 'a' }, { field: 'tags', add: 'c' },
      { field: 'related', remove: 'R' }, { field: 'aliases', add: 'Al' },
    ]));
    expect(edits).toHaveLength(4);
  });

  it('diffMetaTurnsOtherChangesIntoSetsAndRemovedKeysIntoNull', () => {
    const edits = diffMeta({ status: 'draft', summary: 'x', type: 'note', same: 1 }, { status: 'active', type: 'note', same: 1, date: '2026-01-01' });
    expect(edits).toEqual(expect.arrayContaining([
      { field: 'status', value: 'active' }, { field: 'summary', value: null }, { field: 'date', value: '2026-01-01' },
    ]));
    expect(edits).toHaveLength(3);
  });

  it('a removed set field becomes removes and an unchanged object yields nothing', () => {
    expect(diffMeta({ tags: ['a'] }, {})).toEqual([{ field: 'tags', remove: 'a' }]);
    expect(diffMeta({ tags: ['a'], n: { x: 1 } }, { tags: ['a'], n: { x: 1 } })).toEqual([]);
  });
});

describe('createMetaQueue', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); });

  it('fieldEditsAreDebouncedPerField', () => {
    const sent = [];
    const q = createMetaQueue({ onSend: (e) => sent.push(...e) });
    ['h', 'he', 'hel', 'hell', 'hello'].forEach((v) => { q.edit('summary', v); vi.advanceTimersByTime(60); });
    expect(sent).toEqual([]);
    vi.advanceTimersByTime(400);
    expect(sent).toEqual([{ field: 'summary', value: 'hello' }]);
  });

  it('different fields debounce independently', () => {
    const sent = [];
    const q = createMetaQueue({ onSend: (e) => sent.push(...e) });
    q.edit('a', 1); vi.advanceTimersByTime(200); q.edit('b', 2); vi.advanceTimersByTime(250);
    expect(sent).toEqual([{ field: 'a', value: 1 }]);
    vi.advanceTimersByTime(200);
    expect(sent).toHaveLength(2);
  });

  it('dispose flushes a debounced edit instead of dropping it, then goes quiet', () => {
    const sent = [];
    const q = createMetaQueue({ onSend: (e) => sent.push(...e) });
    q.edit('summary', 'half typed');
    vi.advanceTimersByTime(100);
    expect(q.dispose()).toEqual([{ field: 'summary', value: 'half typed' }]);
    expect(sent).toEqual([{ field: 'summary', value: 'half typed' }]);
    vi.advanceTimersByTime(1000);
    expect(sent).toHaveLength(1);
    q.edit('summary', 'late');
    vi.advanceTimersByTime(1000);
    expect(sent).toHaveLength(1);
  });

  it('flushSendsAtOnce', () => {
    const sent = [];
    const q = createMetaQueue({ onSend: (e) => sent.push(e) });
    q.edit('a', 1); q.edit('b', 2);
    q.flush();
    expect(sent).toEqual([[{ field: 'a', value: 1 }, { field: 'b', value: 2 }]]);
    vi.advanceTimersByTime(1000);
    expect(sent).toHaveLength(1); // the debounce timers were cancelled
    expect(q.hasWaiting()).toBe(false);
  });
});

describe('createMetaState', () => {
  it('myTagAddSurvivesTheGapBetweenAckAndPull', () => {
    const m = createMetaState({ meta: { tags: ['a'] }, metaSeq: 3 });
    m.add('tags', 'x');
    expect(m.view().tags).toEqual(['a', 'x']);
    const sent = m.takeSendable();
    expect(m.view().tags).toEqual(['a', 'x']);
    m.acked(sent, 4);
    expect(m.view().tags).toEqual(['a', 'x']); // acked, not yet pulled
    m.applyRemote([], 3); // a pull that has not reached my seq yet
    expect(m.view().tags).toEqual(['a', 'x']);
    m.applyRemote([{ field: 'tags', value: ['a', 'x'] }], 4);
    expect(m.pending()).toEqual([]);
    expect(m.view().tags).toEqual(['a', 'x']);
  });

  it('a lost ack is resent and leaves one x', () => {
    const m = createMetaState({ meta: { tags: [] }, metaSeq: 0 });
    m.add('tags', 'x');
    const first = m.takeSendable();
    m.failed(first); // the response never arrived, though the server applied it
    m.applyRemote([{ field: 'tags', value: ['x'] }], 1); // the pull delivers my op before any ack
    expect(m.view().tags).toEqual(['x']);
    const again = m.takeSendable();
    expect(again).toEqual([{ field: 'tags', add: 'x' }]);
    m.acked(again, 1);
    m.applyRemote([], 1);
    expect(m.view().tags).toEqual(['x']);
    expect(m.pending()).toEqual([]);
  });

  it('an ack that arrives after the pull already reached its seq retires the edit at once', () => {
    const m = createMetaState({ meta: { tags: [] }, metaSeq: 0 });
    m.add('tags', 'x');
    const sent = m.takeSendable();
    m.applyRemote([{ field: 'tags', value: ['x'] }], 1);
    m.acked(sent, 1);
    expect(m.pending()).toEqual([]);
    expect(m.view().tags).toEqual(['x']);
  });

  it('an ack without a metaSeq retires the edit at once rather than pinning it', () => {
    const m = createMetaState({ meta: { tags: [] }, metaSeq: 5 });
    m.add('tags', 'x');
    m.acked(m.takeSendable(), undefined);
    expect(m.pending()).toEqual([]);
    m.add('tags', 'y');
    m.acked(m.takeSendable(), null);
    expect(m.pending()).toEqual([]);
  });

  it('aRemoteOpAfterMineWins', () => {
    const m = createMetaState({ meta: { status: 'draft' }, metaSeq: 0 });
    m.edit('status', 'active');
    m.acked(m.takeSendable(), 1);
    m.applyRemote([{ field: 'status', value: 'active' }, { field: 'status', value: 'archived' }], 2);
    expect(m.view().status).toBe('archived');
    expect(m.pending()).toEqual([]);
  });

  it('aRemoteOpBeforeMineIsOverlaid', () => {
    const m = createMetaState({ meta: { status: 'draft' }, metaSeq: 0 });
    m.edit('status', 'active');
    m.acked(m.takeSendable(), 2);
    m.applyRemote([{ field: 'status', value: 'archived' }], 1);
    expect(m.view().status).toBe('active');
    m.applyRemote([{ field: 'status', value: 'active' }], 2);
    expect(m.view().status).toBe('active');
  });

  it('coalesces unsent SETs of one field and resolves list edge cases like the server', () => {
    const m = createMetaState({ meta: { tags: 'solo' } });
    m.edit('summary', 'a'); m.edit('summary', 'ab');
    expect(m.pending()).toEqual([{ field: 'summary', value: 'ab' }]);
    m.add('tags', 'b');
    expect(m.view().tags).toEqual(['solo', 'b']);
    m.remove('tags', 'solo'); m.remove('tags', 'b');
    expect(m.view().tags).toEqual([]);
    m.edit('summary', null);
    expect('summary' in m.view()).toBe(false);
  });

  it('replace queues the diff', () => {
    const m = createMetaState({ meta: { tags: ['a'], status: 's' } });
    m.replace({ tags: ['a'], status: 's' }, { tags: ['a', 'b'], status: 't' });
    expect(m.view()).toEqual({ tags: ['a', 'b'], status: 't' });
    expect(m.takeSendable()).toHaveLength(2);
    expect(m.takeSendable()).toEqual([]);
  });
});

describe('createMetaState recovery helpers', () => {
  it('unconfirmed lists the unsent and in-flight edits, not the acked ones', () => {
    const m = createMetaState({ meta: {}, metaSeq: 0 });
    m.edit('a', 1);
    m.acked(m.takeSendable(), 5);
    m.edit('b', 2);
    m.takeSendable();
    m.add('tags', 'x');
    expect(m.unconfirmed()).toEqual([{ field: 'b', value: 2 }, { field: 'tags', add: 'x' }]);
  });

  it('unconfirmedOmitsAnAppliedEdit', () => {
    // in flight, and the pull that delivers them landed before the ack: the server already holds them
    const m = createMetaState({ meta: { tags: ['a'] }, metaSeq: 0 });
    m.add('tags', 'x');
    m.edit('status', 'draft');
    m.takeSendable();
    m.applyRemote([{ field: 'tags', value: ['a', 'x'] }, { field: 'status', value: 'draft' }], 2);
    expect(m.unconfirmed()).toEqual([]);
    m.add('tags', 'y');
    expect(m.unconfirmed()).toEqual([{ field: 'tags', add: 'y' }]);
  });

  it('unconfirmed replays in order: an edit undoing an earlier unconfirmed one is still listed', () => {
    const m = createMetaState({ meta: { tags: ['x'] }, metaSeq: 0 });
    m.remove('tags', 'x');
    m.takeSendable();
    m.add('tags', 'x');
    expect(m.unconfirmed()).toEqual([{ field: 'tags', remove: 'x' }, { field: 'tags', add: 'x' }]);
  });

  it('discardPending drops every local edit: the view is the server metadata again', () => {
    const m = createMetaState({ meta: { a: 0 }, metaSeq: 0 });
    m.edit('a', 1);
    m.takeSendable();
    m.add('tags', 'x');
    m.discardPending();
    expect(m.view()).toEqual({ a: 0 });
    expect(m.pending()).toEqual([]);
  });
});

describe('meta state against the fake server', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); });

  async function metaEditor(server, user, wrap) {
    const base = server.transportFor(user);
    const transport = wrap ? wrap(base) : base;
    const joined = await transport.join({});
    const meta = createMetaState({ meta: joined.metadata, metaSeq: joined.metaSeq });
    const views = [];
    const state = EditorState.create({ doc: joined.doc, extensions: [collab({ startVersion: joined.version, clientID: joined.clientID })] });
    const core = createCollabPeerCore({
      transport,
      session: { sessionId: joined.sessionId, clientID: joined.clientID, participantId: joined.participantId },
      host: { getState: () => state, dispatch: () => {} },
      getSelection: () => null,
      onMetaAck: (edits, seq) => { meta.acked(edits, seq); views.push(meta.view()); },
      onMetaOps: (ops, seq) => { meta.applyRemote(ops, seq); views.push(meta.view()); },
    });
    core.start();
    return { meta, core, views };
  }

  it('an ack that overtakes its pull still retires the edit end to end, with one x after a lost ack', async () => {
    const server = createFakeCollabServer({ initialMeta: { tags: ['a'] } });
    let dropAck = true;
    const a = await metaEditor(server, 'ada', (t) => ({
      ...t,
      push: async (b) => {
        const res = await t.push(b);
        if (dropAck && b.metaOps.length) { dropAck = false; throw new TypeError('Failed to fetch'); }
        return res;
      },
    }));
    a.meta.add('tags', 'x');
    a.core.queueMetaEdits(a.meta.takeSendable());
    // the display holds the add while the first push is lost, and the retry is sent
    for (let i = 0; i < 400; i += 1) {
      await vi.advanceTimersByTimeAsync(25);
      expect(a.meta.view().tags).toContain('x');
    }
    expect(server.metadata.tags).toEqual(['a', 'x']);
    expect(a.meta.pending()).toEqual([]);
    expect(a.views.every((v) => v.tags.includes('x'))).toBe(true);
    a.core.destroy();
  });
});
