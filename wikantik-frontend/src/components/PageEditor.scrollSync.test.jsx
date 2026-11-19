/**
 * PageEditor's editor <-> preview sync, block by block, and the preview following the caret. happy-dom lays nothing
 * out, so both panes' geometry is stubbed: the editor's through its handle (scrollGeometry), the preview's through
 * each block's rect, keyed by its source line.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
// eslint-disable-next-line testing-library/no-manual-cleanup -- flush async state between tests
import { render, screen, fireEvent, act, cleanup } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

// The editor: 20 px a line, a 100 px window over 300 px of content. `editor.scrollTop` and `editor.caret` are set
// by each test; `fire()` reports a view change as CodeMirror would (scroll, caret move, edit).
const editor = vi.hoisted(() => ({ scrollTop: 0, caret: { line: 1, col: 1, top: 0 }, onViewChange: null, setScrollTop: null, lineTops: 0, jumps: [] }));
vi.mock('./CodeEditor', async () => {
  const React = (await vi.importActual('react')).default;
  return {
    default: React.forwardRef(function CodeEditorStub({ value, onChange, onViewChange }, ref) {
      editor.onViewChange = onViewChange;
      editor.onChange = onChange;
      React.useImperativeHandle(ref, () => ({
        getSelection: () => ({ selStart: 0, selEnd: 0 }),
        focus() {}, getViewport: () => null, scrollToLine() {}, getScrollerRect: () => null, jumpToLineAligned: (...args) => editor.jumps.push(args),
        getText: () => value,
        getCursor: () => ({ line: editor.caret.line, col: editor.caret.col, selectionText: '' }),
        scrollGeometry: () => ({
          scrollTop: editor.scrollTop, maxScroll: 200, height: 300,
          lineTop: (n) => { editor.lineTops += 1; return (n - 1) * 20; },
          caret: { line: editor.caret.line, top: editor.caret.top },
        }),
        setScrollTop: (top) => editor.setScrollTop(top),
      }));
      return React.createElement('pre', { 'data-testid': 'body-value' }, value);
    }),
  };
});

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

// Lines 1, 3, 5, 7 are blocks; the paragraph on line 5 holds a 500 px tall picture. In the editor they start at
// 0, 40, 80, 120; in the preview at 0, 50, 100, 600 (800 px of content in a 200 px window).
const BODY = '# Title\n\npara one\n\n![a tall picture](a.png)\n\npara two';
const FIRST_TOPS = { 1: 0, 3: 50, 5: 100, 7: 600 };
let PREVIEW_TOPS = FIRST_TOPS;

let originalRect;
const previewEl = () => document.querySelector('.editor-preview');
// Animation frames are run by hand, in the browser's order, so no test depends on the machine's speed.
let frames = [];
let rafSpies = [];
/** Run the frames due now (not the ones they ask for). */
const runFrame = () => act(() => { for (const f of frames.splice(0)) f.cb(performance.now()); });
/** Let the sync run to rest: the frames due now, and the ones they ask for. */
const frame = async () => { for (let i = 0; i < 4 && frames.length; i++) runFrame(); };

async function mountEditor() {
  render(
    <MemoryRouter initialEntries={['/edit/Existing']}>
      <NavigationGuardProvider>
        <Routes><Route path="/edit/:name" element={<PageEditor />} /></Routes>
      </NavigationGuardProvider>
    </MemoryRouter>,
  );
  await screen.findByTestId('body-value');
  await screen.findByText('para two');
  const preview = previewEl();
  Object.defineProperty(preview, 'scrollHeight', { value: 800, configurable: true });
  Object.defineProperty(preview, 'clientHeight', { value: 200, configurable: true });
  Object.defineProperty(preview, 'clientWidth', { value: 500, configurable: true });
  return preview;
}

