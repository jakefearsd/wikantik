/*
 * Administrative-surface request helpers for the k6 harness.
 *
 * The read/write scenarios in endpoints.js drive reader + agent traffic. This
 * module drives /admin/*, which was previously unrepresented in the harness —
 * so every optimisation arc in docs/ScalingCharacterization.md was measured
 * against a mix that contained zero admin work.
 *
 * Auth: HTTP Basic on every request, and DELIBERATELY STATELESS — no session
 * cookie is carried between requests. That models the expensive real client
 * class: monitoring pollers, cron jobs, CI scripts and SDK one-shot calls,
 * which re-present credentials on every call. A cookie-holding admin console is
 * the cheap case (BasicAuthFilter's session fast path short-circuits it), so
 * profiling that instead would flatter the numbers. Measured 2026-08-25: the
 * stateless path costs a full bcrypt verify (cost 12, ~150-250 ms) per request
 * unless the credential-verification cache is enabled.
 *
 * (k6 does not resend the JSESSIONID it receives even though its jar holds it,
 * so this statelessness is what the harness produces anyway — but it is the
 * intent, not an accident. Do not "fix" it into cookie reuse without also
 * keeping a stateless variant.)
 *
 * We deliberately send a JSON Accept header so AdminAuthFilter.isSpaNavigation()
 * does NOT short-circuit the permission check — otherwise the load test would
 * measure a bypass path instead of real authorization.
 *
 * Three tiers, weighted to mirror how the admin surface is actually used:
 *
 *   dashboard  — what an open admin panel polls: counters, status cards.
 *   management — operator CRUD reads: users, groups, policy, keys, KG, audit.
 *   audit      — deliberately EXPENSIVE full-corpus reads. These are the
 *                endpoints the source itself flags as O(corpus) or N+1. They
 *                are rare per-operator but they are where the CPU is.
 *
 * Excluded on purpose (destructive or externally-dependent, not load-testable):
 *   POST /admin/connectors/{id}/sync, POST /admin/connectors/test  (live network)
 *   POST /admin/derived/reflow without ?page=                      (corpus-wide re-extract + save)
 *   POST /admin/knowledge-graph/{clear-all,backfill-frontmatter,judge/run}
 *   POST /admin/knowledge-graph/extract-mentions                   (per-page LLM calls)
 *   POST /admin/content/{bulk-delete,purge-versions,reindex}       (content deletion)
 *   /admin/connector-oauth/gdrive/*                                (needs a live OAuth code)
 */
import http from 'k6/http';
import { check } from 'k6';

/** Build the per-request headers: Basic auth + a JSON Accept (see header note). */
function adminHeaders(cfg) {
  return {
    Authorization: `Basic ${cfg.adminBasic}`,
    Accept: 'application/json',
  };
}

/**
 * Admin endpoints answer 4xx for legitimate reasons under load (a 404 for a
 * scratch resource another VU already deleted, a 409 for an async job already
 * running, a 503 when a subsystem is disabled in this deployment). Those are
 * recorded admin traffic, not server failures — only a 5xx should fail the run.
 */
const EXPECTED = http.expectedStatuses({ min: 200, max: 499 });

function adminGet(cfg, path, surface) {
  const res = http.get(`${cfg.baseUrl}${path}`, {
    headers: adminHeaders(cfg),
    tags: { surface },
    responseCallback: EXPECTED,
  });
  check(res, { [`${surface} not 5xx`]: (r) => r.status < 500 });
  return res;
}

// ---------------------------------------------------------------------------
// Tier 1 — dashboard polling. Cheap-to-moderate, high frequency.
// ---------------------------------------------------------------------------

const DASHBOARD = [
  ['/admin/overview', 'admin_overview'],                       // ~10+ bounded DB round-trips
  ['/admin/llm-activity?limit=50', 'admin_llm_activity'],      // in-memory ring buffer
  ['/admin/content/stats', 'admin_content_stats'],
  ['/admin/content/index-status', 'admin_index_status'],
  ['/admin/ontology/status', 'admin_ontology_status'],
  ['/admin/derived/status', 'admin_derived_status'],
  ['/admin/page-graph/conflicts', 'admin_pagegraph_conflicts'],
  ['/admin/drift/summary', 'admin_drift_summary'],
  ['/admin/drift/status', 'admin_drift_status'],
  ['/admin/kg-policy/reconciliation', 'admin_kgpolicy_recon'],
  ['/admin/knowledge-graph/embeddings/status', 'admin_kg_embed_status'],
  ['/admin/insights/acquisition?days=90', 'admin_insights_acq'],
  ['/admin/insights/backlog?limit=50', 'admin_insights_backlog'],
];

