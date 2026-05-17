import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parsePromText, sumSeries, buildVerifyReport } from './lib/metrics.js';

const SAMPLE = `
# HELP wikantik_page_views_total Total page view requests
# TYPE wikantik_page_views_total counter
wikantik_page_views_total 7.0
http_server_requests_seconds_count{uri="/api/search",status="200"} 3.0
http_server_requests_seconds_count{uri="/api/pages/{id}",status="200"} 11.0
http_server_requests_seconds_count{uri="/knowledge-mcp",status="401"} 5.0
wikantik_search_hybrid_vector_index_size 12252.0
`;

test('parsePromText extracts name, labels, and value', () => {
  const parsed = parsePromText(SAMPLE);
  const view = parsed.find(s => s.name === 'wikantik_page_views_total');
  assert.equal(view.value, 7);
  assert.deepEqual(view.labels, {});
  const search = parsed.find(s =>
    s.name === 'http_server_requests_seconds_count' && s.labels.uri === '/api/search');
  assert.equal(search.value, 3);
  assert.equal(search.labels.status, '200');
});

test('sumSeries sums matching series and ignores comments', () => {
  const parsed = parsePromText(SAMPLE);
  assert.equal(sumSeries(parsed, 'http_server_requests_seconds_count'), 19);
  assert.equal(
    sumSeries(parsed, 'http_server_requests_seconds_count', { uri: '/api/search' }), 3);
  assert.equal(
    sumSeries(parsed, 'http_server_requests_seconds_count',
      { uri: /knowledge-mcp|wikantik-admin-mcp/ }), 5);
  assert.equal(sumSeries(parsed, 'does_not_exist'), 0);
});

test('buildVerifyReport flags a metric that did not move', () => {
  const before = parsePromText('wikantik_page_views_total 7.0\n');
  const after = parsePromText('wikantik_page_views_total 9.0\n');
  const report = buildVerifyReport(before, after, [
    { label: 'Page views', name: 'wikantik_page_views_total', mode: 'increased' },
    { label: 'Edits', name: 'wikantik_page_edits_total', mode: 'increased' },
  ]);
  assert.equal(report.allPass, false);
  assert.equal(report.rows[0].pass, true);
  assert.equal(report.rows[0].delta, 2);
  assert.equal(report.rows[1].pass, false);
});

test('buildVerifyReport mode "positive" checks the after value', () => {
  const before = parsePromText('');
  const after = parsePromText('wikantik_search_hybrid_vector_index_size 12252.0\n');
  const report = buildVerifyReport(before, after, [
    { label: 'Vector index', name: 'wikantik_search_hybrid_vector_index_size', mode: 'positive' },
  ]);
  assert.equal(report.allPass, true);
  assert.equal(report.rows[0].after, 12252);
});
