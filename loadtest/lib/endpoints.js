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
      Accept: 'application/json, text/event-stream',
      Authorization: `Bearer ${cfg.mcpKey}`,
    },
    tags: { surface: 'mcp' },
    // An MCP probe rejected at the protocol/auth layer answers 4xx — that is
    // still recorded agent traffic (the point of the probe), not a server
    // failure. Count 2xx-4xx as expected here so MCP probes never inflate the
    // global http_req_failed rate; a 5xx still fails the run.
    responseCallback: http.expectedStatuses({ min: 200, max: 499 }),
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
    // As with MCP: a tools probe rejected at the auth layer (4xx) is expected
    // recorded traffic, not a server failure — keep it out of http_req_failed.
    responseCallback: http.expectedStatuses({ min: 200, max: 499 }),
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

// Math-bearing bodies so the save path exercises MathValidationPageFilter (the
// new save-time LaTeX validator runs on every write). Mostly valid display
// math; a minority is malformed so the refusal/422 path is profiled too.
const MATH_BODIES = [
  'Queue length: $$\nL_q = \\frac{\\lambda^2 E[S^2]}{2(1 - \\rho)}\n$$\nwith spaced inline $\\rho$ term.',
  'Posterior: $$\np(\\theta \\mid D) = \\frac{p(D \\mid \\theta)\\,p(\\theta)}{p(D)}\n$$\nnormalised.',
  'Matrix form: $$\n\\begin{bmatrix} a & b \\\\ c & d \\end{bmatrix}\n$$\nis invertible when $ad \\ne bc$.',
];
// Deliberately malformed (inline-glued display) — exercises the 422 refusal path.
const MATH_BAD = 'Broken inline:$$\\frac{a}{b}$$glued to prose.';

/**
 * Authenticated create -> edit -> delete cycle on a scoped LoadTest page.
 * Bodies carry LaTeX so MathValidationPageFilter runs on every save. Fires
 * POST_SAVE (and one refusal every ~10th iter), PAGE_DELETED. The VU must have
 * logged in first (cookie is held in the per-VU jar).
 */
export function writeCycle(cfg, vu, iter) {
  const name = `LoadTestK6-${vu}-${iter}`;
  const url = `${cfg.baseUrl}/api/pages/${encodeURIComponent(name)}`;
  const headers = { 'Content-Type': 'application/json' };
  const body = MATH_BODIES[(vu + iter) % MATH_BODIES.length];

  const create = http.put(url,
    JSON.stringify({ content: `---\ntitle: Load ${name}\n---\n\n${body}\n` }),
    { headers, tags: { surface: 'write' } });
  check(create, { 'write create 200/201': (r) => r.status === 200 || r.status === 201 });

  // Every ~10th write attempts malformed math: the filter must refuse with 422,
  // which is expected recorded traffic for this harness, not a server failure.
  if (iter % 10 === 3) {
    const bad = http.put(url,
      JSON.stringify({ content: `---\ntitle: Load ${name}\n---\n\n${MATH_BAD}\n` }),
      { headers, tags: { surface: 'write_reject' },
        responseCallback: http.expectedStatuses({ min: 200, max: 499 }) });
    check(bad, { 'malformed math refused 422': (r) => r.status === 422 });
  }

  const edit = http.put(url,
    JSON.stringify({ content: `---\ntitle: Load ${name}\n---\n\nEdited. ${body}\n` }),
    { headers, tags: { surface: 'write' } });
  check(edit, { 'write edit 200': (r) => r.status === 200 });
  const del = http.del(url, null, { headers, tags: { surface: 'write' } });
  check(del, { 'write delete 200/204': (r) => r.status === 200 || r.status === 204 });
}

// ---------------------------------------------------------------------------
// MCP agent flow — real tools/call traffic (the expensive retrieval surface),
// not just tools/list. Each VU initialises one MCP session and reuses it, the
// way a real agent client does, then calls a weighted mix of retrieval tools.
// ---------------------------------------------------------------------------

const KG_TERMS = [
  'optimization', 'latency', 'embedding', 'retrieval', 'cluster',
  'distributed', 'failure', 'queue', 'gradient', 'bayesian',
];
const mcpSessions = {}; // keyed by VU id -> Mcp-Session-Id

