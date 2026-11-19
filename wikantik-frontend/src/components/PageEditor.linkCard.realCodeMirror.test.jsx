/**
 * The link card on the REAL CodeMirror editor in Rich mode: a link stays a chip with the caret beside it, a click (or
 * Mod-K) opens its card, and what the card writes is one change over the link alone — one undo step — also when the
 * document changed under the open card.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
// eslint-disable-next-line testing-library/no-manual-cleanup -- flush async state between tests
import { act, cleanup, fireEvent, screen } from '@testing-library/react';

vi.mock('../api/client', () => ({
  api: {
    getProfile: vi.fn().mockResolvedValue({ editorMode: null }),
    updateProfile: vi.fn().mockResolvedValue({}),
    getPage: vi.fn(),
    getBacklinks: vi.fn(() => Promise.resolve({ backlinks: [] })),
    savePage: vi.fn(),
    listAttachments: vi.fn(() => Promise.resolve({ attachments: [] })),
    listPages: vi.fn(() => Promise.resolve({ pages: [] })),
    scanMentions: vi.fn(() => Promise.resolve({ mentions: [] })),
    getFrontmatterSchema: vi.fn(() => Promise.resolve({ fields: [] })),
    listTags: vi.fn(() => Promise.resolve({ tags: [] })),
    validateFrontmatter: vi.fn(() => Promise.resolve({ metadata: {}, violations: [] })),
    search: vi.fn(() => Promise.resolve({ results: [] })),
  },
}));
vi.mock('../hooks/usePagePreview', () => ({ loadPreview: vi.fn(), evictPreview: vi.fn() }));
vi.mock('../hooks/useAuth', () => ({ useAuth: vi.fn() }));
vi.mock('../hooks/useDraft', () => ({ useDraft: vi.fn() }));
vi.mock('../hooks/useAttachments', () => ({ useAttachments: vi.fn() }));
vi.mock('../hooks/useEditorDrop', () => ({ useEditorDrop: vi.fn() }));
vi.mock('../hooks/useToast', () => ({ useToast: vi.fn() }));

import PageEditor from './PageEditor';
import { NavigationGuardProvider } from '../navigation/NavigationGuardProvider';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';
import { useDraft } from '../hooks/useDraft';
import { useAttachments } from '../hooks/useAttachments';
import { useToast } from '../hooks/useToast';
import { undo } from '@codemirror/commands';
import '../test/asyncSelectionChange'; // a command's selection + focus: happy-dom would fire selectionchange into the update
import { EditorView } from '@codemirror/view';
import { StateEffect, EditorState } from '@codemirror/state';
import { mountRealEditor, placeCaret, typeAtCaret, docOf, caretOf, until } from '../test/realEditorHarness';

const INITIAL = 'top\n\nsee [[P|a]] here\n\nend';
const LINK = INITIAL.indexOf('[[');   // the link is [LINK, LINK_END)
const LINK_END = LINK + '[[P|a]]'.length;

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  localStorage.setItem('wikantik.editor.mode', 'live');
  window.matchMedia = vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} }));
  useToast.mockReturnValue({ success: vi.fn(), error: vi.fn(), info: vi.fn() });
  useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'alice', roles: ['Admin'] } });
  useDraft.mockReturnValue({ draft: null, saveDraft: vi.fn(), clearDraft: vi.fn() });
  useAttachments.mockImplementation(() => ({ list: [], uploadAttachment: vi.fn(), renameAttachment: vi.fn(), deleteAttachment: vi.fn() }));
  api.getPage.mockResolvedValue({ content: INITIAL, metadata: {}, version: 1, markupSyntax: 'markdown' });
  api.savePage.mockRejectedValue(new Error('probe'));
});

afterEach(async () => {
  // A command focuses the editor, which leaves a DOM selection inside it; happy-dom keeps that selection after the
  // editor is gone and then fires `selectionchange` synchronously into the next test's editor, mid-update.
  document.getSelection()?.removeAllRanges();
  cleanup();
  await act(async () => { await Promise.resolve(); });
});

const isLive = () => document.querySelector('.cm-editor.cm-live-preview') !== null;
async function mountRich(text = INITIAL) {
  api.getPage.mockResolvedValue({ content: text, metadata: {}, version: 1, markupSyntax: 'markdown' });
  const view = await mountRealEditor(PageEditor, NavigationGuardProvider, text);
  await until(isLive);
  return view;
}
const shownText = (view) => view.contentDOM.textContent;
const chip = (view) => view.contentDOM.querySelector('.cm-lp-linkchip');
const card = () => screen.queryByRole('dialog', { name: 'Edit link' });
const field = (name) => screen.getByLabelText(name);
function clickChip(view) {
  act(() => { chip(view).dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, button: 0 })); });
}
const edit = (name, value) => fireEvent.change(field(name), { target: { value } });
const enter = (name) => fireEvent.keyDown(field(name), { key: 'Enter' });
const modK = (view) => act(() => { view.contentDOM.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', code: 'KeyK', ctrlKey: true, bubbles: true, cancelable: true })); });
/** Every document change from here on, as `[from, to, inserted]` in the coordinates of the document it changed. */
function recordChanges(view) {
  const seen = [];
  act(() => {
    view.dispatch({ effects: StateEffect.appendConfig.of(EditorView.updateListener.of((u) => {
      for (const tr of u.transactions) {
        if (!tr.docChanged) continue;
        const parts = [];
        tr.changes.iterChanges((fromA, toA, _fromB, _toB, inserted) => parts.push([fromA, toA, inserted.toString()]));
        seen.push(parts);
      }
    })) });
  });
  return seen;
}

