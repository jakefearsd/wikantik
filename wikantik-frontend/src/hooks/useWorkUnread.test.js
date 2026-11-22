import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

vi.mock('./useWorkNavVisible', () => ({ useWorkNavVisible: vi.fn() }));
vi.mock('../api/work', () => ({ getUnread: vi.fn() }));
import { useWorkNavVisible } from './useWorkNavVisible';
import { getUnread } from '../api/work';
import { useWorkUnread, useUnreadCount, announceUnread, resetUnreadPoller, POLL_MS } from './useWorkUnread';

const tick = (ms) => act(async () => { await vi.advanceTimersByTimeAsync(ms); });

beforeEach(() => {
  vi.useFakeTimers();
  vi.clearAllMocks();
  resetUnreadPoller();
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  getUnread.mockResolvedValue({ unread: 2 });
  useWorkNavVisible.mockReturnValue(true);
});
afterEach(() => { resetUnreadPoller(); vi.useRealTimers(); });

describe('useWorkUnread', () => {
  it('pollsEverySixtySecondsWhileVisible', async () => {
    const { result } = renderHook(() => useWorkUnread());
    await tick(0);
    expect(getUnread).toHaveBeenCalledTimes(1);
    expect(result.current.unread).toBe(2);
    getUnread.mockResolvedValue({ unread: 5 });
    await tick(POLL_MS);
    expect(getUnread).toHaveBeenCalledTimes(2);
    expect(result.current.unread).toBe(5);
  });

  it('doesNotPollWhenWorkIsHidden', async () => {
    useWorkNavVisible.mockReturnValue(false);
    const { result } = renderHook(() => useWorkUnread());
    await tick(180000);
    expect(getUnread).not.toHaveBeenCalled();
    expect(result.current.unread).toBe(0);
  });

  it('twoConsumersShareOneRequestPerInterval', async () => {
    renderHook(() => useWorkUnread());
    renderHook(() => useUnreadCount(true));
    await tick(0);
    await tick(POLL_MS);
    expect(getUnread).toHaveBeenCalledTimes(2);
  });

  it('aFailureBacksOffWarnsOnceAndRecoveryResetsTheInterval', async () => {
    renderHook(() => useWorkUnread());
    await tick(0);
    getUnread.mockRejectedValue(new Error('down'));
    await tick(POLL_MS); // failure 1 -> next in 2x
    expect(getUnread).toHaveBeenCalledTimes(2);
    await tick(POLL_MS); // not yet
    expect(getUnread).toHaveBeenCalledTimes(2);
    await tick(POLL_MS); // 2x elapsed: failure 2 -> next in 4x
    expect(getUnread).toHaveBeenCalledTimes(3);
    expect(console.warn).toHaveBeenCalledTimes(1);
    getUnread.mockResolvedValue({ unread: 7 });
    await tick(4 * POLL_MS);
    expect(getUnread).toHaveBeenCalledTimes(4);
    await tick(POLL_MS); // back to the base interval
    expect(getUnread).toHaveBeenCalledTimes(5);
  });

  it('aLapsedSessionStopsThePollWithoutAnAuthEventAndAnnounceRestartsIt', async () => {
    const auth = vi.fn();
    window.addEventListener('wikantik:auth-required', auth);
    getUnread.mockRejectedValue(Object.assign(new Error('expired'), { status: 401 }));
    const { result } = renderHook(() => useWorkUnread());
    await tick(0);
    await tick(10 * POLL_MS);
    expect(getUnread).toHaveBeenCalledTimes(1);
    expect(auth).not.toHaveBeenCalled();
    expect(console.warn).not.toHaveBeenCalled();
    getUnread.mockResolvedValue({ unread: 4 });
    await act(async () => { announceUnread(); await vi.advanceTimersByTimeAsync(0); });
    expect(getUnread).toHaveBeenCalledTimes(2);
    expect(result.current.unread).toBe(4);
    window.removeEventListener('wikantik:auth-required', auth);
  });

  it('anAnnouncementSetsTheCountWithoutAsking', async () => {
    const { result } = renderHook(() => useWorkUnread());
    await tick(0);
    act(() => announceUnread(0));
    expect(result.current.unread).toBe(0);
    expect(getUnread).toHaveBeenCalledTimes(1);
  });
});
