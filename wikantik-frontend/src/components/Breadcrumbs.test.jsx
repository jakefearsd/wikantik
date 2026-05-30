import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import Breadcrumbs from './Breadcrumbs';

function renderBreadcrumbs(page) {
  return render(
    <MemoryRouter>
      <Breadcrumbs page={page} />
    </MemoryRouter>
  );
}

describe('Breadcrumbs', () => {
  it('renders Home + cluster + page title when cluster is present', () => {
    renderBreadcrumbs({
      name: 'MyPage',
      title: 'My Page Title',
      metadata: { cluster: 'Engineering' },
    });
    const homeLink = screen.getByRole('link', { name: 'Home' });
    expect(homeLink).toHaveAttribute('href', '/');

    expect(screen.getByText('Engineering')).toBeInTheDocument();

    const current = screen.getByText('My Page Title');
    expect(current).toHaveAttribute('aria-current', 'page');

    // 3 items total
    const items = screen.getAllByRole('listitem');
    expect(items).toHaveLength(3);
  });

  it('renders Home + page title when no cluster', () => {
    renderBreadcrumbs({
      name: 'MyPage',
      title: 'My Page Title',
      metadata: {},
    });
    const homeLink = screen.getByRole('link', { name: 'Home' });
    expect(homeLink).toHaveAttribute('href', '/');

    const current = screen.getByText('My Page Title');
    expect(current).toHaveAttribute('aria-current', 'page');

    const items = screen.getAllByRole('listitem');
    expect(items).toHaveLength(2);
  });

  it('falls back to page name if title is absent', () => {
    renderBreadcrumbs({ name: 'SomePage', metadata: {} });
    expect(screen.getByText('SomePage')).toHaveAttribute('aria-current', 'page');
  });

  it('last crumb is not a link', () => {
    renderBreadcrumbs({ name: 'MyPage', title: 'My Page', metadata: {} });
    // The current page element should NOT be an anchor
    const current = screen.getByText('My Page');
    expect(current.tagName).not.toBe('A');
  });

  it('cluster crumb is not a link', () => {
    renderBreadcrumbs({
      name: 'MyPage',
      title: 'My Page',
      metadata: { cluster: 'Engineering' },
    });
    const cluster = screen.getByText('Engineering');
    expect(cluster.tagName).not.toBe('A');
  });

  it('uses nav with aria-label="Breadcrumb"', () => {
    renderBreadcrumbs({ name: 'MyPage', title: 'My Page', metadata: {} });
    expect(screen.getByRole('navigation', { name: 'Breadcrumb' })).toBeInTheDocument();
  });

  it('separators are aria-hidden', () => {
    renderBreadcrumbs({
      name: 'MyPage',
      title: 'My Page',
      metadata: { cluster: 'Engineering' },
    });
    // All SVG chevrons should be aria-hidden (Icon without title prop)
    const svgs = document.querySelectorAll('svg[aria-hidden="true"]');
    expect(svgs.length).toBeGreaterThan(0);
  });
});
