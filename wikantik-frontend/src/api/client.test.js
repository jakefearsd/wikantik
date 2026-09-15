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

// ── typeahead write-amplification fix ──────────────────────────────────────
// The search-as-you-type box hits the same /api/search endpoint as a submitted
// search; only submitted queries may be logged server-side. The `typeahead`
// query param is how the client marks the difference — assert the actual URL
// values, not just that fetch was called.
describe('api.search typeahead marking', () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue(mockFetchResponse({ status: 200, body: { results: [] } }));
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  function lastCall() {
    return global.fetch.mock.calls[global.fetch.mock.calls.length - 1];
  }

  it('a plain call (submitted search) omits the typeahead param', async () => {
    await api.search('deploy', 50);
    const [url] = lastCall();
    expect(url).toBe('/api/search?q=deploy&limit=50');
    expect(url).not.toContain('typeahead');
  });

  it('typeahead: true appends &typeahead=true to the request URL', async () => {
    await api.search('deploy', 20, { typeahead: true });
    const [url] = lastCall();
    expect(url).toBe('/api/search?q=deploy&limit=20&typeahead=true');
  });

  it('typeahead: false behaves exactly like the default (omitted)', async () => {
    await api.search('deploy', 20, { typeahead: false });
    const [url] = lastCall();
    expect(url).toBe('/api/search?q=deploy&limit=20');
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

// ── page-knowledge client contract — prevents camelCase/snake_case drift ──────
// This is the regression guard that would have caught C1: the backend was
// reading snake_case keys while the client sent camelCase. If either side
// drifts, one of these tests will fail with a shape mismatch.
describe('page-knowledge client contract', () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue(
      mockFetchResponse({ status: 200, body: { ok: true, id: 'node-uuid', node: {} } }),
    );
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  function lastCall() {
    return global.fetch.mock.calls[global.fetch.mock.calls.length - 1];
  }

  it('upsertEntity sends camelCase nodeType key to /api/page-knowledge/{name}/entities', async () => {
    await api.upsertEntity('MyPage', { name: 'X', nodeType: 'concept' });
    const [url, opts] = lastCall();
    expect(url).toContain('/api/page-knowledge/MyPage/entities');
    expect(opts.method).toBe('POST');
    const body = JSON.parse(opts.body);
    expect(body).toHaveProperty('nodeType', 'concept');
    // Must NOT contain the legacy snake_case key
    expect(body).not.toHaveProperty('node_type');
  });

  it('upsertEdge sends camelCase sourceId/targetId/relationshipType to /api/page-knowledge/{name}/edges', async () => {
    global.fetch.mockResolvedValue(
      mockFetchResponse({ status: 200, body: { ok: true, id: 'edge-uuid' } }),
    );
    await api.upsertEdge('MyPage', { sourceId: 'a-uuid', targetId: 'b-uuid', relationshipType: 'related_to' });
    const [url, opts] = lastCall();
    expect(url).toContain('/api/page-knowledge/MyPage/edges');
    expect(opts.method).toBe('POST');
    const body = JSON.parse(opts.body);
    expect(body).toHaveProperty('sourceId', 'a-uuid');
    expect(body).toHaveProperty('targetId', 'b-uuid');
    expect(body).toHaveProperty('relationshipType', 'related_to');
    // Must NOT contain the legacy snake_case keys
    expect(body).not.toHaveProperty('source_id');
    expect(body).not.toHaveProperty('target_id');
    expect(body).not.toHaveProperty('relationship_type');
  });
});

// ── request() core behaviour: version mismatch, auth-required, error shapes ──
describe('request() core behaviour', () => {
  beforeEach(() => {
    global.fetch = vi.fn();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('dispatches wikantik:version-mismatch exactly once when X-Build-Version differs', async () => {
    const listener = vi.fn();
    window.addEventListener('wikantik:version-mismatch', listener);
    try {
      const headers = new Map([['X-Build-Version', 'some-other-version-xyz']]);
      global.fetch.mockResolvedValue({
        ok: true,
        status: 200,
        statusText: 'OK',
        headers: { get: (k) => headers.get(k) ?? null },
        json: async () => ({}),
        text: async () => '{}',
      });
      await api.getCapabilities();
      expect(listener).toHaveBeenCalledTimes(1);
      expect(listener.mock.calls[0][0].detail).toEqual({ serverVersion: 'some-other-version-xyz' });

      // Second mismatched response must NOT re-dispatch — the module-level
      // flag latches after the first signal.
      await api.getCapabilities();
      expect(listener).toHaveBeenCalledTimes(1);
    } finally {
      window.removeEventListener('wikantik:version-mismatch', listener);
    }
  });

  it('dispatches wikantik:auth-required on a 401 for a non-auth-probe path', async () => {
    const listener = vi.fn();
    window.addEventListener('wikantik:auth-required', listener);
    try {
      global.fetch.mockResolvedValue(mockFetchResponse({ status: 401, body: { message: 'expired' } }));
      await expect(api.getMyPages()).rejects.toMatchObject({ status: 401 });
      expect(listener).toHaveBeenCalledTimes(1);
      expect(listener.mock.calls[0][0].detail).toMatchObject({ status: 401, path: '/api/me/pages?limit=15' });
    } finally {
      window.removeEventListener('wikantik:auth-required', listener);
    }
  });

  it('dispatches wikantik:auth-required on a 403 for a non-auth-probe path', async () => {
    const listener = vi.fn();
    window.addEventListener('wikantik:auth-required', listener);
    try {
      global.fetch.mockResolvedValue(mockFetchResponse({ status: 403, body: { message: 'forbidden' } }));
      await expect(api.getMyPages()).rejects.toMatchObject({ status: 403 });
      expect(listener).toHaveBeenCalledTimes(1);
    } finally {
      window.removeEventListener('wikantik:auth-required', listener);
    }
  });

  it('does NOT dispatch wikantik:auth-required for the auth probe itself (/api/auth/user)', async () => {
    const listener = vi.fn();
    window.addEventListener('wikantik:auth-required', listener);
    try {
      global.fetch.mockResolvedValue(mockFetchResponse({ status: 401, body: { message: 'not logged in' } }));
      await expect(api.getUser()).rejects.toMatchObject({ status: 401 });
      expect(listener).not.toHaveBeenCalled();
    } finally {
      window.removeEventListener('wikantik:auth-required', listener);
    }
  });

  it('falls back to resp.statusText when the error body is not valid JSON', async () => {
    global.fetch.mockResolvedValue({
      ok: false,
      status: 500,
      statusText: 'Internal Server Error',
      headers: { get: () => null },
      json: async () => { throw new SyntaxError('not json'); },
      text: async () => '',
    });
    await expect(api.getCapabilities()).rejects.toMatchObject({
      status: 500,
      message: 'Internal Server Error',
    });
  });

  it('propagates a network failure (fetch rejects) without swallowing it', async () => {
    global.fetch.mockRejectedValue(new TypeError('Failed to fetch'));
    await expect(api.getCapabilities()).rejects.toThrow('Failed to fetch');
  });

  it('forwards an AbortSignal through to fetch', async () => {
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 200, body: {} }));
    const controller = new AbortController();
    await api.getPage('Foo', { signal: controller.signal });
    const [, opts] = global.fetch.mock.calls[0];
    expect(opts.signal).toBe(controller.signal);
  });

  it('sends Accept + Content-Type headers and same-origin credentials by default', async () => {
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 200, body: {} }));
    await api.getCapabilities();
    const [, opts] = global.fetch.mock.calls[0];
    expect(opts.credentials).toBe('same-origin');
    expect(opts.headers).toMatchObject({ Accept: 'application/json', 'Content-Type': 'application/json' });
  });

  it('returns null for a 200 response with Content-Length: 0 (bodyless success)', async () => {
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 200, contentLength: 0 }));
    const result = await api.getCapabilities();
    expect(result).toBeNull();
  });

  it('returns null when the response body text is empty but not 204/Content-Length:0', async () => {
    global.fetch.mockResolvedValue({
      ok: true,
      status: 200,
      statusText: 'OK',
      headers: { get: () => null },
      json: async () => ({}),
      text: async () => '',
    });
    const result = await api.getCapabilities();
    expect(result).toBeNull();
  });

  it('an unmatched extraErrorCodes status falls through to the generic error shape', async () => {
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 418, body: { message: 'teapot' } }));
    await expect(api.admin.rebuildIndexes()).rejects.toMatchObject({ status: 418, message: 'teapot' });
    await expect(api.admin.rebuildIndexes()).rejects.not.toHaveProperty('code');
  });

  it('extraErrorCodes uses defaultMessage when the body carries no message/error', async () => {
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 409, body: {} }));
    await expect(api.admin.rebuildIndexes()).rejects.toMatchObject({
      status: 409,
      code: 'rebuild_in_flight',
      message: 'A rebuild is already in flight',
    });
  });
});

