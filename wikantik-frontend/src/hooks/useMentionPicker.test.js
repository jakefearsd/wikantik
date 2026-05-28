import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useMentionPicker } from './useMentionPicker';
import { getCaretCoordinates } from '../utils/caretCoords';

vi.mock('../utils/caretCoords', () => ({ getCaretCoordinates: vi.fn(() => ({ top: 222, left: 333 })) }));

function makeTextarea(value = '', selectionStart = 0) {
  const ta = document.createElement('textarea');
  ta.value = value;
  ta.selectionStart = selectionStart;
  ta.selectionEnd = selectionStart;
  document.body.appendChild(ta);
  // happy-dom doesn't update selectionStart on programmatic value change reliably; expose helpers.
  return ta;
}

describe('useMentionPicker', () => {
  let fetchCandidates;
  let textareaRef;

  beforeEach(() => {
    fetchCandidates = vi.fn(async (q) => [
      { loginName: 'alice', fullName: 'Alice A' },
      { loginName: 'alicia', fullName: 'Alicia B' },
    ].filter((u) => u.loginName.startsWith(q)));
    textareaRef = { current: null };
    vi.useFakeTimers();
  });

  it('is closed by default (no @ in text)', async () => {
    textareaRef.current = makeTextarea('hello world', 11);
    const { result } = renderHook(() => useMentionPicker({ textareaRef, fetchCandidates }));
    await act(async () => { result.current.onChange({ target: textareaRef.current }); });
    await act(async () => { await vi.runAllTimersAsync(); });
    expect(result.current.open).toBe(false);
  });

  it('opens after typing @ at the end', async () => {
    textareaRef.current = makeTextarea('hi @', 4);
    const { result } = renderHook(() => useMentionPicker({ textareaRef, fetchCandidates }));
    await act(async () => { result.current.onChange({ target: textareaRef.current }); });
    await act(async () => { await vi.runAllTimersAsync(); });
    expect(result.current.open).toBe(true);
    expect(result.current.query).toBe('');
  });

  it('updates query as user types', async () => {
    textareaRef.current = makeTextarea('hi @al', 6);
    const { result } = renderHook(() => useMentionPicker({ textareaRef, fetchCandidates }));
    await act(async () => { result.current.onChange({ target: textareaRef.current }); });
    await act(async () => { await vi.runAllTimersAsync(); });
    expect(result.current.open).toBe(true);
    expect(result.current.query).toBe('al');
    // fetchCandidates should have been called with the prefix.
    expect(fetchCandidates).toHaveBeenCalledWith('al');
    expect(result.current.candidates.length).toBe(2);
  });

  it('closes when a whitespace appears between @ and caret', async () => {
    textareaRef.current = makeTextarea('hi @al ic', 9);
    const { result } = renderHook(() => useMentionPicker({ textareaRef, fetchCandidates }));
    await act(async () => { result.current.onChange({ target: textareaRef.current }); });
    await act(async () => { await vi.runAllTimersAsync(); });
    expect(result.current.open).toBe(false);
  });

  it('arrow keys cycle selectedIndex', async () => {
    textareaRef.current = makeTextarea('hi @a', 5);
    const { result } = renderHook(() => useMentionPicker({ textareaRef, fetchCandidates }));
    await act(async () => { result.current.onChange({ target: textareaRef.current }); });
    await act(async () => { await vi.runAllTimersAsync(); });
    expect(result.current.selectedIndex).toBe(0);
    const evt = { key: 'ArrowDown', preventDefault: vi.fn() };
    let consumed;
    await act(async () => { consumed = result.current.onKeyDown(evt); });
    expect(consumed).toBe(true);
    expect(evt.preventDefault).toHaveBeenCalled();
    expect(result.current.selectedIndex).toBe(1);
  });

  it('Escape closes the picker', async () => {
    textareaRef.current = makeTextarea('hi @a', 5);
    const { result } = renderHook(() => useMentionPicker({ textareaRef, fetchCandidates }));
    await act(async () => { result.current.onChange({ target: textareaRef.current }); });
    await act(async () => { await vi.runAllTimersAsync(); });
    expect(result.current.open).toBe(true);
    const evt = { key: 'Escape', preventDefault: vi.fn() };
    let consumed;
    await act(() => { consumed = result.current.onKeyDown(evt); });
    expect(consumed).toBe(true);
    expect(result.current.open).toBe(false);
  });

  it('accept(login) replaces the in-flight token with @login + space', async () => {
    textareaRef.current = makeTextarea('hi @al ', 6);
    const { result } = renderHook(() => useMentionPicker({ textareaRef, fetchCandidates }));
    // Drive the picker via onChange so tokenStart/tokenEnd are tracked.
    await act(async () => { result.current.onChange({ target: textareaRef.current }); });
    await act(async () => { await vi.runAllTimersAsync(); });
    // Caret is at position 6 (right after 'al', before the space).
    const out = result.current.accept('alice');
    // Original "hi @al ", token "@al" (indices 3..6) replaced with "@alice " → "hi @alice  ".
    expect(out.replacement).toBe('hi @alice  ');
    // Caret position right after the inserted trailing space.
    expect(out.selectionStart).toBe('hi @alice '.length);
  });

  it('onKeyDown returns false when picker is closed', () => {
    textareaRef.current = makeTextarea('hello', 5);
    const { result } = renderHook(() => useMentionPicker({ textareaRef, fetchCandidates }));
    const consumed = result.current.onKeyDown({ key: 'ArrowDown', preventDefault: vi.fn() });
    expect(consumed).toBe(false);
  });

  it('sets anchorPos when the picker opens', async () => {
    textareaRef.current = makeTextarea('hi @al', 6);
    const { result } = renderHook(() => useMentionPicker({ textareaRef, fetchCandidates }));
    await act(async () => { result.current.onChange({ target: textareaRef.current }); });
    await act(async () => { await vi.runAllTimersAsync(); });
    expect(result.current.anchorPos).toEqual({ top: 222, left: 333 });
    expect(getCaretCoordinates).toHaveBeenCalled();
  });

  it('clears anchorPos when the picker closes', async () => {
    textareaRef.current = makeTextarea('hi @al', 6);
    const { result } = renderHook(() => useMentionPicker({ textareaRef, fetchCandidates }));
    await act(async () => { result.current.onChange({ target: textareaRef.current }); });
    await act(async () => { result.current.close(); });
    expect(result.current.anchorPos).toBeNull();
  });
});
