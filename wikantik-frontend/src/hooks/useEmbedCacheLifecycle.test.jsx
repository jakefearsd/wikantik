import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook } from '@testing-library/react';

vi.mock('../components/WikiEmbed', () => ({ clearWikiEmbedCache: vi.fn() }));

import { clearWikiEmbedCache } from '../components/WikiEmbed';
import { useEmbedCacheLifecycle } from './useEmbedCacheLifecycle';

describe('useEmbedCacheLifecycle', () => {
  beforeEach(() => vi.clearAllMocks());

  it('does not clear on first mount (initial in-flight embeds must survive)', () => {
    renderHook(() => useEmbedCacheLifecycle(true));
    expect(clearWikiEmbedCache).not.toHaveBeenCalled();
  });

  it('clears when the preview is re-opened, not when it closes', () => {
    const { rerender } = renderHook(({ open }) => useEmbedCacheLifecycle(open), { initialProps: { open: true } });
    rerender({ open: false });
    expect(clearWikiEmbedCache).not.toHaveBeenCalled();
    rerender({ open: true });
    expect(clearWikiEmbedCache).toHaveBeenCalledTimes(1);
  });

  it('clears on unmount so the module-level cache does not outlive the editor', () => {
    const { unmount } = renderHook(() => useEmbedCacheLifecycle(true));
    unmount();
    expect(clearWikiEmbedCache).toHaveBeenCalledTimes(1);
  });
});
