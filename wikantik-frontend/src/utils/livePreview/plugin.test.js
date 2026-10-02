import { describe, it, expect, vi, afterEach } from 'vitest';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { markdown } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { history, undo } from '@codemirror/commands';
import { fireEvent } from '@testing-library/react';
import { editorMarkdownConfig } from '../editorMarkdown';
import { livePreview, setLivePreview } from './index';
import { livePreviewPlugin, refreshLivePreview } from './plugin';
const views = [];
function liveView(doc, { context = {}, live = true, caret = doc.length } = {}) {
  const parent = document.createElement('div');
  document.body.appendChild(parent);
  const view = new EditorView({
    parent,
    state: EditorState.create({ doc, extensions: [markdown(editorMarkdownConfig), history(), livePreview({ getContext: () => context })] }),
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
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('[live-preview]'), 'boom');
  });
  it('a throwing spec builder (not just context) also fails open to source', async () => {
    const ranges = await import('./ranges');
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.spyOn(ranges, 'livePreviewSpecs').mockImplementation(() => { throw new Error('spec-boom'); });
    const view = liveView('**a**\n\nend', { caret: 9 });
    expect(view.contentDOM.querySelector('.cm-line').textContent).toBe('**a**');
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('[live-preview]'), 'spec-boom');
  });
  it('a throwing block spec builder (blockField only) fails open to source and warns', async () => {
    const ranges = await import('./ranges');
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.spyOn(ranges, 'blockSpecs').mockImplementation(() => { throw new Error('block-boom'); });
    const view = liveView('**a**\n\nend', { caret: 9 });
    expect(view.contentDOM.querySelector('.cm-line').textContent).toBe('a'); // inline path is unaffected
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('[live-preview]'), 'block-boom');
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
