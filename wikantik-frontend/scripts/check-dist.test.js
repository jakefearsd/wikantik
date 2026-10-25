import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { checkDist, modulePreloads } from './check-dist.js';

let dir;
const write = (rel, text) => writeFileSync(join(dir, rel), text);
const map = (m) => { write('modules.json', JSON.stringify(m)); return join(dir, 'modules.json'); };
const CLEAN = { 'assets/vendor-a.js': ['/w/node_modules/react-markdown/index.js'], 'assets/vendor-b.js': ['/w/x.js'] };
const html = (...hrefs) => `<!doctype html><head>${hrefs.map((h) => `<link rel="modulepreload" crossorigin href="${h}">`).join('')}`
  + '<script type="module" crossorigin src="/assets/index-a.js"></script></head>';

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), 'check-dist-'));
  mkdirSync(join(dir, 'assets'));
  write('assets/vendor-a.js', 'export const x = 1;');
  write('assets/index-a.js', 'import("./mermaidRenderer-a.js")');
});
afterEach(() => { rmSync(dir, { recursive: true, force: true }); });

describe('check-dist', () => {
  it('reads modulepreload hrefs', () => {
    expect(modulePreloads(html('/assets/a.js', '/assets/b.js'))).toEqual(['/assets/a.js', '/assets/b.js']);
    expect(modulePreloads('<link href="/x.css" rel="stylesheet">')).toEqual([]);
  });

  it('passes a clean build (a lazy Mermaid reference in the entry is fine)', () => {
    write('index.html', html('/assets/vendor-a.js'));
    expect(checkDist(dir, map(CLEAN))).toEqual([]);
  });

  it('fails a preload named after Mermaid, one containing Mermaid code, and a missing one', () => {
    write('assets/mermaid-a.js', 'x');
    write('assets/vendor-b.js', 'var mermaidAPI = {};');
    write('index.html', html('/assets/mermaid-a.js', '/assets/vendor-b.js', '/assets/gone.js'));
    const problems = checkDist(dir, map(CLEAN));
    expect(problems).toHaveLength(3);
    expect(problems[0]).toMatch(/mermaid-a\.js, a diagram-library chunk \(by name\)/);
    expect(problems[1]).toMatch(/vendor-b\.js, which contains diagram-library code/);
    expect(problems[2]).toMatch(/gone\.js, which is not in/);
  });

  it('fails a chunk over budget unless it is a known monolithic lazy library', () => {
    write('index.html', html());
    write('assets/big-a.js', 'x'.repeat(700 * 1000 + 1));
    write('assets/elk-IJKZ-a.js', 'x'.repeat(700 * 1000 + 1));
    expect(checkDist(dir, map(CLEAN))).toEqual([expect.stringMatching(/assets\/big-a\.js is 700\.00 kB, over the 700 kB chunk budget/)]);
  });

  it('fails a preload holding a Mermaid-family module even when its code never says "mermaid"', () => {
    write('assets/chunk-X.js', 'var a=1;');
    write('index.html', html('/assets/chunk-X.js'));
    const problems = checkDist(dir, map({ 'assets/chunk-X.js': ['/w/node_modules/mermaid/node_modules/dompurify/x.js'] }));
    expect(problems).toEqual([expect.stringMatching(/chunk-X\.js, which holds Mermaid-family module .*dompurify/)]);
    expect(checkDist(dir, map({ 'assets/chunk-X.js': ['/w/node_modules/dagre-d3-es/src/index.js'] }))).toHaveLength(1);
  });

  it('fails a preload the module map does not know, and a missing build or map', () => {
    write('index.html', html('/assets/vendor-a.js'));
    expect(checkDist(dir, map({}))).toEqual([expect.stringMatching(/does not know \(stale map\?\)/)]);
    expect(checkDist(dir, join(dir, 'none.json'))[0]).toMatch(/none\.json not found/);
    expect(checkDist(join(dir, 'nope'), map(CLEAN))[0]).toMatch(/index\.html not found/);
  });
});
