import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, symlinkSync, writeFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import { FENCE_RENDERERS } from '../src/utils/fences/registry.js';
import { resolve } from 'node:path';

const SCRIPT = resolve(process.cwd(), 'scripts/check-mermaid.mjs');
const GOOD = '```mermaid\ngraph TD\n  A --> B\n```\n';
const BAD = '```mermaid\ngraph TD\n  A --> --> B [\n```\n';
let dir;
const run = (args, input) => spawnSync('node', [SCRIPT, ...args], { encoding: 'utf8', input, timeout: 60000 });

beforeEach(() => { dir = mkdtempSync(join(tmpdir(), 'check-mermaid-')); });
afterEach(() => { rmSync(dir, { recursive: true, force: true }); });

describe('check-mermaid', () => {
  it('reportsOkAndFail', () => {
    writeFileSync(join(dir, 'Page.md'), `# Title\n\n${GOOD}\ntext\n\n${BAD}`);
    const r = run(['--json', dir]);
    expect(r.status).toBe(1);
    const out = JSON.parse(r.stdout);
    expect(out).toHaveLength(2);
    expect(out[0]).toMatchObject({ line: 3, ok: true });
    expect(out[1]).toMatchObject({ line: 10, ok: false });
    expect(out[1].error).toBeTruthy();
    expect(out[0].file).toMatch(/Page\.md$/);
    const text = run([dir]);
    expect(text.stdout).toMatch(/^OK .*Page\.md:3/m);
    expect(text.stdout).toMatch(/^FAIL .*Page\.md:10 /m);
  });

  it('ignoresExampleFences', () => {
    writeFileSync(join(dir, 'P.md'), '```mermaid example\nnot a diagram [[[\n```\n\n```java\nclass A {}\n```\n');
    const r = run(['--json', dir]);
    expect(r.status).toBe(0);
    expect(JSON.parse(r.stdout)).toEqual([]);
  });

  it('stdinInput', () => {
    const r = run(['--json', '-'], `a\n${BAD}`);
    expect(r.status).toBe(1);
    const out = JSON.parse(r.stdout);
    expect(out).toHaveLength(1);
    expect(out[0]).toMatchObject({ file: '-', line: 2, ok: false });
    expect(run(['--json', '-'], GOOD).status).toBe(0);
  });

  it('refusedPolicyCountsAsFail', () => {
    const src = '```mermaid\ngraph TD\n  A --> B\n  click A "javascript:alert(1)"\n```\n';
    const r = run(['--json', '-'], src);
    expect(r.status).toBe(1);
    const out = JSON.parse(r.stdout);
    expect(out[0].ok).toBe(false);
    expect(out[0].error).toMatch(/unsafe-link/);
  });

  it('usageErrorExitsTwo', () => {
    expect(run([]).status).toBe(2);
    expect(run(['/no/such/path-xyz']).status).toBe(2);
  });
});

describe('check-mermaid contract', () => {
  it('exitsTwoWhenCheckerBreaks', () => {
    // A dangling symlink named *.md: listed as a file, but readFileSync throws ENOENT.
    symlinkSync(join(dir, 'missing-target'), join(dir, 'broken.md'));
    const r = run(['--json', dir]);
    expect(r.status).toBe(2);
    expect(r.stdout).toBe('');
    expect(r.stderr).toMatch(/check-mermaid:/);
  });

  it('knowsEveryRegisteredFenceLanguage', () => {
    expect(Object.keys(FENCE_RENDERERS),
      'a fence language was added to registry.js: teach scripts/check-mermaid.mjs to check it (it hardcodes mermaid)')
      .toEqual(['mermaid']);
  });

  it('stdoutStaysPureJsonWhenLibrariesLog', () => {
    const preload = join(dir, 'noisy.mjs');
    writeFileSync(preload, "setTimeout(() => { console.log('noise'); console.info('noise'); console.debug('noise'); }, 0);\n");
    const r = spawnSync('node', ['--import', preload, SCRIPT, '--json', '-'], { encoding: 'utf8', input: GOOD, timeout: 60000 });
    expect(r.status).toBe(0);
    expect(JSON.parse(r.stdout)).toHaveLength(1);
  });

  it('exitsTwoWhenMermaidCannotLoad', () => {
    const r = spawnSync('node', [SCRIPT, '--json', '-'], {
      encoding: 'utf8', input: `${GOOD}\n${GOOD}`, timeout: 60000,
      env: { ...process.env, CHECK_MERMAID_TEST_IMPORT: '/nonexistent/mermaid-module.mjs' },
    });
    expect(r.status).toBe(2);
    expect(r.stdout).toBe('');
    expect(r.stderr).toMatch(/check-mermaid: cannot load mermaid/);
  });
});
