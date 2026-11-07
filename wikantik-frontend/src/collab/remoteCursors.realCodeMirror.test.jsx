import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { useCollabSession } from './useCollabSession.js';
import { createFakeCollabServer } from './fakeCollabServer.js';

describe('remote cursors in two real editors', () => {
  let server;
  beforeEach(() => { vi.useFakeTimers(); server = createFakeCollabServer({ initialText: 'Hello world', pollTimeoutMs: 5000 }); });
  afterEach(() => { vi.useRealTimers(); });
  const settle = (ms = 600) => act(async () => { await vi.advanceTimersByTimeAsync(ms); });

  it('twoRealEditorsSeeEachOthersCaret', async () => {
    const mount = async (user) => {
      const transport = server.transportFor(user);
      const hook = renderHook(() => useCollabSession('P', { transport }));
      await settle(50);
      const view = new EditorView({
        state: EditorState.create({ doc: hook.result.current.docAtJoin, extensions: [hook.result.current.extensions] }),
        parent: document.body,
      });
      return { hook, view };
    };
    const a = await mount('ada');
    const b = await mount('bob');
    a.view.dispatch({ selection: { anchor: 5 } });
    b.view.dispatch({ selection: { anchor: 8 } });
    await settle();
    // concurrent typing, then caret positions follow
    a.view.dispatch({ changes: { from: 0, insert: 'AA' }, selection: { anchor: 7 } });
    b.view.dispatch({ changes: { from: 11, insert: 'BB' }, selection: { anchor: 13 } });
    await settle(2000);
    expect(a.view.state.doc.toString()).toBe(b.view.state.doc.toString());
    const carets = (v) => [...v.dom.querySelectorAll('.cm-remote-caret')];
    expect(carets(a.view)).toHaveLength(1);
    expect(carets(b.view)).toHaveLength(1);
    expect(carets(a.view)[0].textContent).toContain('bob');
    expect(carets(b.view)[0].textContent).toContain('ada');
    a.view.destroy(); b.view.destroy();
  });
});
