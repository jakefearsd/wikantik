/*
 * Pure MCP-reply utilities for the Wikantik load harness. Imports nothing from k6, so it can be unit-tested
 * with `node --test loadtest/mcp-result.test.mjs`.
 */

// Valid display math for the write scenario: the server refuses a $$ fence that does not sit on a line of its own
// with a blank line before and after (math.display.notIsolated), and the earlier bodies broke that rule, so every
// "write" was refused and the scenario saved nothing.
export const MATH_BODIES = [
  'Queue length:\n\n$$\nL_q = \\frac{\\lambda^2 E[S^2]}{2(1 - \\rho)}\n$$\n\nwith spaced inline $\\rho$ term.',
  'Posterior:\n\n$$\np(\\theta \\mid D) = \\frac{p(D \\mid \\theta)\\,p(\\theta)}{p(D)}\n$$\n\nnormalised.',
  'Matrix form:\n\n$$\n\\begin{bmatrix} a & b \\\\ c & d \\end{bmatrix}\n$$\n\nis invertible when $ad \\ne bc$.',
];
// Deliberately malformed (inline-glued display) — exercises the refusal path.
export const MATH_BAD = 'Broken inline:$$\\frac{a}{b}$$glued to prose.';

/** The JSON-RPC message in an MCP reply: the `data:` line of an SSE reply, or the whole body. Null if unparseable. */
function rpcMessage(body) {
  const text = String(body);
  const data = text.split('\n').find((l) => l.startsWith('data:'));
  try {
    return JSON.parse(data ? data.slice(5) : text);
  } catch (e) {
    return null;
  }
}

/**
 * True when an MCP tools/call really succeeded: HTTP 200, a parseable JSON-RPC result with no `error` and no
 * tool-level `isError`, and — for batch tools such as write_pages and delete_pages, which report a refused page
 * inside an otherwise successful result — no per-page `error` and no failed or skipped count.
 */
export function mcpToolOk(r) {
  if (r.status !== 200 || !r.body) return false;
  const msg = rpcMessage(r.body);
  if (!msg || msg.error || !msg.result || msg.result.isError) return false;
  let payload = msg.result.structuredContent;
  if (!payload) {
    const text = msg.result.content && msg.result.content[0] && msg.result.content[0].text;
    try {
      payload = JSON.parse(text);
    } catch (e) {
      return true; // a plain-text payload (a page body, say) carries no per-page status
    }
  }
  if (!payload || typeof payload !== 'object') return true;
  if (Array.isArray(payload.results) && payload.results.some((p) => p && p.error)) return false;
  const s = payload.summary || {};
  return !(s.failedCount > 0 || s.skippedCount > 0);
}
