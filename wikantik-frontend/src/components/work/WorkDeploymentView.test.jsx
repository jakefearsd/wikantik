import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

vi.mock('../../api/work', () => ({
  getDeployment: vi.fn(), getDeploymentHistory: vi.fn(), approveDeployment: vi.fn(), startDeployment: vi.fn(),
  verifyDeployment: vi.fn(), failDeployment: vi.fn(), rollBackDeployment: vi.fn(), rejectDeployment: vi.fn(),
  listProducts: vi.fn(), listDeployments: vi.fn(), requestDeployment: vi.fn(),
}));
import {
  getDeployment, getDeploymentHistory, approveDeployment, startDeployment, verifyDeployment, failDeployment,
  rollBackDeployment, rejectDeployment,
} from '../../api/work';
vi.mock('../../hooks/useAuth', () => ({ useAuth: vi.fn() }));
import { useAuth } from '../../hooks/useAuth';
import WorkDeploymentView from './WorkDeploymentView';

const dep = (state, extra = {}) => ({
  id: 4, product: 'wikantik', version: '2.5.26', environment: 'production', state, requestedByName: 'codex',
  requestedByKind: 'agent', approvedByName: null, evidence: null,
  items: [{ key: 'PLAT-1', title: 'One', status: 'review' }], mayApprove: false, mayRun: false, ...extra,
});
const renderView = () => render(
  <MemoryRouter initialEntries={['/work/deployments/4']}>
    <Routes><Route path="/work/deployments/:id" element={<WorkDeploymentView />} /></Routes>
  </MemoryRouter>);

beforeEach(() => {
  vi.clearAllMocks();
  useAuth.mockReturnValue({ user: { authenticated: true, roles: [] }, loading: false });
  getDeploymentHistory.mockResolvedValue({ history: [
    { id: 1, actorName: 'codex', actorKind: 'agent', from: null, to: 'requested', at: '2026-11-17T10:00:00Z', note: null },
    { id: 2, actorName: 'Jake Fear', actorKind: 'human', from: 'requested', to: 'approved', at: '2026-11-17T10:05:00Z', note: 'go' },
  ] });
  for (const fn of [approveDeployment, startDeployment, verifyDeployment, failDeployment, rollBackDeployment, rejectDeployment]) fn.mockResolvedValue({});
});

