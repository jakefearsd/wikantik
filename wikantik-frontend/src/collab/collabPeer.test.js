import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { EditorState, Transaction } from '@codemirror/state';
import { history, undo } from '@codemirror/commands';
import { collab, getSyncedVersion, sendableUpdates } from '@codemirror/collab';
import { createFakeCollabServer, seededRandom } from './fakeCollabServer.js';
import { createCollabPeerCore, unsentRemainder } from './collabPeer.js';

/** A headless editor: a real EditorState with collab()+history(), driven by a peer core through a host. */
async function makeEditor(server, user, opts = {}) {
  const transport = opts.wrap ? opts.wrap(server.transportFor(user)) : server.transportFor(user);
  const joined = await transport.join({});
  const editor = {
    user, transport, joined, statuses: [], resyncs: [], presence: [], events: [], metaOps: [], unmounted: false,
    state: EditorState.create({
      doc: joined.doc,
      extensions: [collab({ startVersion: joined.version, clientID: joined.clientID }), history()],
    }),
  };
  const host = {
    getState: () => editor.state,
    dispatch: (tr) => {
      editor.state = tr.state;
      if (tr.docChanged && !tr.annotation(collabRemote)) editor.core.notifyChange();
    },
  };
  editor.core = createCollabPeerCore({
    transport,
    session: { sessionId: joined.sessionId, clientID: joined.clientID, participantId: joined.participantId },
    eventsSince: joined.eventSeq,
    metaSince: joined.metaSeq,
    host,
    onStatus: (s) => editor.statuses.push(s),
    onResync: (r) => editor.resyncs.push(r),
    onPresence: (p) => editor.presence.push(p),
    onEvents: (e) => editor.events.push(...e),
    onMetaOps: (m) => editor.metaOps.push(...m),
    getSelection: () => ({ anchor: editor.state.selection.main.anchor, head: editor.state.selection.main.head }),
    ...opts.core,
  });
  editor.type = (insert, at = editor.state.doc.length) => host.dispatch(editor.state.update({ changes: { from: at, insert }, userEvent: 'input.type' }));
  editor.remove = (from, to) => host.dispatch(editor.state.update({ changes: { from, to }, userEvent: 'delete' }));
  editor.core.start();
  return editor;
}
// remote transactions come back through the same dispatch; collab's receiveUpdates is not a local edit
const collabRemote = Transaction.remote;

const advance = (ms) => vi.advanceTimersByTimeAsync(ms);
async function settle(server, editors, maxMs = 120000) {
  for (let t = 0; t < maxMs; t += 25) {
    await advance(25);
    const idle = editors.every((e) => sendableUpdates(e.state).length === 0 && getSyncedVersion(e.state) === server.version
      && e.state.doc.toString() === server.doc);
    if (idle) return;
  }
  throw new Error(`did not settle: server v${server.version} ${JSON.stringify(server.doc)} vs ${editors.map((e) => `${getSyncedVersion(e.state)}/${sendableUpdates(e.state).length}`)}`);
}

