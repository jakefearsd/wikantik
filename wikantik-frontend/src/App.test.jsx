import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

// Mock all the heavy dependencies
vi.mock('./hooks/useAuth', () => ({ useAuth: vi.fn() }));
vi.mock('./components/Sidebar', () => ({ default: () => <div data-testid="sidebar" /> }));
vi.mock('./components/admin/AdminSidebar', () => ({ default: () => <div data-testid="admin-sidebar" /> }));
vi.mock('./components/SearchOverlay', () => ({
  default: ({ onClose }) => (
    <div data-testid="search-overlay">
      <input data-testid="search-overlay-input" autoFocus />
      <button onClick={onClose}>Close</button>
    </div>
  ),
}));

import App from './App';
import { useAuth } from './hooks/useAuth';

function renderApp(initialPath = '/') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path="/*" element={<App />} />
      </Routes>
    </MemoryRouter>
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  useAuth.mockReturnValue({ user: { authenticated: false, roles: [] } });
});

describe('App #23-finish — Cmd+K search on all routes', () => {
  it('Cmd+K opens search overlay on a regular wiki route', async () => {
    renderApp('/wiki/Main');
    expect(screen.queryByTestId('search-overlay')).not.toBeInTheDocument();

    fireEvent.keyDown(window, { key: 'k', metaKey: true });

    await waitFor(() => {
      expect(screen.getByTestId('search-overlay')).toBeInTheDocument();
    });
  });

  it('Cmd+K opens search overlay on an admin route', async () => {
    renderApp('/admin/users');
    expect(screen.queryByTestId('search-overlay')).not.toBeInTheDocument();

    fireEvent.keyDown(window, { key: 'k', metaKey: true });

    await waitFor(() => {
      expect(screen.getByTestId('search-overlay')).toBeInTheDocument();
    });
  });

  it('Ctrl+K also opens search overlay', async () => {
    renderApp('/admin');
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true });

    await waitFor(() => {
      expect(screen.getByTestId('search-overlay')).toBeInTheDocument();
    });
  });

  it('search overlay can be closed', async () => {
    renderApp('/admin/content');
    fireEvent.keyDown(window, { key: 'k', metaKey: true });
    await waitFor(() => expect(screen.getByTestId('search-overlay')).toBeInTheDocument());

    fireEvent.click(screen.getByText('Close'));
    await waitFor(() => expect(screen.queryByTestId('search-overlay')).not.toBeInTheDocument());
  });
});
