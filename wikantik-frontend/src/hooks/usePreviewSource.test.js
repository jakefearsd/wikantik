import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import {
  usePreviewSource, PREVIEW_ALWAYS_LIVE_CHARS, PREVIEW_FRAME_BUDGET_MS, PREVIEW_LIVE_AGAIN_MS, PREVIEW_SETTLE_MS,
} from './usePreviewSource';

const big = (tag) => `${tag}${'x'.repeat(PREVIEW_ALWAYS_LIVE_CHARS)}`;
const SLOW = PREVIEW_FRAME_BUDGET_MS + 1;

afterEach(() => { vi.useRealTimers(); });

function mount(text, key = 'A') {
  return renderHook(({ t, k }) => usePreviewSource(t, k), { initialProps: { t: text, k: key } });
}

describe('usePreviewSource', () => {
  it('follows every keystroke on a small page, even when renders were slow', () => {
    const { result, rerender } = mount('a');
    result.current[1].ms = 500;
    rerender({ k: 'A', t: 'ab' });
    expect(result.current[0]).toBe('ab');
  });

  it('follows every keystroke on a large page whose renders fit the frame budget', () => {
    const { result, rerender } = mount(big('1'));
    result.current[1].ms = PREVIEW_FRAME_BUDGET_MS;
    rerender({ k: 'A', t: big('12') });
    expect(result.current[0]).toBe(big('12'));
  });

  it('on a large page with slow renders, shows the text once typing pauses, not per keystroke', () => {
    vi.useFakeTimers();
    const { result, rerender } = mount(big('1'));
    expect(result.current[0]).toBe(big('1'));
    result.current[1].ms = SLOW;
    rerender({ k: 'A', t: big('12') });
    act(() => { vi.advanceTimersByTime(PREVIEW_SETTLE_MS - 50); });
    rerender({ k: 'A', t: big('123') }); // still typing: the pending update restarts
    act(() => { vi.advanceTimersByTime(PREVIEW_SETTLE_MS - 50); });
    expect(result.current[0]).toBe(big('1'));
    act(() => { vi.advanceTimersByTime(50); });
    expect(result.current[0]).toBe(big('123'));
  });

  it('when renders turn slow, keeps showing the last rendered text (never older) until the pause', () => {
    vi.useFakeTimers();
    const { result, rerender } = mount(big('1'));
    rerender({ k: 'A', t: big('12') }); // live (cheap so far)
    act(() => { vi.advanceTimersByTime(PREVIEW_SETTLE_MS); }); // a pause: settled catches up
    rerender({ k: 'A', t: big('123') });
    expect(result.current[0]).toBe(big('123'));
    result.current[1].ms = SLOW;
    rerender({ k: 'A', t: big('1234') });
    expect(result.current[0]).toBe(big('123')); // not the older settled '12'
    act(() => { vi.advanceTimersByTime(PREVIEW_SETTLE_MS); });
    expect(result.current[0]).toBe(big('1234'));
  });

  it('a render cost jittering around the budget neither flips the preview nor makes it go back', () => {
    vi.useFakeTimers();
    const typed = Array.from({ length: 12 }, (_, i) => big('a'.repeat(i + 1)));
    const { result, rerender } = mount(typed[0]);
    const shown = [];
    typed.slice(1).forEach((t, i) => {
      result.current[1].ms = i % 2 === 0 ? SLOW : PREVIEW_FRAME_BUDGET_MS - 1; // 13, 11, 13, 11, ...
      rerender({ k: 'A', t });
      act(() => { vi.advanceTimersByTime(50); }); // typing faster than the settle delay
      shown.push(typed.indexOf(result.current[0]));
    });
    expect(shown.every((v, i) => i === 0 || v >= shown[i - 1])).toBe(true); // never goes back
    expect(new Set(shown.slice(1)).size).toBe(1); // once slow, it stays put until the pause (no per-keystroke flip)
    act(() => { vi.advanceTimersByTime(PREVIEW_SETTLE_MS); });
    expect(result.current[0]).toBe(typed[11]);
  });

  it('returns to following keystrokes once renders are well under the budget', () => {
    vi.useFakeTimers();
    const { result, rerender } = mount(big('1'));
    result.current[1].ms = SLOW;
    rerender({ k: 'A', t: big('12') });
    expect(result.current[0]).toBe(big('1'));
    result.current[1].ms = PREVIEW_LIVE_AGAIN_MS - 1;
    rerender({ k: 'A', t: big('123') });
    expect(result.current[0]).toBe(big('123'));
  });

  it('switching to another page never shows the previous page\'s text', () => {
    vi.useFakeTimers();
    const { result, rerender } = mount(big('pageA'));
    result.current[1].ms = SLOW;
    rerender({ k: 'A', t: big('pageA-edited') });
    expect(result.current[0]).toBe(big('pageA'));
    rerender({ k: 'B', t: big('pageB') });
    expect(result.current[0]).toBe(big('pageB'));
    rerender({ k: 'B', t: big('pageB!') }); // B's own cost is not known yet: live
    expect(result.current[0]).toBe(big('pageB!'));
  });

  it('shows a slow page that loads after an empty first render on the next tick, without the settle delay', () => {
    vi.useFakeTimers();
    const { result, rerender } = mount('');
    result.current[1].ms = SLOW;
    rerender({ k: 'A', t: big('loaded') });
    expect(result.current[0]).toBe('');
    act(() => { vi.advanceTimersByTime(0); });
    expect(result.current[0]).toBe(big('loaded'));
  });
});
