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
});
