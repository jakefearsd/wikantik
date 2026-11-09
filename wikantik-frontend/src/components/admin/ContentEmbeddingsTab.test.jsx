import { render, screen, fireEvent, waitFor, act, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import ContentEmbeddingsTab from './ContentEmbeddingsTab';

vi.mock('../../api/client', () => ({
  api: {
    admin: { embeddings: {
      getStatus: vi.fn(),
      getPagesWithoutFrontmatter: vi.fn(),
      backfillFrontmatter: vi.fn(),
      getBackfillStatus: vi.fn(),
    } },
  },
}));

import { api } from '../../api/client';

const STATUS_READY = { ready: true, model: 'qwen3-embedding:0.6b', dimension: 1024, pagesEmbedded: 120, chunksTotal: 2000, chunksEmbedded: 1500 };

beforeEach(() => {
  vi.clearAllMocks();
  api.admin.embeddings.getStatus.mockResolvedValue(STATUS_READY);
  api.admin.embeddings.getPagesWithoutFrontmatter.mockResolvedValue({ pages: [], total: 0 });
  api.admin.embeddings.backfillFrontmatter.mockResolvedValue({});
  api.admin.embeddings.getBackfillStatus.mockResolvedValue({ running: false, processed: 0, total: 0 });
});

afterEach(() => {
  vi.useRealTimers();
});

describe('ContentEmbeddingsTab — status render', () => {
  it('renders model, dimension, pages and chunk coverage', async () => {
    render(<ContentEmbeddingsTab />);
    await screen.findByText('Ready');
    expect(screen.getByText('qwen3-embedding:0.6b')).toBeTruthy();
    expect(screen.getByText('dim 1024')).toBeTruthy();
    expect(screen.getByText('120 pages embedded')).toBeTruthy();
    expect(screen.getByText('1500 / 2000 chunks embedded (75%)')).toBeTruthy();
    expect(api.admin.embeddings.getStatus).toHaveBeenCalledTimes(1);
  });

  it('renders Not populated when index is not ready and never mentions the knowledge graph', async () => {
    api.admin.embeddings.getStatus.mockResolvedValue({ ready: false, chunksTotal: 0, chunksEmbedded: 0 });
    const { container } = render(<ContentEmbeddingsTab />);
    await screen.findByText('Not populated');
    expect(screen.queryByText(/chunks embedded/)).toBeNull();
    expect(container.textContent).not.toMatch(/knowledge graph|nodes with mentions/i);
  });
});

describe('ContentEmbeddingsTab — loading and error', () => {
  it('shows loading indicator before data resolves', () => {
    let resolve;
    api.admin.embeddings.getStatus.mockReturnValue(new Promise((r) => { resolve = r; }));
    render(<ContentEmbeddingsTab />);
    expect(screen.getByText(/Loading content embeddings/)).toBeTruthy();
    resolve(STATUS_READY);
  });

  it('renders error when status load rejects', async () => {
    api.admin.embeddings.getStatus.mockRejectedValue(new Error('boom'));
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
    api.admin.embeddings.getPagesWithoutFrontmatter.mockResolvedValue({
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
    expect(api.admin.embeddings.getPagesWithoutFrontmatter).toHaveBeenCalledWith(50, 0);
  });
});

describe('ContentEmbeddingsTab — backfill trigger and polling', () => {
  it('cancelled confirm does not call the API', async () => {
    api.admin.embeddings.getPagesWithoutFrontmatter.mockResolvedValue({
      pages: [{ name: 'Alpha', lastModified: null }],
      total: 1,
    });
    render(<ContentEmbeddingsTab />);
    await screen.findByText('Backfill Frontmatter');
    fireEvent.click(screen.getByText('Backfill Frontmatter'));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: /Cancel/i }));
    expect(api.admin.embeddings.backfillFrontmatter).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('confirmed backfill triggers the API and polls status until not running', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    api.admin.embeddings.getPagesWithoutFrontmatter.mockResolvedValue({
      pages: [{ name: 'Alpha', lastModified: null }],
      total: 1,
    });
    // First poll returns running, second returns finished.
    api.admin.embeddings.getBackfillStatus
      .mockResolvedValueOnce({ running: true, processed: 1, total: 3 })
      .mockResolvedValueOnce({ running: false, processed: 3, total: 3 });

    render(<ContentEmbeddingsTab />);
    await screen.findByText('Backfill Frontmatter');

    fireEvent.click(screen.getByText('Backfill Frontmatter'));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: /Continue/i }));

    await waitFor(() => expect(api.admin.embeddings.backfillFrontmatter).toHaveBeenCalledTimes(1));

    // Advance past the 2000ms poll interval twice.
    await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
    await act(async () => { await vi.advanceTimersByTimeAsync(2000); });

    await waitFor(() => expect(api.admin.embeddings.getBackfillStatus).toHaveBeenCalledTimes(2));
    // On completion loadData runs again — getEmbeddingStatus called twice total (mount + reload).
    await waitFor(() => expect(api.admin.embeddings.getStatus).toHaveBeenCalledTimes(2));
  });

  it('surfaces an error if backfill trigger rejects', async () => {
    api.admin.embeddings.getPagesWithoutFrontmatter.mockResolvedValue({
      pages: [{ name: 'Alpha', lastModified: null }],
      total: 1,
    });
    api.admin.embeddings.backfillFrontmatter.mockRejectedValue(new Error('backfill failed'));
    render(<ContentEmbeddingsTab />);
    await screen.findByText('Backfill Frontmatter');
    fireEvent.click(screen.getByText('Backfill Frontmatter'));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: /Continue/i }));
    await screen.findByText('backfill failed');
  });
});
