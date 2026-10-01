import { renderHook, act, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { __resetRegistryForTest, getCommands } from './registry';

const toast = { error: vi.fn(), success: vi.fn(), info: vi.fn() };
vi.mock('../hooks/useToast', () => ({ useToast: () => toast }));
import { useRegisterCommands, useCommands, useRunCommand } from './useCommands';

describe('useCommands', () => {
  beforeEach(() => { __resetRegistryForTest(); vi.clearAllMocks(); });

  it('registers while mounted and unregisters on unmount', () => {
    const { unmount } = renderHook(() => useRegisterCommands([{ id: 'x', title: 'X', run: () => {} }], []));
    expect(getCommands().map((c) => c.id)).toEqual(['x']);
    unmount();
    expect(getCommands()).toEqual([]);
  });

  it('useCommands re-renders when the registry changes', async () => {
    const { result } = renderHook(() => useCommands());
    expect(result.current).toEqual([]);
    renderHook(() => useRegisterCommands([{ id: 'y', title: 'Y', run: () => {} }], []));
    await waitFor(() => expect(result.current.map((c) => c.id)).toEqual(['y']));
  });

  it('useRunCommand toasts a failure with the command title', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    renderHook(() => useRegisterCommands([{ id: 'z', title: 'Zap', run: () => { throw new Error('no'); } }], []));
    const { result } = renderHook(() => useRunCommand());
    await act(async () => { await result.current('z'); });
    expect(toast.error).toHaveBeenCalledWith('Command failed: Zap');
  });
});
