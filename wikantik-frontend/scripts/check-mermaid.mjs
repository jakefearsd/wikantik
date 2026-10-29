// Corpus checker for Mermaid fences. For every non-`example` ```mermaid fence in the given Markdown it runs the
// wiki's JS safety policy (checkMermaid) and then mermaid.parse, and reports each diagram.
//
// Usage: node scripts/check-mermaid.mjs [--json] <file|dir|->...
//   file  a Markdown file          dir  every *.md under it (recursive)          -  Markdown on stdin
// Output: one line per diagram, `OK|FAIL <file>:<line> <first error line>` (line = the opening fence, 1-based);
//   with --json, ONLY a JSON array of { file, line, ok, error } on stdout. Everything else (mermaid / happy-dom
//   noise) goes to stderr. This stdin/--json contract is used by the content agent's linter — keep it stable.
// Exit: 0 all OK (or no diagrams), 1 any FAIL, 2 usage error.
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { enableCompileCache } from 'node:module';
import { join } from 'node:path';

// Start-up cost dominates a run (the linter checks one page per launch) and most of it is compiling mermaid's
// modules: cache their V8 code (Node 22.1+; a no-op where unsupported, and never a reason to fail).
try {
  enableCompileCache?.(process.env.CHECK_MERMAID_COMPILE_CACHE
    || new URL('../node_modules/.cache/check-mermaid', import.meta.url).pathname);
} catch (e) {
  process.stderr.write(`check-mermaid: compile cache disabled: ${e?.message ?? e}\n`);
}

// Loaded after the cache is on (static imports would be compiled before it).
const { Window } = await import('happy-dom');
const { unified } = await import('unified');
const { default: remarkParse } = await import('remark-parse');
const { default: remarkGfm } = await import('remark-gfm');
const { visit } = await import('unist-util-visit');
const { parseFenceInfo } = await import('../src/utils/fences/fenceInfo.js');
const { checkMermaid } = await import('../src/utils/fences/mermaidPolicy.js');

// stdout is reserved for the report; any library chatter belongs on stderr.
console.log = console.info = console.debug = (...a) => console.error(...a);

function usage(msg) {
  if (msg) process.stderr.write(`${msg}\n`);
  process.stderr.write('usage: node scripts/check-mermaid.mjs [--json] <file|dir|->...\n');
  process.exit(2);
}

const args = process.argv.slice(2);
const json = args.includes('--json');
const targets = args.filter((a) => a !== '--json');
if (targets.length === 0) usage();
for (const t of targets) {
  if (t.startsWith('--')) usage(`unknown option ${t}`);
  if (t !== '-' && !existsSync(t)) usage(`no such file or directory: ${t}`);
}

function markdownFiles(path) {
  if (!statSync(path).isDirectory()) return [path];
  return readdirSync(path, { withFileTypes: true })
    .sort((a, b) => a.name.localeCompare(b.name))
    .flatMap((e) => (e.isDirectory() ? markdownFiles(join(path, e.name)) : e.name.endsWith('.md') ? [join(path, e.name)] : []));
}

const processor = unified().use(remarkParse).use(remarkGfm);

/** The non-example Mermaid fences of a Markdown text: [{ line, source }]. */
function mermaidFences(text) {
  const out = [];
  visit(processor.parse(text), 'code', (node) => {
    const info = parseFenceInfo(`${node.lang ?? ''} ${node.meta ?? ''}`);
    if (info.language === 'mermaid' && !info.example) {
      out.push({ line: node.position.start.line, source: node.value.replace(/\r\n?/g, '\n') });
    }
  });
  return out;
}

class CheckerError extends Error {}

/** Reads every input and checks every diagram; throws CheckerError/other on a checker (not diagram) fault. */
async function main() {
  const inputs = [];
  for (const t of targets) {
    if (t === '-') inputs.push({ file: '-', text: readFileSync(0, 'utf8') });
    else for (const f of markdownFiles(t)) inputs.push({ file: f, text: readFileSync(f, 'utf8') });
  }

  let mermaid = null;
  async function loadMermaid() {
    // Mermaid touches the DOM at import time, so the shim must exist first.
    const w = new Window();
    globalThis.window = w;
    globalThis.document = w.document;
    for (const k of ['DOMParser', 'Element', 'HTMLElement', 'Node', 'DocumentFragment', 'getComputedStyle']) {
      if (!(k in globalThis)) globalThis[k] = w[k];
    }
    try {
      mermaid = (await import(process.env.CHECK_MERMAID_TEST_IMPORT || 'mermaid')).default;
    } catch (e) {
      throw new CheckerError(`cannot load mermaid: ${e?.message ?? e}`);
    }
    mermaid.initialize({ startOnLoad: false, securityLevel: 'antiscript' });
  }

  const results = [];
  for (const { file, text } of inputs) {
    for (const { line, source } of mermaidFences(text)) {
      const policy = checkMermaid(source);
      let error = null;
      if (!policy.allowed) {
        error = policy.reason;
      } else {
        // A load failure is the checker's fault (CheckerError, exit 2), never a per-diagram FAIL.
        if (!mermaid) await loadMermaid();
        try {
          await mermaid.parse(source);
        } catch (e) {
          error = String(e?.message ?? e).split('\n').find((l) => l.trim()) ?? 'parse failed';
        }
      }
      results.push({ file, line, ok: error === null, error });
    }
  }

  return results;
}

let results;
try {
  results = await main();
} catch (e) {
  process.stderr.write(`check-mermaid: ${e?.message ?? e}\n`);
  process.exit(2);
}

if (json) {
  process.stdout.write(`${JSON.stringify(results, null, 2)}\n`);
} else {
  for (const r of results) process.stdout.write(`${r.ok ? 'OK' : 'FAIL'} ${r.file}:${r.line}${r.ok ? '' : ` ${r.error}`}\n`);
  const bad = results.filter((r) => !r.ok).length;
  process.stdout.write(`${results.length} diagrams, ${results.length - bad} ok, ${bad} failed\n`);
}
process.exit(results.some((r) => !r.ok) ? 1 : 0);
