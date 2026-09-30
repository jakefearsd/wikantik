import { describe, it, vi, beforeEach, expect } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { api } from '../api/client';
import DiffViewer from './DiffViewer';

vi.mock('../api/client', () => ({
  api: {
    getHistory: vi.fn(),
    getDiff: vi.fn(),
    getPage: vi.fn(),
  },
}));

function EditProbe() {
  const loc = useLocation();
  return <div data-testid="edit-probe">{JSON.stringify(loc.state)}</div>;
}

function renderDiffViewer(name = 'TestPage') {
  return render(
    <MemoryRouter initialEntries={[`/diff/${name}`]}>
      <Routes>
        <Route path="/diff/:name" element={<DiffViewer />} />
        <Route path="/edit/:name" element={<EditProbe />} />
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
  api.getPage.mockResolvedValue({ permissions: { edit: false } });
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

  it('hides a previously-shown diff once the same version is selected on both sides', async () => {
    api.getHistory.mockResolvedValue({ versions: VERSIONS });
    api.getDiff.mockResolvedValue({ diffHtml: '<p>diff content</p>' });
    renderDiffViewer();
    expect(await screen.findByText(/diff content/)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/To version/i), { target: { value: '1' } });
    expect(screen.queryByText(/diff content/)).not.toBeInTheDocument();
    expect(screen.getByText(/Select two different versions/i)).toBeInTheDocument();
  });
});

describe('DiffViewer restore link', () => {
  const V3 = [{ version: 3 }, { version: 2 }, { version: 1 }];

  beforeEach(() => {
    api.getHistory.mockResolvedValue({ versions: V3 });
    api.getDiff.mockResolvedValue({ diffHtml: '<p>d</p>' });
  });

  it('offers Restore of the From version to editors, carrying route state', async () => {
    api.getPage.mockResolvedValue({ permissions: { edit: true } });
    renderDiffViewer();
    const link = await screen.findByText('Restore version 1');
    expect(link).toHaveAttribute('href', '/edit/TestPage');
    fireEvent.click(link);
    expect(JSON.parse((await screen.findByTestId('edit-probe')).textContent)).toEqual({ restoreVersion: 1 });
  });

  it('hides Restore when From is the current version', async () => {
    api.getPage.mockResolvedValue({ permissions: { edit: true } });
    renderDiffViewer();
    await screen.findByText('Restore version 1');
    fireEvent.change(screen.getByLabelText(/From version/i), { target: { value: '3' } });
    expect(screen.queryByTestId('diff-restore')).not.toBeInTheDocument();
  });

  it('never shows Restore without edit permission', async () => {
    api.getPage.mockResolvedValue({ permissions: { edit: false } });
    renderDiffViewer();
    await screen.findByText('Compare versions: TestPage');
    await waitFor(() => expect(api.getPage).toHaveBeenCalled());
    expect(screen.queryByTestId('diff-restore')).not.toBeInTheDocument();
  });

  it('shows no link and warns when the page lookup fails', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.getPage.mockRejectedValue(new Error('boom'));
    renderDiffViewer();
    await screen.findByText('Compare versions: TestPage');
    await waitFor(() => expect(warn).toHaveBeenCalled());
    expect(screen.queryByTestId('diff-restore')).not.toBeInTheDocument();
    warn.mockRestore();
  });
});
