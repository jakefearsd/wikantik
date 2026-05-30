import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react';
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

import SearchOverlay from './SearchOverlay';
import { api } from '../api/client';

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

  it('#24 Enter with results and focused index 0 navigates to first result', async () => {
    mockSearch.mockResolvedValue(makeResults(['PageA', 'PageB']));
    renderOverlay();
    const input = screen.getByTestId('search-overlay-input');
    await act(async () => {
      fireEvent.change(input, { target: { value: 'page' } });
      await new Promise((r) => setTimeout(r, 250));
    });
    await act(async () => {
      fireEvent.keyDown(input, { key: 'Enter' });
    });
    expect(mockNavigate).toHaveBeenCalledWith('/wiki/PageA');
  });

  it('#24 Enter after ArrowDown navigates to second result', async () => {
    mockSearch.mockResolvedValue(makeResults(['PageA', 'PageB']));
    renderOverlay();
    const input = screen.getByTestId('search-overlay-input');
    await act(async () => {
      fireEvent.change(input, { target: { value: 'page' } });
      await new Promise((r) => setTimeout(r, 250));
    });
    await act(async () => {
      fireEvent.keyDown(input, { key: 'ArrowDown' });
      fireEvent.keyDown(input, { key: 'Enter' });
    });
    expect(mockNavigate).toHaveBeenCalledWith('/wiki/PageB');
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
    // Navigate to last item (index 2)
    await act(async () => {
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
    // focused starts at 0
    expect(items[0]).toHaveClass('focused');
    await act(async () => {
      fireEvent.keyDown(input, { key: 'ArrowUp' });
    });
    expect(items[2]).toHaveClass('focused');
    expect(items[0]).not.toHaveClass('focused');
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
});
