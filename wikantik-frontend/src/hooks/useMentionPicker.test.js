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
      { id: 'u-alice', displayName: 'Alice A' },
      { id: 'u-alicia', displayName: 'Alicia B' },
    ].filter((u) => u.displayName.toLowerCase().includes(q.toLowerCase())));
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

  it('accept(candidate) replaces the in-flight token with @[Display Name](user:<id>) + space', async () => {
    textareaRef.current = makeTextarea('hi @al ', 6);
    const { result } = renderHook(() => useMentionPicker({ textareaRef, fetchCandidates }));
    // Drive the picker via onChange so tokenStart/tokenEnd are tracked.
    await act(async () => { result.current.onChange({ target: textareaRef.current }); });
    await act(async () => { await vi.runAllTimersAsync(); });
    // Caret is at position 6 (right after 'al', before the space).
    const out = result.current.accept({ id: 'u-alice', displayName: 'Alice A' });
    // Original "hi @al ", token "@al" (indices 3..6) replaced with the stored token + space.
    expect(out.replacement).toBe('hi @[Alice A](user:u-alice)  ');
    // Caret position right after the inserted trailing space.
    expect(out.selectionStart).toBe('hi @[Alice A](user:u-alice) '.length);
    expect(out.selectionEnd).toBe(out.selectionStart);
  });

  it('accept() never writes a login name — only the display-name snapshot and the id', async () => {
    textareaRef.current = makeTextarea('@z', 2);
    const { result } = renderHook(() => useMentionPicker({ textareaRef, fetchCandidates }));
    await act(async () => { result.current.onChange({ target: textareaRef.current }); });
    const out = result.current.accept({ id: '7f3c', displayName: 'Zoë [Ng]' });
    expect(out.replacement).toBe('@[Zoë Ng](user:7f3c) ');
  });

  it('opens for a non-ASCII display-name fragment', async () => {
    textareaRef.current = makeTextarea('hi @Zoë', 7);
    const { result } = renderHook(() => useMentionPicker({ textareaRef, fetchCandidates }));
    await act(async () => { result.current.onChange({ target: textareaRef.current }); });
    await act(async () => { await vi.runAllTimersAsync(); });
    expect(result.current.open).toBe(true);
    expect(result.current.query).toBe('Zoë');
  });

  it('closes when the caret rests inside an already-inserted token', async () => {
    const value = 'hi @[Alice A](user:u-alice)';
    textareaRef.current = makeTextarea(value, value.length);
    const { result } = renderHook(() => useMentionPicker({ textareaRef, fetchCandidates }));
    await act(async () => { result.current.onChange({ target: textareaRef.current }); });
    await act(async () => { await vi.runAllTimersAsync(); });
    expect(result.current.open).toBe(false);
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

  it('Enter consumes the key without closing — caller resolves via accept()', async () => {
    textareaRef.current = makeTextarea('hi @a', 5);
    const { result } = renderHook(() => useMentionPicker({ textareaRef, fetchCandidates }));
    await act(async () => { result.current.onChange({ target: textareaRef.current }); });
    await act(async () => { await vi.runAllTimersAsync(); });
    const evt = { key: 'Enter', preventDefault: vi.fn() };
    let consumed;
    await act(() => { consumed = result.current.onKeyDown(evt); });
    expect(consumed).toBe(true);
    expect(evt.preventDefault).toHaveBeenCalled();
    // Picker stays open — selection is the caller's concern (they call accept()).
    expect(result.current.open).toBe(true);
  });

  it('Tab is consumed identically to Enter', async () => {
    textareaRef.current = makeTextarea('hi @a', 5);
    const { result } = renderHook(() => useMentionPicker({ textareaRef, fetchCandidates }));
    await act(async () => { result.current.onChange({ target: textareaRef.current }); });
    await act(async () => { await vi.runAllTimersAsync(); });
    const evt = { key: 'Tab', preventDefault: vi.fn() };
    let consumed;
    await act(() => { consumed = result.current.onKeyDown(evt); });
    expect(consumed).toBe(true);
    expect(evt.preventDefault).toHaveBeenCalled();
  });

  it('accept() returns empty replacement when textareaRef.current is null', () => {
    textareaRef.current = null;
    const { result } = renderHook(() => useMentionPicker({ textareaRef, fetchCandidates }));
    const out = result.current.accept({ id: 'u-alice', displayName: 'Alice A' });
    expect(out).toEqual({ replacement: '', selectionStart: 0, selectionEnd: 0 });
  });
});
