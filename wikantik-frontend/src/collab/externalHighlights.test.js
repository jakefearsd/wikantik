import { describe, it, expect, afterEach, vi } from 'vitest';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { collab, receiveUpdates } from '@codemirror/collab';
import { ChangeSet } from '@codemirror/state';
import { externalHighlights, externalHighlightField, addExternalHighlight, removeExternalHighlight } from './externalHighlights.js';

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

  it('a highlight survives a remote receiveUpdates and moves with it', () => {
    const view = new EditorView({
      state: EditorState.create({ doc: 'Hello world', extensions: [collab({ startVersion: 4, clientID: 'me' }), externalHighlights()] }),
      parent: document.body,
    });
    view.dispatch({ effects: addExternalHighlight.of({ ranges: [[6, 11]], version: 4, id: 'e1' }) });
    view.dispatch(receiveUpdates(view.state, [{ clientID: 'o', changes: ChangeSet.of({ from: 0, insert: 'ZZZ' }, 11) }]));
    const spans = [];
    view.state.field(externalHighlightField).between(0, 100, (f, t) => spans.push([f, t]));
    expect(spans).toEqual([[9, 14]]);
    view.destroy();
  });

  it('removeExternalHighlight drops one id and keeps the others', () => {
    const view = new EditorView({
      state: EditorState.create({ doc: 'Hello world', extensions: [collab({ startVersion: 4, clientID: 'me' }), externalHighlights()] }),
      parent: document.body,
    });
    view.dispatch({ effects: addExternalHighlight.of({ ranges: [[0, 2]], version: 4, id: 'a' }) });
    view.dispatch({ effects: addExternalHighlight.of({ ranges: [[4, 6], [8, 10]], version: 4, id: 'b' }) });
    expect(view.state.field(externalHighlightField).size).toBe(3);
    view.dispatch({ effects: removeExternalHighlight.of('b') });
    const spans = [];
    view.state.field(externalHighlightField).between(0, 100, (f, t) => spans.push([f, t]));
    expect(spans).toEqual([[0, 2]]);
    view.destroy();
  });
});