/** One dashboard-panel fetch. */
export function adminDashboard(cfg) {
  const [path, surface] = DASHBOARD[Math.floor(Math.random() * DASHBOARD.length)];
  return adminGet(cfg, path, surface);
}

/**
 * The full admin-overview page load: what actually happens when an operator
 * opens the console — several panels fetched together, not one at a time.
 */
export function adminDashboardBurst(cfg) {
  const reqs = [
    '/admin/overview',
    '/admin/content/stats',
    '/admin/content/index-status',
    '/admin/drift/summary',
    '/admin/llm-activity?limit=50',
  ].map((p) => ['GET', `${cfg.baseUrl}${p}`, null,
    { headers: adminHeaders(cfg), tags: { surface: 'admin_dashboard_burst' },
      responseCallback: EXPECTED }]);
  const responses = http.batch(reqs);
  check(responses[0], { 'admin dashboard burst not 5xx': (r) => r.status < 500 });
  return responses;
}

// ---------------------------------------------------------------------------
// Tier 2 — operator management reads. Moderate cost, medium frequency.
// ---------------------------------------------------------------------------

const MANAGEMENT = [
  ['/admin/users', 'admin_users_list'],                        // findAllProfiles() bulk query
  ['/admin/groups', 'admin_groups_list'],                      // N+1 getGroup per group (small N)
  ['/admin/policy', 'admin_policy_list'],
  ['/admin/apikeys', 'admin_apikeys_list'],
  ['/admin/audit?limit=100', 'admin_audit_list'],              // server-clamped 1..1000
  ['/admin/audit?category=security&limit=50', 'admin_audit_filtered'],
  ['/admin/kg-policy/clusters', 'admin_kgpolicy_clusters'],
  ['/admin/kg-policy/pending', 'admin_kgpolicy_pending'],
  ['/admin/kg-policy/audit?limit=100', 'admin_kgpolicy_audit'],
  ['/admin/knowledge-graph/schema', 'admin_kg_schema'],
  ['/admin/knowledge-graph/nodes?limit=50', 'admin_kg_nodes'],
  ['/admin/knowledge-graph/edges?limit=50', 'admin_kg_edges'],
  ['/admin/knowledge-graph/proposals?limit=50', 'admin_kg_proposals'],
  ['/admin/knowledge-graph/hub-proposals', 'admin_kg_hub_proposals'],
  ['/admin/connectors', 'admin_connectors_list'],
  ['/admin/drift/trend?days=30', 'admin_drift_trend'],
  ['/admin/page-ownership?filter=orphaned&limit=100', 'admin_page_ownership'],
  ['/admin/retrieval-quality?limit=50', 'admin_retrieval_quality'],
];

/** One operator management read. */
export function adminManagement(cfg) {
  const [path, surface] = MANAGEMENT[Math.floor(Math.random() * MANAGEMENT.length)];
  return adminGet(cfg, path, surface);
}

// ---------------------------------------------------------------------------
// Tier 3 — the expensive full-corpus audits. Low frequency, high cost.
// Each of these is flagged O(corpus) or N+1 in its own source.
// ---------------------------------------------------------------------------

const AUDITS = [
  ['/admin/verification?limit=200', 'admin_verification'],                 // listPagesByFilter(none) full scan
  ['/admin/agent-grade-audit?limit=100', 'admin_agent_grade_audit'],       // SCAN_LIMIT=1000 + 3 lookups/page
  ['/admin/content/orphaned-pages', 'admin_orphaned_pages'],               // full reference-graph scan
  ['/admin/content/broken-links', 'admin_broken_links'],                   // N+1 findReferrers per target
  ['/admin/content/chunks/outliers', 'admin_chunk_outliers'],              // corpus-wide aggregate
  ['/admin/ontology/violations', 'admin_ontology_violations'],             // full SHACL pass, uncached
  ['/admin/audit/verify', 'admin_audit_verify'],                           // full hash-chain verify
  ['/admin/frontmatter-issues', 'admin_frontmatter_issues'],               // getAllPages + strict YAML per page
  ['/admin/knowledge-graph/pages-without-frontmatter', 'admin_kg_no_fm'],  // full corpus scan
  ['/admin/drift/pages', 'admin_drift_pages'],                             // O(rows x pages) per its own comment
];