/** Report a view change, then let the sync's frames run. */
async function fire() {
  act(() => { editor.onViewChange(); });
  await frame();
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  localStorage.setItem('wikantik.editor.mode', 'source');
  window.matchMedia = vi.fn(() => ({ matches: true, addEventListener() {}, removeEventListener() {} }));
  useToast.mockReturnValue({ success: vi.fn(), error: vi.fn(), info: vi.fn() });
  useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'alice', roles: ['Admin'] } });
  useDraft.mockReturnValue({ draft: null, saveDraft: vi.fn(), clearDraft: vi.fn() });
  useAttachments.mockImplementation(() => ({ list: [], uploadAttachment: vi.fn(), renameAttachment: vi.fn(), deleteAttachment: vi.fn() }));
  api.getPage.mockResolvedValue({ content: BODY, metadata: {}, version: 1, markupSyntax: 'markdown' });
  editor.scrollTop = 0;
  editor.caret = { line: 1, col: 1, top: 0 };
  PREVIEW_TOPS = FIRST_TOPS;
  editor.setScrollTop = vi.fn((top) => { editor.scrollTop = top; return top; });
  editor.lineTops = 0;
  editor.jumps = [];
  frames = [];
  let nextId = 0;
  rafSpies = [
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((cb) => { nextId += 1; frames.push({ id: nextId, cb }); return nextId; }),
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation((id) => { frames = frames.filter((f) => f.id !== id); }),
  ];
  originalRect = Element.prototype.getBoundingClientRect;
  // The preview scroller's top is the viewport's top; a block's top is its place in the preview, less the scroll.
  Element.prototype.getBoundingClientRect = function rect() {
    if (this.classList?.contains('editor-preview')) return { top: 0, bottom: 200, left: 0, right: 500, width: 500, height: 200 };
    const line = this.getAttribute?.('data-line');
    const top = (PREVIEW_TOPS[line] ?? 0) - (previewEl()?.scrollTop ?? 0);
    return { top, bottom: top + 20, left: 0, right: 500, width: 500, height: 20 };
  };
});

afterEach(() => {
  Element.prototype.getBoundingClientRect = originalRect;
  cleanup();
  for (const spy of rafSpies) spy.mockRestore();
});

describe('the preview follows the editor block by block', () => {
  it('puts the block at the top of the editor at the top of the preview', async () => {
    const preview = await mountEditor();
    await fire(); // the first report: the caret is new
    editor.scrollTop = 40; // line 3's top
    await fire();
    expect(preview.scrollTop).toBe(50);
  });

  it('moves linearly inside a block, so a tall picture shifts only its own stretch', async () => {
    const preview = await mountEditor();
    await fire();
    editor.scrollTop = 100; // halfway through line 5 (80..120), the picture
    await fire();
    expect(preview.scrollTop).toBe(350); // halfway through the picture (100..600)
  });

  it('puts the preview at its end when the editor is scrolled to its end', async () => {
    const preview = await mountEditor();
    await fire();
    editor.scrollTop = 200;
    await fire();
    expect(preview.scrollTop).toBe(600); // 800 - 200
  });

  it('scrolling the preview scrolls the editor through the same blocks', async () => {
    const preview = await mountEditor();
    await fire();
    preview.scrollTop = 350;
    fireEvent.scroll(preview);
    await frame();
    expect(editor.setScrollTop).toHaveBeenLastCalledWith(100);
  });
});

