import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';

vi.mock('../../api/work', () => ({
  listAgents: vi.fn(), listQueues: vi.fn(), createQueue: vi.fn(), updateQueue: vi.fn(), retireQueue: vi.fn(),
  registerAgent: vi.fn(), updateAgent: vi.fn(), rotateAgentKey: vi.fn(), retireAgent: vi.fn(),
  takeOverAgent: vi.fn(), releaseAgentSponsorship: vi.fn(), listProjects: vi.fn(),
}));
vi.mock('../../hooks/useAuth', () => ({ useAuth: vi.fn() }));
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
vi.mock('../../hooks/useToast', () => ({ useToast: () => toast }));
import {
  listAgents, listQueues, createQueue, registerAgent, rotateAgentKey, retireAgent, retireQueue,
  takeOverAgent, releaseAgentSponsorship, listProjects,
} from '../../api/work';
import { useAuth } from '../../hooks/useAuth';
import WorkAgents from './WorkAgents';

const MINE = {
  id: 11, name: 'mine-bot', vendor: 'acme', runtime: 'claude', queues: ['triage', 'ghost-q'], createdAt: '2026-03-01T00:00:00Z',
  retiredAt: null, sponsorName: 'Lena Lead', status: 'active', sponsoredByMe: true,
};
const LOOSE = { id: 13, name: 'loose-bot', queues: [], createdAt: '2026-03-01T00:00:00Z', retiredAt: null, status: 'no_sponsor', sponsoredByMe: false, mayTakeOver: true };
const QUEUES = [{ key: 'triage', name: 'Triage', description: '', retiredAt: null }];
const refusal = (message, status) => Object.assign(new Error(message), { status });

function renderPage({ admin = true, signedIn = true } = {}) {
  useAuth.mockReturnValue({ user: { authenticated: signedIn, roles: admin ? ['Admin'] : [] }, loading: false });
  return render(
    <MemoryRouter initialEntries={['/work/agents']}>
      <Routes>
        <Route path="/work/agents" element={<WorkAgents />} />
      </Routes>
    </MemoryRouter>,
  );
}
const deferred = () => { let resolve; let reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };

beforeEach(() => {
  vi.clearAllMocks();
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  listAgents.mockResolvedValue([MINE]);
  listQueues.mockResolvedValue(QUEUES);
  listProjects.mockResolvedValue([{ key: 'PLAT', role: 'lead', archived: false }]);
  createQueue.mockResolvedValue({});
  retireQueue.mockResolvedValue(undefined);
  registerAgent.mockResolvedValue({ agent: { name: 'new-bot' }, token: 'wk_TOKEN' });
  rotateAgentKey.mockResolvedValue({ agent: MINE, token: 'wk_ROTATED' });
  retireAgent.mockResolvedValue(undefined);
  takeOverAgent.mockResolvedValue({});
  releaseAgentSponsorship.mockResolvedValue(undefined);
});

