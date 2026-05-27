import { describe, it, vi, beforeEach, afterEach, expect } from 'vitest';
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider } from '../hooks/useAuth';
import { api } from '../api/client';
import PageView from './PageView';

// ---------------------------------------------------------------------------
// Determinism strategy (see the note at the bottom for the prior-flake history):
//   * Mock every heavy collaborator so the render is cheap and never starves a
//     parallel worker's event loop: ../utils/math (renderMath → no-op) and the
//     four side panels (MetadataPanel/SimilarPagesPanel/ChangeNotesPanel/
//     PageMeta → () => null). CommentsDrawer, commentAnchor and commentHighlight
//     stay REAL — they are the integration under test.
//   * Mock ../api/client fully; every method resolves IMMEDIATELY (microtasks,
//     not timers), so waitFor settles in a couple of ticks with no wall-clock
//     dependency — a busy worker cannot time us out.
//   * The ONE real timer in the component is focusThread's 1200ms pulse
//     setTimeout. A leaked real 1200ms timer competing for the worker loop was a
//     prime flake suspect, so the single test that triggers it switches to FAKE
//     timers locally (withFakeTimers) to flush it synchronously — no leak.
//   * Stub the browser APIs happy-dom lacks (scrollIntoView,
//     getBoundingClientRect) and a controllable window.getSelection per-test.
// ---------------------------------------------------------------------------
vi.mock('../utils/math', () => ({ renderMath: vi.fn() }));
vi.mock('./MetadataPanel', () => ({ default: () => null }));
vi.mock('./SimilarPagesPanel', () => ({ default: () => null }));
vi.mock('./ChangeNotesPanel', () => ({ default: () => null }));
vi.mock('./PageMeta', () => ({ default: () => null }));

const PAGE = {
  name: 'Foo',
  // contentHtml carries the thread anchor text "quick brown" so anchorThreads
  // locates it and inserts a <mark>.
  contentHtml: '<p>The quick brown fox jumps</p>',
  permissions: { edit: true },
  metadata: {},
};

const THREAD = {
  id: 'T1',
  status: 'open',
  anchor: { exact: 'quick brown', prefix: 'The ', suffix: ' fox' },
  comments: [{ id: 'C1', author: 'alice', body: 'first comment', createdAt: '2026-01-01T00:00:00Z' }],
};

vi.mock('../api/client', () => ({
  api: {
    getUser: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
    getPage: vi.fn(),
    listCommentThreads: vi.fn(),
    createCommentThread: vi.fn(),
    addCommentReply: vi.fn(),
    resolveCommentThread: vi.fn(),
    reopenCommentThread: vi.fn(),
    getSimilarPages: vi.fn(),
    getHistory: vi.fn(),
  },
}));

const FIXED_RECT = { top: 10, bottom: 30, left: 20, right: 120, width: 100, height: 20, x: 20, y: 10 };

beforeEach(() => {
  vi.clearAllMocks();

  api.getUser.mockResolvedValue({ authenticated: true, username: 'alice', roles: [] });
  // Return a FRESH page object per call (mirrors real fetch responses). PageView
  // double-fetches getPage (useApi deps include user?.authenticated, which flips
  // when AuthProvider's refresh resolves), and its highlight/math effects key on
  // the `page` REFERENCE — so a shared object would skip re-anchoring after the
  // auth-driven refetch reset the article DOM, dropping the <mark>.
  api.getPage.mockImplementation(async () => ({ ...PAGE }));
  api.listCommentThreads.mockResolvedValue({ threads: [THREAD] });
  api.createCommentThread.mockResolvedValue({});
  api.addCommentReply.mockResolvedValue({});
  api.resolveCommentThread.mockResolvedValue({});
  api.reopenCommentThread.mockResolvedValue({});
  api.getSimilarPages.mockResolvedValue({ pages: [] });
  api.getHistory.mockResolvedValue({ versions: [] });

  // happy-dom lacks layout: stub the APIs the comment paths reach for.
  Element.prototype.scrollIntoView = vi.fn();
  Element.prototype.getBoundingClientRect = vi.fn(() => ({ ...FIXED_RECT }));
  Range.prototype.getBoundingClientRect = vi.fn(() => ({ ...FIXED_RECT }));
});