// ── Pages ──────────────────────────────────────────────────────────────────
describe('page CRUD methods', () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue(mockFetchResponse({ status: 200, body: {} }));
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });
  function lastCall() {
    return global.fetch.mock.calls[global.fetch.mock.calls.length - 1];
  }

  it('getPage plain GET with no query params', async () => {
    await api.getPage('My Page');
    expect(lastCall()[0]).toBe('/api/pages/My%20Page');
    expect(lastCall()[1].method ?? 'GET').toBe('GET');
  });

  it('getPage adds version + render query params when given', async () => {
    await api.getPage('Foo', { version: 3, render: true });
    expect(lastCall()[0]).toBe('/api/pages/Foo?version=3&render=true');
  });

  it('savePage PUTs content/metadata/etc as JSON', async () => {
    await api.savePage('Foo', {
      content: 'hi', metadata: { a: 1 }, changeNote: 'note', author: 'bob',
      expectedVersion: 2, expectedContentHash: 'h', markupSyntax: 'markdown',
    });
    const [url, opts] = lastCall();
    expect(url).toBe('/api/pages/Foo');
    expect(opts.method).toBe('PUT');
    expect(JSON.parse(opts.body)).toEqual({
      content: 'hi', metadata: { a: 1 }, changeNote: 'note', author: 'bob',
      expectedVersion: 2, expectedContentHash: 'h', markupSyntax: 'markdown',
    });
  });

  it('patchMetadata PATCHes metadata with default merge action', async () => {
    await api.patchMetadata('Foo', { tags: ['x'] });
    const [url, opts] = lastCall();
    expect(url).toBe('/api/pages/Foo');
    expect(opts.method).toBe('PATCH');
    expect(JSON.parse(opts.body)).toEqual({ metadata: { tags: ['x'] }, action: 'merge' });
  });

  it('patchMetadata honors an explicit action', async () => {
    await api.patchMetadata('Foo', { tags: ['x'] }, 'replace');
    const [, opts] = lastCall();
    expect(JSON.parse(opts.body).action).toBe('replace');
  });

  it('deletePage DELETEs the page path', async () => {
    await api.deletePage('Foo');
    const [url, opts] = lastCall();
    expect(url).toBe('/api/pages/Foo');
    expect(opts.method).toBe('DELETE');
  });

  it('renamePage POSTs newName to the rename endpoint', async () => {
    await api.renamePage('Foo', 'Bar');
    const [url, opts] = lastCall();
    expect(url).toBe('/api/pages/Foo/rename');
    expect(opts.method).toBe('POST');
    expect(JSON.parse(opts.body)).toEqual({ newName: 'Bar' });
  });

  it('listPages defaults limit/offset and omits prefix when absent', async () => {
    await api.listPages();
    expect(lastCall()[0]).toBe('/api/pages?limit=100&offset=0');
  });

  it('listPages includes prefix when given', async () => {
    await api.listPages({ prefix: 'Foo', limit: 10, offset: 5 });
    expect(lastCall()[0]).toBe('/api/pages?limit=10&offset=5&prefix=Foo');
  });

  it('getFrontmatterSchema GETs the schema endpoint', async () => {
    await api.getFrontmatterSchema();
    expect(lastCall()[0]).toBe('/api/frontmatter-schema');
  });

  it('validateFrontmatter POSTs frontmatter + metadata', async () => {
    await api.validateFrontmatter({ frontmatter: 'x: y', metadata: { a: 1 } });
    const [url, opts] = lastCall();
    expect(url).toBe('/api/frontmatter/validate');
    expect(opts.method).toBe('POST');
    expect(JSON.parse(opts.body)).toEqual({ frontmatter: 'x: y', metadata: { a: 1 } });
  });

  it('validateFrontmatter works with no arguments (default {})', async () => {
    await api.validateFrontmatter();
    const [, opts] = lastCall();
    expect(JSON.parse(opts.body)).toEqual({});
  });

  it('convertWikiToMarkdown POSTs content', async () => {
    await api.convertWikiToMarkdown('!!heading');
    const [url, opts] = lastCall();
    expect(url).toBe('/api/convert/wiki-to-markdown');
    expect(JSON.parse(opts.body)).toEqual({ content: '!!heading' });
  });

  it('getHistory / getDiff build the expected URLs', async () => {
    await api.getHistory('Foo');
    expect(lastCall()[0]).toBe('/api/history/Foo');
    await api.getDiff('Foo', 1, 2);
    expect(lastCall()[0]).toBe('/api/diff/Foo?from=1&to=2');
  });

  it('getBacklinks / getOutboundLinks build the expected URLs', async () => {
    await api.getBacklinks('Foo');
    expect(lastCall()[0]).toBe('/api/backlinks/Foo');
    await api.getOutboundLinks('Foo');
    expect(lastCall()[0]).toBe('/api/outbound-links/Foo');
  });

  it('getRecentChanges defaults to limit=50', async () => {
    await api.getRecentChanges();
    expect(lastCall()[0]).toBe('/api/recent-changes?limit=50');
  });

  it('getSimilarPages defaults to limit=5', async () => {
    await api.getSimilarPages('Foo');
    expect(lastCall()[0]).toBe('/api/pages/Foo/similar?limit=5');
  });
});