describe('the preview follows the caret', () => {
  it('a moved caret puts its place in the preview at the caret\'s height', async () => {
    const preview = await mountEditor();
    await fire();
    editor.scrollTop = 60;
    editor.caret = { line: 5, col: 3, top: 90 }; // 30 px down the editor's window, a quarter into line 5
    await fire();
    // a quarter into the picture block (100..600) is 225; 30 px above it is 195
    expect(preview.scrollTop).toBe(195);
  });

  it('a caret below the editor\'s window is not followed: the window\'s top is', async () => {
    const preview = await mountEditor();
    editor.scrollTop = 40; // the window shows 40..140
    editor.caret = { line: 9, col: 1, top: 200 };
    await fire();
    expect(preview.scrollTop).toBe(50); // line 3's top, as with no caret
  });

  it('a scroll after typing keeps the panes level at the caret\'s height, without a jump', async () => {
    const preview = await mountEditor();
    await fire();
    editor.scrollTop = 60;
    editor.caret = { line: 5, col: 3, top: 90 }; // 30 px down the window
    await fire();
    expect(preview.scrollTop).toBe(195);
    editor.scrollTop = 70; // a wheel tick: the caret has not moved
    await fire();
    // level at 30 px down: the editor's 100 is halfway through the picture, the preview's 350; less 30
    expect(preview.scrollTop).toBe(320);
  });

  it('near the top the panes meet at their tops', async () => {
    const preview = await mountEditor();
    await fire();
    editor.scrollTop = 60;
    editor.caret = { line: 5, col: 3, top: 90 };
    await fire();
    editor.scrollTop = 10; // the held line is no lower than the scroll: 10 px
    await fire();
    expect(preview.scrollTop).toBe(15); // the editor's 20 is the preview's 25; less 10
    editor.scrollTop = 0;
    await fire();
    expect(preview.scrollTop).toBe(0);
  });

  it('marks the block holding the caret, and only that block', async () => {
    await mountEditor();
    editor.caret = { line: 3, col: 2, top: 40 };
    await fire();
    const marked = () => [...document.querySelectorAll('.editor-preview-caret-block')].map((el) => el.textContent);
    expect(marked()).toEqual(['para one']);
    editor.caret = { line: 7, col: 2, top: 120 };
    await fire();
    expect(marked()).toEqual(['para two']);
  });

  it('a re-rendered preview is synced again, against its new layout', async () => {
    const preview = await mountEditor();
    await fire();
    // CodeMirror reports the keystroke, the sync runs against the preview as it was, then the preview re-renders
    // (deferred) before the next frame.
    // Two lines typed above the caret: 'para one' is now line 5, held 30 px down the editor's window.
    editor.scrollTop = 60;
    editor.caret = { line: 5, col: 2, top: 90 };
    act(() => { editor.onViewChange(); });
    runFrame();
    expect(preview.scrollTop).toBe(195); // synced against the old preview
    // The re-rendered preview: Intro, Title, para one, the picture, para two on lines 1, 3, 5, 7, 9.
    PREVIEW_TOPS = { 1: 0, 3: 50, 5: 300, 7: 400, 9: 900 };
    Object.defineProperty(preview, 'scrollHeight', { value: 1200, configurable: true });
    act(() => { editor.onChange(`Intro\n\n${BODY}`); });
    await screen.findByText('Intro');
    await frame();
    // the editor's 90 is a quarter through line 5 (80..120); in the new preview that is 300 + 25 = 325; less 30
    expect(preview.scrollTop).toBe(295);
    const marked = [...document.querySelectorAll('.editor-preview-caret-block')].map((el) => el.textContent);
    expect(marked).toEqual(['para one']);
  });
});

describe('the sync and the reader\'s own moves', () => {
  const marked = () => [...document.querySelectorAll('.editor-preview-caret-block')].map((el) => el.textContent);

  it('a caret move in the frame after a sync is not dropped as an echo of it', async () => {
    await mountEditor();
    editor.caret = { line: 3, col: 2, top: 40 };
    await fire();
    editor.scrollTop = 20;
    act(() => { editor.onViewChange(); }); // a scroll
    runFrame(); // its sync runs
    editor.caret = { line: 7, col: 1, top: 120 }; // an arrow key, before the preview reports the sync's scroll
    act(() => { editor.onViewChange(); });
    await frame();
    expect(marked()).toEqual(['para two']);
  });

  it('a re-rendered preview does not undo the reader\'s scroll of it', async () => {
    const preview = await mountEditor();
    await fire();
    act(() => { editor.onChange(`${BODY}\n\nmore`); }); // a peer's edit: the preview re-renders and asks for a sync
    await screen.findByText('more');
    preview.scrollTop = 350; // meanwhile the reader scrolls the preview
    fireEvent.scroll(preview);
    await frame();
    expect(preview.scrollTop).toBe(350);
    expect(editor.setScrollTop).toHaveBeenLastCalledWith(100);
  });

  it('the preview reporting the sync\'s own scroll does not scroll the editor', async () => {
    const preview = await mountEditor();
    await fire();
    editor.scrollTop = 40;
    await fire();
    expect(preview.scrollTop).toBe(50);
    fireEvent.scroll(preview); // the browser reports the scroll the sync made
    await frame();
    expect(editor.setScrollTop).not.toHaveBeenCalled();
  });

  it('the reader scrolling the preview right after a sync is followed, not taken for the sync\'s own scroll', async () => {
    const preview = await mountEditor();
    await fire();
    editor.scrollTop = 40;
    act(() => { editor.onViewChange(); });
    runFrame(); // the sync puts the preview at 50
    expect(preview.scrollTop).toBe(50);
    preview.scrollTop = 350; // the reader's wheel, in the very next frame
    fireEvent.scroll(preview);
    await frame();
    expect(editor.setScrollTop).toHaveBeenLastCalledWith(100);
  });

  it('the editor reporting the sync\'s own scroll does not move the preview', async () => {
    const preview = await mountEditor();
    await fire();
    preview.scrollTop = 350;
    fireEvent.scroll(preview);
    await frame();
    expect(editor.scrollTop).toBe(100);
    preview.scrollTop = 340; // the preview moves on (say, a picture above it settled) …
    await fire(); // … and only then does CodeMirror report the scroll the sync made
    expect(preview.scrollTop).toBe(340);
  });
});

