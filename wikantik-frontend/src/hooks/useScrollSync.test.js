import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useScrollSync } from './useScrollSync';
import { PREVIEW_CARET_CLASS } from '../utils/previewAnchors';

// A controllable animation-frame queue: flushFrame() runs what was queued before the call (callbacks queued while
// running belong to the next frame), the way a browser's frame does.
let queue;
let nextId;
function flushFrame() {
  const run = queue;
  queue = new Map();
  for (const cb of run.values()) cb();
}

const EDITOR_HEIGHT = 2000;
const EDITOR_MAX = 1500; // editor window = 500
const PREVIEW_HEIGHT = 3000;
const PREVIEW_CLIENT = 500; // preview max = 2500

function makePreview() {
  const preview = document.createElement('div');
  Object.defineProperty(preview, 'scrollHeight', { value: PREVIEW_HEIGHT, configurable: true, writable: true });
  Object.defineProperty(preview, 'clientHeight', { value: PREVIEW_CLIENT, configurable: true });
  Object.defineProperty(preview, 'clientWidth', { value: 800, configurable: true, writable: true });
  // jsdom has no layout: scrollTop is a plain property here.
  let top = 0;
  Object.defineProperty(preview, 'scrollTop', { get: () => top, set: (v) => { top = v; }, configurable: true });
  return preview;
}

/** An article whose blocks (data-line) are laid out at the given tops (via a stubbed getBoundingClientRect). */
function makeArticle(blocks) {
  const article = document.createElement('article');
  const els = blocks.map(([line, top]) => {
    const el = document.createElement('p');
    el.setAttribute('data-line', String(line));
    el.getBoundingClientRect = () => ({ top, width: 100, height: 20 });
    article.appendChild(el);
    return el;
  });
  return { article, els };
}

function makeEditor(initial = {}) {
  const state = { scrollTop: 0, caretTop: 0, caretLine: 1, doc: { id: 'doc' }, ...initial };
  const editor = {
    state,
    scrollGeometry: vi.fn(() => (state.measurable === false ? null : {
      doc: state.doc,
      scrollTop: state.scrollTop,
      maxScroll: EDITOR_MAX,
      height: EDITOR_HEIGHT,
      lineTop: (n) => { state.lineTopCalls = (state.lineTopCalls || 0) + 1; return (n - 1) * 100; },
      caret: { line: state.caretLine, top: state.caretTop },
    })),
    // Lands exactly where asked unless the test says the editor corrects itself.
    setScrollTop: vi.fn((top) => {
      state.scrollTop = state.landAt ?? top;
      return state.scrollTop;
    }),
  };
  return editor;
}

function setup({ blocks = [], body = '', editor = makeEditor(), guard = false } = {}) {
  const preview = makePreview();
  const { article, els } = makeArticle(blocks);
  const refs = {
    editorRef: { current: editor },
    previewRef: { current: preview },
    previewArticleRef: { current: article },
    bodyRef: { current: body },
    clickSyncGuardRef: { current: guard },
  };
  const hook = renderHook(() => useScrollSync(refs));
  return { ...hook, ...refs, preview, article, els, editor };
}