describe('a link in Rich mode', () => {
  it('is still a chip with the caret right beside it: no [[ is shown', async () => {
    const view = await mountRich();
    placeCaret(view, LINK_END);
    expect(shownText(view)).toContain('see a here');
    expect(shownText(view)).not.toContain('[[');
    placeCaret(view, LINK);
    expect(shownText(view)).not.toContain('[[');
    expect(docOf(view)).toBe(INITIAL);
  });

  it('a click on it opens the card with the link\'s fields, and the caret stays where it was', async () => {
    const view = await mountRich();
    placeCaret(view, 0);
    clickChip(view);
    expect(card()).not.toBe(null);
    expect(field('Page').value).toBe('P');
    expect(field('Text').value).toBe('a');
    expect(caretOf(view)).toBe(0);
    expect(docOf(view)).toBe(INITIAL);
  });
});

describe('committing the card', () => {
  it('a new page name is exactly one change, over the link alone, and one undo restores the text', async () => {
    const view = await mountRich();
    clickChip(view);
    const changes = recordChanges(view);
    edit('Page', 'Q');
    enter('Page');
    expect(docOf(view)).toBe(INITIAL.replace('[[P|a]]', '[[Q|a]]'));
    expect(changes).toEqual([[[LINK, LINK_END, '[[Q|a]]']]]);
    expect(card()).toBe(null);
    // The focus is back in the text, the caret after the link.
    expect(caretOf(view)).toBe(LINK_END);
    expect(view.hasFocus).toBe(true);
    act(() => { undo(view); });
    expect(docOf(view)).toBe(INITIAL);
    expect(changes).toHaveLength(2);
  });

  it('typing before the commit does not merge into its undo step', async () => {
    const view = await mountRich();
    placeCaret(view, INITIAL.length);
    typeAtCaret(view, 'Z');
    clickChip(view);
    edit('Text', 'b');
    enter('Text');
    expect(docOf(view)).toBe(`${INITIAL.replace('|a]]', '|b]]')}Z`);
    act(() => { undo(view); });
    expect(docOf(view)).toBe(`${INITIAL}Z`);
  });

  it('a change earlier in the document while the card is open does not misplace the commit', async () => {
    const view = await mountRich();
    clickChip(view);
    act(() => { view.dispatch({ changes: { from: 0, insert: 'a collaborator wrote this\n' } }); }); // as a peer's edit arrives
    expect(card()).not.toBe(null);
    const changes = recordChanges(view);
    edit('Page', 'Q');
    enter('Page');
    const moved = 'a collaborator wrote this\n'.length;
    expect(docOf(view)).toBe(`a collaborator wrote this\n${INITIAL.replace('[[P|a]]', '[[Q|a]]')}`);
    expect(changes).toEqual([[[LINK + moved, LINK_END + moved, '[[Q|a]]']]]);
  });

  it('a change to the link itself while the card is open closes it, and nothing of the card is written', async () => {
    const view = await mountRich();
    clickChip(view);
    edit('Page', 'Q');
    act(() => { view.dispatch({ changes: { from: LINK + 2, to: LINK + 3, insert: 'Other' } }); });
    expect(card()).toBe(null);
    expect(docOf(view)).toBe(INITIAL.replace('[[P|a]]', '[[Other|a]]'));
  });

  it('Enter with nothing edited, and Escape after an edit, change nothing', async () => {
    const view = await mountRich();
    const changes = recordChanges(view);
    clickChip(view);
    enter('Page');
    expect(card()).toBe(null);
    clickChip(view);
    edit('Text', 'never written');
    fireEvent.keyDown(field('Text'), { key: 'Escape' });
    expect(card()).toBe(null);
    expect(docOf(view)).toBe(INITIAL);
    expect(changes).toEqual([]);
    expect(caretOf(view)).toBe(LINK_END);
    expect(view.hasFocus).toBe(true);
  });

  it('Remove unwraps the link in one change and keeps its text', async () => {
    const view = await mountRich();
    clickChip(view);
    const changes = recordChanges(view);
    fireEvent.click(screen.getByRole('button', { name: 'Remove' }));
    expect(docOf(view)).toBe(INITIAL.replace('[[P|a]]', 'a'));
    expect(changes).toEqual([[[LINK, LINK_END, 'a']]]);
    expect(caretOf(view)).toBe(LINK + 1);
    act(() => { undo(view); });
    expect(docOf(view)).toBe(INITIAL);
  });

  it('Open follows the link in a new tab and writes nothing', async () => {
    const view = await mountRich();
    const opened = vi.spyOn(window, 'open').mockImplementation(() => null);
    clickChip(view);
    fireEvent.click(screen.getByRole('button', { name: 'Open' }));
    expect(opened).toHaveBeenCalledWith('/wiki/P', '_blank', 'noopener');
    expect(docOf(view)).toBe(INITIAL);
    opened.mockRestore();
  });

  it('a commit made by clicking elsewhere leaves the caret where the click put it', async () => {
    const view = await mountRich();
    clickChip(view);
    edit('Text', 'b');
    placeCaret(view, 1); // the click in the text lands first, then the card loses the focus
    fireEvent.blur(field('Text'), { relatedTarget: view.contentDOM });
    expect(docOf(view)).toBe(INITIAL.replace('|a]]', '|b]]'));
    expect(caretOf(view)).toBe(1);
    expect(card()).toBe(null);
  });

  it('the mode switched to Source from outside the card (the toolbar, Mod-E) closes it without writing', async () => {
    const view = await mountRich();
    clickChip(view);
    edit('Text', 'b');
    fireEvent.click(screen.getByRole('radio', { name: 'Source' })); // a test click moves no focus: the card is not left
    await until(() => !isLive());
    expect(card()).toBe(null);
    expect(docOf(view)).toBe(INITIAL);
  });
});

