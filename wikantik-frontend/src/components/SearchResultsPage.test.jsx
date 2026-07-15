import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

vi.mock('../api/client', () => ({
  api: { search: vi.fn() },
}));

vi.mock('../hooks/useApi', () => ({
  useApi: vi.fn(),
}));

import SearchResultsPage from './SearchResultsPage';
import { useApi } from '../hooks/useApi';

const makeResults = (n) =>
  Array.from({ length: n }, (_, i) => ({
    name: `Page${i + 1}`,
    summary: `Summary for page ${i + 1}`,
    score: 0.9,
  }));

const renderPage = (search = '') =>
  render(
    <MemoryRouter initialEntries={[`/search${search}`]}>
      <Routes>
        <Route path="/search" element={<SearchResultsPage />} />
      </Routes>
    </MemoryRouter>
  );

beforeEach(() => {
  vi.clearAllMocks();
  useApi.mockReturnValue({ data: { results: [] }, loading: false, error: null });
});

describe('SearchResultsPage', () => {
  it('shows empty search state with no query', () => {
    renderPage();
    expect(screen.getByText('Enter a search term to find articles.')).toBeInTheDocument();
  });

  it('renders result cards', () => {
    useApi.mockReturnValue({ data: { results: makeResults(3) }, loading: false, error: null });
    renderPage('?q=page');
    expect(screen.getAllByTestId('search-result-card')).toHaveLength(3);
  });

  it('#27 highlights query terms in result titles', () => {
    useApi.mockReturnValue({
      data: { results: [{ name: 'Hello World', summary: '', score: 1 }] },
      loading: false,
      error: null,
    });
    renderPage('?q=hello');
    const marks = document.querySelectorAll('mark');
    expect(marks.length).toBeGreaterThanOrEqual(1);
    expect(marks[0].textContent).toBe('Hello');
  });

  it('#27 highlights query terms in summaries', () => {
    useApi.mockReturnValue({
      data: {
        results: [{ name: 'MyPage', summary: 'This page is about testing', score: 1 }],
      },
      loading: false,
      error: null,
    });
    renderPage('?q=testing');
    const marks = document.querySelectorAll('mark');
    const matchTexts = Array.from(marks).map((m) => m.textContent);
    expect(matchTexts).toContain('testing');
  });

  // ── #28: Pagination / load-more ──────────────────────────────────────────

  it('#28 shows first 20 results initially when more than 20 exist', () => {
    useApi.mockReturnValue({
      data: { results: makeResults(25) },
      loading: false,
      error: null,
    });
    renderPage('?q=page');
    expect(screen.getAllByTestId('search-result-card')).toHaveLength(20);
    expect(screen.getByTestId('load-more-button')).toBeInTheDocument();
    expect(screen.getByTestId('results-count')).toHaveTextContent('Showing 20 of 25');
  });

  it('#28 clicking Load more shows all results and hides the button', () => {
    useApi.mockReturnValue({
      data: { results: makeResults(25) },
      loading: false,
      error: null,
    });
    renderPage('?q=page');
    fireEvent.click(screen.getByTestId('load-more-button'));
    expect(screen.getAllByTestId('search-result-card')).toHaveLength(25);
    expect(screen.queryByTestId('load-more-button')).not.toBeInTheDocument();
  });

  it('#28 does not show load-more when 20 or fewer results', () => {
    useApi.mockReturnValue({
      data: { results: makeResults(20) },
      loading: false,
      error: null,
    });
    renderPage('?q=page');
    expect(screen.getAllByTestId('search-result-card')).toHaveLength(20);
    expect(screen.queryByTestId('load-more-button')).not.toBeInTheDocument();
  });

  // ── Search faceting ──────────────────────────────────────────────────────

  const facetedResults = [
    { name: 'P1', summary: '', score: 1, cluster: 'Security', author: 'alice', tags: ['acl'] },
    { name: 'P2', summary: '', score: 1, cluster: 'Security', author: 'bob', tags: [] },
    { name: 'P3', summary: '', score: 1, cluster: 'Operations', author: 'alice', tags: [] },
  ];

  it('filters results when a facet chip is selected', () => {
    useApi.mockReturnValue({ data: { results: facetedResults }, loading: false, error: null });
    renderPage('?q=page');
    expect(screen.getAllByTestId('search-result-card')).toHaveLength(3);

    // Selecting the Operations topic narrows to the single matching result.
    fireEvent.click(screen.getByRole('button', { name: /Operations/ }));
    const cards = screen.getAllByTestId('search-result-card');
    expect(cards).toHaveLength(1);
    expect(cards[0]).toHaveAttribute('data-page-name', 'P3');
    expect(screen.getByTestId('search-results-heading')).toHaveTextContent('filtered from 3');
  });

  it('shows a no-match state with a clear control when filters exclude everything', () => {
    useApi.mockReturnValue({ data: { results: facetedResults }, loading: false, error: null });
    renderPage('?q=page');
    // Operations AND author bob never co-occur → empty.
    fireEvent.click(screen.getByRole('button', { name: /Operations/ }));
    fireEvent.click(screen.getByRole('button', { name: /bob/ }));
    expect(screen.queryAllByTestId('search-result-card')).toHaveLength(0);
    expect(screen.getByText('No results match these filters')).toBeInTheDocument();

    // Clearing restores the full set.
    fireEvent.click(screen.getByRole('button', { name: /Clear all filters/ }));
    expect(screen.getAllByTestId('search-result-card')).toHaveLength(3);
  });

  // ── Derived-page badge ───────────────────────────────────────────────────

  it('shows the derived badge for a result synced from an external source', () => {
    useApi.mockReturnValue({
      data: { results: [{ name: 'X', summary: '', score: 1, derived: true }] },
      loading: false,
      error: null,
    });
    renderPage('?q=page');
    expect(screen.getByTitle('Synced from an external source')).toBeInTheDocument();
  });

  it('does not show the derived badge for a non-derived result', () => {
    useApi.mockReturnValue({
      data: { results: [{ name: 'X', summary: '', score: 1 }] },
      loading: false,
      error: null,
    });
    renderPage('?q=page');
    expect(screen.queryByTitle('Synced from an external source')).not.toBeInTheDocument();
  });

  // #55 friendly empty state
  it('#55 shows EmptyState when search returns zero results', () => {
    useApi.mockReturnValue({ data: { results: [] }, loading: false, error: null });
    renderPage('?q=nothing');
    // EmptyState should render instead of a "No results" heading
    expect(screen.getByTestId('search-results-page')).toBeInTheDocument();
    // Should show empty state message
    expect(screen.getByText(/No results for "nothing"/)).toBeInTheDocument();
    // Should NOT render any result cards
    expect(screen.queryAllByTestId('search-result-card')).toHaveLength(0);
  });
});
