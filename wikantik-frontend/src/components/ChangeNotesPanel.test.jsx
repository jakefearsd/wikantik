import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';

vi.mock('../api/client', () => ({
  api: { getHistory: vi.fn() },
}));

import ChangeNotesPanel from './ChangeNotesPanel';
import { api } from '../api/client';

const renderPanel = (pageName = 'SomePage') =>
  render(
    <MemoryRouter>
      <ChangeNotesPanel pageName={pageName} />
    </MemoryRouter>
  );

beforeEach(() => {
  vi.clearAllMocks();
});

describe('ChangeNotesPanel', () => {
  it('renders collapsed by default without calling the API', () => {
    renderPanel();
    expect(screen.getByText(/Change Notes/)).toBeInTheDocument();
    expect(screen.getByText(/▸/)).toBeInTheDocument();
    expect(api.getHistory).not.toHaveBeenCalled();
  });

  it('fetches and shows history on first expand, and flips the chevron', async () => {
    api.getHistory.mockResolvedValue({
      versions: [
        { version: 1, author: 'alice', lastModified: '2026-01-02T00:00:00Z', changeNote: 'Initial' },
      ],
    });
    renderPanel();

    fireEvent.click(screen.getByText(/Change Notes/));

    await waitFor(() => expect(api.getHistory).toHaveBeenCalledWith('SomePage'));
    expect(await screen.findByText('alice')).toBeInTheDocument();
    expect(screen.getByText('Initial')).toBeInTheDocument();
    expect(screen.getByText(/▾/)).toBeInTheDocument();
  });

  it('shows a placeholder for missing author/note and formats the date', async () => {
    api.getHistory.mockResolvedValue({
      versions: [{ version: 2, author: null, lastModified: null, changeNote: null }],
    });
    renderPanel();
    fireEvent.click(screen.getByText(/Change Notes/));

    expect(await screen.findByText('v2')).toBeInTheDocument();
    const dashes = screen.getAllByText('—');
    expect(dashes.length).toBeGreaterThanOrEqual(3); // author, date, note
  });

  it('shows "No history available." when there are no versions', async () => {
    api.getHistory.mockResolvedValue({ versions: [] });
    renderPanel();
    fireEvent.click(screen.getByText(/Change Notes/));
    expect(await screen.findByText('No history available.')).toBeInTheDocument();
  });

  it('shows a "Compare versions" link only with 2+ versions', async () => {
    api.getHistory.mockResolvedValue({
      versions: [
        { version: 1, author: 'a', lastModified: '2026-01-01T00:00:00Z', changeNote: 'one' },
        { version: 2, author: 'b', lastModified: '2026-01-02T00:00:00Z', changeNote: 'two' },
      ],
    });
    renderPanel('DiffPage');
    fireEvent.click(screen.getByText(/Change Notes/));

    const link = await screen.findByRole('link', { name: /Compare versions/ });
    expect(link).toHaveAttribute('href', '/diff/DiffPage');
  });

  it('hides the "Compare versions" link with a single version', async () => {
    api.getHistory.mockResolvedValue({
      versions: [{ version: 1, author: 'a', lastModified: '2026-01-01T00:00:00Z', changeNote: 'one' }],
    });
    renderPanel();
    fireEvent.click(screen.getByText(/Change Notes/));
    await screen.findByText('v1');
    expect(screen.queryByRole('link', { name: /Compare versions/ })).not.toBeInTheDocument();
  });

  it('surfaces a load error', async () => {
    api.getHistory.mockRejectedValue(new Error('history unavailable'));
    renderPanel();
    fireEvent.click(screen.getByText(/Change Notes/));
    expect(await screen.findByText('history unavailable')).toBeInTheDocument();
  });

  it('does not re-fetch on a second expand once versions are cached', async () => {
    api.getHistory.mockResolvedValue({ versions: [] });
    renderPanel();

    fireEvent.click(screen.getByText(/Change Notes/)); // expand -> fetch
    await waitFor(() => expect(api.getHistory).toHaveBeenCalledTimes(1));

    fireEvent.click(screen.getByText(/Change Notes/)); // collapse
    fireEvent.click(screen.getByText(/Change Notes/)); // expand again

    expect(api.getHistory).toHaveBeenCalledTimes(1);
  });
});

