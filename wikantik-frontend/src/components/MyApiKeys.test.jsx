import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import MyApiKeys from './MyApiKeys';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';
import { MemoryRouter } from 'react-router-dom';

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
  { id: 2, label: null, scope: 'mcp_admin', legacyScope: true, createdAt: '2026-06-02T10:00:00Z', lastUsedAt: null },
];

beforeEach(() => {
  vi.clearAllMocks();
  api.self.listApiKeys.mockResolvedValue({ keys: KEYS });
  useAuth.mockReturnValue({ user: { authenticated: true, roles: ['Authenticated'] } });
});

async function openScopeOptions() {
  render(<MemoryRouter><MyApiKeys /></MemoryRouter>);
  await screen.findByText('laptop');
  fireEvent.click(screen.getByRole('button', { name: /new key/i }));
  return within(screen.getByRole('combobox')).getAllByRole('option').map((o) => o.value);
}

describe('MyApiKeys', () => {
  it('lists the user’s keys', async () => {
    render(<MemoryRouter><MyApiKeys /></MemoryRouter>);
    await screen.findByText('laptop');
    expect(screen.getByText('tools')).toBeInTheDocument();
  });

  it('generates a key and reveals the token once', async () => {
    api.self.createApiKey.mockResolvedValue({ id: 3, label: 'ci', scope: 'all', token: 'wkk_SECRET' });
    render(<MemoryRouter><MyApiKeys /></MemoryRouter>);
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
    render(<MemoryRouter><MyApiKeys /></MemoryRouter>);
    await screen.findByText('laptop');

    // The row's "Revoke" opens a confirm whose primary button is "Revoke key".
    const row = screen.getByText('laptop').closest('tr');
    fireEvent.click(within(row).getByRole('button', { name: /revoke/i }));
    fireEvent.click(await screen.findByRole('button', { name: /revoke key/i }));
    await waitFor(() => expect(api.self.revokeApiKey).toHaveBeenCalledWith(1));
  });

  it('marks a key stored under the legacy mcp scope name', async () => {
    render(<MemoryRouter><MyApiKeys /></MemoryRouter>);
    const cell = (await screen.findByText('mcp_admin')).closest('td');
    expect(within(cell).getByText(/legacy 'mcp'/)).toBeInTheDocument();
    const toolsCell = screen.getByText('tools', { selector: 'code' }).closest('td');
    expect(within(toolsCell).queryByText(/legacy/)).toBeNull();
  });

  it('offersWorkScope: a non-admin gets the self-service scopes including work', async () => {
    expect(await openScopeOptions()).toEqual(['tools', 'mcp_read', 'work']);
  });

  it('offers an admin every scope', async () => {
    useAuth.mockReturnValue({ user: { authenticated: true, roles: ['Authenticated', 'Admin'] } });
    expect(await openScopeOptions()).toEqual(['tools', 'mcp_read', 'work', 'mcp_admin', 'all']);
  });

  describe('agent keys', () => {
    const AGENT_KEYS = [
      ...KEYS,
      { id: 7, label: 'work-agent:triage', scope: 'work', agentKey: true, agent: 'Triage',
        createdAt: '2026-06-03T10:00:00Z', lastUsedAt: null },
      { id: 8, label: 'work-agent:other', scope: 'work', agentKey: true,
        createdAt: '2026-06-03T10:00:00Z', lastUsedAt: null },
    ];

    it('badges an agent key with the agent name and offers no Rotate or Revoke', async () => {
      api.self.listApiKeys.mockResolvedValue({ keys: AGENT_KEYS });
      render(<MemoryRouter><MyApiKeys /></MemoryRouter>);
      const row = (await screen.findByText('work-agent:triage')).closest('tr');
      expect(within(row).getByText('agent key')).toBeInTheDocument();
      expect(within(row).getByText('Triage')).toBeInTheDocument();
      expect(within(row).getByText('Managed on the Agents page')).toBeInTheDocument();
      expect(within(row).queryByRole('button', { name: /rotate|revoke/i })).toBeNull();
      // an ordinary key keeps both
      const plain = screen.getByText('laptop').closest('tr');
      expect(within(plain).getByRole('button', { name: /rotate/i })).toBeInTheDocument();
      expect(within(plain).getByRole('button', { name: /revoke/i })).toBeInTheDocument();
    });

    it('shows the badge without a name when the server gave none, and no link for a non-admin', async () => {
      api.self.listApiKeys.mockResolvedValue({ keys: AGENT_KEYS });
      render(<MemoryRouter><MyApiKeys /></MemoryRouter>);
      const row = (await screen.findByText('work-agent:other')).closest('tr');
      expect(within(row).getByText('agent key')).toBeInTheDocument();
      expect(within(row).queryByRole('link')).toBeNull();
    });

    it('links an admin to the Agents page', async () => {
      useAuth.mockReturnValue({ user: { authenticated: true, roles: ['Authenticated', 'Admin'] } });
      api.self.listApiKeys.mockResolvedValue({ keys: AGENT_KEYS });
      render(<MemoryRouter><MyApiKeys /></MemoryRouter>);
      const row = (await screen.findByText('work-agent:triage')).closest('tr');
      expect(within(row).getByRole('link', { name: /agents page/i })).toHaveAttribute('href', '/work/agents');
    });

    it('a 409 on revoke closes the confirm dialog and shows the server message', async () => {
      const msg = 'This key belongs to a work agent. Rotate or retire the agent on the Agents page.';
      api.self.revokeApiKey.mockRejectedValue(Object.assign(new Error(msg), { status: 409 }));
      render(<MemoryRouter><MyApiKeys /></MemoryRouter>);
      const row = (await screen.findByText('laptop')).closest('tr');
      fireEvent.click(within(row).getByRole('button', { name: /revoke/i }));
      fireEvent.click(await screen.findByRole('button', { name: /revoke key/i }));
      expect(await screen.findByRole('alert')).toHaveTextContent(msg);
      expect(screen.queryByRole('button', { name: /revoke key/i })).toBeNull();
    });

    it('shows the server refusal from an older tab as that message', async () => {
      const msg = 'This key belongs to a work agent. Rotate or retire the agent on the Agents page.';
      api.self.rotateApiKey.mockRejectedValue(new Error(msg));
      render(<MemoryRouter><MyApiKeys /></MemoryRouter>);
      const row = (await screen.findByText('laptop')).closest('tr');
      fireEvent.click(within(row).getByRole('button', { name: /rotate/i }));
      expect(await screen.findByRole('alert')).toHaveTextContent(msg);
    });
  });
});
