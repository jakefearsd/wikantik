/*
 * Pure Prometheus-text utilities for the Wikantik load harness.
 *
 * This module imports NOTHING from k6 so it can be unit-tested with
 * `node --test`. The k6 entry point does the HTTP scrape and passes the
 * raw response body to parsePromText().
 */

/** Parse Prometheus text exposition into [{ name, labels, value }]. */
export function parsePromText(text) {
  const series = [];
  for (const raw of String(text).split('\n')) {
    const line = raw.trim();
    if (line === '' || line.startsWith('#')) continue;
    // Match metric name, optional labels (handling quoted strings), and value
    const match = line.match(/^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{.*?\})?\s+(.+)$/);
    if (!match) continue;
    const value = Number(match[3].trim().split(/\s+/)[0]);
    if (Number.isNaN(value)) continue;
    series.push({ name: match[1], labels: parseLabels(match[2]), value });
  }
  return series;
}

function parseLabels(blob) {
  const labels = {};
  if (!blob) return labels;
  const body = blob.slice(1, -1);
  const re = /([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"/g;
  let m;
  while ((m = re.exec(body)) !== null) {
    labels[m[1]] = m[2].replace(/\\"/g, '"').replace(/\\n/g, '\n').replace(/\\\\/g, '\\');
  }
  return labels;
}

/**
 * Sum the value of every series named `name` whose labels satisfy `match`.
 * A matcher value may be a string (exact equality) or a RegExp.
 */
export function sumSeries(parsed, name, match) {
  let total = 0;
  for (const s of parsed) {
    if (s.name !== name) continue;
    if (match && !labelsMatch(s.labels, match)) continue;
    total += s.value;
  }
  return total;
}

function labelsMatch(labels, match) {
  for (const key of Object.keys(match)) {
    const want = match[key];
    const got = labels[key];
    if (want instanceof RegExp) {
      if (got === undefined || !want.test(got)) return false;
    } else if (got !== want) {
      return false;
    }
  }
  return true;
}

/**
 * Compare two parsed scrapes against a list of verify targets.
 * Each target: { label, name, match?, mode }. mode is 'increased'
 * (after-before > 0) or 'positive' (after > 0).
 * Returns { allPass, rows: [{ label, before, after, delta, pass }] }.
 */
export function buildVerifyReport(before, after, targets) {
  const rows = targets.map((t) => {
    const b = sumSeries(before, t.name, t.match);
    const a = sumSeries(after, t.name, t.match);
    const delta = a - b;
    const pass = t.mode === 'positive' ? a > 0 : delta > 0;
    return { label: t.label, before: b, after: a, delta, pass };
  });
  return { allPass: rows.every((r) => r.pass), rows };
}
