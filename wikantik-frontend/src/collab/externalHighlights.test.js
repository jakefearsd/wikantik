import { describe, it, expect, afterEach, vi } from 'vitest';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { collab } from '@codemirror/collab';
import { externalHighlights, externalHighlightField, addExternalHighlight } from './externalHighlights.js';

describe('externalHighlights', () => {
  afterEach(() => { vi.useRealTimers(); });

  it('externalChangeRangesAreHighlightedAndFadeAfterTenSeconds', () => {
    vi.useFakeTimers();
    const view = new EditorView({
      state: EditorState.create({ doc: 'Hello world', extensions: [collab({ startVersion: 4, clientID: 'me' }), externalHighlights()] }),
      parent: document.body,
    });
    view.dispatch({ effects: addExternalHighlight.of({ ranges: [[0, 5]], version: 4, id: 'e1' }) });
    expect(view.state.field(externalHighlightField).size).toBe(1);
    view.dispatch({ changes: { from: 0, insert: '>>' } });
    const spans = [];
    view.state.field(externalHighlightField).between(0, 100, (f, t) => spans.push([f, t]));
    expect(spans).toEqual([[2, 7]]);
    vi.advanceTimersByTime(9000);
    expect(view.state.field(externalHighlightField).size).toBe(1);
    vi.advanceTimersByTime(1500);
    expect(view.state.field(externalHighlightField).size).toBe(0);
    view.destroy();
  });

  it('clears pending timers when the view is destroyed', () => {
    vi.useFakeTimers();
    const view = new EditorView({
      state: EditorState.create({ doc: 'abc', extensions: [collab({ startVersion: 0, clientID: 'me' }), externalHighlights()] }),
      parent: document.body,
    });
    view.dispatch({ effects: addExternalHighlight.of({ ranges: [[0, 2]], version: 0, id: 'x' }) });
    view.destroy();
    expect(() => vi.advanceTimersByTime(20000)).not.toThrow();
  });
});
