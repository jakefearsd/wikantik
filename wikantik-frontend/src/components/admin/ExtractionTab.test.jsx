import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import ExtractionTab from './ExtractionTab';
import { api } from '../../api/client';

const idle = {
  state: 'IDLE', totalPages: 0, processedPages: 0, failedPages: 0,
  totalChunks: 0, processedChunks: 0, failedChunks: 0,
  mentionsWritten: 0, proposalsFiled: 0, elapsedMs: 0,
  forceOverwrite: false, concurrency: 4, startedAt: null, finishedAt: null,
  excludedSkipped: 0, lastError: null, extractorBackend: 'claude',
};

const running = {
  ...idle, state: 'RUNNING',
  totalPages: 100, processedPages: 25, failedPages: 1,
  totalChunks: 800, processedChunks: 200, failedChunks: 3,
  mentionsWritten: 42, proposalsFiled: 18, elapsedMs: 12_000,
  startedAt: '2026-04-28T10:00:00Z',
};

const errored = {
  ...idle, state: 'ERROR',
  lastError: 'Anthropic API timed out after 60s',
  finishedAt: '2026-04-28T11:00:00Z',
  totalPages: 100, processedPages: 30, failedPages: 5,
};

describe('ExtractionTab', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('IDLE: shows Extract Mentions enabled and no Cancel button', async () => {
    vi.spyOn(api.knowledge, 'getExtractionStatus').mockResolvedValue(idle);
    render(<ExtractionTab />);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /Extract Mentions/i })).toBeEnabled());
    expect(screen.queryByRole('button', { name: /^Cancel$/ })).toBeNull();
  });

  it('RUNNING: shows progress, counter row, disabled trigger, visible Cancel', async () => {
    vi.spyOn(api.knowledge, 'getExtractionStatus').mockResolvedValue(running);
    render(<ExtractionTab />);
    expect(await screen.findByTestId('extraction-progress')).toBeInTheDocument();
    expect(screen.getByText(/25\/100/)).toBeInTheDocument();
    expect(screen.getByText(/200\/800/)).toBeInTheDocument();
    expect(screen.getByText(/^42$/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Running/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /^Cancel$/ })).toBeInTheDocument();
  });

  it('ERROR: surfaces lastError in the details panel', async () => {
    vi.spyOn(api.knowledge, 'getExtractionStatus').mockResolvedValue(errored);
    render(<ExtractionTab />);
    expect(await screen.findByText(/Anthropic API timed out/i)).toBeInTheDocument();
  });

  it('503 from API renders the disabled fallback', async () => {
    const err = Object.assign(new Error('disabled'), { status: 503, body: {} });
    vi.spyOn(api.knowledge, 'getExtractionStatus').mockRejectedValue(err);
    render(<ExtractionTab />);
    expect(await screen.findByText(/extraction is not configured/i)).toBeInTheDocument();
  });

  it('clicking Extract Mentions opens confirm and POSTs without force by default', async () => {
    vi.spyOn(api.knowledge, 'getExtractionStatus').mockResolvedValue(idle);
    const start = vi.spyOn(api.knowledge, 'startExtraction').mockResolvedValue(running);

    render(<ExtractionTab />);
    await waitFor(() => screen.getByRole('button', { name: /Extract Mentions/i }));
    fireEvent.click(screen.getByRole('button', { name: /Extract Mentions/i }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(/may take a long time/i)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: /Continue/i }));
    await waitFor(() => expect(start).toHaveBeenCalledWith(false));
  });

  it('Force re-extract checkbox changes confirm copy and POSTs force=true', async () => {
    vi.spyOn(api.knowledge, 'getExtractionStatus').mockResolvedValue(idle);
    const start = vi.spyOn(api.knowledge, 'startExtraction').mockResolvedValue(running);

    render(<ExtractionTab />);
    await waitFor(() => screen.getByRole('button', { name: /Extract Mentions/i }));
    fireEvent.click(screen.getByRole('checkbox', { name: /Force re-extract/i }));
    fireEvent.click(screen.getByRole('button', { name: /Extract Mentions/i }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(/already processed/i)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: /Continue/i }));
    await waitFor(() => expect(start).toHaveBeenCalledWith(true));
  });

  it('Cancel button confirms then sends DELETE; cancellation hint appears', async () => {
    vi.spyOn(api.knowledge, 'getExtractionStatus').mockResolvedValue(running);
    const cancel = vi.spyOn(api.knowledge, 'cancelExtraction').mockResolvedValue(running);

    render(<ExtractionTab />);
    await waitFor(() => screen.getByRole('button', { name: /^Cancel$/ }));
    fireEvent.click(screen.getByRole('button', { name: /^Cancel$/ }));
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: /Continue/i }));
    await waitFor(() => expect(cancel).toHaveBeenCalled());
    expect(await screen.findByText(/Cancellation requested/i)).toBeInTheDocument();
  });

  it('409 on POST surfaces "already in progress" error', async () => {
    vi.spyOn(api.knowledge, 'getExtractionStatus').mockResolvedValue(idle);
    const err = Object.assign(new Error('Conflict'),
      { status: 409, body: { ...running, message: 'in progress' } });
    vi.spyOn(api.knowledge, 'startExtraction').mockRejectedValue(err);

    render(<ExtractionTab />);
    await waitFor(() => screen.getByRole('button', { name: /Extract Mentions/i }));
    fireEvent.click(screen.getByRole('button', { name: /Extract Mentions/i }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: /Continue/i }));
    expect(await screen.findByText(/already in progress/i)).toBeInTheDocument();
  });

  it('COMPLETED with processedPages < totalPages (post-cancel shape) renders cleanly', async () => {
    vi.spyOn(api.knowledge, 'getExtractionStatus').mockResolvedValue({
      ...running, state: 'COMPLETED', processedPages: 30, totalPages: 100,
      finishedAt: '2026-04-28T10:30:00Z',
    });
    render(<ExtractionTab />);
    expect(await screen.findByText(/30\/100/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Extract Mentions/i })).toBeEnabled();
    expect(screen.queryByRole('button', { name: /^Cancel$/ })).toBeNull();
  });
});
