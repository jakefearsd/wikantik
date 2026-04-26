// Context path prefix injected by SpaRoutingFilter at index.html serve time.
// Empty string for root-context deployments (production), e.g.
// "/wikantik-it-test-custom" for the selenide IT suite's cargo-launched WAR.
// Without this, absolute API paths like "/api/pages" miss the context and 404.
const BASE = (typeof window !== 'undefined' && window.__WIKANTIK_BASE__) || '';

/* global __BUILD_VERSION__ */
let versionMismatchSignaled = false;

async function request(path, options = {}) {
  const { signal, ...rest } = options;
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
    throw Object.assign(new Error(body.message || resp.statusText), { status: resp.status, body });
  }
  // 204 No Content and bodyless 200s are valid successes — don't blow up trying to parse JSON.
  if (resp.status === 204 || resp.headers.get('Content-Length') === '0') {
    return null;
  }
  const text = await resp.text();
  return text ? JSON.parse(text) : null;
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

  // Comments
  getComments: (name) =>
    request(`/api/comments/${encodeURIComponent(name)}`),

  addComment: (name, text) =>
    request(`/api/comments/${encodeURIComponent(name)}`, {
      method: 'POST',
      body: JSON.stringify({ text }),
    }),

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

    getChunks: async (pageName) => {
      const resp = await fetch(
        `${BASE}/admin/content/chunks?page=${encodeURIComponent(pageName)}`,
        {
          credentials: 'same-origin',
          headers: { 'Accept': 'application/json' },
        },
      );
      if (resp.status === 404) {
        const body = await resp.json().catch(() => ({}));
        throw Object.assign(new Error(body.error || `No chunks found for page ${pageName}`), {
          status: 404,
          code: 'page_not_found',
          body,
        });
      }
      if (!resp.ok) {
        const body = await resp.json().catch(() => ({ message: resp.statusText }));
        throw Object.assign(new Error(body.message || resp.statusText), {
          status: resp.status,
          body,
        });
      }
      const text = await resp.text();
      return text ? JSON.parse(text) : null;
    },

    getChunkOutliers: () => request('/admin/content/chunks/outliers'),

    rebuildIndexes: async () => {
      const resp = await fetch(`${BASE}/admin/content/rebuild-indexes`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Accept': 'application/json', 'Content-Type': 'application/json' },
      });
      if (resp.status === 409) {
        const body = await resp.json().catch(() => ({}));
        throw Object.assign(new Error(body.message || 'A rebuild is already in flight'), {
          status: 409,
          code: 'rebuild_in_flight',
          body,
        });
      }
      if (resp.status === 503) {
        const body = await resp.json().catch(() => ({}));
        throw Object.assign(new Error(body.message || 'Rebuild disabled (wikantik.rebuild.enabled=false)'), {
          status: 503,
          code: 'rebuild_disabled',
          body,
        });
      }
      if (!resp.ok) {
        const body = await resp.json().catch(() => ({ message: resp.statusText }));
        throw Object.assign(new Error(body.message || resp.statusText), { status: resp.status, body });
      }
      if (resp.status === 204 || resp.headers.get('Content-Length') === '0') return null;
      const text = await resp.text();
      return text ? JSON.parse(text) : null;
    },

    flushCache: (cache) =>
      request('/admin/content/cache/flush', {
        method: 'POST',
        body: JSON.stringify({ cache: cache || null }),
      }),

    reindexEmbeddings: async () => {
      const resp = await fetch(`${BASE}/admin/content/reindex-embeddings`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Accept': 'application/json', 'Content-Type': 'application/json' },
      });
      if (resp.status === 409) {
        const body = await resp.json().catch(() => ({}));
        throw Object.assign(new Error(body.message || 'Embedding bootstrap already running'), {
          status: 409,
          code: 'embedding_bootstrap_running',
          body,
        });
      }
      if (resp.status === 503) {
        const body = await resp.json().catch(() => ({}));
        throw Object.assign(new Error(body.message || 'Hybrid search disabled (wikantik.search.hybrid.enabled=false)'), {
          status: 503,
          code: 'hybrid_disabled',
          body,
        });
      }
      if (!resp.ok) {
        const body = await resp.json().catch(() => ({ message: resp.statusText }));
        throw Object.assign(new Error(body.message || resp.statusText), { status: resp.status, body });
      }
      if (resp.status === 204 || resp.headers.get('Content-Length') === '0') return null;
      const text = await resp.text();
      return text ? JSON.parse(text) : null;
    },

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
  },

  // Knowledge Graph Administration
  knowledge: {
    getGraphSnapshot: ({ signal } = {}) =>
      request('/api/knowledge/graph', { signal }),

    getSchema: () => request('/admin/knowledge/schema'),

    queryNodes: ({ node_type, name, status, limit = 50, offset = 0 } = {}) => {
      const params = new URLSearchParams({ limit, offset });
      if (node_type) params.set('node_type', node_type);
      if (name) params.set('name', name);
      if (status) params.set('status', status);
      return request(`/admin/knowledge/nodes?${params}`);
    },

    getNode: (name) =>
      request(`/admin/knowledge/nodes/${encodeURIComponent(name)}`),

    getEdges: (nodeId, direction = 'both') =>
      request(`/admin/knowledge/edges/${nodeId}?direction=${direction}`),

    queryEdges: ({ relationship_type, search, limit = 50, offset = 0 } = {}) => {
      const params = new URLSearchParams({ limit, offset });
      if (relationship_type) params.set('relationship_type', relationship_type);
      if (search) params.set('search', search);
      return request(`/admin/knowledge/edges?${params}`);
    },

    listProposals: (status = 'pending', limit = 50) =>
      request(`/admin/knowledge/proposals?status=${status}&limit=${limit}`),

    approveProposal: (id) =>
      request(`/admin/knowledge/proposals/${id}/approve`, { method: 'POST' }),

    rejectProposal: (id, reason) =>
      request(`/admin/knowledge/proposals/${id}/reject`, {
        method: 'POST',
        body: JSON.stringify({ reason }),
      }),

    upsertNode: (data) =>
      request('/admin/knowledge/nodes', { method: 'POST', body: JSON.stringify(data) }),

    deleteNode: (id) =>
      request(`/admin/knowledge/nodes/${id}`, { method: 'DELETE' }),

    mergeNodes: (sourceId, targetId) =>
      request('/admin/knowledge/nodes/merge', {
        method: 'POST',
        body: JSON.stringify({ sourceId, targetId }),
      }),

    upsertEdge: (data) =>
      request('/admin/knowledge/edges', { method: 'POST', body: JSON.stringify(data) }),

    deleteEdge: (id) =>
      request(`/admin/knowledge/edges/${id}`, { method: 'DELETE' }),

    projectAll: () =>
      request('/admin/knowledge/project-all', { method: 'POST' }),

    clearAll: () =>
      request('/admin/knowledge/clear-all', { method: 'POST' }),

    // Embedding endpoints (unified mention-centroid index)
    getEmbeddingStatus: () =>
      request('/admin/knowledge/embeddings/status'),

    getSimilarNodes: (name, limit = 10) =>
      request(`/admin/knowledge/nodes/${encodeURIComponent(name)}/similar?limit=${limit}`),

    getPagesWithoutFrontmatter: (limit = 100, offset = 0) =>
      request(`/admin/knowledge/pages-without-frontmatter?limit=${limit}&offset=${offset}`),

    // Hub Proposals
    listHubProposals: (status = 'pending', hub = null, limit = 50, offset = 0) => {
      const params = new URLSearchParams({ status, limit, offset });
      if (hub) params.set('hub', hub);
      return request(`/admin/knowledge/hub-proposals?${params}`);
    },

    generateHubProposals: () =>
      request('/admin/knowledge/hub-proposals/generate', { method: 'POST' }),

    approveHubProposal: (id) =>
      request(`/admin/knowledge/hub-proposals/${id}/approve`, { method: 'POST' }),

    rejectHubProposal: (id, reason) =>
      request(`/admin/knowledge/hub-proposals/${id}/reject`, {
        method: 'POST',
        body: JSON.stringify({ reason }),
      }),

    bulkApproveHubProposals: (ids) =>
      request('/admin/knowledge/hub-proposals/bulk-approve', {
        method: 'POST',
        body: JSON.stringify({ ids }),
      }),

    bulkRejectHubProposals: (ids, reason) =>
      request('/admin/knowledge/hub-proposals/bulk-reject', {
        method: 'POST',
        body: JSON.stringify({ ids, reason }),
      }),

    thresholdApproveHubProposals: (threshold) =>
      request('/admin/knowledge/hub-proposals/threshold-approve', {
        method: 'POST',
        body: JSON.stringify({ threshold }),
      }),

    // Hub Discovery (cluster-based)
    listHubDiscoveryProposals: (limit = 50, offset = 0) => {
      const params = new URLSearchParams({ limit, offset });
      return request(`/admin/knowledge/hub-discovery/proposals?${params}`);
    },

    runHubDiscovery: () =>
      request('/admin/knowledge/hub-discovery/run', { method: 'POST' }),

    acceptHubDiscoveryProposal: (id, name, members) =>
      request(`/admin/knowledge/hub-discovery/proposals/${id}/accept`, {
        method: 'POST',
        body: JSON.stringify({ name, members }),
      }),

    dismissHubDiscoveryProposal: (id) =>
      request(`/admin/knowledge/hub-discovery/proposals/${id}/dismiss`, { method: 'POST' }),

    listDismissedHubDiscoveryProposals: (limit = 50, offset = 0) => {
      const params = new URLSearchParams({ limit, offset });
      return request(`/admin/knowledge/hub-discovery/proposals/dismissed?${params}`);
    },

    deleteDismissedHubDiscoveryProposal: (id) =>
      request(`/admin/knowledge/hub-discovery/proposals/dismissed/${id}`, { method: 'DELETE' }),

    bulkDeleteDismissedHubDiscoveryProposals: (ids) =>
      request('/admin/knowledge/hub-discovery/proposals/dismissed/bulk-delete', {
        method: 'POST',
        body: JSON.stringify({ ids }),
      }),

    // Existing Hubs (read + remove-member)
    listExistingHubs: () =>
      request('/admin/knowledge/hub-discovery/hubs'),

    getHubDrilldown: (hubName) =>
      request(`/admin/knowledge/hub-discovery/hubs/${encodeURIComponent(hubName)}`),

    removeHubMember: (hubName, member) =>
      request(`/admin/knowledge/hub-discovery/hubs/${encodeURIComponent(hubName)}/remove-member`, {
        method: 'POST',
        body: JSON.stringify({ member }),
      }),

    backfillFrontmatter: () =>
      request('/admin/knowledge/backfill-frontmatter', { method: 'POST' }),

    getBackfillStatus: () =>
      request('/admin/knowledge/backfill-frontmatter'),

    syncHubMemberships: () =>
      request('/admin/knowledge/sync-hub-memberships', { method: 'POST' }),
  },

  // Public page similarity
  getSimilarPages: (name, limit = 5) =>
    request(`/api/pages/${encodeURIComponent(name)}/similar?limit=${limit}`),
};
