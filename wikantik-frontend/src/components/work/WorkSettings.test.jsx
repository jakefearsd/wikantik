import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';

vi.mock('../../api/work', () => ({
  getMe: vi.fn(() => Promise.resolve({ projects: [], notify: { webhooksAvailable: false } })), listWebhooks: vi.fn(() => Promise.resolve([])),
  addWebhook: vi.fn(), updateWebhook: vi.fn(), removeWebhook: vi.fn(),
  listMembers: vi.fn(), listAgents: vi.fn(), putMember: vi.fn(), removeMember: vi.fn(), updateProject: vi.fn(), archiveProject: vi.fn(),
}));
vi.mock('../../hooks/useAuth', () => ({ useAuth: vi.fn() }));
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
vi.mock('../../hooks/useToast', () => ({ useToast: () => toast }));
import { getMe, listWebhooks, listMembers, listAgents, putMember, removeMember, updateProject, archiveProject } from '../../api/work';
import { useAuth } from '../../hooks/useAuth';
import { WorkProjectContext } from './useWorkProject';
import WorkSettings from './WorkSettings';

const PROJECT = { id: 1, key: 'PLAT', name: 'Platform', description: 'The platform', defaultAutonomy: 'supervised' };
const MEMBERS = [
  { principal: 'Me Myself', principalKind: 'user', role: 'lead', displayName: 'Me Myself', self: true },
  { principal: 'bob', principalKind: 'user', role: 'member', displayName: 'Bob B' },
  { principal: 'Devs', principalKind: 'group', role: 'viewer', displayName: 'Devs' },
];
const AGENTS = [
  { id: 1, name: 'triage-bot', retiredAt: null, queues: [] },
  { id: 2, name: 'old-bot', retiredAt: '2026-01-01T00:00:00Z', queues: [] },
  { id: 3, name: 'build-bot', retiredAt: null, queues: [] },
];
const AGENT_MEMBER = { principal: 'build-bot', principalKind: 'agent', role: 'member', displayName: 'build-bot' };
const RELEASE_TEXT = 'Any work this agent has claimed in this project goes back to its queue.';
const reload = vi.fn();

function renderSettings({ role = 'lead', admin = false, authLoading = false } = {}) {
  useAuth.mockReturnValue(authLoading ? { user: null, loading: true }
    : { user: { authenticated: true, roles: admin ? ['Admin'] : [] }, loading: false });
  render(
    <MemoryRouter initialEntries={['/work/PLAT/settings']}>
      <WorkProjectContext.Provider value={{ project: PROJECT, role, reload }}>
        <Routes>
          <Route path="/work/PLAT/settings" element={<WorkSettings />} />
          <Route path="/work/PLAT" element={<div>BOARD</div>} />
          <Route path="/work" element={<div>HOME</div>} />
        </Routes>
      </WorkProjectContext.Provider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  listMembers.mockResolvedValue(MEMBERS);
  listAgents.mockResolvedValue(AGENTS);
  putMember.mockResolvedValue(undefined);
  removeMember.mockResolvedValue(undefined);
  updateProject.mockResolvedValue({});
  archiveProject.mockResolvedValue(undefined);
  window.confirm = vi.fn();
});

