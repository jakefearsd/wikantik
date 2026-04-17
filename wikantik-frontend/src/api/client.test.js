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
