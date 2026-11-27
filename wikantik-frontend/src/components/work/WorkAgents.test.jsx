import { StrictMode } from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within, act } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';

vi.mock('../../api/work', () => ({
  listAgents: vi.fn(), listQueues: vi.fn(), createQueue: vi.fn(), updateQueue: vi.fn(), retireQueue: vi.fn(),
  registerAgent: vi.fn(), updateAgent: vi.fn(), rotateAgentKey: vi.fn(), retireAgent: vi.fn(),
  takeOverAgent: vi.fn(), releaseAgentSponsorship: vi.fn(), listProjects: vi.fn(), putMember: vi.fn(),
}));
vi.mock('../../api/client', () => ({ api: { listMentionableUsers: vi.fn() }, apiUrl: (p) => p }));
vi.mock('../../hooks/useAuth', () => ({ useAuth: vi.fn() }));
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
vi.mock('../../hooks/useToast', () => ({ useToast: () => toast }));
import {
  listAgents, listQueues, createQueue, registerAgent, rotateAgentKey, retireAgent, retireQueue, updateAgent, updateQueue,
  takeOverAgent, releaseAgentSponsorship, listProjects, putMember,
} from '../../api/work';
import { api } from '../../api/client';
import { useAuth } from '../../hooks/useAuth';
import WorkAgents from './WorkAgents';

const SECRET = 'wk_SECRET_TOKEN_VALUE_123';
const AGENTS = [
  { id: 2, name: 'old-bot', vendor: 'acme', runtime: 'x', queues: ['triage'], createdAt: '2026-01-01T00:00:00Z', retiredAt: '2026-02-01T00:00:00Z', sponsorName: 'Root Admin', status: 'retired', sponsoredByMe: true },
  { id: 1, name: 'triage-bot', vendor: 'acme', runtime: 'claude', queues: ['triage'], createdAt: '2026-01-02T00:00:00Z', retiredAt: null, sponsorName: 'Root Admin', status: 'active', sponsoredByMe: true },
];
const QUEUES = [
  { key: 'triage', name: 'Triage', description: 'Incoming', retiredAt: null },
  { key: 'old-q', name: 'Old queue', description: '', retiredAt: '2026-01-01T00:00:00Z' },
];

function renderPage({ admin = true, authLoading = false, strict = false } = {}) {
  useAuth.mockReturnValue(authLoading ? { user: null, loading: true }
    : { user: { authenticated: true, roles: admin ? ['Admin'] : [] }, loading: false });
  const inner = () => (
    <MemoryRouter initialEntries={['/work/agents']}>
      <Routes>
        <Route path="/work/agents" element={<WorkAgents />} />
        <Route path="/work" element={<div>HOME</div>} />
      </Routes>
    </MemoryRouter>
  );
  const ui = () => (strict ? <StrictMode>{inner()}</StrictMode> : inner());
  const r = render(ui());
  return { ...r, again: () => r.rerender(ui()) };
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear(); sessionStorage.clear();
  listAgents.mockResolvedValue(AGENTS);
  listQueues.mockResolvedValue(QUEUES);
  listProjects.mockResolvedValue([{ key: 'PLAT', role: 'lead', archived: false }]);
  api.listMentionableUsers.mockResolvedValue({ users: [{ id: 'u1', displayName: 'Alice A' }, { id: 'u2', displayName: 'Bob B' }] });
  putMember.mockResolvedValue({});
  createQueue.mockResolvedValue({});
  registerAgent.mockResolvedValue({ agent: AGENTS[1], token: SECRET });
  rotateAgentKey.mockResolvedValue({ agent: AGENTS[1], token: SECRET });
  retireAgent.mockResolvedValue(undefined);
  retireQueue.mockResolvedValue(undefined);
  updateAgent.mockResolvedValue({});
  updateQueue.mockResolvedValue({});
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: vi.fn().mockResolvedValue(undefined) } });
});

async function openRegister() {
  await screen.findByText('triage-bot');
  fireEvent.click(screen.getByRole('button', { name: 'Register agent' }));
  fireEvent.change(screen.getByLabelText('Agent name'), { target: { value: 'new-bot' } });
}

