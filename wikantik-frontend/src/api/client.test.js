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

describe('request envelope unwrapping', () => {
  beforeEach(() => {
    global.fetch = vi.fn();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('unwraps single-key `{data: ...}` envelopes so callers can read fields directly', async () => {
    const wrapped = { data: { recent_runs: [{ id: 1 }], count: 1 } };
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 200, body: wrapped }));
    const result = await api.admin.listRetrievalRuns();
    expect(result).toEqual({ recent_runs: [{ id: 1 }], count: 1 });
  });

  it('leaves multi-key responses untouched even when one key happens to be `data`', async () => {
    // A legacy resource that happens to ship a top-level `data` field alongside
    // siblings is NOT an envelope — leave it intact.
    const payload = { data: { x: 1 }, count: 5 };
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 200, body: payload }));
    const result = await api.admin.listRetrievalRuns();
    expect(result).toEqual(payload);
  });

  it('returns null for 204 responses without trying to unwrap', async () => {
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 204, contentLength: 0 }));
    const result = await api.admin.listRetrievalRuns();
    expect(result).toBeNull();
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

describe('anchored comment thread methods', () => {
  beforeEach(() => {
    global.fetch = vi.fn();
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 200, body: {} }));
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  function lastCall() {
    return global.fetch.mock.calls[global.fetch.mock.calls.length - 1];
  }

  it('listCommentThreads issues GET with page + status query', async () => {
    await api.listCommentThreads('Foo', 'open');
    const [url, opts] = lastCall();
    expect(url).toContain('/api/comment-threads?page=Foo&status=open');
    // GET = no explicit method override
    expect(opts.method).toBeUndefined();
  });

  it('createCommentThread POSTs the anchor + text body to the page', async () => {
    await api.createCommentThread('Foo', { exact: 'e', prefix: 'p', suffix: 's', text: 't' });
    const [url, opts] = lastCall();
    expect(url).toContain('/api/comment-threads?page=Foo');
    expect(opts.method).toBe('POST');
    expect(JSON.parse(opts.body)).toEqual({ exact: 'e', prefix: 'p', suffix: 's', text: 't' });
  });

  it('addCommentReply POSTs the reply text to the thread comments', async () => {
    await api.addCommentReply('tid', 'hi');
    const [url, opts] = lastCall();
    expect(url).toContain('/api/comment-threads/tid/comments');
    expect(opts.method).toBe('POST');
    expect(JSON.parse(opts.body)).toEqual({ text: 'hi' });
  });

  it('editComment PATCHes the specific comment', async () => {
    await api.editComment('tid', 'cid', 'x');
    const [url, opts] = lastCall();
    expect(url).toContain('/api/comment-threads/tid/comments/cid');
    expect(opts.method).toBe('PATCH');
    expect(JSON.parse(opts.body)).toEqual({ text: 'x' });
  });

  it('deleteComment DELETEs the specific comment', async () => {
    await api.deleteComment('tid', 'cid');
    const [url, opts] = lastCall();
    expect(url).toContain('/api/comment-threads/tid/comments/cid');
    expect(opts.method).toBe('DELETE');
  });

  it('resolveCommentThread POSTs to the resolve endpoint', async () => {
    await api.resolveCommentThread('tid');
    const [url, opts] = lastCall();
    expect(url).toContain('/api/comment-threads/tid/resolve');
    expect(opts.method).toBe('POST');
  });

  it('reopenCommentThread POSTs to the reopen endpoint', async () => {
    await api.reopenCommentThread('tid');
    const [url, opts] = lastCall();
    expect(url).toContain('/api/comment-threads/tid/reopen');
    expect(opts.method).toBe('POST');
  });

  it('deleteCommentThread DELETEs the thread root path', async () => {
    await api.deleteCommentThread('tid');
    const [url, opts] = lastCall();
    expect(url).toMatch(/\/api\/comment-threads\/tid$/);
    expect(opts.method).toBe('DELETE');
  });
});

