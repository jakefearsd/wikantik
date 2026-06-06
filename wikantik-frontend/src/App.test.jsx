import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

// Mock all the heavy dependencies
vi.mock('./hooks/useAuth', () => ({ useAuth: vi.fn() }));
// Sidebar mock captures and exposes the onOpenSearch prop so App tests can
// fire the sidebar search trigger and verify the single shared overlay opens.
let capturedOnOpenSearch = null;
vi.mock('./components/Sidebar', () => ({
  default: (props) => {
    capturedOnOpenSearch = props.onOpenSearch;
    return <div data-testid="sidebar" />;
  },
}));
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
import { act } from '@testing-library/react';

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
  capturedOnOpenSearch = null;
  useAuth.mockReturnValue({ user: { authenticated: false, roles: [] } });
});

describe('App #23 — single shared SearchOverlay', () => {
  it('passes onOpenSearch prop to Sidebar', async () => {
    renderApp('/wiki/Main');
    expect(typeof capturedOnOpenSearch).toBe('function');
  });

  it('sidebar onOpenSearch opens the single shared overlay (exactly one)', async () => {
    renderApp('/wiki/Main');
    expect(screen.queryByTestId('search-overlay')).not.toBeInTheDocument();
    await act(async () => { capturedOnOpenSearch(); });
    // Only one search-overlay in the entire tree.
    expect(screen.getAllByTestId('search-overlay').length).toBe(1);
  });

  it('Cmd+K and sidebar onOpenSearch both open the same overlay — still only one', async () => {
    renderApp('/wiki/Main');
    await act(async () => { capturedOnOpenSearch(); });
    // Fire Cmd+K while already open — should not duplicate.
    fireEvent.keyDown(window, { key: 'k', metaKey: true });
    await waitFor(() =>
      expect(screen.getAllByTestId('search-overlay').length).toBe(1));
  });
});

describe('App — footer version', () => {
  it('shows the build version in the footer on reader routes', () => {
    renderApp('/wiki/Main');
    const version = screen.getByTestId('app-version');
    expect(version).toBeInTheDocument();
    // The vite build-version plugin define falls back to 'dev' in tests.
    expect(version).toHaveTextContent('dev');
  });

  it('hides the footer version on admin routes', () => {
    renderApp('/admin/users');
    expect(screen.queryByTestId('app-version')).not.toBeInTheDocument();
  });
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
