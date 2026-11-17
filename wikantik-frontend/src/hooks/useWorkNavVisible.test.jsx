import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';

vi.mock('./useAuth', () => ({ useAuth: vi.fn() }));
vi.mock('./useCapabilities', () => ({ useCapabilities: vi.fn() }));
vi.mock('../api/work', () => ({ getMe: vi.fn() }));
import { useAuth } from './useAuth';
import { useCapabilities } from './useCapabilities';
import { getMe } from '../api/work';
import { useWorkNavVisible } from './useWorkNavVisible';

const as = (user, work) => {
  useAuth.mockReturnValue({ user });
  useCapabilities.mockReturnValue({ capabilities: { work } });
};
beforeEach(() => { vi.clearAllMocks(); getMe.mockResolvedValue({ waitingOn: [], projects: [] }); });

describe('useWorkNavVisible', () => {
  it('is hidden, and makes no request, when the feature is off', () => {
    as({ authenticated: true, roles: ['Admin'] }, false);
    const { result } = renderHook(() => useWorkNavVisible());
    expect(result.current).toBe(false);
    expect(getMe).not.toHaveBeenCalled();
  });

  it('is hidden for an anonymous viewer', () => {
    as({ authenticated: false, roles: [] }, true);
    expect(renderHook(() => useWorkNavVisible()).result.current).toBe(false);
    expect(getMe).not.toHaveBeenCalled();
  });

  it('shows for an admin even with no projects', async () => {
    as({ authenticated: true, roles: ['Admin'] }, true);
    const { result } = renderHook(() => useWorkNavVisible());
    expect(result.current).toBe(true);
    await waitFor(() => expect(getMe).toHaveBeenCalled());
  });

  it('hides for a non-admin with no projects', async () => {
    as({ authenticated: true, roles: [] }, true);
    const { result } = renderHook(() => useWorkNavVisible());
    await waitFor(() => expect(getMe).toHaveBeenCalled());
    expect(result.current).toBe(false);
  });

  it('shows for a non-admin who belongs to a project', async () => {
    getMe.mockResolvedValue({ waitingOn: [], projects: [{ key: 'PLAT' }] });
    as({ authenticated: true, roles: [] }, true);
    const { result } = renderHook(() => useWorkNavVisible());
    await waitFor(() => expect(result.current).toBe(true));
  });

  it('hides for a non-admin whose only project is archived', async () => {
    getMe.mockResolvedValue({ waitingOn: [], projects: [{ key: 'OLD', archived: true }] });
    as({ authenticated: true, roles: [] }, true);
    const { result } = renderHook(() => useWorkNavVisible());
    await waitFor(() => expect(getMe).toHaveBeenCalled());
    expect(result.current).toBe(false);
  });
});

describe('useWorkNavVisible when the signed-in user changes', () => {
  it('does not reuse the previous user\'s answer: false until asked again for the new user', async () => {
    getMe.mockResolvedValueOnce({ projects: [{ key: 'PLAT' }] });
    as({ authenticated: true, username: 'alice', roles: [] }, true);
    const { result, rerender } = renderHook(() => useWorkNavVisible());
    await waitFor(() => expect(result.current).toBe(true));
    let answer;
    getMe.mockReturnValueOnce(new Promise((r) => { answer = r; }));
    as({ authenticated: true, username: 'bob', roles: [] }, true);
    rerender();
    expect(result.current).toBe(false);
    await waitFor(() => expect(getMe).toHaveBeenCalledTimes(2));
    answer({ projects: [] });
    await waitFor(() => expect(getMe).toHaveBeenCalledTimes(2));
    expect(result.current).toBe(false);
  });

  it('asks again after a logout and a new login', async () => {
    getMe.mockResolvedValue({ projects: [{ key: 'PLAT' }] });
    as({ authenticated: true, username: 'alice', roles: [] }, true);
    const { result, rerender } = renderHook(() => useWorkNavVisible());
    await waitFor(() => expect(result.current).toBe(true));
    as({ authenticated: false, username: 'anonymous', roles: [] }, true);
    rerender();
    expect(result.current).toBe(false);
    getMe.mockReturnValueOnce(new Promise(() => {}));
    as({ authenticated: true, username: 'carol', roles: [] }, true);
    rerender();
    expect(result.current).toBe(false);
    expect(getMe).toHaveBeenCalledTimes(2);
  });
});
