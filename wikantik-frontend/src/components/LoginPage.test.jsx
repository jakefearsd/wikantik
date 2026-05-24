import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider } from '../hooks/useAuth';
import LoginPage from './LoginPage';

// ---------------------------------------------------------------------------
// Mock api/client so AuthProvider.useEffect does not fire real HTTP calls.
// ---------------------------------------------------------------------------
vi.mock('../api/client', () => ({
  api: {
    getUser: vi.fn().mockResolvedValue({ authenticated: false, username: 'anonymous', roles: [] }),
    login: vi.fn(),
    logout: vi.fn(),
  },
}));

beforeEach(() => {
  vi.clearAllMocks();
});

/**
 * Render LoginPage inside a MemoryRouter with the given initial URL.
 * AuthProvider wraps it so useAuth() does not throw.
 */
function renderLoginPage(initialEntry = '/login') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe('LoginPage', () => {
  it('renders the login page container', () => {
    renderLoginPage('/login');
    expect(screen.getByTestId('login-page')).toBeInTheDocument();
  });

  it('shows the sso-error banner with sso_callback_failed message', () => {
    renderLoginPage('/login?error=sso_callback_failed');
    const banner = screen.getByTestId('sso-error');
    expect(banner).toBeInTheDocument();
    expect(banner).toHaveTextContent(
      'Single sign-on could not be completed. Please try signing in again.',
    );
  });

  it('shows the sso-error banner with no_sso_client message', () => {
    renderLoginPage('/login?error=no_sso_client');
    const banner = screen.getByTestId('sso-error');
    expect(banner).toBeInTheDocument();
    expect(banner).toHaveTextContent(
      'Single sign-on is not configured. Contact your administrator.',
    );
  });

  it('shows the sso-error banner with sso_redirect_failed message', () => {
    renderLoginPage('/login?error=sso_redirect_failed');
    const banner = screen.getByTestId('sso-error');
    expect(banner).toBeInTheDocument();
    expect(banner).toHaveTextContent(
      'Could not start single sign-on. Please try again.',
    );
  });

  it('shows a generic message for an unknown error code', () => {
    renderLoginPage('/login?error=mystery_code');
    const banner = screen.getByTestId('sso-error');
    expect(banner).toBeInTheDocument();
    expect(banner).toHaveTextContent('Sign-in failed. Please try again.');
  });

  it('does NOT render the sso-error banner when no error param is present', () => {
    renderLoginPage('/login');
    expect(screen.queryByTestId('sso-error')).not.toBeInTheDocument();
  });

  it('renders the username, password and submit fields', () => {
    renderLoginPage('/login');
    expect(screen.getByTestId('login-username')).toBeInTheDocument();
    expect(screen.getByTestId('login-password')).toBeInTheDocument();
    expect(screen.getByTestId('login-submit')).toBeInTheDocument();
  });
});
