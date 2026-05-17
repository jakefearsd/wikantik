/*
 * Configuration for the Wikantik load harness: reads k6 __ENV, defines the
 * smoke/load/stress profiles, and lists the --verify targets. Imported by
 * the k6 entry point (init context).
 */

/** Resolve runtime config from k6 environment variables. */
export function readConfig(env) {
  const baseUrl = (env.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
  return {
    baseUrl,
    metricsUrl: (env.METRICS_URL || baseUrl + '/metrics').replace(/\/+$/, ''),
    profile: env.PROFILE || 'smoke',
    verify: env.VERIFY === '1',
    writes: env.WRITES === '1',
    adminUser: env.LOADTEST_ADMIN_USER || '',
    adminPass: env.LOADTEST_ADMIN_PASS || '',
    mcpKey: env.LOADTEST_MCP_KEY || '',
    toolsKey: env.LOADTEST_TOOLS_KEY || '',
    durationOverride: env.K6_DURATION || '',
    vusOverride: env.K6_VUS ? Number(env.K6_VUS) : 0,
  };
}

/**
 * k6 `options` for a profile. `read` is the main traffic scenario; `writes`
 * is added only when WRITES=1. Thresholds make k6 exit non-zero on breach.
 */
export function buildOptions(cfg) {
  // --vus / --duration arrive as K6_VUS / K6_DURATION (see readConfig).
  const peak = cfg.vusOverride > 0 ? cfg.vusOverride : null;
  const profiles = {
    smoke: {
      executor: 'constant-vus',
      vus: peak || 5,
      duration: cfg.durationOverride || '2m',
    },
    load: {
      executor: 'ramping-vus', startVUs: 0,
      stages: [
        { duration: '2m', target: peak || 30 },
        { duration: cfg.durationOverride || '5m', target: peak || 30 },
        { duration: '1m', target: 0 },
      ],
    },
    stress: {
      executor: 'ramping-vus', startVUs: 0,
      stages: [
        { duration: '2m', target: Math.round((peak || 200) * 0.25) },
        { duration: '2m', target: Math.round((peak || 200) * 0.5) },
        { duration: '2m', target: peak || 200 },
        { duration: '2m', target: 0 },
      ],
    },
  };
  const read = profiles[cfg.profile];
  if (!read) throw new Error(`unknown profile: ${cfg.profile}`);

  const scenarios = { read: { ...read, exec: 'readScenario' } };
  if (cfg.writes) {
    scenarios.writes = {
      executor: 'constant-vus', vus: 1,
      duration: read.duration || '10m',
      exec: 'writeScenario',
    };
  }
  return {
    scenarios,
    thresholds: {
      // Page/search/write traffic must stay clean. MCP and tools probes are
      // exempted per-request (responseCallback in endpoints.js) because a
      // protocol/auth 4xx there is expected recorded traffic, not a server
      // failure — so they do not inflate this rate.
      http_req_failed: ['rate<0.10'],
      http_req_duration: ['p(95)<2000'],
      verify_failures: ['count==0'],
    },
  };
}

/** Verify targets — one row per dashboard panel the harness must light up. */
export function verifyTargets(cfg) {
  const targets = [
    { label: 'Page views', name: 'wikantik_page_views_total', mode: 'increased' },
    { label: 'Searches (/api/search)', name: 'http_server_requests_seconds_count',
      match: { uri: '/api/search' }, mode: 'increased' },
    /* 'increased' — correct for the smoke gate's intended use (a freshly
       deployed app, embedder cache cold). A repeat run against a warm
       process may cache-hit and legitimately not move this counter. */
    { label: 'Hybrid embedder calls', name: 'wikantik_search_hybrid_embedder_calls_total',
      mode: 'increased' },
    { label: 'Vector index size', name: 'wikantik_search_hybrid_vector_index_size',
      mode: 'positive' },
    { label: 'Agent/admin traffic', name: 'http_server_requests_seconds_count',
      match: { uri: /knowledge-mcp|wikantik-admin-mcp/ }, mode: 'increased' },
  ];
  if (cfg.writes) {
    targets.push(
      { label: 'Page edits', name: 'wikantik_page_edits_total', mode: 'increased' },
      { label: 'Page deletes', name: 'wikantik_page_deletes_total', mode: 'increased' },
      { label: 'Logins', name: 'wikantik_auth_logins_total',
        match: { result: 'success' }, mode: 'increased' });
  }
  return targets;
}
