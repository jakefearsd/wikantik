import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import SearchFacets from './SearchFacets';
import { EMPTY_SELECTION } from '../utils/searchFacets';

const FACETS = {
  clusters: [{ value: 'Security', count: 2 }, { value: 'Operations', count: 1 }],
  authors: [{ value: 'alice', count: 2 }],
  tags: [{ value: 'acl', count: 2 }, { value: 'auth', count: 1 }],
};

let handlers;
beforeEach(() => {
  handlers = { onToggle: vi.fn(), onSetSince: vi.fn(), onClear: vi.fn() };
});

const renderFacets = (selection = EMPTY_SELECTION, sinceKey = null) =>
  render(
    <SearchFacets facets={FACETS} selection={selection} sinceKey={sinceKey} {...handlers} />
  );

describe('SearchFacets', () => {
  it('renders facet values with their counts', () => {
    renderFacets();
    expect(screen.getByRole('button', { name: /Security/ })).toHaveTextContent('2');
    expect(screen.getByRole('button', { name: /Operations/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /alice/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /acl/ })).toBeInTheDocument();
  });

  it('calls onToggle with the group and value when a chip is clicked', () => {
    renderFacets();
    fireEvent.click(screen.getByRole('button', { name: /Security/ }));
    expect(handlers.onToggle).toHaveBeenCalledWith('clusters', 'Security');
    fireEvent.click(screen.getByRole('button', { name: /acl/ }));
    expect(handlers.onToggle).toHaveBeenCalledWith('tags', 'acl');
  });

  it('reflects active selection via aria-pressed', () => {
    renderFacets({ ...EMPTY_SELECTION, clusters: ['Security'] });
    expect(screen.getByRole('button', { name: /Security/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: /Operations/ })).toHaveAttribute('aria-pressed', 'false');
  });

  it('omits a facet group with no values', () => {
    render(
      <SearchFacets
        facets={{ clusters: [], authors: [], tags: [] }}
        selection={EMPTY_SELECTION}
        sinceKey={null}
        {...handlers}
      />
    );
    expect(screen.queryByText('Topic')).not.toBeInTheDocument();
    expect(screen.queryByText('Author')).not.toBeInTheDocument();
    expect(screen.queryByText('Tag')).not.toBeInTheDocument();
  });

  describe('date presets', () => {
    it('clears the since cutoff when "Any time" is chosen', () => {
      renderFacets({ ...EMPTY_SELECTION, since: 123 }, 'week');
      fireEvent.click(screen.getByRole('button', { name: /Any time/ }));
      expect(handlers.onSetSince).toHaveBeenCalledWith(null, null);
    });

    it('passes a numeric cutoff and key when a window is chosen', () => {
      renderFacets();
      fireEvent.click(screen.getByRole('button', { name: /Past week/ }));
      expect(handlers.onSetSince).toHaveBeenCalledTimes(1);
      const [ms, key] = handlers.onSetSince.mock.calls[0];
      expect(typeof ms).toBe('number');
      expect(key).toBe('week');
    });

    it('marks the active date preset via aria-pressed', () => {
      renderFacets({ ...EMPTY_SELECTION, since: 123 }, 'month');
      expect(screen.getByRole('button', { name: /Past month/ })).toHaveAttribute('aria-pressed', 'true');
      expect(screen.getByRole('button', { name: /Past week/ })).toHaveAttribute('aria-pressed', 'false');
    });
  });

  describe('clear all', () => {
    it('is hidden when no facets are active', () => {
      renderFacets();
      expect(screen.queryByRole('button', { name: /Clear filters/ })).not.toBeInTheDocument();
    });

    it('shows and calls onClear when facets are active', () => {
      renderFacets({ ...EMPTY_SELECTION, authors: ['alice'] });
      const clear = screen.getByRole('button', { name: /Clear filters/ });
      fireEvent.click(clear);
      expect(handlers.onClear).toHaveBeenCalledTimes(1);
    });
  });
});