// ── Attachments (FormData / direct-fetch bypass of request()) ──────────────
describe('attachment methods', () => {
  beforeEach(() => {
    global.fetch = vi.fn();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('listAttachments GETs the page attachments list', async () => {
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 200, body: [] }));
    await api.listAttachments('Foo');
    expect(global.fetch.mock.calls[0][0]).toBe('/api/attachments/Foo');
  });

  it('uploadAttachment POSTs FormData with file + optional name', async () => {
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 200, body: { ok: true } }));
    const file = new File(['content'], 'a.txt', { type: 'text/plain' });
    const result = await api.uploadAttachment('Foo', file, 'renamed.txt');
    expect(result).toEqual({ ok: true });
    const [url, opts] = global.fetch.mock.calls[0];
    expect(url).toBe('/api/attachments/Foo');
    expect(opts.method).toBe('POST');
    expect(opts.body).toBeInstanceOf(FormData);
    expect(opts.body.get('file')).toBe(file);
    expect(opts.body.get('name')).toBe('renamed.txt');
  });

  it('uploadAttachment omits the name field when not given', async () => {
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 200, body: { ok: true } }));
    const file = new File(['x'], 'a.txt');
    await api.uploadAttachment('Foo', file);
    const opts = global.fetch.mock.calls[0][1];
    expect(opts.body.has('name')).toBe(false);
  });

  it('uploadAttachment throws with server message on failure', async () => {
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 500, body: { message: 'disk full' } }));
    const file = new File(['x'], 'a.txt');
    await expect(api.uploadAttachment('Foo', file)).rejects.toThrow('disk full');
  });

  it('uploadAttachment falls back to a generic message when error body is not JSON', async () => {
    global.fetch.mockResolvedValue({
      ok: false,
      status: 500,
      json: async () => { throw new SyntaxError('bad'); },
    });
    const file = new File(['x'], 'a.txt');
    await expect(api.uploadAttachment('Foo', file)).rejects.toThrow('Upload failed');
  });

  it('deleteAttachment DELETEs the page/filename path', async () => {
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 200, body: {} }));
    await api.deleteAttachment('Foo', 'a b.txt');
    const [url, opts] = global.fetch.mock.calls[0];
    expect(url).toBe('/api/attachments/Foo/a%20b.txt');
    expect(opts.method).toBe('DELETE');
  });

  it('ingestDocument POSTs FormData with the file', async () => {
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 200, body: { pageId: 'p1' } }));
    const file = new File(['x'], 'doc.pdf');
    const result = await api.ingestDocument(file);
    expect(result).toEqual({ pageId: 'p1' });
    const [url, opts] = global.fetch.mock.calls[0];
    expect(url).toBe('/api/ingest');
    expect(opts.method).toBe('POST');
    expect(opts.body.get('file')).toBe(file);
  });

  it('ingestDocument throws with server message on failure', async () => {
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 422, body: { message: 'bad file' } }));
    await expect(api.ingestDocument(new File(['x'], 'a.txt'))).rejects.toThrow('bad file');
  });

  it('ingestDocument falls back to a generic message when error body is not JSON', async () => {
    global.fetch.mockResolvedValue({
      ok: false,
      status: 500,
      json: async () => { throw new SyntaxError('bad'); },
    });
    await expect(api.ingestDocument(new File(['x'], 'a.txt'))).rejects.toThrow('Ingest failed');
  });

  it('renameAttachment PUTs newName as JSON to the old-name path', async () => {
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 200, body: { ok: true } }));
    const result = await api.renameAttachment('Foo', 'old.txt', 'new.txt');
    expect(result).toEqual({ ok: true });
    const [url, opts] = global.fetch.mock.calls[0];
    expect(url).toBe('/api/attachments/Foo/old.txt');
    expect(opts.method).toBe('PUT');
    expect(opts.headers).toEqual({ 'Content-Type': 'application/json' });
    expect(JSON.parse(opts.body)).toEqual({ newName: 'new.txt' });
  });

  it('renameAttachment throws with server message on failure', async () => {
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 409, body: { message: 'name taken' } }));
    await expect(api.renameAttachment('Foo', 'old.txt', 'new.txt')).rejects.toThrow('name taken');
  });

  it('renameAttachment falls back to a generic message when error body is not JSON', async () => {
    global.fetch.mockResolvedValue({
      ok: false,
      status: 500,
      json: async () => { throw new SyntaxError('bad'); },
    });
    await expect(api.renameAttachment('Foo', 'old.txt', 'new.txt')).rejects.toThrow('Rename failed');
  });
});

