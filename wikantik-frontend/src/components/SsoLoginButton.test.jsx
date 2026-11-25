import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { setReaderPageContext } from '../analytics/umami';
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
  // SSO config is cached stickily in localStorage; clear it so each case starts
  // from a known-empty cache rather than inheriting the previous test's value.
  localStorage.clear();
});

function renderButton(props = {}) {
  return render(
    <AuthProvider>
      <SsoLoginButton {...props} />
    </AuthProvider>,
  );
}

const ENABLED_USER = {
  authenticated: false,
  username: 'anonymous',
  roles: [],
  sso: { enabled: true, loginUrl: '/sso/login', providerLabel: 'Google' },
};

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
    expect(link.getAttribute('href')).toBe('/sso/login?return=%2F');
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

  it('still renders from cached config when the auth probe fails', async () => {
    // Sticky cache from a prior successful load.
    localStorage.setItem('wikantik.sso', JSON.stringify({
      enabled: true, loginUrl: '/sso/login', providerLabel: 'Google',
    }));
    // This load's probe fails (network/5xx/backpressure) — the button must survive.
    api.getUser.mockRejectedValue(new Error('network down'));

    renderButton();

    const link = await screen.findByTestId('sso-login-button');
    expect(link).toHaveTextContent('Continue with Google');
    expect(link.getAttribute('href')).toBe('/sso/login?return=%2F');
  });

  describe('return path (land back on the page you signed in from)', () => {
    afterEach(() => window.history.pushState({}, '', '/'));

    it('carries the current page, query and fragment by default', async () => {
      window.history.pushState({}, '', '/edit/SomePage?section=2#intro');
      api.getUser.mockResolvedValue(ENABLED_USER);

      renderButton();

      const link = await screen.findByTestId('sso-login-button');
      expect(link.getAttribute('href'))
        .toBe('/sso/login?return=' + encodeURIComponent('/edit/SomePage?section=2#intro'));
    });

    it('uses an explicit returnTo over the current page', async () => {
      window.history.pushState({}, '', '/login');
      api.getUser.mockResolvedValue(ENABLED_USER);

      renderButton({ returnTo: '/wiki/Target' });

      const link = await screen.findByTestId('sso-login-button');
      expect(link.getAttribute('href')).toBe('/sso/login?return=%2Fwiki%2FTarget');
    });

    it('sends no return path when returnTo is null', async () => {
      api.getUser.mockResolvedValue(ENABLED_USER);

      renderButton({ returnTo: null });

      const link = await screen.findByTestId('sso-login-button');
      expect(link.getAttribute('href')).toBe('/sso/login');
    });
  });

  it('sends sign-in-click with the canonical_id of the current page', async () => {
    const sent = [];
    window.umami = { track: vi.fn((fn) => { sent.push(fn({})); return Promise.resolve(); }) };
    setReaderPageContext(window.location.pathname, 'cid-5', undefined);
    api.getUser.mockResolvedValue(ENABLED_USER);
    renderButton();
    const link = await screen.findByTestId('sso-login-button');
    link.addEventListener('click', (e) => e.preventDefault());
    fireEvent.click(link);
    delete window.umami;
    expect(sent[0]).toMatchObject({ name: 'sign-in-click', data: { canonical_id: 'cid-5' } });
  });
});
