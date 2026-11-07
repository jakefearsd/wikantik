import { describe, it, expect, vi, afterEach } from 'vitest';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { markdown } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { history, undo, redo } from '@codemirror/commands';
import { EditorSelection } from '@codemirror/state';
import { fireEvent } from '@testing-library/react';
import { editorMarkdownConfig } from '../editorMarkdown';
import { livePreview, setLivePreview } from './index';
import { livePreviewPlugin, refreshLivePreview, blockField } from './plugin';
import { parseFully } from '../../test/fullParse';

vi.mock('../fences/drawFence', () => ({ drawFence: vi.fn(() => Promise.resolve('drawn')) }));
const views = [];
function liveView(doc, { context = {}, live = true, caret = doc.length } = {}) {
  const parent = document.createElement('div');
  document.body.appendChild(parent);
  const view = new EditorView({
    parent,
    state: EditorState.create({ doc, extensions: [markdown(editorMarkdownConfig), history(), EditorState.allowMultipleSelections.of(true), livePreview({ getContext: () => context })] }),
  });
  ensureSyntaxTree(view.state, view.state.doc.length, 5000);
  view.dispatch({ selection: { anchor: caret } });
  if (live) setLivePreview(view, true);
  views.push(view);
  return view;
}
afterEach(() => { while (views.length) views.pop().destroy(); vi.restoreAllMocks(); });
const lineText = (view, n) => view.contentDOM.querySelectorAll('.cm-line')[n - 1].textContent;
describe('live preview extension', () => {
  it('is off until enabled, and toggling never changes the document', () => {
    const view = liveView('# T\n\n**b**\n', { live: false });
    expect(view.dom.classList.contains('cm-live-preview')).toBe(false);
    expect(lineText(view, 1)).toBe('# T');
    setLivePreview(view, true);
    expect(view.dom.classList.contains('cm-live-preview')).toBe(true);
    expect(lineText(view, 1)).toBe('T');
    expect(view.contentDOM.querySelector('.cm-line.cm-lp-h1')).not.toBeNull();
    setLivePreview(view, false);
    expect(lineText(view, 3)).toBe('**b**');
    expect(view.state.doc.toString()).toBe('# T\n\n**b**\n');
  });
  it('moving the caret moves the revealed line', () => {
    const view = liveView('**a**\n\n**b**\n');
    expect([lineText(view, 1), lineText(view, 3)]).toEqual(['a', 'b']);
    view.dispatch({ selection: { anchor: 8 } });
    expect([lineText(view, 1), lineText(view, 3)]).toEqual(['a', '**b**']);
  });
  it('renders block math from the StateField and reveals it when the caret enters', () => {
    const view = liveView('$$\nx^2\n$$\n\nend');
    expect(view.dom.querySelector('.cm-lp-math-block .katex')).not.toBeNull();
    view.dispatch({ selection: { anchor: 4 } });
    expect(view.dom.querySelector('.cm-lp-math-block')).toBeNull();
  });
  it('a mousedown on a block widget places the caret in it, revealing the source', () => {
    const view = liveView('$$\nx^2\n$$\n\nend');
    fireEvent.mouseDown(view.dom.querySelector('.cm-lp-math-block'));
    expect(view.state.selection.main.head).toBe(0);
    expect(view.dom.querySelector('.cm-lp-math-block')).toBeNull();
  });
  it('renders a page embed through the injected loader', async () => {
    const loadEmbed = vi.fn(() => Promise.resolve({ state: 'ok', html: '<p class="emb">Hi</p>' }));
    const view = liveView('![[Other#Intro]]\n\nend', { context: { loadEmbed } });
    await new Promise((r) => setTimeout(r, 0));
    expect(loadEmbed).toHaveBeenCalledWith('Other', 'Intro');
    expect(view.dom.querySelector('.cm-lp-embed .emb').textContent).toBe('Hi');
  });
  it('a checkbox click toggles the task in the document, undoable in one step', () => {
    const view = liveView('- [ ] task\n\nend');
    fireEvent.mouseDown(view.dom.querySelector('input.cm-lp-task'));
    expect(view.state.doc.toString()).toBe('- [x] task\n\nend');
    expect(view.dom.querySelector('input.cm-lp-task').checked).toBe(true);
    undo(view);
    expect(view.state.doc.toString()).toBe('- [ ] task\n\nend');
  });
  it('is bounded to the visible ranges on a 3,000-line page', () => {
    const doc = Array.from({ length: 3000 }, (_, i) => `- item **${i}**`).join('\n');
    const view = liveView(doc, { caret: 0 });
    const rendered = view.contentDOM.querySelectorAll('.cm-line').length;
    expect(rendered).toBeLessThan(200); // happy-dom renders ~35 lines of this doc
    expect(view.contentDOM.querySelectorAll('.cm-lp-bullet').length).toBeLessThanOrEqual(rendered);
    expect(view.plugin(livePreviewPlugin).decorations.size).toBeLessThan(rendered * 6);
    const end = view.state.doc.length;
    view.dispatch({ changes: { from: end, insert: '!' }, selection: { anchor: end + 1 }, userEvent: 'input.type' });
    expect(view.plugin(livePreviewPlugin).decorations.size).toBeLessThan(rendered * 6);
  });
  it('an external edit that leaves the caret inside a hidden marker reveals the line', () => {
    const view = liveView('x\n\nsee **bold** now\n', { caret: 0 });
    expect(lineText(view, 3)).toBe('see bold now');
    // a background rewrite (rename/convert) that lands the caret inside the hidden "**"
    expect(() => view.dispatch({ changes: { from: 0, to: 1, insert: 'y' }, selection: { anchor: 8 } })).not.toThrow();
    expect(lineText(view, 3)).toBe('see **bold** now');
  });
  it('typing at the end of a decorated line keeps the raw source on that line', () => {
    const view = liveView('**bold**\n\nend', { caret: 8 });
    view.dispatch({ changes: { from: 8, insert: 'x' }, selection: { anchor: 9 }, userEvent: 'input.type' });
    expect(lineText(view, 1)).toBe('**bold**x');
    expect(view.state.doc.toString()).toBe('**bold**x\n\nend');
  });
  it('a decoration-builder failure falls back to plain source and warns', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const parent = document.createElement('div');
    document.body.appendChild(parent);
    const broken = new EditorView({
      parent,
      state: EditorState.create({ doc: '**a**\n\nend', extensions: [markdown(editorMarkdownConfig), livePreview({ getContext: () => { throw new Error('boom'); } })] }),
    });
    views.push(broken);
    broken.dispatch({ selection: { anchor: 9 } });
    setLivePreview(broken, true);
    expect(broken.contentDOM.querySelector('.cm-line').textContent).toBe('**a**');
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('[live-preview]'), 'boom', expect.any(Error));
  });
  it('warns once per distinct error message, with the error object', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const throwing = () => { throw new Error('repeat-boom'); };
    for (let i = 0; i < 3; i++) {
      const v = new EditorView({
        parent: document.body,
        state: EditorState.create({ doc: '**a**\n\nend', extensions: [markdown(editorMarkdownConfig), livePreview({ getContext: throwing })] }),
      });
      views.push(v);
      v.dispatch({ selection: { anchor: 9 } });
      setLivePreview(v, true);
    }
    const hits = warn.mock.calls.filter((c) => c[1] === 'repeat-boom' && c[0].includes('decorations'));
    expect(hits).toHaveLength(1);
    expect(hits[0][2]).toBeInstanceOf(Error);
  });
  it('a throwing spec builder (not just context) also fails open to source', async () => {
    const ranges = await import('./ranges');
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.spyOn(ranges, 'livePreviewSpecs').mockImplementation(() => { throw new Error('spec-boom'); });
    const view = liveView('**a**\n\nend', { caret: 9 });
    expect(view.contentDOM.querySelector('.cm-line').textContent).toBe('**a**');
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('[live-preview]'), 'spec-boom', expect.any(Error));
  });
  it('a throwing block spec builder (blockField only) fails open to source and warns', async () => {
    const ranges = await import('./ranges');
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.spyOn(ranges, 'blockCandidates').mockImplementation(() => { throw new Error('block-boom'); });
    const view = liveView('**a**\n\nend', { caret: 9 });
    expect(view.contentDOM.querySelector('.cm-line').textContent).toBe('a'); // inline path is unaffected
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('[live-preview]'), 'block-boom', expect.any(Error));
  });
  it('refreshLivePreview rebuilds decorations from the current context', () => {
    const context = { pageName: 'P', attachments: [] };
    const view = liveView('![a](pic.png)\n\nend', { context });
    expect(view.dom.querySelector('img.cm-lp-image').getAttribute('src')).toBe('pic.png');
    context.attachments = ['pic.png'];
    view.dispatch({ effects: refreshLivePreview.of(null) });
    expect(view.dom.querySelector('img.cm-lp-image').getAttribute('src')).toBe('/attach/P/pic.png');
  });
});

