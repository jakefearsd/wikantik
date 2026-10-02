import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { usePreviewSource, PREVIEW_ALWAYS_LIVE_CHARS, PREVIEW_FRAME_BUDGET_MS, PREVIEW_SETTLE_MS } from './usePreviewSource';

const big = (tag) => `${tag}${'x'.repeat(PREVIEW_ALWAYS_LIVE_CHARS)}`;
const SLOW = PREVIEW_FRAME_BUDGET_MS + 1;

afterEach(() => { vi.useRealTimers(); });

function mount(text) {
  return renderHook(({ t }) => usePreviewSource(t), { initialProps: { t: text } });
}

describe('usePreviewSource', () => {
  it('follows every keystroke on a small page, even when renders were slow', () => {
    const { result, rerender } = mount('a');
    result.current[1].ms = 500;
    rerender({ t: 'ab' });
    expect(result.current[0]).toBe('ab');
  });

  it('follows every keystroke on a large page whose renders fit the frame budget', () => {
    const { result, rerender } = mount(big('1'));
    result.current[1].ms = PREVIEW_FRAME_BUDGET_MS;
    rerender({ t: big('12') });
    expect(result.current[0]).toBe(big('12'));
  });

  it('on a large page with slow renders, shows the text once typing pauses, not per keystroke', () => {
    vi.useFakeTimers();
    const { result, rerender } = mount(big('1'));
    expect(result.current[0]).toBe(big('1'));
    result.current[1].ms = SLOW;
    rerender({ t: big('12') });
    act(() => { vi.advanceTimersByTime(PREVIEW_SETTLE_MS - 50); });
    rerender({ t: big('123') }); // still typing: the pending update restarts
    act(() => { vi.advanceTimersByTime(PREVIEW_SETTLE_MS - 50); });
    expect(result.current[0]).toBe(big('1'));
    act(() => { vi.advanceTimersByTime(50); });
    expect(result.current[0]).toBe(big('123'));
  });

  it('when renders turn slow, shows the text of the last pause, never older text', () => {
    vi.useFakeTimers();
    const { result, rerender } = mount(big('1'));
    rerender({ t: big('12') }); // live (cheap so far)
    act(() => { vi.advanceTimersByTime(PREVIEW_SETTLE_MS); }); // a pause: settled catches up
    rerender({ t: big('123') });
    expect(result.current[0]).toBe(big('123'));
    result.current[1].ms = SLOW;
    rerender({ t: big('1234') });
    expect(result.current[0]).toBe(big('12'));
    act(() => { vi.advanceTimersByTime(PREVIEW_SETTLE_MS); });
    expect(result.current[0]).toBe(big('1234'));
  });

  it('shows a slow page that loads after an empty first render on the next tick, without the settle delay', () => {
    vi.useFakeTimers();
    const { result, rerender } = mount('');
    result.current[1].ms = SLOW;
    rerender({ t: big('loaded') });
    expect(result.current[0]).toBe('');
    act(() => { vi.advanceTimersByTime(0); });
    expect(result.current[0]).toBe(big('loaded'));
  });
});
