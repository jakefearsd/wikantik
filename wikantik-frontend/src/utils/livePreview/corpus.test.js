/**
 * Corpus sweep: the live preview run over every page of the repo corpus (docs/wikantik-pages), each processed exactly
 * as the editor would (frontmatter stripped; legacy JSPWiki-syntax pages skipped because the editor forces source mode
 * for them). A failure here names the page and the invariant; fix the product, never exclude the page.
 */
import { describe, it, expect, vi, afterAll } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { markdown } from '@codemirror/lang-markdown';
import { editorMarkdownConfig } from '../editorMarkdown';
import { stripFrontmatter } from '../frontmatterUtils';
import { blockCandidates, updateBlockCandidates, livePreviewSpecs } from './ranges';
import { livePreview, setLivePreview, liveModeField } from './index';
import { fullyParsed, parseFully } from '../../test/fullParse';

const PAGES_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../../docs/wikantik-pages');
const MAX_SPECS_MS = 250;
const VIEW_SAMPLE = 100;

// Port of WikiToMarkdownConverter.isLikelyWikiSyntax(String) in
// wikantik-main/src/main/java/com/wikantik/content/WikiToMarkdownConverter.java (the server sets markupSyntax
// 'likely-wiki' from it; the editor then forces source mode). Keep in step with the Java patterns and
// HEURISTIC_THRESHOLD = 3; the 'drift guard' test below mirrors cases from WikiToMarkdownConverterTest.
const WIKI_SIGNALS = [
  [/^!{1,3}\s/m, 2], [/''[^']+?''/, 2], [/(?<!\[)\[(?!\[)([^\]|]+)\|([^\]]+)\](?!\])/, 2], [/\[\{[^}]+\}\](?!\()/, 2], [/\{\{\{/, 1], [/__[^_]+?__/, 1],
];
function isLikelyWikiSyntax(body) {
  if (!body || !body.trim()) return false;
  return WIKI_SIGNALS.reduce((score, [re, pts]) => score + (re.test(body) ? pts : 0), 0) >= 3;
}

/** FNV-1a, so the edits for a page are a pure function of its name. */
function seedOf(name) {
  let h = 2166136261;
  for (let i = 0; i < name.length; i += 1) { h ^= name.charCodeAt(i); h = Math.imul(h, 16777619); }
  return h >>> 0;
}

const context = { pageName: 'CorpusPage', attachments: [], loadEmbed: () => Promise.resolve({ state: 'ok', html: '' }) };
const extensions = [markdown(editorMarkdownConfig)];

const all = readdirSync(PAGES_DIR).filter((f) => f.endsWith('.md')).sort();
const pages = [];
let legacy = 0;
for (const file of all) {
  const body = stripFrontmatter(readFileSync(path.join(PAGES_DIR, file), 'utf8'));
  if (isLikelyWikiSyntax(body)) legacy += 1; else pages.push({ name: file.replace(/\.md$/, ''), body });
}

/** Three edits: a char mid-document, a line deleted, a blank line at the top. Each is applied to the previous result. */
function editsFor(name) {
  const seed = seedOf(name);
  const mid = (st) => ({ from: Math.floor(st.doc.length / 2) + (seed % 7 === 0 ? 0 : Math.min(seed % 7, Math.floor(st.doc.length / 2))), insert: 'x' });
  const delLine = (st) => {
    const line = st.doc.line(1 + (seed % st.doc.lines));
    return { from: line.from, to: Math.min(st.doc.length, line.to + 1) };
  };
  const top = () => ({ from: 0, insert: '\n' });
  return [mid, delLine, top].map((f) => (st) => st.update({ changes: f(st), userEvent: 'input.type' }));
}

const failures = { throwing: [], noisy: [], crossing: [], overlap: [], ascend: [], incremental: [], slow: [] };
const timings = [];
const swept = [];

// The plan's budget is <= 30 s locally; this is headroom for coverage instrumentation and parallel CI workers,
// which push the full sweep past vitest's 5 s default without anything being wrong.
const HEAVY_TEST_TIMEOUT_MS = 60_000;

describe('live preview corpus sweep', () => {
  it('finds the corpus and skips only a small legacy minority', () => {
    expect(all.length).toBeGreaterThan(1000);
    expect(pages.length).toBeGreaterThan(1000);
    expect(legacy).toBeLessThan(all.length * 0.1);
  });

  it('legacy-syntax port agrees with the Java heuristic (drift guard, cases from WikiToMarkdownConverterTest)', () => {
    expect(isLikelyWikiSyntax("!!! Heading\n\nSome ''italic'' text with [Link|Page].")).toBe(true);
    expect(isLikelyWikiSyntax('# Heading\n\nSome *italic* text with [Link](Page).')).toBe(false);
    expect(isLikelyWikiSyntax('Some __bold__ text here.')).toBe(false);
    expect(isLikelyWikiSyntax('Some text with [{TableOfContents}] plugin.\n! And a heading.')).toBe(true);
    expect(isLikelyWikiSyntax('See [[OtherPage|the other page]].\n\n[{TableOfContents}]')).toBe(false);
    expect(isLikelyWikiSyntax('[[A|a]] and [[B#Heading|b]]\n\n[{ALLOW view All}]')).toBe(false);
    expect(isLikelyWikiSyntax('See [[OtherPage|alias]] and [Link|Page].\n\n[{TableOfContents}]')).toBe(true);
    expect(isLikelyWikiSyntax('Some [Link|Page] here.\n! A heading.')).toBe(true);
    expect(isLikelyWikiSyntax(null)).toBe(false);
    expect(isLikelyWikiSyntax('')).toBe(false);
    expect(isLikelyWikiSyntax('   ')).toBe(false);
  });

  it('state-level invariants hold for every page', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      for (const { name, body } of pages) {
        const before = warn.mock.calls.length + error.mock.calls.length;
        try {
          const state = fullyParsed(EditorState.create({ doc: body, extensions }));
          const t0 = performance.now();
          const specs = livePreviewSpecs(state, new Set(), { from: 0, to: state.doc.length, context });
          timings.push([name, performance.now() - t0]);
          const candidates = blockCandidates(state, context);

          let lastEnd = -1;
          for (const s of specs) {
            if (s.kind !== 'hide' && s.kind !== 'widget') continue;
            if (state.doc.sliceString(s.from, s.to).includes('\n')) failures.crossing.push(`${name}@${s.from}`);
            if (s.from < lastEnd) failures.overlap.push(`${name}@${s.from}`);
            lastEnd = Math.max(lastEnd, s.to);
          }
          for (let i = 1; i < candidates.length; i += 1) {
            const a = candidates[i - 1];
            const b = candidates[i];
            if (b.from < a.to || b.from < a.from || b.to < a.to) failures.ascend.push(`${name}@${b.from}`);
          }

          let st = state;
          let cands = candidates;
          for (const edit of editsFor(name)) {
            const tr = edit(st);
            // updateBlockCandidates reads syntaxTree(tr.startState) and syntaxTree(tr.state); `st` is fully parsed, and
            // tr.state's own tree would be partial under load, so hand it a transaction view onto the fully parsed
            // successor: both sides of the comparison then see complete trees and a slow machine cannot mismatch.
            const next = fullyParsed(tr.state);
            const inc = updateBlockCandidates(cands, { changes: tr.changes, startState: tr.startState, state: next }, context);
            const full = blockCandidates(next, context);
            if (JSON.stringify(inc) !== JSON.stringify(full)) { failures.incremental.push(name); break; }
            st = next;
            cands = full;
          }
        } catch (e) {
          failures.throwing.push(`${name}: ${e && e.message}`);
        }
        if (warn.mock.calls.length + error.mock.calls.length > before) failures.noisy.push(name);
      }
    } finally {
      warn.mockRestore();
      error.mockRestore();
    }
    const slowest = [...timings].sort((a, b) => b[1] - a[1]).slice(0, 5);
    failures.slow = slowest.filter(([, ms]) => ms > MAX_SPECS_MS).map(([n, ms]) => `${n}: ${ms.toFixed(0)} ms`);
    console.info(`live-preview corpus: ${pages.length} pages scanned, ${legacy} skipped (legacy syntax), slowest livePreviewSpecs: `
      + slowest.map(([n, ms]) => `${n} ${ms.toFixed(0)}ms`).join(', '));
    for (const [k, v] of Object.entries(failures)) expect(v, `invariant "${k}" failed on: ${v.slice(0, 20).join('; ')}`).toEqual([]);
  }, HEAVY_TEST_TIMEOUT_MS);

  // happy-dom has no layout, so CodeMirror renders only the first screenful of each page: this part exercises less
  // than the state-level sweep above, which covers every page in full.
  it('toggling live on/off/on dispatches no document change (deterministic sample)', () => {
    const step = Math.max(1, Math.floor(pages.length / VIEW_SAMPLE));
    const sample = pages.filter((_, i) => i % step === 0).slice(0, VIEW_SAMPLE);
    const bad = [];
    for (const { name, body } of sample) {
      const changed = [];
      const parent = document.createElement('div');
      document.body.appendChild(parent);
      const view = new EditorView({
        parent,
        state: EditorState.create({
          doc: body,
          extensions: [...extensions, livePreview({ getContext: () => context }),
            EditorView.updateListener.of((u) => { if (u.docChanged) changed.push(name); })],
        }),
      });
      try {
        parseFully(view);
        const baseline = view.state.doc.toString(); // CodeMirror normalises CRLF on load; the toggle must not alter that
        const live = () => view.state.field(liveModeField) && view.dom.classList.contains('cm-live-preview');
        const modes = [];
        for (const on of [true, false, true]) { setLivePreview(view, on); modes.push(live()); }
        if (modes.join() !== 'true,false,true') { bad.push(`${name}: mode did not follow the toggle (${modes.join()})`); continue; }
        if (changed.length > 0 || view.state.doc.toString() !== baseline) bad.push(name);
      } catch (e) {
        bad.push(`${name}: ${e && e.message}`);
      } finally {
        view.destroy();
        parent.remove();
      }
      swept.push(name);
    }
    expect(sample.length).toBeGreaterThan(0);
    expect(bad, `toggle changed the document of: ${bad.join('; ')}`).toEqual([]);
  }, HEAVY_TEST_TIMEOUT_MS);

  afterAll(() => { console.info(`live-preview corpus: toggle sweep covered ${swept.length} pages`); });
});
