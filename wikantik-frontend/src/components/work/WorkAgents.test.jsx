import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';

vi.mock('../../api/work', () => ({
  listAgents: vi.fn(), listQueues: vi.fn(), createQueue: vi.fn(), updateQueue: vi.fn(), retireQueue: vi.fn(),
  registerAgent: vi.fn(), updateAgent: vi.fn(), rotateAgentKey: vi.fn(), retireAgent: vi.fn(),
}));
vi.mock('../../api/client', () => ({ api: { listMentionableUsers: vi.fn() } }));
vi.mock('../../hooks/useAuth', () => ({ useAuth: vi.fn() }));
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
vi.mock('../../hooks/useToast', () => ({ useToast: () => toast }));
import {
  listAgents, listQueues, createQueue, registerAgent, rotateAgentKey, retireAgent, retireQueue,
} from '../../api/work';
import { api } from '../../api/client';
import { useAuth } from '../../hooks/useAuth';
import WorkAgents from './WorkAgents';

const SECRET = 'wk_SECRET_TOKEN_VALUE_123';
const AGENTS = [
  { id: 2, name: 'old-bot', vendor: 'acme', runtime: 'x', queues: ['triage'], createdAt: '2026-01-01T00:00:00Z', retiredAt: '2026-02-01T00:00:00Z', apiKeyId: 99 },
  { id: 1, name: 'triage-bot', vendor: 'acme', runtime: 'claude', queues: ['triage'], createdAt: '2026-01-02T00:00:00Z', retiredAt: null, apiKeyId: 98 },
];
const QUEUES = [
  { key: 'triage', name: 'Triage', description: 'Incoming', retiredAt: null },
  { key: 'old-q', name: 'Old queue', description: '', retiredAt: '2026-01-01T00:00:00Z' },
];

function renderPage({ admin = true, authLoading = false } = {}) {
  useAuth.mockReturnValue(authLoading ? { user: null, loading: true }
    : { user: { authenticated: true, roles: admin ? ['Admin'] : [] }, loading: false });
  const ui = () => (
    <MemoryRouter initialEntries={['/work/agents']}>
      <Routes>
        <Route path="/work/agents" element={<WorkAgents />} />
        <Route path="/work" element={<div>HOME</div>} />
      </Routes>
    </MemoryRouter>
  );
  const r = render(ui());
  return { ...r, again: () => r.rerender(ui()) };
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear(); sessionStorage.clear();
  listAgents.mockResolvedValue(AGENTS);
  listQueues.mockResolvedValue(QUEUES);
  api.listMentionableUsers.mockResolvedValue({ users: [{ id: 'u1', displayName: 'Alice A' }, { id: 'u2', displayName: 'Bob B' }] });
  createQueue.mockResolvedValue({});
  registerAgent.mockResolvedValue({ agent: AGENTS[1], token: SECRET });
  rotateAgentKey.mockResolvedValue({ agent: AGENTS[1], token: SECRET });
  retireAgent.mockResolvedValue(undefined);
  retireQueue.mockResolvedValue(undefined);
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: vi.fn().mockResolvedValue(undefined) } });
});

async function openRegister() {
  await screen.findByText('triage-bot');
  fireEvent.click(screen.getByRole('button', { name: 'Register agent' }));
  fireEvent.change(screen.getByLabelText('Agent name'), { target: { value: 'new-bot' } });
}