describe('collabPeer', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); });

  it('threeEditorsTypingConcurrentlyConverge', async () => {
    const server = createFakeCollabServer({ initialText: 'seed\n', seed: 7, pollTimeoutMs: 5000 });
    const editors = [];
    for (const u of ['ada', 'bob', 'cyn']) editors.push(await makeEditor(server, u));
    server.setLatency(90);
    const rand = seededRandom(20261106);
    const words = ['alpha', 'be', 'γάμμα', '😀', 'delta\nline', ' ', 'x'];
    for (let step = 0; step < 500; step++) {
      const e = editors[Math.floor(rand() * editors.length)];
      const len = e.state.doc.length;
      if (len > 0 && rand() < 0.35) {
        const from = Math.floor(rand() * len);
        const to = Math.min(len, from + 1 + Math.floor(rand() * 4));
        // do not split a surrogate pair, as a real editor never would
        const doc = e.state.doc.toString();
        const safeFrom = /[\uDC00-\uDFFF]/.test(doc[from] ?? '') ? from - 1 : from;
        const safeTo = /[\uDC00-\uDFFF]/.test(doc[to] ?? '') ? to + 1 : to;
        e.remove(safeFrom, Math.min(len, safeTo));
      } else {
        let at = Math.floor(rand() * (len + 1));
        const doc = e.state.doc.toString();
        if (/[\uDC00-\uDFFF]/.test(doc[at] ?? '') && at > 0) at -= 1;
        e.type(words[Math.floor(rand() * words.length)], at);
      }
      if (rand() < 0.03) server.dropNextResponse('POST', '/updates'); // a lost acknowledgement now and then
      await advance(Math.floor(rand() * 70));
    }
    await settle(server, editors);
    for (const e of editors) {
      expect(e.state.doc.toString()).toBe(server.doc);
      expect(e.resyncs).toEqual([]);
    }
    expect(server.version).toBeGreaterThan(100);
    const pushes = server.calls.filter((c) => c.method === 'POST' && c.path === '/updates');
    expect(pushes.filter((c) => c.status === 409 && c.reason === 'stale-base').length).toBeGreaterThanOrEqual(1);
    expect(pushes.filter((c) => c.status === 'dropped').length).toBeGreaterThanOrEqual(1);
    for (const e of editors) e.core.destroy();
  }, 60000);

  it('aLostPushResponseDoesNotDuplicate', async () => {
    const server = createFakeCollabServer({ initialText: 'abc' });
    const a = await makeEditor(server, 'ada');
    const b = await makeEditor(server, 'bob');
    server.dropNextResponse('POST', '/updates');
    a.type('X', 3);
    await settle(server, [a, b]);
    expect(server.doc).toBe('abcX');
    expect(a.state.doc.toString()).toBe('abcX');
    expect(b.state.doc.toString()).toBe('abcX');
    const pushes = server.calls.filter((c) => c.method === 'POST' && c.path === '/updates' && c.user === 'ada' && c.body.updates.length);
    // the server's head advanced, so A's parked pull returned its own update as a confirmation: no resend at all
    expect(pushes.map((c) => c.status)).toEqual(['dropped']);
    expect(server.version).toBe(1);
  });

  it('a lost push response whose confirmation is slow is resent with the old version, gets a stale-base 409, and still applies once', async () => {
    const server = createFakeCollabServer({ initialText: 'abc' });
    let release;
    const gate = new Promise((r) => { release = r; });
    const a = await makeEditor(server, 'ada', { wrap: (t) => ({ ...t, pull: async (q) => { await gate; return t.pull(q); } }) });
    server.dropNextResponse('POST', '/updates');
    a.type('X', 3);
    await advance(2000);
    release();
    await settle(server, [a]);
    expect(server.doc).toBe('abcX');
    const pushes = server.calls.filter((c) => c.method === 'POST' && c.path === '/updates' && c.body.updates.length);
    expect(pushes.map((c) => c.status)).toEqual(['dropped', 409]);
    expect(server.version).toBe(1);
  });

  it('stalePushRebasesAndRetries', async () => {
    const server = createFakeCollabServer({ initialText: 'one two' });
    let release;
    const gate = new Promise((r) => { release = r; });
    const a = await makeEditor(server, 'ada');
    const b = await makeEditor(server, 'bob', {
      wrap: (t) => ({ ...t, pull: async (q) => { await gate; return t.pull(q); } }),
    });
    a.type('A-', 0);
    await advance(300);
    expect(server.doc).toBe('A-one two');
    b.type('!', 7); // typed against version 0 while B has not seen A's update
    await advance(300);
    const stale = server.calls.filter((c) => c.user === 'bob' && c.path === '/updates' && c.method === 'POST' && c.body.updates.length);
    expect(stale[0].status).toBe(409);
    expect(stale[0].body.version).toBe(0);
    release();
    await settle(server, [a, b]);
    expect(server.doc).toBe('A-one two!');
    expect(b.state.doc.toString()).toBe('A-one two!');
    const ok = server.calls.filter((c) => c.user === 'bob' && c.path === '/updates' && c.method === 'POST' && c.status === 200 && c.body.updates.length);
    expect(ok).toHaveLength(1);
    expect(ok[0].body.version).toBe(1);
  });

  it('networkErrorBacksOff', async () => {
    const server = createFakeCollabServer({ initialText: 'abc', pollTimeoutMs: 60000 });
    const attempts = [];
    let failures = 7;
    const a = await makeEditor(server, 'ada', {
      wrap: (t) => ({
        ...t,
        pull: async (q) => {
          attempts.push(Date.now());
          if (failures-- > 0) throw new TypeError('Failed to fetch');
          return t.pull(q);
        },
      }),
    });
    await advance(60000);
    const gaps = attempts.slice(1, 8).map((t, i) => t - attempts[i]);
    expect(gaps).toEqual([500, 1000, 2000, 4000, 8000, 10000, 10000]);
    expect(a.statuses).toContain('reconnecting');
    expect(a.statuses.at(-1)).toBe('live');
    a.core.destroy();
  });

  it('a push that fails with a network error is retried with backoff and keeps the edit', async () => {
    const server = createFakeCollabServer({ initialText: 'abc' });
    let failures = 2;
    const a = await makeEditor(server, 'ada', {
      wrap: (t) => ({ ...t, push: async (b) => { if (b.updates.length && failures-- > 0) throw new TypeError('Failed to fetch'); return t.push(b); } }),
    });
    a.type('Z', 3);
    await settle(server, [a]);
    expect(server.doc).toBe('abcZ');
  });

  it('resyncHandsUnsyncedTextToRecovery', async () => {
    const server = createFakeCollabServer({ initialText: 'base', pollTimeoutMs: 60000 });
    let release;
    const gate = new Promise((r) => { release = r; });
    const a = await makeEditor(server, 'ada');
    const b = await makeEditor(server, 'bob', {
      wrap: (t) => ({ ...t, pull: async (q) => { await gate; return t.pull(q); } }),
    });
    a.type('1', 4); await advance(300);
    a.type('2', 5); await advance(300);
    a.type('3', 6); await advance(300);
    server.truncateBelow(3);
    b.type('MINE', 0);
    await advance(300);
    release();
    await advance(2000);
    expect(b.resyncs).toHaveLength(1);
    const r = b.resyncs[0];
    expect(r.reason).toBe('resync');
    expect(r.unsyncedText).toContain('MINE');
    expect(r.docText).toBe('MINEbase');
    // A10: the peer never re-applies the text itself; its document is untouched and it stops talking
    expect(b.state.doc.toString()).toBe('MINEbase');
    expect(b.statuses.at(-1)).toBe('resync');
    const before = server.calls.length;
    await advance(30000);
    expect(server.calls.filter((c, i) => i >= before && c.user === 'bob')).toEqual([]);
    expect(server.doc).toBe('base123');
  });

  it('an oversize push hands the unsynced text to recovery as well', async () => {
    const server = createFakeCollabServer({ initialText: 'abc', maxPageBytes: 5 });
    const a = await makeEditor(server, 'ada');
    a.type('0123456789', 3);
    await advance(500);
    expect(a.resyncs).toHaveLength(1);
    expect(a.resyncs[0].reason).toBe('oversize');
    expect(a.resyncs[0].unsyncedText).toContain('0123456789');
  });

  it('undoOnlyUndoesMyEdits', async () => {
    const server = createFakeCollabServer({ initialText: 'mid' });
    const a = await makeEditor(server, 'ada');
    const b = await makeEditor(server, 'bob');
    a.type('AAA', 0);
    await settle(server, [a, b]);
    await advance(1000); // history() groups edits made within 500 ms
    b.type('BBB', b.state.doc.length);
    await settle(server, [a, b]);
    expect(server.doc).toBe('AAAmidBBB');
    await advance(1000);
    a.type('CCC', 3);
    await settle(server, [a, b]);
    expect(server.doc).toBe('AAACCCmidBBB');
    await advance(1000);
    undo({ state: a.state, dispatch: (tr) => { a.state = tr.state; a.core.notifyChange(); } });
    await settle(server, [a, b]);
    expect(server.doc).toBe('AAAmidBBB');
    expect(b.state.doc.toString()).toBe('AAAmidBBB');
    undo({ state: a.state, dispatch: (tr) => { a.state = tr.state; a.core.notifyChange(); } });
    await settle(server, [a, b]);
    expect(server.doc).toBe('midBBB');
  });

  it('the pull starts at the join\'s event cursor: a joiner never replays older events', async () => {
    const server = createFakeCollabServer({ initialText: 'abc' });
    server.oversize(5, 4);
    server.renamePage('Q');
    const a = await makeEditor(server, 'ada');
    await advance(100);
    const firstPull = server.calls.find((c) => c.method === 'GET' && c.path === '/updates' && c.user === 'ada');
    expect(firstPull.query.eventsSince).toBe(a.joined.eventSeq);
    expect(a.events).toEqual([]); // not the old events, not its own participant-joined
    server.oversize(7, 6);
    await advance(100);
    expect(a.events.map((e) => e.type)).toEqual(['oversize']);
    a.core.destroy();
  });

  it('the pull starts at the join\'s meta cursor: a joiner never re-pulls the field edits its join already holds', async () => {
    const server = createFakeCollabServer({ initialText: 'abc' });
    const bob = server.transportFor('bob');
    const bj = await bob.join({});
    await bob.push({ sessionId: bj.sessionId, participantId: bj.participantId, version: 0, updates: [], metaOps: [{ field: 'summary', value: 'Bobs' }] });
    const a = await makeEditor(server, 'ada');
    expect(a.joined.metaSeq).toBe(1);
    await advance(100);
    const firstPull = server.calls.find((c) => c.method === 'GET' && c.path === '/updates' && c.user === 'ada');
    expect(firstPull.query.metaSince).toBe(1);
    expect(a.metaOps).toEqual([]);
    await bob.push({ sessionId: bj.sessionId, participantId: bj.participantId, version: 0, updates: [], metaOps: [{ field: 'type', value: 'note' }] });
    await advance(100);
    expect(a.metaOps).toEqual([expect.objectContaining({ field: 'type', value: 'note' })]);
    a.core.destroy();
  });

  it('a rejoin keeps its event cursor: events while it was away still arrive', async () => {
    const server = createFakeCollabServer({ initialText: 'abc' });
    const a = await makeEditor(server, 'ada');
    await advance(100);
    server.expire('ada');
    server.oversize(5, 4); // wakes the parked pull, which meets 409 rejoin
    await advance(1000);
    expect(server.calls.filter((c) => c.path === '/join' && c.user === 'ada')).toHaveLength(2);
    expect(a.events.map((e) => e.type)).toContain('oversize');
    a.core.destroy();
  });

  it('a 409 rejoin rejoins with the prior clientID and keeps unsent edits (7.2)', async () => {
    const server = createFakeCollabServer({ initialText: 'abc' });
    const a = await makeEditor(server, 'ada');
    const oldClient = a.joined.clientID;
    await advance(100);
    server.expire('ada');
    a.type('K', 3);
    await settle(server, [a]);
    expect(server.doc).toBe('abcK');
    const joins = server.calls.filter((c) => c.path === '/join' && c.user === 'ada');
    expect(joins).toHaveLength(2);
    expect(joins[1].body.priorClientId).toBe(oldClient);
    expect(a.resyncs).toEqual([]);
  });

  it('a rejoin that cannot keep the clientID hands the text to recovery instead of re-applying it', async () => {
    const server = createFakeCollabServer({ initialText: 'abc' });
    const a = await makeEditor(server, 'ada');
    await advance(100);
    server.expire('ada');
    server.forgetClientIds();
    a.type('K', 3);
    await advance(1000);
    expect(a.resyncs).toHaveLength(1);
    expect(a.resyncs[0].reason).toBe('rejoin-new-client');
    expect(a.resyncs[0].unsyncedText).toContain('K');
    await advance(100);
    // the participant the rejoin's join just created is not left behind as a ghost, and the stale one is gone too
    expect(server.liveParticipantIds()).toEqual([]);
    await expect(a.core.whenLeft()).resolves.toBeDefined();
  });

  it('a resync leaves the stale participant, and whenLeft resolves after that leave', async () => {
    const server = createFakeCollabServer({ initialText: 'abc', maxPageBytes: 5 });
    const a = await makeEditor(server, 'ada');
    a.type('0123456789', 3);
    await advance(500);
    expect(a.resyncs).toHaveLength(1);
    await a.core.whenLeft();
    expect(server.isLive(a.joined.participantId)).toBe(false);
  });

  it('two tabs of the same user are two participants and neither disturbs the other', async () => {
    const server = createFakeCollabServer({ initialText: 'abc', seed: 3 });
    const t1 = await makeEditor(server, 'ada');
    const t2 = await makeEditor(server, 'ada');
    expect(t1.joined.participantId).not.toBe(t2.joined.participantId);
    expect(t1.joined.clientID).not.toBe(t2.joined.clientID);
    t1.type('1', 0);
    t2.type('2', 3);
    await settle(server, [t1, t2]);
    expect(server.doc).toBe('1abc2');
    // tab 2 closes (pagehide beacon): tab 1 keeps working, unaffected
    t2.core.destroy();
    t2.transport.leaveBeacon({ sessionId: t2.joined.sessionId, participantId: t2.joined.participantId });
    t1.type('3', 1);
    await settle(server, [t1]);
    expect(server.doc).toBe('13abc2');
    expect(server.isLive(t1.joined.participantId)).toBe(true);
    expect(server.isLive(t2.joined.participantId)).toBe(false);
    expect(t1.resyncs).toEqual([]);
    expect(server.calls.filter((c) => c.user === 'ada' && c.status === 409 && c.reason === 'rejoin')).toEqual([]);
  });

  it('a stale-base 409 without a numeric version is malformed: it backs off instead of retrying at once', async () => {
    const server = createFakeCollabServer({ initialText: 'abc' });
    const sent = [];
    const a = await makeEditor(server, 'ada', {
      wrap: (t) => ({
        ...t,
        push: async (b) => {
          if (b.updates.length) {
            sent.push(Date.now());
            if (sent.length <= 2) throw Object.assign(new Error('stale'), { status: 409, body: { accepted: false, reason: 'stale-base' } });
          }
          return t.push(b);
        },
      }),
    });
    a.type('Q', 3);
    await settle(server, [a]);
    expect(sent.length).toBeGreaterThanOrEqual(3);
    expect(sent[1] - sent[0]).toBeGreaterThanOrEqual(500);
    expect(sent[2] - sent[1]).toBeGreaterThanOrEqual(1000);
    expect(server.doc).toBe('abcQ');
  });

  it('rate-limited pushes back off by retryAfterMs and then succeed', async () => {
    const server = createFakeCollabServer({ initialText: 'abc', pushesPerSecond: 2 });
    const a = await makeEditor(server, 'ada');
    for (let i = 0; i < 6; i++) { a.type(String(i), a.state.doc.length); await advance(110); }
    await settle(server, [a]);
    expect(server.doc).toBe('abc012345');
    expect(server.calls.some((c) => c.status === 429 && c.path === '/updates' && c.user === 'ada')).toBe(true);
    expect(a.resyncs).toEqual([]);
  });

  it('a 403 keeps the unsent edits and stops, until resume() (A13)', async () => {
    const server = createFakeCollabServer({ initialText: 'abc' });
    let deny = true;
    const a = await makeEditor(server, 'ada', {
      wrap: (t) => ({
        ...t,
        push: async (b) => { if (deny && b.updates.length) throw Object.assign(new Error('forbidden'), { status: 403, body: {} }); return t.push(b); },
      }),
    });
    a.type('Q', 3);
    await advance(2000);
    expect(a.statuses.at(-1)).toBe('auth-required');
    expect(sendableUpdates(a.state)).toHaveLength(1);
    expect(a.resyncs).toEqual([]);
    deny = false;
    a.core.resume();
    await settle(server, [a]);
    expect(server.doc).toBe('abcQ');
  });

  it('frozen pushes stop the peer and report it', async () => {
    const server = createFakeCollabServer({ initialText: 'abc' });
    const a = await makeEditor(server, 'ada');
    server.config.frozen = true;
    a.type('Q', 3);
    await advance(1000);
    expect(a.statuses.at(-1)).toBe('frozen');
    expect(sendableUpdates(a.state)).toHaveLength(1);
  });

  it('the selection piggybacks on pushes and the peer hands received presence, events and meta ops to the host', async () => {
    const server = createFakeCollabServer({ initialText: 'abcdef' });
    const a = await makeEditor(server, 'ada');
    const b = await makeEditor(server, 'bob');
    a.state = a.state.update({ selection: { anchor: 2, head: 4 } }).state;
    a.type('Z', 6);
    await settle(server, [a, b]);
    const sel = server.calls.filter((c) => c.user === 'ada' && c.method === 'POST' && c.path === '/updates' && c.body.updates.length)[0].body.selection;
    expect(sel).toMatchObject({ version: 0, unconfirmed: 1 });
    const seen = b.presence.flatMap((p) => Object.values(p));
    expect(seen.some((p) => p.displayName === 'ada' && p.anchor === 2 && p.head === 4)).toBe(true);
    // bob joined after ada's cursor, so ada hears of it; bob starts past his own join (R11)
    expect(a.events.some((e) => e.type === 'participant-joined' && e.participant.displayName === 'bob')).toBe(true);
    expect(b.events.some((e) => e.type === 'participant-joined')).toBe(false);
  });

  it('whenSynced resolves once every local edit is confirmed', async () => {
    const server = createFakeCollabServer({ initialText: 'abc' });
    const a = await makeEditor(server, 'ada');
    server.setLatency(40);
    a.type('1', 3);
    a.type('2', 4);
    let done = false;
    a.core.whenSynced().then(() => { done = true; });
    expect(done).toBe(false);
    await advance(2000);
    expect(done).toBe(true);
    expect(sendableUpdates(a.state)).toHaveLength(0);
  });
});