// ── Auth / Self ──────────────────────────────────────────────────────────
describe('auth + self methods', () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue(mockFetchResponse({ status: 200, body: {} }));
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });
  function lastCall() {
    return global.fetch.mock.calls[global.fetch.mock.calls.length - 1];
  }

  it('getCapabilities / getUser / getProfile are plain GETs', async () => {
    await api.getCapabilities();
    expect(lastCall()[0]).toBe('/api/capabilities');
    await api.getUser();
    expect(lastCall()[0]).toBe('/api/auth/user');
    await api.getProfile();
    expect(lastCall()[0]).toBe('/api/auth/profile');
  });

  it('login POSTs username + password', async () => {
    await api.login('bob', 'secret');
    const [url, opts] = lastCall();
    expect(url).toBe('/api/auth/login');
    expect(opts.method).toBe('POST');
    expect(JSON.parse(opts.body)).toEqual({ username: 'bob', password: 'secret' });
  });

  it('logout POSTs with no body', async () => {
    await api.logout();
    const [url, opts] = lastCall();
    expect(url).toBe('/api/auth/logout');
    expect(opts.method).toBe('POST');
  });

  it('updateProfile PUTs the given data', async () => {
    await api.updateProfile({ fullName: 'Bob' });
    const [url, opts] = lastCall();
    expect(url).toBe('/api/auth/profile');
    expect(opts.method).toBe('PUT');
    expect(JSON.parse(opts.body)).toEqual({ fullName: 'Bob' });
  });

  it('deleteAccount DELETEs with confirmLoginName', async () => {
    await api.deleteAccount('bob');
    const [url, opts] = lastCall();
    expect(url).toBe('/api/auth/profile');
    expect(opts.method).toBe('DELETE');
    expect(JSON.parse(opts.body)).toEqual({ confirmLoginName: 'bob' });
  });

  it('resetPassword POSTs the email', async () => {
    await api.resetPassword('bob@example.com');
    const [url, opts] = lastCall();
    expect(url).toBe('/api/auth/reset-password');
    expect(JSON.parse(opts.body)).toEqual({ email: 'bob@example.com' });
  });

  it('self.listApiKeys / createApiKey / rotateApiKey / revokeApiKey', async () => {
    await api.self.listApiKeys();
    expect(lastCall()[0]).toBe('/api/self/apikeys');

    await api.self.createApiKey({ name: 'k1' });
    let [url, opts] = lastCall();
    expect(url).toBe('/api/self/apikeys');
    expect(opts.method).toBe('POST');
    expect(JSON.parse(opts.body)).toEqual({ name: 'k1' });

    await api.self.rotateApiKey(7);
    [url, opts] = lastCall();
    expect(url).toBe('/api/self/apikeys/7/rotate');
    expect(opts.method).toBe('POST');

    await api.self.revokeApiKey(7);
    [url, opts] = lastCall();
    expect(url).toBe('/api/self/apikeys/7');
    expect(opts.method).toBe('DELETE');
  });
});

// ── Admin — users, content, groups, policy, apikeys ─────────────────────
describe('admin user/content/group/policy/apikey methods', () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue(mockFetchResponse({ status: 200, body: {} }));
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });
  function lastCall() {
    return global.fetch.mock.calls[global.fetch.mock.calls.length - 1];
  }

  it('user management CRUD builds expected requests', async () => {
    await api.admin.listUsers();
    expect(lastCall()[0]).toBe('/admin/users');

    await api.admin.getOverview();
    expect(lastCall()[0]).toBe('/admin/overview');

    await api.admin.getUser('bob');
    expect(lastCall()[0]).toBe('/admin/users/bob');

    await api.admin.createUser({ loginName: 'bob' });
    let [url, opts] = lastCall();
    expect(url).toBe('/admin/users');
    expect(opts.method).toBe('POST');

    await api.admin.updateUser('bob', { fullName: 'Bob' });
    [url, opts] = lastCall();
    expect(url).toBe('/admin/users/bob');
    expect(opts.method).toBe('PUT');

    await api.admin.deleteUser('bob');
    [url, opts] = lastCall();
    expect(url).toBe('/admin/users/bob');
    expect(opts.method).toBe('DELETE');

    await api.admin.lockUser('bob', '2027-01-01');
    [url, opts] = lastCall();
    expect(url).toBe('/admin/users/bob/lock');
    expect(JSON.parse(opts.body)).toEqual({ expiry: '2027-01-01' });

    await api.admin.unlockUser('bob');
    [url, opts] = lastCall();
    expect(url).toBe('/admin/users/bob/unlock');
    expect(opts.method).toBe('POST');
  });

  it('content management methods build expected requests', async () => {
    await api.admin.getContentStats();
    expect(lastCall()[0]).toBe('/admin/content/stats');

    await api.admin.getOrphanedPages();
    expect(lastCall()[0]).toBe('/admin/content/orphaned-pages');

    await api.admin.getBrokenLinks();
    expect(lastCall()[0]).toBe('/admin/content/broken-links');

    await api.admin.bulkDeletePages(['A', 'B']);
    let [url, opts] = lastCall();
    expect(url).toBe('/admin/content/bulk-delete');
    expect(JSON.parse(opts.body)).toEqual({ pages: ['A', 'B'] });

    await api.admin.purgeVersions('A', 5);
    [url, opts] = lastCall();
    expect(url).toBe('/admin/content/purge-versions');
    expect(JSON.parse(opts.body)).toEqual({ page: 'A', keepLatest: 5 });

    await api.admin.reindex();
    [url, opts] = lastCall();
    expect(url).toBe('/admin/content/reindex');
    expect(opts.method).toBe('POST');

    await api.admin.flushCache('render');
    [url, opts] = lastCall();
    expect(url).toBe('/admin/content/cache/flush');
    expect(JSON.parse(opts.body)).toEqual({ cache: 'render' });

    await api.admin.flushCache();
    [, opts] = lastCall();
    expect(JSON.parse(opts.body)).toEqual({ cache: null });
  });

  it('reindexEmbeddings distinguishes 409/503 extraErrorCodes', async () => {
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 409, body: {} }));
    await expect(api.admin.reindexEmbeddings()).rejects.toMatchObject({ code: 'embedding_bootstrap_running' });
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 503, body: {} }));
    await expect(api.admin.reindexEmbeddings()).rejects.toMatchObject({ code: 'hybrid_disabled' });
  });

  it('group management CRUD builds expected requests', async () => {
    await api.admin.listGroups();
    expect(lastCall()[0]).toBe('/admin/groups');

    await api.admin.getGroup('Editors');
    expect(lastCall()[0]).toBe('/admin/groups/Editors');

    await api.admin.updateGroup('Editors', { members: ['bob'] });
    let [url, opts] = lastCall();
    expect(url).toBe('/admin/groups/Editors');
    expect(opts.method).toBe('PUT');

    await api.admin.deleteGroup('Editors');
    [, opts] = lastCall();
    expect(opts.method).toBe('DELETE');
  });

  it('policy grant CRUD builds expected requests', async () => {
    await api.admin.listPolicyGrants();
    expect(lastCall()[0]).toBe('/admin/policy');

    await api.admin.createPolicyGrant({ role: 'Anonymous', permission: 'view' });
    let [url, opts] = lastCall();
    expect(url).toBe('/admin/policy');
    expect(opts.method).toBe('POST');

    await api.admin.updatePolicyGrant(3, { permission: 'edit' });
    [url, opts] = lastCall();
    expect(url).toBe('/admin/policy/3');
    expect(opts.method).toBe('PUT');

    await api.admin.deletePolicyGrant(3);
    [url, opts] = lastCall();
    expect(url).toBe('/admin/policy/3');
    expect(opts.method).toBe('DELETE');
  });

  it('API key admin CRUD + bulk actions build expected requests', async () => {
    await api.admin.listApiKeys();
    expect(lastCall()[0]).toBe('/admin/apikeys');

    await api.admin.createApiKey({ name: 'k' });
    let [url, opts] = lastCall();
    expect(url).toBe('/admin/apikeys');
    expect(opts.method).toBe('POST');

    await api.admin.revokeApiKey(9);
    [url, opts] = lastCall();
    expect(url).toBe('/admin/apikeys/9');
    expect(opts.method).toBe('DELETE');

    await api.admin.bulkApiKeyAction('revoke', [1, 2]);
    [url, opts] = lastCall();
    expect(url).toBe('/admin/apikeys/bulk-action');
    expect(JSON.parse(opts.body)).toEqual({ action: 'revoke', ids: [1, 2] });

    await api.admin.bulkUserAction('lock', ['bob'], { expiry: '2027-01-01' });
    [url, opts] = lastCall();
    expect(url).toBe('/admin/users/bulk-action');
    expect(JSON.parse(opts.body)).toEqual({ action: 'lock', ids: ['bob'], expiry: '2027-01-01' });
  });
});

