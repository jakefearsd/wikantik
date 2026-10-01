import { describe, it, expect, vi, beforeEach } from 'vitest';
vi.mock('../api/client', () => ({ api: { getPagePreview: vi.fn() } }));
import { api } from '../api/client';
import { loadPreview, evictPreview, clearPreviewCache, __resetPreviewCacheForTest } from './usePagePreview';

describe('page preview cache', () => {
  beforeEach(() => { vi.clearAllMocks(); __resetPreviewCacheForTest(); });

  it('fetches once and serves the cache afterwards', async () => {
    api.getPagePreview.mockResolvedValue({ name: 'A', title: 'A', excerpt: 'x' });
    expect(await loadPreview('A', null)).toEqual({ status: 'ok', data: { name: 'A', title: 'A', excerpt: 'x' } });
    await loadPreview('A', null);
    expect(api.getPagePreview).toHaveBeenCalledTimes(1);
  });

  it('caches a 404 as missing', async () => {
    api.getPagePreview.mockRejectedValue(Object.assign(new Error('nf'), { status: 404 }));
    expect(await loadPreview('Gone', null)).toEqual({ status: 'missing' });
    expect(await loadPreview('Gone', null)).toEqual({ status: 'missing' });
    expect(api.getPagePreview).toHaveBeenCalledTimes(1);
  });

  it('does not cache other failures', async () => {
    api.getPagePreview.mockRejectedValueOnce(Object.assign(new Error('boom'), { status: 500 }))
      .mockResolvedValueOnce({ name: 'B' });
    await expect(loadPreview('B', null)).rejects.toThrow('boom');
    expect((await loadPreview('B', null)).status).toBe('ok');
  });

  it('evictPreview drops every section of a page', async () => {
    api.getPagePreview.mockResolvedValue({ name: 'C' });
    await loadPreview('C', null);
    await loadPreview('C', 'usage');
    evictPreview('C');
    await loadPreview('C', null);
    expect(api.getPagePreview).toHaveBeenCalledTimes(3);
  });

  it('passes the section through', async () => {
    api.getPagePreview.mockResolvedValue({ name: 'D' });
    await loadPreview('D', 'setup');
    expect(api.getPagePreview).toHaveBeenCalledWith('D', expect.objectContaining({ section: 'setup' }));
  });

  it('a fetch still in flight when the cache is cleared is not cached afterwards', async () => {
    let resolve;
    api.getPagePreview.mockReturnValueOnce(new Promise((r) => { resolve = r; }))
      .mockResolvedValueOnce({ name: 'E', excerpt: 'fresh' });
    const pending = loadPreview('E', null);
    clearPreviewCache(); // e.g. logout while the hover fetch is in flight
    resolve({ name: 'E', excerpt: 'stale' });
    await pending;
    expect((await loadPreview('E', null)).data.excerpt).toBe('fresh');
    expect(api.getPagePreview).toHaveBeenCalledTimes(2);
  });
});
