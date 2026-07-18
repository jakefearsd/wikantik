import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import AdminContentPage from './AdminContentPage';

// ---------------------------------------------------------------------------
// Mock api/client. The page composes several sub-tabs; IndexStatusTab and
// ChunkInspectorTab are rendered lazily (only when their tab is active) so we
// mock the broad surface but never click into them here.
// ---------------------------------------------------------------------------

vi.mock('../../api/client', () => ({
  api: {
    admin: {
      getContentStats: vi.fn(),
      flushCache: vi.fn(),
      getOrphanedPages: vi.fn(),
      getBrokenLinks: vi.fn(),
      purgeVersions: vi.fn(),
      bulkDeletePages: vi.fn(),
    },
    getHistory: vi.fn(),
  },
}));

import { api } from '../../api/client';

const STATS = {
  pageCount: 120,
  orphanedCount: 3,
  brokenLinkCount: 2,
  caches: [
    { fullName: 'render.full', name: 'render', size: 50, maxSize: 1000, hits: 1234, misses: 56, hitRatio: 95 },
    { fullName: 'agent.full', name: 'agent', size: 5, maxSize: 100, hits: 10, misses: 90, hitRatio: 10 },
  ],
};

function renderPage() {
  return render(<MemoryRouter><AdminContentPage /></MemoryRouter>);
}

beforeEach(() => {
  vi.clearAllMocks();
  api.admin.getContentStats.mockResolvedValue(STATS);
  api.admin.flushCache.mockResolvedValue({ entriesRemoved: 42 });
  api.admin.getOrphanedPages.mockResolvedValue({ pages: ['Lonely', 'Abandoned'] });
  api.admin.getBrokenLinks.mockResolvedValue({
    links: [{ target: 'Missing', referencedBy: ['Home', 'Sandbox'], referrerCount: 2 }],
  });
  api.admin.purgeVersions.mockResolvedValue({ purged: 4, remaining: 3 });
  api.admin.bulkDeletePages.mockResolvedValue({ deleted: ['Lonely', 'Abandoned'], failed: [] });
  api.getHistory.mockResolvedValue({
    versions: [
      { version: 5, author: 'alice', lastModified: '2024-01-05', changeNote: 'fix' },
      { version: 4, author: 'bob', lastModified: '2024-01-04', changeNote: '' },
      { version: 3, author: null, lastModified: '2024-01-03', changeNote: 'init' },
      { version: 2, author: 'alice', lastModified: '2024-01-02', changeNote: '' },
      { version: 1, author: 'alice', lastModified: '2024-01-01', changeNote: 'create' },
    ],
  });
});

// ---------------------------------------------------------------------------
// Dashboard tab
// ---------------------------------------------------------------------------

