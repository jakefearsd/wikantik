const BASE = '';

async function request(path, options = {}) {
  const resp = await fetch(`${BASE}${path}`, {
    headers: { 'Content-Type': 'application/json', ...options.headers },
    ...options,
  });
  if (!resp.ok) {
    const body = await resp.json().catch(() => ({ message: resp.statusText }));
    throw Object.assign(new Error(body.message || resp.statusText), { status: resp.status, body });
  }
  return resp.json();
}

export const api = {
  // Pages
  getPage: (name, { version, render } = {}) => {
    const params = new URLSearchParams();
    if (version) params.set('version', version);
    if (render) params.set('render', 'true');
    const qs = params.toString();
    return request(`/api/pages/${encodeURIComponent(name)}${qs ? '?' + qs : ''}`);
  },

  savePage: (name, { content, metadata, changeNote, author, expectedVersion, expectedContentHash }) =>
    request(`/api/pages/${encodeURIComponent(name)}`, {
      method: 'PUT',
      body: JSON.stringify({ content, metadata, changeNote, author, expectedVersion, expectedContentHash }),
    }),

  patchMetadata: (name, metadata, action = 'merge') =>
    request(`/api/pages/${encodeURIComponent(name)}`, {
      method: 'PATCH',
      body: JSON.stringify({ metadata, action }),
    }),

  deletePage: (name) =>
    request(`/api/pages/${encodeURIComponent(name)}`, { method: 'DELETE' }),

  listPages: ({ prefix, limit = 100, offset = 0 } = {}) => {
    const params = new URLSearchParams({ limit, offset });
    if (prefix) params.set('prefix', prefix);
    return request(`/api/pages?${params}`);
  },

  // Search
  search: (query, limit = 20) =>
    request(`/api/search?q=${encodeURIComponent(query)}&limit=${limit}`),

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

  uploadAttachment: async (page, file) => {
    const form = new FormData();
    form.append('file', file);
    const resp = await fetch(`/api/attachments/${encodeURIComponent(page)}`, {
      method: 'POST',
      body: form,
    });
    if (!resp.ok) throw new Error('Upload failed');
    return resp.json();
  },

  deleteAttachment: (page, filename) =>
    request(`/api/attachments/${encodeURIComponent(page)}/${encodeURIComponent(filename)}`, {
      method: 'DELETE',
    }),

  // Auth
  getUser: () => request('/api/auth/user'),

  login: (username, password) =>
    request('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    }),

  logout: () => request('/api/auth/logout', { method: 'POST' }),
};