// ── Admin — retrieval quality, drift, insights, audit, page ownership ─────
describe('admin retrieval/drift/insights/audit/ownership methods', () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue(mockFetchResponse({ status: 200, body: {} }));
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });
  function lastCall() {
    return global.fetch.mock.calls[global.fetch.mock.calls.length - 1];
  }

  it('listRetrievalRuns omits absent filters and includes given ones', async () => {
    await api.admin.listRetrievalRuns();
    expect(lastCall()[0]).toBe('/admin/retrieval-quality?limit=30');

    await api.admin.listRetrievalRuns({ querySetId: 'qs1', mode: 'hybrid', limit: 10 });
    expect(lastCall()[0]).toBe('/admin/retrieval-quality?query_set_id=qs1&mode=hybrid&limit=10');
  });

  it('runRetrievalNow POSTs query_set_id + mode', async () => {
    await api.admin.runRetrievalNow('qs1', 'hybrid');
    const [url, opts] = lastCall();
    expect(url).toBe('/admin/retrieval-quality/run');
    expect(JSON.parse(opts.body)).toEqual({ query_set_id: 'qs1', mode: 'hybrid' });
  });

  it('drift dashboard methods build expected requests', async () => {
    await api.admin.getDriftSummary();
    expect(lastCall()[0]).toBe('/admin/drift/summary');

    await api.admin.getDriftTrend();
    expect(lastCall()[0]).toBe('/admin/drift/trend?days=30');

    await api.admin.getDriftTrend(7);
    expect(lastCall()[0]).toBe('/admin/drift/trend?days=7');

    await api.admin.getDriftPages('structural', 'MULTI_CLUSTER_HUB');
    expect(lastCall()[0]).toBe('/admin/drift/pages?family=structural&code=MULTI_CLUSTER_HUB');

    await api.admin.runDriftSweep();
    const [url, opts] = lastCall();
    expect(url).toBe('/admin/drift/sweep');
    expect(opts.method).toBe('POST');
    expect(opts.body).toBe('{}');

    await api.admin.getDriftStatus();
    expect(lastCall()[0]).toBe('/admin/drift/status');
  });

  it('getInsightsAcquisition omits site when absent, includes when given', async () => {
    await api.admin.getInsightsAcquisition();
    expect(lastCall()[0]).toBe('/admin/insights/acquisition?days=90');

    await api.admin.getInsightsAcquisition('example.com', 30);
    expect(lastCall()[0]).toBe('/admin/insights/acquisition?site=example.com&days=30');
  });

  it('getInsightsBacklog builds the full filter set when all options given', async () => {
    await api.admin.getInsightsBacklog();
    expect(lastCall()[0]).toBe('/admin/insights/backlog');

    await api.admin.getInsightsBacklog({
      site: 'example.com', type: 'zero-result', limit: 20, minPriority: 5, includeSnoozed: true,
    });
    const url = lastCall()[0];
    expect(url).toContain('site=example.com');
    expect(url).toContain('type=zero-result');
    expect(url).toContain('limit=20');
    expect(url).toContain('minPriority=5');
    expect(url).toContain('includeSnoozed=true');
  });

  it('listAuditLog omits absent filters and converts from/to to ISO', async () => {
    await api.admin.listAuditLog();
    expect(lastCall()[0]).toBe('/admin/audit');

    await api.admin.listAuditLog({
      actor: 'bob', category: 'security', eventType: 'login', target: 'X',
      outcome: 'success', from: '2026-01-01T00:00:00Z', to: '2026-01-02T00:00:00Z',
      beforeSeq: 100, limit: 25,
    });
    const url = lastCall()[0];
    expect(url).toContain('actor=bob');
    expect(url).toContain('category=security');
    expect(url).toContain('eventType=login');
    expect(url).toContain('target=X');
    expect(url).toContain('outcome=success');
    expect(url).toContain('from=2026-01-01T00%3A00%3A00.000Z');
    expect(url).toContain('to=2026-01-02T00%3A00%3A00.000Z');
    expect(url).toContain('beforeSeq=100');
    expect(url).toContain('limit=25');
  });

  it('verifyAuditChain GETs the verify endpoint', async () => {
    await api.admin.verifyAuditChain();
    expect(lastCall()[0]).toBe('/admin/audit/verify');
  });

  it('pageOwnership methods build expected requests', async () => {
    await api.admin.pageOwnership.listOrphaned();
    expect(lastCall()[0]).toBe('/admin/page-ownership?filter=orphaned&limit=50&offset=0');

    await api.admin.pageOwnership.listByOwner('bob', { limit: 5, offset: 10 });
    expect(lastCall()[0]).toBe('/admin/page-ownership?filter=by-owner&owner=bob&limit=5&offset=10');
  });
});