afterEach(() => {
  vi.restoreAllMocks();
});

function renderPageView() {
  return render(
    <MemoryRouter initialEntries={['/wiki/Foo']}>
      <AuthProvider>
        <Routes>
          <Route path="/wiki/:name" element={<PageView />} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

// Mount and wait until the page render + the loadThreads effect have settled,
// signalled by the Comments toggle showing the loaded thread count. waitFor
// polls on real timers but the api mocks resolve in microtasks, so this returns
// after a couple of ticks — no wall-clock dependency, no event-loop starvation.
// Wait for the STABLE loaded state. AuthProvider starts with user=null then
// refresh() flips it to authenticated; PageView's useApi depends on
// user?.authenticated, so getPage fires TWICE and the component briefly returns
// to its "Loading…" branch between fetches. A synchronous query issued after
// only the first fetch can latch onto — or be torn down by — that transient
// (the prime cross-test flake we hit under the parallel worker pool). Gate on
// BOTH fetches completing, THEN on the loaded markers, so every later
// synchronous getBy* runs against settled DOM. Polling re-queries each tick and
// the mocks resolve in microtasks, so this is deterministic with no wall clock.
async function awaitStableLoaded() {
  await waitFor(() => expect(api.getPage.mock.calls.length).toBeGreaterThanOrEqual(2));
  await waitFor(() => {
    expect(screen.getByTestId('page-view')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /💬 Comments/ })).toBeInTheDocument();
  });
}

async function mountAndSettle() {
  const utils = renderPageView();
  await awaitStableLoaded();
  return utils;
}

// Run `fn` with fake timers installed, then drain pending timers and restore
// real timers. Used only by the pulse test so the 1200ms focusThread timeout is
// flushed synchronously and never leaks a real timer into the worker pool.
async function withFakeTimers(fn) {
  vi.useFakeTimers();
  try {
    await fn();
  } finally {
    vi.runOnlyPendingTimers();
    vi.useRealTimers();
  }
}

const TEST_TIMEOUT = 15000;

