import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import AdminDriftPage from './AdminDriftPage';

vi.mock('../../api/client', () => ({
  api: {
    admin: {
      getDriftSummary: vi.fn(),
      getDriftTrend: vi.fn(),
      getDriftPages: vi.fn(),
      runDriftSweep: vi.fn(),
    },
  },
}));

import { api } from '../../api/client';

const SUMMARY = {
  sweptAt: '2026-06-09T05:00:00Z',
  pagesScanned: 120,
  durationMs: 4200,
  triggeredBy: 'scheduled',
  shaclChecked: true,
  counts: [
    { family: 'frontmatter', code: 'status.noncanonical', severity: 'WARNING', count: 5, delta: -3 },
    { family: 'shacl', code: 'wk:implements', severity: 'ERROR', count: 2, delta: null },
  ],
};

const TREND = {
  sweeps: [
    { sweptAt: '2026-06-08T05:00:00Z', shaclChecked: true,
      counts: [{ family: 'frontmatter', code: 'status.noncanonical', severity: 'WARNING', count: 8 }] },
    { sweptAt: '2026-06-09T05:00:00Z', shaclChecked: true,
      counts: [{ family: 'frontmatter', code: 'status.noncanonical', severity: 'WARNING', count: 5 }] },
  ],
};

beforeEach(() => {
  vi.clearAllMocks();
  api.admin.getDriftSummary.mockResolvedValue(SUMMARY);
  api.admin.getDriftTrend.mockResolvedValue(TREND);
});

describe('AdminDriftPage', () => {
  it('renders the latest sweep summary with counts and deltas', async () => {
    render(<AdminDriftPage />);
    await waitFor(() => expect(screen.getByText('status.noncanonical')).toBeInTheDocument());
    expect(screen.getByText('wk:implements')).toBeInTheDocument();
    expect(screen.getByTestId('drift-pages-scanned')).toHaveTextContent('120');
    expect(screen.getByTestId('delta-status.noncanonical')).toHaveTextContent('-3');
  });

  it('shows the empty state before the first sweep', async () => {
    api.admin.getDriftSummary.mockResolvedValue({ sweptAt: null, counts: [] });
    api.admin.getDriftTrend.mockResolvedValue({ sweeps: [] });
    render(<AdminDriftPage />);
    await waitFor(() => expect(screen.getByTestId('drift-empty-state')).toBeInTheDocument());
  });

  it('expanding a row fetches the live page list', async () => {
    api.admin.getDriftPages.mockResolvedValue({
      pages: [{ pageName: 'Drifty', field: 'status', severity: 'WARNING',
                code: 'status.noncanonical', message: 'Non-canonical status', suggestion: 'active' }],
    });
    render(<AdminDriftPage />);
    await waitFor(() => expect(screen.getByText('status.noncanonical')).toBeInTheDocument());

    fireEvent.click(screen.getByTestId('expand-status.noncanonical'));
    await waitFor(() => expect(screen.getByText('Drifty')).toBeInTheDocument());
    expect(api.admin.getDriftPages).toHaveBeenCalledWith('frontmatter', 'status.noncanonical');
    expect(screen.getByText(/active/)).toBeInTheDocument();
  });

  it('run-now triggers a sweep and reloads after completion', async () => {
    api.admin.runDriftSweep.mockResolvedValue({ state: 'RUNNING' });
    render(<AdminDriftPage />);
    await waitFor(() => expect(screen.getByTestId('drift-run-now')).toBeEnabled());

    fireEvent.click(screen.getByTestId('drift-run-now'));
    expect(api.admin.runDriftSweep).toHaveBeenCalled();
    await waitFor(() => expect(api.admin.getDriftSummary.mock.calls.length).toBeGreaterThan(1));
  });

  it('shows a badge when SHACL was not checked', async () => {
    api.admin.getDriftSummary.mockResolvedValue({ ...SUMMARY, shaclChecked: false });
    render(<AdminDriftPage />);
    await waitFor(() => expect(screen.getByTestId('drift-shacl-unchecked')).toBeInTheDocument());
  });
});