describe('collabPeer metadata', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); });

  it('metaEditsRideOnAPresenceOnlyPush', async () => {
    const server = createFakeCollabServer({ initialText: 'abc', initialMeta: { tags: ['a'] } });
    const acks = [];
    const a = await makeEditor(server, 'ada', { core: { onMetaAck: (e, seq) => acks.push({ e, seq }) } });
    a.core.queueMetaEdits([{ field: 'tags', add: 'x' }, { field: 'status', value: 'draft' }]);
    await advance(500);
    expect(server.metadata).toEqual({ tags: ['a', 'x'], status: 'draft' });
    expect(server.version).toBe(0); // no text moved
    expect(acks).toEqual([{ e: [{ field: 'tags', add: 'x' }, { field: 'status', value: 'draft' }], seq: 2 }]);
    expect(a.core.hasPendingMeta()).toBe(false);
  });

  it('aStalePushKeepsTheMetaEditsForTheRetry', async () => {
    const server = createFakeCollabServer({ initialText: 'one two' });
    let release;
    const gate = new Promise((r) => { release = r; });
    const a = await makeEditor(server, 'ada');
    const b = await makeEditor(server, 'bob', { wrap: (t) => ({ ...t, pull: async (q) => { await gate; return t.pull(q); } }) });
    a.type('A-', 0);
    await advance(300);
    b.type('!', 7);
    b.core.queueMetaEdits([{ field: 'tags', add: 'x' }]);
    await advance(300);
    // meta rode the stale push, which was refused; the edit is still queued
    expect(server.calls.some((c) => c.user === 'bob' && c.status === 409 && c.body.metaOps?.length)).toBe(true);
    release();
    await settle(server, [a, b]);
    await advance(500);
    expect(server.metadata.tags).toEqual(['x']);
    expect(b.core.hasPendingMeta()).toBe(false);
  });

  it('a network failure and a 429 keep the meta edits queued and resend them', async () => {
    const server = createFakeCollabServer({ initialText: 'abc' });
    let fails = 2;
    const a = await makeEditor(server, 'ada', {
      wrap: (t) => ({
        ...t,
        push: async (b) => {
          if (b.metaOps.length && fails-- > 0) {
            throw fails === 1 ? new TypeError('Failed to fetch') : Object.assign(new Error('slow'), { status: 429, body: { retryAfterMs: 200 } });
          }
          return t.push(b);
        },
      }),
    });
    a.core.queueMetaEdits([{ field: 'tags', add: 'x' }]);
    await advance(5000);
    expect(server.metadata.tags).toEqual(['x']);
  });

  it('whenSyncedWaitsForTheMetaQueue', async () => {
    const server = createFakeCollabServer({ initialText: 'abc' });
    const a = await makeEditor(server, 'ada');
    server.setLatency(40);
    a.core.queueMetaEdits([{ field: 'tags', add: 'x' }]);
    let done = false;
    a.core.whenSynced().then(() => { done = true; });
    await advance(20);
    expect(done).toBe(false);
    await advance(2000);
    expect(done).toBe(true);
    expect(server.metadata.tags).toEqual(['x']);
  });

  it('evicted403MapsToStatusByDetail', async () => {
    const run = async (detail) => {
      const server = createFakeCollabServer({ initialText: 'abc' });
      const evicted = [];
      const a = await makeEditor(server, 'ada', { core: { onEvicted: (e) => evicted.push(e) } });
      server.evict(a.joined.participantId, detail);
      a.type('Q', 3);
      a.core.queueMetaEdits([{ field: 'tags', add: 'x' }]);
      await advance(1000);
      return { a, evicted };
    };
    const lost = await run('lost-edit');
    expect(lost.a.statuses.at(-1)).toBe('forbidden');
    expect(lost.a.core.getEvictedReason()).toBe('lost-edit');
    expect(lost.evicted).toEqual([{ reason: 'lost-edit' }]);
    expect(lost.a.resyncs).toEqual([]);
    const out = await run('signed-out');
    expect(out.a.statuses.at(-1)).toBe('auth-required');
    expect(sendableUpdates(out.a.state)).toHaveLength(1);
    expect(out.a.core.hasPendingMeta()).toBe(true);
    expect(out.evicted).toEqual([]);
    const gone = await run('account-deleted');
    expect(gone.a.statuses.at(-1)).toBe('forbidden');
  });

  it('after a signed-out eviction, resume() rejoins with the prior clientID and sends every kept edit (A13)', async () => {
    const server = createFakeCollabServer({ initialText: 'abc' });
    const a = await makeEditor(server, 'ada');
    server.evict(a.joined.participantId, 'signed-out');
    a.type('Q', 3);
    a.core.queueMetaEdits([{ field: 'tags', add: 'x' }]);
    await advance(1000);
    expect(a.statuses.at(-1)).toBe('auth-required');
    a.core.resume();
    await settle(server, [a]);
    expect(server.doc).toBe('abcQ');
    expect(server.metadata.tags).toEqual(['x']);
    expect(a.core.getSession().participantId).not.toBe(a.joined.participantId);
    expect(a.core.getEvictedReason()).toBeNull();
    a.core.destroy();
  });

  it('a resume() whose rejoin is still refused (sign-in cancelled) keeps the eviction; the next resume rejoins', async () => {
    const server = createFakeCollabServer({ initialText: 'abc' });
    let signedIn = true;
    const a = await makeEditor(server, 'ada', {
      wrap: (t) => ({
        ...t,
        join: (b) => (signedIn ? t.join(b) : Promise.reject(Object.assign(new Error('HTTP 401'), { status: 401, body: {} }))),
      }),
    });
    signedIn = false;
    server.evict(a.joined.participantId, 'signed-out');
    a.type('Q', 3);
    await advance(1000);
    expect(a.statuses.at(-1)).toBe('auth-required');
    a.core.resume(); // the login was cancelled: still signed out
    await advance(1000);
    expect(a.statuses.at(-1)).toBe('auth-required');
    expect(a.core.getEvictedReason()).toBe('signed-out');
    signedIn = true;
    a.core.resume();
    await settle(server, [a]);
    expect(server.doc).toBe('abcQ');
    expect(a.core.getEvictedReason()).toBeNull();
    a.core.destroy();
  });

  it('onMetaFailed hands back the inflight meta when a resync or an eviction abandons it, not on signed-out', async () => {
    const edits = [{ field: 'tags', add: 'x' }];
    const server = createFakeCollabServer({ initialText: 'abc' });
    const failed = [];
    const a = await makeEditor(server, 'ada', { core: { onMetaFailed: (e) => failed.push(e) } });
    server.evict(a.joined.participantId, 'lost-edit');
    a.core.queueMetaEdits(edits);
    await advance(1000);
    expect(failed).toEqual([edits]);

    const s2 = createFakeCollabServer({ initialText: 'abc' });
    const f2 = [];
    const b = await makeEditor(s2, 'bob', { core: { onMetaFailed: (e) => f2.push(e) } });
    s2.evict(b.joined.participantId, 'signed-out');
    b.core.queueMetaEdits(edits);
    await advance(1000);
    expect(f2).toEqual([]);

    const s3 = createFakeCollabServer({ initialText: 'abc' });
    const f3 = [];
    const c = await makeEditor(s3, 'cy', {
      core: { onMetaFailed: (e) => f3.push(e) },
      wrap: (t) => ({ ...t, push: async () => { throw Object.assign(new Error('gone'), { status: 410, body: { resync: true } }); } }),
    });
    c.core.queueMetaEdits(edits);
    await advance(1000);
    expect(c.statuses.at(-1)).toBe('resync');
    expect(f3).toEqual([edits]);
  });
});

