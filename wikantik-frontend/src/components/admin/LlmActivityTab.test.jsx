import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import LlmActivityTab from './LlmActivityTab';

vi.mock('../../api/client', () => ({
  api: { knowledge: { getLlmActivity: vi.fn() } },
}));
import { api } from '../../api/client';

const call = (overrides = {}) => ({
  seq: 1,
  startedAt: '2026-05-15T14:32:07Z',
  subsystem: 'ENTITY_EXTRACTION',
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

// getLlmActivity resolves the already-unwrapped data object (the client strips
// the {data:...} envelope), so the mock returns that shape directly.
const payload = (calls, extra = {}) => ({
  calls, inFlight: 0, enabled: true, windowMinutes: 60, capacity: 5000, ...extra,
});

describe('LlmActivityTab', () => {
  beforeEach(() => {
    api.knowledge.getLlmActivity.mockReset();
  });

  it('renders a row per recorded call', async () => {
    api.knowledge.getLlmActivity.mockResolvedValue(payload([call(), call({ seq: 2, model: 'nomic' })]));
    render(<LlmActivityTab />);
    expect(await screen.findByText('gemma4-assist')).toBeInTheDocument();
    expect(screen.getByText('nomic')).toBeInTheDocument();
  });

  it('shows an error row distinctly', async () => {
    api.knowledge.getLlmActivity.mockResolvedValue(
      payload([call({ status: 'ERROR', errorMessage: 'timeout after 30s', responsePreview: null })]),
    );
    render(<LlmActivityTab />);
    expect(await screen.findByText(/timeout after 30s/)).toBeInTheDocument();
  });

  it('shows a disabled notice when the log is disabled', async () => {
    api.knowledge.getLlmActivity.mockResolvedValue(payload([], { enabled: false }));
    render(<LlmActivityTab />);
    expect(await screen.findByText(/wikantik\.llm_activity\.enabled/)).toBeInTheDocument();
  });

  it('shows an empty state when there are no calls', async () => {
    api.knowledge.getLlmActivity.mockResolvedValue(payload([]));
    render(<LlmActivityTab />);
    expect(await screen.findByText(/No LLM calls/i)).toBeInTheDocument();
  });

  it('filters table rows by subsystem when a chip is clicked', async () => {
    api.knowledge.getLlmActivity.mockResolvedValue(
      payload([
        call({ seq: 1, subsystem: 'ENTITY_EXTRACTION', model: 'gemma4-assist' }),
        call({ seq: 2, subsystem: 'EMBEDDING', model: 'nomic' }),
      ]),
    );
    render(<LlmActivityTab />);
    expect(await screen.findByText('gemma4-assist')).toBeInTheDocument();
    expect(screen.getByText('nomic')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Embedding' }));

    await waitFor(() => expect(screen.queryByText('gemma4-assist')).not.toBeInTheDocument());
    expect(screen.getByText('nomic')).toBeInTheDocument();
  });

  it('renders chips + labeled rows for the SECTION_RERANK and QUERY_DECOMPOSITION subsystems and filters by them', async () => {
    api.knowledge.getLlmActivity.mockResolvedValue(
      payload([
        call({ seq: 1, subsystem: 'SECTION_RERANK', model: 'rerank-model' }),
        call({ seq: 2, subsystem: 'QUERY_DECOMPOSITION', model: 'decomp-model' }),
      ]),
    );
    render(<LlmActivityTab />);
    expect(await screen.findByText('rerank-model')).toBeInTheDocument();
    // Rows carry the short subsystem labels, not the raw enum names.
    expect(screen.getByText('rerank')).toBeInTheDocument();
    expect(screen.getByText('decomposition')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Rerank' }));
    await waitFor(() => expect(screen.queryByText('decomp-model')).not.toBeInTheDocument());
    expect(screen.getByText('rerank-model')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Decomposition' }));
    await waitFor(() => expect(screen.queryByText('rerank-model')).not.toBeInTheDocument());
    expect(screen.getByText('decomp-model')).toBeInTheDocument();
  });

  it('filters table rows by status when a chip is clicked', async () => {
    api.knowledge.getLlmActivity.mockResolvedValue(
      payload([
        call({ seq: 1, status: 'OK', model: 'gemma4-assist' }),
        call({ seq: 2, status: 'ERROR', model: 'nomic', errorMessage: 'boom' }),
      ]),
    );
    render(<LlmActivityTab />);
    expect(await screen.findByText('gemma4-assist')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Error' }));

    await waitFor(() => expect(screen.queryByText('gemma4-assist')).not.toBeInTheDocument());
    expect(screen.getByText('nomic')).toBeInTheDocument();
  });

  it('keeps header totals unfiltered while a filter is active', async () => {
    api.knowledge.getLlmActivity.mockResolvedValue(
      payload([
        call({ seq: 1, subsystem: 'ENTITY_EXTRACTION', model: 'gemma4-assist' }),
        call({ seq: 2, subsystem: 'EMBEDDING', model: 'nomic' }),
      ]),
    );
    render(<LlmActivityTab />);
    expect(await screen.findByText('gemma4-assist')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Embedding' }));

    // Header still reflects the full unfiltered total of 2 calls.
    expect(screen.getByText(/2 calls/)).toBeInTheDocument();
  });

  it('summarises in-flight and error counts in the header', async () => {
    api.knowledge.getLlmActivity.mockResolvedValue(
      payload([call(), call({ seq: 2, status: 'ERROR', errorMessage: 'x' })], { inFlight: 3 }),
    );
    render(<LlmActivityTab />);
    expect(await screen.findByText(/3 in-flight/)).toBeInTheDocument();
    expect(screen.getByText(/1 error/)).toBeInTheDocument();
  });
});
