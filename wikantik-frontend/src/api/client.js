// Context path prefix injected by SpaRoutingFilter at index.html serve time.
// Empty string for root-context deployments (production), e.g.
// "/wikantik-it-test-custom" for the selenide IT suite's cargo-launched WAR.
// Without this, absolute API paths like "/api/pages" miss the context and 404.
const BASE = (typeof window !== 'undefined' && window.__WIKANTIK_BASE__) || '';

/* global __BUILD_VERSION__ */
let versionMismatchSignaled = false;

async function request(path, options = {}) {
  const { signal, extraErrorCodes, ...rest } = options;
  const resp = await fetch(`${BASE}${path}`, {
    credentials: 'same-origin',
    headers: { 'Accept': 'application/json', 'Content-Type': 'application/json', ...rest.headers },
    signal,
    ...rest,
  });

  // Detect server redeployment via build version header
  if (!versionMismatchSignaled && typeof __BUILD_VERSION__ !== 'undefined') {
    const serverVersion = resp.headers.get('X-Build-Version');
    if (serverVersion && serverVersion !== __BUILD_VERSION__) {
      versionMismatchSignaled = true;
      window.dispatchEvent(new CustomEvent('wikantik:version-mismatch', { detail: { serverVersion } }));
    }
  }

  if (!resp.ok) {
    const body = await resp.json().catch(() => ({ message: resp.statusText }));
    // Authenticated session expired or revoked mid-flight. Tell the auth
    // context so it re-queries /api/auth/user — the SPA's RequireAuth gate
    // then redirects to login. Without this, the user clicks a button that
    // 403s and nothing visible happens. Skip for /api/auth/user itself —
    // that endpoint is the auth probe; re-querying it on its own failure
    // would loop.
    if ((resp.status === 401 || resp.status === 403)
        && typeof window !== 'undefined'
        && path !== '/api/auth/user') {
      window.dispatchEvent(new CustomEvent('wikantik:auth-required', {
        detail: { status: resp.status, path },
      }));
    }
    // Caller-supplied custom status → app-level code mapping. Lets callers
    // surface domain errors (rebuild_in_flight, hybrid_disabled, …) without
    // duplicating the entire fetch/error-envelope dance.
    const extra = Array.isArray(extraErrorCodes)
      ? extraErrorCodes.find((m) => m.status === resp.status)
      : null;
    if (extra) {
      throw Object.assign(
        new Error(body.message || body.error || extra.defaultMessage || resp.statusText),
        { status: resp.status, code: extra.code, body },
      );
    }
    throw Object.assign(new Error(body.message || resp.statusText), { status: resp.status, body });
  }
  // 204 No Content and bodyless 200s are valid successes — don't blow up trying to parse JSON.
  if (resp.status === 204 || resp.headers.get('Content-Length') === '0') {
    return null;
  }
  const text = await resp.text();
  if (!text) return null;
  return unwrapEnvelope(JSON.parse(text));
}

// House style on the REST side wraps newer resources in `{ data: ... }`
// (PageByIdResource, PageForAgentResource, AdminVerificationResource,
// AdminStructuralConflictsResource, AdminRetrievalQualityResource,
// StructureResource). Auto-unwrap so callers don't
// have to repeat `resp?.data?.X` at every site. Conservative shape check —
// only single-key `{data: …}` objects unwrap, so legacy responses that
// happen to include a top-level `data` field stay untouched.
function unwrapEnvelope(parsed) {
  if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      && Object.keys(parsed).length === 1 && 'data' in parsed) {
    return parsed.data;
  }
  return parsed;
}