describe('ChangeNotesPanel restore links', () => {
  const HISTORY = { versions: [{ version: 3 }, { version: 2 }, { version: 1 }] };

  function Probe() {
    const loc = useLocation();
    return <div data-testid="probe">{JSON.stringify(loc.state)}</div>;
  }

  function renderWith(canEdit) {
    return render(
      <MemoryRouter initialEntries={['/wiki/SomePage']}>
        <Routes>
          <Route path="/wiki/:name" element={<ChangeNotesPanel pageName="SomePage" canEdit={canEdit} />} />
          <Route path="/edit/:name" element={<Probe />} />
        </Routes>
      </MemoryRouter>
    );
  }

  it('offers Restore on older versions only, linking to the editor', async () => {
    api.getHistory.mockResolvedValue(HISTORY);
    renderWith(true);
    fireEvent.click(screen.getByText(/Change Notes/));
    expect(await screen.findByTestId('restore-v2')).toHaveAttribute('href', '/edit/SomePage');
    expect(screen.getByTestId('restore-v1')).toHaveAttribute('href', '/edit/SomePage');
    expect(screen.queryByTestId('restore-v3')).not.toBeInTheDocument();
  });

  it('passes the version to the editor as route state', async () => {
    api.getHistory.mockResolvedValue(HISTORY);
    renderWith(true);
    fireEvent.click(screen.getByText(/Change Notes/));
    fireEvent.click(await screen.findByTestId('restore-v2'));
    expect(JSON.parse((await screen.findByTestId('probe')).textContent)).toEqual({ restoreVersion: 2 });
  });

  it('renders no Restore links without edit permission', async () => {
    api.getHistory.mockResolvedValue(HISTORY);
    renderWith(false);
    fireEvent.click(screen.getByText(/Change Notes/));
    await screen.findByText('v2');
    expect(screen.queryByTestId('restore-v2')).not.toBeInTheDocument();
    expect(screen.queryByTestId('restore-v1')).not.toBeInTheDocument();
  });

  const openWith = async (versions) => {
    api.getHistory.mockResolvedValue({ versions });
    renderPanel();
    fireEvent.click(screen.getByText(/Change Notes/));
    await screen.findByText('v1');
  };

  it('aVersionWithContributorsNamesThem', async () => {
    await openWith([{ version: 1, author: 'Ana', contributors: ['Ana', 'Ben', 'Cy'], changeNote: 'n' }]);
    expect(screen.getByText(/Ana with Ben and Cy/)).toBeInTheDocument();
  });

  it('theAuthorIsNotRepeated', async () => {
    await openWith([{ version: 1, author: 'Ana', contributors: ['Ana', 'Ben'], changeNote: 'n' }]);
    expect(screen.getByText(/Ana with Ben/)).toBeInTheDocument();
    expect(screen.queryByText(/Ben and Ana|Ana and Ana|Ana, Ana/)).toBeNull();
  });

  it('noContributorsRendersAsToday', async () => {
    await openWith([
      { version: 1, author: 'Ana', changeNote: 'n' },
      { version: 2, author: 'Ben', contributors: [], changeNote: 'n' },
      { version: 3, author: 'Cy', contributors: ['Cy'], changeNote: 'n' },
    ]);
    expect(screen.getByText('Ana')).toBeInTheDocument();
    expect(screen.getByText('Ben')).toBeInTheDocument();
    expect(screen.getByText('Cy')).toBeInTheDocument();
    expect(screen.queryByText(/ with /)).toBeNull();
  });
});
