/*
 * Per-surface k6 request helpers. Every helper drives an INSTRUMENTED
 * endpoint (the reason the old crawl scripts produced empty dashboards):
 * page views via /api/pages, search via /api/search, MCP via JSON-RPC,
 * tools via /tools, and the authenticated write cycle.
 */
import http from 'k6/http';
import { check } from 'k6';

const SEARCH_TERMS = [
  'logistics', 'optimization', 'algorithm', 'monitoring', 'latency',
  'throughput', 'scalability', 'retrieval', 'embedding', 'cluster',
];

/** GET /api/pages/{slug} — fires PAGE_REQUESTED -> wikantik_page_views_total. */
export function viewPage(cfg, slug) {
  const res = http.get(`${cfg.baseUrl}/api/pages/${encodeURIComponent(slug)}`,
    { tags: { surface: 'page' } });
  check(res, { 'page view 200/404': (r) => r.status === 200 || r.status === 404 });
  return res;
}

/** GET /api/search?q=... — drives the /api/search timer and the query embedder. */
export function search(cfg) {
  const term = SEARCH_TERMS[Math.floor(Math.random() * SEARCH_TERMS.length)];
  const res = http.get(`${cfg.baseUrl}/api/search?q=${encodeURIComponent(term)}&limit=20`,
    { tags: { surface: 'search' } });
  check(res, { 'search 200': (r) => r.status === 200 });
  return res;
}

/** POST a JSON-RPC tools/list to an MCP endpoint. */
export function mcpCall(cfg, path) {
  const body = JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/list' });
  const res = http.post(`${cfg.baseUrl}${path}`, body, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${cfg.mcpKey}`,
    },
    tags: { surface: 'mcp' },
  });
  // 401 is recorded traffic too, but warn so a bad key is not silent.
  if (res.status === 401) {
    console.warn(`MCP ${path} returned 401 — check LOADTEST_MCP_KEY scope/validity`);
  }
  check(res, { 'mcp reached app': (r) => r.status !== 0 });
  return res;
}

/** GET the OpenAPI tool server. */
export function toolsCall(cfg) {
  const res = http.get(`${cfg.baseUrl}/tools/search_wiki?q=monitoring`, {
    headers: cfg.toolsKey ? { Authorization: `Bearer ${cfg.toolsKey}` } : {},
    tags: { surface: 'tools' },
  });
  check(res, { 'tools reached app': (r) => r.status !== 0 });
  return res;
}

/** POST /api/auth/login — returns the response (sets a session cookie in the VU jar). */
export function login(cfg) {
  const res = http.post(`${cfg.baseUrl}/api/auth/login`,
    JSON.stringify({ username: cfg.adminUser, password: cfg.adminPass }),
    { headers: { 'Content-Type': 'application/json' }, tags: { surface: 'auth' } });
  check(res, { 'login 200': (r) => r.status === 200 });
  return res;
}

/**
 * Authenticated create -> edit -> delete cycle on a scoped LoadTest page.
 * Fires POST_SAVE x2 and PAGE_DELETED. The VU must have logged in first
 * (cookie is held in the per-VU jar).
 */
export function writeCycle(cfg, vu, iter) {
  const name = `LoadTest/k6-${vu}-${iter}`;
  const url = `${cfg.baseUrl}/api/pages/${encodeURIComponent(name)}`;
  const headers = { 'Content-Type': 'application/json' };
  const create = http.put(url,
    JSON.stringify({ content: `Load test page ${name}, created.` }),
    { headers, tags: { surface: 'write' } });
  check(create, { 'write create 200/201': (r) => r.status === 200 || r.status === 201 });
  const edit = http.put(url,
    JSON.stringify({ content: `Load test page ${name}, edited.` }),
    { headers, tags: { surface: 'write' } });
  check(edit, { 'write edit 200': (r) => r.status === 200 });
  const del = http.del(url, null, { headers, tags: { surface: 'write' } });
  check(del, { 'write delete 200/204': (r) => r.status === 200 || r.status === 204 });
}
