import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../hooks/useAuth', () => ({ useAuth: vi.fn() }));
vi.mock('./LoginForm', () => ({
  default: ({ onClose }) => (
    <div data-testid="login-form">
      <button onClick={onClose}>close-login</button>
    </div>
  ),
}));

import UserBadge from './UserBadge';
import { useAuth } from '../hooks/useAuth';

const renderBadge = () =>
  render(
    <MemoryRouter>
      <UserBadge />
    </MemoryRouter>
  );

beforeEach(() => {
  vi.clearAllMocks();
});

describe('UserBadge', () => {
  it('renders nothing while the user is unresolved (null)', () => {
    useAuth.mockReturnValue({ user: null, logout: vi.fn() });
    const { container } = renderBadge();
    expect(container).toBeEmptyDOMElement();
  });

  it('shows a Sign in button for an unauthenticated user', () => {
    useAuth.mockReturnValue({ user: { authenticated: false }, logout: vi.fn() });
    renderBadge();
    const badge = screen.getByTestId('user-badge');
    expect(badge).toHaveAttribute('data-authenticated', 'false');
    expect(screen.getByTestId('user-badge-signin')).toBeInTheDocument();
    expect(screen.queryByTestId('login-form')).not.toBeInTheDocument();
  });

  it('opens the login form when Sign in is clicked, and closes it again', () => {
    useAuth.mockReturnValue({ user: { authenticated: false }, logout: vi.fn() });
    renderBadge();

    fireEvent.click(screen.getByTestId('user-badge-signin'));
    expect(screen.getByTestId('login-form')).toBeInTheDocument();

    fireEvent.click(screen.getByText('close-login'));
    expect(screen.queryByTestId('login-form')).not.toBeInTheDocument();
  });

  it('shows the username, links to preferences, and a logout button for an authenticated user', () => {
    const logout = vi.fn();
    useAuth.mockReturnValue({
      user: { authenticated: true, username: 'alice', loginPrincipal: 'alice-principal' },
      logout,
    });
    renderBadge();

    const badge = screen.getByTestId('user-badge');
    expect(badge).toHaveAttribute('data-authenticated', 'true');
    expect(badge).toHaveAttribute('data-username', 'alice');
    expect(badge).toHaveAttribute('data-login-name', 'alice-principal');

    const nameLink = screen.getByTestId('user-badge-name');
    expect(nameLink).toHaveAttribute('href', '/preferences');
    expect(nameLink).toHaveTextContent('alice');

    fireEvent.click(screen.getByTestId('user-badge-logout'));
    expect(logout).toHaveBeenCalledTimes(1);
  });

  it('falls back to username for data-login-name when loginPrincipal is absent', () => {
    useAuth.mockReturnValue({
      user: { authenticated: true, username: 'bob' },
      logout: vi.fn(),
    });
    renderBadge();
    expect(screen.getByTestId('user-badge')).toHaveAttribute('data-login-name', 'bob');
  });
});