beforeEach(() => {
  queue = new Map();
  nextId = 1;
  vi.stubGlobal('requestAnimationFrame', vi.fn((cb) => { const id = nextId++; queue.set(id, cb); return id; }));
  vi.stubGlobal('cancelAnimationFrame', vi.fn((id) => { queue.delete(id); }));
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('useScrollSync: editor to preview', () => {
  it('the editor at its end puts the preview at its end, and at its top at the top', () => {
    const { result, preview, editor } = setup();
    editor.state.scrollTop = EDITOR_MAX;
    result.current.syncPreview();
    flushFrame();
    expect(preview.scrollTop).toBe(PREVIEW_HEIGHT - PREVIEW_CLIENT);

    editor.state.scrollTop = 0;
    result.current.syncPreview({ caretMoved: false, force: true });
    flushFrame();
    expect(preview.scrollTop).toBe(0);
  });

  it('moves the preview in proportion in between', () => {
    const { result, preview, editor } = setup();
    editor.state.scrollTop = EDITOR_MAX / 2;
    result.current.syncPreview();
    flushFrame();
    expect(preview.scrollTop).toBeGreaterThan(0);
    expect(preview.scrollTop).toBeLessThan(PREVIEW_HEIGHT - PREVIEW_CLIENT);
  });

  it('calls within one frame share one sync', () => {
    const { result, editor } = setup();
    result.current.syncPreview();
    result.current.syncPreview();
    result.current.syncPreview({ caretMoved: true });
    expect(queue.size).toBe(1);
    flushFrame();
    expect(editor.scrollGeometry).toHaveBeenCalledTimes(1);
  });

  it('stands still while a click in the preview is moving the editor', () => {
    const { result, preview, editor, clickSyncGuardRef } = setup({ guard: true });
    editor.state.scrollTop = EDITOR_MAX;
    result.current.syncPreview();
    expect(queue.size).toBe(0);

    // The guard raised after the frame was requested also stops the frame's work.
    clickSyncGuardRef.current = false;
    result.current.syncPreview();
    clickSyncGuardRef.current = true;
    flushFrame();
    expect(preview.scrollTop).toBe(0);
    expect(editor.scrollGeometry).not.toHaveBeenCalled();
  });

  it('does nothing when the editor cannot be measured or the preview is gone', () => {
    const editor = makeEditor({ measurable: false });
    const { result, preview, previewRef } = setup({ editor });
    result.current.syncPreview();
    flushFrame();
    expect(preview.scrollTop).toBe(0);

    editor.state.measurable = true;
    editor.state.scrollTop = EDITOR_MAX;
    previewRef.current = null;
    result.current.syncPreview();
    flushFrame();
    expect(preview.scrollTop).toBe(0);
  });

  it('copes with an editor handle that has no scrollGeometry', () => {
    const { result, preview } = setup({ editor: {} });
    result.current.syncPreview();
    flushFrame();
    expect(preview.scrollTop).toBe(0);
  });

  it('leaves the preview alone when it is already where the editor maps it', () => {
    const { result, preview, editor } = setup();
    editor.state.scrollTop = EDITOR_MAX;
    result.current.syncPreview();
    flushFrame();
    const writes = vi.fn();
    let top = preview.scrollTop;
    Object.defineProperty(preview, 'scrollTop', { get: () => top, set: (v) => { writes(v); top = v; }, configurable: true });
    result.current.syncPreview({ force: true });
    flushFrame();
    expect(writes).not.toHaveBeenCalled();
  });
});

describe('useScrollSync: preview to editor', () => {
  it('puts the editor at the place the preview maps to, end to end', () => {
    const { result, preview, editor } = setup();
    preview.scrollTop = PREVIEW_HEIGHT - PREVIEW_CLIENT;
    result.current.syncEditor();
    expect(editor.setScrollTop).not.toHaveBeenCalled(); // next frame
    flushFrame();
    expect(editor.setScrollTop).toHaveBeenCalledTimes(1);
    expect(editor.setScrollTop).toHaveBeenCalledWith(EDITOR_MAX);
  });

  it('a second request while one is pending shares the frame', () => {
    const { result, preview } = setup();
    preview.scrollTop = 1000;
    result.current.syncEditor();
    result.current.syncEditor();
    expect(queue.size).toBe(1);
  });

  it('stands still during a click jump, without a preview, or without an editor that can scroll', () => {
    const guarded = setup({ guard: true });
    guarded.preview.scrollTop = 1000;
    guarded.result.current.syncEditor();
    expect(queue.size).toBe(0);

    const gone = setup();
    gone.previewRef.current = null;
    gone.result.current.syncEditor();
    expect(queue.size).toBe(0);

    const noScroll = setup({ editor: { scrollGeometry: vi.fn(() => ({})) } });
    noScroll.preview.scrollTop = 1000;
    noScroll.result.current.syncEditor();
    flushFrame();
    expect(noScroll.editor.scrollGeometry).not.toHaveBeenCalled();

    const detached = setup();
    detached.preview.scrollTop = 1000;
    detached.result.current.syncEditor();
    detached.previewRef.current = null; // gone before the frame ran
    flushFrame();
    expect(detached.editor.setScrollTop).not.toHaveBeenCalled();
  });

  it('writes nothing when the editor is already within a pixel of the target', () => {
    const editor = makeEditor({ scrollTop: EDITOR_MAX });
    const { result, preview } = setup({ editor });
    preview.scrollTop = PREVIEW_HEIGHT - PREVIEW_CLIENT;
    result.current.syncEditor();
    flushFrame();
    expect(editor.setScrollTop).not.toHaveBeenCalled();
  });

  it('does not sync back the scroll the editor-to-preview sync just gave the preview', () => {
    const { result, preview, editor } = setup();
    editor.state.scrollTop = EDITOR_MAX;
    result.current.syncPreview();
    flushFrame();
    expect(preview.scrollTop).toBeGreaterThan(0);

    // The preview's scroll event reports the sync's own write: an echo.
    result.current.syncEditor();
    expect(queue.size).toBe(0);

    // A reader's scroll somewhere else is followed, and drops the record.
    preview.scrollTop = 100;
    result.current.syncEditor();
    expect(queue.size).toBe(1);
  });

  it('drops the sync-written preview position when the preview is hidden', () => {
    const { result, preview, editor } = setup();
    editor.state.scrollTop = EDITOR_MAX;
    result.current.syncPreview();
    flushFrame();
    result.current.resetScrollMap(false);
    result.current.syncEditor();
    expect(queue.size).toBe(1);
    expect(preview.scrollTop).toBeGreaterThan(0);
  });

  it('records where the editor really landed, not where it was asked to', () => {
    const editor = makeEditor({ landAt: 1200 });
    const { result, preview } = setup({ editor });
    preview.scrollTop = PREVIEW_HEIGHT - PREVIEW_CLIENT;
    result.current.syncEditor();
    flushFrame();
    expect(editor.state.scrollTop).toBe(1200);
    // The editor reporting 1200 is an echo of the write (the settle correction aside), not a reader's scroll:
    // it must not move the preview.
    flushFrame();
    flushFrame();
    result.current.syncPreview();
    flushFrame();
    expect(preview.scrollTop).toBe(PREVIEW_HEIGHT - PREVIEW_CLIENT);
  });

  it('falls back to the requested position when the editor reports none', () => {
    const editor = makeEditor();
    editor.setScrollTop = vi.fn(() => undefined);
    editor.state.scrollTop = 0;
    const { result, preview } = setup({ editor });
    preview.scrollTop = PREVIEW_HEIGHT - PREVIEW_CLIENT;
    result.current.syncEditor();
    flushFrame();
    expect(editor.setScrollTop).toHaveBeenCalledWith(EDITOR_MAX);
    // the editor now reports that very position: an echo, so the preview stays put
    editor.state.scrollTop = EDITOR_MAX;
    flushFrame();
    flushFrame();
    result.current.syncPreview();
    flushFrame();
    expect(preview.scrollTop).toBe(PREVIEW_HEIGHT - PREVIEW_CLIENT);
  });
});

describe('useScrollSync: the editor correcting itself after a write', () => {
  // CodeMirror measures what it scrolled to in the frame after the write and moves its scroll to keep it in place.
  it('puts the editor back in line with the preview instead of moving the preview', () => {
    const { result, preview, editor } = setup();
    preview.scrollTop = 1250; // mid-way
    result.current.syncEditor();
    flushFrame();
    const target = editor.setScrollTop.mock.calls[0][0];
    expect(target).toBeGreaterThan(0);
    expect(target).toBeLessThan(EDITOR_MAX);

    // the editor corrects its scroll, and reports the place the sync did not give it
    editor.state.scrollTop = target + 40;
    result.current.syncPreview();
    flushFrame();
    expect(preview.scrollTop).toBe(1250);
    expect(editor.setScrollTop).toHaveBeenCalledTimes(2);
    expect(editor.setScrollTop.mock.calls[1][0]).toBe(target);
  });

  it('the correction is only for that one frame: afterwards the editor leads again', () => {
    const { result, preview, editor } = setup();
    preview.scrollTop = 1250;
    result.current.syncEditor();
    flushFrame(); // the preview-to-editor write; arms the settle flag, clears it two frames later
    flushFrame();
    flushFrame();

    editor.state.scrollTop = EDITOR_MAX; // the reader scrolls the editor
    result.current.syncPreview();
    flushFrame();
    expect(preview.scrollTop).toBe(PREVIEW_HEIGHT - PREVIEW_CLIENT);
  });

  it('a caret move during the settle frame is followed: the preview moves', () => {
    const { result, preview, editor } = setup();
    preview.scrollTop = 1250;
    result.current.syncEditor();
    flushFrame();
    editor.state.scrollTop = EDITOR_MAX;
    editor.state.caretTop = EDITOR_MAX + 100;
    result.current.syncPreview({ caretMoved: true });
    flushFrame();
    expect(preview.scrollTop).toBe(PREVIEW_HEIGHT - PREVIEW_CLIENT);
  });

  it('the preview being scrolled by the reader holds the editor-to-preview sync back', () => {
    const { result, preview, editor } = setup();
    preview.scrollTop = 1250;
    editor.state.scrollTop = EDITOR_MAX;
    result.current.syncPreview(); // its frame runs first, while the preview-to-editor frame is still pending
    result.current.syncEditor();
    flushFrame();
    expect(preview.scrollTop).toBe(1250);
  });
});

describe('useScrollSync: the reading line', () => {
  it('a caret move makes the caret height in the window the reading line, so the next sync holds that height', () => {
    const moved = setup();
    moved.editor.state.scrollTop = 600;
    moved.editor.state.caretTop = 600 + 250;
    moved.result.current.syncPreview({ caretMoved: true });
    flushFrame();

    const still = setup();
    still.editor.state.scrollTop = 600;
    still.editor.state.caretTop = 600 + 250;
    still.result.current.syncPreview();
    flushFrame();

    expect(moved.preview.scrollTop).not.toBe(still.preview.scrollTop);
  });

  it('a caret outside the window is not a reading line', () => {
    const above = setup();
    above.editor.state.scrollTop = 600;
    above.editor.state.caretTop = 100; // above the window
    above.result.current.syncPreview({ caretMoved: true });
    flushFrame();

    const below = setup();
    below.editor.state.scrollTop = 600;
    below.editor.state.caretTop = 600 + 900; // below the 500px window
    below.result.current.syncPreview({ caretMoved: true });
    flushFrame();

    const plain = setup();
    plain.editor.state.scrollTop = 600;
    plain.result.current.syncPreview();
    flushFrame();

    expect(above.preview.scrollTop).toBe(plain.preview.scrollTop);
    expect(below.preview.scrollTop).toBe(plain.preview.scrollTop);
  });

  it('prepareClickJump makes the clicked offset the reading line, clamped at the window top', () => {
    const jumped = setup();
    jumped.editor.state.scrollTop = 600;
    jumped.result.current.prepareClickJump(250);
    jumped.result.current.syncPreview();
    flushFrame();

    const plain = setup();
    plain.editor.state.scrollTop = 600;
    plain.result.current.syncPreview();
    flushFrame();
    expect(jumped.preview.scrollTop).not.toBe(plain.preview.scrollTop);

    const negative = setup();
    negative.editor.state.scrollTop = 600;
    negative.result.current.prepareClickJump(-40);
    negative.result.current.syncPreview();
    flushFrame();
    expect(negative.preview.scrollTop).toBe(plain.preview.scrollTop);
  });

  it('prepareClickJump cancels a pending sync and drops its pending caret move', () => {
    const { result, preview, editor } = setup();
    editor.state.scrollTop = 600;
    editor.state.caretTop = 600 + 250;
    result.current.syncPreview({ caretMoved: true });
    result.current.prepareClickJump(0);
    expect(queue.size).toBe(0);
    expect(cancelAnimationFrame).toHaveBeenCalled();
    expect(preview.scrollTop).toBe(0);

    // The next sync is not a caret move any more: it keeps the clicked reading line (0) and ignores the caret.
    result.current.syncPreview();
    flushFrame();
    const plain = setup();
    plain.editor.state.scrollTop = 600;
    plain.editor.state.caretTop = 600 + 250;
    plain.result.current.syncPreview();
    flushFrame();
    expect(preview.scrollTop).toBe(plain.preview.scrollTop);
  });

  it('prepareClickJump with nothing pending is harmless', () => {
    const { result } = setup();
    expect(() => result.current.prepareClickJump(10)).not.toThrow();
    expect(cancelAnimationFrame).not.toHaveBeenCalled();
  });
});

describe('useScrollSync: the preview blocks and the caret block', () => {
  const blocks = [[1, 0], [3, 300], [6, 900]];

  it('marks the block holding the caret and moves the mark with it', () => {
    const { result, els, editor } = setup({ blocks });
    editor.state.caretLine = 4;
    result.current.syncPreview();
    flushFrame();
    expect(els[1].classList.contains(PREVIEW_CARET_CLASS)).toBe(true);

    editor.state.caretLine = 6;
    result.current.syncPreview();
    flushFrame();
    expect(els[1].classList.contains(PREVIEW_CARET_CLASS)).toBe(false);
    expect(els[2].classList.contains(PREVIEW_CARET_CLASS)).toBe(true);
  });

  it('puts the mark back when something removed it, and keeps it while it stays', () => {
    const { result, els, editor } = setup({ blocks });
    editor.state.caretLine = 1;
    result.current.syncPreview();
    flushFrame();
    els[0].classList.remove(PREVIEW_CARET_CLASS); // the preview re-rendered
    result.current.syncPreview();
    flushFrame();
    expect(els[0].classList.contains(PREVIEW_CARET_CLASS)).toBe(true);
    const add = vi.spyOn(els[0].classList, 'add');
    result.current.syncPreview();
    flushFrame();
    expect(add).not.toHaveBeenCalled();
  });

  it('reads the caret line past the body frontmatter', () => {
    const body = '---\ntitle: x\n---\nText\n\nMore';
    const { result, els, editor } = setup({ blocks: [[1, 0], [3, 300]], body });
    editor.state.caretLine = 4; // the first body line
    result.current.syncPreview();
    flushFrame();
    expect(els[0].classList.contains(PREVIEW_CARET_CLASS)).toBe(true);
    expect(els[1].classList.contains(PREVIEW_CARET_CLASS)).toBe(false);
  });

  it('a caret above the first block marks nothing and clears the old mark', () => {
    const { result, els, editor } = setup({ blocks: [[5, 0]] });
    editor.state.caretLine = 5;
    result.current.syncPreview();
    flushFrame();
    expect(els[0].classList.contains(PREVIEW_CARET_CLASS)).toBe(true);
    editor.state.caretLine = 1;
    result.current.syncPreview();
    flushFrame();
    expect(els[0].classList.contains(PREVIEW_CARET_CLASS)).toBe(false);
  });

  it('measures the preview once per layout and again when it was dropped or resized', () => {
    const { result, preview } = setup({ blocks });
    const first = result.current.previewBlocks();
    expect(first.anchors.map((a) => a.line)).toEqual([1, 3, 6]);
    expect(first.byLine).toHaveLength(3);
    expect(result.current.previewBlocks()).toBe(first);

    result.current.resetScrollMap(true);
    const second = result.current.previewBlocks();
    expect(second).not.toBe(first);

    Object.defineProperty(preview, 'scrollHeight', { value: PREVIEW_HEIGHT + 50, configurable: true });
    const third = result.current.previewBlocks();
    expect(third).not.toBe(second);

    Object.defineProperty(preview, 'clientWidth', { value: 400, configurable: true });
    expect(result.current.previewBlocks()).not.toBe(third);
  });

  it('is empty without a preview or an article', () => {
    const { result, previewRef, previewArticleRef } = setup({ blocks });
    previewArticleRef.current = null;
    expect(result.current.previewBlocks()).toEqual({ anchors: [], byLine: [] });
    previewArticleRef.current = document.createElement('article');
    previewRef.current = null;
    expect(result.current.previewBlocks()).toEqual({ anchors: [], byLine: [] });
  });

  it('hiding the preview drops the marked block and the map; showing it keeps them', () => {
    const { result, els, editor } = setup({ blocks });
    editor.state.caretLine = 1;
    result.current.syncPreview();
    flushFrame();
    result.current.resetScrollMap(true);
    expect(els[0].classList.contains(PREVIEW_CARET_CLASS)).toBe(true);

    result.current.resetScrollMap(false);
    // the record is gone, so the next mark does not try to unmark the old element...
    editor.state.caretLine = 3;
    result.current.syncPreview();
    flushFrame();
    expect(els[1].classList.contains(PREVIEW_CARET_CLASS)).toBe(true);
    // ...which is why the stale element keeps its class: nothing holds it any more
    expect(els[0].classList.contains(PREVIEW_CARET_CLASS)).toBe(true);
  });

  it('follows the blocks: a block-mapped sync differs from the straight line when blocks are uneven', () => {
    // a tall block (lines 1-2 take 1400 px of preview, 100 px of editor): the map bends around it
    const tall = setup({ blocks: [[1, 0], [3, 1400]] });
    tall.editor.state.scrollTop = 150;
    tall.result.current.syncPreview({ force: true });
    flushFrame();

    const flat = setup();
    flat.editor.state.scrollTop = 150;
    flat.result.current.syncPreview({ force: true });
    flushFrame();
    expect(tall.preview.scrollTop).not.toBe(flat.preview.scrollTop);
  });

  it('rebuilds the scroll map only when its inputs change', () => {
    const { result, editor, preview } = setup({ blocks });
    editor.state.scrollTop = 300;
    result.current.syncPreview();
    flushFrame();
    const built = editor.state.lineTopCalls;
    expect(built).toBeGreaterThan(0);

    editor.state.scrollTop = 310; // only the scroll moved: the map is reused
    result.current.syncPreview();
    flushFrame();
    expect(editor.state.lineTopCalls).toBe(built);

    editor.state.doc = { id: 'edited' }; // an edit
    result.current.syncPreview();
    flushFrame();
    expect(editor.state.lineTopCalls).toBeGreaterThan(built);

    const afterEdit = editor.state.lineTopCalls;
    Object.defineProperty(preview, 'scrollHeight', { value: PREVIEW_HEIGHT + 100, configurable: true });
    result.current.syncPreview();
    flushFrame();
    expect(editor.state.lineTopCalls).toBeGreaterThan(afterEdit);
  });
});

describe('useScrollSync: lifetime', () => {
  it('cancels pending frames on unmount', () => {
    const { result, unmount, preview } = setup();
    preview.scrollTop = 800;
    result.current.syncEditor();
    result.current.syncPreview();
    expect(queue.size).toBe(2);
    unmount();
    expect(cancelAnimationFrame).toHaveBeenCalledTimes(2);
    expect(queue.size).toBe(0);
  });

  it('unmounting with nothing pending cancels nothing', () => {
    const { unmount } = setup();
    unmount();
    expect(cancelAnimationFrame).not.toHaveBeenCalled();
  });

  it('keeps stable function identities across renders', () => {
    const { result, rerender } = setup();
    const first = result.current;
    rerender();
    expect(result.current.syncPreview).toBe(first.syncPreview);
    expect(result.current.syncEditor).toBe(first.syncEditor);
    expect(result.current.previewBlocks).toBe(first.previewBlocks);
  });
});
