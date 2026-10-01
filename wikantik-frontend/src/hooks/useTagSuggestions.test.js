import { renderHook, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/client', () => ({ api: { listTags: vi.fn() } }));
import { api } from '../api/client';
import { useTagSuggestions, __resetTagSuggestionsForTest } from './useTagSuggestions';

describe('useTagSuggestions', () => {
  beforeEach(() => { vi.clearAllMocks(); __resetTagSuggestionsForTest(); });

  it('returns tag names ordered by usage count, then name', async () => {
    api.listTags.mockResolvedValue({ tags: [
      { tag: 'b-tag', count: 2 }, { tag: 'a-tag', count: 2 }, { tag: 'top', count: 9 },
    ] });
    const { result } = renderHook(() => useTagSuggestions());
    await waitFor(() => expect(result.current).toEqual(['top', 'a-tag', 'b-tag']));
  });

  it('fetches once per session and shares the result', async () => {
    api.listTags.mockResolvedValue({ tags: [{ tag: 'x', count: 1 }] });
    const a = renderHook(() => useTagSuggestions());
    await waitFor(() => expect(a.result.current).toEqual(['x']));
    const b = renderHook(() => useTagSuggestions());
    await waitFor(() => expect(b.result.current).toEqual(['x']));
    expect(api.listTags).toHaveBeenCalledTimes(1);
  });

  it('degrades to no suggestions and logs when the fetch fails', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.listTags.mockRejectedValue(new Error('down'));
    const { result } = renderHook(() => useTagSuggestions());
    await waitFor(() => expect(warn).toHaveBeenCalled());
    expect(result.current).toEqual([]);
    warn.mockRestore();
  });
});