/** One expensive full-corpus audit read. */
export function adminAudit(cfg) {
  const [path, surface] = AUDITS[Math.floor(Math.random() * AUDITS.length)];
  return adminGet(cfg, path, surface);
}

// ---------------------------------------------------------------------------
// Tier 4 — administrative WRITES. Every one is either reversible within the
// iteration or explicitly plan-only, and each fires an audit-log append so the
// tamper-evident audit chain is exercised under concurrency.
// ---------------------------------------------------------------------------

/**
 * Create then delete a scoped policy grant. Exercises the authorization write
 * path plus the policy-cache invalidation every /admin/* request then reads
 * through, which is the reason this belongs in a CPU profile rather than being
 * mocked out.
 */
export function adminPolicyCycle(cfg, vu, iter) {
  const headers = { ...adminHeaders(cfg), 'Content-Type': 'application/json' };
  const target = `LoadTestGrant-${vu}-${iter}`;
  const create = http.post(`${cfg.baseUrl}/admin/policy`,
    JSON.stringify({ principalType: 'role', principalName: 'Authenticated',
      permissionType: 'page', target, actions: 'view' }),
    { headers, tags: { surface: 'admin_policy_write' }, responseCallback: EXPECTED });
  check(create, { 'policy create not 5xx': (r) => r.status < 500 });

  // Delete by the id the create returned, so we never delete a real grant.
  let id = null;
  try { id = (create.json() || {}).id; } catch (e) { id = null; }
  if (id) {
    http.del(`${cfg.baseUrl}/admin/policy/${id}`, null,
      { headers, tags: { surface: 'admin_policy_write' }, responseCallback: EXPECTED });
  }
}

/**
 * Create then revoke an API key. Hits ApiKeyService's write path and the
 * short-TTL verify cache invalidation added in the 2026-05-22 campaign.
 */
export function adminApiKeyCycle(cfg, vu, iter) {
  const headers = { ...adminHeaders(cfg), 'Content-Type': 'application/json' };
  const create = http.post(`${cfg.baseUrl}/admin/apikeys`,
    JSON.stringify({ principalLogin: cfg.adminUser,
      label: `loadtest-${vu}-${iter}`, scope: 'mcp_read' }),
    { headers, tags: { surface: 'admin_apikey_write' }, responseCallback: EXPECTED });
  check(create, { 'apikey create not 5xx': (r) => r.status < 500 });

  let id = null;
  try { id = (create.json() || {}).id; } catch (e) { id = null; }
  if (id) {
    http.del(`${cfg.baseUrl}/admin/apikeys/${id}`, null,
      { headers, tags: { surface: 'admin_apikey_write' }, responseCallback: EXPECTED });
  }
}

/**
 * Cluster rename in PLAN-ONLY mode. Without confirm=true the endpoint computes
 * the full member set and returns the plan without writing — a genuine
 * corpus-wide read that an operator really does run before a rename.
 */
export function adminClusterRenamePlan(cfg) {
  const headers = { ...adminHeaders(cfg), 'Content-Type': 'application/json' };
  const res = http.post(
    `${cfg.baseUrl}/admin/clusters/rename?from=engineering&to=engineering-renamed`,
    null, { headers, tags: { surface: 'admin_cluster_plan' }, responseCallback: EXPECTED });
  check(res, { 'cluster rename plan not 5xx': (r) => r.status < 500 });
  return res;
}

/** Flush a render cache — a real operator action, and it forces re-render work. */
export function adminCacheFlush(cfg) {
  const headers = { ...adminHeaders(cfg), 'Content-Type': 'application/json' };
  const res = http.post(`${cfg.baseUrl}/admin/content/cache/flush?cache=documentCache`,
    null, { headers, tags: { surface: 'admin_cache_flush' }, responseCallback: EXPECTED });
  check(res, { 'cache flush not 5xx': (r) => r.status < 500 });
  return res;
}
