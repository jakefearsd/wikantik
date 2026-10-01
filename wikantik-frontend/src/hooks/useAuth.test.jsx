import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { AuthProvider, useAuth } from './useAuth';
import { api } from '../api/client';
import { loadPreview, __resetPreviewCacheForTest } from './usePagePreview';

vi.mock('../api/client', () => ({
  api: {
    getUser: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
    getPagePreview: vi.fn(),
  },
}));

const wrapper = ({ children }) => <AuthProvider>{children}</AuthProvider>;

beforeEach(() => {
  vi.clearAllMocks();
  __resetPreviewCacheForTest();
});

describe('useAuth.logout', () => {
  it('flips user to anonymous after successful logout', async () => {
    api.getUser.mockResolvedValueOnce({ authenticated: true, username: 'janne' });
    api.logout.mockResolvedValueOnce({ success: true });

    const { result } = renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.user?.authenticated).toBe(true));

    await act(async () => { await result.current.logout(); });

    expect(result.current.user).toEqual({
      authenticated: false,
      username: 'anonymous',
      roles: [],
    });
  });

  it('still flips to anonymous even if api.logout rejects', async () => {
    api.getUser.mockResolvedValueOnce({ authenticated: true, username: 'janne' });
    api.logout.mockRejectedValueOnce(new Error('network down'));

    const { result } = renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.user?.authenticated).toBe(true));

    await act(async () => {
      await result.current.logout().catch(() => {});
    });

    expect(result.current.user.authenticated).toBe(false);
  });
});

describe('useAuth identity change drops cached link previews', () => {
  it('a preview fetched while logged in is refetched after logout', async () => {
    api.getUser.mockResolvedValueOnce({ authenticated: true, username: 'janne' });
    api.logout.mockResolvedValueOnce({ success: true });
    api.getPagePreview.mockResolvedValue({ name: 'Secret', excerpt: 'restricted text' });

    const { result } = renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.user?.authenticated).toBe(true));
    await loadPreview('Secret', null);
    await loadPreview('Secret', null);
    expect(api.getPagePreview).toHaveBeenCalledTimes(1); // cached while the identity is unchanged

    await act(async () => { await result.current.logout(); });
    await loadPreview('Secret', null);
    expect(api.getPagePreview).toHaveBeenCalledTimes(2);
  });

  it('a preview cached anonymously is refetched after login', async () => {
    api.getUser.mockResolvedValueOnce({ authenticated: false, username: 'anonymous' })
      .mockResolvedValueOnce({ authenticated: true, username: 'janne' });
    api.login.mockResolvedValueOnce({ success: true });
    api.getPagePreview.mockRejectedValue(Object.assign(new Error('nf'), { status: 404 }));

    const { result } = renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.user?.username).toBe('anonymous'));
    expect(await loadPreview('Members', null)).toEqual({ status: 'missing' });

    await act(async () => { await result.current.login('janne', 'pw'); });
    await waitFor(() => expect(result.current.user?.authenticated).toBe(true));
    await loadPreview('Members', null);
    expect(api.getPagePreview).toHaveBeenCalledTimes(2);
  });
});
