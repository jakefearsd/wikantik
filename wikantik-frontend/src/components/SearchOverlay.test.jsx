import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

// Hoisted mocks — factory functions must not reference outer variables
const mockNavigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, useNavigate: () => mockNavigate };
});

vi.mock('../api/client', () => ({
  api: { search: vi.fn() },
}));

vi.mock('../hooks/useAuth', () => ({
  useAuth: vi.fn(() => ({ user: null })),
}));

vi.mock('../hooks/useRecentSearches', () => ({
  useRecentSearches: vi.fn(() => ({ searches: [], record: vi.fn(), clear: vi.fn() })),
}));

vi.mock('../hooks/useRecentlyViewed', () => ({
  useRecentlyViewed: vi.fn(() => ({ items: [], record: vi.fn() })),
}));

import SearchOverlay from './SearchOverlay';
import { api } from '../api/client';
import { useRecentSearches } from '../hooks/useRecentSearches';
import { useRecentlyViewed } from '../hooks/useRecentlyViewed';

const mockSearch = api.search;

const renderOverlay = (onClose = vi.fn()) =>
  render(
    <MemoryRouter>
      <SearchOverlay onClose={onClose} />
    </MemoryRouter>
  );

const makeResults = (names) => ({
  results: names.map((n) => ({ name: n, score: 0.9 })),
});

beforeEach(() => {
  vi.clearAllMocks();
  mockSearch.mockResolvedValue({ results: [] });
});

