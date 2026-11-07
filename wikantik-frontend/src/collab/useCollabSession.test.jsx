import { StrictMode } from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { getSyncedVersion } from '@codemirror/collab';
import { useCollabSession } from './useCollabSession.js';
import { setRemotePresence } from './remoteCursors.js';
import { createFakeCollabServer } from './fakeCollabServer.js';

describe('useCollabSession', () => {
  let server;
  beforeEach(() => { vi.useFakeTimers(); server = createFakeCollabServer({ initialText: 'Hello', pollTimeoutMs: 5000 }); });
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

  const flush = () => act(async () => { await vi.advanceTimersByTimeAsync(50); });

  it('joins on mount and exposes status, docAtJoin, startVersion and participants', async () => {
    const transport = server.transportFor('ada');
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    expect(result.current.status).toBe('joining');
    await flush();
    expect(result.current.status).toBe('live');
    expect(result.current.docAtJoin).toBe('Hello');
    expect(result.current.startVersion).toBe(0);
    expect(result.current.participants.map((p) => p.displayName)).toEqual(['ada']);
    expect(result.current.clientID).toBeTruthy();
    expect(result.current.extensions).toBeTruthy();
  });

  it('passes initialText and initialMeta to join', async () => {
    const t = server.transportFor('ada');
    const spy = vi.spyOn(t, 'join');
    renderHook(() => useCollabSession('P', { transport: t, initialText: 'tmpl', initialMeta: { a: 1 } }));
    await flush();
    expect(spy).toHaveBeenCalledWith(expect.objectContaining({ initialText: 'tmpl', initialMeta: { a: 1 } }));
  });

  it('leaves on unmount', async () => {
    const transport = server.transportFor('ada');
    const { unmount } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    unmount();
    await flush();
    expect(server.calls.some((c) => c.path === '/leave' && c.user === 'ada')).toBe(true);
  });

  it('leaves via sendBeacon on pagehide', async () => {
    const transport = server.transportFor('ada');
    const beacon = vi.spyOn(transport, 'leaveBeacon');
    renderHook(() => useCollabSession('P', { transport }));
    await flush();
    act(() => { window.dispatchEvent(new Event('pagehide')); });
    expect(beacon).toHaveBeenCalledWith({ sessionId: 's-1f3a', participantId: 'pt-1' });
  });

  it('a join that resolves after unmount leaves immediately (strict-mode double mount)', async () => {
    const transport = server.transportFor('ada');
    const { unmount } = renderHook(() => useCollabSession('P', { transport }));
    unmount();
    await flush();
    expect(server.calls.filter((c) => c.path === '/leave' && c.user === 'ada')).toHaveLength(1);
  });

  const storeDown = () => Object.assign(new Error('collab store unavailable'), { status: 503, body: { reason: 'store' } });

  it('a first join refused 503 store is retried with backoff before it is reported', async () => {
    const base = server.transportFor('ada');
    let refusals = 2;
    const transport = { ...base, join: async (b) => { if (refusals > 0) { refusals -= 1; throw storeDown(); } return base.join(b); } };
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
    expect(result.current.status).toBe('live');
    expect(refusals).toBe(0);
  });

  it('a join that keeps meeting 503 store ends in error after a few attempts', async () => {
    let attempts = 0;
    const transport = { ...server.transportFor('ada'), join: async () => { attempts += 1; throw storeDown(); } };
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await act(async () => { await vi.advanceTimersByTimeAsync(60000); });
    expect(result.current.status).toBe('error');
    expect(attempts).toBeGreaterThan(1);
    expect(attempts).toBeLessThan(6);
  });

  it.each([
    ['collab-disabled', 'disabled'], ['derived-page', 'unavailable'], ['session-full', 'full'], ['forbidden', 'forbidden'],
  ])('maps a refused join (%s) to status %s', async (flag, status) => {
    const cfg = { 'collab-disabled': 'disabled', 'derived-page': 'derived', 'session-full': 'full', forbidden: 'forbidJoin' }[flag];
    server.config[cfg] = true;
    const transport = server.transportFor('ada');
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    expect(result.current.status).toBe(status);
  });

  it('publish waits for the peer to sync, then publishes at the synced version and surfaces the result', async () => {
    const transport = server.transportFor('ada');
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    const state = EditorState.create({ doc: result.current.docAtJoin, extensions: [result.current.extensions] });
    expect(state.doc.toString()).toBe('Hello');
    let outcome;
    await act(async () => {
      const p = result.current.publish('note');
      await vi.advanceTimersByTimeAsync(50);
      outcome = await p;
    });
    expect(outcome).toMatchObject({ ok: true, pageVersion: 8 });
    expect(server.calls.find((c) => c.path === '/publish').body).toMatchObject({ sessionId: 's-1f3a', participantId: 'pt-1', version: 0, changeNote: 'note' });
  });

  it('publish retries after publish-merged-retry and reports 422 violations', async () => {
    const transport = server.transportFor('ada');
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    server.config.publishMergedRetry = true;
    let outcome;
    await act(async () => { outcome = await result.current.publish(''); });
    expect(outcome.ok).toBe(true);
    expect(server.calls.filter((c) => c.path === '/publish')).toHaveLength(2);
    server.config.publishInvalid = true;
    await act(async () => { outcome = await result.current.publish(''); });
    expect(outcome).toMatchObject({ ok: false, status: 422, body: { error: 'frontmatter_validation_failed' } });
  });

  it('discard posts to the server', async () => {
    const transport = server.transportFor('ada');
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    await act(async () => { await result.current.discard(); });
    expect(server.calls.some((c) => c.path === '/discard')).toBe(true);
  });

  it('surfaces participants joining/leaving and events from the pull loop', async () => {
    const transport = server.transportFor('ada');
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    const view = new EditorView({
      state: EditorState.create({ doc: result.current.docAtJoin, extensions: [result.current.extensions] }),
      parent: document.body,
    });
    const bob = server.transportFor('bob');
    await act(async () => { await bob.join({}); await vi.advanceTimersByTimeAsync(50); });
    expect(result.current.participants.map((p) => p.displayName).sort()).toEqual(['ada', 'bob']);
    expect(result.current.events.some((e) => e.type === 'participant-joined')).toBe(true);
    await act(async () => { await bob.leave({ sessionId: 's-1f3a', participantId: 'pt-2' }); await vi.advanceTimersByTimeAsync(50); });
    expect(result.current.participants.map((p) => p.displayName)).toEqual(['ada']);
    view.destroy();
  });

  it('the peer plugin mounted in a real EditorView pushes typed text and applies the other editor\'s update', async () => {
    const transport = server.transportFor('ada');
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    const view = new EditorView({
      state: EditorState.create({ doc: result.current.docAtJoin, extensions: [result.current.extensions] }),
      parent: document.body,
    });
    view.dispatch({ changes: { from: 5, insert: ' there' } });
    await act(async () => { await vi.advanceTimersByTimeAsync(400); });
    expect(server.doc).toBe('Hello there');
    expect(getSyncedVersion(view.state)).toBe(1);
    const bob = server.transportFor('bob');
    const bj = await bob.join({});
    await act(async () => {
      await bob.push({ sessionId: bj.sessionId, participantId: bj.participantId, version: 1, updates: [{ clientID: bj.clientID, changes: [0, [0, '>> '], 11] }], metaOps: [] }).catch(() => {});
      await vi.advanceTimersByTimeAsync(100);
    });
    expect(view.state.doc.toString()).toBe('>> Hello there');
    view.destroy();
  });

  it('destroying the view stops the peer', async () => {
    const transport = server.transportFor('ada');
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    const view = new EditorView({
      state: EditorState.create({ doc: result.current.docAtJoin, extensions: [result.current.extensions] }),
      parent: document.body,
    });
    await flush();
    view.destroy();
    const before = server.calls.length;
    await act(async () => { await vi.advanceTimersByTimeAsync(20000); });
    expect(server.calls.length).toBe(before);
  });

  it('rendered under a real StrictMode it converges without resync and leaves only the discarded first join', async () => {
    const transport = server.transportFor('ada');
    const { result } = renderHook(() => useCollabSession('P', { transport }), { wrapper: StrictMode });
    await flush();
    expect(result.current.status).toBe('live');
    const view = new EditorView({
      state: EditorState.create({ doc: result.current.docAtJoin, extensions: [result.current.extensions] }),
      parent: document.body,
    });
    view.dispatch({ changes: { from: 5, insert: '!' } });
    await act(async () => { await vi.advanceTimersByTimeAsync(600); });
    expect(server.doc).toBe('Hello!');
    expect(result.current.resync).toBeNull();
    expect(result.current.status).toBe('live');
    const joins = server.calls.filter((c) => c.path === '/join');
    const leaves = server.calls.filter((c) => c.path === '/leave');
    expect(joins).toHaveLength(2);
    expect(leaves).toHaveLength(1);
    expect(leaves[0].body.participantId).toBe('pt-1');
    expect(server.liveParticipantIds()).toEqual([result.current.participants[0].participantId]);
    expect(server.calls.filter((c) => c.status === 409 && c.reason === 'rejoin')).toEqual([]);
    view.destroy();
  });

  it('restart() after a resync leaves the stale participant first, then joins afresh', async () => {
    let release;
    const gate = new Promise((r) => { release = r; });
    const base = server.transportFor('ada');
    const transport = { ...base, pull: async (q) => { await gate; return base.pull(q); } };
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    const view = new EditorView({
      state: EditorState.create({ doc: result.current.docAtJoin, extensions: [result.current.extensions] }),
      parent: document.body,
    });
    const oldId = result.current.participants[0].participantId;
    const bob = server.transportFor('bob');
    const bj = await bob.join({});
    for (let v = 0; v < 3; v += 1) {
      await bob.push({ sessionId: bj.sessionId, participantId: bj.participantId, version: v, updates: [{ clientID: bj.clientID, changes: [v === 0 ? 5 : 5 + v, [0, String(v)]] }], metaOps: [] });
    }
    server.truncateBelow(2);
    release();
    await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
    expect(result.current.status).toBe('resync');
    expect(result.current.resync.reason).toBe('resync');
    view.destroy();
    await act(async () => { await result.current.restart(); await vi.advanceTimersByTimeAsync(200); });
    const log = server.calls.filter((c) => (c.path === '/join' && c.user === 'ada') || (c.path === '/leave' && c.body.participantId === oldId));
    const order = log.map((c) => c.path);
    expect(order.indexOf('/leave')).toBeGreaterThan(-1);
    expect(order.lastIndexOf('/join')).toBeGreaterThan(order.indexOf('/leave'));
    expect(server.isLive(oldId)).toBe(false);
    expect(result.current.status).toBe('live');
    expect(result.current.resync).toBeNull();
    expect(result.current.participants.map((p) => p.participantId)).not.toContain(oldId);
  });

  it('discard resolves {ok:false} instead of throwing when the server refuses', async () => {
    const transport = server.transportFor('ada');
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    server.expire('ada');
    let out;
    await act(async () => { out = await result.current.discard(); });
    expect(out).toMatchObject({ ok: false, status: 409, reason: 'rejoin' });
  });

  it('presence lists every other live participant; one without a cursor has null selection fields', async () => {
    const transport = server.transportFor('ada');
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    const view = new EditorView({
      state: EditorState.create({ doc: result.current.docAtJoin, extensions: [result.current.extensions] }),
      parent: document.body,
    });
    const bob = server.transportFor('bob');
    const bj = await bob.join({});
    await bob.push({ sessionId: bj.sessionId, participantId: bj.participantId, version: 0, updates: [], metaOps: [], selection: { anchor: 1, head: 3, version: 0, unconfirmed: 0 } });
    const carl = server.transportFor('carl');
    const cj = await carl.join({});
    await act(async () => { await vi.advanceTimersByTimeAsync(100); });
    expect(result.current.status).toBe('live');
    expect(result.current.presence[bj.participantId]).toMatchObject({ displayName: 'bob', anchor: 1, head: 3, version: 0, unconfirmed: 0 });
    expect(result.current.presence[cj.participantId]).toEqual(expect.objectContaining({
      displayName: 'carl', anchor: null, head: null, version: null, unconfirmed: null,
    }));
    expect(result.current.participants.map((p) => p.displayName).sort()).toEqual(['ada', 'bob', 'carl']);
    // a later leave drops the entry
    await carl.leave({ sessionId: cj.sessionId, participantId: cj.participantId });
    await act(async () => { await vi.advanceTimersByTimeAsync(100); });
    expect(result.current.presence[cj.participantId]).toBeUndefined();
    expect(Object.keys(result.current.presence)).toEqual([bj.participantId]);
    view.destroy();
  });

  it('keeps only the last 200 events', async () => {
    const transport = server.transportFor('ada');
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    const view = new EditorView({
      state: EditorState.create({ doc: result.current.docAtJoin, extensions: [result.current.extensions] }),
      parent: document.body,
    });
    for (let i = 0; i < 250; i += 1) {
      const t = server.transportFor(`u${i}`);
      await t.join({});
    }
    await act(async () => { await vi.advanceTimersByTimeAsync(100); });
    expect(result.current.events.length).toBe(200);
    view.destroy();
  });

  it('unmounting destroys the peer: no more requests even with the view still alive', async () => {
    const transport = server.transportFor('ada');
    const { result, unmount } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    const view = new EditorView({
      state: EditorState.create({ doc: result.current.docAtJoin, extensions: [result.current.extensions] }),
      parent: document.body,
    });
    await flush();
    unmount();
    await flush();
    const before = server.calls.length;
    view.dispatch({ changes: { from: 0, insert: 'x' } });
    await act(async () => { await vi.advanceTimersByTimeAsync(20000); });
    expect(server.calls.length).toBe(before);
    view.destroy();
  });

  it('restart() routes unpushed text to the recovery path, loses nothing typed during the leave, and leaves the participant once', async () => {
    const base = server.transportFor('ada');
    const transport = {
      ...base,
      leave: async (b) => { await new Promise((r) => setTimeout(r, 300)); return base.leave(b); },
    };
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    const view = new EditorView({
      state: EditorState.create({ doc: result.current.docAtJoin, extensions: [result.current.extensions] }),
      parent: document.body,
    });
    const oldId = result.current.participants[0].participantId;
    view.dispatch({ changes: { from: 5, insert: ' unpushed' } }); // inside the 100 ms debounce: not sent yet
    expect(server.doc).toBe('Hello');
    let p;
    act(() => { p = result.current.restart(); });
    await act(async () => { await vi.advanceTimersByTimeAsync(100); });
    view.dispatch({ changes: { from: view.state.doc.length, insert: ' during-leave' } });
    await act(async () => { await vi.advanceTimersByTimeAsync(1000); await p; });
    expect(result.current.resync.unsyncedText).toContain('unpushed');
    expect(result.current.resync.unsyncedText).toContain('during-leave');
    expect(result.current.resync.reason).toBe('restart');
    expect(server.doc).toBe('Hello'); // never pushed, never re-applied
    expect(server.calls.filter((c) => c.path === '/leave' && c.body.participantId === oldId)).toHaveLength(1);
    expect(server.isLive(oldId)).toBe(false);
    expect(result.current.status).toBe('live');
    act(() => result.current.dismissResync());
    expect(result.current.resync).toBeNull();
    view.destroy();
  });

  it('a push stored but whose answer was lost is not offered as unsent text after the restart; the true remainder is', async () => {
    let release;
    const gate = new Promise((r) => { release = r; });
    const base = server.transportFor('ada');
    let lose = true;
    const transport = {
      ...base,
      pull: async (q) => { await gate; return base.pull(q); },
      push: async (b) => {
        const r = await base.push(b);
        if (lose && b.updates.length) { lose = false; throw new Error('network: the answer was lost'); }
        return r;
      },
    };
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    const view = new EditorView({
      state: EditorState.create({ doc: result.current.docAtJoin, extensions: [result.current.extensions] }),
      parent: document.body,
    });
    view.dispatch({ changes: { from: 5, insert: ' there' } });
    await act(async () => { await vi.advanceTimersByTimeAsync(150); }); // stored; the answer lost; backing off
    expect(server.doc).toBe('Hello there');
    view.dispatch({ changes: { from: view.state.doc.length, insert: ' unsent' } });
    server.truncateBelow(1); // the client's cursor (0) is behind the ring: its pull answers 410
    release();
    await act(async () => { await vi.advanceTimersByTimeAsync(100); });
    expect(result.current.status).toBe('resync');
    expect(result.current.resync.unsyncedText).toContain('there'); // still unconfirmed on the client
    view.destroy();
    await act(async () => { await result.current.restart(); await vi.advanceTimersByTimeAsync(200); });
    expect(result.current.status).toBe('live');
    expect(result.current.docAtJoin).toBe('Hello there');
    expect(result.current.resync.unsyncedText).toBe(' unsent'); // never ' there', which the draft already holds
  });

  it('after a restart whose join already holds every captured edit, nothing is offered as lost', async () => {
    let release;
    const gate = new Promise((r) => { release = r; });
    const base = server.transportFor('ada');
    let lose = true;
    const transport = {
      ...base,
      pull: async (q) => { await gate; return base.pull(q); },
      push: async (b) => {
        const r = await base.push(b);
        if (lose && b.updates.length) { lose = false; throw new Error('network: the answer was lost'); }
        return r;
      },
    };
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    const view = new EditorView({
      state: EditorState.create({ doc: result.current.docAtJoin, extensions: [result.current.extensions] }),
      parent: document.body,
    });
    view.dispatch({ changes: { from: 0, insert: 'Oh, ' } });
    await act(async () => { await vi.advanceTimersByTimeAsync(150); });
    expect(server.doc).toBe('Oh, Hello');
    server.truncateBelow(1);
    release();
    await act(async () => { await vi.advanceTimersByTimeAsync(100); });
    expect(result.current.resync.unsyncedText).toBe('Oh, ');
    await act(async () => { await result.current.restart(); await vi.advanceTimersByTimeAsync(200); });
    expect(result.current.resync.unsyncedText).toBe('');
    view.destroy();
  });

  it('restart() after the view was destroyed keeps the recovery payload that was already delivered', async () => {
    server = createFakeCollabServer({ initialText: 'Hello', maxPageBytes: 8 });
    const transport = server.transportFor('ada');
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    const view = new EditorView({
      state: EditorState.create({ doc: result.current.docAtJoin, extensions: [result.current.extensions] }),
      parent: document.body,
    });
    view.dispatch({ changes: { from: 5, insert: ' far too large' } });
    await act(async () => { await vi.advanceTimersByTimeAsync(500); });
    expect(result.current.status).toBe('resync');
    expect(result.current.resync.unsyncedText).toContain('far too large');
    view.destroy(); // the peer detaches: a fresh capture is no longer possible
    await act(async () => { await result.current.restart(); await vi.advanceTimersByTimeAsync(200); });
    expect(result.current.resync?.unsyncedText).toContain('far too large');
    expect(result.current.resync.reason).toBe('oversize');
    act(() => result.current.dismissResync());
    expect(result.current.resync).toBeNull();
  });

  it('participantsFollowThePresenceMap', async () => {
    const transport = server.transportFor('ada');
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    const view = new EditorView({
      state: EditorState.create({ doc: result.current.docAtJoin, extensions: [result.current.extensions] }),
      parent: document.body,
    });
    const bob = server.transportFor('bob');
    await act(async () => { await bob.join({}); await vi.advanceTimersByTimeAsync(50); });
    expect(Object.values(result.current.presence).map((e) => e.displayName)).toEqual(['bob']);
    expect(result.current.participants.map((p) => p.displayName)).toEqual(['ada', 'bob']);
    expect(result.current.participants[1].color).toBeTruthy();
    await act(async () => { await bob.leave({ sessionId: 's-1f3a', participantId: 'pt-2' }); await vi.advanceTimersByTimeAsync(50); });
    expect(result.current.participants.map((p) => p.displayName)).toEqual(['ada']);
    view.destroy();
  });

  it('presence and external-change events reach the editor decorations', async () => {
    const transport = server.transportFor('ada');
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    const view = new EditorView({
      state: EditorState.create({ doc: result.current.docAtJoin, extensions: [result.current.extensions] }),
      parent: document.body,
    });
    const bob = server.transportFor('bob');
    let pt;
    await act(async () => { pt = await bob.join({}); await vi.advanceTimersByTimeAsync(50); });
    await act(async () => {
      await bob.push({ sessionId: 's-1f3a', participantId: pt.participantId, version: 0, updates: [], metaOps: [], selection: { anchor: 2, head: 2, version: 0, unconfirmed: 0 } });
      await vi.advanceTimersByTimeAsync(50);
    });
    expect(view.dom.querySelectorAll('.cm-remote-caret').length).toBe(1);
    await act(async () => { server.externalChange({ source: 'disk', text: 'Hello there' }); await vi.advanceTimersByTimeAsync(50); });
    expect(view.dom.querySelectorAll('.cm-external-change').length).toBeGreaterThan(0);
    view.destroy();
  });

  it('pageRenamedRetargetsTheTransportWithoutRejoining', async () => {
    const base = server.transportFor('ada');
    const transport = { ...base, retarget: vi.fn() };
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    const view = new EditorView({
      state: EditorState.create({ doc: result.current.docAtJoin, extensions: [result.current.extensions] }),
      parent: document.body,
    });
    await act(async () => { server.renamePage('New'); await vi.advanceTimersByTimeAsync(50); });
    expect(transport.retarget).toHaveBeenCalledWith('New');
    expect(result.current.renamedTo).toBe('New');
    expect(server.calls.filter((c) => c.path === '/join')).toHaveLength(1);
    expect(result.current.status).toBe('live');
    view.destroy();
  });

  const recorder = () => {
    const seen = [];
    const ext = EditorView.updateListener.of((u) => {
      for (const tr of u.transactions) for (const e of tr.effects) if (e.is(setRemotePresence)) seen.push(e.value);
    });
    return { seen, ext };
  };

  it('a session change clears stale carets immediately', async () => {
    const transport = server.transportFor('ada');
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    const rec = recorder();
    const view = new EditorView({
      state: EditorState.create({ doc: result.current.docAtJoin, extensions: [result.current.extensions, rec.ext] }),
      parent: document.body,
    });
    server.expire('ada');
    await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
    expect(rec.seen.some((m) => Object.keys(m).length === 0)).toBe(true);
    view.destroy();
  });

  it('restart clears carets before remounting', async () => {
    const transport = server.transportFor('ada');
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    const rec = recorder();
    const view = new EditorView({
      state: EditorState.create({ doc: result.current.docAtJoin, extensions: [result.current.extensions, rec.ext] }),
      parent: document.body,
    });
    await act(async () => { await result.current.restart(); await vi.advanceTimersByTimeAsync(200); });
    expect(rec.seen.some((m) => Object.keys(m).length === 0)).toBe(true);
    view.destroy();
  });

  it('an external-change that arrives before the view mounts is highlighted once it does', async () => {
    const transport = server.transportFor('ada');
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    await act(async () => { server.externalChange({ source: 'disk', text: 'Hello there' }); await vi.advanceTimersByTimeAsync(50); });
    const view = new EditorView({
      state: EditorState.create({ doc: result.current.docAtJoin, extensions: [result.current.extensions] }),
      parent: document.body,
    });
    await act(async () => { await vi.advanceTimersByTimeAsync(50); });
    expect(view.dom.querySelectorAll('.cm-external-change').length).toBeGreaterThan(0);
    view.destroy();
  });
  const mountView = (result) => new EditorView({
    state: EditorState.create({ doc: result.current.docAtJoin, extensions: [result.current.extensions] }),
    parent: document.body,
  });

  it('meta: a field edit still inside the debounce window is pushed on unmount, before the leave', async () => {
    const transport = server.transportFor('ada');
    const { result, unmount } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    const view = mountView(result);
    act(() => { result.current.meta.edit('summary', 'typed'); });
    expect(result.current.meta.view().summary).toBe('typed');
    expect(result.current.meta.pending).toBe(true);
    unmount();
    await flush();
    expect(server.metadata.summary).toBe('typed');
    const push = server.calls.findIndex((c) => c.path === '/updates' && c.method === 'POST' && (c.body.metaOps || []).length);
    const leave = server.calls.findIndex((c) => c.path === '/leave');
    expect(push).toBeGreaterThan(-1);
    expect(push).toBeLessThan(leave);
    view.destroy();
  });

  it('meta: remote ops update the view, my acked edit stops being pending', async () => {
    server = createFakeCollabServer({ initialText: 'Hello', initialMeta: { tags: ['a'] }, pollTimeoutMs: 5000 });
    const transport = server.transportFor('ada');
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    const view = mountView(result);
    act(() => { result.current.meta.setField('tags', ['a', 'b']); });
    await flush();
    expect(server.metadata.tags).toEqual(['a', 'b']);
    expect(result.current.meta.pending).toBe(false);
    const bob = server.transportFor('bob');
    await act(async () => {
      const j = await bob.join({});
      await bob.push({ sessionId: j.sessionId, participantId: j.participantId, version: 0, updates: [], metaOps: [{ field: 'status', value: 'active' }] });
      await vi.advanceTimersByTimeAsync(50);
    });
    expect(result.current.meta.view()).toEqual({ tags: ['a', 'b'], status: 'active' });
    view.destroy();
  });

  it('presence is seeded from the join, before the first pull', async () => {
    const bob = server.transportFor('bob');
    await bob.join({});
    const transport = server.transportFor('ada');
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    expect(Object.values(result.current.presenceNow()).map((p) => p.displayName)).toEqual(['bob']);
    expect(result.current.participants.map((p) => p.displayName)).toEqual(['ada', 'bob']);
  });

  it('after page-renamed, the page name changing to the new name keeps the session (no leave, no rejoin)', async () => {
    const transport = server.transportFor('ada');
    const { result, rerender } = renderHook(({ name }) => useCollabSession(name, { transport }), { initialProps: { name: 'P' } });
    await flush();
    const view = mountView(result);
    await act(async () => { server.renamePage('Q'); await vi.advanceTimersByTimeAsync(50); });
    expect(result.current.renamedTo).toBe('Q');
    rerender({ name: 'Q' });
    await flush();
    expect(result.current.status).toBe('live');
    expect(server.calls.filter((c) => c.path === '/join')).toHaveLength(1);
    expect(server.calls.some((c) => c.path === '/leave')).toBe(false);
    rerender({ name: 'Other' });
    await flush();
    expect(server.calls.some((c) => c.path === '/leave')).toBe(true);
    view.destroy();
  });

  it('page-deleted makes the status frozen; publish then recreates without waiting for a sync', async () => {
    const transport = server.transportFor('ada');
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    const view = mountView(result);
    await act(async () => { server.freeze(); await vi.advanceTimersByTimeAsync(50); });
    expect(result.current.status).toBe('frozen');
    // a push meets the frozen draft and stops the peer: whenSynced can never resolve true now
    await act(async () => { view.dispatch({ changes: { from: 5, insert: '!' } }); await vi.advanceTimersByTimeAsync(300); });
    expect(server.calls.some((c) => c.path === '/updates' && c.reason === 'frozen')).toBe(true);
    let outcome;
    await act(async () => { const p = result.current.publish('again'); await vi.advanceTimersByTimeAsync(50); outcome = await p; });
    expect(outcome).toMatchObject({ ok: true });
    view.destroy();
  });

  it('an eviction for lost edit rights is forbidden with evictedReason', async () => {
    const transport = server.transportFor('ada');
    const { result } = renderHook(() => useCollabSession('P', { transport }));
    await flush();
    const view = mountView(result);
    await act(async () => { server.evict('pt-1', 'lost-edit'); await vi.advanceTimersByTimeAsync(50); });
    expect(result.current.status).toBe('forbidden');
    expect(result.current.evictedReason).toBe('lost-edit');
    view.destroy();
  });

  describe('draft state (Phase 3)', () => {
    const joinAs = async (user) => {
      const t = server.transportFor(user);
      const j = await t.join({});
      return { t, j };
    };
    const ids = (who) => ({ sessionId: who.j.sessionId, participantId: who.j.participantId });
    const typeAtEnd = (who, text) => who.t.push({
      ...ids(who), version: server.version, metaOps: [],
      updates: [{ clientID: who.j.clientID, changes: [server.doc.length, [0, text]] }],
    });
    const publishAs = (who) => who.t.publish({ ...ids(who), version: server.version, changeNote: '' });
    const advance = (ms = 50) => act(async () => { await vi.advanceTimersByTimeAsync(ms); });

    it('draftStateFollowsJoinAndEvents', async () => {
      const bob = await joinAs('bob');
      await typeAtEnd(bob, '!'); // bob contributes before ada joins
      const transport = server.transportFor('ada');
      const { result } = renderHook(() => useCollabSession('P', { transport }));
      await flush();
      expect(result.current.draft).toMatchObject({ unpublished: true, contributors: ['bob'] });
      expect(result.current.draft.lastEditAt).toEqual(expect.any(String));
      const view = mountView(result);
      await advance();
      await act(async () => { await publishAs(bob); await vi.advanceTimersByTimeAsync(50); });
      // as the server: a publish clears the contributors; lastEditAt still names the last edit
      expect(result.current.draft).toMatchObject({ unpublished: false, contributors: [] });
      expect(result.current.draft.lastEditAt).toEqual(expect.any(String));
      await act(async () => { await typeAtEnd(bob, '?'); await vi.advanceTimersByTimeAsync(50); });
      expect(result.current.draft).toMatchObject({ unpublished: true, contributors: ['bob'] });
      view.destroy();
    });

    it('a join without a draft block is seeded as nothing unpublished', async () => {
      const base = server.transportFor('ada');
      const transport = { ...base, join: async (b) => { const j = await base.join(b); delete j.draft; return j; } };
      const { result } = renderHook(() => useCollabSession('P', { transport }));
      await flush();
      expect(result.current.draft).toEqual({ unpublished: false, contributors: [], lastEditAt: null });
    });

    it('lastEditAtAdvancesOnRemoteUpdates', async () => {
      const transport = server.transportFor('ada');
      const { result } = renderHook(() => useCollabSession('P', { transport }));
      await flush();
      const view = mountView(result);
      let bob;
      await act(async () => { bob = await joinAs('bob'); await typeAtEnd(bob, '!'); await vi.advanceTimersByTimeAsync(50); });
      const firstIso = result.current.draft.lastEditAt;
      const first = Date.parse(firstIso);
      expect(Number.isNaN(first)).toBe(false);
      // within the display resolution an edit does not re-render the host for a new timestamp
      await act(async () => { await typeAtEnd(bob, '.'); await vi.advanceTimersByTimeAsync(50); });
      expect(result.current.draft.lastEditAt).toBe(firstIso);
      // bob is already a contributor: the server sends no draft-state for these, the client advances lastEditAt itself
      vi.setSystemTime(Date.now() + 600_000);
      await act(async () => { await typeAtEnd(bob, '?'); await vi.advanceTimersByTimeAsync(50); });
      const second = Date.parse(result.current.draft.lastEditAt);
      expect(second).toBeGreaterThanOrEqual(first + 600_000);
      vi.setSystemTime(Date.now() + 600_000);
      await act(async () => {
        await bob.t.push({ ...ids(bob), version: server.version, updates: [], metaOps: [{ field: 'status', value: 'active' }] });
        await vi.advanceTimersByTimeAsync(50);
      });
      expect(Date.parse(result.current.draft.lastEditAt)).toBeGreaterThanOrEqual(second + 600_000);
      view.destroy();
    });

    it('publishedAndNoticesAreExposed', async () => {
      const transport = server.transportFor('ada');
      const { result } = renderHook(() => useCollabSession('P', { transport }));
      await flush();
      expect(result.current.published).toBeNull();
      expect(result.current.notice).toBeNull();
      expect(result.current.oversizeLimit).toBeNull();
      const view = mountView(result);
      let bob;
      await act(async () => { bob = await joinAs('bob'); await publishAs(bob); await vi.advanceTimersByTimeAsync(50); });
      expect(result.current.published).toEqual({ by: 'bob', pageVersion: 8, seq: expect.any(Number) });
      server.failNextPublish('bob');
      await act(async () => { await publishAs(bob).catch(() => {}); await vi.advanceTimersByTimeAsync(50); });
      expect(result.current.notice).toEqual({ type: 'publish-failed', by: 'bob', seq: expect.any(Number) });
      await act(async () => { await bob.t.discard(ids(bob)); await vi.advanceTimersByTimeAsync(50); });
      expect(result.current.notice).toEqual({ type: 'draft-discarded', by: 'bob', seq: expect.any(Number) });
      await act(async () => { server.oversize(1_000_001, 1_000_000); await vi.advanceTimersByTimeAsync(50); });
      expect(result.current.notice).toEqual({ type: 'oversize', bytes: 1_000_001, limit: 1_000_000, seq: expect.any(Number) });
      expect(result.current.oversizeLimit).toBe(1_000_000);
      view.destroy();
    });

    it('notices and draft events from before the join are not replayed as news', async () => {
      const bob = await joinAs('bob');
      server.oversize(2_000_000, 1_000_000);
      await publishAs(bob);
      const transport = server.transportFor('ada');
      const { result } = renderHook(() => useCollabSession('P', { transport }));
      await flush();
      const view = mountView(result);
      await advance();
      expect(result.current.events).toEqual([]); // the pull starts at the join's cursor
      expect(result.current.published).toBeNull();
      expect(result.current.notice).toBeNull();
      expect(result.current.oversizeLimit).toBeNull();
      await act(async () => { server.oversize(3, 2); await vi.advanceTimersByTimeAsync(50); });
      expect(result.current.notice).toMatchObject({ type: 'oversize', bytes: 3, limit: 2 });
      view.destroy();
    });

    it('draftSummaryWaitsForSync', async () => {
      server = createFakeCollabServer({ initialText: 'Hello', initialMeta: { tags: ['a'] }, pollTimeoutMs: 5000 });
      const transport = server.transportFor('ada');
      const { result } = renderHook(() => useCollabSession('P', { transport }));
      await flush();
      const view = mountView(result);
      act(() => {
        view.dispatch({ changes: { from: 5, insert: ' world' } });
        result.current.meta.setField('status', 'draft');
      });
      // both are still inside their debounce windows
      let out;
      await act(async () => { const p = result.current.draftSummary(); await vi.advanceTimersByTimeAsync(500); out = await p; });
      expect(out.ok).toBe(true);
      expect(out.summary.diff).toContain('world');
      expect(out.summary.metaChanges).toEqual([{ field: 'status', before: null, after: 'draft' }]);
      expect(out.summary.contributors).toEqual(['ada']);
      expect(out.summary.version).toBe(1);
      const draftAt = server.calls.findIndex((c) => c.path === '/draft');
      const textAt = server.calls.findIndex((c) => c.method === 'POST' && c.path === '/updates' && c.body.updates?.length);
      const metaAt = server.calls.findIndex((c) => c.method === 'POST' && c.path === '/updates' && c.body.metaOps?.length);
      expect(textAt).toBeGreaterThan(-1);
      expect(metaAt).toBeGreaterThan(-1);
      expect(draftAt).toBeGreaterThan(Math.max(textAt, metaAt));
      expect(server.calls[draftAt].query).toEqual({ sessionId: 's-1f3a', participantId: 'pt-1' });
      view.destroy();
    });

    it('draftSummary reports a refusal as {ok:false, status, reason}', async () => {
      const base = server.transportFor('ada');
      const transport = {
        ...base,
        draft: async () => { throw Object.assign(new Error('evicted'), { status: 403, body: { reason: 'evicted', detail: 'lost-edit' } }); },
      };
      const { result } = renderHook(() => useCollabSession('P', { transport }));
      await flush();
      let out;
      await act(async () => { out = await result.current.draftSummary(); });
      expect(out).toEqual({ ok: false, status: 403, reason: 'evicted' });
    });

    it('unsyncedParticipantsListsUnconfirmedPresence', async () => {
      const transport = server.transportFor('ada');
      const { result } = renderHook(() => useCollabSession('P', { transport }));
      await flush();
      const view = mountView(result);
      await act(async () => {
        const bob = await joinAs('bob');
        const kim = await joinAs('kim');
        await bob.t.push({ ...ids(bob), version: server.version, updates: [], metaOps: [], selection: { anchor: 0, head: 0, version: 0, unconfirmed: 2 } });
        await kim.t.push({ ...ids(kim), version: server.version, updates: [], metaOps: [], selection: { anchor: 1, head: 1, version: 0, unconfirmed: 0 } });
        await vi.advanceTimersByTimeAsync(50);
      });
      expect(Object.keys(result.current.presence)).toHaveLength(2);
      expect(result.current.unsyncedParticipants()).toEqual(['bob']);
      view.destroy();
    });

    it('publishPassesMarkupSyntax', async () => {
      const transport = server.transportFor('ada');
      const { result } = renderHook(() => useCollabSession('P', { transport }));
      await flush();
      await act(async () => { await result.current.publish('Converted to Markdown', { markupSyntax: 'markdown' }); });
      await act(async () => { await result.current.publish('plain'); });
      const bodies = server.calls.filter((c) => c.path === '/publish').map((c) => c.body);
      expect(bodies[0]).toMatchObject({ changeNote: 'Converted to Markdown', markupSyntax: 'markdown' });
      expect('markupSyntax' in bodies[1]).toBe(false);
    });

    it('a page deleted and recreated, or renamed, before the join neither freezes the session nor sets renamedTo', async () => {
      server.freeze();
      server.config.frozen = false; // recreated before ada arrives
      server.renamePage('Q');
      const transport = server.transportFor('ada');
      const { result } = renderHook(() => useCollabSession('P', { transport }));
      await flush();
      const view = mountView(result);
      await advance();
      expect(result.current.status).toBe('live');
      expect(result.current.renamedTo).toBeNull();
      view.destroy();
    });

    it('isNews stays a second line of defence: a replayed event at or below the join cursor sets nothing', async () => {
      const bob = await joinAs('bob');
      server.oversize(2_000_000, 1_000_000);
      await publishAs(bob);
      const base = server.transportFor('ada');
      // a peer that (wrongly) replays the window from 0: the hook still treats those events as history
      let firstPull = true;
      const transport = {
        ...base,
        pull: (q) => { const replayed = firstPull ? { ...q, eventsSince: 0 } : q; firstPull = false; return base.pull(replayed); },
      };
      const { result } = renderHook(() => useCollabSession('P', { transport }));
      await flush();
      const view = mountView(result);
      await advance();
      expect(result.current.events.some((e) => e.type === 'oversize')).toBe(true);
      expect(result.current.notice).toBeNull();
      expect(result.current.published).toBeNull();
      view.destroy();
    });

    it('a join that answers frozen is frozen at once, without the page-deleted event', async () => {
      server.config.frozen = true; // no page-deleted event in the window
      const transport = server.transportFor('ada');
      const { result } = renderHook(() => useCollabSession('P', { transport }));
      await flush();
      expect(result.current.status).toBe('frozen');
    });

    it('anEmptyCaptureNeverOverwritesADeliveredPayload', async () => {
      server = createFakeCollabServer({ initialText: 'Hello', maxPageBytes: 8 });
      const transport = server.transportFor('ada');
      const { result } = renderHook(() => useCollabSession('P', { transport }));
      await flush();
      const view = mountView(result);
      view.dispatch({ changes: { from: 5, insert: ' far too large' } });
      await advance(500);
      expect(result.current.status).toBe('resync');
      expect(result.current.resync.unsyncedText).toContain('far too large');
      // the user deletes the text again: a fresh capture now finds nothing unsynced
      view.dispatch({ changes: { from: 5, to: 5 + ' far too large'.length } });
      await act(async () => { await result.current.restart(); await vi.advanceTimersByTimeAsync(200); });
      expect(result.current.resync?.unsyncedText).toContain('far too large');
      view.destroy();
    });
  });
});
