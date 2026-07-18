import { describe, it, vi, beforeEach, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api } from '../api/client';
import DiffViewer from './DiffViewer';

vi.mock('../api/client', () => ({
  api: {
    getHistory: vi.fn(),
    getDiff: vi.fn(),
  },
}));

function renderDiffViewer(name = 'TestPage') {
  return render(
    <MemoryRouter initialEntries={[`/diff/${name}`]}>
      <Routes>
        <Route path="/diff/:name" element={<DiffViewer />} />
      </Routes>
    </MemoryRouter>,
  );
}

const VERSIONS = [
  { version: 2, author: 'alice', changeNote: 'fix typo' },
  { version: 1, author: 'bob', changeNote: 'initial' },
];

beforeEach(() => {
  vi.clearAllMocks();
});

describe('DiffViewer', () => {
  it('[#5] shows a Spinner (role=status) while loading version history', () => {
    // Never resolves — keeps the component in loading state.
    api.getHistory.mockReturnValue(new Promise(() => {}));
    renderDiffViewer();
    // The Spinner component renders role="status".
    expect(screen.getByRole('status')).toBeInTheDocument();
    // Label accessible text visible.
    expect(screen.getByText('Loading…')).toBeInTheDocument();
  });

  it('[#5] shows a Spinner while loading the diff', async () => {
    api.getHistory.mockResolvedValue({ versions: VERSIONS });
    // Diff never resolves — keeps diff in loading state.
    api.getDiff.mockReturnValue(new Promise(() => {}));
    renderDiffViewer();
    // Wait for history to resolve (spinner for history disappears).
    expect(await screen.findByText('Compare versions: TestPage')).toBeInTheDocument();
    // Now the diff spinner should be visible. findByRole (async) — the
    // diffLoading effect flushes a tick after the heading renders.
    expect(await screen.findByRole('status')).toBeInTheDocument();
  });

  it('shows an error message when history fails to load', async () => {
    api.getHistory.mockRejectedValue(new Error('network error'));
    renderDiffViewer();
    expect(await screen.findByText(/network error/)).toBeInTheDocument();
  });

  it('renders version selectors and diff when history loads successfully', async () => {
    api.getHistory.mockResolvedValue({ versions: VERSIONS });
    api.getDiff.mockResolvedValue({ diffHtml: '<p>diff content</p>' });
    renderDiffViewer();
    expect(await screen.findByText('Compare versions: TestPage')).toBeInTheDocument();
    expect(await screen.findByText(/diff content/)).toBeInTheDocument();
  });

  it('shows the single-version message when there is only one version', async () => {
    api.getHistory.mockResolvedValue({ versions: [VERSIONS[0]] });
    renderDiffViewer();
    expect(await screen.findByText(/fewer than two versions/i)).toBeInTheDocument();
  });
});
