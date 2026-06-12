import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import Breadcrumbs from './Breadcrumbs';
import { usePageTrail } from '../hooks/usePageTrail';

vi.mock('../hooks/usePageTrail', () => ({ usePageTrail: vi.fn() }));

function withTrail(items) {
  usePageTrail.mockReturnValue({ items, record: vi.fn() });
  return render(
    <MemoryRouter>
      <Breadcrumbs />
    </MemoryRouter>
  );
}

beforeEach(() => usePageTrail.mockReset());

describe('Breadcrumbs (navigation history trail)', () => {
  it('renders nothing when the trail is empty', () => {
    const { container } = withTrail([]);
    expect(container.firstChild).toBeNull();
  });

  it('renders a single current entry (no link) when only one page is visited', () => {
    withTrail([{ slug: 'Main', title: 'Main' }]);
    const current = screen.getByText('Main');
    expect(current).toHaveAttribute('aria-current', 'page');
    expect(current.tagName).not.toBe('A');
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('renders prior pages as links and the current page as plain text', () => {
    withTrail([
      { slug: 'FastenerEngineering', title: 'Fastener Engineering' },
      { slug: 'HybridRetrieval', title: 'Hybrid Retrieval' },
      { slug: 'ThisPage', title: 'This Page' },
    ]);

    const first = screen.getByRole('link', { name: 'Fastener Engineering' });
    expect(first).toHaveAttribute('href', '/wiki/FastenerEngineering');
    const second = screen.getByRole('link', { name: 'Hybrid Retrieval' });
    expect(second).toHaveAttribute('href', '/wiki/HybridRetrieval');

    // The last (current) entry is not a link.
    const current = screen.getByText('This Page');
    expect(current).toHaveAttribute('aria-current', 'page');
    expect(current.tagName).not.toBe('A');
    expect(screen.getAllByRole('link')).toHaveLength(2);
  });

  it('puts a separator between entries (n-1 separators)', () => {
    withTrail([
      { slug: 'A', title: 'A' },
      { slug: 'B', title: 'B' },
      { slug: 'C', title: 'C' },
    ]);
    const svgs = document.querySelectorAll('.breadcrumbs-separator svg[aria-hidden="true"]');
    expect(svgs.length).toBe(2);
  });

  it('falls back to the slug when an entry has no title', () => {
    withTrail([{ slug: 'SomePage' }]);
    expect(screen.getByText('SomePage')).toHaveAttribute('aria-current', 'page');
  });

  it('uses a nav labelled "Recent pages"', () => {
    withTrail([{ slug: 'A', title: 'A' }]);
    expect(screen.getByRole('navigation', { name: 'Recent pages' })).toBeInTheDocument();
  });
});
