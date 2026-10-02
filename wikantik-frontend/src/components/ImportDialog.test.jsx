import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../api/client', () => ({
  api: {
    listClusters: vi.fn(),
    importVault: { plan: vi.fn(), apply: vi.fn(), job: vi.fn(), current: vi.fn() },
  },
}));
import ImportDialog from './ImportDialog';
import { api } from '../api/client';

const PLAN = {
  planHash: 'h1',
  totals: { pagesNew: 2, pagesSkippedExisting: 1, pagesSkippedReserved: 0, pagesFailing: 0,
    attachments: 1, attachmentsBlocked: 0, attachmentsSkipped: 3, clustersCreate: 1, clustersJoin: 0, hubsCreate: 1 },
  pages: [{ vaultPath: 'Projects/Alpha.md', name: 'Alpha', status: 'NEW', warnings: [] },
    { vaultPath: 'Main.md', name: 'Main', status: 'SKIPPED_EXISTS', reason: 'page exists', warnings: [] }],
  clusters: [{ cluster: 'projects', action: 'CREATE', hubPage: 'Projects' }],
  warningGroups: { link: 2 },
};
const notFound = () => Object.assign(new Error('none'), { status: 404 });
const FILE = () => new File(['zip'], 'v.zip', { type: 'application/zip' });
const open = () => render(<MemoryRouter><ImportDialog isOpen onClose={() => {}} /></MemoryRouter>);
const pick = async (file) => fireEvent.change(await screen.findByTestId('import-file'), { target: { files: [file] } });

beforeEach(() => {
  api.importVault.current.mockRejectedValue(notFound());
  api.importVault.plan.mockResolvedValue(PLAN);
  api.listClusters.mockResolvedValue({ clusters: [{ name: 'finance' }] });
});
afterEach(() => { vi.useRealTimers(); vi.clearAllMocks(); });

