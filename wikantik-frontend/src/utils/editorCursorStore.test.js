import { describe, it, expect, vi } from 'vitest';
import { createEditorCursorStore } from './editorCursorStore';

describe('createEditorCursorStore', () => {
  it('starts at line 1 and notifies subscribers only on real changes', () => {
    const s = createEditorCursorStore();
    expect(s.get()).toEqual({ line: 1, col: 1, selectionText: '', topLine: 1 });
    const l = vi.fn();
    const unsub = s.subscribe(l);
    s.set({ line: 1 });
    expect(l).not.toHaveBeenCalled();
    s.set({ line: 4, col: 2 });
    expect(l).toHaveBeenCalledTimes(1);
    expect(s.get().line).toBe(4);
    unsub();
    s.set({ line: 5 });
    expect(l).toHaveBeenCalledTimes(1);
  });
});