describe('mentions methods', () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue(mockFetchResponse({ status: 200, body: {} }));
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  function lastCall() {
    return global.fetch.mock.calls[global.fetch.mock.calls.length - 1];
  }

  it('listMentionableUsers GETs the autocomplete endpoint with q + limit', async () => {
    await api.listMentionableUsers('al', 5);
    const [url, opts] = lastCall();
    expect(url).toContain('/api/users/mentionable?q=al&limit=5');
    expect(opts.method ?? 'GET').toBe('GET');
  });

  it('listMyMentions GETs with status + limit + optional before', async () => {
    await api.listMyMentions({ status: 'unread', limit: 10 });
    expect(lastCall()[0]).toContain('/api/me/mentions?status=unread&limit=10');
    expect(lastCall()[0]).not.toContain('before=');
    await api.listMyMentions({ status: 'all', limit: 20, before: '2026-01-01T00:00:00Z' });
    expect(lastCall()[0]).toContain('before=2026-01-01T00%3A00%3A00Z');
  });

  it('getMyMentionsUnreadCount GETs unread-count', async () => {
    await api.getMyMentionsUnreadCount();
    expect(lastCall()[0]).toMatch(/\/api\/me\/mentions\/unread-count$/);
  });

  it('markMentionRead POSTs the /{id}/read path', async () => {
    await api.markMentionRead('11111111-2222-3333-4444-555555555555');
    const [url, opts] = lastCall();
    expect(url).toMatch(/\/api\/me\/mentions\/11111111-2222-3333-4444-555555555555\/read$/);
    expect(opts.method).toBe('POST');
  });

  it('markAllMentionsRead POSTs to /mark-all-read', async () => {
    await api.markAllMentionsRead();
    const [url, opts] = lastCall();
    expect(url).toMatch(/\/api\/me\/mentions\/mark-all-read$/);
    expect(opts.method).toBe('POST');
  });
});

describe('admin.pageOwnership methods', () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue(mockFetchResponse({ status: 200, body: {} }));
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  function lastCall() {
    return global.fetch.mock.calls[global.fetch.mock.calls.length - 1];
  }

  it('listOrphaned GETs with filter=orphaned and pagination', async () => {
    await api.admin.pageOwnership.listOrphaned({ limit: 25, offset: 50 });
    expect(lastCall()[0]).toContain('/admin/page-ownership?filter=orphaned&limit=25&offset=50');
  });

  it('listByOwner URL-encodes the owner login', async () => {
    await api.admin.pageOwnership.listByOwner('alice user');
    expect(lastCall()[0]).toContain('owner=alice%20user');
  });

  it('reassign POSTs the pages array + newOwner', async () => {
    await api.admin.pageOwnership.reassign(['cid-1', 'cid-2'], 'bob');
    const [url, opts] = lastCall();
    expect(url).toMatch(/\/admin\/page-ownership\/reassign$/);
    expect(opts.method).toBe('POST');
    expect(JSON.parse(opts.body)).toEqual({ pages: ['cid-1', 'cid-2'], newOwner: 'bob' });
  });

  it('reassignByUser POSTs the fromOwner + toOwner', async () => {
    await api.admin.pageOwnership.reassignByUser('alice', 'bob');
    const [url, opts] = lastCall();
    expect(url).toMatch(/\/admin\/page-ownership\/reassign-by-user$/);
    expect(opts.method).toBe('POST');
    expect(JSON.parse(opts.body)).toEqual({ fromOwner: 'alice', toOwner: 'bob' });
  });
});

describe('api.knowledge.listProposalsFiltered', () => {
  beforeEach(() => {
    global.fetch = vi.fn();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('forwards offset to the server so pagination advances past page 0', async () => {
    global.fetch.mockResolvedValue(
      mockFetchResponse({ status: 200, body: { proposals: [], total_count: 0 } }),
    );
    await api.knowledge.listProposalsFiltered({
      status: 'pending',
      limit: 25,
      offset: 25,
    });
    const url = global.fetch.mock.calls[0][0];
    expect(url).toContain('offset=25');
  });
});
