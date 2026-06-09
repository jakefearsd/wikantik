import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
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

    fireEvent.click(screen.getByTestId('expand-frontmatter|status.noncanonical'));
    await waitFor(() => expect(screen.getByText('Drifty')).toBeInTheDocument());
    expect(api.admin.getDriftPages).toHaveBeenCalledWith('frontmatter', 'status.noncanonical');
    expect(screen.getByText(/active/)).toBeInTheDocument();
  });

  it('run-now polls until a new sweep lands and re-enables the button', async () => {
    vi.useFakeTimers();
    try {
      // Initial load and the first (immediate) poll see the old sweep;
      // the poll after the 2s interval sees a NEW sweptAt.
      api.admin.getDriftSummary
        .mockResolvedValueOnce(SUMMARY)
        .mockResolvedValueOnce(SUMMARY)
        .mockResolvedValue({ ...SUMMARY, sweptAt: '2026-06-09T06:00:00Z' });
      api.admin.runDriftSweep.mockResolvedValue({ state: 'RUNNING' });

      render(<AdminDriftPage />);
      await act(async () => {}); // flush the initial load
      expect(screen.getByTestId('drift-run-now')).toBeEnabled();

      fireEvent.click(screen.getByTestId('drift-run-now'));
      expect(api.admin.runDriftSweep).toHaveBeenCalled();
      expect(screen.getByTestId('drift-run-now')).toBeDisabled();

      // First poll returns the OLD sweptAt — button must stay disabled.
      await act(async () => {});
      expect(screen.getByTestId('drift-run-now')).toBeDisabled();

      // Advance past the poll interval: next poll sees the NEW sweptAt and completes.
      await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
      expect(screen.getByTestId('drift-run-now')).toBeEnabled();
      expect(api.admin.getDriftTrend.mock.calls.length).toBeGreaterThan(1); // trend refreshed
      expect(vi.getTimerCount()).toBe(0); // no dangling poll timer
    } finally {
      vi.useRealTimers();
    }
  });

  it('shows the page-level error when the initial load fails', async () => {
    api.admin.getDriftSummary.mockRejectedValue(new Error('boom'));
    render(<AdminDriftPage />);
    await waitFor(() => expect(screen.getByText('boom')).toBeInTheDocument());
  });

  it('run-now surfaces a 409 as "already running" and re-enables the button', async () => {
    const err = new Error('conflict');
    err.status = 409;
    api.admin.runDriftSweep.mockRejectedValue(err);
    render(<AdminDriftPage />);
    await waitFor(() => expect(screen.getByTestId('drift-run-now')).toBeEnabled());

    fireEvent.click(screen.getByTestId('drift-run-now'));
    await waitFor(() =>
      expect(screen.getByText('A sweep is already running.')).toBeInTheDocument());
    expect(screen.getByTestId('drift-run-now')).toBeEnabled();
  });

  it('shows a badge when SHACL was not checked', async () => {
    api.admin.getDriftSummary.mockResolvedValue({ ...SUMMARY, shaclChecked: false });
    render(<AdminDriftPage />);
    await waitFor(() => expect(screen.getByTestId('drift-shacl-unchecked')).toBeInTheDocument());
  });
});
