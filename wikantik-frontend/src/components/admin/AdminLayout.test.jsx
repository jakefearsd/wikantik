// AdminLayout.test.jsx
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import AdminLayout from './AdminLayout';

vi.mock('../../hooks/useAuth', () => ({
  useAuth: () => ({ user: { authenticated: true, roles: ['Admin'] }, loading: false }),
}));

describe('AdminLayout', () => {
  it('renders the routed child and no longer renders the old top-nav', () => {
    render(
      <MemoryRouter initialEntries={['/admin/users']}>
        <Routes>
          <Route path="/admin" element={<AdminLayout />}>
            <Route path="users" element={<div>USERS CONTENT</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    );
    expect(screen.getByText('USERS CONTENT')).toBeInTheDocument();
    // The old in-content nav link is gone (nav now lives in AdminSidebar).
    expect(screen.queryByRole('link', { name: 'Knowledge Graph' })).toBeNull();
  });
});
