// AdminSidebar.test.jsx
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../../hooks/useCapabilities', () => ({ useCapabilities: vi.fn() }));

import AdminSidebar from './AdminSidebar';
import { useCapabilities } from '../../hooks/useCapabilities';

beforeEach(() => {
  useCapabilities.mockReturnValue({
    capabilities: { knowledgeGraph: true, hybridSearch: true, genaiMode: 'full', ontology: true, connectors: true, citations: true },
    loading: false,
  });
});

function renderAt(path) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AdminSidebar />
    </MemoryRouter>
  );
}

describe('AdminSidebar', () => {
  it('renders the back-to-wiki link and the Overview entry', () => {
    renderAt('/admin/users');
    expect(screen.getByRole('link', { name: /back to wiki/i })).toHaveAttribute('href', '/wiki/Main');
    expect(screen.getByRole('link', { name: 'Overview' })).toHaveAttribute('href', '/admin');
  });

  it('renders all three group headings', () => {
    renderAt('/admin/users');
    ['People & Access', 'Content', 'Knowledge & Search'].forEach((g) =>
      expect(screen.getByText(g)).toBeInTheDocument()
    );
  });

  it('marks the active section link', () => {
    renderAt('/admin/security');
    expect(screen.getByRole('link', { name: 'Security' }).className).toMatch(/active/);
    expect(screen.getByRole('link', { name: 'Users' }).className).not.toMatch(/active/);
  });

  it('has data-testid on the sidebar root, back-to-wiki link, and every nav link', () => {
    renderAt('/admin/users');

    // Sidebar root
    expect(screen.getByTestId('admin-sidebar')).toBeInTheDocument();

    // Back-to-wiki link
    expect(screen.getByTestId('admin-back-to-wiki')).toHaveAttribute('href', '/wiki/Main');

    // Overview (exact /admin match)
    expect(screen.getByTestId('admin-nav-overview')).toHaveAttribute('href', '/admin');

    // People & Access group
    expect(screen.getByTestId('admin-nav-users')).toHaveAttribute('href', '/admin/users');
    expect(screen.getByTestId('admin-nav-security')).toHaveAttribute('href', '/admin/security');
    expect(screen.getByTestId('admin-nav-apikeys')).toHaveAttribute('href', '/admin/apikeys');

    // Content group
    expect(screen.getByTestId('admin-nav-content')).toHaveAttribute('href', '/admin/content');
    expect(screen.getByTestId('admin-nav-page-ownership')).toHaveAttribute('href', '/admin/page-ownership');

    // Knowledge & Search group
    expect(screen.getByTestId('admin-nav-knowledge-graph')).toHaveAttribute('href', '/admin/knowledge-graph');
    expect(screen.getByTestId('admin-nav-kg-policy')).toHaveAttribute('href', '/admin/kg-policy');
    expect(screen.getByTestId('admin-nav-retrieval-quality')).toHaveAttribute('href', '/admin/retrieval-quality');
  });

  describe('Knowledge Graph nav gating (capabilities)', () => {
    it('omits the Knowledge Graph link when capabilities.knowledgeGraph is false', () => {
      useCapabilities.mockReturnValue({
        capabilities: { knowledgeGraph: false }, loading: false,
      });
      renderAt('/admin/users');
      expect(screen.queryByTestId('admin-nav-knowledge-graph')).not.toBeInTheDocument();
      // Sibling links in the same group are unaffected.
      expect(screen.getByTestId('admin-nav-kg-policy')).toHaveAttribute('href', '/admin/kg-policy');
    });

    it('renders the Knowledge Graph link while capabilities is loading (fail-open)', () => {
      useCapabilities.mockReturnValue({
        capabilities: { knowledgeGraph: true }, loading: true,
      });
      renderAt('/admin/users');
      expect(screen.getByTestId('admin-nav-knowledge-graph')).toHaveAttribute('href', '/admin/knowledge-graph');
    });
  });
});