describe('WorkAgents edges', () => {
  it('loads nothing for a visitor who is not signed in', () => {
    renderPage({ signedIn: false });
    expect(screen.getByText('Loading agents…')).toBeInTheDocument();
    expect(listAgents).not.toHaveBeenCalled();
  });

  it('a failed first load blocks the page with the server words and Retry loads it', async () => {
    listAgents.mockRejectedValueOnce(new Error('registry down'));
    renderPage();
    expect(await screen.findByText('registry down')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Register agent' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('mine-bot')).toBeInTheDocument();
    expect(screen.queryByText(/Could not refresh/)).toBeNull();
  });

  it('treats missing lists as empty', async () => {
    listAgents.mockResolvedValue(null);
    listQueues.mockResolvedValue(null);
    renderPage();
    expect(await screen.findByText('No agents registered yet.')).toBeInTheDocument();
    expect(screen.getByText('No queues yet.')).toBeInTheDocument();
  });

  it('names a queue by its key when the queue list does not know it, and says none when an agent serves nothing', async () => {
    listAgents.mockResolvedValue([MINE, { ...LOOSE, queues: undefined }]);
    renderPage();
    const mine = (await screen.findByText('mine-bot')).closest('li');
    expect(within(mine).getByText('Triage')).toBeInTheDocument();
    expect(within(mine).getByText('ghost-q')).toBeInTheDocument();
    expect(within(screen.getByText('loose-bot').closest('li')).getByText(/Queues:\s*none/)).toBeInTheDocument();
  });

  it('shows no vendor line text and no dangling Created label for an agent missing both', async () => {
    listAgents.mockResolvedValue([{ ...LOOSE, createdAt: null }]);
    renderPage();
    const row = (await screen.findByText('loose-bot')).closest('li');
    expect(within(row).getByText('No vendor or runtime set')).toBeInTheDocument();
    expect(within(row).getByText('No sponsor')).toBeInTheDocument();
    expect(within(row).queryByText(/Created/)).toBeNull();
  });

  it('keeps retired agents together after the active ones', async () => {
    const retired = { ...MINE, queues: [], retiredAt: '2026-04-01T00:00:00Z', status: 'retired' };
    listAgents.mockResolvedValue([{ ...retired, id: 1, name: 'r1' }, { ...retired, id: 2, name: 'r2' }, MINE]);
    renderPage();
    await screen.findByText('mine-bot');
    expect(screen.getAllByTestId('agent-row').map((r) => within(r).getByRole('strong').textContent)).toEqual(['mine-bot', 'r1', 'r2']);
    expect(screen.getAllByText(/retired 2026-04-01/)).toHaveLength(2);
  });

  it('cancelling the register form brings Register agent back without calling the server', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Register agent' }));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.getByRole('button', { name: 'Register agent' })).toBeInTheDocument();
    expect(registerAgent).not.toHaveBeenCalled();
  });

  it('calls a freshly registered agent just "agent" in the key panel when the server did not return it', async () => {
    registerAgent.mockResolvedValue({ token: 'wk_TOKEN' });
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Register agent' }));
    fireEvent.change(screen.getByLabelText('Agent name'), { target: { value: 'new-bot' } });
    fireEvent.click(screen.getByRole('button', { name: 'Register' }));
    expect(await screen.findByText('wk_TOKEN')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Done' })).toBeInTheDocument();
  });

  it('a registration that completes after the page is left does not reload the lists', async () => {
    const d = deferred();
    registerAgent.mockReturnValue(d.promise);
    const { unmount } = renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Register agent' }));
    fireEvent.change(screen.getByLabelText('Agent name'), { target: { value: 'new-bot' } });
    fireEvent.click(screen.getByRole('button', { name: 'Register' }));
    unmount();
    d.resolve({ agent: { name: 'new-bot' }, token: 'wk_TOKEN' });
    await d.promise;
    expect(listAgents).toHaveBeenCalledTimes(1);
  });

  it('cancelling a confirmation does nothing and restores the action buttons', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Retire mine-bot' }));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(retireAgent).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Retire mine-bot' })).toBeInTheDocument();
  });

  it('cancelling the edit form shows the agent row again', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Edit mine-bot' }));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.getByRole('button', { name: 'Edit mine-bot' })).toBeInTheDocument();
  });

  it('retiring an agent tells the admin, reloads and closes the confirmation', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Retire mine-bot' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, retire mine-bot' }));
    await waitFor(() => expect(retireAgent).toHaveBeenCalledWith(11));
    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Retired mine-bot'));
    await waitFor(() => expect(listAgents).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });

  it('a refused retirement keeps the confirmation open with the server reason', async () => {
    retireAgent.mockRejectedValue(refusal('Not your agent.', 403));
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Retire mine-bot' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, retire mine-bot' }));
    expect(await within(screen.getByRole('alertdialog')).findByRole('alert')).toHaveTextContent('Not your agent.');
    expect(toast.error).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Yes, retire mine-bot' })).toBeEnabled();
    expect(listAgents).toHaveBeenCalledTimes(1);
  });

  it('a retirement refused with no message uses the plain default', async () => {
    retireAgent.mockRejectedValue(new Error(''));
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Retire mine-bot' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, retire mine-bot' }));
    expect(await within(screen.getByRole('alertdialog')).findByRole('alert')).toHaveTextContent('Could not retire the agent.');
  });

  it.each([
    ['rotate', rotateAgentKey, 'Rotate key of mine-bot', 'Yes, rotate mine-bot', 'Could not rotate the key.'],
    ['release', releaseAgentSponsorship, 'Release sponsorship of mine-bot', 'Yes, release mine-bot', 'Could not release the agent.'],
  ])('a %s refused without a message keeps the confirmation open with a plain default', async (_a, fn, open, yes, text) => {
    fn.mockRejectedValue(new Error(''));
    renderPage({ admin: false });
    fireEvent.click(await screen.findByRole('button', { name: open }));
    fireEvent.click(screen.getByRole('button', { name: yes }));
    expect(await within(screen.getByRole('alertdialog')).findByText(text)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: yes })).toBeEnabled();
  });

  it('a take-over refused without a message says so plainly', async () => {
    listAgents.mockResolvedValue([LOOSE]);
    takeOverAgent.mockRejectedValue(new Error(''));
    renderPage({ admin: false });
    fireEvent.click(await screen.findByRole('button', { name: 'Take over loose-bot' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, take over loose-bot' }));
    expect(await screen.findByText('Could not take over the agent.')).toBeInTheDocument();
  });

  it('a successful take-over says the person now sponsors the agent', async () => {
    listAgents.mockResolvedValue([LOOSE]);
    renderPage({ admin: false });
    fireEvent.click(await screen.findByRole('button', { name: 'Take over loose-bot' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, take over loose-bot' }));
    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('You now sponsor loose-bot'));
    expect(takeOverAgent).toHaveBeenCalledWith(13);
  });

  it('a rotation that finishes after the page is left shows no key and raises no toast', async () => {
    const d = deferred();
    rotateAgentKey.mockReturnValue(d.promise);
    const { unmount } = renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Rotate key of mine-bot' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, rotate mine-bot' }));
    unmount();
    d.resolve({ agent: MINE, token: 'wk_ROTATED' });
    await d.promise;
    expect(document.body.textContent).not.toContain('wk_ROTATED');
  });

  it('a refusal arriving after the page is left is dropped quietly', async () => {
    const d = deferred();
    rotateAgentKey.mockReturnValue(d.promise);
    const { unmount } = renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Rotate key of mine-bot' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, rotate mine-bot' }));
    unmount();
    d.reject(refusal('Too late.', 403));
    await d.promise.catch(() => {});
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('an older read that fails after a newer one succeeded does not raise a refresh warning', async () => {
    const slow = deferred();
    renderPage();
    await screen.findByText('mine-bot');
    // load 2 (slow, will fail) is started by creating a queue; load 3 (fast) by retiring one.
    listAgents.mockReturnValueOnce(slow.promise);
    fireEvent.click(screen.getByRole('button', { name: 'New queue' }));
    fireEvent.change(screen.getByLabelText('Queue key'), { target: { value: 'build' } });
    fireEvent.change(screen.getByLabelText('Queue name'), { target: { value: 'Build' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create queue' }));
    await waitFor(() => expect(listAgents).toHaveBeenCalledTimes(2));
    fireEvent.click(screen.getByRole('button', { name: 'Retire queue triage' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, retire triage' }));
    await waitFor(() => expect(listAgents).toHaveBeenCalledTimes(3));
    slow.reject(new Error('late failure'));
    await slow.promise.catch(() => {});
    expect(screen.getByText('mine-bot')).toBeInTheDocument();
    expect(screen.queryByText(/Could not refresh/)).toBeNull();
  });

  it('an older read that succeeds late does not replace what a newer read showed', async () => {
    const slow = deferred();
    renderPage();
    await screen.findByText('mine-bot');
    listAgents.mockReturnValueOnce(slow.promise);
    fireEvent.click(screen.getByRole('button', { name: 'New queue' }));
    fireEvent.change(screen.getByLabelText('Queue key'), { target: { value: 'build' } });
    fireEvent.change(screen.getByLabelText('Queue name'), { target: { value: 'Build' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create queue' }));
    await waitFor(() => expect(listAgents).toHaveBeenCalledTimes(2));
    listAgents.mockResolvedValueOnce([{ ...MINE, name: 'newest-bot' }]);
    fireEvent.click(screen.getByRole('button', { name: 'Retire queue triage' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, retire triage' }));
    expect(await screen.findByText('newest-bot')).toBeInTheDocument();
    slow.resolve([{ ...MINE, name: 'stale-bot' }]);
    await slow.promise;
    expect(screen.getByText('newest-bot')).toBeInTheDocument();
    expect(screen.queryByText('stale-bot')).toBeNull();
  });

  describe('buttons follow what the viewer may do', () => {
    const THEIRS = { ...MINE, id: 12, name: 'their-bot', sponsoredByMe: false };
    const lead = [{ key: 'PLAT', role: 'lead', archived: false }];

    it('offers Register an agent to a lead and to an admin, not to someone who leads nothing', async () => {
      listProjects.mockResolvedValue([{ key: 'PLAT', role: 'member', archived: false }, { key: 'OLD', role: 'lead', archived: true }]);
      renderPage({ admin: false });
      await screen.findByText('mine-bot');
      expect(screen.queryByRole('button', { name: 'Register agent' })).toBeNull();
    });

    it('offers Register to an admin who leads no project', async () => {
      listProjects.mockResolvedValue([]);
      renderPage({ admin: true });
      expect(await screen.findByRole('button', { name: 'Register agent' })).toBeInTheDocument();
    });

    it('a sponsor who leads nothing may still rotate, retire and release but not edit or take over', async () => {
      listProjects.mockResolvedValue([{ key: 'PLAT', role: 'member', archived: false }]);
      listAgents.mockResolvedValue([MINE, { ...LOOSE, mayTakeOver: false }]);
      renderPage({ admin: false });
      await screen.findByText('mine-bot');
      expect(screen.queryByRole('button', { name: 'Edit mine-bot' })).toBeNull();
      for (const l of ['Rotate key of mine-bot', 'Retire mine-bot', 'Release sponsorship of mine-bot']) {
        expect(screen.getByRole('button', { name: l })).toBeInTheDocument();
      }
      expect(screen.queryByRole('button', { name: 'Take over loose-bot' })).toBeNull();
    });

    it('a lead of some other project is not offered a takeover the server would refuse', async () => {
      listProjects.mockResolvedValue(lead);
      listAgents.mockResolvedValue([MINE, { ...LOOSE, mayTakeOver: false }]);
      renderPage({ admin: false });
      await screen.findByText('loose-bot');
      expect(screen.queryByRole('button', { name: 'Take over loose-bot' })).toBeNull();
    });

    it('an admin is offered only the takeovers the server says they may make', async () => {
      listProjects.mockResolvedValue([]);
      listAgents.mockResolvedValue([{ ...LOOSE, mayTakeOver: true }, { ...THEIRS, mayTakeOver: false }]);
      renderPage({ admin: true });
      expect(await screen.findByRole('button', { name: 'Take over loose-bot' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Take over their-bot' })).toBeNull();
    });

    it('a lead may take over an agent with no sponsor but sees nothing on another sponsor\'s agent', async () => {
      listProjects.mockResolvedValue(lead);
      listAgents.mockResolvedValue([THEIRS, LOOSE]);
      renderPage({ admin: false });
      expect(await screen.findByRole('button', { name: 'Take over loose-bot' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Edit their-bot' })).toBeNull();
    });

    it('if the viewer\'s projects cannot be read the buttons stay and the server decides', async () => {
      listProjects.mockRejectedValue(new Error('down'));
      renderPage({ admin: false });
      expect(await screen.findByRole('button', { name: 'Register agent' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Edit mine-bot' })).toBeInTheDocument();
    });
  });
});