describe('WorkSettings', () => {
  it('showsWebhooksToALeadWhenTheWikiHasThem', async () => {
    getMe.mockResolvedValue({ projects: [], notify: { webhooksAvailable: true } });
    renderSettings();
    expect(await screen.findByRole('heading', { name: 'Webhooks' })).toBeInTheDocument();
    await waitFor(() => expect(listWebhooks).toHaveBeenCalledWith('PLAT'));
  });

  it('showsNoWebhooksWhenTheWikiHasThemOff', async () => {
    getMe.mockResolvedValue({ projects: [], notify: { webhooksAvailable: false } });
    renderSettings();
    await screen.findByText('Members');
    await waitFor(() => expect(getMe).toHaveBeenCalled());
    expect(screen.queryByRole('heading', { name: 'Webhooks' })).toBeNull();
  });

  it.each(['member', 'viewer'])('hiddenFromNonLeads (%s is sent to the board)', async (role) => {
    renderSettings({ role });
    expect(await screen.findByText('BOARD')).toBeInTheDocument();
    expect(listMembers).not.toHaveBeenCalled();
  });

  it('lists members by display name with kind and role', async () => {
    renderSettings();
    const row = (await screen.findByText('Bob B')).closest('li');
    expect(within(row).getByText('user')).toBeInTheDocument();
    expect(screen.getByText('group')).toBeInTheDocument();
    expect(screen.queryByText('bob')).not.toBeInTheDocument();
    expect(screen.queryByText('Takes effect in a later release')).toBeNull();
  });

  it('saves the project name and description', async () => {
    renderSettings();
    fireEvent.change(await screen.findByLabelText('Project name'), { target: { value: 'Platform 2' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save details' }));
    await waitFor(() => expect(updateProject).toHaveBeenCalledWith('PLAT', { name: 'Platform 2', description: 'The platform' }));
    expect(reload).toHaveBeenCalled();
  });

  it('addMemberByUserOrGroup', async () => {
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.change(screen.getByLabelText('Display name or group name'), { target: { value: 'carol' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add member' }));
    await waitFor(() => expect(putMember).toHaveBeenCalledWith('PLAT', { principal: 'carol', principalKind: 'user', role: 'member' }));
    fireEvent.change(screen.getByLabelText('Display name or group name'), { target: { value: 'Ops' } });
    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'group' } });
    fireEvent.change(screen.getByLabelText('Role for new member'), { target: { value: 'viewer' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add member' }));
    await waitFor(() => expect(putMember).toHaveBeenLastCalledWith('PLAT', { principal: 'Ops', principalKind: 'group', role: 'viewer' }));
  });

  it('shows the server message beside the field when a member is rejected', async () => {
    putMember.mockRejectedValue(Object.assign(new Error('No such user.'), { status: 400 }));
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.change(screen.getByLabelText('Display name or group name'), { target: { value: 'ghost' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add member' }));
    expect(await screen.findByText('No such user.')).toBeInTheDocument();
  });

  it('changes another member role and removes a member', async () => {
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.change(screen.getByLabelText('Role of Bob B'), { target: { value: 'viewer' } });
    await waitFor(() => expect(putMember).toHaveBeenCalledWith('PLAT', { principal: 'bob', principalKind: 'user', role: 'viewer' }));
    fireEvent.click(screen.getByRole('button', { name: 'Remove Bob B' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, remove Bob B' }));
    await waitFor(() => expect(removeMember).toHaveBeenCalledWith('PLAT', { principal: 'bob', principalKind: 'user' }));
  });

  it('changingOwnLeadRoleAsksForConfirmation', async () => {
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.change(screen.getByLabelText('Role of Me Myself'), { target: { value: 'member' } });
    expect(putMember).not.toHaveBeenCalled();
    expect(screen.getByText(/You will lose access to these settings/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(putMember).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('Role of Me Myself'), { target: { value: 'member' } });
    fireEvent.click(screen.getByRole('button', { name: 'Yes, change my role' }));
    await waitFor(() => expect(putMember).toHaveBeenCalledWith('PLAT', { principal: 'Me Myself', principalKind: 'user', role: 'member' }));
  });

  it('offers reporter for people and not for agents', async () => {
    listMembers.mockResolvedValue([...MEMBERS, AGENT_MEMBER]);
    renderSettings();
    await screen.findByText('Bob B');
    const opts = (el) => within(el).getAllByRole('option').map((o) => o.value);
    expect(opts(screen.getByLabelText('Role of Bob B'))).toContain('reporter');
    expect(opts(screen.getByLabelText('Role of build-bot'))).not.toContain('reporter');
    expect(screen.getByText('Reporter: files items and sees only the ones they filed.')).toBeInTheDocument();
    expect(screen.getByText(/or a reporter, also ends the claims/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Role for new member'), { target: { value: 'reporter' } });
    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'agent' } });
    expect(screen.getByLabelText('Role for new member')).toHaveValue('member');
  });

  it('demoting yourself to reporter leaves the project', async () => {
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.change(screen.getByLabelText('Role of Me Myself'), { target: { value: 'reporter' } });
    fireEvent.click(screen.getByRole('button', { name: 'Yes, change my role' }));
    await waitFor(() => expect(putMember).toHaveBeenCalledWith('PLAT', { principal: 'Me Myself', principalKind: 'user', role: 'reporter' }));
    expect(await screen.findByText('HOME')).toBeInTheDocument();
  });

  it('asks before removing yourself', async () => {
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.click(screen.getByRole('button', { name: 'Remove Me Myself' }));
    expect(removeMember).not.toHaveBeenCalled();
    expect(screen.getByText(/You will lose access to these settings/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Yes, remove me' }));
    await waitFor(() => expect(removeMember).toHaveBeenCalledWith('PLAT', { principal: 'Me Myself', principalKind: 'user' }));
  });

  it('hides archive from a lead who is not an admin', async () => {
    renderSettings({ admin: false });
    await screen.findByText('Bob B');
    expect(screen.queryByRole('button', { name: 'Archive project' })).not.toBeInTheDocument();
  });

  it('archiveAsksForConfirmationInline', async () => {
    renderSettings({ admin: true });
    await screen.findByText('Bob B');
    fireEvent.click(screen.getByRole('button', { name: 'Archive project' }));
    expect(window.confirm).not.toHaveBeenCalled();
    expect(archiveProject).not.toHaveBeenCalled();
    expect(screen.getByRole('alertdialog')).toHaveTextContent(/claim an agent holds .* is released/);
    fireEvent.click(screen.getByRole('button', { name: 'Yes, archive PLAT' }));
    await waitFor(() => expect(archiveProject).toHaveBeenCalledWith('PLAT'));
    expect(await screen.findByText('HOME')).toBeInTheDocument();
  });

  it('shows the server reason in a toast when archive is refused', async () => {
    archiveProject.mockRejectedValue(Object.assign(new Error('Admins only.'), { status: 403 }));
    renderSettings({ admin: true });
    await screen.findByText('Bob B');
    fireEvent.click(screen.getByRole('button', { name: 'Archive project' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, archive PLAT' }));
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Admins only.'));
  });

  it('shows an error with Retry when members fail to load', async () => {
    listMembers.mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce(MEMBERS);
    renderSettings();
    expect(await screen.findByText('boom')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Bob B')).toBeInTheDocument();
  });

  it('after your own role change it re-reads the project (so the page redirects) and shows no error panel', async () => {
    renderSettings();
    await screen.findByText('Bob B');
    listMembers.mockClear();
    listMembers.mockRejectedValue(Object.assign(new Error('Not found'), { status: 404 }));
    fireEvent.change(screen.getByLabelText('Role of Me Myself'), { target: { value: 'member' } });
    fireEvent.click(screen.getByRole('button', { name: 'Yes, change my role' }));
    await waitFor(() => expect(reload).toHaveBeenCalled());
    await waitFor(() => expect(listMembers).toHaveBeenCalled());
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
    expect(screen.getByText('Bob B')).toBeInTheDocument();
  });

  it('after removing yourself you land on the work home', async () => {
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.click(screen.getByRole('button', { name: 'Remove Me Myself' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, remove me' }));
    expect(await screen.findByText('HOME')).toBeInTheDocument();
  });

  it('waits for the signed-in user before deciding anything', async () => {
    renderSettings({ authLoading: true });
    expect(screen.getByText('Loading…')).toBeInTheDocument();
    expect(listMembers).not.toHaveBeenCalled();
  });

  it('a double click on Add member submits once', async () => {
    let release;
    putMember.mockReturnValue(new Promise((r) => { release = r; }));
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.change(screen.getByLabelText('Display name or group name'), { target: { value: 'carol' } });
    const add = screen.getByRole('button', { name: 'Add member' });
    fireEvent.click(add);
    fireEvent.click(add);
    expect(putMember).toHaveBeenCalledTimes(1);
    expect(add).toBeDisabled();
    release();
    await waitFor(() => expect(add).not.toBeDisabled());
  });

  it('a double click on the archive confirmation archives once', async () => {
    let release;
    archiveProject.mockReturnValue(new Promise((r) => { release = r; }));
    renderSettings({ admin: true });
    await screen.findByText('Bob B');
    fireEvent.click(screen.getByRole('button', { name: 'Archive project' }));
    const yes = screen.getByRole('button', { name: 'Yes, archive PLAT' });
    fireEvent.click(yes);
    fireEvent.click(yes);
    expect(archiveProject).toHaveBeenCalledTimes(1);
    release();
    expect(await screen.findByText('HOME')).toBeInTheDocument();
  });

  it('an archived project is read-only: no saving, no member changes, no archive button, and says so', async () => {
    useAuth.mockReturnValue({ user: { authenticated: true, roles: ['Admin'] }, loading: false });
    render(
      <MemoryRouter initialEntries={['/work/PLAT/settings']}>
        <WorkProjectContext.Provider value={{ project: { ...PROJECT, archived: true }, role: 'lead', reload }}>
          <Routes><Route path="/work/PLAT/settings" element={<WorkSettings />} /></Routes>
        </WorkProjectContext.Provider>
      </MemoryRouter>,
    );
    await screen.findByText('Bob B');
    expect(screen.queryByRole('button', { name: 'Save details' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Add member' })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Remove / })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Archive project' })).toBeNull();
    expect(screen.getByLabelText('Project name')).toHaveAttribute('readonly');
  });

  it('the archive text says the project stays readable', async () => {
    renderSettings({ admin: true });
    await screen.findByText('Bob B');
    expect(screen.getByText(/becomes read-only and moves to the Archived list/)).toBeInTheDocument();
  });

  it('addAgentMemberOffersActiveAgentsOnly', async () => {
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'agent' } });
    const pick = await screen.findByLabelText('Agent to add');
    const names = within(pick).getAllByRole('option').map((o) => o.textContent);
    expect(names).toContain('triage-bot');
    expect(names).toContain('build-bot');
    expect(names).not.toContain('old-bot');
    fireEvent.change(pick, { target: { value: 'build-bot' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add member' }));
    await waitFor(() => expect(putMember).toHaveBeenCalledWith('PLAT', { principal: 'build-bot', principalKind: 'agent', role: 'member' }));
  });

  it('agentRoleOptionsAreViewerAndMemberOnly', async () => {
    listMembers.mockResolvedValue([...MEMBERS, AGENT_MEMBER]);
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'agent' } });
    const opts = (el) => within(el).getAllByRole('option').map((o) => o.value);
    expect(opts(screen.getByLabelText('Role for new member'))).toEqual(['viewer', 'member']);
    expect(opts(screen.getByLabelText('Role of build-bot'))).toEqual(['viewer', 'member']);
    expect(opts(screen.getByLabelText('Role of Bob B'))).toEqual(['reporter', 'viewer', 'member', 'lead']);
  });

  it('switching the new member to an agent resets a lead role choice', async () => {
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.change(screen.getByLabelText('Role for new member'), { target: { value: 'lead' } });
    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'agent' } });
    expect(screen.getByLabelText('Role for new member')).toHaveValue('member');
  });

  it('agentRowShowsAgentChip', async () => {
    listMembers.mockResolvedValue([...MEMBERS, AGENT_MEMBER]);
    renderSettings();
    const row = (await screen.findByText('build-bot')).closest('li');
    expect(within(row).getByText('agent')).toHaveClass('badge');
  });

  it('an agent row with no effect says why, in words', async () => {
    const rows = [
      { ...AGENT_MEMBER, principal: 'a1', displayName: 'a1', effective: true },
      { ...AGENT_MEMBER, principal: 'a2', displayName: 'a2', effective: false, reason: 'no_sponsor' },
      { ...AGENT_MEMBER, principal: 'a3', displayName: 'a3', effective: false, reason: 'sponsor_gone' },
      { ...AGENT_MEMBER, principal: 'a4', displayName: 'a4', effective: false, reason: 'sponsor_not_member' },
      { ...AGENT_MEMBER, principal: 'a5', displayName: 'a5', effective: false, reason: 'sponsor_viewer' },
      { ...AGENT_MEMBER, principal: 'a6', displayName: 'a6' },
    ];
    listMembers.mockResolvedValue(rows);
    renderSettings();
    const line = async (name) => (await screen.findByText(name)).closest('li');
    expect(await line('a2')).toHaveTextContent('No effect: this agent has no sponsor');
    expect(await line('a3')).toHaveTextContent('No effect: its sponsor no longer has an account');
    expect(await line('a4')).toHaveTextContent('No effect: its sponsor is not a member of this project');
    expect(await line('a5')).toHaveTextContent('Read-only: its sponsor is a viewer here');
    expect(await line('a1')).not.toHaveTextContent(/No effect|Read-only/);
    expect(await line('a6')).not.toHaveTextContent(/No effect|Read-only/);
  });

  it('says that changing a person can end the claims of agents they sponsor', async () => {
    listMembers.mockResolvedValue([AGENT_MEMBER]);
    renderSettings();
    await screen.findByText('build-bot');
    expect(screen.getByText('Removing a person, or making them a viewer or a reporter, also ends the claims of the agents they sponsor in this project.')).toBeInTheDocument();
  });

  it('removing an agent asks first and says its claims go back to the queue', async () => {
    listMembers.mockResolvedValue([...MEMBERS, AGENT_MEMBER]);
    renderSettings();
    await screen.findByText('build-bot');
    fireEvent.click(screen.getByRole('button', { name: 'Remove build-bot' }));
    expect(removeMember).not.toHaveBeenCalled();
    expect(screen.getByRole('alertdialog')).toHaveTextContent(RELEASE_TEXT);
    fireEvent.click(screen.getByRole('button', { name: 'Yes, remove build-bot' }));
    await waitFor(() => expect(removeMember).toHaveBeenCalledWith('PLAT', { principal: 'build-bot', principalKind: 'agent' }));
  });

  it('demoting an agent to viewer asks first and says its claims go back to the queue', async () => {
    listMembers.mockResolvedValue([...MEMBERS, AGENT_MEMBER]);
    renderSettings();
    await screen.findByText('build-bot');
    fireEvent.change(screen.getByLabelText('Role of build-bot'), { target: { value: 'viewer' } });
    expect(putMember).not.toHaveBeenCalled();
    expect(screen.getByRole('alertdialog')).toHaveTextContent(RELEASE_TEXT);
    fireEvent.click(screen.getByRole('button', { name: 'Yes, make build-bot a viewer' }));
    await waitFor(() => expect(putMember).toHaveBeenCalledWith('PLAT', { principal: 'build-bot', principalKind: 'agent', role: 'viewer' }));
  });

  it('shows the server refusal when an agent cannot be added', async () => {
    putMember.mockRejectedValue(Object.assign(new Error('An agent is a viewer or member.'), { status: 400 }));
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'agent' } });
    fireEvent.change(await screen.findByLabelText('Agent to add'), { target: { value: 'triage-bot' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add member' }));
    expect(await screen.findByText('An agent is a viewer or member.')).toBeInTheDocument();
  });

  it('an archived project offers no agent controls', async () => {
    listMembers.mockResolvedValue([...MEMBERS, AGENT_MEMBER]);
    useAuth.mockReturnValue({ user: { authenticated: true, roles: [] }, loading: false });
    render(
      <MemoryRouter initialEntries={['/work/PLAT/settings']}>
        <WorkProjectContext.Provider value={{ project: { ...PROJECT, archived: true }, role: 'lead', reload }}>
          <Routes><Route path="/work/PLAT/settings" element={<WorkSettings />} /></Routes>
        </WorkProjectContext.Provider>
      </MemoryRouter>,
    );
    await screen.findByText('build-bot');
    expect(screen.queryByRole('button', { name: 'Remove build-bot' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Kind')).not.toBeInTheDocument();
  });

  it('agents that are already members are not offered', async () => {
    listMembers.mockResolvedValue([...MEMBERS, AGENT_MEMBER]);
    renderSettings();
    await screen.findByText('build-bot');
    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'agent' } });
    const pick = await screen.findByLabelText('Agent to add');
    const names = within(pick).getAllByRole('option').map((o) => o.textContent);
    expect(names).toContain('triage-bot');
    expect(names).not.toContain('build-bot');
  });

  it('adding someone who is already a member never calls the server', async () => {
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.change(screen.getByLabelText('Display name or group name'), { target: { value: 'Bob B' } });
    fireEvent.change(screen.getByLabelText('Role for new member'), { target: { value: 'lead' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add member' }));
    expect(await screen.findByText('Already a member \u2014 change the role in the list below.')).toBeInTheDocument();
    expect(putMember).not.toHaveBeenCalled();
  });

  it('a refused agent add refreshes the agents and shows the message; switching kind clears it', async () => {
    putMember.mockRejectedValue(Object.assign(new Error('That agent is retired.'), { status: 409 }));
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'agent' } });
    fireEvent.change(await screen.findByLabelText('Agent to add'), { target: { value: 'triage-bot' } });
    listAgents.mockClear();
    fireEvent.click(screen.getByRole('button', { name: 'Add member' }));
    expect(await screen.findByText('That agent is retired.')).toBeInTheDocument();
    await waitFor(() => expect(listAgents).toHaveBeenCalled());
    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'group' } });
    expect(screen.queryByText('That agent is retired.')).not.toBeInTheDocument();
  });
});

describe('WorkSettings usability', () => {
  it('default autonomy is a live select preselected from the project and saved through updateProject', async () => {
    renderSettings();
    const select = await screen.findByLabelText('Default autonomy');
    expect(select).toHaveValue('supervised');
    expect(screen.getByText('A person approves work as ready, merges and closes it.')).toBeInTheDocument();
    expect(screen.getByText('Applies to items with no epic. An epic or an item can set its own level.')).toBeInTheDocument();
    fireEvent.change(select, { target: { value: 'autonomous' } });
    await waitFor(() => expect(updateProject).toHaveBeenCalledWith('PLAT', { defaultAutonomy: 'autonomous' }));
    await waitFor(() => expect(reload).toHaveBeenCalled());
  });

  it('explains the selected autonomy level in plain words', async () => {
    renderSettings();
    fireEvent.change(await screen.findByLabelText('Default autonomy'), { target: { value: 'delegated' } });
    expect(screen.getByText('A person approves work as ready; agents review, merge and close.')).toBeInTheDocument();
  });

  it('a refused autonomy change shows the server message and returns the select to the saved level', async () => {
    updateProject.mockRejectedValue(Object.assign(new Error('Not allowed to change autonomy.'), { status: 403 }));
    renderSettings();
    const select = await screen.findByLabelText('Default autonomy');
    fireEvent.change(select, { target: { value: 'autonomous' } });
    expect(await screen.findByText('Not allowed to change autonomy.')).toHaveAttribute('role', 'alert');
    expect(screen.getByLabelText('Default autonomy')).toHaveValue('supervised');
  });

  it('an archived project shows the autonomy level as text only', async () => {
    useAuth.mockReturnValue({ user: { authenticated: true, roles: [] }, loading: false });
    render(
      <MemoryRouter initialEntries={['/work/PLAT/settings']}>
        <WorkProjectContext.Provider value={{ project: { ...PROJECT, archived: true, defaultAutonomy: 'delegated' }, role: 'lead', reload }}>
          <Routes><Route path="/work/PLAT/settings" element={<WorkSettings />} /></Routes>
        </WorkProjectContext.Provider>
      </MemoryRouter>,
    );
    await screen.findByText('Bob B');
    expect(screen.queryByLabelText('Default autonomy')).toBeNull();
    expect(screen.getByText('delegated')).toBeInTheDocument();
  });

  it('removing a person asks by name, mentions sponsored agents, and cancelling neither removes nor reloads', async () => {
    renderSettings();
    await screen.findByText('Bob B');
    listMembers.mockClear();
    fireEvent.click(screen.getByRole('button', { name: 'Remove Bob B' }));
    const strip = screen.getByRole('alertdialog');
    expect(strip).toHaveTextContent('Remove Bob B from Platform?');
    expect(strip).toHaveTextContent('Agents they sponsor lose their claims in this project.');
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(removeMember).not.toHaveBeenCalled();
    expect(listMembers).not.toHaveBeenCalled();
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });

  it('removing a group asks first without the sponsored-agents line', async () => {
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.click(screen.getByRole('button', { name: 'Remove Devs' }));
    expect(screen.getByRole('alertdialog')).toHaveTextContent('Remove Devs from Platform?');
    expect(screen.getByRole('alertdialog')).not.toHaveTextContent('Agents they sponsor');
  });

  it('a refused removal stays open in the strip with the server message', async () => {
    removeMember.mockRejectedValue(Object.assign(new Error('A project needs at least one lead.'), { status: 409 }));
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.click(screen.getByRole('button', { name: 'Remove Bob B' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, remove Bob B' }));
    const alert = await within(screen.getByRole('alertdialog')).findByRole('alert');
    expect(alert).toHaveTextContent('A project needs at least one lead.');
    expect(screen.getByRole('alertdialog')).toBeInTheDocument();
  });

  it('helper text under the name follows the kind being added', async () => {
    renderSettings();
    await screen.findByText('Bob B');
    expect(screen.getByText("Type the person's display name as it appears on the wiki.")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'group' } });
    expect(screen.getByText("Type the group's name.")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'agent' } });
    expect(screen.getByText('Choose from the agents registered on the Agents page.')).toBeInTheDocument();
  });
});
