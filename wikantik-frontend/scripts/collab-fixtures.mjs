// Generates src/collab/__fixtures__/changesets.json: a seeded corpus of ChangeSet cases recorded
// from the real @codemirror/state, so a Java port can be pinned to the library byte-for-byte.
// Run: npm run collab:fixtures
import { ChangeSet, Text } from '@codemirror/state';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SEED = 129;
const CASES = 1200;
const here = dirname(fileURLToPath(import.meta.url));
const out = resolve(here, '../src/collab/__fixtures__/changesets.json');
const version = JSON.parse(
  readFileSync(resolve(here, '../node_modules/@codemirror/state/package.json'), 'utf8'),
).version;

function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rnd = mulberry32(SEED);
const int = (n) => Math.floor(rnd() * n);
const pick = (arr) => arr[int(arr.length)];

// Whole code points only: a surrogate pair is one alphabet entry, never split by construction.
const ALPHABET = [
  'a', 'b', 'c', 'x', 'y', 'z', ' ', ' ', '.', '#', '[', ']', '\n', '\n',
  '中', '文', '😀', '𝔸', 'é',
];
const isHigh = (c) => c >= 0xd800 && c <= 0xdbff;
const isLow = (c) => c >= 0xdc00 && c <= 0xdfff;

function randomText(maxLen) {
  const target = int(maxLen + 1);
  let s = '';
  while (s.length < target) s += pick(ALPHABET);
  return s;
}

// Move a boundary off the middle of a surrogate pair (down by one).
function safePos(s, pos) {
  if (pos > 0 && pos < s.length && isLow(s.charCodeAt(pos)) && isHigh(s.charCodeAt(pos - 1))) return pos - 1;
  return pos;
}

// Random non-overlapping specs on `s` (normalised, '\n' line breaks), 0-4 of them.
function randomSpecs(s) {
  const n = int(5);
  const cuts = [];
  for (let i = 0; i < n * 2; i++) {
    const r = rnd();
    const raw = r < 0.12 ? 0 : r < 0.24 ? s.length : int(s.length + 1);
    cuts.push(safePos(s, raw));
  }
  cuts.sort((x, y) => x - y);
  const specs = [];
  for (let i = 0; i + 1 < cuts.length; i += 2) {
    const from = cuts[i];
    const to = cuts[i + 1];
    const insert = rnd() < 0.7 ? randomText(6) : '';
    if (from === to && insert === '') continue;
    specs.push({ from, to, insert });
  }
  // Merge-prone adjacency is fine: ChangeSet.of normalises. Keep specs strictly ordered.
  const ordered = [];
  let last = -1;
  for (const sp of specs) {
    if (sp.from < last) continue;
    ordered.push(sp);
    last = sp.to;
  }
  return ordered;
}

function build() {
  const norm = int(10) === 0 ? '' : randomText(200);
  const doc = Text.of(norm.split('\n'));
  const a = ChangeSet.of(randomSpecs(norm), doc.length);
  const b = ChangeSet.of(randomSpecs(norm), doc.length);
  const afterA = a.apply(doc);
  const c = ChangeSet.of(randomSpecs(afterA.toString()), afterA.length);

  const positions = [];
  if (doc.length <= 40) {
    for (let pos = 0; pos <= doc.length; pos++) positions.push(pos);
  } else {
    for (let i = 0; i < 20; i++) positions.push(int(doc.length + 1));
  }
  // CRLF: a third of multi-line docs are serialised with \r\n; Text normalises them to one break.
  const serialised = norm.includes('\n') && int(3) === 0 ? norm.replace(/\n/g, '\r\n') : norm;
  return {
    doc: serialised,
    a: a.toJSON(),
    b: b.toJSON(),
    c: c.toJSON(),
    applyA: afterA.toString(),
    composeAC: a.compose(c).toJSON(),
    mapBA: b.map(a).toJSON(),
    mapBABefore: b.map(a, true).toJSON(),
    positions: positions.map((pos) => ({ pos, left: a.mapPos(pos, -1), right: a.mapPos(pos, 1) })),
  };
}

const cases = [];
for (let i = 0; i < CASES; i++) cases.push(build());

const lines = cases.map((c) => '    ' + JSON.stringify(c, null, 0));
const text =
  '{\n' +
  `  "generator": "collab-fixtures.mjs mulberry32(${SEED})",\n` +
  `  "library": "@codemirror/state ${version}",\n` +
  '  "cases": [\n' +
  lines.join(',\n') +
  '\n  ]\n}\n';
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, text);
console.log(`wrote ${cases.length} cases (@codemirror/state ${version}) to ${out}`);