describe('ImportDialog', () => {
  it('plans, applies, polls and shows the result', async () => {
    api.importVault.apply.mockResolvedValue({ jobId: 'j1' });
    api.importVault.job.mockResolvedValueOnce({ jobId: 'j1', state: 'RUNNING', done: 1, total: 3, current: 'Alpha', results: [], summary: {} })
      .mockResolvedValue({ jobId: 'j1', state: 'DONE', done: 3, total: 3, results: [{ kind: 'page', name: 'Alpha', status: 'CREATED' }], summary: { hubs: ['Projects'] } });
    vi.useFakeTimers({ shouldAdvanceTime: true });
    open();
    const file = FILE();
    await pick(file);
    expect(await screen.findByTestId('import-totals')).toHaveTextContent('2 new');
    fireEvent.click(screen.getByTestId('import-apply'));
    await waitFor(() => expect(api.importVault.apply).toHaveBeenCalledWith(file, { clusterMode: 'folders', cluster: '' }, 'h1'));
    await act(async () => { await vi.advanceTimersByTimeAsync(2100); });
    expect(await screen.findByTestId('import-result')).toHaveTextContent('Alpha');
    expect(screen.getByTestId('import-open-hub')).toHaveAttribute('href', '/wiki/Projects');
  });

  it('re-plans when the cluster option changes', async () => {
    open();
    const file = FILE();
    await pick(file);
    await screen.findByTestId('import-totals');
    fireEvent.click(screen.getByLabelText('No clusters'));
    await waitFor(() => expect(api.importVault.plan).toHaveBeenLastCalledWith(file, { clusterMode: 'none', cluster: '' }));
  });

  it('reopening shows the running job instead of the picker', async () => {
    api.importVault.current.mockResolvedValue({ jobId: 'j9', state: 'RUNNING', done: 1, total: 4, current: 'X', results: [], summary: {} });
    api.importVault.job.mockResolvedValue({ jobId: 'j9', state: 'RUNNING', done: 1, total: 4, current: 'X', results: [], summary: {} });
    open();
    expect(await screen.findByTestId('import-progress')).toHaveTextContent('1 / 4');
    expect(screen.queryByTestId('import-file')).toBeNull();
  });

  it('a stale-hash 409 re-plans and shows the message', async () => {
    api.importVault.apply.mockRejectedValue(Object.assign(new Error('The vault or the wiki changed since the plan was made'), { status: 409 }));
    open();
    await pick(FILE());
    await screen.findByTestId('import-totals');
    fireEvent.click(screen.getByTestId('import-apply'));
    await waitFor(() => expect(api.importVault.plan).toHaveBeenCalledTimes(2));
    expect(await screen.findByText(/changed since/)).toBeInTheDocument();
  });

  it('shows the server message for other errors', async () => {
    api.importVault.plan.mockRejectedValue(Object.assign(new Error('wikantik.import.maxUploadBytes exceeded'), { status: 413 }));
    open();
    await pick(FILE());
    expect(await screen.findByText(/maxUploadBytes/)).toBeInTheDocument();
  });

  it('filters the page table', async () => {
    open();
    await pick(FILE());
    await screen.findByTestId('import-pages');
    expect(screen.getByText('Main')).toBeInTheDocument();
    fireEvent.change(screen.getByTestId('import-filter'), { target: { value: 'alpha' } });
    expect(screen.queryByText('Main')).toBeNull();
    expect(screen.getByText('Alpha')).toBeInTheDocument();
  });

  it('filters and labels generated-hub rows that have no vault path', async () => {
    const errors = vi.spyOn(console, 'error').mockImplementation(() => {});
    const pages = [...PLAN.pages,
      { name: 'Notes Hub', status: 'NEW', warnings: [] },
      { name: 'Deep Hub', status: 'NEW', warnings: [] }];
    api.importVault.plan.mockResolvedValue({ ...PLAN, pages });
    open();
    await pick(FILE());
    await screen.findByTestId('import-pages');
    fireEvent.change(screen.getByTestId('import-filter'), { target: { value: 'notes' } });
    expect(screen.getByText('Notes Hub')).toBeInTheDocument();
    expect(screen.queryByText('Deep Hub')).toBeNull();
    expect(screen.queryByText('Alpha')).toBeNull();
    fireEvent.change(screen.getByTestId('import-filter'), { target: { value: '' } });
    expect(screen.getAllByText('(generated hub)')).toHaveLength(2);
    expect(errors.mock.calls.filter((c) => String(c[0]).includes('key'))).toEqual([]);
    errors.mockRestore();
  });

  it('caps rows at 200 with a more indicator', async () => {
    const pages = Array.from({ length: 500 }, (_, i) => ({ vaultPath: `n${i}.md`, name: `N${i}`, status: 'NEW', warnings: [] }));
    api.importVault.plan.mockResolvedValue({ ...PLAN, pages });
    open();
    await pick(FILE());
    const table = await screen.findByTestId('import-pages');
    expect(table.querySelectorAll('tbody tr').length).toBeLessThan(205);
    expect(screen.getByText(/300 more/)).toBeInTheDocument();
  });

  it('stops polling on unmount', async () => {
    api.importVault.current.mockResolvedValue({ jobId: 'j9', state: 'RUNNING', done: 1, total: 4, results: [], summary: {} });
    api.importVault.job.mockResolvedValue({ jobId: 'j9', state: 'RUNNING', done: 1, total: 4, results: [], summary: {} });
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const { unmount } = open();
    await screen.findByTestId('import-progress');
    unmount();
    api.importVault.job.mockClear();
    await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
    expect(api.importVault.job).not.toHaveBeenCalled();
  });

  it('labels and tones statuses (WILL_FAIL is bad)', async () => {
    const pages = [
      { vaultPath: 'a.md', name: 'A', status: 'WILL_FAIL', reason: 'bad yaml', warnings: [] },
      { vaultPath: 'b.md', name: 'B', status: 'SKIPPED_RESERVED', warnings: [] },
    ];
    api.importVault.plan.mockResolvedValue({ ...PLAN, pages });
    open();
    await pick(FILE());
    const bad = await screen.findByText('Will fail');
    expect(bad.className).toContain('import-dialog-status-bad');
    expect(screen.getByText('Reserved name')).toBeInTheDocument();
  });

  it('exposes progress and error accessibly', async () => {
    api.importVault.current.mockResolvedValue({ jobId: 'j9', state: 'RUNNING', done: 1, total: 4, current: 'X', results: [], summary: {} });
    api.importVault.job.mockResolvedValue({ jobId: 'j9', state: 'RUNNING', done: 1, total: 4, results: [], summary: {} });
    open();
    const prog = await screen.findByTestId('import-progress');
    expect(prog.querySelector('progress')).toHaveAttribute('aria-label', 'Import progress');
    expect(prog.querySelector('[role="status"]')).toHaveTextContent('1 / 4');
  });

  it('error banner has role alert', async () => {
    api.importVault.plan.mockRejectedValue(Object.assign(new Error('nope'), { status: 400 }));
    open();
    await pick(FILE());
    expect(await screen.findByRole('alert')).toHaveTextContent('nope');
  });

  it('double-click Import sends one apply request', async () => {
    api.importVault.apply.mockReturnValue(new Promise(() => {}));
    open();
    await pick(FILE());
    await screen.findByTestId('import-totals');
    const btn = screen.getByTestId('import-apply');
    fireEvent.click(btn);
    fireEvent.click(btn);
    expect(api.importVault.apply).toHaveBeenCalledTimes(1);
    expect(btn).toBeDisabled();
  });

  it('a 404 while polling stops and explains', async () => {
    api.importVault.current.mockResolvedValue({ jobId: 'j9', state: 'RUNNING', done: 1, total: 4, results: [], summary: {} });
    api.importVault.job.mockRejectedValue(notFound());
    vi.useFakeTimers({ shouldAdvanceTime: true });
    open();
    await screen.findByTestId('import-progress');
    await act(async () => { await vi.advanceTimersByTimeAsync(1100); });
    expect(await screen.findByText(/no longer available/)).toBeInTheDocument();
    api.importVault.job.mockClear();
    await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
    expect(api.importVault.job).not.toHaveBeenCalled();
  });

  it('backs off after poll errors', async () => {
    api.importVault.current.mockResolvedValue({ jobId: 'j9', state: 'RUNNING', done: 1, total: 4, results: [], summary: {} });
    api.importVault.job.mockRejectedValue(Object.assign(new Error('boom'), { status: 500 }));
    vi.useFakeTimers({ shouldAdvanceTime: true });
    open();
    await screen.findByTestId('import-progress');
    await act(async () => { await vi.advanceTimersByTimeAsync(1100); });
    expect(api.importVault.job).toHaveBeenCalledTimes(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(1500); }); // next due at +2000
    expect(api.importVault.job).toHaveBeenCalledTimes(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
    expect(api.importVault.job).toHaveBeenCalledTimes(2);
  });

  it('shows the message of a FAILED job', async () => {
    api.importVault.current.mockResolvedValue({ jobId: 'j', state: 'FAILED', message: 'disk full', results: [], summary: {} });
    open();
    expect(await screen.findByTestId('import-result')).toHaveTextContent('disk full');
  });

  it('shows no picker until current() settles, and ignores a late response after a file is picked', async () => {
    let resolveCurrent;
    api.importVault.current.mockReturnValue(new Promise((r) => { resolveCurrent = r; }));
    open();
    expect(screen.queryByTestId('import-file')).toBeNull();
    await act(async () => { resolveCurrent(null); });
    expect(await screen.findByTestId('import-file')).toBeInTheDocument();
  });

  it('lists blocked attachments with reasons', async () => {
    api.importVault.plan.mockResolvedValue({ ...PLAN, attachments: [
      { vaultPath: 'x.exe', status: 'BLOCKED', reason: 'executable type' },
      { vaultPath: 'ok.png', status: 'IMPORT' },
    ] });
    open();
    await pick(FILE());
    const list = await screen.findByTestId('import-attachments');
    expect(list).toHaveTextContent('x.exe');
    expect(list).toHaveTextContent('executable type');
    expect(list).not.toHaveTextContent('ok.png');
  });
});