describe('PageView comment integration', () => {
  it('renders the page and the Comments toggle reflecting the loaded thread count', async () => {
    await mountAndSettle();
    expect(screen.getByTestId('page-view')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /💬 Comments \(1\)/ })).toBeInTheDocument();
  }, TEST_TIMEOUT);

  it('anchors the open thread as a <mark> in the rendered article', async () => {
    const { container } = await mountAndSettle();
    // The highlight effect wraps the anchor text in a <mark>.
    const mark = await waitFor(() =>
      container.querySelector('mark.comment-highlight[data-thread-id="T1"]'));
    expect(mark).toBeTruthy();
    expect(mark.textContent).toBe('quick brown');
  }, TEST_TIMEOUT);

  it('clicking the highlight mark opens the drawer and focuses the thread (pulse)', async () => {
    const { container } = await mountAndSettle();
    const mark = await waitFor(() =>
      container.querySelector('mark.comment-highlight[data-thread-id="T1"]'));

    await withFakeTimers(async () => {
      await act(async () => { fireEvent.click(mark); });

      // Drawer opened: the thread comment body renders.
      expect(screen.getByText(/first comment/)).toBeInTheDocument();
      // focusThread scrolled to + pulsed the mark.
      expect(Element.prototype.scrollIntoView).toHaveBeenCalled();
      expect(mark.classList.contains('comment-highlight-pulse')).toBe(true);

      // Advance past the 1200ms pulse → class removed, no leaked timer.
      await act(async () => { vi.advanceTimersByTime(1300); });
      expect(mark.classList.contains('comment-highlight-pulse')).toBe(false);
    });
  }, TEST_TIMEOUT);

  it('toggle button opens the drawer showing the loaded thread', async () => {
    await mountAndSettle();
    const toggle = screen.getByRole('button', { name: /💬 Comments \(1\)/ });
    await act(async () => { fireEvent.click(toggle); });
    expect(screen.getByText(/first comment/)).toBeInTheDocument();
  }, TEST_TIMEOUT);

  it('mouse-up over a text selection renders the floating Comment button and createThread posts', async () => {
    const { container } = await mountAndSettle();
    const article = container.querySelector('article.article-prose');

    // After anchoring, the paragraph is split into
    //   ["The "][<mark>quick brown</mark>][" fox jumps"]
    // so select "fox" inside the trailing plain text node (a range over the
    // highlighted segment would straddle the <mark> and be unstable). Locate the
    // text node containing "fox" and build a range over just that word, so
    // captureSelection's selectorFromRange yields a selector; then point
    // window.getSelection at it.
    const p = article.querySelector('p');
    const foxNode = Array.from(p.childNodes).find(
      (n) => n.nodeType === Node.TEXT_NODE && n.data.includes('fox'));
    const foxStart = foxNode.data.indexOf('fox');
    const range = document.createRange();
    range.setStart(foxNode, foxStart);
    range.setEnd(foxNode, foxStart + 3); // "fox"
    const fakeSelection = {
      rangeCount: 1,
      isCollapsed: false,
      getRangeAt: () => range,
      removeAllRanges: vi.fn(),
    };
    vi.spyOn(window, 'getSelection').mockReturnValue(fakeSelection);

    await act(async () => { fireEvent.mouseUp(article); });

    const floating = await screen.findByRole('button', { name: /💬 Comment$/ });
    expect(floating).toBeInTheDocument();

    // createThread: prompt → api.createCommentThread(name, {selector..., text}).
    vi.spyOn(window, 'prompt').mockReturnValue('a new comment');
    await act(async () => { fireEvent.click(floating); });

    await waitFor(() => expect(api.createCommentThread).toHaveBeenCalledTimes(1));
    const [pageName, payload] = api.createCommentThread.mock.calls[0];
    expect(pageName).toBe('Foo');
    expect(payload.exact).toBe('fox');
    expect(payload.text).toBe('a new comment');
    // createThread opens the drawer after reload.
    await waitFor(() => expect(screen.getByText(/first comment/)).toBeInTheDocument());
  }, TEST_TIMEOUT);

  it('selection over math renders the math hint instead of the Comment button', async () => {
    const { container } = await mountAndSettle();
    const article = container.querySelector('article.article-prose');

    // Inject a KaTeX-like node and select inside it so selectionTouchesMath wins.
    const mathSpan = document.createElement('span');
    mathSpan.className = 'katex';
    mathSpan.textContent = 'x^2';
    article.appendChild(mathSpan);
    const range = document.createRange();
    range.selectNodeContents(mathSpan);
    vi.spyOn(window, 'getSelection').mockReturnValue({
      rangeCount: 1,
      isCollapsed: false,
      getRangeAt: () => range,
      removeAllRanges: vi.fn(),
    });

    await act(async () => { fireEvent.mouseUp(article); });

    expect(screen.getByText(/Can.t comment on math/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /💬 Comment$/ })).toBeNull();
  }, TEST_TIMEOUT);

  it('drawer Reply and Resolve callbacks call the api then reload threads', async () => {
    await mountAndSettle();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /💬 Comments \(1\)/ })); });

    // Reply.
    const replyInput = screen.getByPlaceholderText('Reply…');
    await act(async () => { fireEvent.change(replyInput, { target: { value: 'thanks' } }); });
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Reply' })); });
    await waitFor(() => expect(api.addCommentReply).toHaveBeenCalledWith('T1', 'thanks'));

    // Resolve → api.resolveCommentThread + loadThreads reload (finally block).
    const callsBefore = api.listCommentThreads.mock.calls.length;
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Resolve' })); });
    await waitFor(() => expect(api.resolveCommentThread).toHaveBeenCalledWith('T1'));
    await waitFor(() =>
      expect(api.listCommentThreads.mock.calls.length).toBeGreaterThan(callsBefore));
  }, TEST_TIMEOUT);

  it('drawer Reopen callback calls api.reopenCommentThread then reloads', async () => {
    // Seed a resolved thread up front so the Reopen button is present.
    api.listCommentThreads.mockResolvedValue({ threads: [{ ...THREAD, status: 'resolved' }] });
    renderPageView();
    await awaitStableLoaded();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /💬 Comments/ })); });

    // Default filter is "open"; switch to "resolved" to reveal the thread + Reopen.
    await act(async () => {
      fireEvent.change(screen.getByRole('combobox'), { target: { value: 'resolved' } });
    });
    const reopen = await screen.findByRole('button', { name: 'Reopen' });
    const callsBefore = api.listCommentThreads.mock.calls.length;
    await act(async () => { fireEvent.click(reopen); });
    await waitFor(() => expect(api.reopenCommentThread).toHaveBeenCalledWith('T1'));
    await waitFor(() =>
      expect(api.listCommentThreads.mock.calls.length).toBeGreaterThan(callsBefore));
  }, TEST_TIMEOUT);

  // --- error paths: every comment api call is wrapped in try/catch so a
  // transient failure logs a warning and keeps the UI alive rather than
  // throwing. These drive each catch branch deterministically by rejecting the
  // mock; console.warn is silenced so the failure is expected, not noise. ---

  it('listCommentThreads failure is swallowed (page still renders, no threads)', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.listCommentThreads.mockRejectedValue(new Error('boom'));
    renderPageView();
    await waitFor(() => expect(api.getPage.mock.calls.length).toBeGreaterThanOrEqual(2));
    // Page renders; the count badge shows no number (threads stayed []). Assert
    // both in one waitFor so a loading transient between queries can't trip us.
    await waitFor(() => {
      expect(screen.getByTestId('page-view')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /💬 Comments$/ })).toBeInTheDocument();
    });
    expect(warn).toHaveBeenCalledWith('Failed to load comment threads', expect.any(Error));
  }, TEST_TIMEOUT);

  it('createCommentThread failure is swallowed (drawer still opens)', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.createCommentThread.mockRejectedValue(new Error('nope'));
    const { container } = await mountAndSettle();
    const article = container.querySelector('article.article-prose');
    const p = article.querySelector('p');
    const foxNode = Array.from(p.childNodes).find(
      (n) => n.nodeType === Node.TEXT_NODE && n.data.includes('fox'));
    const foxStart = foxNode.data.indexOf('fox');
    const range = document.createRange();
    range.setStart(foxNode, foxStart);
    range.setEnd(foxNode, foxStart + 3);
    vi.spyOn(window, 'getSelection').mockReturnValue({
      rangeCount: 1, isCollapsed: false, getRangeAt: () => range, removeAllRanges: vi.fn(),
    });
    await act(async () => { fireEvent.mouseUp(article); });
    const floating = await screen.findByRole('button', { name: /💬 Comment$/ });
    vi.spyOn(window, 'prompt').mockReturnValue('hi');
    await act(async () => { fireEvent.click(floating); });
    await waitFor(() => expect(warn).toHaveBeenCalledWith('Failed to create comment thread', expect.any(Error)));
    // createThread still reloads + opens the drawer despite the failure.
    await waitFor(() => expect(screen.getByText(/first comment/)).toBeInTheDocument());
  }, TEST_TIMEOUT);

  it('drawer Reply and Resolve api failures are swallowed (warn logged)', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.addCommentReply.mockRejectedValue(new Error('x'));
    api.resolveCommentThread.mockRejectedValue(new Error('x'));
    await mountAndSettle();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /💬 Comments \(1\)/ })); });

    // Reply failure → warn + finally reload.
    await act(async () => { fireEvent.change(screen.getByPlaceholderText('Reply…'), { target: { value: 'r' } }); });
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Reply' })); });
    await waitFor(() => expect(warn).toHaveBeenCalledWith('Failed to add reply', expect.any(Error)));

    // Resolve failure → warn + finally reload.
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Resolve' })); });
    await waitFor(() => expect(warn).toHaveBeenCalledWith('Failed to resolve thread', expect.any(Error)));
  }, TEST_TIMEOUT);

  it('drawer Reopen api failure is swallowed (warn logged)', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.reopenCommentThread.mockRejectedValue(new Error('x'));
    // Seed a resolved thread up front so the Reopen button is present.
    api.listCommentThreads.mockResolvedValue({ threads: [{ ...THREAD, status: 'resolved' }] });
    renderPageView();
    await awaitStableLoaded();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /💬 Comments/ })); });
    await act(async () => {
      fireEvent.change(screen.getByRole('combobox'), { target: { value: 'resolved' } });
    });
    const reopen = await screen.findByRole('button', { name: 'Reopen' });
    await act(async () => { fireEvent.click(reopen); });
    await waitFor(() => expect(warn).toHaveBeenCalledWith('Failed to reopen thread', expect.any(Error)));
  }, TEST_TIMEOUT);
});