describe('AdminContentPage — Dashboard', () => {
  it('shows a loading state before stats arrive', () => {
    let resolve;
    api.admin.getContentStats.mockReturnValue(new Promise(r => { resolve = r; }));
    renderPage();
    expect(screen.getByText('Loading stats…')).toBeTruthy();
    resolve(STATS);
  });

  it('renders content stats and cache table after load', async () => {
    renderPage();
    await screen.findByText('120');
    expect(screen.getByText('Total Pages')).toBeTruthy();
    // orphaned + broken counts shown in stat cards
    expect(screen.getByText('3')).toBeTruthy();
    expect(screen.getByText('2')).toBeTruthy();
    // cache rows
    expect(screen.getByText('render')).toBeTruthy();
    expect(screen.getByText('95%')).toBeTruthy();
    expect(screen.getByText('1,234')).toBeTruthy();
    expect(screen.getByText('10%')).toBeTruthy();
    expect(api.admin.getContentStats).toHaveBeenCalledTimes(1);
  });

  it('shows an error message when stats fail to load', async () => {
    api.admin.getContentStats.mockRejectedValueOnce(new Error('stats boom'));
    renderPage();
    await screen.findByText('stats boom');
  });

  it('flushes caches, shows the entries-removed message, and reloads stats', async () => {
    renderPage();
    await screen.findByText('120');

    fireEvent.click(screen.getByRole('button', { name: 'Flush All Caches' }));

    await screen.findByText('Caches flushed: 42 entries removed');
    expect(api.admin.flushCache).toHaveBeenCalledTimes(1);
    // load() runs again after flush
    await waitFor(() => expect(api.admin.getContentStats).toHaveBeenCalledTimes(2));
  });

  it('surfaces a flush error', async () => {
    api.admin.flushCache.mockRejectedValueOnce(new Error('flush failed'));
    renderPage();
    await screen.findByText('120');
    fireEvent.click(screen.getByRole('button', { name: 'Flush All Caches' }));
    await screen.findByText('flush failed');
  });

  it('omits the cache table when no caches are reported', async () => {
    api.admin.getContentStats.mockResolvedValueOnce({ pageCount: 1, orphanedCount: 0, brokenLinkCount: 0, caches: [] });
    renderPage();
    await screen.findByText('Total Pages');
    expect(screen.queryByText('Cache Statistics')).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// Orphaned Pages tab
// ---------------------------------------------------------------------------

describe('AdminContentPage — Orphaned Pages', () => {
  const openTab = async () => {
    renderPage();
    await screen.findByText('120');
    fireEvent.click(screen.getByRole('button', { name: 'Orphaned Pages' }));
    await waitFor(() => expect(api.admin.getOrphanedPages).toHaveBeenCalled());
  };

  it('lists orphaned pages', async () => {
    await openTab();
    await screen.findByText('Lonely');
    expect(screen.getByText('Abandoned')).toBeTruthy();
    expect(screen.getByText('2 orphaned pages')).toBeTruthy();
  });

  it('shows an empty state when there are no orphaned pages', async () => {
    api.admin.getOrphanedPages.mockResolvedValueOnce({ pages: [] });
    await openTab();
    await screen.findByText(/No orphaned pages found/);
    expect(screen.getByText('0 orphaned pages')).toBeTruthy();
  });

  it('bulk-deletes selected pages and reports success', async () => {
    await openTab();
    await screen.findByText('Lonely');

    // Row checkboxes: index 0 is the header select-all
    const boxes = screen.getAllByRole('checkbox');
    fireEvent.click(boxes[1]);
    fireEvent.click(boxes[2]);

    fireEvent.click(screen.getByRole('button', { name: 'Delete 2 Selected' }));

    await waitFor(() => {
      expect(api.admin.bulkDeletePages).toHaveBeenCalledWith(['Lonely', 'Abandoned']);
    });
    await screen.findByText('Deleted 2 pages');
    // reload after delete
    await waitFor(() => expect(api.admin.getOrphanedPages).toHaveBeenCalledTimes(2));
  });

  it('select-all toggles every row', async () => {
    await openTab();
    await screen.findByText('Lonely');
    const boxes = screen.getAllByRole('checkbox');
    fireEvent.click(boxes[0]); // header select-all
    await screen.findByText('Delete 2 Selected');
  });

  it('reports a partial-failure count in the success message', async () => {
    api.admin.bulkDeletePages.mockResolvedValueOnce({ deleted: ['Lonely'], failed: ['Abandoned'] });
    await openTab();
    await screen.findByText('Lonely');
    const boxes = screen.getAllByRole('checkbox');
    fireEvent.click(boxes[1]);
    fireEvent.click(boxes[2]);
    fireEvent.click(screen.getByRole('button', { name: 'Delete 2 Selected' }));
    await screen.findByText('Deleted 1 pages, 1 failed');
  });

  it('shows an error if bulk delete throws', async () => {
    api.admin.bulkDeletePages.mockRejectedValueOnce(new Error('delete boom'));
    await openTab();
    await screen.findByText('Lonely');
    const boxes = screen.getAllByRole('checkbox');
    fireEvent.click(boxes[1]);
    fireEvent.click(screen.getByRole('button', { name: 'Delete 1 Selected' }));
    await screen.findByText('delete boom');
  });
});

// ---------------------------------------------------------------------------
// Broken Links tab
// ---------------------------------------------------------------------------

describe('AdminContentPage — Broken Links', () => {
  const openTab = async () => {
    renderPage();
    await screen.findByText('120');
    fireEvent.click(screen.getByRole('button', { name: 'Broken Links' }));
    await waitFor(() => expect(api.admin.getBrokenLinks).toHaveBeenCalled());
  };

  it('lists broken links with their referrers', async () => {
    await openTab();
    await screen.findByText('Missing');
    expect(screen.getByText('Home')).toBeTruthy();
    expect(screen.getByText('Sandbox')).toBeTruthy();
    expect(screen.getByText('1 broken link')).toBeTruthy();
  });

  it('shows an empty state when there are no broken links', async () => {
    api.admin.getBrokenLinks.mockResolvedValueOnce({ links: [] });
    await openTab();
    await screen.findByText(/No broken links found/);
    expect(screen.getByText('0 broken links')).toBeTruthy();
  });
});

// ---------------------------------------------------------------------------
// Versions tab
// ---------------------------------------------------------------------------

describe('AdminContentPage — Versions', () => {
  const openTab = async () => {
    renderPage();
    await screen.findByText('120');
    fireEvent.click(screen.getByRole('button', { name: 'Versions' }));
    return screen.findByPlaceholderText('Enter page name…');
  };

  it('looks up history and renders the version table', async () => {
    const input = await openTab();
    fireEvent.change(input, { target: { value: 'Home' } });
    fireEvent.click(screen.getByRole('button', { name: 'Look Up' }));

    await waitFor(() => expect(api.getHistory).toHaveBeenCalledWith('Home'));
    await screen.findAllByText('alice');
    expect(screen.getByText('bob')).toBeTruthy();
    // version numbers render in the table
    expect(screen.getByText('fix')).toBeTruthy();
  });

  it('shows an error when history lookup fails', async () => {
    api.getHistory.mockRejectedValueOnce(new Error('no such page'));
    const input = await openTab();
    fireEvent.change(input, { target: { value: 'Nope' } });
    fireEvent.click(screen.getByRole('button', { name: 'Look Up' }));
    await screen.findByText(/Could not load history: no such page/);
  });

  it('purges old versions and reports the result', async () => {
    const input = await openTab();
    fireEvent.change(input, { target: { value: 'Home' } });
    fireEvent.click(screen.getByRole('button', { name: 'Look Up' }));
    await screen.findAllByText('alice');

    // keepLatest defaults to 3, 5 versions -> purge button enabled
    fireEvent.click(screen.getByRole('button', { name: 'Purge Old Versions' }));

    await waitFor(() => expect(api.admin.purgeVersions).toHaveBeenCalledWith('Home', 3));
    // handlePurge re-runs loadVersions, which clears the transient success
    // message, so assert on the reload rather than the flash.
    await waitFor(() => expect(api.getHistory).toHaveBeenCalledTimes(2));
  });

  it('does not call the API for a blank page name', async () => {
    await openTab();
    fireEvent.click(screen.getByRole('button', { name: 'Look Up' }));
    await waitFor(() => {});
    expect(api.getHistory).not.toHaveBeenCalled();
  });
});

// ---------------------------------------------------------------------------
// Tab navigation
// ---------------------------------------------------------------------------

describe('AdminContentPage — tabs', () => {
  it('renders all tab buttons and switches the active class', async () => {
    renderPage();
    await screen.findByText('120');
    for (const t of ['Dashboard', 'Orphaned Pages', 'Broken Links', 'Versions', 'Chunk Inspector', 'Index Status']) {
      expect(screen.getByRole('button', { name: t })).toBeTruthy();
    }
    const orphans = screen.getByRole('button', { name: 'Orphaned Pages' });
    fireEvent.click(orphans);
    expect(orphans.className).toContain('active');
  });
});
