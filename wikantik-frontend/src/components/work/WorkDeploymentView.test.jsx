import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

vi.mock('../../api/work', () => ({
  getDeployment: vi.fn(), getDeploymentHistory: vi.fn(), approveDeployment: vi.fn(), startDeployment: vi.fn(),
  verifyDeployment: vi.fn(), failDeployment: vi.fn(), rollBackDeployment: vi.fn(),
  listProducts: vi.fn(), listDeployments: vi.fn(), requestDeployment: vi.fn(),
}));
import {
  getDeployment, getDeploymentHistory, approveDeployment, startDeployment, verifyDeployment, failDeployment,
  rollBackDeployment,
} from '../../api/work';
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
  getDeploymentHistory.mockResolvedValue({ history: [
    { id: 1, actorName: 'codex', actorKind: 'agent', from: null, to: 'requested', at: '2026-11-17T10:00:00Z', note: null },
    { id: 2, actorName: 'Jake Fear', actorKind: 'human', from: 'requested', to: 'approved', at: '2026-11-17T10:05:00Z', note: 'go' },
  ] });
  for (const fn of [approveDeployment, startDeployment, verifyDeployment, failDeployment, rollBackDeployment]) fn.mockResolvedValue({});
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
    waiting.unmount();

    getDeployment.mockResolvedValue(dep('requested', { mayApprove: true }));
    renderView();
    fireEvent.change(await screen.findByLabelText('Note'), { target: { value: 'go ahead' } });
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(approveDeployment).toHaveBeenCalledWith('4', { note: 'go ahead' }));
    await waitFor(() => expect(getDeployment).toHaveBeenCalledTimes(3));
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
    fireEvent.change(await screen.findByLabelText('Note'), { target: { value: 'smoke red' } });
    fireEvent.click(screen.getByRole('button', { name: 'Mark as failed' }));
    await waitFor(() => expect(failDeployment).toHaveBeenCalledWith('4', { note: 'smoke red' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('cannot become failed');
  });

  it('shows evidence as plain text, never as markup, and lets a verified deployment be rolled back', async () => {
    getDeployment.mockResolvedValue(dep('verified', { mayRun: true, evidence: '<img src=x onerror=alert(1)> health 200' }));
    const { container } = renderView();
    const pre = (await screen.findByText(/health 200/)).closest('pre');
    expect(pre).toHaveTextContent('<img src=x onerror=alert(1)> health 200');
    expect(container.querySelector('img')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Mark as rolled back' }));
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
});
