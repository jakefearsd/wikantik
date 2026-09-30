/**
 * Tiny external store for the editor's cursor position, selection and top visible line. Updates fire
 * on every keystroke and scroll; routing them through a store lets only the rail and status bar
 * re-render, never PageEditor and its preview.
 */
export function createEditorCursorStore() {
  let state = { line: 1, col: 1, selectionText: '', topLine: 1 };
  const listeners = new Set();
  return {
    get: () => state,
    set(patch) {
      if (Object.keys(patch).every((k) => patch[k] === state[k])) return;
      state = { ...state, ...patch };
      listeners.forEach((l) => l());
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}
