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
      getDriftStatus: vi.fn(),
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
  api.admin.getDriftStatus.mockResolvedValue({ running: false, phase: null, pagesScanned: 0, totalPages: 0 });
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

  it('shows a determinate progress bar with phase label while sweeping', async () => {
    vi.useFakeTimers();
    try {
      api.admin.getDriftStatus
        .mockResolvedValueOnce({ running: false, phase: null, pagesScanned: 0, totalPages: 0 }) // mount
        .mockResolvedValueOnce({ running: true, phase: 'frontmatter', pagesScanned: 84, totalPages: 312 }) // 1st poll
        .mockResolvedValue({ running: false, phase: null, pagesScanned: 0, totalPages: 0 }); // then idle
      api.admin.runDriftSweep.mockResolvedValue({ state: 'RUNNING' });
      api.admin.getDriftSummary
        .mockResolvedValueOnce(SUMMARY)                                    // mount
        .mockResolvedValue({ ...SUMMARY, sweptAt: '2026-06-10T06:00:00Z' }); // completion check

      render(<AdminDriftPage />);
      await act(async () => {});
      fireEvent.click(screen.getByTestId('drift-run-now'));
      await act(async () => {});

      expect(screen.getByTestId('drift-progress')).toBeInTheDocument();
      expect(screen.getByTestId('drift-progress-label'))
        .toHaveTextContent('84 / 312 pages — validating frontmatter');

      await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
      await act(async () => {});
      expect(screen.queryByTestId('drift-progress')).not.toBeInTheDocument();
      expect(screen.getByTestId('drift-run-now')).toBeEnabled();
    } finally {
      vi.useRealTimers();
    }
  });

  it('detects an in-flight sweep on mount and shows the bar', async () => {
    api.admin.getDriftStatus.mockResolvedValue(
      { running: true, phase: 'frontmatter', pagesScanned: 10, totalPages: 50 });
    render(<AdminDriftPage />);
    await waitFor(() => expect(screen.getByTestId('drift-progress')).toBeInTheDocument());
    expect(screen.getByTestId('drift-run-now')).toBeDisabled();
    expect(screen.getByTestId('drift-progress-label'))
      .toHaveTextContent('10 / 50 pages — validating frontmatter');
  });

  it('renders a full bar with a phase label for the shacl phase', async () => {
    api.admin.getDriftStatus.mockResolvedValue(
      { running: true, phase: 'shacl', pagesScanned: 50, totalPages: 50 });
    render(<AdminDriftPage />);
    await waitFor(() => expect(screen.getByTestId('drift-progress-label'))
      .toHaveTextContent('checking SHACL conformance'));
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
