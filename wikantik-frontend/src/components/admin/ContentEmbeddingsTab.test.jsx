import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import ContentEmbeddingsTab from './ContentEmbeddingsTab';

vi.mock('../../api/client', () => ({
  api: {
    knowledge: {
      getEmbeddingStatus: vi.fn(),
      getPagesWithoutFrontmatter: vi.fn(),
      backfillFrontmatter: vi.fn(),
      getBackfillStatus: vi.fn(),
    },
  },
}));

import { api } from '../../api/client';

const STATUS_READY = { ready: true, dimension: 384, mentioned_node_count: 42 };

beforeEach(() => {
  vi.clearAllMocks();
  api.knowledge.getEmbeddingStatus.mockResolvedValue(STATUS_READY);
  api.knowledge.getPagesWithoutFrontmatter.mockResolvedValue({ pages: [], total: 0 });
  api.knowledge.backfillFrontmatter.mockResolvedValue({});
  api.knowledge.getBackfillStatus.mockResolvedValue({ running: false, processed: 0, total: 0 });
});

afterEach(() => {
  vi.useRealTimers();
});

describe('ContentEmbeddingsTab — status render', () => {
  it('renders Ready status with dimension and node counts', async () => {
    render(<ContentEmbeddingsTab />);
    await screen.findByText('Ready');
    expect(screen.getByText('dim 384')).toBeTruthy();
    expect(screen.getByText('42 nodes with mentions')).toBeTruthy();
    expect(api.knowledge.getEmbeddingStatus).toHaveBeenCalledTimes(1);
  });

  it('renders Not populated when index is not ready', async () => {
    api.knowledge.getEmbeddingStatus.mockResolvedValue({ ready: false });
    render(<ContentEmbeddingsTab />);
    await screen.findByText('Not populated');
    expect(screen.queryByText(/nodes with mentions/)).toBeNull();
  });
});

describe('ContentEmbeddingsTab — loading and error', () => {
  it('shows loading indicator before data resolves', () => {
    let resolve;
    api.knowledge.getEmbeddingStatus.mockReturnValue(new Promise((r) => { resolve = r; }));
    render(<ContentEmbeddingsTab />);
    expect(screen.getByText(/Loading content embeddings/)).toBeTruthy();
    resolve(STATUS_READY);
  });

  it('renders error when status load rejects', async () => {
    api.knowledge.getEmbeddingStatus.mockRejectedValue(new Error('boom'));
    render(<ContentEmbeddingsTab />);
    await screen.findByText('boom');
  });
});

describe('ContentEmbeddingsTab — pages-without-frontmatter list', () => {
  it('shows EmptyState when every page has frontmatter (total 0)', async () => {
    render(<ContentEmbeddingsTab />);
    await screen.findByText('Every page has frontmatter — nothing to backfill.');
    expect(screen.queryByText('Backfill Frontmatter')).toBeNull();
  });

  it('renders the table of pages lacking frontmatter when total > 0', async () => {
    api.knowledge.getPagesWithoutFrontmatter.mockResolvedValue({
      pages: [
        { name: 'Alpha', lastModified: '2026-01-01T00:00:00Z' },
        { name: 'Beta', lastModified: null },
      ],
      total: 2,
    });
    render(<ContentEmbeddingsTab />);
    await screen.findByText('Alpha');
    expect(screen.getByText('Beta')).toBeTruthy();
    // Header shows the total count
    expect(screen.getByText('(2)')).toBeTruthy();
    // Backfill button present
    expect(screen.getByText('Backfill Frontmatter')).toBeTruthy();
    // First load fetches with limit 50, offset 0
    expect(api.knowledge.getPagesWithoutFrontmatter).toHaveBeenCalledWith(50, 0);
  });
});

describe('ContentEmbeddingsTab — backfill trigger and polling', () => {
  it('cancelled confirm does not call the API', async () => {
    api.knowledge.getPagesWithoutFrontmatter.mockResolvedValue({
      pages: [{ name: 'Alpha', lastModified: null }],
      total: 1,
    });
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false);
    render(<ContentEmbeddingsTab />);
    await screen.findByText('Backfill Frontmatter');
    fireEvent.click(screen.getByText('Backfill Frontmatter'));
    expect(confirmSpy).toHaveBeenCalled();
    expect(api.knowledge.backfillFrontmatter).not.toHaveBeenCalled();
    confirmSpy.mockRestore();
  });

  it('confirmed backfill triggers the API and polls status until not running', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    api.knowledge.getPagesWithoutFrontmatter.mockResolvedValue({
      pages: [{ name: 'Alpha', lastModified: null }],
      total: 1,
    });
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    // First poll returns running, second returns finished.
    api.knowledge.getBackfillStatus
      .mockResolvedValueOnce({ running: true, processed: 1, total: 3 })
      .mockResolvedValueOnce({ running: false, processed: 3, total: 3 });

    render(<ContentEmbeddingsTab />);
    await screen.findByText('Backfill Frontmatter');

    fireEvent.click(screen.getByText('Backfill Frontmatter'));

    await waitFor(() => expect(api.knowledge.backfillFrontmatter).toHaveBeenCalledTimes(1));

    // Advance past the 2000ms poll interval twice.
    await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
    await act(async () => { await vi.advanceTimersByTimeAsync(2000); });

    await waitFor(() => expect(api.knowledge.getBackfillStatus).toHaveBeenCalledTimes(2));
    // On completion loadData runs again — getEmbeddingStatus called twice total (mount + reload).
    await waitFor(() => expect(api.knowledge.getEmbeddingStatus).toHaveBeenCalledTimes(2));
    confirmSpy.mockRestore();
  });

  it('surfaces an error if backfill trigger rejects', async () => {
    api.knowledge.getPagesWithoutFrontmatter.mockResolvedValue({
      pages: [{ name: 'Alpha', lastModified: null }],
      total: 1,
    });
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    api.knowledge.backfillFrontmatter.mockRejectedValue(new Error('backfill failed'));
    render(<ContentEmbeddingsTab />);
    await screen.findByText('Backfill Frontmatter');
    fireEvent.click(screen.getByText('Backfill Frontmatter'));
    await screen.findByText('backfill failed');
    confirmSpy.mockRestore();
  });
});
