import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import EmbeddingActivityTab from './EmbeddingActivityTab';

vi.mock('../../api/client', () => ({
  api: { admin: { embeddings: { getEmbeddingActivity: vi.fn() } } },
}));
import { api } from '../../api/client';

const call = (overrides = {}) => ({
  seq: 1,
  startedAt: '2026-05-15T14:32:07Z',
  subsystem: 'EMBEDDING',
  backend: 'ollama',
  model: 'gemma4-assist',
  operation: 'chat',
  status: 'OK',
  durationMs: 1240,
  promptPreview: 'the chunk text',
  responsePreview: 'nodes=12 edges=5 mentions=30',
  inputTokens: null,
  outputTokens: null,
  errorMessage: null,
  ...overrides,
});

// getEmbeddingActivity resolves the already-unwrapped data object (the client strips
// the {data:...} envelope), so the mock returns that shape directly.
const payload = (calls, extra = {}) => ({
  calls, inFlight: 0, enabled: true, windowMinutes: 60, capacity: 5000, ...extra,
});

describe('EmbeddingActivityTab', () => {
  beforeEach(() => {
    api.admin.embeddings.getEmbeddingActivity.mockReset();
  });

  it('renders a row per recorded call', async () => {
    api.admin.embeddings.getEmbeddingActivity.mockResolvedValue(payload([call(), call({ seq: 2, model: 'nomic' })]));
    render(<EmbeddingActivityTab />);
    expect(await screen.findByText('gemma4-assist')).toBeInTheDocument();
    expect(screen.getByText('nomic')).toBeInTheDocument();
  });

  it('shows an error row distinctly', async () => {
    api.admin.embeddings.getEmbeddingActivity.mockResolvedValue(
      payload([call({ status: 'ERROR', errorMessage: 'timeout after 30s', responsePreview: null })]),
    );
    render(<EmbeddingActivityTab />);
    expect(await screen.findByText(/timeout after 30s/)).toBeInTheDocument();
  });

  it('shows a disabled notice when the log is disabled', async () => {
    api.admin.embeddings.getEmbeddingActivity.mockResolvedValue(payload([], { enabled: false }));
    render(<EmbeddingActivityTab />);
    expect(await screen.findByText(/wikantik\.embedding_activity\.enabled/)).toBeInTheDocument();
  });

  it('shows an empty state when there are no calls', async () => {
    api.admin.embeddings.getEmbeddingActivity.mockResolvedValue(payload([]));
    render(<EmbeddingActivityTab />);
    expect(await screen.findByText(/No embedding calls/i)).toBeInTheDocument();
  });

  it('filters table rows by status when a chip is clicked', async () => {
    api.admin.embeddings.getEmbeddingActivity.mockResolvedValue(
      payload([
        call({ seq: 1, status: 'OK', model: 'gemma4-assist' }),
        call({ seq: 2, status: 'ERROR', model: 'nomic', errorMessage: 'boom' }),
      ]),
    );
    render(<EmbeddingActivityTab />);
    expect(await screen.findByText('gemma4-assist')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Error' }));

    await waitFor(() => expect(screen.queryByText('gemma4-assist')).not.toBeInTheDocument());
    expect(screen.getByText('nomic')).toBeInTheDocument();
  });

  it('keeps header totals unfiltered while a filter is active', async () => {
    api.admin.embeddings.getEmbeddingActivity.mockResolvedValue(
      payload([
        call({ seq: 1, status: 'OK', model: 'gemma4-assist' }),
        call({ seq: 2, status: 'ERROR', model: 'nomic' }),
      ]),
    );
    render(<EmbeddingActivityTab />);
    expect(await screen.findByText('gemma4-assist')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Error' }));

    // Header still reflects the full unfiltered total of 2 calls.
    expect(screen.getByText(/2 calls/)).toBeInTheDocument();
  });

  it('summarises in-flight and error counts in the header', async () => {
    api.admin.embeddings.getEmbeddingActivity.mockResolvedValue(
      payload([call(), call({ seq: 2, status: 'ERROR', errorMessage: 'x' })], { inFlight: 3 }),
    );
    render(<EmbeddingActivityTab />);
    expect(await screen.findByText(/3 in-flight/)).toBeInTheDocument();
    expect(screen.getByText(/1 error/)).toBeInTheDocument();
  });
});