describe('saving with the card open (Ctrl/Cmd-S with the focus in a field of the card)', () => {
  const saveKey = (el) => fireEvent.keyDown(el, { key: 's', ctrlKey: true });

  it('writes what the card holds first: the save carries the edit, the card closes and the focus is back in the text', async () => {
    const view = await mountRich();
    clickChip(view);
    edit('Page', 'Q');
    await act(async () => { saveKey(field('Page')); });
    const wanted = INITIAL.replace('[[P|a]]', '[[Q|a]]');
    expect(docOf(view)).toBe(wanted);
    expect(api.savePage).toHaveBeenCalledTimes(1);
    expect(api.savePage.mock.calls[0][1].content).toBe(wanted);
    expect(card()).toBe(null);
    expect(view.hasFocus).toBe(true);
  });

  it('with nothing edited in the card it just saves', async () => {
    const view = await mountRich();
    clickChip(view);
    await act(async () => { saveKey(field('Page')); });
    expect(api.savePage).toHaveBeenCalledTimes(1);
    expect(api.savePage.mock.calls[0][1].content).toBe(INITIAL);
    expect(docOf(view)).toBe(INITIAL);
  });

  it('a value the link cannot carry is not saved around: the card stays with the reason, and nothing is sent', async () => {
    const view = await mountRich();
    clickChip(view);
    edit('Page', 'A|B');
    await act(async () => { saveKey(field('Page')); });
    expect(api.savePage).not.toHaveBeenCalled();
    expect(card()).not.toBe(null);
    expect(screen.getByRole('alert').textContent).toMatch(/cannot contain \|/);
    expect(docOf(view)).toBe(INITIAL);
  });
});

