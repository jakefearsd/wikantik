import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import MyApiKeys from './MyApiKeys';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';

vi.mock('../hooks/useAuth', () => ({ useAuth: vi.fn() }));

vi.mock('../api/client', () => ({
  api: { self: {
    listApiKeys: vi.fn(),
    createApiKey: vi.fn(),
    rotateApiKey: vi.fn(),
    revokeApiKey: vi.fn(),
  } },
}));

const KEYS = [
  { id: 1, label: 'laptop', scope: 'tools', createdAt: '2026-06-01T10:00:00Z', lastUsedAt: '2026-06-18T14:40:00Z' },
  { id: 2, label: null, scope: 'mcp', createdAt: '2026-06-02T10:00:00Z', lastUsedAt: null },
];

beforeEach(() => {
  vi.clearAllMocks();
  api.self.listApiKeys.mockResolvedValue({ keys: KEYS });
  useAuth.mockReturnValue({ user: { authenticated: true, roles: ['Authenticated'] } });
});

async function openScopeOptions() {
  render(<MyApiKeys />);
  await screen.findByText('laptop');
  fireEvent.click(screen.getByRole('button', { name: /new key/i }));
  return within(screen.getByRole('combobox')).getAllByRole('option').map((o) => o.value);
}

describe('MyApiKeys', () => {
  it('lists the user’s keys', async () => {
    render(<MyApiKeys />);
    await screen.findByText('laptop');
    expect(screen.getByText('tools')).toBeInTheDocument();
  });

  it('generates a key and reveals the token once', async () => {
    api.self.createApiKey.mockResolvedValue({ id: 3, label: 'ci', scope: 'all', token: 'wkk_SECRET' });
    render(<MyApiKeys />);
    await screen.findByText('laptop');

    // Toolbar "+ New key" opens the form; the form's "Generate key" submit does the work.
    fireEvent.click(screen.getByRole('button', { name: /new key/i }));
    fireEvent.click(screen.getByRole('button', { name: /generate key/i }));
    // Reveal modal shows the token once.
    expect(await screen.findByText('wkk_SECRET')).toBeInTheDocument();
    expect(api.self.createApiKey).toHaveBeenCalled();
  });

  it('revokes a key after confirmation', async () => {
    api.self.revokeApiKey.mockResolvedValue({ success: true, id: 1 });
    render(<MyApiKeys />);
    await screen.findByText('laptop');

    // The row's "Revoke" opens a confirm whose primary button is "Revoke key".
    const row = screen.getByText('laptop').closest('tr');
    fireEvent.click(within(row).getByRole('button', { name: /revoke/i }));
    fireEvent.click(await screen.findByRole('button', { name: /revoke key/i }));
    await waitFor(() => expect(api.self.revokeApiKey).toHaveBeenCalledWith(1));
  });

  it('offers a non-admin only the self-service scopes', async () => {
    expect(await openScopeOptions()).toEqual(['tools', 'mcp_read']);
  });

  it('offers an admin every scope', async () => {
    useAuth.mockReturnValue({ user: { authenticated: true, roles: ['Authenticated', 'Admin'] } });
    expect(await openScopeOptions()).toEqual(['tools', 'mcp_read', 'mcp', 'all']);
  });
});