describe('SearchOverlay', () => {
  it('renders the input', () => {
    renderOverlay();
    expect(screen.getByTestId('search-overlay-input')).toBeInTheDocument();
  });

  it('shows empty state when query is empty', () => {
    renderOverlay();
    expect(screen.getByText('Type to search…')).toBeInTheDocument();
  });

  it('shows no-results message when query has no hits', async () => {
    mockSearch.mockResolvedValue({ results: [] });
    renderOverlay();
    await act(async () => {
      fireEvent.change(screen.getByTestId('search-overlay-input'), {
        target: { value: 'xyz' },
      });
      await new Promise((r) => setTimeout(r, 250));
    });
    expect(screen.getByText(/No results for/)).toBeInTheDocument();
  });

  it('renders result buttons after a search', async () => {
    mockSearch.mockResolvedValue(makeResults(['PageA', 'PageB']));
    renderOverlay();
    await act(async () => {
      fireEvent.change(screen.getByTestId('search-overlay-input'), {
        target: { value: 'hello' },
      });
      await new Promise((r) => setTimeout(r, 250));
    });
    const items = screen.getAllByTestId('search-overlay-result');
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveAttribute('data-page-name', 'PageA');
  });

  // ── #24: Enter opens focused result ──────────────────────────────────────

  it('#24 Enter without selecting a result opens the full search results page', async () => {
    mockSearch.mockResolvedValue(makeResults(['PageA', 'PageB']));
    renderOverlay();
    const input = screen.getByTestId('search-overlay-input');
    await act(async () => {
      fireEvent.change(input, { target: { value: 'page' } });
      await new Promise((r) => setTimeout(r, 250));
    });
    // No arrow-key selection -> Enter must land on /search, not open result #0.
    await act(async () => {
      fireEvent.keyDown(input, { key: 'Enter' });
    });
    expect(mockNavigate).toHaveBeenCalledWith('/search?q=page');
  });

  it('#24 Enter after ArrowDown opens the highlighted result', async () => {
    mockSearch.mockResolvedValue(makeResults(['PageA', 'PageB']));
    renderOverlay();
    const input = screen.getByTestId('search-overlay-input');
    await act(async () => {
      fireEvent.change(input, { target: { value: 'page' } });
      await new Promise((r) => setTimeout(r, 250));
    });
    // From the unselected state, one ArrowDown highlights the first result.
    await act(async () => {
      fireEvent.keyDown(input, { key: 'ArrowDown' });
      fireEvent.keyDown(input, { key: 'Enter' });
    });
    expect(mockNavigate).toHaveBeenCalledWith('/wiki/PageA');
  });

  it('#24 Enter with query but zero results navigates to search results page', async () => {
    mockSearch.mockResolvedValue({ results: [] });
    renderOverlay();
    const input = screen.getByTestId('search-overlay-input');
    await act(async () => {
      fireEvent.change(input, { target: { value: 'noresults' } });
      await new Promise((r) => setTimeout(r, 250));
    });
    await act(async () => {
      fireEvent.keyDown(input, { key: 'Enter' });
    });
    expect(mockNavigate).toHaveBeenCalledWith('/search?q=noresults');
  });

  // ── #25: Arrow-key wrap ───────────────────────────────────────────────────

  it('#25 ArrowDown on last result wraps to first (index 0)', async () => {
    mockSearch.mockResolvedValue(makeResults(['PageA', 'PageB', 'PageC']));
    renderOverlay();
    const input = screen.getByTestId('search-overlay-input');
    await act(async () => {
      fireEvent.change(input, { target: { value: 'page' } });
      await new Promise((r) => setTimeout(r, 250));
    });
    // From unselected (-1): three ArrowDowns -> index 0,1,2 (last item).
    await act(async () => {
      fireEvent.keyDown(input, { key: 'ArrowDown' });
      fireEvent.keyDown(input, { key: 'ArrowDown' });
      fireEvent.keyDown(input, { key: 'ArrowDown' });
    });
    const items = screen.getAllByTestId('search-overlay-result');
    expect(items[2]).toHaveClass('focused');
    // ArrowDown again should wrap to 0
    await act(async () => {
      fireEvent.keyDown(input, { key: 'ArrowDown' });
    });
    expect(items[0]).toHaveClass('focused');
    expect(items[2]).not.toHaveClass('focused');
  });

  it('#25 ArrowUp on index 0 wraps to last result', async () => {
    mockSearch.mockResolvedValue(makeResults(['PageA', 'PageB', 'PageC']));
    renderOverlay();
    const input = screen.getByTestId('search-overlay-input');
    await act(async () => {
      fireEvent.change(input, { target: { value: 'page' } });
      await new Promise((r) => setTimeout(r, 250));
    });
    const items = screen.getAllByTestId('search-overlay-result');
    // Nothing is highlighted until the user navigates.
    expect(items[0]).not.toHaveClass('focused');
    await act(async () => {
      fireEvent.keyDown(input, { key: 'ArrowUp' });
    });
    expect(items[2]).toHaveClass('focused');
    expect(items[0]).not.toHaveClass('focused');
  });

  // ── #26: Recent searches + recently viewed in empty state ────────────────

  it('#26 shows recent searches when query is empty', async () => {
    useRecentSearches.mockReturnValue({
      searches: ['previous query', 'another search'],
      record: vi.fn(),
      clear: vi.fn(),
    });
    renderOverlay();
    expect(screen.getByTestId('recent-searches-section')).toBeInTheDocument();
    expect(screen.getByText(/previous query/)).toBeInTheDocument();
    expect(screen.getByText(/another search/)).toBeInTheDocument();
  });

  it('#26 clicking a recent search sets the query input', async () => {
    useRecentSearches.mockReturnValue({
      searches: ['my search'],
      record: vi.fn(),
      clear: vi.fn(),
    });
    renderOverlay();
    const item = screen.getByTestId('recent-search-item');
    await act(async () => { fireEvent.click(item); });
    expect(screen.getByTestId('search-overlay-input')).toHaveValue('my search');
  });

  it('#26 shows recently viewed when query is empty', async () => {
    useRecentlyViewed.mockReturnValue({
      items: [{ slug: 'PageX', title: 'Page X' }],
      record: vi.fn(),
    });
    renderOverlay();
    expect(screen.getByTestId('recently-viewed-section')).toBeInTheDocument();
    expect(screen.getByText(/Page X/)).toBeInTheDocument();
  });

  it('#26 shows "Type to search" when both lists are empty', () => {
    useRecentSearches.mockReturnValue({ searches: [], record: vi.fn(), clear: vi.fn() });
    useRecentlyViewed.mockReturnValue({ items: [], record: vi.fn() });
    renderOverlay();
    expect(screen.getByText('Type to search…')).toBeInTheDocument();
  });

  it('#25 arrow keys are no-op when results are empty', async () => {
    mockSearch.mockResolvedValue({ results: [] });
    renderOverlay();
    const input = screen.getByTestId('search-overlay-input');
    await act(async () => {
      fireEvent.change(input, { target: { value: 'xyz' } });
      await new Promise((r) => setTimeout(r, 250));
    });
    // Should not throw
    await act(async () => {
      fireEvent.keyDown(input, { key: 'ArrowDown' });
      fireEvent.keyDown(input, { key: 'ArrowUp' });
    });
    expect(screen.queryAllByTestId('search-overlay-result')).toHaveLength(0);
  });

  // ── View-all affordance: a discoverable path to the full results page ─────

  it('shows a clickable "view all results" button when results exist', async () => {
    mockSearch.mockResolvedValue(makeResults(['PageA', 'PageB']));
    renderOverlay();
    const input = screen.getByTestId('search-overlay-input');
    await act(async () => {
      fireEvent.change(input, { target: { value: 'page' } });
      await new Promise((r) => setTimeout(r, 250));
    });
    const viewAll = screen.getByTestId('search-overlay-view-all');
    expect(viewAll).toBeInTheDocument();
    await act(async () => { fireEvent.click(viewAll); });
    expect(mockNavigate).toHaveBeenCalledWith('/search?q=page');
  });

  it('offers "view all results" even when quick results are empty', async () => {
    mockSearch.mockResolvedValue({ results: [] });
    renderOverlay();
    const input = screen.getByTestId('search-overlay-input');
    await act(async () => {
      fireEvent.change(input, { target: { value: 'obscure' } });
      await new Promise((r) => setTimeout(r, 250));
    });
    const viewAll = screen.getByTestId('search-overlay-view-all');
    expect(viewAll).toBeInTheDocument();
    await act(async () => { fireEvent.click(viewAll); });
    expect(mockNavigate).toHaveBeenCalledWith('/search?q=obscure');
  });

  it('does not show "view all results" before any query is entered', () => {
    renderOverlay();
    expect(screen.queryByTestId('search-overlay-view-all')).not.toBeInTheDocument();
  });
});
