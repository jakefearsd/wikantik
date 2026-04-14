import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { AuthProvider, useAuth } from './useAuth';
import { api } from '../api/client';

vi.mock('../api/client', () => ({
  api: {
    getUser: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
  },
}));

const wrapper = ({ children }) => <AuthProvider>{children}</AuthProvider>;

beforeEach(() => {
  vi.clearAllMocks();
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
