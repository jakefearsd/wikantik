import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';

vi.mock('../api/client', () => ({
  api: { exportVault: { options: vi.fn(), preview: vi.fn(), downloadUrl: vi.fn(() => '/api/export?cluster=finance'), params: vi.fn() } },
}));
import ExportDialog from './ExportDialog';
import { api } from '../api/client';

const PREVIEW = { pages: 12, attachments: 3, estimatedBytes: 2_500_000, unresolvedLinks: 4, cap: 2000, overCap: false, sample: ['A', 'B'] };

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  api.exportVault.options.mockResolvedValue({ clusters: ['finance', 'math'], tags: ['tax'] });
  api.exportVault.preview.mockResolvedValue(PREVIEW);
});
afterEach(() => { vi.useRealTimers(); vi.clearAllMocks(); });

const open = (props = {}) => render(<ExportDialog isOpen onClose={vi.fn()} {...props} />);

describe('ExportDialog', () => {
  it('prefills the cluster and previews it', async () => {
    open({ initialCluster: 'finance' });
    await act(() => vi.advanceTimersByTimeAsync(350));
    await waitFor(() => expect(api.exportVault.preview).toHaveBeenCalled());
    expect(api.exportVault.preview.mock.calls.at(-1)[0].clusters).toEqual(['finance']);
    expect(await screen.findByTestId('export-preview-summary')).toHaveTextContent('12 pages · up to 3 attachments · ~2.4 MB · 4 links unresolved');
    expect(screen.getByTestId('export-download')).toHaveAttribute('href', '/api/export?cluster=finance');
  });
  it('debounces rapid changes into one preview call', async () => {
    open();
    await act(() => vi.advanceTimersByTimeAsync(350));
    api.exportVault.preview.mockClear();
    fireEvent.click(screen.getByRole('button', { name: '1' }));
    fireEvent.click(screen.getByRole('button', { name: '2' }));
    await act(() => vi.advanceTimersByTimeAsync(350));
    expect(api.exportVault.preview).toHaveBeenCalledTimes(1);
    expect(api.exportVault.preview.mock.calls[0][0].hops).toBe(2);
  });
  it('disables download while a change is waiting on the debounce', async () => {
    open();
    await act(() => vi.advanceTimersByTimeAsync(350));
    expect(await screen.findByTestId('export-download')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '1' }));
    // Before the 300 ms debounce fires, the old preview is stale — no download against it.
    expect(screen.queryByTestId('export-download')).toBeNull();
    expect(screen.getByTestId('export-disabled-reason')).toHaveTextContent('Updating preview…');
    await act(() => vi.advanceTimersByTimeAsync(350));
    expect(await screen.findByTestId('export-download')).toBeInTheDocument();
  });
  it('a superseded preview settling does not re-enable download', async () => {
    open();
    await act(() => vi.advanceTimersByTimeAsync(350));
    let resolveSlow;
    api.exportVault.preview.mockImplementationOnce(() => new Promise((r) => { resolveSlow = r; }));
    fireEvent.click(screen.getByRole('button', { name: '1' }));
    await act(() => vi.advanceTimersByTimeAsync(350)); // slow request for hops=1 now in flight
    fireEvent.click(screen.getByRole('button', { name: '2' })); // new change, debounce pending
    await act(async () => { resolveSlow({ ...PREVIEW, pages: 99 }); });
    expect(screen.queryByTestId('export-download')).toBeNull();
    await act(() => vi.advanceTimersByTimeAsync(350));
    expect(await screen.findByTestId('export-download')).toBeInTheDocument();
    expect(screen.getByTestId('export-preview-summary')).toHaveTextContent('12 pages');
  });
  it('disables download over the cap with a reason', async () => {
    api.exportVault.preview.mockResolvedValue({ ...PREVIEW, pages: 2500, overCap: true });
    open();
    await act(() => vi.advanceTimersByTimeAsync(350));
    expect(await screen.findByTestId('export-disabled-reason')).toHaveTextContent('2500 pages exceeds the limit of 2000');
    expect(screen.queryByTestId('export-download')).toBeNull();
  });
  it('disables download for an empty selection', async () => {
    api.exportVault.preview.mockResolvedValue({ ...PREVIEW, pages: 0, sample: [] });
    open();
    await act(() => vi.advanceTimersByTimeAsync(350));
    expect(await screen.findByTestId('export-disabled-reason')).toHaveTextContent('Nothing matches this selection');
    expect(screen.queryByTestId('export-download')).toBeNull();
  });
  it('shows the denied message on 403', async () => {
    api.exportVault.preview.mockRejectedValue(Object.assign(new Error('Forbidden'), { status: 403 }));
    open();
    await act(() => vi.advanceTimersByTimeAsync(350));
    expect(await screen.findByTestId('export-denied')).toHaveTextContent('Export is disabled for your account.');
  });
});
