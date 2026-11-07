import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { getSyncedVersion } from '@codemirror/collab';
import { useCollabSession } from './useCollabSession.js';
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
    expect(beacon).toHaveBeenCalledWith({ sessionId: 's-1f3a' });
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
    expect(server.calls.find((c) => c.path === '/publish').body).toMatchObject({ sessionId: 's-1f3a', version: 0, changeNote: 'note' });
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
    await act(async () => { await bob.leave({ sessionId: 's-1f3a' }); await vi.advanceTimersByTimeAsync(50); });
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
      await bob.push({ sessionId: bj.sessionId, version: 1, updates: [{ clientID: bj.clientID, changes: [0, [0, '>> '], 11] }], metaOps: [] }).catch(() => {});
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
});