describe('WorkDeploymentView', () => {
  it('shows what it carries, who asked, and its history', async () => {
    getDeployment.mockResolvedValue(dep('approved', { approvedByName: 'Jake Fear', approvedByKind: 'human' }));
    renderView();
    expect(await screen.findByRole('heading', { name: /wikantik 2\.5\.26/ })).toHaveTextContent('approved');
    expect(screen.getByRole('link', { name: 'PLAT-1' })).toHaveAttribute('href', '/work/item/PLAT-1');
    expect(screen.getByText(/requested by codex \(agent\)/)).toBeInTheDocument();
    expect(screen.getByText(/approved by Jake Fear/)).toBeInTheDocument();
    expect(await screen.findByText(/codex \(agent\) requested it/)).toBeInTheDocument();
    expect(screen.getByText(/Jake Fear moved it to approved/)).toBeInTheDocument();
    expect(screen.getByText('go')).toBeInTheDocument();
    expect(screen.getByText('Only the items in projects you belong to are listed.')).toBeInTheDocument();
  });

  it('offers Approve only to someone who may, and sends the note', async () => {
    getDeployment.mockResolvedValue(dep('requested'));
    const waiting = renderView();
    expect(await screen.findByText('Waiting for someone who may approve deployments of this product.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Withdraw' })).toBeInTheDocument();
    waiting.unmount();

    getDeployment.mockResolvedValue(dep('requested', { mayApprove: true }));
    renderView();
    fireEvent.change(await screen.findByLabelText('Note'), { target: { value: 'go ahead' } });
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(approveDeployment).toHaveBeenCalledWith('4', { note: 'go ahead' }));
    await waitFor(() => expect(getDeployment).toHaveBeenCalledTimes(3));
  });

  it('lets a requested or approved deployment be withdrawn or rejected with a reason, and nothing later', async () => {
    getDeployment.mockResolvedValue(dep('requested'));
    const requested = renderView();
    fireEvent.click(await screen.findByRole('button', { name: 'Withdraw' }));
    expect(rejectDeployment).not.toHaveBeenCalled();
    expect(screen.getByRole('alertdialog')).toHaveTextContent('Withdraw this request? It will not run.');
    fireEvent.change(screen.getByLabelText('Why (optional)'), { target: { value: 'wrong version' } });
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Withdraw' }));
    await waitFor(() => expect(rejectDeployment).toHaveBeenCalledWith('4', { note: 'wrong version' }));
    requested.unmount();

    getDeployment.mockResolvedValue(dep('approved', { mayRun: true }));
    const approved = renderView();
    fireEvent.click(await screen.findByRole('button', { name: 'Reject' }));
    expect(screen.getByRole('alertdialog')).toHaveTextContent('Reject this deployment? It will not run.');
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Reject' }));
    await waitFor(() => expect(rejectDeployment).toHaveBeenLastCalledWith('4', { note: null }));
    approved.unmount();

    getDeployment.mockResolvedValue(dep('running', { mayRun: true }));
    renderView();
    await screen.findByRole('button', { name: 'Mark as verified' });
    expect(screen.queryByRole('button', { name: /Reject|Withdraw/ })).toBeNull();
  });

  it('marks an approved deployment as running', async () => {
    getDeployment.mockResolvedValue(dep('approved', { mayRun: true }));
    renderView();
    fireEvent.click(await screen.findByRole('button', { name: 'Mark as running' }));
    await waitFor(() => expect(startDeployment).toHaveBeenCalledWith('4'));
  });

  it('will not mark it verified without evidence, and sends the evidence as typed', async () => {
    getDeployment.mockResolvedValue(dep('running', { mayRun: true }));
    renderView();
    fireEvent.click(await screen.findByRole('button', { name: 'Mark as verified' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Attach the health and smoke-test output before marking this verified.');
    expect(verifyDeployment).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('Health and smoke-test output'), { target: { value: 'health 200\nsmoke ok' } });
    fireEvent.click(screen.getByRole('button', { name: 'Mark as verified' }));
    await waitFor(() => expect(verifyDeployment).toHaveBeenCalledWith('4', { evidence: 'health 200\nsmoke ok' }));
  });

  it('marks a running deployment as failed with a note, and shows a refusal in place', async () => {
    getDeployment.mockResolvedValue(dep('running', { mayRun: true }));
    failDeployment.mockRejectedValue(Object.assign(new Error('A deployment that is verified cannot become failed.'), { status: 409 }));
    renderView();
    fireEvent.click(await screen.findByRole('button', { name: 'Mark as failed' }));
    expect(failDeployment).not.toHaveBeenCalled();
    const strip = screen.getByRole('alertdialog');
    expect(strip).toHaveTextContent('Mark this deployment as failed?');
    fireEvent.change(within(strip).getByLabelText('Why (optional)'), { target: { value: 'smoke red' } });
    fireEvent.click(within(strip).getByRole('button', { name: 'Mark as failed' }));
    await waitFor(() => expect(failDeployment).toHaveBeenCalledWith('4', { note: 'smoke red' }));
    expect(await within(screen.getByRole('alertdialog')).findByRole('alert')).toHaveTextContent('cannot become failed');
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });

  it('shows evidence as plain text, never as markup, and lets a verified deployment be rolled back', async () => {
    getDeployment.mockResolvedValue(dep('verified', { mayRun: true, evidence: '<img src=x onerror=alert(1)> health 200' }));
    const { container } = renderView();
    const pre = (await screen.findByText(/health 200/)).closest('pre');
    expect(pre).toHaveTextContent('<img src=x onerror=alert(1)> health 200');
    expect(container.querySelector('img')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Mark as rolled back' }));
    expect(rollBackDeployment).not.toHaveBeenCalled();
    expect(screen.getByRole('alertdialog')).toHaveTextContent('Items it shipped will no longer count as shipped.');
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Mark as rolled back' }));
    await waitFor(() => expect(rollBackDeployment).toHaveBeenCalledWith('4', { note: null }));
  });

  it('someone who may not run it sees no step to take', async () => {
    getDeployment.mockResolvedValue(dep('running'));
    renderView();
    await screen.findByRole('heading', { name: /wikantik 2\.5\.26/ });
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('an unseen or unknown deployment is the neutral not-found state', async () => {
    getDeployment.mockRejectedValue(Object.assign(new Error('Not found'), { status: 404 }));
    renderView();
    expect(await screen.findByText('No such deployment.')).toBeInTheDocument();
  });

  it('shows the server reason when the deployment cannot be loaded, or a plain default', async () => {
    getDeployment.mockRejectedValue(Object.assign(new Error('The server is unwell.'), { status: 500 }));
    const first = renderView();
    expect(await screen.findByText('The server is unwell.')).toBeInTheDocument();
    first.unmount();
    getDeployment.mockRejectedValue({});
    renderView();
    expect(await screen.findByText('Could not load the deployment.')).toBeInTheDocument();
  });

  it('shows a generic message when a step is refused with no reason', async () => {
    getDeployment.mockResolvedValue(dep('approved', { mayRun: true }));
    startDeployment.mockRejectedValue({});
    renderView();
    fireEvent.click(await screen.findByRole('button', { name: 'Mark as running' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('That did not work.');
  });

  it('says Reject, not Withdraw, to an approver of a requested deployment', async () => {
    getDeployment.mockResolvedValue(dep('requested', { mayApprove: true }));
    renderView();
    expect(await screen.findByRole('button', { name: 'Reject' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Withdraw' })).toBeNull();
  });

  it('lets a failed deployment be rolled back with a note', async () => {
    getDeployment.mockResolvedValue(dep('failed', { mayRun: true }));
    renderView();
    fireEvent.click(await screen.findByRole('button', { name: 'Mark as rolled back' }));
    const strip = screen.getByRole('alertdialog');
    fireEvent.change(within(strip).getByLabelText('Why (optional)'), { target: { value: ' bad migration ' } });
    fireEvent.click(within(strip).getByRole('button', { name: 'Mark as rolled back' }));
    await waitFor(() => expect(rollBackDeployment).toHaveBeenCalledWith('4', { note: 'bad migration' }));
  });

  it('names an unknown requester as someone, shows an unknown state as it is, and copes with odd history', async () => {
    getDeployment.mockResolvedValue(dep('rolled_back', { requestedByName: null, requestedAt: null }));
    getDeploymentHistory.mockResolvedValue({ history: [
      { id: 7, actorName: null, actorKind: 'human', from: 'verified', to: 'mystery', at: null, note: null },
    ] });
    renderView();
    expect(await screen.findByRole('heading', { name: /wikantik 2\.5\.26/ })).toBeInTheDocument();
    expect(screen.getByText(/requested by someone/)).toBeInTheDocument();
    expect(await screen.findByText(/someone moved it to mystery/)).toBeInTheDocument();
  });

  it('words a history row from its to state, with spaces for underscores', async () => {
    getDeployment.mockResolvedValue(dep('rolled_back'));
    getDeploymentHistory.mockResolvedValue({ history: [
      { id: 1, actorName: 'Ana', actorKind: 'human', from: null, to: 'rolled_back', at: null, note: null },
      { id: 2, actorName: 'Bo', actorKind: 'human', from: null, to: 'requested', at: null, note: null },
      { id: 3, actorName: 'Cy', actorKind: 'human', from: 'verified', to: 'odd_state', at: null, note: null },
    ] });
    renderView();
    expect(await screen.findByText(/Ana set it to rolled back/)).toBeInTheDocument();
    expect(screen.getByText(/Bo requested it/)).toBeInTheDocument();
    expect(screen.getByText(/Cy moved it to odd state/)).toBeInTheDocument();
  });

  it('shows an empty history when the server sends none', async () => {
    getDeployment.mockResolvedValue(dep('requested'));
    getDeploymentHistory.mockResolvedValue(undefined);
    renderView();
    expect(await screen.findByRole('heading', { name: 'History' })).toBeInTheDocument();
    expect(screen.queryByText(/moved it to|requested it/)).toBeNull();
  });

  it('has the Work > Deployments breadcrumb heading like the other work pages', async () => {
    getDeployment.mockResolvedValue(dep('approved'));
    renderView();
    const h = await screen.findByRole('heading', { name: /wikantik 2\.5\.26/ });
    expect(within(h).getByRole('link', { name: 'Work' })).toHaveAttribute('href', '/work');
    expect(within(h).getByRole('link', { name: 'Deployments' })).toHaveAttribute('href', '/work/deployments');
  });

  it('does not tell a wiki administrator that only their projects are listed', async () => {
    useAuth.mockReturnValue({ user: { authenticated: true, roles: ['Admin'] }, loading: false });
    getDeployment.mockResolvedValue(dep('approved'));
    renderView();
    await screen.findByRole('heading', { name: /wikantik 2\.5\.26/ });
    expect(screen.queryByText('Only the items in projects you belong to are listed.')).toBeNull();
  });
});