describe('unsentRemainder (final review item 2)', () => {
  const cap = (docText, unsyncedRanges) => ({
    reason: 'restart', docText, unsyncedRanges, unsyncedText: unsyncedRanges.map(([a, b]) => docText.slice(a, b)).join('\n'),
  });

  it('drops what the joined doc holds and keeps what it lacks', () => {
    expect(unsentRemainder(cap('Hello there unsent', [[5, 18]]), 'Hello there').unsyncedText).toBe(' unsent');
    expect(unsentRemainder(cap('Hello there', [[5, 11]]), 'Hello there').unsyncedText).toBe('');
    expect(unsentRemainder(cap('Hello there', [[5, 11]]), 'Hello').unsyncedText).toBe(' there');
  });

  it('a repeated character never hides a missing insert', () => {
    // my insert is the first "a"; the draft holds one "a": one of mine is missing, whichever side it is placed
    expect(unsentRemainder(cap('aa', [[0, 1]]), 'a').unsyncedText).toBe('a');
  });

  it('when others changed the draft too, every unsynced insert inside the difference is kept', () => {
    // mine: " mine" at 5 (stored) and " lost" at the end (not); bob replaced the start meanwhile
    const out = unsentRemainder(cap('Hello mine and lost', [[5, 10], [14, 19]]), 'Howdy mine and');
    expect(out.unsyncedText).toContain('lost');
  });

  it('leaves a payload without a capture alone', () => {
    const p = { reason: 'oversize', unsyncedText: 'x' };
    expect(unsentRemainder(p, 'doc')).toBe(p);
    expect(unsentRemainder(null, 'doc')).toBeNull();
  });
});
