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
import { search, setSearchQuery, SearchQuery, findNext } from '@codemirror/search';
import { editorMarkdownConfig } from '../editorMarkdown';
import { livePreview, setLivePreview } from './index';
import { linkCardField, closeLinkCard, requestLinkCard, requestLinkWrap, cardModelAt, cardModelOf } from './linkCard';

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
const press = (view, key, init = {}) => view.contentDOM.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init }));
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
const sel = (view) => [view.state.selection.main.from, view.state.selection.main.to];
/** The card without its id (each opening has its own). */
const on = (c) => { if (!c) return c; const { id, ...rest } = c; expect(typeof id).toBe('number'); return rest; };

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
  it.each([['Backspace', {}], ['Shift-Backspace', { shiftKey: true }]])('%s right after it selects the whole link first; a second press deletes it', (_name, mods) => {
    const view = mount(DOC, { caret: 12 });
    press(view, 'Backspace', mods);
    expect(view.state.doc.toString()).toBe(DOC);
    expect(sel(view)).toEqual([2, 12]);
    expect(shown(view)).toBe('x a y'); // still a chip, now selected
    press(view, 'Backspace', mods);
    expect(view.state.doc.toString()).toBe('x  y');
  });
  it('Delete right before it selects the whole link first; a second press deletes it', () => {
    const view = mount(DOC, { caret: 2 });
    press(view, 'Delete');
    expect(view.state.doc.toString()).toBe(DOC);
    expect(sel(view)).toEqual([2, 12]);
    press(view, 'Delete');
    expect(view.state.doc.toString()).toBe('x  y');
  });
  // (With the link selected, Shift-Delete is the platform's Cut: the editor binds nothing to it.)
  it('Shift-Delete right before it selects the whole link too, and deletes nothing', () => {
    const view = mount(DOC, { caret: 2 });
    const event = new KeyboardEvent('keydown', { key: 'Delete', shiftKey: true, bubbles: true, cancelable: true });
    view.contentDOM.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
    expect(view.state.doc.toString()).toBe(DOC);
    expect(sel(view)).toEqual([2, 12]);
  });
  it('typing after the first press replaces the selected link', () => {
    const view = mount(DOC, { caret: 12 });
    press(view, 'Backspace');
    const { from, to } = view.state.selection.main;
    view.dispatch({ changes: { from, to, insert: 'z' }, selection: { anchor: from + 1 }, userEvent: 'input.type' });
    expect(view.state.doc.toString()).toBe('x z y');
  });
  it('Backspace and Delete elsewhere, and on the far side of a chip, are the ordinary keys', () => {
    const view = mount(DOC, { caret: 2 });
    press(view, 'Backspace');
    expect(view.state.doc.toString()).toBe('x[[Page|a]] y');
    view.dispatch({ selection: { anchor: 11 } });
    press(view, 'Delete');
    expect(view.state.doc.toString()).toBe('x[[Page|a]]y');
  });
  it('a drawn image is selected first too', () => {
    const view = mount('x ![alt](pic.png) y', { caret: 17 });
    press(view, 'Backspace');
    expect(sel(view)).toEqual([2, 17]);
    expect(view.state.doc.toString()).toBe('x ![alt](pic.png) y');
  });
  it('on a read-only document neither key selects or deletes anything', () => {
    const view = mount(DOC, { caret: 12, readOnly: true });
    press(view, 'Backspace');
    expect(sel(view)).toEqual([12, 12]);
    expect(view.state.doc.toString()).toBe(DOC);
  });
  it('a find match inside the hidden page name shows the link\'s source', () => {
    const view = mount(`${DOC}\n\nend`, { caret: 17, extra: search() });
    view.dispatch({ effects: setSearchQuery.of(new SearchQuery({ search: 'Page' })) });
    findNext(view);
    expect(sel(view)).toEqual([4, 8]);
    expect(shown(view)).toContain('x [[Page|a]] y');
    view.dispatch({ selection: { anchor: 17 } });
    expect(shown(view)).toContain('x a y');
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
    expect(on(onLinkCard.mock.calls[0][0])).toEqual({ from: 2, to: 12, source: '[[Page|a]]' });
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
    expect(on(onLinkCard.mock.calls[0][0])).toEqual({ from: 3, to: 18, source: '![alt](pic.png)' });
  });
  it('on a whole-line page embed opens the embed\'s card instead of revealing its source', () => {
    const onLinkCard = vi.fn();
    const doc = 'top\n\n  ![[Other]]\n\nend';
    const view = mount(doc, { onLinkCard, caret: 0 });
    mouseDown(view.contentDOM.querySelector('.cm-lp-embed'));
    expect(on(onLinkCard.mock.calls[0][0])).toEqual({ from: 7, to: 17, source: '![[Other]]' });
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
    expect(on(card(view))).toEqual({ from: 7, to: 17, source: '[[Page|a]]' });
    onLinkCard.mockClear();
    return { view, onLinkCard };
  };
  it('through a change above it', () => {
    const { view, onLinkCard } = open();
    view.dispatch({ changes: { from: 0, insert: 'remote ' } });
    expect(on(card(view))).toEqual({ from: 14, to: 24, source: '[[Page|a]]' });
    expect(onLinkCard).toHaveBeenLastCalledWith(card(view), view);
  });
  it('and says nothing about a change that does not move it', () => {
    const { view, onLinkCard } = open();
    const before = card(view);
    view.dispatch({ changes: { from: DOC.length, insert: ' more' } });
    view.dispatch({ selection: { anchor: 0 } });
    expect(card(view)).toBe(before);
    expect(onLinkCard).not.toHaveBeenCalled();
  });
  it('through text typed right at its edges', () => {
    const { view } = open();
    view.dispatch({ changes: [{ from: 7, insert: 'A' }, { from: 17, insert: 'B' }] });
    expect(on(card(view))).toEqual({ from: 8, to: 18, source: '[[Page|a]]' });
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

describe('one card at a time', () => {
  const DOC = '[[A]] and [[B]] end';
  it('every opening has its own id, also of the same link at the same place', () => {
    const view = mount(DOC, { onLinkCard: vi.fn() });
    requestLinkCard(view, 1);
    const first = card(view).id;
    view.dispatch({ effects: closeLinkCard.of(null) });
    requestLinkCard(view, 1);
    expect(card(view).id).not.toBe(first);
  });
  it('asking again for the link the card is on changes nothing', () => {
    const onLinkCard = vi.fn();
    const view = mount(DOC, { onLinkCard });
    requestLinkCard(view, 1);
    const before = card(view);
    expect(requestLinkCard(view, 3)).toBe(true);
    expect(card(view)).toBe(before);
    expect(onLinkCard).toHaveBeenCalledTimes(1);
  });
  it('the open card is told it is being left before another opens, and what it writes then moves the new one', () => {
    const calls = [];
    const onLinkCard = (c, view, leaving) => {
      calls.push([c && c.source, !!leaving]);
      // As the host does: the card being left writes its change, over its own range.
      if (leaving) view.dispatch({ changes: { from: c.from, to: c.to, insert: '[[Longer]]' } });
    };
    const view = mount(DOC, { onLinkCard });
    requestLinkCard(view, 1);
    expect(requestLinkCard(view, 11)).toBe(true); // [[B]], where it is before the first card writes
    expect(view.state.doc.toString()).toBe('[[Longer]] and [[B]] end');
    expect(on(card(view))).toEqual({ from: 15, to: 20, source: '[[B]]' });
    expect(calls).toEqual([['[[A]]', false], ['[[A]]', true], [null, false], ['[[B]]', false]]);
  });
});

describe('a card on text that is not a link yet', () => {
  const DOC = 'top\n\nalpha beta gamma and [[Page]] tail\nnext'; // the link is [26, 34)
  const open = (from = 11, to = 15) => {
    const onLinkCard = vi.fn();
    const view = mount(DOC, { onLinkCard });
    const ok = requestLinkWrap(view, from, to);
    return { view, onLinkCard, ok };
  };
  it('opens on the range without writing, and its model is a link to be: the text, no address', () => {
    const { view, ok } = open();
    expect(ok).toBe(true);
    expect(view.state.doc.toString()).toBe(DOC);
    expect(on(card(view))).toEqual({ from: 11, to: 15, source: 'beta', wrap: true });
    expect(cardModelOf(view.state, card(view))).toMatchObject({ kind: 'link', from: 11, to: 15, text: 'beta', url: '', pending: true });
    expect(cardModelOf(view.state, card(view)).original).toBe(undefined);
  });
  it('follows a change above it and closes when its text is changed or deleted', () => {
    const { view } = open();
    view.dispatch({ changes: { from: 0, insert: 'XX' } });
    expect(on(card(view))).toEqual({ from: 13, to: 17, source: 'beta', wrap: true });
    view.dispatch({ changes: { from: 14, to: 15, insert: 'E' } });
    expect(card(view)).toBe(null);
  });
  it.each([
    ['an empty range', 11, 11],
    ['a range across a line break', 11, DOC.length],
    ['a range that starts inside a link and ends past it', 28, 37],
    ['a range inside a link', 28, 30],
    ['a range that ends inside a link', 20, 30],
  ])('is refused for %s: no card, no change', (_name, from, to) => {
    const { view, ok, onLinkCard } = open(from, to);
    expect(ok).toBe(false);
    expect(card(view)).toBe(null);
    expect(onLinkCard).not.toHaveBeenCalled();
    expect(view.state.doc.toString()).toBe(DOC);
  });
  // A link's text cannot hold a link: wrapped, the inner one's brackets would be escaped and it would stop being one.
  it.each([
    ['a range that holds a whole wikilink and text before it', 22, 34],
    ['a range that holds a whole wikilink and text after it', 26, 37],
    ['a range around a whole Markdown link', DOC.length + 1, DOC.length + 16, `${DOC}\nsee [x](u) here`],
    ['a range around a whole image', DOC.length + 1, DOC.length + 17, `${DOC}\nsee ![x](u) here`],
  ])('is refused for %s: no card, no change', (_name, from, to, doc = DOC) => {
    const onLinkCard = vi.fn();
    const view = mount(doc, { onLinkCard });
    expect(requestLinkWrap(view, from, to)).toBe(false);
    expect(card(view)).toBe(null);
    expect(onLinkCard).not.toHaveBeenCalled();
    expect(view.state.doc.toString()).toBe(doc);
  });
  it('is allowed from a link\'s edge, the link itself left out', () => {
    expect(open(21, 26).ok).toBe(true);
    expect(open(34, 39).ok).toBe(true);
  });
  it('is checked again once an open card has written: the text it turned into a link cannot be wrapped', () => {
    const onLinkCard = vi.fn((c, view, leaving) => {
      // As the host does: the card being left (open on `beta`, an address typed) writes its link.
      if (leaving) view.dispatch({ changes: { from: c.from, to: c.to, insert: '[beta](http://u)' } });
    });
    const view = mount(DOC, { onLinkCard });
    expect(requestLinkWrap(view, 11, 15)).toBe(true);
    expect(requestLinkWrap(view, 5, 21)).toBe(false); // `alpha beta gamma`, which holds no link until the card writes
    expect(view.state.doc.toString()).toBe(DOC.replace('beta', '[beta](http://u)'));
    expect(card(view)).toBe(null);
  });
  it('is refused on a read-only document', () => {
    const view = mount(DOC, { onLinkCard: vi.fn(), readOnly: true });
    expect(requestLinkWrap(view, 11, 15)).toBe(false);
  });

  describe('in a table', () => {
    const ROW = '| one | two | a\\|b | x\\\\| y |';
    const TDOC = `Intro\n\n| A | B | C | D | E |\n| --- | --- | --- | --- | --- |\n${ROW}\n\nAfter`;
    const at = (needle) => TDOC.indexOf(needle);
    const wrap = (from, to) => {
      const onLinkCard = vi.fn();
      const view = mount(TDOC, { onLinkCard });
      return { view, onLinkCard, ok: requestLinkWrap(view, from, to) };
    };
    it.each([
      ['two cells of a row', at('one'), at('two') + 3],
      ['a cell and the pipe after it', at('one'), at('one') + 5],
      ['a pipe whose backslash is left out', at('a\\|b') + 2, at('a\\|b') + 4],
      ['a pipe after an escaped backslash (a cell boundary)', at('x\\\\|'), at('x\\\\|') + 5],
      ['a whole row', at('| one'), at('| one') + ROW.length],
    ])('is refused across a cell boundary (%s): no card, no change', (_name, from, to) => {
      const { view, ok, onLinkCard } = wrap(from, to);
      expect(ok).toBe(false);
      expect(card(view)).toBe(null);
      expect(onLinkCard).not.toHaveBeenCalled();
      expect(view.state.doc.toString()).toBe(TDOC);
    });
    it('is allowed inside one cell, an escaped pipe included', () => {
      expect(wrap(at('one'), at('one') + 3).ok).toBe(true);
      const { view, ok } = wrap(at('a\\|b'), at('a\\|b') + 4);
      expect(ok).toBe(true);
      expect(cardModelOf(view.state, card(view))).toMatchObject({ text: 'a|b', cell: true });
    });
  });
  it('a pipe outside a table is ordinary text', () => {
    const doc = 'left | right';
    const view = mount(doc, { onLinkCard: vi.fn() });
    expect(requestLinkWrap(view, 0, doc.length)).toBe(true);
  });
});