// ── Admin — KG policy ────────────────────────────────────────────────────
describe('admin.kgPolicy methods', () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue(mockFetchResponse({ status: 200, body: {} }));
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });
  function lastCall() {
    return global.fetch.mock.calls[global.fetch.mock.calls.length - 1];
  }

  it('cluster CRUD + review + bootstrap build expected requests', async () => {
    await api.admin.kgPolicy.listClusters();
    expect(lastCall()[0]).toBe('/admin/kg-policy/clusters');

    await api.admin.kgPolicy.getCluster('dev/backend');
    expect(lastCall()[0]).toBe('/admin/kg-policy/clusters/dev%2Fbackend');

    await api.admin.kgPolicy.setCluster('dev', { action: 'include' });
    let [url, opts] = lastCall();
    expect(url).toBe('/admin/kg-policy/clusters/dev');
    expect(opts.method).toBe('PUT');

    await api.admin.kgPolicy.clearCluster('dev');
    [, opts] = lastCall();
    expect(opts.method).toBe('DELETE');

    await api.admin.kgPolicy.markReviewed('dev');
    [url, opts] = lastCall();
    expect(url).toBe('/admin/kg-policy/clusters/dev/review');
    expect(opts.method).toBe('POST');

    await api.admin.kgPolicy.bootstrap({ dryRun: true });
    [url, opts] = lastCall();
    expect(url).toBe('/admin/kg-policy/bootstrap');
    expect(JSON.parse(opts.body)).toEqual({ dryRun: true });
  });

  it('explain / pending / audit / reconciliation / estimate build expected requests', async () => {
    await api.admin.kgPolicy.explain('id1');
    expect(lastCall()[0]).toBe('/admin/kg-policy/explain/id1');

    await api.admin.kgPolicy.pending();
    expect(lastCall()[0]).toBe('/admin/kg-policy/pending');

    await api.admin.kgPolicy.audit();
    expect(lastCall()[0]).toBe('/admin/kg-policy/audit?limit=100');

    await api.admin.kgPolicy.audit({ cluster: 'dev', limit: 10 });
    expect(lastCall()[0]).toBe('/admin/kg-policy/audit?limit=10&cluster=dev');

    await api.admin.kgPolicy.reconciliation();
    expect(lastCall()[0]).toBe('/admin/kg-policy/reconciliation');

    await api.admin.kgPolicy.estimate('dev', 'include');
    expect(lastCall()[0]).toBe('/admin/kg-policy/estimate?cluster=dev&action=include');
  });
});