describe('WorkAgents', () => {
  it('adminOnly: a non-admin is sent home and nothing is loaded', async () => {
    renderPage({ admin: false });
    expect(await screen.findByText('HOME')).toBeInTheDocument();
    expect(listAgents).not.toHaveBeenCalled();
    expect(listQueues).not.toHaveBeenCalled();
  });

  it('does not bounce an admin whose user arrives late', async () => {
    const { again } = renderPage({ authLoading: true });
    expect(screen.queryByText('HOME')).not.toBeInTheDocument();
    useAuth.mockReturnValue({ user: { authenticated: true, roles: ['Admin'] }, loading: false });
    again();
    expect(await screen.findByText('triage-bot')).toBeInTheDocument();
    expect(screen.queryByText('HOME')).not.toBeInTheDocument();
  });

  it('lists agents with queues by name and never shows an api key id', async () => {
    renderPage();
    const row = (await screen.findByText('triage-bot')).closest('li');
    expect(within(row).getByText('Triage')).toBeInTheDocument();
    expect(within(row).getByText(/acme/)).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/98|99/);
  });

  it('retiredRowsShownMutedAndLast', async () => {
    renderPage();
    await screen.findByText('triage-bot');
    const rows = screen.getAllByTestId('agent-row');
    expect(rows[0]).toHaveTextContent('triage-bot');
    expect(rows[1]).toHaveTextContent('old-bot');
    expect(rows[1]).toHaveClass('work-retired');
    expect(within(rows[1]).getByText('retired')).toBeInTheDocument();
    expect(within(rows[1]).queryByRole('button', { name: /Rotate/ })).not.toBeInTheDocument();
  });

  it('createQueueValidatesKeyPattern', async () => {
    renderPage();
    await screen.findByText('triage-bot');
    fireEvent.click(screen.getByRole('button', { name: 'New queue' }));
    for (const bad of ['A', 'Bad', '1abc', 'a', 'has space', `a${'b'.repeat(30)}`]) {
      fireEvent.change(screen.getByLabelText('Queue key'), { target: { value: bad } });
      fireEvent.change(screen.getByLabelText('Queue name'), { target: { value: 'N' } });
      fireEvent.click(screen.getByRole('button', { name: 'Create queue' }));
      expect(screen.getByRole('alert')).toHaveTextContent(/Key must start/);
    }
    expect(createQueue).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('Queue key'), { target: { value: 'review-q' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create queue' }));
    await waitFor(() => expect(createQueue).toHaveBeenCalledWith({ key: 'review-q', name: 'N', description: '' }));
  });

  it('shows the server field message for a duplicate queue key', async () => {
    createQueue.mockRejectedValue(Object.assign(new Error('That queue already exists.'), { status: 409, body: { reason: 'exists' } }));
    renderPage();
    await screen.findByText('triage-bot');
    fireEvent.click(screen.getByRole('button', { name: 'New queue' }));
    fireEvent.change(screen.getByLabelText('Queue key'), { target: { value: 'triage' } });
    fireEvent.change(screen.getByLabelText('Queue name'), { target: { value: 'T' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create queue' }));
    expect(await screen.findByText('That queue already exists.')).toBeInTheDocument();
  });

  it('registerValidatesNamePattern', async () => {
    renderPage();
    await openRegister();
    for (const bad of ['Bad Name', '-x', '', 'UPPER']) {
      fireEvent.change(screen.getByLabelText('Agent name'), { target: { value: bad } });
      fireEvent.click(screen.getByRole('button', { name: 'Register' }));
      expect(screen.getByRole('alert')).toHaveTextContent(/Agent name/);
    }
    expect(registerAgent).not.toHaveBeenCalled();
  });

  it('ownerPickerUsesDisplayNames', async () => {
    renderPage();
    await openRegister();
    const owner = await screen.findByLabelText('Key owner');
    expect(within(owner).getByRole('option', { name: 'Alice A' })).toBeInTheDocument();
    fireEvent.change(owner, { target: { value: 'Bob B' } });
    fireEvent.click(screen.getByLabelText('Triage'));
    fireEvent.click(screen.getByRole('button', { name: 'Register' }));
    await waitFor(() => expect(registerAgent).toHaveBeenCalledWith(
      { name: 'new-bot', vendor: '', runtime: '', queues: ['triage'], owner: 'Bob B' }));
  });

  it('registerShowsTokenOnceWithCopyAndWarning', async () => {
    renderPage();
    await openRegister();
    fireEvent.click(screen.getByRole('button', { name: 'Register' }));
    const panel = await screen.findByRole('dialog', { name: /key/i });
    expect(within(panel).getByText(SECRET)).toBeInTheDocument();
    expect(panel).toHaveTextContent(/cannot be shown again/i);
    fireEvent.click(within(panel).getByRole('button', { name: 'Copy' }));
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(SECRET);
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    fireEvent.click(within(panel).getByRole('button', { name: 'Done' }));
    expect(screen.queryByText(SECRET)).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain(SECRET);
    expect(toast.success).not.toHaveBeenCalledWith(expect.stringContaining(SECRET));
  });

  it('removes the token when the page unmounts', async () => {
    const { unmount } = renderPage();
    await openRegister();
    fireEvent.click(screen.getByRole('button', { name: 'Register' }));
    await screen.findByText(SECRET);
    unmount();
    expect(document.body.textContent).not.toContain(SECRET);
  });

  it('a failed register shows the field error and no token panel', async () => {
    registerAgent.mockRejectedValue(Object.assign(new Error('That agent name is taken.'), { status: 409, body: { reason: 'exists' } }));
    renderPage();
    await openRegister();
    fireEvent.click(screen.getByRole('button', { name: 'Register' }));
    expect(await screen.findByText('That agent name is taken.')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('rotateAsksInlineConfirmationThenShowsNewToken', async () => {
    renderPage();
    const row = (await screen.findByText('triage-bot')).closest('li');
    fireEvent.click(within(row).getByRole('button', { name: 'Rotate key of triage-bot' }));
    expect(rotateAgentKey).not.toHaveBeenCalled();
    expect(screen.getByRole('alertdialog')).toHaveTextContent(/old key stops working/i);
    fireEvent.click(screen.getByRole('button', { name: /Yes, rotate/ }));
    expect(await screen.findByText(SECRET)).toBeInTheDocument();
    expect(rotateAgentKey).toHaveBeenCalledWith(1);
    fireEvent.click(screen.getByRole('button', { name: 'Done' }));
    expect(screen.queryByText(SECRET)).not.toBeInTheDocument();
  });

  it('retireAsksInlineConfirmation and says what it does', async () => {
    renderPage();
    const row = (await screen.findByText('triage-bot')).closest('li');
    fireEvent.click(within(row).getByRole('button', { name: 'Retire triage-bot' }));
    expect(retireAgent).not.toHaveBeenCalled();
    const confirm = screen.getByRole('alertdialog');
    expect(confirm).toHaveTextContent(/releases all its claims/i);
    expect(confirm).toHaveTextContent(/revokes its key/i);
    expect(confirm).toHaveTextContent(/cannot be undone/i);
    fireEvent.click(screen.getByRole('button', { name: /Yes, retire/ }));
    await waitFor(() => expect(retireAgent).toHaveBeenCalledWith(1));
  });

  it('retires a queue after confirmation', async () => {
    renderPage();
    const row = (await screen.findByText('Incoming')).closest('li');
    fireEvent.click(within(row).getByRole('button', { name: 'Retire queue triage' }));
    fireEvent.click(screen.getByRole('button', { name: /Yes, retire/ }));
    await waitFor(() => expect(retireQueue).toHaveBeenCalledWith('triage'));
  });
});
