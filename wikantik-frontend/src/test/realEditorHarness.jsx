/**
 * Shared helpers for PageEditor suites that mount the REAL @uiw/react-codemirror editor.
 *
 * react-codemirror arms a "typing latch" on every non-External transaction: a TimeoutLatch counted down by a
 * global 1 ms setInterval (200 ticks). A `value` change that arrives while it is armed is parked and replayed
 * (stale) when it fires. These helpers fake ONLY setInterval/clearInterval, so the latch stays armed until a
 * test explicitly expires it — every race below is deterministic. setTimeout stays real, so React,
 * testing-library's waitFor timeout and the editor's own timers are unaffected.
 */
import { render, act, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { EditorView } from '@codemirror/view';
import { vi, expect } from 'vitest';

/** Mount PageEditor at /edit/Existing and return the live EditorView once `expectedText` is loaded. */
export async function mountRealEditor(PageEditor, NavigationGuardProvider, expectedText) {
  render(
    <MemoryRouter initialEntries={['/edit/Existing']}>
      <NavigationGuardProvider>
        <Routes><Route path="/edit/:name" element={<PageEditor />} /></Routes>
      </NavigationGuardProvider>
    </MemoryRouter>,
  );
  await until(() => document.querySelector('.cm-editor') !== null);
  const view = EditorView.findFromDOM(document.querySelector('.cm-editor'));
  await until(() => view.state.doc.toString() === expectedText);
  return view;
}

/** Take control of react-codemirror's typing latch (call AFTER mounting, BEFORE the first keystroke). */
export function fakeLatchClock() {
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
}

/** Unmount-safe teardown: drain the latch scheduler on the fake clock, then restore real timers. */
export function releaseLatchClock() {
  try {
    vi.advanceTimersByTime(1000);
  } finally {
    vi.useRealTimers();
  }
}

/** Let `ms` of latch time pass (1000 ms is far past the 200 ms latch window). */
export function advanceLatch(ms = 1000) {
  act(() => { vi.advanceTimersByTime(ms); });
}

/** Poll with REAL setTimeout (the fake setInterval would starve waitFor's own polling). */
export async function until(predicate, timeoutMs = 3000) {
  const start = Date.now();
  let lastError;
  for (;;) {
    try {
      if (predicate()) return;
      lastError = null;
    } catch (err) {
      lastError = err; // the predicate may read DOM that is not there yet — reported if we time out
    }
    if (Date.now() - start > timeoutMs) {
      throw new Error(`until: condition not met in ${timeoutMs} ms: ${predicate}${lastError ? ` (last error: ${lastError.message})` : ''}`);
    }
    await act(async () => { await new Promise((r) => setTimeout(r, 5)); });
  }
}

/** Flush pending promise continuations (e.g. an awaited api call that was just resolved). */
export async function flush() {
  await act(async () => { for (let i = 0; i < 5; i += 1) await Promise.resolve(); });
}

/** One keystroke per character at the caret, dispatched exactly as CodeMirror's input handler does. */
export function typeAtCaret(view, text) {
  for (const ch of text) {
    act(() => {
      const pos = view.state.selection.main.head;
      view.dispatch({ changes: { from: pos, insert: ch }, selection: { anchor: pos + ch.length }, userEvent: 'input.type' });
    });
  }
}

export function placeCaret(view, pos) {
  act(() => { view.dispatch({ selection: { anchor: pos } }); });
}

export const docOf = (view) => view.state.doc.toString();
export const caretOf = (view) => view.state.selection.main.head;

/** A promise whose settlement the test controls. */
export function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

/** Open the attachment panel (once) and confirm a rename of the first row's stem to `newStem`. */
export function startRename(newStem) {
  if (!document.querySelector('.editor-with-panel')) fireEvent.click(screen.getByRole('button', { name: 'Attach' }));
  // A refused rename leaves the row in rename mode (AttachmentRow keeps the input open): reuse it.
  if (!document.querySelector('.attachment-rename-input')) fireEvent.click(screen.getAllByTitle('Rename')[0]);
  const input = document.querySelector('.attachment-rename-input');
  fireEvent.change(input, { target: { value: newStem } });
  fireEvent.click(screen.getByTitle('Confirm'));
}

/** Ctrl+S, then assert the save payload is exactly the text the editor shows. Returns the payload. */
export function saveAndExpectVisibleText(api, view) {
  const before = api.savePage.mock.calls.length;
  fireEvent.keyDown(window, { key: 's', ctrlKey: true });
  expect(api.savePage.mock.calls.length).toBe(before + 1);
  const payload = api.savePage.mock.calls[before][1];
  expect(payload.content).toBe(docOf(view));
  return payload;
}