describe('Insert link (Mod-K) in Rich mode', () => {
  it('with the caret on a link opens that link\'s card', async () => {
    const view = await mountRich();
    placeCaret(view, LINK_END);
    modK(view);
    expect(card()).not.toBe(null);
    expect(field('Page').value).toBe('P');
    expect(docOf(view)).toBe(INITIAL);
  });

  const select = (view, anchor, head) => act(() => { view.dispatch({ selection: { anchor, head } }); });

  it('with a selection opens the card on that text and writes nothing until it is committed: then one change', async () => {
    const view = await mountRich('alpha beta gamma');
    const changes = recordChanges(view);
    select(view, 6, 10);
    modK(view);
    expect(docOf(view)).toBe('alpha beta gamma');
    expect(card()).not.toBe(null);
    expect(document.activeElement).toBe(field('URL'));
    expect(field('Text').value).toBe('beta');
    edit('URL', 'https://e.org');
    expect(changes).toEqual([]);
    enter('URL');
    expect(docOf(view)).toBe('alpha [beta](https://e.org) gamma');
    expect(changes).toEqual([[[6, 10, '[beta](https://e.org)']]]);
    expect(card()).toBe(null);
    expect(caretOf(view)).toBe(27);
    expect(view.hasFocus).toBe(true);
    act(() => { undo(view); });
    expect(docOf(view)).toBe('alpha beta gamma'); // one step: nothing else was put in the history
  });

  it('with a selection: the edited text is written by the link\'s rules (brackets escaped)', async () => {
    const view = await mountRich('alpha beta gamma');
    select(view, 6, 10);
    modK(view);
    edit('Text', 'be[t]a');
    edit('URL', 'a b');
    enter('Text');
    expect(docOf(view)).toBe('alpha [be\\[t\\]a](<a b>) gamma');
  });

  it.each([
    ['Escape', () => fireEvent.keyDown(field('URL'), { key: 'Escape' })],
    ['Enter with no address', () => { edit('Text', 'other'); enter('Text'); }],
    ['the focus leaving with no address', () => fireEvent.blur(field('URL'), { relatedTarget: document.body })],
  ])('with a selection: %s closes the card and the document is untouched', async (_name, end) => {
    const view = await mountRich('alpha beta gamma');
    const changes = recordChanges(view);
    select(view, 6, 10);
    modK(view);
    end();
    expect(card()).toBe(null);
    expect(docOf(view)).toBe('alpha beta gamma');
    expect(changes).toEqual([]);
  });

  it('with a selection: the selected text changed by someone else closes the card, and nothing of it is written', async () => {
    const view = await mountRich('alpha beta gamma');
    select(view, 6, 10);
    modK(view);
    edit('URL', 'https://e.org');
    act(() => { view.dispatch({ changes: { from: 0, insert: '> ' } }); }); // above it: the card stays
    expect(card()).not.toBe(null);
    act(() => { view.dispatch({ changes: { from: 9, to: 10, insert: 'E' } }); });
    expect(card()).toBe(null);
    expect(docOf(view)).toBe('> alpha bEta gamma');
  });

  it('with a selection: switching to Source closes the card and writes nothing', async () => {
    const view = await mountRich('alpha beta gamma');
    select(view, 6, 10);
    modK(view);
    edit('URL', 'https://e.org');
    fireEvent.click(screen.getByRole('radio', { name: 'Source' }));
    await until(() => !isLive());
    expect(card()).toBe(null);
    expect(docOf(view)).toBe('alpha beta gamma');
  });

  it.each([
    ['across a line break', 'alpha\nbeta', 0, 10],
    ['from inside a link to past it', 'see [[Page|a]] here', 7, 17],
    ['ending inside a link', 'see [[Page|a]] here', 0, 7],
  ])('with a selection %s does nothing: no card, no change', async (_name, text, anchor, head) => {
    const view = await mountRich(text);
    const changes = recordChanges(view);
    select(view, anchor, head);
    modK(view);
    expect(card()).toBe(null);
    expect(docOf(view)).toBe(text);
    expect(changes).toEqual([]);
  });

  it.each([['a caret', 6, 6], ['a selection', 0, 5], ['the caret on a link', 10, 10]])('on a read-only document, with %s, does nothing', async (_name, anchor, head) => {
    const text = 'alpha [[P]] end';
    const view = await mountRich(text);
    act(() => { view.dispatch({ effects: StateEffect.appendConfig.of(EditorState.readOnly.of(true)) }); }); // as a frozen shared draft
    const changes = recordChanges(view);
    select(view, anchor, head);
    modK(view);
    expect(card()).toBe(null);
    expect(docOf(view)).toBe(text);
    expect(changes).toEqual([]);
  });

  it('with nothing selected writes [[]] with the caret between the brackets, for the page completion', async () => {
    const view = await mountRich('alpha ');
    placeCaret(view, 6);
    modK(view);
    expect(docOf(view)).toBe('alpha [[]]');
    expect(caretOf(view)).toBe(8);
    expect(card()).toBe(null);
    typeAtCaret(view, 'P');
    expect(docOf(view)).toBe('alpha [[P]]');
    expect(caretOf(view)).toBe(9); // the link being typed keeps the caret
  });
});