describe('a pane reporting the sync\'s scroll more than once', () => {
  it('the editor reporting the same place again does not nudge the preview, even where one editor px is many preview px', async () => {
    const preview = await mountEditor();
    await fire();
    preview.scrollTop = 351; // inside the picture: 12.5 preview px to an editor px
    fireEvent.scroll(preview);
    await frame();
    expect(editor.setScrollTop).toHaveBeenLastCalledWith(100); // 100.08, to the whole px
    await fire(); // CodeMirror reports the scroll …
    await fire(); // … and again, as it re-measures
    expect(preview.scrollTop).toBe(351);
  });

  it('the editor correcting the scroll it was given (CodeMirror re-measuring) moves the editor back in line, never the preview', async () => {
    const preview = await mountEditor();
    await fire();
    preview.scrollTop = 351;
    fireEvent.scroll(preview);
    runFrame(); // the editor follows to 100 …
    expect(editor.setScrollTop).toHaveBeenLastCalledWith(100);
    editor.scrollTop = 107; // … and, in the same frame, CodeMirror moves itself on as it measures the lines it shows
    act(() => { editor.onViewChange(); });
    await frame();
    expect(preview.scrollTop).toBe(351); // the reader put the preview there
    expect(editor.setScrollTop).toHaveBeenLastCalledWith(100); // the editor is put back in line with it
  });

  it('a later move of the editor away and back is the reader\'s, and is followed', async () => {
    const preview = await mountEditor();
    await fire();
    preview.scrollTop = 351;
    fireEvent.scroll(preview);
    await frame();
    editor.scrollTop = 120;
    await fire();
    editor.scrollTop = 100;
    await fire();
    expect(preview.scrollTop).toBe(350);
  });
});

describe('a click in the preview', () => {
  it('puts the caret on the clicked block\'s line, level with the block, which becomes the reading line', async () => {
    const preview = await mountEditor();
    await fire();
    preview.scrollTop = 30;
    fireEvent.click(screen.getByText('para one')); // line 3, drawn 50 - 30 = 20 px down the preview
    expect(editor.jumps).toEqual([[3, 20]]);
    await frame();
    // Once the click's guard lets go, a scroll holds the panes level 20 px down: the editor's 80 + 20 is halfway
    // through line 5 (80..120), the preview's 350 halfway through the picture (100..600); less 20.
    await act(async () => { await new Promise((r) => setTimeout(r, 350)); });
    editor.scrollTop = 80;
    await fire();
    expect(preview.scrollTop).toBe(330);
  });
});

describe('the sync does no more work than a frame needs', () => {
  it('maps the blocks into the editor once per layout, not on every frame', async () => {
    await mountEditor();
    await fire();
    editor.scrollTop = 40;
    await fire();
    const once = editor.lineTops;
    expect(once).toBeGreaterThan(0);
    editor.scrollTop = 60;
    await fire();
    editor.scrollTop = 80;
    await fire();
    expect(editor.lineTops).toBe(once);
  });

  it('does not mark the caret\'s block again while the caret stays in it', async () => {
    await mountEditor();
    editor.caret = { line: 3, col: 2, top: 40 };
    await fire();
    const block = document.querySelector('.editor-preview-caret-block');
    const add = vi.spyOn(block.classList, 'add');
    editor.scrollTop = 20;
    await fire();
    editor.caret = { line: 3, col: 4, top: 40 };
    await fire();
    expect(add).not.toHaveBeenCalled();
    expect(block.classList.contains('editor-preview-caret-block')).toBe(true);
  });
});

describe('the Preview button', () => {
  it('shows the side pane by default, hides it, and the choice is remembered', async () => {
    await mountEditor();
    const button = screen.getByTestId('editor-preview-toggle');
    expect(button).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(button);
    expect(previewEl()).toBeNull();
    expect(button).toHaveAttribute('aria-pressed', 'false');
    cleanup();
    render(
      <MemoryRouter initialEntries={['/edit/Existing']}>
        <NavigationGuardProvider>
          <Routes><Route path="/edit/:name" element={<PageEditor />} /></Routes>
        </NavigationGuardProvider>
      </MemoryRouter>,
    );
    await screen.findByTestId('body-value');
    expect(screen.getByTestId('editor-preview-toggle')).toHaveAttribute('aria-pressed', 'false');
    expect(previewEl()).toBeNull();
    fireEvent.click(screen.getByTestId('editor-preview-toggle'));
    expect(previewEl()).not.toBeNull();
  });
});