// ── Page Graph + Knowledge Graph (admin) namespaces ─────────────────────
describe('pageGraph + knowledge namespaces', () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue(mockFetchResponse({ status: 200, body: {} }));
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });
  function lastCall() {
    return global.fetch.mock.calls[global.fetch.mock.calls.length - 1];
  }

  it('pageGraph.getSnapshot GETs the snapshot endpoint', async () => {
    await api.pageGraph.getSnapshot();
    expect(lastCall()[0]).toBe('/api/page-graph/snapshot');
  });

  it('knowledge.getGraphSnapshot omits min_tier when absent, includes when given', async () => {
    await api.knowledge.getGraphSnapshot();
    expect(lastCall()[0]).toBe('/api/knowledge/graph');
    await api.knowledge.getGraphSnapshot({ minTier: 'human' });
    expect(lastCall()[0]).toBe('/api/knowledge/graph?min_tier=human');
  });

  it('knowledge node/edge query + CRUD methods build expected requests', async () => {
    await api.knowledge.getSchema();
    expect(lastCall()[0]).toBe('/admin/knowledge-graph/schema');

    await api.knowledge.queryNodes({ node_type: 'person', name: 'Bob', status: 'active' });
    expect(lastCall()[0]).toBe('/admin/knowledge-graph/nodes?limit=50&offset=0&node_type=person&name=Bob&status=active');

    await api.knowledge.getNode('Bob');
    expect(lastCall()[0]).toBe('/admin/knowledge-graph/nodes/Bob');

    await api.knowledge.getNodeById('uuid-1');
    expect(lastCall()[0]).toBe('/admin/knowledge-graph/nodes/by-id/uuid-1');

    await api.knowledge.getNodeMentions('uuid-1');
    expect(lastCall()[0]).toBe('/admin/knowledge-graph/nodes/by-id/uuid-1/mentions?limit=3');

    await api.knowledge.getEdges('uuid-1');
    expect(lastCall()[0]).toBe('/admin/knowledge-graph/edges/uuid-1?direction=both');

    await api.knowledge.queryEdges({ relationship_type: 'related_to', search: 'x', endpoint_kind: 'entity' });
    expect(lastCall()[0]).toBe(
      '/admin/knowledge-graph/edges?limit=50&offset=0&relationship_type=related_to&search=x&endpoint_kind=entity',
    );

    await api.knowledge.listProposals();
    expect(lastCall()[0]).toBe('/admin/knowledge-graph/proposals?status=pending&limit=50');

    await api.knowledge.judgeProposal(1);
    let [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/proposals/1/judge');
    expect(opts.method).toBe('POST');

    await api.knowledge.runJudge();
    expect(lastCall()[0]).toBe('/admin/knowledge-graph/judge/run');

    await api.knowledge.judgeStatus();
    expect(lastCall()[0]).toBe('/admin/knowledge-graph/judge/status');

    await api.knowledge.listProposalReviews(1);
    expect(lastCall()[0]).toBe('/admin/knowledge-graph/proposals/1/reviews');

    await api.knowledge.approveProposal(1);
    [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/proposals/1/approve');
    expect(opts.method).toBe('POST');

    await api.knowledge.rejectProposal(1, 'bad');
    [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/proposals/1/reject');
    expect(JSON.parse(opts.body)).toEqual({ reason: 'bad' });

    await api.knowledge.bulkProposalAction('approve', [1, 2], { note: 'batch' });
    [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/proposals/bulk-action');
    expect(JSON.parse(opts.body)).toEqual({ action: 'approve', ids: [1, 2], note: 'batch' });

    await api.knowledge.upsertNode({ name: 'Bob', nodeType: 'person' });
    [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/nodes');
    expect(opts.method).toBe('POST');

    await api.knowledge.deleteNode(1);
    [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/nodes/1');
    expect(opts.method).toBe('DELETE');

    await api.knowledge.mergeNodes('a', 'b');
    [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/nodes/merge');
    expect(JSON.parse(opts.body)).toEqual({ sourceId: 'a', targetId: 'b' });

    await api.knowledge.upsertEdge({ sourceId: 'a', targetId: 'b', relationshipType: 'r' });
    [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/edges');
    expect(opts.method).toBe('POST');

    await api.knowledge.deleteEdge(1);
    [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/edges/1');
    expect(opts.method).toBe('DELETE');

    await api.knowledge.deleteAndRejectEdge(1, 'bad edge');
    [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/edges/1/delete-and-reject');
    expect(JSON.parse(opts.body)).toEqual({ reason: 'bad edge' });

    await api.knowledge.confirmEdge(1);
    [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/edges/1/confirm');
    expect(opts.method).toBe('POST');

    await api.knowledge.bulkDeleteEdges({ relationship_type: 'r', search: 'x', expected_count: 3 });
    [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/edges/bulk-delete');
    expect(JSON.parse(opts.body)).toEqual({ relationship_type: 'r', search: 'x', expected_count: 3 });

    await api.knowledge.getEdgeAudit(1);
    expect(lastCall()[0]).toBe('/admin/knowledge-graph/edges/1/audit?limit=20');

    await api.knowledge.clearAll();
    [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/clear-all');
    expect(opts.method).toBe('POST');
  });

  it('knowledge embedding + frontmatter-backfill methods build expected requests', async () => {
    await api.knowledge.getEmbeddingStatus();
    expect(lastCall()[0]).toBe('/admin/knowledge-graph/embeddings/status');

    await api.knowledge.getSimilarNodes('Bob');
    expect(lastCall()[0]).toBe('/admin/knowledge-graph/nodes/Bob/similar?limit=10');

    await api.knowledge.getPagesWithoutFrontmatter();
    expect(lastCall()[0]).toBe('/admin/knowledge-graph/pages-without-frontmatter?limit=100&offset=0');

    await api.knowledge.backfillFrontmatter();
    let [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/backfill-frontmatter');
    expect(opts.method).toBe('POST');

    await api.knowledge.getBackfillStatus();
    [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/backfill-frontmatter');
    expect(opts.method ?? 'GET').toBe('GET');

    await api.knowledge.syncHubMemberships();
    [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/sync-hub-memberships');
    expect(opts.method).toBe('POST');
  });

  it('hub proposal methods build expected requests', async () => {
    await api.knowledge.listHubProposals();
    expect(lastCall()[0]).toBe('/admin/knowledge-graph/hub-proposals?status=pending&limit=50&offset=0');

    await api.knowledge.listHubProposals('approved', 'MyHub', 10, 5);
    expect(lastCall()[0]).toBe('/admin/knowledge-graph/hub-proposals?status=approved&limit=10&offset=5&hub=MyHub');

    await api.knowledge.generateHubProposals();
    let [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/hub-proposals/generate');
    expect(opts.method).toBe('POST');

    await api.knowledge.approveHubProposal(1);
    [url] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/hub-proposals/1/approve');

    await api.knowledge.rejectHubProposal(1, 'bad');
    [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/hub-proposals/1/reject');
    expect(JSON.parse(opts.body)).toEqual({ reason: 'bad' });

    await api.knowledge.bulkApproveHubProposals([1, 2]);
    [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/hub-proposals/bulk-approve');
    expect(JSON.parse(opts.body)).toEqual({ ids: [1, 2] });

    await api.knowledge.bulkRejectHubProposals([1, 2], 'bad');
    [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/hub-proposals/bulk-reject');
    expect(JSON.parse(opts.body)).toEqual({ ids: [1, 2], reason: 'bad' });

    await api.knowledge.thresholdApproveHubProposals(0.8);
    [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/hub-proposals/threshold-approve');
    expect(JSON.parse(opts.body)).toEqual({ threshold: 0.8 });
  });

  it('hub discovery + existing hubs methods build expected requests', async () => {
    await api.knowledge.listHubDiscoveryProposals();
    expect(lastCall()[0]).toBe('/admin/knowledge-graph/hub-discovery/proposals?limit=50&offset=0');

    await api.knowledge.runHubDiscovery();
    let [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/hub-discovery/run');
    expect(opts.method).toBe('POST');

    await api.knowledge.acceptHubDiscoveryProposal(1, 'NewHub', ['A', 'B']);
    [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/hub-discovery/proposals/1/accept');
    expect(JSON.parse(opts.body)).toEqual({ name: 'NewHub', members: ['A', 'B'] });

    await api.knowledge.dismissHubDiscoveryProposal(1);
    [url] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/hub-discovery/proposals/1/dismiss');

    await api.knowledge.listDismissedHubDiscoveryProposals();
    expect(lastCall()[0]).toBe('/admin/knowledge-graph/hub-discovery/proposals/dismissed?limit=50&offset=0');

    await api.knowledge.deleteDismissedHubDiscoveryProposal(1);
    [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/hub-discovery/proposals/dismissed/1');
    expect(opts.method).toBe('DELETE');

    await api.knowledge.bulkDeleteDismissedHubDiscoveryProposals([1, 2]);
    [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/hub-discovery/proposals/dismissed/bulk-delete');
    expect(JSON.parse(opts.body)).toEqual({ ids: [1, 2] });

    await api.knowledge.listExistingHubs();
    expect(lastCall()[0]).toBe('/admin/knowledge-graph/hub-discovery/hubs');

    await api.knowledge.getHubDrilldown('MyHub');
    expect(lastCall()[0]).toBe('/admin/knowledge-graph/hub-discovery/hubs/MyHub');

    await api.knowledge.removeHubMember('MyHub', 'A');
    [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/hub-discovery/hubs/MyHub/remove-member');
    expect(JSON.parse(opts.body)).toEqual({ member: 'A' });
  });

  it('extraction methods build expected requests, force toggles the query param', async () => {
    await api.knowledge.getExtractionStatus();
    expect(lastCall()[0]).toBe('/admin/knowledge-graph/extract-mentions');

    await api.knowledge.getLlmActivity({ subsystem: 'kg', status: 'running' });
    expect(lastCall()[0]).toBe('/admin/llm-activity?limit=200&subsystem=kg&status=running');

    await api.knowledge.startExtraction();
    let [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/extract-mentions');
    expect(opts.method).toBe('POST');

    await api.knowledge.startExtraction(true);
    [url] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/extract-mentions?force=true');

    await api.knowledge.cancelExtraction();
    [url, opts] = lastCall();
    expect(url).toBe('/admin/knowledge-graph/extract-mentions');
    expect(opts.method).toBe('DELETE');
  });
});

// ── Page-scoped Knowledge Graph curation (top-level, /api/page-knowledge) ──
describe('page-knowledge curation methods (remaining, not covered above)', () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue(mockFetchResponse({ status: 200, body: {} }));
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });
  function lastCall() {
    return global.fetch.mock.calls[global.fetch.mock.calls.length - 1];
  }

  it('getPageKnowledge GETs the page slice', async () => {
    await api.getPageKnowledge('Foo');
    expect(lastCall()[0]).toBe('/api/page-knowledge/Foo');
  });

  it('confirmEntity / deleteEntity build expected requests', async () => {
    await api.confirmEntity('Foo', 'e1');
    let [url, opts] = lastCall();
    expect(url).toBe('/api/page-knowledge/Foo/entities/e1/confirm');
    expect(opts.method).toBe('POST');

    await api.deleteEntity('Foo', 'e1');
    [url, opts] = lastCall();
    expect(url).toBe('/api/page-knowledge/Foo/entities/e1');
    expect(opts.method).toBe('DELETE');
  });

  it('confirmEdge / deleteEdge / rejectEdge build expected requests', async () => {
    await api.confirmEdge('Foo', 'edge1');
    let [url, opts] = lastCall();
    expect(url).toBe('/api/page-knowledge/Foo/edges/edge1/confirm');
    expect(opts.method).toBe('POST');

    await api.deleteEdge('Foo', 'edge1');
    [url, opts] = lastCall();
    expect(url).toBe('/api/page-knowledge/Foo/edges/edge1');
    expect(opts.method).toBe('DELETE');

    await api.rejectEdge('Foo', 'edge1', 'not related');
    [url, opts] = lastCall();
    expect(url).toBe('/api/page-knowledge/Foo/edges/edge1/reject');
    expect(JSON.parse(opts.body)).toEqual({ reason: 'not related' });
  });
});

// ── Connectors ─────────────────────────────────────────────────────────
describe('api.connectors methods', () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue(mockFetchResponse({ status: 200, body: {} }));
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });
  function lastCall() {
    return global.fetch.mock.calls[global.fetch.mock.calls.length - 1];
  }

  it('list / get / sync / runs / pages / importFromProperties / testSaved build expected requests', async () => {
    await api.connectors.list();
    expect(lastCall()[0]).toBe('/admin/connectors');

    await api.connectors.get('c1');
    expect(lastCall()[0]).toBe('/admin/connectors/c1');

    await api.connectors.sync('c1');
    let [url, opts] = lastCall();
    expect(url).toBe('/admin/connectors/c1/sync');
    expect(opts.method).toBe('POST');

    await api.connectors.runs('c1');
    expect(lastCall()[0]).toBe('/admin/connectors/c1/runs?limit=20');

    await api.connectors.pages('c1');
    expect(lastCall()[0]).toBe('/admin/connectors/c1/pages');

    await api.connectors.importFromProperties('c1');
    [url, opts] = lastCall();
    expect(url).toBe('/admin/connectors/c1/import');
    expect(opts.method).toBe('POST');

    await api.connectors.testSaved('c1');
    [url, opts] = lastCall();
    expect(url).toBe('/admin/connectors/c1/test');
    expect(opts.method).toBe('POST');
  });

  it('create / update / test surface a distinguishable 422 validation error', async () => {
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 422, body: { message: 'bad config' } }));
    await expect(api.connectors.create({ type: 'webcrawler' })).rejects.toMatchObject({ status: 422 });
    await expect(api.connectors.update('c1', { type: 'webcrawler' })).rejects.toMatchObject({ status: 422 });
    await expect(api.connectors.test({ type: 'webcrawler' })).rejects.toMatchObject({ status: 422 });
  });

  it('create / update POST the connector body', async () => {
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 200, body: { id: 'c1' } }));
    await api.connectors.create({ type: 'webcrawler', name: 'Crawl' });
    let [url, opts] = lastCall();
    expect(url).toBe('/admin/connectors');
    expect(opts.method).toBe('POST');
    expect(JSON.parse(opts.body)).toEqual({ type: 'webcrawler', name: 'Crawl' });

    await api.connectors.update('c1', { name: 'Renamed' });
    [url, opts] = lastCall();
    expect(url).toBe('/admin/connectors/c1');
    expect(opts.method).toBe('PUT');
  });

  it('remove DELETEs with deletePages coerced to boolean', async () => {
    await api.connectors.remove('c1');
    expect(lastCall()[0]).toBe('/admin/connectors/c1?deletePages=false');

    await api.connectors.remove('c1', true);
    let [url, opts] = lastCall();
    expect(url).toBe('/admin/connectors/c1?deletePages=true');
    expect(opts.method).toBe('DELETE');
  });

  it('credential methods build expected requests', async () => {
    await api.connectors.listCredentials('c1');
    expect(lastCall()[0]).toBe('/admin/connector-credentials/c1');

    await api.connectors.setCredential('c1', 'token', 'secret-value');
    const [url, opts] = lastCall();
    expect(url).toBe('/admin/connector-credentials/c1/token');
    expect(opts.method).toBe('POST');
    expect(opts.headers).toEqual({ 'Content-Type': 'text/plain' });
    expect(opts.body).toBe('secret-value');

    await api.connectors.deleteCredential('c1', 'token');
    const [url2, opts2] = lastCall();
    expect(url2).toBe('/admin/connector-credentials/c1/token');
    expect(opts2.method).toBe('DELETE');
  });
});
