import { render, screen, waitFor } from '@testing-library/react';
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
    await waitFor(() => expect(screen.getByText('gemma4-assist')).toBeInTheDocument());
    expect(screen.getByText('nomic')).toBeInTheDocument();
  });

  it('shows an error row distinctly', async () => {
    api.knowledge.getLlmActivity.mockResolvedValue(
      payload([call({ status: 'ERROR', errorMessage: 'timeout after 30s', responsePreview: null })]),
    );
    render(<LlmActivityTab />);
    await waitFor(() => expect(screen.getByText(/timeout after 30s/)).toBeInTheDocument());
  });

  it('shows a disabled notice when the log is disabled', async () => {
    api.knowledge.getLlmActivity.mockResolvedValue(payload([], { enabled: false }));
    render(<LlmActivityTab />);
    await waitFor(() =>
      expect(screen.getByText(/wikantik\.llm_activity\.enabled/)).toBeInTheDocument(),
    );
  });

  it('shows an empty state when there are no calls', async () => {
    api.knowledge.getLlmActivity.mockResolvedValue(payload([]));
    render(<LlmActivityTab />);
    await waitFor(() => expect(screen.getByText(/No LLM calls/i)).toBeInTheDocument());
  });

  it('summarises in-flight and error counts in the header', async () => {
    api.knowledge.getLlmActivity.mockResolvedValue(
      payload([call(), call({ seq: 2, status: 'ERROR', errorMessage: 'x' })], { inFlight: 3 }),
    );
    render(<LlmActivityTab />);
    await waitFor(() => expect(screen.getByText(/3 in-flight/)).toBeInTheDocument());
    expect(screen.getByText(/1 error/)).toBeInTheDocument();
  });
});
