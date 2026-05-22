// AdminLayout.test.jsx
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import AdminLayout from './AdminLayout';

// Mutable auth value so each test can set the user/loading shape it needs.
let authValue = { user: { authenticated: true, roles: ['Admin'] }, loading: false };
vi.mock('../../hooks/useAuth', () => ({
  useAuth: () => authValue,
}));

beforeEach(() => {
  authValue = { user: { authenticated: true, roles: ['Admin'] }, loading: false };
});

// Renders AdminLayout under a router with a child route and a separate
// destination route so a <Navigate> redirect is observable by content.
function renderWithRoutes() {
  return render(
    <MemoryRouter initialEntries={['/admin/users']}>
      <Routes>
        <Route path="/admin" element={<AdminLayout />}>
          <Route path="users" element={<div>USERS CONTENT</div>} />
        </Route>
        <Route path="/wiki/Main" element={<div>WIKI MAIN PAGE</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('AdminLayout', () => {
  it('renders the routed child and no longer renders the old top-nav', () => {
    renderWithRoutes();
    expect(screen.getByText('USERS CONTENT')).toBeInTheDocument();
    // The old in-content nav link is gone (nav now lives in AdminSidebar).
    expect(screen.queryByRole('link', { name: 'Knowledge Graph' })).toBeNull();
  });

  it('redirects an authenticated non-admin to /wiki/Main', () => {
    authValue = { user: { authenticated: true, roles: ['User'] }, loading: false };
    renderWithRoutes();
    expect(screen.queryByText('USERS CONTENT')).toBeNull();
    expect(screen.getByText('WIKI MAIN PAGE')).toBeInTheDocument();
  });

  it('redirects an unauthenticated user to /wiki/Main', () => {
    authValue = { user: null, loading: false };
    renderWithRoutes();
    expect(screen.queryByText('USERS CONTENT')).toBeNull();
    expect(screen.getByText('WIKI MAIN PAGE')).toBeInTheDocument();
  });

  it('renders nothing (no child, no redirect) while auth is loading', () => {
    authValue = { user: null, loading: true };
    renderWithRoutes();
    expect(screen.queryByText('USERS CONTENT')).toBeNull();
    // Still loading — must not have redirected to the wiki yet either.
    expect(screen.queryByText('WIKI MAIN PAGE')).toBeNull();
  });
});
