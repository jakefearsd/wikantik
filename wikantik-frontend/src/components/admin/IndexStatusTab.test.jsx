import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import IndexStatusTab from './IndexStatusTab';
import { api } from '../../api/client';

const idleStatus = {
    pages: { total: 100, system: 5, indexable: 95 },
    lucene: { documents_indexed: 95, queue_depth: 0, last_update: null },
    chunks: { pages_with_chunks: 95, pages_missing_chunks: 0,
              total_chunks: 800, avg_tokens: 287, min_tokens: 42, max_tokens: 512 },
    rebuild: { state: "IDLE", started_at: null,
               pages_total: 0, pages_iterated: 0, pages_chunked: 0,
               system_pages_skipped: 0, lucene_queued: 0, chunks_written: 0,
               errors: [] }
};

const embeddingsEnabledStatus = {
    ...idleStatus,
    embeddings: {
        model_code: 'qwen3-embedding-0.6b',
        dim: 768,
        row_count: 17,
        last_update: '2026-04-19T11:00:00Z',
        bootstrap: {
            state: 'COMPLETED',
            chunks_total: 42,
            chunks_processed: 17,
            started_at: '2026-04-19T10:00:00Z',
            completed_at: '2026-04-19T10:05:00Z',
            error_message: null,
        },
        embedder: {
            circuit_state: 'CLOSED',
            call_success: 100,
            call_failure: 5,
            call_timeout: 1,
            cache_hit: 80,
            cache_miss: 25,
            breaker_open: 0,
            breaker_close: 0,
            breaker_half_open_probe: 0,
            breaker_call_rejected: 0,
        },
    },
};

