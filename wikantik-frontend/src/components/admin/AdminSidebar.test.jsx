// AdminSidebar.test.jsx
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect } from 'vitest';
import AdminSidebar from './AdminSidebar';

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

    // Knowledge & Search group
    expect(screen.getByTestId('admin-nav-knowledge-graph')).toHaveAttribute('href', '/admin/knowledge-graph');
    expect(screen.getByTestId('admin-nav-kg-policy')).toHaveAttribute('href', '/admin/kg-policy');
    expect(screen.getByTestId('admin-nav-retrieval-quality')).toHaveAttribute('href', '/admin/retrieval-quality');
  });
});
