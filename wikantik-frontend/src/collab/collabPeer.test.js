import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { EditorState, Transaction } from '@codemirror/state';
import { history, undo } from '@codemirror/commands';
import { collab, getSyncedVersion, sendableUpdates } from '@codemirror/collab';
import { createFakeCollabServer, seededRandom } from './fakeCollabServer.js';
import { createCollabPeerCore } from './collabPeer.js';

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
    session: { sessionId: joined.sessionId, clientID: joined.clientID },
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
      await advance(Math.floor(rand() * 70));
    }
    await settle(server, editors);
    for (const e of editors) {
      expect(e.state.doc.toString()).toBe(server.doc);
      expect(e.resyncs).toEqual([]);
    }
    expect(server.version).toBeGreaterThan(100);
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
  });

  it('rate-limited pushes back off by retryAfterMs and then succeed', async () => {
    const server = createFakeCollabServer({ initialText: 'abc', pushesPerSecond: 2 });
    const a = await makeEditor(server, 'ada');
    for (let i = 0; i < 6; i++) { a.type(String(i), a.state.doc.length); await advance(110); }
    await settle(server, [a]);
    expect(server.doc).toBe('abc012345');
    expect(server.calls.some((c) => c.status === 409 && c.path === '/updates' && c.user === 'ada')).toBe(true);
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
    expect(b.events.some((e) => e.type === 'participant-joined')).toBe(true);
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