function mcpInit(cfg, vu) {
  if (mcpSessions[vu]) return mcpSessions[vu];
  const res = http.post(`${cfg.baseUrl}/wikantik-admin-mcp`,
    JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize',
      params: { protocolVersion: '2024-11-05', capabilities: {},
        clientInfo: { name: 'k6', version: '1' } } }),
    { headers: { 'Content-Type': 'application/json',
        Accept: 'application/json, text/event-stream',
        Authorization: `Bearer ${cfg.mcpKey}` },
      tags: { surface: 'mcp_init' },
      responseCallback: http.expectedStatuses({ min: 200, max: 499 }) });
  const sid = res.headers['Mcp-Session-Id'] || res.headers['mcp-session-id'];
  if (sid) mcpSessions[vu] = sid;
  return sid;
}

/** Drive a real MCP retrieval tool call (read_page / search_knowledge / query_nodes). */
export function mcpAgentFlow(cfg, vu, slug) {
  const sid = mcpInit(cfg, vu);
  const hdr = { 'Content-Type': 'application/json',
    Accept: 'application/json, text/event-stream',
    Authorization: `Bearer ${cfg.mcpKey}` };
  if (sid) hdr['Mcp-Session-Id'] = sid;

  const r = Math.random();
  let params;
  if (r < 0.5) {
    params = { name: 'read_page', arguments: { pageName: slug } };
  } else if (r < 0.85) {
    params = { name: 'search_knowledge',
      arguments: { query: KG_TERMS[Math.floor(Math.random() * KG_TERMS.length)], limit: 10 } };
  } else {
    params = { name: 'query_nodes',
      arguments: { query: KG_TERMS[Math.floor(Math.random() * KG_TERMS.length)], limit: 10 } };
  }
  const res = http.post(`${cfg.baseUrl}/wikantik-admin-mcp`,
    JSON.stringify({ jsonrpc: '2.0', id: 2, method: 'tools/call', params }),
    { headers: hdr, tags: { surface: 'mcp_tool' },
      responseCallback: http.expectedStatuses({ min: 200, max: 499 }) });
  check(res, { 'mcp tool reached app': (r2) => r2.status !== 0 });
  return res;
}

/**
 * Authenticated-via-Bearer MCP write cycle: write_pages (create, math-bearing)
 * -> occasional malformed-math refusal -> delete_pages. Exercises the MCP write
 * surface AND MathValidationPageFilter under load without a password login.
 */
export function mcpWriteCycle(cfg, vu, iter) {
  const sid = mcpInit(cfg, vu);
  const hdr = { 'Content-Type': 'application/json',
    Accept: 'application/json, text/event-stream',
    Authorization: `Bearer ${cfg.mcpKey}` };
  if (sid) hdr['Mcp-Session-Id'] = sid;
  const expected = http.expectedStatuses({ min: 200, max: 499 });
  const name = `LoadTestK6-${vu}-${iter}`;
  const body = MATH_BODIES[(vu + iter) % MATH_BODIES.length];
  const toolCall = (params, surface) => http.post(`${cfg.baseUrl}/wikantik-admin-mcp`,
    JSON.stringify({ jsonrpc: '2.0', id: 3, method: 'tools/call', params }),
    { headers: hdr, tags: { surface }, responseCallback: expected });

  const create = toolCall({ name: 'write_pages',
    arguments: { pages: [{ pageName: name, content: `---\ntitle: Load ${name}\n---\n\n${body}\n` }] } },
    'write_mcp');
  check(create, { 'mcp write reached app': (r) => r.status !== 0 });

  if (iter % 10 === 3) {
    toolCall({ name: 'write_pages',
      arguments: { pages: [{ pageName: `${name}-bad`, content: `---\ntitle: t\n---\n\n${MATH_BAD}\n` }] } },
      'write_mcp_reject');
  }
  toolCall({ name: 'delete_pages', arguments: { pageNames: [name], confirm: true } }, 'write_mcp');
}

/** GET /wiki/{slug}?format=md — the raw RAG-ingestion content surface. */
export function rawContent(cfg, slug) {
  const res = http.get(`${cfg.baseUrl}/wiki/${encodeURIComponent(slug)}?format=md`,
    { tags: { surface: 'raw' },
      responseCallback: http.expectedStatuses({ min: 200, max: 499 }) });
  check(res, { 'raw 200/404': (r) => r.status === 200 || r.status === 404 });
  return res;
}

/** GET /api/changes?since=... — the incremental change feed for sync pipelines. */
export function changesFeed(cfg) {
  const since = new Date(Date.now() - 86400000).toISOString();
  const res = http.get(`${cfg.baseUrl}/api/changes?since=${encodeURIComponent(since)}`,
    { tags: { surface: 'changes' } });
  check(res, { 'changes 200': (r) => r.status === 200 });
  return res;
}
