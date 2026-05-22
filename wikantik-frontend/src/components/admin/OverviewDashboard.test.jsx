// OverviewDashboard.test.jsx
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { api } from '../../api/client';
import OverviewDashboard from './OverviewDashboard';

vi.mock('../../api/client', () => ({ api: { admin: { getOverview: vi.fn() } } }));

const payload = {
  load: { inflight: 3, permitsMax: 390, rejected: 0 },
  kgProposals: { pending: 17 },
  degraded: ['retrieval'],
};

beforeEach(() => { api.admin.getOverview.mockResolvedValue(payload); });

describe('OverviewDashboard', () => {
  it('renders the status band cards from the payload', async () => {
    render(<MemoryRouter><OverviewDashboard /></MemoryRouter>);
    await waitFor(() => expect(screen.getByText('17')).toBeInTheDocument());
    expect(screen.getByText('Status & action')).toBeInTheDocument();
    expect(screen.getByText('System metrics')).toBeInTheDocument();
  });

  it('renders a degraded card in its unavailable state', async () => {
    render(<MemoryRouter><OverviewDashboard /></MemoryRouter>);
    await waitFor(() => expect(screen.getAllByText(/unavailable/i).length).toBeGreaterThan(0));
  });
});
