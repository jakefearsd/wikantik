// OverviewDashboard.test.jsx
import { render, screen, waitFor, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { api } from '../../api/client';
import OverviewDashboard from './OverviewDashboard';

vi.mock('../../api/client', () => ({ api: { admin: { getOverview: vi.fn() } } }));

const payload = {
  load: { inflight: 3, permitsMax: 390, rejected: 0 },
  kgProposals: { pending: 17 },
  users: { users: 5, apiKeys: 3, locked: 1 },
  kgSize: { nodes: 200, edges: 450, stubs: 12, orphans: 4 },
  judge: { pending: 8, timeouts: 2, shortCircuit: 1 },
  auth: { logins: 42, failed: 3 },
  agentSurface: { hubSynthesis: 7, hintFailures: 0, forAgentBytes: 1024, forAgentCount: 15 },
  contentQuality: { authoritative: 80, provisional: 15, stale: 5, noVerification: 10 },
  retrievalModes: { bm25: 100, hybrid: 250, hybridGraph: 30 },
  attachments: { provider: 'BasicAttachmentProvider', maxSize: '10MB', allowedCount: 200, forbiddenCount: 0 },
  degraded: ['retrieval'],
};

beforeEach(() => { api.admin.getOverview.mockResolvedValue(payload); });

describe('OverviewDashboard', () => {
  it('renders the status band cards from the payload', async () => {
    render(<MemoryRouter><OverviewDashboard /></MemoryRouter>);
    expect(await screen.findByText('17')).toBeInTheDocument();
    expect(screen.getByText('Status & action')).toBeInTheDocument();
    expect(screen.getByText('System metrics')).toBeInTheDocument();
  });

  it('renders a degraded card in its unavailable state', async () => {
    render(<MemoryRouter><OverviewDashboard /></MemoryRouter>);
    await waitFor(() => expect(screen.getAllByText(/unavailable/i).length).toBeGreaterThan(0));
  });

  it('enriched users card shows api keys and locked count', async () => {
    render(<MemoryRouter><OverviewDashboard /></MemoryRouter>);
    expect(await screen.findByText('3 keys · 1 locked')).toBeInTheDocument();
  });

  it('enriched kgSize card shows edges, stubs, and orphans', async () => {
    render(<MemoryRouter><OverviewDashboard /></MemoryRouter>);
    expect(await screen.findByText('450 edges · 12 stubs · 4 orphans')).toBeInTheDocument();
  });

  it('enriched judge card shows pending as value and timeout/sc in meta', async () => {
    render(<MemoryRouter><OverviewDashboard /></MemoryRouter>);
    expect(await screen.findByText('2 timeout · 1 sc')).toBeInTheDocument();
  });

  it('enriched auth card shows failed count in meta', async () => {
    render(<MemoryRouter><OverviewDashboard /></MemoryRouter>);
    expect(await screen.findByText('3 failed')).toBeInTheDocument();
  });

  it('enriched agentSurface card shows forAgentBytes and hintFailures in meta', async () => {
    render(<MemoryRouter><OverviewDashboard /></MemoryRouter>);
    expect(await screen.findByText('1024B avg · 0 hint fails')).toBeInTheDocument();
  });

  it('new contentQuality card renders authoritative value and breakdown meta', async () => {
    render(<MemoryRouter><OverviewDashboard /></MemoryRouter>);
    expect(await screen.findByText('15 prov · 5 stale · 10 none')).toBeInTheDocument();
  });

  it('new retrievalModes card renders hybrid value and bm25/hybrid/graph meta', async () => {
    render(<MemoryRouter><OverviewDashboard /></MemoryRouter>);
    expect(await screen.findByText('bm25 100 · hybrid 250 · graph 30')).toBeInTheDocument();
  });

  it('new attachments card renders provider value and maxSize/allowedCount meta', async () => {
    render(<MemoryRouter><OverviewDashboard /></MemoryRouter>);
    expect(await screen.findByText('10MB · 200 allowed')).toBeInTheDocument();
  });

  it('testid: admin-overview root and metric-card-users wrapper exist', async () => {
    render(<MemoryRouter><OverviewDashboard /></MemoryRouter>);
    expect(await screen.findByTestId('admin-overview')).toBeInTheDocument();
    expect(screen.getByTestId('metric-card-users')).toBeInTheDocument();
  });

  it('testid: metric-card-contentQuality, metric-card-retrievalModes, metric-card-attachments exist', async () => {
    render(<MemoryRouter><OverviewDashboard /></MemoryRouter>);
    expect(await screen.findByTestId('admin-overview')).toBeInTheDocument();
    expect(screen.getByTestId('metric-card-contentQuality')).toBeInTheDocument();
    expect(screen.getByTestId('metric-card-retrievalModes')).toBeInTheDocument();
    expect(screen.getByTestId('metric-card-attachments')).toBeInTheDocument();
  });

  it('link card: kgProposals renders an anchor to /admin/knowledge-graph', async () => {
    render(<MemoryRouter><OverviewDashboard /></MemoryRouter>);
    expect(await screen.findByText('17')).toBeInTheDocument();
    const link = screen.getByRole('link', { name: /kg proposals/i });
    expect(link).toHaveAttribute('href', '/admin/knowledge-graph');
  });

  it('link card: users renders an anchor to /admin/users', async () => {
    render(<MemoryRouter><OverviewDashboard /></MemoryRouter>);
    expect(await screen.findByText('5')).toBeInTheDocument();
    const link = screen.getByRole('link', { name: /users/i });
    expect(link).toHaveAttribute('href', '/admin/users');
  });

  it('error state: renders error banner when API rejects', async () => {
    api.admin.getOverview.mockRejectedValueOnce(new Error('boom'));
    render(<MemoryRouter><OverviewDashboard /></MemoryRouter>);
    expect(await screen.findByText('boom')).toBeInTheDocument();
    expect(document.querySelector('.error-banner')).not.toBeNull();
  });

  it('polling: calls getOverview again after POLL_MS elapses', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    api.admin.getOverview.mockResolvedValue(payload);

    render(<MemoryRouter><OverviewDashboard /></MemoryRouter>);

    // Drain microtasks so the initial async load resolves
    await act(async () => {
      await Promise.resolve();
    });

    const callsAfterFirst = api.admin.getOverview.mock.calls.length;
    expect(callsAfterFirst).toBeGreaterThanOrEqual(1);

    // Advance past POLL_MS (20000ms) to trigger the interval callback, then drain
    await act(async () => {
      await vi.advanceTimersByTimeAsync(20001);
    });

    expect(api.admin.getOverview.mock.calls.length).toBeGreaterThanOrEqual(2);

    vi.useRealTimers();
  });
});
