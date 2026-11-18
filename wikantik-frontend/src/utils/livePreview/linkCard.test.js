/**
 * Chips on a real EditorView: a link, wikilink, embed or image stays drawn with the caret on its line, the arrow keys
 * step over it, a click asks for its card, and the open card's link is followed through edits. A link being typed
 * (the caret strictly inside it) shows its source until the caret leaves.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import { EditorState, Compartment } from '@codemirror/state';
import { EditorView, keymap } from '@codemirror/view';
import { defaultKeymap } from '@codemirror/commands';
import { markdown } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { editorMarkdownConfig } from '../editorMarkdown';
import { livePreview, setLivePreview } from './index';
import { linkCardField, closeLinkCard, requestLinkCard, cardModelAt } from './linkCard';

const context = { pageName: 'Here', attachments: ['pic.png'], loadEmbed: () => Promise.resolve({ state: 'missing' }) };
const views = [];
afterEach(() => { while (views.length) { const v = views.pop(); v.destroy(); v.dom.parentNode?.remove(); } });

function mount(doc, { onLinkCard, readOnly = false, caret = 0, extra = [] } = {}) {
  const parent = document.createElement('div');
  document.body.appendChild(parent);
  const view = new EditorView({
    parent,
    state: EditorState.create({
      doc,
      selection: { anchor: caret },
      extensions: [markdown(editorMarkdownConfig), livePreview({ getContext: () => context, onLinkCard }), keymap.of(defaultKeymap),
        extra, EditorState.readOnly.of(readOnly)],
    }),
  });
  views.push(view);
  ensureSyntaxTree(view.state, view.state.doc.length, 60000);
  setLivePreview(view, true);
  return view;
}
const press = (view, key) => view.contentDOM.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
const head = (view) => view.state.selection.main.head;
const shown = (view) => view.contentDOM.textContent;
const type = (view, ch) => {
  const pos = head(view);
  view.dispatch({ changes: { from: pos, insert: ch }, selection: { anchor: pos + ch.length }, userEvent: 'input.type' });
};
function mouseDown(el, init = {}) {
  const event = new MouseEvent('mousedown', { bubbles: true, cancelable: true, button: 0, ...init });
  el.dispatchEvent(event);
  return event;
}
const chipOf = (view) => view.contentDOM.querySelector('.cm-lp-linkchip');
const card = (view) => view.state.field(linkCardField);

describe('a chip under the caret', () => {
  const DOC = 'x [[Page|a]] y';
  it('stays drawn with the caret right before and right after it', () => {
    const view = mount(DOC, { caret: 2 });
    expect(shown(view)).toBe('x a y');
    view.dispatch({ selection: { anchor: 12 } });
    expect(shown(view)).toBe('x a y');
  });
  it('the arrow keys step over it as one unit', () => {
    const view = mount(DOC, { caret: 2 });
    press(view, 'ArrowRight');
    expect(head(view)).toBe(12);
    press(view, 'ArrowLeft');
    expect(head(view)).toBe(2);
  });
  it('a caret a command places strictly inside it shows the source, as a link being typed does', () => {
    const view = mount(`${DOC}\n\nend`, { caret: 17 });
    view.dispatch({ selection: { anchor: 4 } });
    expect(head(view)).toBe(4);
    expect(shown(view)).toContain('x [[Page|a]] y');
  });
  it('Backspace right after it removes the whole link', () => {
    const view = mount(DOC, { caret: 12 });
    press(view, 'Backspace');
    expect(view.state.doc.toString()).toBe('x  y');
  });
});

describe('a link being typed shows its source', () => {
  it('typing between the brackets keeps the caret there, and leaving draws the chip', () => {
    const view = mount('x [[]] y', { caret: 4 });
    type(view, 'P');
    expect(view.state.doc.toString()).toBe('x [[P]] y');
    expect(head(view)).toBe(5);
    expect(shown(view)).toBe('x [[P]] y');
    type(view, 'q');
    expect(view.state.doc.toString()).toBe('x [[Pq]] y');
    press(view, 'Backspace');
    expect(view.state.doc.toString()).toBe('x [[P]] y');
    view.dispatch({ selection: { anchor: 7 } }); // out of the link, on the same line
    expect(shown(view)).toBe('x P y');
  });
});

describe('a click on a chip', () => {
  const DOC = 'x [[Page|a]] y\n\nend';
  it('asks for the card on that link and leaves the caret where it was', () => {
    const onLinkCard = vi.fn();
    const view = mount(DOC, { onLinkCard, caret: DOC.length });
    const event = mouseDown(chipOf(view));
    expect(event.defaultPrevented).toBe(true);
    expect(onLinkCard).toHaveBeenCalledTimes(1);
    expect(onLinkCard.mock.calls[0][0]).toEqual({ from: 2, to: 12, source: '[[Page|a]]', openedAt: 2 });
    expect(onLinkCard.mock.calls[0][1]).toBe(view);
    expect(head(view)).toBe(DOC.length);
  });
  it('with Ctrl or Cmd held is not the card\'s (it follows the link)', () => {
    const onLinkCard = vi.fn();
    const view = mount(DOC, { onLinkCard, caret: DOC.length });
    mouseDown(chipOf(view), { ctrlKey: true });
    mouseDown(chipOf(view), { metaKey: true });
    expect(onLinkCard).not.toHaveBeenCalled();
  });
  // The two refusals are asked directly: an unclaimed mousedown goes on to CodeMirror's own mouse selection, which
  // happy-dom cannot run (it fires selectionchange synchronously, into the update in progress).
  it('is not claimed when nothing shows cards', () => {
    const view = mount(DOC, { caret: DOC.length });
    expect(requestLinkCard(view, 3)).toBe(false);
    expect(card(view)).toBe(null);
  });
  it('opens no card on a read-only document', () => {
    const onLinkCard = vi.fn();
    const view = mount(DOC, { onLinkCard, readOnly: true, caret: DOC.length });
    expect(chipOf(view)).not.toBe(null);
    expect(requestLinkCard(view, 3)).toBe(false);
    expect(onLinkCard).not.toHaveBeenCalled();
    expect(card(view)).toBe(null);
  });
  it('on a drawn image opens the image\'s card, also inside a link', () => {
    const onLinkCard = vi.fn();
    const doc = 'x [![alt](pic.png)](https://e.org) y\n\nend';
    const view = mount(doc, { onLinkCard, caret: doc.length });
    mouseDown(view.contentDOM.querySelector('.cm-lp-image-wrap'));
    expect(onLinkCard.mock.calls[0][0]).toEqual({ from: 3, to: 18, source: '![alt](pic.png)', openedAt: 3 });
  });
  it('on a whole-line page embed opens the embed\'s card instead of revealing its source', () => {
    const onLinkCard = vi.fn();
    const doc = 'top\n\n  ![[Other]]\n\nend';
    const view = mount(doc, { onLinkCard, caret: 0 });
    mouseDown(view.contentDOM.querySelector('.cm-lp-embed'));
    expect(onLinkCard.mock.calls[0][0]).toEqual({ from: 7, to: 17, source: '![[Other]]', openedAt: 7 });
    expect(head(view)).toBe(0);
  });
  it('on a whole-line page embed still reveals its source when nothing shows cards', () => {
    const doc = 'top\n\n![[Other]]\n\nend';
    const view = mount(doc, { caret: 0 });
    mouseDown(view.contentDOM.querySelector('.cm-lp-embed'));
    expect(head(view)).toBe(5);
  });
});

describe('the open card follows its link', () => {
  const DOC = 'top\n\nx [[Page|a]] y';
  const open = () => {
    const onLinkCard = vi.fn();
    const view = mount(DOC, { onLinkCard, caret: 0 });
    expect(requestLinkCard(view, 7)).toBe(true);
    expect(card(view)).toEqual({ from: 7, to: 17, source: '[[Page|a]]', openedAt: 7 });
    onLinkCard.mockClear();
    return { view, onLinkCard };
  };
  it('through a change above it', () => {
    const { view, onLinkCard } = open();
    view.dispatch({ changes: { from: 0, insert: 'remote ' } });
    expect(card(view)).toEqual({ from: 14, to: 24, source: '[[Page|a]]', openedAt: 7 });
    expect(onLinkCard).toHaveBeenLastCalledWith({ from: 14, to: 24, source: '[[Page|a]]', openedAt: 7 }, view);
  });
  it('through text typed right at its edges', () => {
    const { view } = open();
    view.dispatch({ changes: [{ from: 7, insert: 'A' }, { from: 17, insert: 'B' }] });
    expect(card(view)).toEqual({ from: 8, to: 18, source: '[[Page|a]]', openedAt: 7 });
  });
  it.each([
    ['its text changes', { from: 9, to: 13, insert: 'Other' }],
    ['it is deleted', { from: 5, to: 19 }],
    ['it is replaced by the same characters around a change', { from: 8, to: 9, insert: '[x' }],
  ])('and closes when %s', (_name, changes) => {
    const { view, onLinkCard } = open();
    view.dispatch({ changes });
    expect(card(view)).toBe(null);
    expect(onLinkCard).toHaveBeenLastCalledWith(null, view);
  });
  it('closes on request, once', () => {
    const { view, onLinkCard } = open();
    view.dispatch({ effects: closeLinkCard.of(null) });
    view.dispatch({ effects: closeLinkCard.of(null) });
    expect(onLinkCard).toHaveBeenCalledTimes(1);
    expect(onLinkCard).toHaveBeenLastCalledWith(null, view);
  });
  it('closes when the document turns read-only (a shared draft freezing)', () => {
    const frozen = new Compartment();
    const onLinkCard = vi.fn();
    const view = mount(DOC, { onLinkCard, extra: frozen.of(EditorState.readOnly.of(false)) });
    requestLinkCard(view, 7);
    view.dispatch({ effects: frozen.reconfigure(EditorState.readOnly.of(true)) });
    expect(card(view)).toBe(null);
    expect(onLinkCard).toHaveBeenLastCalledWith(null, view);
  });
  it('is not opened where there is no link', () => {
    const { view } = open();
    view.dispatch({ effects: closeLinkCard.of(null) });
    expect(requestLinkCard(view, 0)).toBe(false);
    expect(card(view)).toBe(null);
  });
});

describe('cardModelAt', () => {
  const stateOf = (doc) => mount(doc).state;
  it('is the link model, with the text locked when it holds markup of its own', () => {
    expect(cardModelAt(stateOf('[plain](u)'), 1)).toMatchObject({ kind: 'link', text: 'plain', url: 'u', textLocked: false });
    expect(cardModelAt(stateOf('[a \\[b\\]](u)'), 1)).toMatchObject({ text: 'a [b]', textLocked: false });
    expect(cardModelAt(stateOf('[![alt](pic.png)](u)'), 0)).toMatchObject({ kind: 'link', textLocked: true });
    expect(cardModelAt(stateOf('[**bold**](u)'), 1)).toMatchObject({ textLocked: true });
    expect(cardModelAt(stateOf('[[Page]]'), 1)).toMatchObject({ kind: 'wiki', target: 'Page' });
    expect(cardModelAt(stateOf('plain'), 1)).toBe(null);
  });
});
