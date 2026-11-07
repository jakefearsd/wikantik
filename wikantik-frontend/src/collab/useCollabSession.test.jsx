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
});