describe('IndexStatusTab', () => {
    beforeEach(() => {
        vi.useFakeTimers({ shouldAdvanceTime: true });
        vi.spyOn(api.admin, 'getIndexStatus').mockResolvedValue(idleStatus);
    });
    afterEach(() => {
        vi.useRealTimers();
        vi.restoreAllMocks();
    });

    it('renders stat cards from the status response', async () => {
        render(<IndexStatusTab />);
        await waitFor(() => expect(screen.getByText('95')).toBeInTheDocument());
        expect(screen.getByText(/287/)).toBeInTheDocument(); // avg tokens
        expect(screen.getByText(/800/)).toBeInTheDocument(); // total chunks
    });

    it('rebuild button opens confirm dialog and calls API on confirm', async () => {
        const rebuild = vi.spyOn(api.admin, 'rebuildIndexes')
            .mockResolvedValue({ ...idleStatus,
                rebuild: { ...idleStatus.rebuild, state: 'STARTING' } });
        render(<IndexStatusTab />);

        await waitFor(() => screen.getByRole('button', { name: /Rebuild Indexes/i }));
        fireEvent.click(screen.getByRole('button', { name: /Rebuild Indexes/i }));
        // Confirm dialog copy must include "clear"
        expect(screen.getByText(/clear/i)).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: /Continue/i }));
        await waitFor(() => expect(rebuild).toHaveBeenCalled());
    });

    it('disables rebuild button while not IDLE', async () => {
        vi.spyOn(api.admin, 'getIndexStatus').mockResolvedValue({
            ...idleStatus,
            rebuild: { ...idleStatus.rebuild, state: 'RUNNING',
                       pages_total: 95, pages_iterated: 10, pages_chunked: 9,
                       system_pages_skipped: 1, lucene_queued: 10 }
        });
        render(<IndexStatusTab />);
        await waitFor(() => expect(
            screen.getByRole('button', { name: /Rebuild/i })).toBeDisabled());
    });

    it('shows errors panel when rebuild.errors is non-empty', async () => {
        vi.spyOn(api.admin, 'getIndexStatus').mockResolvedValue({
            ...idleStatus,
            rebuild: { ...idleStatus.rebuild, errors: [
                { page: 'BadPage', error: 'NullPointerException: x was null',
                  at: '2026-04-17T10:00:00Z' }
            ]}
        });
        render(<IndexStatusTab />);
        await waitFor(() => expect(screen.getByText(/BadPage/)).toBeInTheDocument());
        expect(screen.getByText(/NullPointerException/)).toBeInTheDocument();
    });

    it('handles 409 conflict gracefully', async () => {
        vi.spyOn(api.admin, 'rebuildIndexes').mockRejectedValue(
            Object.assign(new Error('A rebuild is already in flight'),
                { status: 409, code: 'rebuild_in_flight' }));
        render(<IndexStatusTab />);
        await waitFor(() => screen.getByRole('button', { name: /Rebuild Indexes/i }));
        fireEvent.click(screen.getByRole('button', { name: /Rebuild Indexes/i }));
        fireEvent.click(screen.getByRole('button', { name: /Continue/i }));
        await waitFor(() => expect(
            screen.getByText(/already in flight|conflict/i)).toBeInTheDocument());
    });

    // ----- Embeddings UI section (hidden when embeddings disabled) -----

    it('hides the Embeddings section entirely when no model_code is reported', async () => {
        render(<IndexStatusTab />);
        await waitFor(() => screen.getByText(/Lucene Queue Depth/));
        expect(screen.queryByRole('button', { name: /Reindex Embeddings/i }))
            .not.toBeInTheDocument();
        expect(screen.queryByText(/Circuit:/)).not.toBeInTheDocument();
        expect(screen.queryByText(/Embedding Bootstrap/)).not.toBeInTheDocument();
    });

    it('renders embeddings stat card, bootstrap progress, and embedder metrics when enabled', async () => {
        vi.spyOn(api.admin, 'getIndexStatus').mockResolvedValue(embeddingsEnabledStatus);
        render(<IndexStatusTab />);
        await waitFor(() => screen.getByRole('button', { name: /Reindex Embeddings/i }));
        // Stat card subtitle proves the section rendered with model details.
        expect(screen.getByText(/qwen3-embedding-0\.6b · dim 768/)).toBeInTheDocument();
        // The section header h3 also reads "Embeddings".
        expect(screen.getByRole('heading', { name: /^Embeddings$/i })).toBeInTheDocument();
        // Bootstrap progress block — completed run.
        expect(screen.getByText(/Embedding Bootstrap/)).toBeInTheDocument();
        expect(screen.getByText(/Completed/)).toBeInTheDocument();
        expect(screen.getByText(/Finished at 2026-04-19T10:05:00Z/)).toBeInTheDocument();
        // Embedder metrics block — circuit state and cache hit rate.
        expect(screen.getByText('CLOSED')).toBeInTheDocument();
        // 80 hits / (80+25 miss) = 76.2%
        expect(screen.getByText('76.2%')).toBeInTheDocument();
        expect(screen.getByText(/80 hits \/ 25 miss/)).toBeInTheDocument();
    });

    it('shows progress bar and disables button while bootstrap is RUNNING', async () => {
        vi.spyOn(api.admin, 'getIndexStatus').mockResolvedValue({
            ...embeddingsEnabledStatus,
            embeddings: {
                ...embeddingsEnabledStatus.embeddings,
                row_count: 21,
                bootstrap: {
                    ...embeddingsEnabledStatus.embeddings.bootstrap,
                    state: 'RUNNING',
                    chunks_total: 42,
                    chunks_processed: 21,
                    completed_at: null,
                },
            },
        });
        render(<IndexStatusTab />);
        await waitFor(() =>
            expect(screen.getByRole('button', { name: /Embedding Bootstrap Running/i }))
                .toBeDisabled());
        // 21/42 = 50%
        expect(screen.getByText(/21\/42 chunks embedded \(50%\)/)).toBeInTheDocument();
    });

    it('renders FAILED bootstrap error message in alert role', async () => {
        vi.spyOn(api.admin, 'getIndexStatus').mockResolvedValue({
            ...embeddingsEnabledStatus,
            embeddings: {
                ...embeddingsEnabledStatus.embeddings,
                bootstrap: {
                    ...embeddingsEnabledStatus.embeddings.bootstrap,
                    state: 'FAILED',
                    completed_at: '2026-04-19T10:05:00Z',
                    error_message: 'connection refused: ollama backend down',
                },
            },
        });
        render(<IndexStatusTab />);
        await waitFor(() => screen.getByRole('alert'));
        expect(screen.getByRole('alert'))
            .toHaveTextContent(/connection refused: ollama backend down/);
    });

    it('reindex button calls reindexEmbeddings and surfaces dispatch message', async () => {
        vi.spyOn(api.admin, 'getIndexStatus').mockResolvedValue(embeddingsEnabledStatus);
        const reindex = vi.spyOn(api.admin, 'reindexEmbeddings')
            .mockResolvedValue({ state: 'RUNNING', model_code: 'qwen3-embedding-0.6b' });
        render(<IndexStatusTab />);
        await waitFor(() => screen.getByRole('button', { name: /Reindex Embeddings/i }));
        fireEvent.click(screen.getByRole('button', { name: /Reindex Embeddings/i }));
        await waitFor(() => expect(reindex).toHaveBeenCalled());
        await waitFor(() =>
            expect(screen.getByText(/Embedding reindex dispatched.*RUNNING/i))
                .toBeInTheDocument());
    });

    it('reindex 409 surfaces "already running" error', async () => {
        vi.spyOn(api.admin, 'getIndexStatus').mockResolvedValue(embeddingsEnabledStatus);
        vi.spyOn(api.admin, 'reindexEmbeddings').mockRejectedValue(
            Object.assign(new Error('Embedding bootstrap already running'),
                { status: 409, code: 'embedding_bootstrap_running' }));
        render(<IndexStatusTab />);
        await waitFor(() => screen.getByRole('button', { name: /Reindex Embeddings/i }));
        fireEvent.click(screen.getByRole('button', { name: /Reindex Embeddings/i }));
        await waitFor(() =>
            expect(screen.getByText(/embedding bootstrap is already running/i))
                .toBeInTheDocument());
    });

    it('reindex 503 surfaces hybrid-disabled error', async () => {
        vi.spyOn(api.admin, 'getIndexStatus').mockResolvedValue(embeddingsEnabledStatus);
        vi.spyOn(api.admin, 'reindexEmbeddings').mockRejectedValue(
            Object.assign(new Error('Hybrid disabled'),
                { status: 503, code: 'hybrid_disabled' }));
        render(<IndexStatusTab />);
        await waitFor(() => screen.getByRole('button', { name: /Reindex Embeddings/i }));
        fireEvent.click(screen.getByRole('button', { name: /Reindex Embeddings/i }));
        await waitFor(() =>
            expect(screen.getByText(/Hybrid search disabled/i)).toBeInTheDocument());
    });
});
