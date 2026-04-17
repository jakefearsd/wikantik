import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { api } from './client.js';

function mockFetchResponse({ status = 200, body = null, contentLength = null } = {}) {
  const headers = new Map();
  if (contentLength !== null) headers.set('Content-Length', String(contentLength));
  const text = body === null ? '' : JSON.stringify(body);
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: `status ${status}`,
    headers: {
      get: (k) => headers.get(k) ?? null,
    },
    json: async () => (body === null ? {} : body),
    text: async () => text,
  };
}

describe('api.admin.getIndexStatus', () => {
  beforeEach(() => {
    global.fetch = vi.fn();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('returns JSON body on success', async () => {
    const payload = { state: 'idle', lastRunAt: '2026-04-16T00:00:00Z' };
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 200, body: payload }));
    const result = await api.admin.getIndexStatus();
    expect(result).toEqual(payload);
    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining('/admin/content/index-status'),
      expect.objectContaining({ credentials: 'same-origin' }),
    );
  });

  it('throws on non-2xx with status attached', async () => {
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 500, body: { message: 'boom' } }));
    await expect(api.admin.getIndexStatus()).rejects.toMatchObject({ status: 500, message: 'boom' });
  });
});

describe('api.admin.rebuildIndexes', () => {
  beforeEach(() => {
    global.fetch = vi.fn();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('returns JSON body on success', async () => {
    const payload = { accepted: true, jobId: 'abc-123' };
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 200, body: payload }));
    const result = await api.admin.rebuildIndexes();
    expect(result).toEqual(payload);
    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining('/admin/content/rebuild-indexes'),
      expect.objectContaining({ method: 'POST' }),
    );
  });

  it('throws a distinguishable 409 error when a rebuild is already in flight', async () => {
    global.fetch.mockResolvedValue(
      mockFetchResponse({ status: 409, body: { message: 'already running', jobId: 'existing' } }),
    );
    await expect(api.admin.rebuildIndexes()).rejects.toMatchObject({
      status: 409,
      code: 'rebuild_in_flight',
    });
  });

  it('throws a distinguishable 503 error when rebuild is disabled', async () => {
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 503, body: { message: 'disabled' } }));
    await expect(api.admin.rebuildIndexes()).rejects.toMatchObject({
      status: 503,
      code: 'rebuild_disabled',
    });
  });

  it('throws a generic error for other non-2xx responses', async () => {
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 500, body: { message: 'boom' } }));
    await expect(api.admin.rebuildIndexes()).rejects.toMatchObject({ status: 500, message: 'boom' });
  });
});

describe('api.admin.getChunks', () => {
  beforeEach(() => {
    global.fetch = vi.fn();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('returns JSON body on success and URL-encodes the page name', async () => {
    const payload = { page: 'My Page', chunks: [{ chunk_index: 0, text: 'hi' }] };
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 200, body: payload }));
    const result = await api.admin.getChunks('My Page');
    expect(result).toEqual(payload);
    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining('/admin/content/chunks?page=My%20Page'),
      expect.objectContaining({ credentials: 'same-origin' }),
    );
  });

  it('throws a distinguishable 404 error when the page has no chunks', async () => {
    global.fetch.mockResolvedValue(
      mockFetchResponse({ status: 404, body: { error: 'page not found', page: 'Missing' } }),
    );
    await expect(api.admin.getChunks('Missing')).rejects.toMatchObject({
      status: 404,
      code: 'page_not_found',
    });
  });
});

describe('api.admin.getChunkOutliers', () => {
  beforeEach(() => {
    global.fetch = vi.fn();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('returns JSON body on success', async () => {
    const payload = {
      most_chunks: [{ page_name: 'A', chunk_count: 12, max_tokens: 500, total_tokens: 5000, char_count: 2000 }],
      large_single_chunks: [],
      oversized_chunks: [],
    };
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 200, body: payload }));
    const result = await api.admin.getChunkOutliers();
    expect(result).toEqual(payload);
    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining('/admin/content/chunks/outliers'),
      expect.objectContaining({ credentials: 'same-origin' }),
    );
  });
});
