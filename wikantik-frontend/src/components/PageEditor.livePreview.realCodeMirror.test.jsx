/**
 * Live preview mode on the REAL CodeMirror editor: the toolbar button and Mod-e toggle it (remembered per
 * browser), the document, undo history and saved text are untouched by the mode, and the one live edit
 * (a task checkbox) goes through the view.
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
import { mountRealEditor, saveAndExpectVisibleText, placeCaret, typeAtCaret, docOf, caretOf, until, flush } from '../test/realEditorHarness';

const INITIAL = '# Title\n\n- [ ] task **b**\n\nend';

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  localStorage.setItem('wikantik.editor.mode', 'source'); // most cases start from source; live ones set it explicitly
  window.matchMedia = vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} }));
  useToast.mockReturnValue({ success: vi.fn(), error: vi.fn(), info: vi.fn() });
  useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'alice', roles: ['Admin'] } });
  useDraft.mockReturnValue({ draft: null, saveDraft: vi.fn(), clearDraft: vi.fn() });
  useAttachments.mockImplementation(() => ({ list: [], uploadAttachment: vi.fn(), renameAttachment: vi.fn(), deleteAttachment: vi.fn() }));
  api.getPage.mockResolvedValue({ content: INITIAL, metadata: {}, version: 1, markupSyntax: 'markdown' });
  api.savePage.mockRejectedValue(new Error('probe'));
});

afterEach(async () => {
  cleanup();
  await act(async () => { await Promise.resolve(); });
});

const isLive = () => document.querySelector('.cm-editor.cm-live-preview') !== null;

describe('rich mode toggle', () => {
  it('the toolbar button toggles live mode and remembers it', async () => {
    const view = await mountRealEditor(PageEditor, NavigationGuardProvider, INITIAL);
    expect(isLive()).toBe(false);
    fireEvent.click(screen.getByRole('radio', { name: 'Rich' }));
    await until(isLive);
    expect(localStorage.getItem('wikantik.editor.mode')).toBe('live');
    expect(docOf(view)).toBe(INITIAL);
  });

  it('Mod-e toggles live mode from anywhere on the page', async () => {
    await mountRealEditor(PageEditor, NavigationGuardProvider, INITIAL);
    fireEvent.keyDown(window, { key: 'e', code: 'KeyE', ctrlKey: true });
    await until(isLive);
    fireEvent.keyDown(window, { key: 'e', code: 'KeyE', ctrlKey: true });
    await until(() => !isLive());
  });

  it('undo history survives a mode toggle', async () => {
    const view = await mountRealEditor(PageEditor, NavigationGuardProvider, INITIAL);
    placeCaret(view, INITIAL.length);
    typeAtCaret(view, 'Z');
    expect(docOf(view)).toBe(`${INITIAL}Z`);
    fireEvent.keyDown(window, { key: 'e', code: 'KeyE', ctrlKey: true });
    await until(isLive);
    expect(docOf(view)).toBe(`${INITIAL}Z`);
    fireEvent.keyDown(window, { key: 'e', code: 'KeyE', ctrlKey: true });
    await until(() => !isLive());
    act(() => { undo(view); });
    expect(docOf(view)).toBe(INITIAL);
  });

  it('saving in live mode sends exactly the source text, including a checkbox toggle', async () => {
    localStorage.setItem('wikantik.editor.mode', 'live');
    const view = await mountRealEditor(PageEditor, NavigationGuardProvider, INITIAL);
    placeCaret(view, INITIAL.length);
    await until(() => document.querySelector('input.cm-lp-task') !== null);
    saveAndExpectVisibleText(api, view);
    expect(api.savePage.mock.calls[0][1].content).toContain('- [ ] task **b**');
    fireEvent.mouseDown(document.querySelector('input.cm-lp-task'));
    expect(docOf(view)).toBe('# Title\n\n- [x] task **b**\n\nend');
    await flush(); // let the rejected first probe settle so the next Ctrl+S is not ignored
    const payload = saveAndExpectVisibleText(api, view);
    expect(payload.content).toContain('- [x] task **b**');
  });

  it('one undo reverts a checkbox toggle without losing text typed just before it (inside the history window)', async () => {
    localStorage.setItem('wikantik.editor.mode', 'live');
    const view = await mountRealEditor(PageEditor, NavigationGuardProvider, INITIAL);
    placeCaret(view, INITIAL.length);
    typeAtCaret(view, 'Z');
    await until(() => document.querySelector('input.cm-lp-task') !== null);
    fireEvent.mouseDown(document.querySelector('input.cm-lp-task')); // well within the 500 ms grouping window
    expect(docOf(view)).toBe('# Title\n\n- [x] task **b**\n\nendZ');
    act(() => { undo(view); });
    expect(docOf(view)).toBe(`${INITIAL}Z`);
  });

  it('a legacy-wiki page is never live-rendered, and the Rich option is disabled with an explanation', async () => {
    localStorage.setItem('wikantik.editor.mode', 'live');
    api.getPage.mockResolvedValue({ content: INITIAL, metadata: {}, version: 1, markupSyntax: 'wiki' });
    await mountRealEditor(PageEditor, NavigationGuardProvider, INITIAL);
    expect(isLive()).toBe(false);
    const live = screen.getByRole('radio', { name: 'Rich' });
    expect(live).toBeDisabled();
    expect(live).toHaveAttribute('title', 'Rich editing is available for Markdown pages');
  });

  it('Mod-e is ignored while a modal is open, on key auto-repeat, and during IME composition', async () => {
    await mountRealEditor(PageEditor, NavigationGuardProvider, INITIAL);
    const dialog = document.createElement('div');
    dialog.setAttribute('role', 'dialog');
    document.body.appendChild(dialog);
    fireEvent.keyDown(window, { key: 'e', code: 'KeyE', ctrlKey: true });
    dialog.remove();
    fireEvent.keyDown(window, { key: 'e', code: 'KeyE', ctrlKey: true, repeat: true });
    fireEvent.keyDown(window, { key: 'e', code: 'KeyE', ctrlKey: true, isComposing: true });
    await flush();
    expect(isLive()).toBe(false);
    expect(localStorage.getItem('wikantik.editor.mode')).toBe('source'); // unchanged: no toggle happened
    fireEvent.keyDown(window, { key: 'e', code: 'KeyE', ctrlKey: true });
    await until(isLive);
  });
});

describe('rich mode: block markers stay hidden under the caret', () => {
  async function mountRich(text) {
    localStorage.setItem('wikantik.editor.mode', 'live');
    api.getPage.mockResolvedValue({ content: text, metadata: {}, version: 1, markupSyntax: 'markdown' });
    const view = await mountRealEditor(PageEditor, NavigationGuardProvider, text);
    await until(isLive);
    return view;
  }
  async function mountSource(text) {
    api.getPage.mockResolvedValue({ content: text, metadata: {}, version: 1, markupSyntax: 'markdown' });
    return mountRealEditor(PageEditor, NavigationGuardProvider, text);
  }
  const press = (view, key) => {
    act(() => { view.contentDOM.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true })); });
  };
  const lineText = (view, n) => view.contentDOM.querySelectorAll('.cm-line')[n - 1].textContent;

  it('the heading marker is not shown with the caret on its line, and typing at the visual start goes after it', async () => {
    const view = await mountRich('## Title\n\nend');
    placeCaret(view, 0);
    await until(() => lineText(view, 1) === 'Title');
    expect(caretOf(view)).toBe(3);
    typeAtCaret(view, 'x');
    expect(docOf(view)).toBe('## xTitle\n\nend');
    expect(lineText(view, 1)).toBe('xTitle');
  });

  it('Backspace at the visual start of a heading removes the marker and nothing else', async () => {
    const view = await mountRich('## Title\n\nend');
    placeCaret(view, 0);
    press(view, 'Backspace');
    expect(docOf(view)).toBe('Title\n\nend');
    expect(caretOf(view)).toBe(0);
  });

  it.each([['- item', 'item'], ['1. item', 'item'], ['- [ ] item', 'item'], ['> item', 'item'], ['> - item', '> item']])(
    'Backspace at the start of the text of %j leaves %j', async (line, after) => {
      const text = `Para\n\n${line}\n\nend`;
      const view = await mountRich(text);
      placeCaret(view, 6);
      expect(caretOf(view)).toBe(6 + line.length - 'item'.length);
      press(view, 'Backspace');
      expect(docOf(view)).toBe(`Para\n\n${after}\n\nend`);
    },
  );

  it('Enter at the end of a list item continues the list, and Enter on the empty item ends it', async () => {
    const view = await mountRich('- a');
    placeCaret(view, 3);
    press(view, 'Enter');
    expect(docOf(view)).toBe('- a\n- ');
    expect(caretOf(view)).toBe(6);
    // The Markdown keymap ends a tight list in two steps (it first sets the empty item apart), in Source mode too.
    press(view, 'Enter');
    press(view, 'Enter');
    expect(docOf(view)).toBe('- a\n\n');
  });

  it('Enter in a list behaves exactly as in Source mode', async () => {
    const run = async (mount) => {
      const view = await mount('1. a\n\nend');
      placeCaret(view, 4);
      const steps = [];
      for (let i = 0; i < 3; i += 1) { press(view, 'Enter'); steps.push(docOf(view)); }
      cleanup();
      return steps;
    };
    const rich = await run(mountRich);
    localStorage.setItem('wikantik.editor.mode', 'source');
    expect(rich).toEqual(await run(mountSource));
    expect(rich[0]).toBe('1. a\n2. \n\nend');
  });

  it('a checkbox is still clickable with the caret on its line', async () => {
    const view = await mountRich('- [ ] task\n\nend');
    placeCaret(view, 8);
    await until(() => document.querySelector('input.cm-lp-task') !== null);
    fireEvent.mouseDown(document.querySelector('input.cm-lp-task'));
    expect(docOf(view)).toBe('- [x] task\n\nend');
  });

  // Review Focus 3: a selection from mid-paragraph into a heading line deletes exactly what a plain string would lose.
  it.each([['Delete', 9], ['Delete', 10], ['Delete', 12], ['Delete', 14], ['Backspace', 10], ['Backspace', 14]])(
    '%s over a selection from mid-paragraph to offset %i (in the next heading line) removes exactly the selected text', async (key, to) => {
      const text = 'Para one\n## Title\n\nend';
      const view = await mountRich(text);
      act(() => { view.dispatch({ selection: { anchor: 5, head: to } }); });
      press(view, key);
      expect(docOf(view)).toBe(text.slice(0, 5) + text.slice(to));
    },
  );

  it('moving the caret through every line start and end of a page of markers changes nothing', async () => {
    const text = '# Title\n\n- one\n  - nested\n1. first\n- [ ] task\n> quote\n> - [x] deep\n\nend';
    const view = await mountRich(text);
    const changed = vi.fn();
    api.savePage.mockClear();
    for (let n = 1; n <= view.state.doc.lines; n += 1) {
      const line = view.state.doc.line(n);
      for (const at of [line.from, line.to]) {
        placeCaret(view, at);
        if (docOf(view) !== text) changed(at);
      }
    }
    expect(changed).not.toHaveBeenCalled();
    expect(saveAndExpectVisibleText(api, view).content).toBe(text);
  });
});
