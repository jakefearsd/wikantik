/*
 * Wikantik load-test entry point. Run via bin/loadtest.sh, which sets the
 * PROFILE / BASE_URL / credential env vars. Drives a weighted mix of the
 * instrumented endpoints; with VERIFY=1 it scrapes /metrics before and
 * after and gates on a per-panel delta.
 */
import { sleep } from 'k6';
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';

import { readConfig, buildOptions, verifyTargets } from './lib/config.js';
import { loadSlugs, pick } from './lib/slugs.js';
import { viewPage, search, mcpCall, toolsCall, login, writeCycle,
  mcpAgentFlow, mcpWriteCycle, rawContent, changesFeed } from './lib/endpoints.js';
import { parsePromText, buildVerifyReport } from './lib/metrics.js';

const cfg = readConfig(__ENV);
const slugs = loadSlugs(__ENV.SLUGS_FILE);
export const options = buildOptions(cfg);

// Incremented in teardown for every verify target that did not move.
// The verify_failures threshold (config.js) turns count>0 into a non-zero exit.
const verifyFailures = new Counter('verify_failures');

/** setup(): scrape the baseline /metrics when VERIFY=1. */
export function setup() {
  if (!cfg.verify) return { baseline: null };
  const res = http.get(cfg.metricsUrl);
  if (res.status !== 200) {
    throw new Error(
      `--verify: metrics endpoint ${cfg.metricsUrl} returned ${res.status}. ` +
      'Run from inside the network or pass --metrics-url.');
  }
  return { baseline: res.body };
}

/** Read scenario: weighted mix over the instrumented surfaces, including the
 * real MCP retrieval tool surface (mcp_tool) and current RAG/sync read paths. */
export function readScenario() {
  const r = Math.random();
  if (r < 0.40) viewPage(cfg, pick(slugs));
  else if (r < 0.55) search(cfg);
  else if (r < 0.80) mcpAgentFlow(cfg, exec.vu.idInTest, pick(slugs));   // real tools/call (expensive)
  else if (r < 0.88) rawContent(cfg, pick(slugs));                       // /wiki/{slug}?format=md
  else if (r < 0.93) changesFeed(cfg);                                   // /api/changes feed
  else if (r < 0.97) mcpCall(cfg, Math.random() < 0.5 ? '/knowledge-mcp' : '/wikantik-admin-mcp');
  else toolsCall(cfg);
  sleep(0.5 + Math.random());
}

/**
 * Write scenario: math-bearing create/edit/delete cycles that exercise
 * MathValidationPageFilter on every save. Prefers the MCP write surface
 * (Bearer auth — no password login, and profiles the MCP write path the way a
 * real agent edits content); falls back to the REST login path when only admin
 * credentials are available.
 */
export function writeScenario() {
  if (cfg.mcpKey) {
    mcpWriteCycle(cfg, exec.vu.idInTest, exec.vu.iterationInScenario);
  } else if (cfg.adminUser && cfg.adminPass) {
    login(cfg);
    writeCycle(cfg, exec.vu.idInTest, exec.vu.iterationInScenario);
  } else {
    throw new Error('WRITES=1 requires LOADTEST_MCP_KEY (preferred) or LOADTEST_ADMIN_USER/PASS');
  }
  sleep(2);
}

/** teardown(): re-scrape /metrics, diff, print PASS/FAIL, gate on the delta. */
export function teardown(data) {
  if (!cfg.verify || !data.baseline) return;
  const after = http.get(cfg.metricsUrl);
  if (after.status !== 200) {
    verifyFailures.add(1);
    console.error(`--verify: post-run scrape failed (${after.status})`);
    return;
  }
  const report = buildVerifyReport(
    parsePromText(data.baseline), parsePromText(after.body), verifyTargets(cfg));

  console.log('=== Verify: metrics delta ===');
  for (const row of report.rows) {
    const mark = row.pass ? 'PASS' : 'FAIL';
    console.log(
      `  [${mark}] ${row.label}: before=${row.before} after=${row.after} delta=${row.delta}`);
    if (!row.pass) verifyFailures.add(1);
  }
  console.log(report.allPass
    ? '=== Verify: all targets moved ==='
    : '=== Verify: FAILED — see FAIL rows above ===');
}
