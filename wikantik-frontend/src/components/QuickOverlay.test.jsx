import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../api/client', () => ({
  api: { listPages: vi.fn(), search: vi.fn(), getRecentChanges: vi.fn() },
}));
vi.mock('../hooks/useAuth', () => ({ useAuth: () => ({ user: { loginPrincipal: 'me' } }) }));
vi.mock('../hooks/useRecentlyViewed', () => ({
  useRecentlyViewed: () => ({ items: [{ slug: 'RecentOne', title: 'Recent One' }], record: vi.fn() }),
}));
const mockNavigate = vi.fn();
vi.mock('../navigation/NavigationGuardProvider', () => ({ useGuardedNavigate: () => mockNavigate }));
const openNewPage = vi.fn();
vi.mock('../newpage/NewPageProvider', () => ({ useNewPage: () => ({ openNewPage }) }));
vi.mock('../hooks/useToast', () => ({ useToast: () => ({ showToast: vi.fn(), toast: vi.fn() }) }));

import { api } from '../api/client';
import { registerCommands, __resetRegistryForTest } from '../commands/registry';
import QuickOverlay from './QuickOverlay';

const renderOverlay = (props) => render(
  <MemoryRouter><QuickOverlay mode="pages" onClose={vi.fn()} {...props} /></MemoryRouter>);
const input = () => screen.getByTestId('search-overlay-input');
const type = (v) => fireEvent.change(input(), { target: { value: v } });
const settle = () => act(() => vi.advanceTimersByTimeAsync(300));

