import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { useUnreadMentions } from './useUnreadMentions';
import { api } from '../api/client';

vi.mock('../api/client', () => ({
  api: {
    getMyMentionsUnreadCount: vi.fn(),
  },
}));

describe('useUnreadMentions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('fetches count on mount', async () => {
    api.getMyMentionsUnreadCount.mockResolvedValue({ count: 3 });
    const { result } = renderHook(() => useUnreadMentions());
    await waitFor(() => expect(result.current.count).toBe(3));
    expect(api.getMyMentionsUnreadCount).toHaveBeenCalledTimes(1);
  });

  it('exposes refresh that re-fetches', async () => {
    api.getMyMentionsUnreadCount.mockResolvedValue({ count: 1 });
    const { result } = renderHook(() => useUnreadMentions());
    await waitFor(() => expect(result.current.count).toBe(1));
    api.getMyMentionsUnreadCount.mockResolvedValue({ count: 5 });
    await act(async () => { await result.current.refresh(); });
    expect(result.current.count).toBe(5);
  });

  it('refreshes on visibilitychange when document becomes visible', async () => {
    api.getMyMentionsUnreadCount.mockResolvedValue({ count: 2 });
    renderHook(() => useUnreadMentions());
    await waitFor(() => expect(api.getMyMentionsUnreadCount).toHaveBeenCalledTimes(1));
    // Simulate tab becoming visible.
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
    await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });
    await waitFor(() => expect(api.getMyMentionsUnreadCount).toHaveBeenCalledTimes(2));
  });

  it('returns 0 if API throws', async () => {
    api.getMyMentionsUnreadCount.mockRejectedValue(new Error('nope'));
    const { result } = renderHook(() => useUnreadMentions());
    await waitFor(() => expect(api.getMyMentionsUnreadCount).toHaveBeenCalled());
    expect(result.current.count).toBe(0);
  });

  it('does nothing when enabled=false', async () => {
    api.getMyMentionsUnreadCount.mockResolvedValue({ count: 7 });
    const { result } = renderHook(() => useUnreadMentions({ enabled: false }));
    // No fetch and count stays 0.
    expect(api.getMyMentionsUnreadCount).not.toHaveBeenCalled();
    expect(result.current.count).toBe(0);
  });
});