export const api = {
  // Pages
  getPage: (name, { version, render, signal } = {}) => {
    const params = new URLSearchParams();
    if (version) params.set('version', version);
    if (render) params.set('render', 'true');
    const qs = params.toString();
    return request(`/api/pages/${encodeURIComponent(name)}${qs ? '?' + qs : ''}`, { signal });
  },

  savePage: (name, { content, metadata, changeNote, author, expectedVersion, expectedContentHash, markupSyntax }) =>
    request(`/api/pages/${encodeURIComponent(name)}`, {
      method: 'PUT',
      body: JSON.stringify({ content, metadata, changeNote, author, expectedVersion, expectedContentHash, markupSyntax }),
    }),

  patchMetadata: (name, metadata, action = 'merge') =>
    request(`/api/pages/${encodeURIComponent(name)}`, {
      method: 'PATCH',
      body: JSON.stringify({ metadata, action }),
    }),

  deletePage: (name) =>
    request(`/api/pages/${encodeURIComponent(name)}`, { method: 'DELETE' }),

  renamePage: (name, newName) =>
    request(`/api/pages/${encodeURIComponent(name)}/rename`, {
      method: 'POST',
      body: JSON.stringify({ newName }),
    }),

  listPages: ({ prefix, limit = 100, offset = 0 } = {}) => {
    const params = new URLSearchParams({ limit, offset });
    if (prefix) params.set('prefix', prefix);
    return request(`/api/pages?${params}`);
  },

  // Structured frontmatter editor: server-authoritative schema + dry-run validation.
  getFrontmatterSchema: () => request('/api/frontmatter-schema'),

  validateFrontmatter: ({ frontmatter, metadata } = {}) =>
    request('/api/frontmatter/validate', {
      method: 'POST',
      body: JSON.stringify({ frontmatter, metadata }),
    }),

  // Anchored comment threads
  listCommentThreads: (page, status = 'all') =>
    request(`/api/comment-threads?page=${encodeURIComponent(page)}&status=${encodeURIComponent(status)}`),

  createCommentThread: (page, { exact, prefix, suffix, text }) =>
    request(`/api/comment-threads?page=${encodeURIComponent(page)}`, {
      method: 'POST',
      body: JSON.stringify({ exact, prefix, suffix, text }),
    }),

  addCommentReply: (threadId, text) =>
    request(`/api/comment-threads/${encodeURIComponent(threadId)}/comments`, {
      method: 'POST',
      body: JSON.stringify({ text }),
    }),

  editComment: (threadId, commentId, text) =>
    request(`/api/comment-threads/${encodeURIComponent(threadId)}/comments/${encodeURIComponent(commentId)}`, {
      method: 'PATCH',
      body: JSON.stringify({ text }),
    }),

  deleteComment: (threadId, commentId) =>
    request(`/api/comment-threads/${encodeURIComponent(threadId)}/comments/${encodeURIComponent(commentId)}`, {
      method: 'DELETE',
    }),

  resolveCommentThread: (threadId) =>
    request(`/api/comment-threads/${encodeURIComponent(threadId)}/resolve`, { method: 'POST' }),

  reopenCommentThread: (threadId) =>
    request(`/api/comment-threads/${encodeURIComponent(threadId)}/reopen`, { method: 'POST' }),

  deleteCommentThread: (threadId) =>
    request(`/api/comment-threads/${encodeURIComponent(threadId)}`, { method: 'DELETE' }),

  // Mentions + mentionable users
  listMentionableUsers: (q, limit = 8) =>
    request(`/api/users/mentionable?q=${encodeURIComponent(q)}&limit=${limit}`),

  listMyMentions: ({ status = 'unread', limit = 25, before } = {}) =>
    request(`/api/me/mentions?status=${encodeURIComponent(status)}&limit=${limit}`
      + (before ? `&before=${encodeURIComponent(before)}` : '')),

  getMyMentionsUnreadCount: () =>
    request('/api/me/mentions/unread-count'),

  getMyPages: (limit = 15) =>
    request(`/api/me/pages?limit=${limit}`),

  markMentionRead: (id) =>
    request(`/api/me/mentions/${encodeURIComponent(id)}/read`, { method: 'POST' }),

  markAllMentionsRead: () =>
    request('/api/me/mentions/mark-all-read', { method: 'POST' }),

  // Conversion
  convertWikiToMarkdown: (content) =>
    request('/api/convert/wiki-to-markdown', {
      method: 'POST',
      body: JSON.stringify({ content }),
    }),

  // Search
  search: (query, limit = 20, { signal } = {}) =>
    request(`/api/search?q=${encodeURIComponent(query)}&limit=${limit}`, { signal }),

  // History & Diff
  getHistory: (name) =>
    request(`/api/history/${encodeURIComponent(name)}`),

  getDiff: (name, from, to) =>
    request(`/api/diff/${encodeURIComponent(name)}?from=${from}&to=${to}`),

  // Links
  getBacklinks: (name) =>
    request(`/api/backlinks/${encodeURIComponent(name)}`),

  getOutboundLinks: (name) =>
    request(`/api/outbound-links/${encodeURIComponent(name)}`),

  // Recent changes
  getRecentChanges: (limit = 50) =>
    request(`/api/recent-changes?limit=${limit}`),

  // Attachments
  listAttachments: (page) =>
    request(`/api/attachments/${encodeURIComponent(page)}`),

  uploadAttachment: async (page, file, name) => {
    const form = new FormData();
    form.append('file', file);
    if (name) form.append('name', name);
    const resp = await fetch(`${BASE}/api/attachments/${encodeURIComponent(page)}`, {
      method: 'POST',
      body: form,
    });
    if (!resp.ok) {
      const err = await resp.json().catch(() => ({ message: 'Upload failed' }));
      throw new Error(err.message || 'Upload failed');
    }
    return resp.json();
  },

  deleteAttachment: (page, filename) =>
    request(`/api/attachments/${encodeURIComponent(page)}/${encodeURIComponent(filename)}`, {
      method: 'DELETE',
    }),

  renameAttachment: async (page, oldName, newName) => {
    const resp = await fetch(
      `${BASE}/api/attachments/${encodeURIComponent(page)}/${encodeURIComponent(oldName)}`,
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ newName }),
      }
    );
    if (!resp.ok) {
      const err = await resp.json().catch(() => ({ message: 'Rename failed' }));
      throw new Error(err.message || 'Rename failed');
    }
    return resp.json();
  },

  // Auth
  getUser: () => request('/api/auth/user'),

  login: (username, password) =>
    request('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    }),

  logout: () => request('/api/auth/logout', { method: 'POST' }),

  getProfile: () => request('/api/auth/profile'),

  updateProfile: (data) =>
    request('/api/auth/profile', { method: 'PUT', body: JSON.stringify(data) }),

  deleteAccount: (confirmLoginName) =>
    request('/api/auth/profile', { method: 'DELETE', body: JSON.stringify({ confirmLoginName }) }),

  resetPassword: (email) =>
    request('/api/auth/reset-password', { method: 'POST', body: JSON.stringify({ email }) }),

  // Blog
  blog: {
    list: ({ signal } = {}) => request('/api/blog', { signal }),
    get: (username, { render, signal } = {}) => {
      const params = new URLSearchParams();
      if (render) params.set('render', 'true');
      const qs = params.toString();
      return request(`/api/blog/${encodeURIComponent(username)}${qs ? '?' + qs : ''}`, { signal });
    },
    create: () => request('/api/blog', { method: 'POST', body: '{}' }),
    update: (username, content) =>
      request(`/api/blog/${encodeURIComponent(username)}`, {
        method: 'PUT',
        body: JSON.stringify({ content }),
      }),
    remove: (username) => request(`/api/blog/${encodeURIComponent(username)}`, { method: 'DELETE' }),
    listEntries: (username) => request(`/api/blog/${encodeURIComponent(username)}/entries`),
    getEntry: (username, name, { render, signal } = {}) => {
      const params = new URLSearchParams();
      if (render) params.set('render', 'true');
      const qs = params.toString();
      return request(`/api/blog/${encodeURIComponent(username)}/entries/${encodeURIComponent(name)}${qs ? '?' + qs : ''}`, { signal });
    },
    createEntry: (username, topic, content) =>
      request(`/api/blog/${encodeURIComponent(username)}/entries`, {
        method: 'POST',
        body: JSON.stringify({ topic, ...(content ? { content } : {}) }),
      }),
    updateEntry: (username, name, content) =>
      request(`/api/blog/${encodeURIComponent(username)}/entries/${encodeURIComponent(name)}`, {
        method: 'PUT',
        body: JSON.stringify({ content }),
      }),
    deleteEntry: (username, name) =>
      request(`/api/blog/${encodeURIComponent(username)}/entries/${encodeURIComponent(name)}`, {
        method: 'DELETE',
      }),
  },

  // Admin — User Management
  admin: {
    listUsers: () => request('/admin/users'),

    getOverview: () => request('/admin/overview'),

    getUser: (loginName) =>
      request(`/admin/users/${encodeURIComponent(loginName)}`),

    createUser: (data) =>
      request('/admin/users', {
        method: 'POST',
        body: JSON.stringify(data),
      }),

    updateUser: (loginName, data) =>
      request(`/admin/users/${encodeURIComponent(loginName)}`, {
        method: 'PUT',
        body: JSON.stringify(data),
      }),

    deleteUser: (loginName) =>
      request(`/admin/users/${encodeURIComponent(loginName)}`, {
        method: 'DELETE',
      }),

    lockUser: (loginName, expiry) =>
      request(`/admin/users/${encodeURIComponent(loginName)}/lock`, {
        method: 'POST',
        body: JSON.stringify({ expiry }),
      }),

    unlockUser: (loginName) =>
      request(`/admin/users/${encodeURIComponent(loginName)}/unlock`, {
        method: 'POST',
        body: JSON.stringify({}),
      }),

    // Content Management
    getContentStats: () => request('/admin/content/stats'),

    getOrphanedPages: () => request('/admin/content/orphaned-pages'),

    getBrokenLinks: () => request('/admin/content/broken-links'),

    bulkDeletePages: (pages) =>
      request('/admin/content/bulk-delete', {
        method: 'POST',
        body: JSON.stringify({ pages }),
      }),

    purgeVersions: (page, keepLatest) =>
      request('/admin/content/purge-versions', {
        method: 'POST',
        body: JSON.stringify({ page, keepLatest }),
      }),

    reindex: () =>
      request('/admin/content/reindex', {
        method: 'POST',
        body: JSON.stringify({}),
      }),

    getIndexStatus: () => request('/admin/content/index-status'),

    getChunks: (pageName) =>
      request(`/admin/content/chunks?page=${encodeURIComponent(pageName)}`, {
        extraErrorCodes: [
          { status: 404, code: 'page_not_found', defaultMessage: `No chunks found for page ${pageName}` },
        ],
      }),

    getChunkOutliers: () => request('/admin/content/chunks/outliers'),

    rebuildIndexes: () =>
      request('/admin/content/rebuild-indexes', {
        method: 'POST',
        extraErrorCodes: [
          { status: 409, code: 'rebuild_in_flight', defaultMessage: 'A rebuild is already in flight' },
          { status: 503, code: 'rebuild_disabled', defaultMessage: 'Rebuild disabled (wikantik.rebuild.enabled=false)' },
        ],
      }),

    flushCache: (cache) =>
      request('/admin/content/cache/flush', {
        method: 'POST',
        body: JSON.stringify({ cache: cache || null }),
      }),

    reindexEmbeddings: () =>
      request('/admin/content/reindex-embeddings', {
        method: 'POST',
        extraErrorCodes: [
          { status: 409, code: 'embedding_bootstrap_running', defaultMessage: 'Embedding bootstrap already running' },
          { status: 503, code: 'hybrid_disabled', defaultMessage: 'Hybrid search disabled (wikantik.search.hybrid.enabled=false)' },
        ],
      }),

    // Group Management
    listGroups: () => request('/admin/groups'),

    getGroup: (name) =>
      request(`/admin/groups/${encodeURIComponent(name)}`),

    updateGroup: (name, data) =>
      request(`/admin/groups/${encodeURIComponent(name)}`, {
        method: 'PUT',
        body: JSON.stringify(data),
      }),

    deleteGroup: (name) =>
      request(`/admin/groups/${encodeURIComponent(name)}`, {
        method: 'DELETE',
      }),

    // Policy Grant Management
    listPolicyGrants: () => request('/admin/policy'),

    createPolicyGrant: (data) =>
      request('/admin/policy', {
        method: 'POST',
        body: JSON.stringify(data),
      }),

    updatePolicyGrant: (id, data) =>
      request(`/admin/policy/${id}`, {
        method: 'PUT',
        body: JSON.stringify(data),
      }),

    deletePolicyGrant: (id) =>
      request(`/admin/policy/${id}`, {
        method: 'DELETE',
      }),

    // API Key Management (MCP + OpenAPI tool server)
    listApiKeys: () => request('/admin/apikeys'),

    createApiKey: (data) =>
      request('/admin/apikeys', {
        method: 'POST',
        body: JSON.stringify(data),
      }),

    revokeApiKey: (id) =>
      request(`/admin/apikeys/${id}`, {
        method: 'DELETE',
      }),

    bulkApiKeyAction: (action, ids) =>
      request('/admin/apikeys/bulk-action', {
        method: 'POST',
        body: JSON.stringify({ action, ids }),
      }),

    bulkUserAction: (action, ids, opts = {}) =>
      request('/admin/users/bulk-action', {
        method: 'POST',
        body: JSON.stringify({ action, ids, ...opts }),
      }),

    // Retrieval Quality (Phase 5b)
    listRetrievalRuns: ({ querySetId, mode, limit = 30 } = {}) => {
      const params = new URLSearchParams();
      if (querySetId) params.set('query_set_id', querySetId);
      if (mode) params.set('mode', mode);
      if (limit) params.set('limit', String(limit));
      const qs = params.toString();
      return request(`/admin/retrieval-quality${qs ? '?' + qs : ''}`);
    },

    runRetrievalNow: (querySetId, mode) =>
      request('/admin/retrieval-quality/run', {
        method: 'POST',
        body: JSON.stringify({ query_set_id: querySetId, mode }),
      }),

    // KG inclusion / exclusion policy
    kgPolicy: {
      listClusters: () => request('/admin/kg-policy/clusters'),
      getCluster: (name) =>
        request(`/admin/kg-policy/clusters/${encodeURIComponent(name)}`),
      setCluster: (name, body) =>
        request(`/admin/kg-policy/clusters/${encodeURIComponent(name)}`, {
          method: 'PUT',
          body: JSON.stringify(body),
        }),
      clearCluster: (name) =>
        request(`/admin/kg-policy/clusters/${encodeURIComponent(name)}`, {
          method: 'DELETE',
        }),
      markReviewed: (name) =>
        request(
          `/admin/kg-policy/clusters/${encodeURIComponent(name)}/review`,
          { method: 'POST' },
        ),
      bootstrap: (body) =>
        request('/admin/kg-policy/bootstrap', {
          method: 'POST',
          body: JSON.stringify(body),
        }),
      explain: (id) =>
        request(`/admin/kg-policy/explain/${encodeURIComponent(id)}`),
      pending: () => request('/admin/kg-policy/pending'),
      audit: ({ cluster, limit = 100 } = {}) => {
        const params = new URLSearchParams({ limit: String(limit) });
        if (cluster) params.set('cluster', cluster);
        return request(`/admin/kg-policy/audit?${params}`);
      },
      reconciliation: () => request('/admin/kg-policy/reconciliation'),
      estimate: (cluster, action) => {
        const params = new URLSearchParams({ cluster, action });
        return request(`/admin/kg-policy/estimate?${params}`);
      },
    },

    // Audit Log
    listAuditLog: ({ actor, category, eventType, target, outcome, from, to, beforeSeq, limit } = {}) => {
      const params = new URLSearchParams();
      if (actor) params.set('actor', actor);
      if (category) params.set('category', category);
      if (eventType) params.set('eventType', eventType);
      if (target) params.set('target', target);
      if (outcome) params.set('outcome', outcome);
      if (from) params.set('from', new Date(from).toISOString());
      if (to) params.set('to', new Date(to).toISOString());
      if (beforeSeq != null) params.set('beforeSeq', String(beforeSeq));
      if (limit != null) params.set('limit', String(limit));
      const qs = params.toString();
      return request(`/admin/audit${qs ? '?' + qs : ''}`);
    },

    verifyAuditChain: () => request('/admin/audit/verify'),

    // Page Ownership Management
    pageOwnership: {
      listOrphaned: ({ limit = 50, offset = 0 } = {}) =>
        request(`/admin/page-ownership?filter=orphaned&limit=${limit}&offset=${offset}`),
      listByOwner: (owner, { limit = 50, offset = 0 } = {}) =>
        request(`/admin/page-ownership?filter=by-owner&owner=${encodeURIComponent(owner)}&limit=${limit}&offset=${offset}`),
      reassign: (pages, newOwner) =>
        request('/admin/page-ownership/reassign', {
          method: 'POST',
          body: JSON.stringify({ pages, newOwner }),
        }),
      reassignByUser: (fromOwner, toOwner) =>
        request('/admin/page-ownership/reassign-by-user', {
          method: 'POST',
          body: JSON.stringify({ fromOwner, toOwner }),
        }),
    },
  },

  // Page Graph (wikilinks between pages — distinct from the Knowledge Graph
  // entity-and-relation projection below). Powers the /page-graph viewer.
  pageGraph: {
    getSnapshot: ({ signal } = {}) =>
      request('/api/page-graph/snapshot', { signal }),
  },

  // Knowledge Graph Administration
  knowledge: {
    getGraphSnapshot: ({ minTier, signal } = {}) => {
      const qs = minTier ? `?min_tier=${encodeURIComponent(minTier)}` : '';
      return request(`/api/knowledge/graph${qs}`, { signal });
    },

    getSchema: () => request('/admin/knowledge-graph/schema'),

    queryNodes: ({ node_type, name, status, limit = 50, offset = 0 } = {}) => {
      const params = new URLSearchParams({ limit, offset });
      if (node_type) params.set('node_type', node_type);
      if (name) params.set('name', name);
      if (status) params.set('status', status);
      return request(`/admin/knowledge-graph/nodes?${params}`);
    },

    getNode: (name) =>
      request(`/admin/knowledge-graph/nodes/${encodeURIComponent(name)}`),

    // Fetches a node by its UUID. Use this whenever the node name might
    // contain characters Tomcat rejects in path segments (notably `/`, which
    // it 400s as an encoded slash by default). The edge list response
    // already carries source_id/target_id, so prefer this over getNode(name)
    // for edge curation flows.
    getNodeById: (id) =>
      request(`/admin/knowledge-graph/nodes/by-id/${encodeURIComponent(id)}`),

    // Top mentions of a node — surrounding content chunks the entity appears
    // in, ranked by extractor confidence. Used by the Edge Explorer to give
    // curators disambiguation context for acronyms / initialisms.
    getNodeMentions: (id, limit = 3) =>
      request(
        `/admin/knowledge-graph/nodes/by-id/${encodeURIComponent(id)}/mentions?limit=${limit}`,
      ),

    getEdges: (nodeId, direction = 'both') =>
      request(`/admin/knowledge-graph/edges/${nodeId}?direction=${direction}`),

    queryEdges: ({ relationship_type, search, endpoint_kind, limit = 50, offset = 0 } = {}) => {
      const params = new URLSearchParams({ limit, offset });
      if (relationship_type) params.set('relationship_type', relationship_type);
      if (search) params.set('search', search);
      if (endpoint_kind) params.set('endpoint_kind', endpoint_kind);
      return request(`/admin/knowledge-graph/edges?${params}`);
    },

    listProposals: (status = 'pending', limit = 50) =>
      request(`/admin/knowledge-graph/proposals?status=${status}&limit=${limit}`),

    listProposalsFiltered: (opts) => {
      const params = new URLSearchParams();
      if (opts?.status) params.set('status', opts.status);
      if (opts?.tier) params.set('tier', opts.tier);
      if (opts?.machineStatus) params.set('machine_status', opts.machineStatus);
      if (opts?.sourcePage) params.set('source_page', opts.sourcePage);
      if (opts?.limit) params.set('limit', String(opts.limit));
      if (opts?.offset) params.set('offset', String(opts.offset));
      if (opts?.includeMachineRejected) params.set('include_machine_rejected', 'true');
      return request(`/admin/knowledge-graph/proposals?${params}`);
    },

    judgeProposal: (id) =>
      request(`/admin/knowledge-graph/proposals/${id}/judge`, { method: 'POST' }),

    runJudge: () =>
      request('/admin/knowledge-graph/judge/run', { method: 'POST' }),

    judgeStatus: () =>
      request('/admin/knowledge-graph/judge/status'),

    listProposalReviews: (id) =>
      request(`/admin/knowledge-graph/proposals/${id}/reviews`),

    approveProposal: (id) =>
      request(`/admin/knowledge-graph/proposals/${id}/approve`, { method: 'POST' }),

    rejectProposal: (id, reason) =>
      request(`/admin/knowledge-graph/proposals/${id}/reject`, {
        method: 'POST',
        body: JSON.stringify({ reason }),
      }),

    bulkProposalAction: (action, ids, opts = {}) =>
      request('/admin/knowledge-graph/proposals/bulk-action', {
        method: 'POST',
        body: JSON.stringify({ action, ids, ...opts }),
      }),

    upsertNode: (data) =>
      request('/admin/knowledge-graph/nodes', { method: 'POST', body: JSON.stringify(data) }),

    deleteNode: (id) =>
      request(`/admin/knowledge-graph/nodes/${id}`, { method: 'DELETE' }),

    mergeNodes: (sourceId, targetId) =>
      request('/admin/knowledge-graph/nodes/merge', {
        method: 'POST',
        body: JSON.stringify({ sourceId, targetId }),
      }),

    upsertEdge: (data) =>
      request('/admin/knowledge-graph/edges', { method: 'POST', body: JSON.stringify(data) }),

    deleteEdge: (id) =>
      request(`/admin/knowledge-graph/edges/${id}`, { method: 'DELETE' }),

    deleteAndRejectEdge: (id, reason) =>
      request(`/admin/knowledge-graph/edges/${id}/delete-and-reject`, {
        method: 'POST',
        body: JSON.stringify({ reason }),
      }),

    // Elevate an existing edge in place to human-curated status — no edit
    // assumptions, just "I've reviewed this and I endorse it". Sets tier to
    // 'human' and provenance to 'human-curated', writes a CONFIRM audit row.
    confirmEdge: (id) =>
      request(`/admin/knowledge-graph/edges/${id}/confirm`, { method: 'POST' }),

    bulkDeleteEdges: ({ relationship_type, search, expected_count }) =>
      request('/admin/knowledge-graph/edges/bulk-delete', {
        method: 'POST',
        body: JSON.stringify({ relationship_type, search, expected_count }),
      }),

    getEdgeAudit: (id, limit = 20) =>
      request(`/admin/knowledge-graph/edges/${id}/audit?limit=${limit}`),

    clearAll: () =>
      request('/admin/knowledge-graph/clear-all', { method: 'POST' }),

    // Embedding endpoints (unified mention-centroid index)
    getEmbeddingStatus: () =>
      request('/admin/knowledge-graph/embeddings/status'),

    getSimilarNodes: (name, limit = 10) =>
      request(`/admin/knowledge-graph/nodes/${encodeURIComponent(name)}/similar?limit=${limit}`),

    getPagesWithoutFrontmatter: (limit = 100, offset = 0) =>
      request(`/admin/knowledge-graph/pages-without-frontmatter?limit=${limit}&offset=${offset}`),

    // Hub Proposals
    listHubProposals: (status = 'pending', hub = null, limit = 50, offset = 0) => {
      const params = new URLSearchParams({ status, limit, offset });
      if (hub) params.set('hub', hub);
      return request(`/admin/knowledge-graph/hub-proposals?${params}`);
    },

    generateHubProposals: () =>
      request('/admin/knowledge-graph/hub-proposals/generate', { method: 'POST' }),

    approveHubProposal: (id) =>
      request(`/admin/knowledge-graph/hub-proposals/${id}/approve`, { method: 'POST' }),

    rejectHubProposal: (id, reason) =>
      request(`/admin/knowledge-graph/hub-proposals/${id}/reject`, {
        method: 'POST',
        body: JSON.stringify({ reason }),
      }),

    bulkApproveHubProposals: (ids) =>
      request('/admin/knowledge-graph/hub-proposals/bulk-approve', {
        method: 'POST',
        body: JSON.stringify({ ids }),
      }),

    bulkRejectHubProposals: (ids, reason) =>
      request('/admin/knowledge-graph/hub-proposals/bulk-reject', {
        method: 'POST',
        body: JSON.stringify({ ids, reason }),
      }),

    thresholdApproveHubProposals: (threshold) =>
      request('/admin/knowledge-graph/hub-proposals/threshold-approve', {
        method: 'POST',
        body: JSON.stringify({ threshold }),
      }),

    // Hub Discovery (cluster-based)
    listHubDiscoveryProposals: (limit = 50, offset = 0) => {
      const params = new URLSearchParams({ limit, offset });
      return request(`/admin/knowledge-graph/hub-discovery/proposals?${params}`);
    },

    runHubDiscovery: () =>
      request('/admin/knowledge-graph/hub-discovery/run', { method: 'POST' }),

    acceptHubDiscoveryProposal: (id, name, members) =>
      request(`/admin/knowledge-graph/hub-discovery/proposals/${id}/accept`, {
        method: 'POST',
        body: JSON.stringify({ name, members }),
      }),

    dismissHubDiscoveryProposal: (id) =>
      request(`/admin/knowledge-graph/hub-discovery/proposals/${id}/dismiss`, { method: 'POST' }),

    listDismissedHubDiscoveryProposals: (limit = 50, offset = 0) => {
      const params = new URLSearchParams({ limit, offset });
      return request(`/admin/knowledge-graph/hub-discovery/proposals/dismissed?${params}`);
    },

    deleteDismissedHubDiscoveryProposal: (id) =>
      request(`/admin/knowledge-graph/hub-discovery/proposals/dismissed/${id}`, { method: 'DELETE' }),

    bulkDeleteDismissedHubDiscoveryProposals: (ids) =>
      request('/admin/knowledge-graph/hub-discovery/proposals/dismissed/bulk-delete', {
        method: 'POST',
        body: JSON.stringify({ ids }),
      }),

    // Existing Hubs (read + remove-member)
    listExistingHubs: () =>
      request('/admin/knowledge-graph/hub-discovery/hubs'),

    getHubDrilldown: (hubName) =>
      request(`/admin/knowledge-graph/hub-discovery/hubs/${encodeURIComponent(hubName)}`),

    removeHubMember: (hubName, member) =>
      request(`/admin/knowledge-graph/hub-discovery/hubs/${encodeURIComponent(hubName)}/remove-member`, {
        method: 'POST',
        body: JSON.stringify({ member }),
      }),

    backfillFrontmatter: () =>
      request('/admin/knowledge-graph/backfill-frontmatter', { method: 'POST' }),

    getBackfillStatus: () =>
      request('/admin/knowledge-graph/backfill-frontmatter'),

    syncHubMemberships: () =>
      request('/admin/knowledge-graph/sync-hub-memberships', { method: 'POST' }),

    // Entity extraction (LLM-based proposal regeneration)
    getExtractionStatus: () =>
      request('/admin/knowledge-graph/extract-mentions'),

    getLlmActivity: ({ limit = 200, subsystem, status } = {}) => {
      const params = new URLSearchParams({ limit });
      if (subsystem) params.set('subsystem', subsystem);
      if (status) params.set('status', status);
      return request(`/admin/llm-activity?${params}`);
    },

    startExtraction: (force = false) =>
      request(`/admin/knowledge-graph/extract-mentions${force ? '?force=true' : ''}`,
              { method: 'POST' }),

    cancelExtraction: () =>
      request('/admin/knowledge-graph/extract-mentions', { method: 'DELETE' }),
  },

  // Public page similarity
  getSimilarPages: (name, limit = 5) =>
    request(`/api/pages/${encodeURIComponent(name)}/similar?limit=${limit}`),

  // Page-scoped Knowledge Graph curation (/api/page-knowledge/{name}).
  // Paths mirror the backend PageKnowledgeResource servlet mapping exactly.
  getPageKnowledge: (name) =>
    request(`/api/page-knowledge/${encodeURIComponent(name)}`),

  upsertEntity: (name, { name: entityName, nodeType, properties }) =>
    request(`/api/page-knowledge/${encodeURIComponent(name)}/entities`, {
      method: 'POST',
      body: JSON.stringify({ name: entityName, nodeType, properties }),
    }),

  confirmEntity: (name, id) =>
    request(`/api/page-knowledge/${encodeURIComponent(name)}/entities/${encodeURIComponent(id)}/confirm`, {
      method: 'POST',
    }),

  deleteEntity: (name, id) =>
    request(`/api/page-knowledge/${encodeURIComponent(name)}/entities/${encodeURIComponent(id)}`, {
      method: 'DELETE',
    }),

  upsertEdge: (name, { sourceId, targetId, relationshipType, properties }) =>
    request(`/api/page-knowledge/${encodeURIComponent(name)}/edges`, {
      method: 'POST',
      body: JSON.stringify({ sourceId, targetId, relationshipType, properties }),
    }),

  confirmEdge: (name, id) =>
    request(`/api/page-knowledge/${encodeURIComponent(name)}/edges/${encodeURIComponent(id)}/confirm`, {
      method: 'POST',
    }),

  deleteEdge: (name, id) =>
    request(`/api/page-knowledge/${encodeURIComponent(name)}/edges/${encodeURIComponent(id)}`, {
      method: 'DELETE',
    }),

  rejectEdge: (name, id, reason) =>
    request(`/api/page-knowledge/${encodeURIComponent(name)}/edges/${encodeURIComponent(id)}/reject`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    }),
};
