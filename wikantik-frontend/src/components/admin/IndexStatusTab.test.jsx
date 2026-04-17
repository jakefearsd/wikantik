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
});