describe('QuickOverlay', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    __resetRegistryForTest();
    vi.clearAllMocks();
    api.getRecentChanges.mockResolvedValue({ changes: [] });
  });
  afterEach(() => { vi.useRealTimers(); });

  it('shows recently viewed pages for an empty query', () => {
    renderOverlay();
    expect(screen.getByText('Recent One')).toBeInTheDocument();
  });

  it('lists ranked page matches, then full-text matches, then the search and create rows', async () => {
    api.listPages.mockResolvedValue({ pages: [{ name: 'IndexFundsHub' }, { name: 'LowCostIndexFundInvesting' }] });
    api.search.mockResolvedValue({ results: [{ name: 'BondLadders' }, { name: 'IndexFundsHub' }] });
    renderOverlay();
    type('index fund');
    await settle();
    const rows = screen.getAllByTestId('quick-row').map((r) => `${r.dataset.kind}:${r.dataset.pageName ?? ''}`);
    expect(rows).toEqual(['page:IndexFundsHub', 'page:LowCostIndexFundInvesting', 'fulltext:BondLadders',
                          'search:', 'create:']);
    expect(api.listPages).toHaveBeenCalledWith({ q: 'index fund', limit: 8 });
    expect(screen.getByText('Search full text for “index fund”')).toBeInTheDocument();
    expect(screen.getByText('Create page “index fund”')).toBeInTheDocument();
  });

  it('omits the create row when a page name matches exactly', async () => {
    api.listPages.mockResolvedValue({ pages: [{ name: 'IndexFund' }] });
    api.search.mockResolvedValue({ results: [] });
    renderOverlay();
    type('IndexFund');
    await settle();
    expect(screen.queryByText(/Create page/)).toBeNull();
  });

  it('Enter opens the first page; Ctrl-Enter opens it in a new tab', async () => {
    const open = vi.spyOn(window, 'open').mockImplementation(() => null);
    api.listPages.mockResolvedValue({ pages: [{ name: 'Alpha' }] });
    api.search.mockResolvedValue({ results: [] });
    renderOverlay();
    type('alp');
    await settle();
    fireEvent.keyDown(input(), { key: 'Enter', ctrlKey: true });
    expect(open).toHaveBeenCalledWith('/wiki/Alpha', '_blank', 'noopener');
    fireEvent.keyDown(input(), { key: 'Enter' });
    expect(mockNavigate).toHaveBeenCalledWith('/wiki/Alpha');
    open.mockRestore();
  });

  it('arrow keys move the selection and Enter activates it', async () => {
    api.listPages.mockResolvedValue({ pages: [{ name: 'A1' }, { name: 'A2' }] });
    api.search.mockResolvedValue({ results: [] });
    renderOverlay();
    type('a');
    await settle();
    fireEvent.keyDown(input(), { key: 'ArrowDown' }); // selection starts on row 0 (A1)
    fireEvent.keyDown(input(), { key: 'Enter' });
    expect(mockNavigate).toHaveBeenCalledWith('/wiki/A2');
    fireEvent.keyDown(input(), { key: 'ArrowDown' }); // now the full-text search row
    fireEvent.keyDown(input(), { key: 'Enter' });
    expect(mockNavigate).toHaveBeenLastCalledWith('/search?q=a');
  });

  it('create row closes the overlay and opens the new-page dialog with the query', async () => {
    api.listPages.mockResolvedValue({ pages: [] });
    api.search.mockResolvedValue({ results: [] });
    const onClose = vi.fn();
    renderOverlay({ onClose });
    type('Brand New');
    await settle();
    fireEvent.click(screen.getByText('Create page “Brand New”'));
    expect(onClose).toHaveBeenCalled();
    expect(openNewPage).toHaveBeenCalledWith('Brand New');
  });

  it('shows "Page search failed" but keeps full-text results when the title search fails', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.listPages.mockRejectedValue(new Error('x'));
    api.search.mockResolvedValue({ results: [{ name: 'Still' }] });
    renderOverlay();
    type('st');
    await settle();
    expect(screen.getByText('Page search failed')).toBeInTheDocument();
    expect(screen.getByText('Still')).toBeInTheDocument();
  });

  it('ignores a stale title-search response that resolves after a newer one', async () => {
    let resolveOld;
    api.listPages.mockImplementation(({ q }) => (q === 'old'
      ? new Promise((r) => { resolveOld = r; })
      : Promise.resolve({ pages: [{ name: 'NewPage' }] })));
    api.search.mockResolvedValue({ results: [] });
    renderOverlay();
    type('old');
    await settle();
    type('new');
    await settle();
    await act(async () => { resolveOld({ pages: [{ name: 'OldPage' }] }); });
    const names = screen.getAllByTestId('quick-row').map((r) => r.dataset.pageName).filter(Boolean);
    expect(names).toEqual(['NewPage']);
  });

  it('switching mode while open (Ctrl-P over Ctrl-K, and back) re-seeds the query', () => {
    const onClose = vi.fn();
    const { rerender } = renderOverlay({ onClose });
    type('some page');
    rerender(<MemoryRouter><QuickOverlay mode="commands" onClose={onClose} /></MemoryRouter>);
    expect(input().value).toBe('>');
    rerender(<MemoryRouter><QuickOverlay mode="pages" onClose={onClose} /></MemoryRouter>);
    expect(input().value).toBe('');
  });

  it('command mode lists commands by fuzzy title and runs the chosen one after closing', async () => {
    const run = vi.fn();
    registerCommands([{ id: 'fold-all', title: 'Fold all headings', keys: 'Ctrl-Alt-[', run },
                      { id: 'unfold-all', title: 'Unfold all headings', run: vi.fn() }]);
    const onClose = vi.fn();
    renderOverlay({ mode: 'commands', onClose });
    expect(input().value).toBe('>');
    type('>fold');
    const ids = screen.getAllByTestId('quick-row').map((r) => r.dataset.commandId);
    expect(ids).toEqual(['fold-all', 'unfold-all']);
    expect(screen.getByText('Ctrl+Alt+[').tagName).toBe('KBD'); // jsdom is not a Mac
    expect(screen.getAllByTestId('quick-section').map((h) => h.textContent)).toEqual(['Commands']);
    fireEvent.keyDown(input(), { key: 'Enter' });
    expect(onClose).toHaveBeenCalled();
    await waitFor(() => expect(run).toHaveBeenCalled());
  });

  it('typing ">" switches a page query into command mode', () => {
    registerCommands([{ id: 'x', title: 'Toggle sidebar', run: vi.fn() }]);
    renderOverlay();
    type('>tog');
    expect(screen.getAllByTestId('quick-row')[0].dataset.commandId).toBe('x');
    expect(api.listPages).not.toHaveBeenCalled();
  });

  it('page and full-text rows show the title (or the de-CamelCased name) over the page name', async () => {
    api.listPages.mockResolvedValue({ pages: [
      { name: 'LowCostIndexFundInvesting', title: 'Low-Cost Index Fund Investing' }, { name: 'IndexFundsHub' }] });
    api.search.mockResolvedValue({ results: [{ name: 'BondLadders' }, { name: 'Still' }] });
    renderOverlay();
    type('index fund');
    await settle();
    const text = (name) => {
      const row = screen.getAllByTestId('quick-row').find((r) => r.dataset.pageName === name);
      return [row.querySelector('.quick-row-title')?.textContent, row.querySelector('.quick-row-name')?.textContent];
    };
    expect(text('LowCostIndexFundInvesting')).toEqual(['Low-Cost Index Fund Investing', 'LowCostIndexFundInvesting']);
    expect(text('IndexFundsHub')).toEqual(['Index Funds Hub', 'IndexFundsHub']);
    expect(text('BondLadders')).toEqual(['Bond Ladders', 'BondLadders']);
    expect(text('Still')).toEqual(['Still', undefined]); // no repeated name when the title is the name
  });

  it('groups results under non-selectable section headers with a divider before the actions', async () => {
    api.listPages.mockResolvedValue({ pages: [{ name: 'IndexFundsHub' }] });
    api.search.mockResolvedValue({ results: [{ name: 'BondLadders' }] });
    renderOverlay();
    type('index');
    await settle();
    expect(screen.getAllByTestId('quick-section').map((h) => h.textContent)).toEqual(['Pages', 'Full-text matches']);
    screen.getAllByTestId('quick-section').forEach((h) => expect(h).not.toHaveAttribute('role', 'option'));
    const divider = screen.getByTestId('quick-divider');
    expect(divider.nextElementSibling.dataset.kind).toBe('search');
    expect(divider.closest('.quick-overlay-footer')).not.toBeNull();
  });

  it('omits the Pages header when only full-text matches exist', async () => {
    api.listPages.mockResolvedValue({ pages: [] });
    api.search.mockResolvedValue({ results: [{ name: 'BondLadders' }] });
    renderOverlay();
    type('bond');
    await settle();
    expect(screen.getAllByTestId('quick-section').map((h) => h.textContent)).toEqual(['Full-text matches']);
  });

  it('labels the empty-query list "Recent"', () => {
    renderOverlay();
    expect(screen.getAllByTestId('quick-section').map((h) => h.textContent)).toEqual(['Recent']);
  });

  it('full-text, search and create rows carry an inline SVG icon', async () => {
    api.listPages.mockResolvedValue({ pages: [] });
    api.search.mockResolvedValue({ results: [{ name: 'BondLadders' }] });
    renderOverlay();
    type('bond');
    await settle();
    for (const kind of ['fulltext', 'search', 'create']) {
      const row = screen.getAllByTestId('quick-row').find((r) => r.dataset.kind === kind);
      expect(row.querySelector('svg'), kind).not.toBeNull();
    }
  });

  it('only one row is highlighted: hovering moves the single focus', async () => {
    api.listPages.mockResolvedValue({ pages: [{ name: 'A1' }, { name: 'A2' }] });
    api.search.mockResolvedValue({ results: [] });
    renderOverlay();
    type('a');
    await settle();
    const rows = screen.getAllByTestId('quick-row');
    fireEvent.mouseEnter(rows[1]);
    const focused = screen.getAllByTestId('quick-row').filter((r) => r.classList.contains('focused'));
    expect(focused).toEqual([rows[1]]);
    expect(rows[1]).toHaveAttribute('aria-selected', 'true');
    expect(rows[0]).toHaveAttribute('aria-selected', 'false');
  });

  it('the listbox exposes only options: headers and the divider are aria-hidden, the error is a disabled option', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.listPages.mockRejectedValue(new Error('x'));
    api.search.mockResolvedValue({ results: [{ name: 'BondLadders' }] });
    renderOverlay();
    type('bond');
    await settle();
    const listbox = screen.getByRole('listbox');
    // The scrolling results area and the pinned footer are role="none" wrappers, flattened out of the a11y tree.
    const wrappers = [...listbox.children];
    wrappers.forEach((w) => expect(w).toHaveAttribute('role', 'none'));
    const children = wrappers.flatMap((w) => [...w.children]);
    expect(children.length).toBeGreaterThan(0);
    children.forEach((el) => {
      if (el.getAttribute('aria-hidden') === 'true') return;
      expect(el).toHaveAttribute('role', 'option');
    });
    screen.getAllByTestId('quick-section').forEach((h) => expect(h).toHaveAttribute('aria-hidden', 'true'));
    expect(screen.getByTestId('quick-divider')).toHaveAttribute('aria-hidden', 'true');
    const error = screen.getAllByTestId('quick-row').find((r) => r.dataset.kind === 'error');
    expect(error).toHaveAttribute('aria-disabled', 'true');
  });

  it('scrolls the focused row into view on arrow keys but not when the mouse moves the focus', async () => {
    const scroll = vi.fn();
    const original = Element.prototype.scrollIntoView;
    Element.prototype.scrollIntoView = scroll;
    try {
      api.listPages.mockResolvedValue({ pages: [{ name: 'A1' }, { name: 'A2' }, { name: 'A3' }] });
      api.search.mockResolvedValue({ results: [] });
      renderOverlay();
      type('a');
      await settle();
      scroll.mockClear();
      fireEvent.mouseEnter(screen.getAllByTestId('quick-row')[2]);
      expect(scroll).not.toHaveBeenCalled();
      fireEvent.keyDown(input(), { key: 'ArrowUp' });
      expect(scroll).toHaveBeenCalledTimes(1);
      expect(scroll.mock.contexts[0].dataset.pageName).toBe('A2');
    } finally {
      Element.prototype.scrollIntoView = original;
    }
  });

  it('pins the search and create actions in a footer outside the scrolling results, reachable by arrow keys', async () => {
    const many = Array.from({ length: 8 }, (_, i) => ({ name: `IndexPage${i}` }));
    api.listPages.mockResolvedValue({ pages: many });
    api.search.mockResolvedValue({ results: [{ name: 'FullA' }, { name: 'FullB' }, { name: 'FullC' }] });
    renderOverlay();
    type('index fund');
    await settle();
    const byKind = (kind) => screen.getAllByTestId('quick-row').filter((r) => r.dataset.kind === kind);
    const scroller = document.querySelector('.quick-overlay-results');
    expect(scroller).not.toBeNull();
    [...byKind('page'), ...byKind('fulltext')].forEach((r) => expect(scroller.contains(r)).toBe(true));
    for (const kind of ['search', 'create']) {
      const [row] = byKind(kind);
      expect(scroller.contains(row), kind).toBe(false);
      expect(row.closest('.quick-overlay-footer'), kind).not.toBeNull();
    }
    const focusedKind = () => screen.getAllByTestId('quick-row').find((r) => r.classList.contains('focused')).dataset;
    for (let i = 0; i < 11; i += 1) fireEvent.keyDown(input(), { key: 'ArrowDown' });
    expect(focusedKind().kind).toBe('search');
    fireEvent.keyDown(input(), { key: 'ArrowDown' });
    expect(focusedKind().kind).toBe('create');
    fireEvent.keyDown(input(), { key: 'ArrowUp' });
    fireEvent.keyDown(input(), { key: 'ArrowUp' });
    expect(focusedKind().pageName).toBe('FullC');
    fireEvent.keyDown(input(), { key: 'ArrowDown' });
    fireEvent.keyDown(input(), { key: 'ArrowDown' });
    fireEvent.keyDown(input(), { key: 'Enter' });
    expect(openNewPage).toHaveBeenCalledWith('index fund');
  });

  it('Escape closes', () => {
    const onClose = vi.fn();
    renderOverlay({ onClose });
    fireEvent.keyDown(input(), { key: 'Escape' });
    expect(onClose).toHaveBeenCalled();
  });
});