describe('WorkAgents', () => {
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
    expect(document.body.textContent).not.toContain('7731');
    expect(document.body.textContent).not.toContain('8842');
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

  // ---- review round: key cannot be lost ----
  async function showPanelViaRegister(opts) {
    renderPage(opts);
    await openRegister();
    fireEvent.click(screen.getByRole('button', { name: 'Register' }));
    return screen.findByRole('dialog', { name: /key/i });
  }

  it('StrictMode: a rotated key is shown and the confirm strip is cleared', async () => {
    renderPage({ strict: true });
    const row = (await screen.findByText('triage-bot')).closest('li');
    fireEvent.click(within(row).getByRole('button', { name: 'Rotate key of triage-bot' }));
    fireEvent.click(screen.getByRole('button', { name: /Yes, rotate/ }));
    expect(await screen.findByText(SECRET)).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument());
  });

  it('StrictMode: a registered key is shown', async () => {
    const panel = await showPanelViaRegister({ strict: true });
    expect(within(panel).getByText(SECRET)).toBeInTheDocument();
  });

  describe('copy feedback is truthful', () => {
    const setClipboard = (value) => Object.defineProperty(navigator, 'clipboard', { configurable: true, value });
    const FAIL = /Copying the token failed/;
    async function copyClick() {
      const panel = await showPanelViaRegister();
      fireEvent.click(within(panel).getByRole('button', { name: 'Copy' }));
      return panel;
    }
    it('no clipboard API: failure text, never Copied', async () => {
      setClipboard(undefined);
      const panel = await copyClick();
      expect(await within(panel).findByText(FAIL)).toBeInTheDocument();
      expect(panel).not.toHaveTextContent(/copied\./i);
      expect(within(panel).getByText(SECRET)).toBeInTheDocument();
    });
    it('writeText rejects: failure text', async () => {
      setClipboard({ writeText: vi.fn().mockRejectedValue(new Error('denied')) });
      const panel = await copyClick();
      expect(await within(panel).findByText(FAIL)).toBeInTheDocument();
      expect(panel).not.toHaveTextContent(/copied\./i);
    });
    it('writeText throws synchronously: failure text', async () => {
      setClipboard({ writeText: vi.fn(() => { throw new Error('sync'); }) });
      const panel = await copyClick();
      expect(await within(panel).findByText(FAIL)).toBeInTheDocument();
      expect(panel).not.toHaveTextContent(/copied\./i);
    });
    it('writeText resolves: Token copied.', async () => {
      setClipboard({ writeText: vi.fn().mockResolvedValue(undefined) });
      const panel = await copyClick();
      expect(await within(panel).findByText(/Token copied\./)).toBeInTheDocument();
    });
  });

  it('a failed reload after register keeps the open token panel and the lists, with an error line', async () => {
    renderPage();
    await openRegister();
    listAgents.mockRejectedValue(new Error('network down'));
    fireEvent.click(screen.getByRole('button', { name: 'Register' }));
    expect(await screen.findByText(SECRET)).toBeInTheDocument();
    expect(await screen.findByText(/Could not refresh/)).toBeInTheDocument();
    expect(screen.getByText(SECRET)).toBeInTheDocument();
    expect(screen.getByText('triage-bot')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });

  it('while a key is shown, rotate, retire, register and edit are disabled with a reason', async () => {
    await showPanelViaRegister();
    const reason = /Copy or dismiss the key shown above first/;
    const row = (await screen.findAllByTestId('agent-row'))[0];
    for (const b of [within(row).getByRole('button', { name: 'Rotate key of triage-bot' }),
      within(row).getByRole('button', { name: 'Retire triage-bot' }),
      within(row).getByRole('button', { name: 'Edit triage-bot' }),
      screen.getByRole('button', { name: 'Register agent' })]) {
      expect(b).toBeDisabled();
      expect(b).toHaveAccessibleDescription(reason);
    }
    fireEvent.click(screen.getByRole('button', { name: 'Done' }));
    expect(screen.getByRole('button', { name: 'Register agent' })).toBeEnabled();
  });

  // ---- edit UI ----
  it('edit agent pre-fills, keeps retired served queues checked and labelled, sends all four fields', async () => {
    listAgents.mockResolvedValue([{ ...AGENTS[1], queues: ['triage', 'old-q'] }]);
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Edit triage-bot' }));
    expect(screen.getByLabelText('Agent name')).toHaveValue('triage-bot');
    expect(screen.getByLabelText('Vendor')).toHaveValue('acme');
    expect(screen.getByLabelText('Triage')).toBeChecked();
    const retired = screen.getByLabelText(/Old queue \(retired\)/);
    expect(retired).toBeChecked();
    expect(screen.queryByLabelText('Key owner')).not.toBeInTheDocument();
    expect(screen.getByText(/keeps any work it has already claimed/i)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Vendor'), { target: { value: 'globex' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save agent' }));
    await waitFor(() => expect(updateAgent).toHaveBeenCalledWith(
      1, { name: 'triage-bot', vendor: 'globex', runtime: 'claude', queues: ['triage', 'old-q'] }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(registerAgent).not.toHaveBeenCalled();
  });

  it('a retired queue the agent does not serve is not offered when editing', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Edit triage-bot' }));
    expect(screen.queryByLabelText(/Old queue/)).not.toBeInTheDocument();
  });

  it('a 409 exists on rename shows on the name field and keeps the typed values', async () => {
    updateAgent.mockRejectedValue(Object.assign(new Error('Agent build-bot already exists.'), { status: 409, body: { reason: 'exists' } }));
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Edit triage-bot' }));
    fireEvent.change(screen.getByLabelText('Agent name'), { target: { value: 'build-bot' } });
    fireEvent.change(screen.getByLabelText('Vendor'), { target: { value: 'typed' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save agent' }));
    expect(await screen.findByText('Agent build-bot already exists.')).toBeInTheDocument();
    expect(screen.getByLabelText('Agent name')).toHaveAccessibleDescription('Agent build-bot already exists.');
    expect(screen.getByLabelText('Agent name')).toHaveValue('build-bot');
    expect(screen.getByLabelText('Vendor')).toHaveValue('typed');
  });

  it('edit form guards against a double submit', async () => {
    let done;
    updateAgent.mockReturnValue(new Promise((r) => { done = r; }));
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Edit triage-bot' }));
    const save = screen.getByRole('button', { name: 'Save agent' });
    fireEvent.click(save);
    fireEvent.click(save);
    expect(updateAgent).toHaveBeenCalledTimes(1);
    done({});
  });

  it('edit queue sends name and description; the key is read-only', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Edit queue triage' }));
    expect(screen.getByLabelText('Queue key')).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Queue name'), { target: { value: 'Triage 2' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save queue' }));
    await waitFor(() => expect(updateQueue).toHaveBeenCalledWith('triage', { name: 'Triage 2', description: 'Incoming' }));
  });

  it('retired rows have no Edit', async () => {
    renderPage();
    await screen.findByText('triage-bot');
    expect(screen.queryByRole('button', { name: 'Edit old-bot' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit queue old-q' })).not.toBeInTheDocument();
  });

  it('server field errors attach to the matching control', async () => {
    registerAgent.mockRejectedValue(Object.assign(new Error('Unknown queue.'), { status: 400, body: { field: 'queues' } }));
    renderPage();
    await openRegister();
    fireEvent.click(screen.getByRole('button', { name: 'Register' }));
    expect(await screen.findByText('Unknown queue.')).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Queues' })).toHaveAccessibleDescription('Unknown queue.');
    expect(screen.getByLabelText('Agent name')).not.toHaveAttribute('aria-invalid');
  });

  // ---- every minted key keeps its own panel ----
  const REG = 'REG_TOKEN_AAA';
  const ROT = 'ROT_TOKEN_BBB';
  function deferred() { let resolve; const promise = new Promise((r) => { resolve = r; }); return { promise, resolve }; }

  let unmountPage;
  async function registerInFlight() {
    const reg = deferred();
    registerAgent.mockReturnValue(reg.promise);
    rotateAgentKey.mockResolvedValue({ agent: AGENTS[1], token: ROT });
    unmountPage = renderPage().unmount;
    await openRegister();
    fireEvent.click(screen.getByRole('button', { name: 'Register' }));
    return () => reg.resolve({ agent: { id: 9, name: 'new-bot' }, token: REG });
  }
  async function rotateTriage() {
    const row = (await screen.findAllByTestId('agent-row'))[0];
    fireEvent.click(within(row).getByRole('button', { name: 'Rotate key of triage-bot' }));
    fireEvent.click(screen.getByRole('button', { name: /Yes, rotate/ }));
  }

  it('aRotationCompletingWhileARegisterIsInFlightKeepsBothKeys', async () => {
    const finishRegister = await registerInFlight();
    await rotateTriage();
    await screen.findByText(ROT);
    await act(async () => { finishRegister(); });
    const rotPanel = await screen.findByRole('dialog', { name: 'API key for triage-bot' });
    const regPanel = await screen.findByRole('dialog', { name: 'API key for new-bot' });
    expect(within(rotPanel).getByText(ROT)).toBeInTheDocument();
    expect(within(regPanel).getByText(REG)).toBeInTheDocument();
  });

  it('doneRemovesOnlyThatKey', async () => {
    const finishRegister = await registerInFlight();
    await rotateTriage();
    await screen.findByText(ROT);
    await act(async () => { finishRegister(); });
    const regPanel = await screen.findByRole('dialog', { name: 'API key for new-bot' });
    fireEvent.click(within(regPanel).getByRole('button', { name: 'Done' }));
    expect(screen.queryByText(REG)).not.toBeInTheDocument();
    expect(screen.getByText(ROT)).toBeInTheDocument();
  });

  it('anAlreadyOpenRegisterFormIsLockedWhileAKeyIsShown', async () => {
    renderPage();
    await openRegister();
    const row = (await screen.findAllByTestId('agent-row'))[0];
    fireEvent.click(within(row).getByRole('button', { name: 'Rotate key of triage-bot' }));
    fireEvent.click(screen.getByRole('button', { name: /Yes, rotate/ }));
    await screen.findByText(SECRET);
    const submit = screen.getByRole('button', { name: 'Register' });
    expect(submit).toBeDisabled();
    expect(submit).toHaveAccessibleDescription(/Copy or dismiss the key shown above first/);
    fireEvent.click(submit);
    expect(registerAgent).not.toHaveBeenCalled();
  });

  it('anAlreadyOpenRotateConfirmIsLockedWhileAKeyIsShown', async () => {
    const finishRegister = await registerInFlight();
    const row = (await screen.findAllByTestId('agent-row'))[0];
    fireEvent.click(within(row).getByRole('button', { name: 'Rotate key of triage-bot' }));
    await act(async () => { finishRegister(); });
    await screen.findByText(REG);
    const yes = screen.getByRole('button', { name: /Yes, rotate/ });
    expect(yes).toBeDisabled();
    fireEvent.click(yes);
    expect(rotateAgentKey).not.toHaveBeenCalled();
    expect(screen.getByText(REG)).toBeInTheDocument();
  });

  it('unmountDropsEveryKey', async () => {
    const finishRegister = await registerInFlight();
    await rotateTriage();
    await screen.findByText(ROT);
    await act(async () => { finishRegister(); });
    await screen.findByText(REG);
    unmountPage();
    expect(document.body.textContent).not.toContain(REG);
    expect(document.body.textContent).not.toContain(ROT);
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it('a vendor-only edit sends the unchanged name and succeeds', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Edit triage-bot' }));
    fireEvent.change(screen.getByLabelText('Vendor'), { target: { value: 'globex' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save agent' }));
    await waitFor(() => expect(updateAgent).toHaveBeenCalledWith(1, expect.objectContaining({ name: 'triage-bot', vendor: 'globex' })));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Save agent' })).not.toBeInTheDocument());
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  // ---- hardening wave B: rotation owner, refusals in the dialog, focus and announcements ----
  async function openRotate() {
    const row = (await screen.findAllByTestId('agent-row'))[0];
    fireEvent.click(within(row).getByRole('button', { name: 'Rotate key of triage-bot' }));
    return screen.findByRole('alertdialog', { name: 'Confirm' });
  }

  it('a server refusal of the rotation is shown verbatim in the dialog, which stays open', async () => {
    const msg = 'Name the new owner: the key owner no longer exists.';
    rotateAgentKey.mockRejectedValue(Object.assign(new Error(msg), { status: 400 }));
    renderPage();
    const dialog = await openRotate();
    fireEvent.click(within(dialog).getByRole('button', { name: /Yes, rotate/ }));
    expect(await within(await screen.findByRole('alertdialog', { name: 'Confirm' })).findByText(msg)).toBeInTheDocument();
    expect(screen.queryByText(SECRET)).not.toBeInTheDocument();
  });

  it('the locked Yes is described by the lock explanation', async () => {
    const finishRegister = await registerInFlight();
    await openRotate();
    await act(async () => { finishRegister(); });
    await screen.findByText(REG);
    const yes = screen.getByRole('button', { name: /Yes, rotate/ });
    expect(yes).toBeDisabled();
    expect(yes).toHaveAccessibleDescription(/Copy or dismiss the key shown above first/);
  });

  it('a newly shown key panel does not steal focus from the panel being used, and is announced politely', async () => {
    const finishRegister = await registerInFlight();
    await rotateTriage();
    const rotPanel = await screen.findByRole('dialog', { name: 'API key for triage-bot' });
    await waitFor(() => expect(rotPanel).toHaveFocus());
    await act(async () => { finishRegister(); });
    const regPanel = await screen.findByRole('dialog', { name: 'API key for new-bot' });
    expect(regPanel).not.toHaveFocus();
    expect(rotPanel).toHaveFocus();
    const live = screen.getByTestId('work-agents-announce');
    expect(live).toHaveAttribute('role', 'status');
    expect(live).toHaveAttribute('aria-live', 'polite');
    expect(live).toHaveTextContent('A key for new-bot is shown below.');
  });

  // ---- sponsors (phase 3a): leads use this page too; the server decides what each person sees and may do

  const MINE = { id: 11, name: 'mine-bot', vendor: '', runtime: '', queues: ['triage'], createdAt: '2026-03-01T00:00:00Z', retiredAt: null, sponsorName: 'Lena Lead', status: 'active', sponsoredByMe: true };
  const THEIRS = { id: 12, name: 'their-bot', vendor: '', runtime: '', queues: [], createdAt: '2026-03-01T00:00:00Z', retiredAt: null, sponsorName: 'Pat P', status: 'active', sponsoredByMe: false };
  const LOOSE = { id: 13, name: 'loose-bot', vendor: '', runtime: '', queues: [], createdAt: '2026-03-01T00:00:00Z', retiredAt: null, status: 'no_sponsor', sponsoredByMe: false, mayTakeOver: true };
  const ORPHAN = { id: 14, name: 'orphan-bot', vendor: '', runtime: '', queues: [], createdAt: '2026-03-01T00:00:00Z', retiredAt: null, sponsorName: 'Gone G', status: 'sponsor_gone', sponsoredByMe: false, mayTakeOver: true };
  const row = (name) => screen.getByText(name).closest('[data-testid="agent-row"]');

  it('a lead who is not an admin opens the page and sees the agents the server returns', async () => {
    listAgents.mockResolvedValue([MINE, THEIRS]);
    renderPage({ admin: false });
    expect(await screen.findByText('mine-bot')).toBeInTheDocument();
    expect(screen.queryByText('HOME')).toBeNull();
    expect(screen.queryByRole('button', { name: 'New queue' })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Edit queue/ })).toBeNull();
    expect(screen.getByRole('button', { name: 'Register agent' })).toBeInTheDocument();
  });

  it('each agent names its sponsor, and says plainly when nobody stands behind it', async () => {
    listAgents.mockResolvedValue([MINE, LOOSE, ORPHAN]);
    renderPage({ admin: false });
    expect(within(await waitFor(() => row('mine-bot'))).getByText('Sponsor: Lena Lead')).toBeInTheDocument();
    expect(within(row('loose-bot')).getByText('Suspended: no sponsor')).toBeInTheDocument();
    expect(within(row('orphan-bot')).getByText('Suspended: its sponsor no longer has an account')).toBeInTheDocument();
  });

  it('a non-admin manages only the agents they sponsor, and may take over one with no sponsor', async () => {
    listAgents.mockResolvedValue([MINE, THEIRS, LOOSE, ORPHAN]);
    renderPage({ admin: false });
    await screen.findByText('mine-bot');
    for (const label of ['Edit mine-bot', 'Rotate key of mine-bot', 'Retire mine-bot', 'Release sponsorship of mine-bot']) {
      expect(screen.getByRole('button', { name: label })).toBeInTheDocument();
    }
    expect(within(row('their-bot')).queryAllByRole('button')).toHaveLength(0);
    expect(within(row('loose-bot')).getByRole('button', { name: 'Take over loose-bot' })).toBeInTheDocument();
    expect(within(row('orphan-bot')).getByRole('button', { name: 'Take over orphan-bot' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Take over mine-bot' })).toBeNull();
  });

  it('an admin may manage and take over any agent', async () => {
    listAgents.mockResolvedValue([{ ...THEIRS, mayTakeOver: true }]);
    renderPage({ admin: true });
    await screen.findByText('their-bot');
    expect(screen.getByRole('button', { name: 'Rotate key of their-bot' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Take over their-bot' })).toBeInTheDocument();
  });

  it('releasing sponsorship says what it means, then releases and reloads', async () => {
    listAgents.mockResolvedValue([MINE]);
    releaseAgentSponsorship.mockResolvedValue(undefined);
    renderPage({ admin: false });
    fireEvent.click(await screen.findByRole('button', { name: 'Release sponsorship of mine-bot' }));
    expect(screen.getByText('Release mine-bot? This agent stops working until someone takes it over.')).toBeInTheDocument();
    expect(releaseAgentSponsorship).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Yes, release mine-bot' }));
    await waitFor(() => expect(releaseAgentSponsorship).toHaveBeenCalledWith(11));
    await waitFor(() => expect(listAgents).toHaveBeenCalledTimes(2));
  });

  it('taking over says what it means, and a refusal is shown in the server\'s words', async () => {
    listAgents.mockResolvedValue([LOOSE]);
    takeOverAgent.mockRejectedValueOnce(Object.assign(new Error('Taking over an agent takes a lead of a project it is in, or a wiki administrator.'), { status: 403 }));
    takeOverAgent.mockResolvedValue({ ...LOOSE, status: 'active', sponsoredByMe: true });
    renderPage({ admin: false });
    fireEvent.click(await screen.findByRole('button', { name: 'Take over loose-bot' }));
    expect(screen.getByText('Take over loose-bot? You become responsible for what this agent does. It can never do more than you can.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Yes, take over loose-bot' }));
    expect(await screen.findByText('Taking over an agent takes a lead of a project it is in, or a wiki administrator.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Yes, take over loose-bot' }));
    await waitFor(() => expect(takeOverAgent).toHaveBeenCalledTimes(2));
    expect(takeOverAgent).toHaveBeenLastCalledWith(13);
    await waitFor(() => expect(listAgents).toHaveBeenCalledTimes(2));
  });

  it('registering asks for no owner, and a refusal is shown in the server\'s words', async () => {
    listAgents.mockResolvedValue([]);
    registerAgent.mockRejectedValueOnce(Object.assign(new Error('Only a project lead or a wiki administrator may register an agent.'), { status: 403 }));
    renderPage({ admin: false });
    fireEvent.click(await screen.findByRole('button', { name: 'Register agent' }));
    expect(screen.queryByLabelText(/owner/i)).toBeNull();
    fireEvent.change(screen.getByLabelText('Agent name'), { target: { value: 'new-bot' } });
    fireEvent.click(screen.getByRole('button', { name: 'Register' }));
    await waitFor(() => expect(registerAgent).toHaveBeenCalledWith({ name: 'new-bot', vendor: '', runtime: '', queues: [] }));
    expect(await screen.findByText('Only a project lead or a wiki administrator may register an agent.')).toBeInTheDocument();
  });

  it('rotating sends only the agent', async () => {
    listAgents.mockResolvedValue([MINE]);
    renderPage({ admin: false });
    fireEvent.click(await screen.findByRole('button', { name: 'Rotate key of mine-bot' }));
    expect(screen.queryByLabelText('New owner')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Yes, rotate mine-bot' }));
    await waitFor(() => expect(rotateAgentKey).toHaveBeenCalledWith(11));
  });

  describe('admitting the new agent', () => {
    const BOT = { name: 'bot' };
    it('shows the token before admitting, then admits to each checked project', async () => {
      registerAgent.mockResolvedValue({ agent: BOT, token: SECRET });
      let release;
      putMember.mockReturnValue(new Promise((r) => { release = r; }));
      renderPage();
      await openRegister();
      expect(screen.getByLabelText(/PLAT/)).toBeChecked();
      fireEvent.click(screen.getByRole('button', { name: 'Register' }));
      const panel = await screen.findByRole('dialog', { name: /key/i });
      expect(within(panel).getByText(SECRET)).toBeInTheDocument();
      expect(within(panel).getByText(`claude mcp add --transport http wikantik-work ${window.location.origin}/work-mcp --header "Authorization: Bearer ${SECRET}"`)).toBeInTheDocument();
      await waitFor(() => expect(putMember).toHaveBeenCalledWith('PLAT', { principal: 'bot', principalKind: 'agent', role: 'member' }));
      await act(async () => { release({}); });
      expect(screen.queryByRole('alert')).toBeNull();
    });
    it('keeps the token and names the project when admission fails', async () => {
      listProjects.mockResolvedValue([{ key: 'PLAT', role: 'lead', archived: false }, { key: 'OPS', role: 'lead', archived: false }]);
      registerAgent.mockResolvedValue({ agent: BOT, token: SECRET });
      putMember.mockImplementation((key) => (key === 'OPS' ? Promise.reject(new Error('no')) : Promise.resolve({})));
      renderPage();
      await openRegister();
      fireEvent.click(screen.getByLabelText(/PLAT/));
      fireEvent.click(screen.getByLabelText(/OPS/));
      fireEvent.click(screen.getByRole('button', { name: 'Register' }));
      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('OPS');
      expect(alert).toHaveTextContent('Settings');
      expect(alert).not.toHaveTextContent('PLAT');
      expect(screen.getByRole('dialog', { name: /key/i })).toHaveTextContent(SECRET);
    });
    it('admits nobody when the server names no agent, but still shows the key', async () => {
      registerAgent.mockResolvedValue({ agent: {}, token: SECRET });
      renderPage();
      await openRegister();
      expect(screen.getByLabelText(/PLAT/)).toBeChecked();
      fireEvent.click(screen.getByRole('button', { name: 'Register' }));
      const panel = await screen.findByRole('dialog', { name: 'API key for agent' });
      expect(within(panel).getByText(SECRET)).toBeInTheDocument();
      await waitFor(() => expect(listAgents.mock.calls.length).toBeGreaterThan(1));
      expect(putMember).not.toHaveBeenCalled();
    });
    it('clears the admission warning when the key panel is dismissed', async () => {
      registerAgent.mockResolvedValue({ agent: BOT, token: SECRET });
      putMember.mockRejectedValue(new Error('no'));
      renderPage();
      await openRegister();
      fireEvent.click(screen.getByRole('button', { name: 'Register' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('PLAT');
      fireEvent.click(within(screen.getByRole('dialog', { name: /key/i })).getByRole('button', { name: 'Done' }));
      await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
    });
    it('makes no putMember call when nothing is checked', async () => {
      registerAgent.mockResolvedValue({ agent: BOT, token: SECRET });
      renderPage();
      await openRegister();
      fireEvent.click(screen.getByLabelText(/PLAT/));
      fireEvent.click(screen.getByRole('button', { name: 'Register' }));
      await screen.findByRole('dialog', { name: /key/i });
      expect(putMember).not.toHaveBeenCalled();
    });
  });
});