describe('live preview incremental updates', () => {
  const blocksOf = (view) => view.state.field(blockField).decos;
  const typeAt = (view, pos, text) => view.dispatch({ changes: { from: pos, insert: text }, selection: { anchor: pos + text.length }, userEvent: 'input.type' });
  const DOC = 'para one\n\n$$\nx^2\n$$\n\n![[Other]]\n\npara two **b**\n';

  it('a caret move within the revealed line keeps both decoration sets (no rebuild)', () => {
    const view = liveView(DOC, { caret: 1 });
    const inline = view.plugin(livePreviewPlugin).decorations;
    const blocks = blocksOf(view);
    view.dispatch({ selection: { anchor: 5 } });
    expect(view.plugin(livePreviewPlugin).decorations).toBe(inline);
    expect(blocksOf(view)).toBe(blocks);
  });

  it('a caret move to another line rebuilds the inline set but keeps the block set', () => {
    const view = liveView(DOC, { caret: 1 });
    const blocks = blocksOf(view);
    view.dispatch({ selection: { anchor: DOC.indexOf('para two') } });
    const lines = () => [...view.contentDOM.querySelectorAll('.cm-line')].map((l) => l.textContent);
    expect(lines()).toContain('para two **b**');
    expect(blocksOf(view)).toBe(blocks);
    view.dispatch({ selection: { anchor: 1 } });
    expect(lines()).toContain('para two b');
  });

  it('typing above a display-math block keeps its rendered widget (mapped, not rebuilt)', () => {
    const view = liveView(DOC, { caret: 1 });
    const math = view.dom.querySelector('.cm-lp-math-block');
    typeAt(view, 1, 'abc');
    expect(view.state.doc.toString()).toBe(`pabcara one${DOC.slice(8)}`);
    expect(view.dom.querySelector('.cm-lp-math-block')).toBe(math);
    expect(math.isConnected).toBe(true);
    expect(view.dom.querySelectorAll('.cm-lp-embed')).toHaveLength(1);
  });

  it('editing inside a block reveals its source, and leaving renders the new TeX', () => {
    const view = liveView(DOC, { caret: 1 });
    view.dispatch({ selection: { anchor: DOC.indexOf('x^2') + 3 } });
    expect(view.dom.querySelector('.cm-lp-math-block')).toBeNull();
    typeAt(view, DOC.indexOf('x^2') + 3, '+y');
    view.dispatch({ selection: { anchor: 1 } });
    const math = view.dom.querySelector('.cm-lp-math-block');
    expect(math).not.toBeNull();
    expect(math.querySelector('annotation').textContent).toBe('x^2+y');
  });

  it('a second cursor inside a block reveals it; dropping that cursor renders it again', () => {
    const view = liveView(DOC, { caret: 1 });
    view.dispatch({ selection: EditorSelection.create([EditorSelection.cursor(1), EditorSelection.cursor(DOC.indexOf('![[') + 2)]) });
    expect(view.dom.querySelector('.cm-lp-embed')).toBeNull();
    expect(view.dom.querySelector('.cm-lp-math-block')).not.toBeNull();
    view.dispatch({ selection: { anchor: 1 } });
    expect(view.dom.querySelector('.cm-lp-embed')).not.toBeNull();
  });

  it('a multi-cursor edit inside and around blocks never leaves a stale widget', () => {
    const view = liveView(DOC, { caret: 1 });
    const end = DOC.indexOf('$$\n\n') + 2; // after the closing $$
    view.dispatch({ changes: [{ from: DOC.indexOf('$$'), insert: 'z' }, { from: end, insert: '\nmore' }], selection: { anchor: 1 }, userEvent: 'input.type' });
    // "z$$\nx^2\n$$\nmore": the opener is no longer a `$$` line, and the lone closer has no opener: no math widget may remain.
    // (Text right after a closing `$$` does not stop display math: the reader's rule, DisplayMathPreProcessor.)
    expect(view.dom.querySelector('.cm-lp-math-block')).toBeNull();
    expect(view.dom.querySelectorAll('.cm-lp-embed')).toHaveLength(1);
  });

  it('the "around" edit alone leaves the math widget present and correct (text after a closing $$ does not end display math)', () => {
    const view = liveView(DOC, { caret: 1 });
    const end = DOC.indexOf('$$\n\n') + 2;
    view.dispatch({ changes: [{ from: end, insert: '\nmore' }], selection: { anchor: 1 }, userEvent: 'input.type' });
    const math = view.dom.querySelectorAll('.cm-lp-math-block');
    expect(math).toHaveLength(1);
    expect(math[0].textContent).toContain('x');
    expect(view.dom.querySelectorAll('.cm-lp-embed')).toHaveLength(1);
  });

  it('opening a fence above the blocks removes their widgets; undo/redo restore and remove them', () => {
    // each whole-document reparse (the fence opens or closes over everything below it) gets a ~20 ms budget in the
    // transaction; under a loaded full suite it can stop short, so the parse is finished before each assertion
    const view = liveView(DOC, { caret: 0 });
    typeAt(view, 0, '```\n');
    parseFully(view);
    view.dispatch({ selection: { anchor: 0 } });
    expect(view.dom.querySelector('.cm-lp-math-block')).toBeNull();
    expect(view.dom.querySelector('.cm-lp-embed')).toBeNull();
    undo(view);
    expect(view.state.doc.toString()).toBe(DOC);
    parseFully(view);
    view.dispatch({ selection: { anchor: 0 } });
    expect(view.dom.querySelector('.cm-lp-math-block')).not.toBeNull();
    expect(view.dom.querySelector('.cm-lp-embed')).not.toBeNull();
    redo(view);
    parseFully(view);
    view.dispatch({ selection: { anchor: 0 } });
    expect(view.dom.querySelector('.cm-lp-math-block')).toBeNull();
  });

  it('programmatic edit inside an inactive table/fence redraws it', () => {
    const shown = (view) => {
      const out = [];
      for (const it = blocksOf(view).iter(); it.value; it.next()) out.push(it.value.spec.lpWidget);
      return out;
    };
    const doc = 'top\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n```mermaid\nflowchart TD\n  A-->B\n```\n\nend';
    const view = liveView(doc, { caret: 1 });
    expect(shown(view).map((w) => w.type)).toEqual(['table', 'fence']);
    // A find/replace-style change: no selection moves into either block, so both stay inactive.
    view.dispatch({ changes: { from: doc.indexOf('| 1') + 2, insert: '9' } });
    expect(view.state.selection.main.head).toBe(1);
    expect(shown(view)[0].source).toBe('| a | b |\n|---|---|\n| 91 | 2 |');
    expect(view.dom.querySelector('.cm-lp-table tbody td').textContent).toBe('91');
    const at = view.state.doc.toString().indexOf('A-->B');
    view.dispatch({ changes: { from: at + 4, insert: 'C' } });
    expect(shown(view)[1]).toEqual({ type: 'fence', language: 'mermaid', source: 'flowchart TD\n  A-->CB\n' });
    expect(view.dom.querySelector('.cm-lp-fence-block code').textContent).toBe('flowchart TD\n  A-->CB\n');
  });

  it('turning live mode off and on again rebuilds the block widgets from scratch', () => {
    const view = liveView(DOC, { caret: 1 });
    setLivePreview(view, false);
    expect(blocksOf(view).size).toBe(0);
    typeAt(view, 0, '$$\nq\n$$\n\n');
    setLivePreview(view, true);
    expect(view.dom.querySelectorAll('.cm-lp-math-block')).toHaveLength(2);
  });
});

describe('live preview block markers', () => {
  it('renders a marker as a muted chip and a duplicate with its warning title', () => {
    const view = liveView('One ^dup\n\nTwo ^dup\n\nend', { caret: 0 });
    // line 1 is active: raw; line 3 is a duplicate
    expect(view.contentDOM.querySelector('.cm-lp-blockid')).toBeNull();
    const dup = view.contentDOM.querySelector('.cm-lp-blockid-dup');
    expect(dup.textContent).toBe('^dup');
    expect(dup.getAttribute('title')).toBe('Duplicate block id — only the first ^dup is linkable');
    view.dispatch({ selection: { anchor: view.state.doc.length } });
    expect(view.contentDOM.querySelector('.cm-lp-blockid').textContent).toBe('^dup');
    expect(view.contentDOM.querySelector('.cm-lp-blockid').getAttribute('title')).toBeNull();
  });
});
