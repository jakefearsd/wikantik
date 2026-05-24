import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { AuthProvider } from '../hooks/useAuth';
import SsoLoginButton from './SsoLoginButton';

// Mock api/client so AuthProvider.useEffect resolves a controlled user shape.
vi.mock('../api/client', () => ({
  api: {
    getUser: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
  },
}));

import { api } from '../api/client';

beforeEach(() => {
  vi.clearAllMocks();
});

function renderButton() {
  return render(
    <AuthProvider>
      <SsoLoginButton />
    </AuthProvider>,
  );
}

describe('SsoLoginButton', () => {
  it('renders a provider-labelled link to the SSO login URL when SSO is enabled', async () => {
    api.getUser.mockResolvedValue({
      authenticated: false,
      username: 'anonymous',
      roles: [],
      sso: { enabled: true, loginUrl: '/sso/login', providerLabel: 'Google' },
    });

    renderButton();

    const link = await screen.findByTestId('sso-login-button');
    expect(link).toHaveTextContent('Continue with Google');
    expect(link.getAttribute('href')).toBe('/sso/login');
    // The account-creation hint addresses the "no create-account entry" gap.
    expect(screen.getByText(/Signing in creates your account/i)).toBeInTheDocument();
  });

  it('renders nothing when SSO is disabled', async () => {
    api.getUser.mockResolvedValue({
      authenticated: false,
      username: 'anonymous',
      roles: [],
      sso: { enabled: false },
    });

    renderButton();

    // Wait for the provider effect to settle, then assert the button is absent.
    await waitFor(() => expect(api.getUser).toHaveBeenCalled());
    expect(screen.queryByTestId('sso-login-button')).toBeNull();
  });

  it('renders nothing when the sso descriptor is missing entirely', async () => {
    api.getUser.mockResolvedValue({ authenticated: false, username: 'anonymous', roles: [] });

    renderButton();

    await waitFor(() => expect(api.getUser).toHaveBeenCalled());
    expect(screen.queryByTestId('sso-login-button')).toBeNull();
  });
});
