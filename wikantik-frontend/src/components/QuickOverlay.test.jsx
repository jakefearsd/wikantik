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
    expect(screen.getByText('Ctrl-Alt-[')).toBeInTheDocument();
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

  it('Escape closes', () => {
    const onClose = vi.fn();
    renderOverlay({ onClose });
    fireEvent.keyDown(input(), { key: 'Escape' });
    expect(onClose).toHaveBeenCalled();
  });
});
