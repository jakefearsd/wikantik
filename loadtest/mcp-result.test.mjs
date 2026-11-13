import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mcpToolOk, MATH_BODIES, MATH_BAD } from './lib/mcp-result.js';

const sse = (obj) => `id: 1\nevent: message\ndata: ${JSON.stringify(obj)}\n\n`;
const toolResult = (payload, isError = false) => sse({ jsonrpc: '2.0', id: 3, result: {
  content: [{ type: 'text', text: JSON.stringify(payload) }], isError, structuredContent: payload } });

test('a clean write result is ok', () => {
  const body = toolResult({ results: [{ pageName: 'P', created: true }],
    summary: { total: 1, createdCount: 1, failedCount: 0 } });
  assert.equal(mcpToolOk({ status: 200, body }), true);
});

test('a per-page failure inside an isError:false result is NOT ok', () => {
  // write_pages reports a refused page this way: the batch call "succeeds".
  const body = toolResult({ results: [{ pageName: 'P', created: false, error: 'math validation failed' }],
    summary: { total: 1, createdCount: 0, failedCount: 1 } });
  assert.equal(mcpToolOk({ status: 200, body }), false);
});

test('a delete that skipped or failed its page is NOT ok', () => {
  const body = toolResult({ results: [{ pageName: 'P', deleted: false, error: 'not found' }],
    summary: { total: 1, deletedCount: 0, skippedCount: 1, failedCount: 0 } });
  assert.equal(mcpToolOk({ status: 200, body }), false);
});

test('tool-level isError and JSON-RPC errors are NOT ok', () => {
  assert.equal(mcpToolOk({ status: 200, body: toolResult({ message: 'bad input' }, true) }), false);
  assert.equal(mcpToolOk({ status: 200,
    body: sse({ jsonrpc: '2.0', id: 3, error: { code: -32602, message: 'Invalid params' } }) }), false);
});

test('non-200, empty and unparseable replies are NOT ok', () => {
  assert.equal(mcpToolOk({ status: 429, body: '' }), false);
  assert.equal(mcpToolOk({ status: 200, body: '' }), false);
  assert.equal(mcpToolOk({ status: 200, body: 'data: {not json' }), false);
});

test('read tools with a plain text payload are ok', () => {
  assert.equal(mcpToolOk({ status: 200, body: toolResult({ slug: 'Main', content: '# Main' }) }), true);
  const plain = sse({ jsonrpc: '2.0', id: 3, result: { content: [{ type: 'text', text: '# not json' }], isError: false } });
  assert.equal(mcpToolOk({ status: 200, body: plain }), true);
});

// The server refuses display math that is not isolated (math.display.notIsolated): the $$ fences sit on lines of
// their own with a blank line before and after. The harness bodies must pass, and MATH_BAD must not.
const displayIsolated = (text) => {
  const lines = text.split('\n');
  let open = false;
  for (let i = 0; i < lines.length; i++) {
    if (!lines[i].includes('$$')) continue;
    if (lines[i].trim() !== '$$') return false;
    if (!open && i > 0 && lines[i - 1].trim() !== '') return false;
    if (open && i + 1 < lines.length && lines[i + 1].trim() !== '') return false;
    open = !open;
  }
  return !open;
};

test('every harness math body isolates its display math; the deliberate bad one does not', () => {
  for (const b of MATH_BODIES) assert.equal(displayIsolated(b), true, b);
  assert.equal(displayIsolated(MATH_BAD), false);
});
