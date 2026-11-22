import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

vi.mock('../../api/work', () => ({ getProductDora: vi.fn() }));
import { getProductDora } from '../../api/work';
vi.mock('../../hooks/useAuth', () => ({ useAuth: vi.fn() }));
import { useAuth } from '../../hooks/useAuth';
import WorkProductDora from './WorkProductDora';

const REPORT = {
  from: '2026-11-02', to: '2026-11-15', zone: 'UTC', product: 'wikantik',
  deploymentFrequency: [{ weekStart: '2026-11-02', deployments: 2 }],
  leadTimeForChanges: { n: 0, p50Seconds: null, p85Seconds: null }, changesWithoutCommitTime: 0, changesWithClampedCommitTime: 0,
  maxCommitLeadDays: 30, changeFailureRate: { numerator: 0, denominator: 2 }, recoveryTime: { n: 0, p50Seconds: null, p85Seconds: null },
  unrecovered: 0, reworkRate: { numerator: 0, denominator: 2 }, merges: { reviewed: 0, unreviewed: 0, direct: 0, flagged: 0, flaggedUnacknowledged: 0 },
};
const renderAt = (url = '/work/products/wikantik/dora') => render(
  <MemoryRouter initialEntries={[url]}>
    <Routes>
      <Route path="/work/products/:product/dora" element={<WorkProductDora />} />
      <Route path="/work" element={<div>work home</div>} />
    </Routes>
  </MemoryRouter>);

beforeEach(() => {
  vi.clearAllMocks();
  useAuth.mockReturnValue({ user: { roles: ['Admin'] }, loading: false });
  getProductDora.mockResolvedValue(REPORT);
});

describe('WorkProductDora', () => {
  it('a non-admin is sent to /work', async () => {
    useAuth.mockReturnValue({ user: { roles: [] }, loading: false });
    renderAt();
    expect(await screen.findByText('work home')).toBeInTheDocument();
    expect(getProductDora).not.toHaveBeenCalled();
  });

  it('an admin sees the product figures, covering every project', async () => {
    renderAt('/work/products/wikantik/dora?from=2026-11-02&to=2026-11-15');
    expect(await screen.findByRole('region', { name: 'Deployment frequency' })).toBeInTheDocument();
    expect(getProductDora).toHaveBeenCalledWith('wikantik', { from: '2026-11-02', to: '2026-11-15' });
    expect(screen.getByText(/changes of every project/)).toBeInTheDocument();
    expect(screen.getByLabelText('From')).toHaveValue('2026-11-02');
  });

  it('does not ask for a period the server would refuse', async () => {
    renderAt();
    await screen.findByRole('region', { name: 'Deployment frequency' });
    getProductDora.mockClear();
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '31-12-2026' } });
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Start: use a real date');
    expect(getProductDora).not.toHaveBeenCalled();
  });

  it('treats the server refusal as not found, not an error banner', async () => {
    getProductDora.mockRejectedValue(Object.assign(new Error('Only wiki admins'), { status: 403 }));
    renderAt();
    expect(await screen.findByText('Not found')).toBeInTheDocument();
    expect(screen.queryByText(/Only wiki admins/)).toBeNull();
  });

  it('shows an error with retry and keeps the last figures when a reload fails', async () => {
    renderAt();
    await screen.findByRole('region', { name: 'Deployment frequency' });
    getProductDora.mockRejectedValueOnce(Object.assign(new Error('boom'), { status: 500 }));
    fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-11-20' } });
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }));
    expect(await screen.findByText(/boom/)).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Deployment frequency' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(screen.queryByText(/boom/)).toBeNull());
  });
});