describe('two links, one card at a time', () => {
  const TWO = 'see [[A|x]] and [[B|y]] here';
  const chips = (view) => [...view.contentDOM.querySelectorAll('.cm-lp-linkchip')];
  const clickNth = (view, n) => act(() => { chips(view)[n].dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, button: 0 })); });

  it('clicking another chip commits the open card\'s edit to its own link, once, and opens the other with its values', async () => {
    const view = await mountRich(TWO);
    clickNth(view, 0);
    const changes = recordChanges(view);
    edit('Text', 'longer text');
    clickNth(view, 1);
    expect(docOf(view)).toBe('see [[A|longer text]] and [[B|y]] here');
    expect(changes).toEqual([[[4, 11, '[[A|longer text]]']]]);
    expect(field('Page').value).toBe('B');
    expect(field('Text').value).toBe('y');
    // The second card is on the second link, where it is now.
    edit('Page', 'C');
    enter('Page');
    expect(docOf(view)).toBe('see [[A|longer text]] and [[C|y]] here');
    expect(changes).toHaveLength(2);
  });

  it('clicking another chip with a refused value in the open card discards it', async () => {
    const view = await mountRich(TWO);
    clickNth(view, 1);
    edit('Text', 'a]b');
    clickNth(view, 0);
    expect(docOf(view)).toBe(TWO);
    expect(field('Page').value).toBe('A');
    expect(screen.queryByRole('alert')).toBe(null);
  });

  it('clicking another chip with nothing edited just moves the card', async () => {
    const view = await mountRich(TWO);
    const changes = recordChanges(view);
    clickNth(view, 0);
    clickNth(view, 1);
    expect(field('Page').value).toBe('B');
    expect(changes).toEqual([]);
    expect(screen.getAllByRole('dialog', { name: 'Edit link' })).toHaveLength(1);
  });
});

describe('Insert link (Mod-K) in Source mode', () => {
  it('inserts [text](url) as before, with no card', async () => {
    localStorage.setItem('wikantik.editor.mode', 'source');
    api.getPage.mockResolvedValue({ content: 'alpha ', metadata: {}, version: 1, markupSyntax: 'markdown' });
    const view = await mountRealEditor(PageEditor, NavigationGuardProvider, 'alpha ');
    placeCaret(view, 6);
    modK(view);
    expect(docOf(view)).toBe('alpha [text](url)');
    expect(card()).toBe(null);
  });
});
