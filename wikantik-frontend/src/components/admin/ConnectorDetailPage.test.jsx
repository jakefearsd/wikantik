import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ConnectorDetailPage from './ConnectorDetailPage';

const mockNavigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, useNavigate: () => mockNavigate };
});

vi.mock('../../api/client', () => ({
  api: {
    connectors: {
      get: vi.fn(),
      update: vi.fn(),
      remove: vi.fn(),
      sync: vi.fn(),
      runs: vi.fn(),
      pages: vi.fn(),
      testSaved: vi.fn(),
      importFromProperties: vi.fn(),
      listCredentials: vi.fn(),
      setCredential: vi.fn(),
      deleteCredential: vi.fn(),
    },
  },
}));

import { api } from '../../api/client';

const DETAIL_GITHUB = {
  id: 'gh-wikantik',
  type: 'github',
  origin: 'db',
  enabled: true,
  syncIntervalHours: 6,
  lastRun: '2026-07-14T10:00:00Z',
  lastStatus: 'success',
  pageCount: 42,
  secretsSet: ['token'],
  config: { repo: 'jakefearsd/wikantik', branch: '', path_prefix: '' },
  cluster: 'engineering',
  defaultTags: 'docs',
  pagePrefix: 'GH-',
};

const DETAIL_GDRIVE_PROPERTIES = {
  id: 'gdrive-legacy',
  type: 'gdrive',
  origin: 'properties',
  enabled: true,
  syncIntervalHours: 0,
  lastRun: null,
  lastStatus: 'never synced',
  pageCount: 5,
  secretsSet: [],
  config: { folder_ids: ['abc'], max_files: 500, export_mime: 'text/markdown', redirect_uri: '', client_id: '' },
  cluster: '',
  defaultTags: '',
  pagePrefix: '',
};

const RUNS = [
  {
    runId: 'r1', trigger: 'manual', started: '2026-07-15T08:00:00Z', finished: '2026-07-15T08:05:00Z',
    status: 'success', created: 3, updated: 1, unchanged: 10, deleted: 0, failed: 0, error: null,
  },
  {
    // Old "running" row — must render as "interrupted" (started well over 1h ago).
    runId: 'r2', trigger: 'scheduled', started: '2020-01-01T00:00:00Z', finished: null,
    status: 'running', created: 0, updated: 0, unchanged: 0, deleted: 0, failed: 0, error: null,
  },
  {
    runId: 'r3', trigger: 'manual', started: '2026-07-14T08:00:00Z', finished: '2026-07-14T08:01:00Z',
    status: 'failed', created: 0, updated: 0, unchanged: 0, deleted: 0, failed: 2, error: 'connection refused',
  },
];

function renderPage(initialPath = '/admin/connectors/gh-wikantik') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path="/admin/connectors/:id" element={<ConnectorDetailPage />} />
      </Routes>
    </MemoryRouter>
  );
}

describe('ConnectorDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockNavigate.mockReset();
    api.connectors.pages.mockResolvedValue({ pages: [] });
    api.connectors.runs.mockResolvedValue({ runs: [] });
  });

  it('renders overview with runs and interrupted render', async () => {
    api.connectors.get.mockResolvedValue(DETAIL_GITHUB);
    api.connectors.runs.mockResolvedValue({ runs: RUNS });

    renderPage();

    await waitFor(() => expect(screen.getByTestId('run-status-r2')).toHaveTextContent('interrupted'));
    expect(screen.getByTestId('run-status-r1')).toHaveTextContent('success');
    expect(screen.getByTestId('run-status-r3')).toHaveTextContent('failed');

    // Failed row's error is in an expandable <details>.
    const details = screen.getByTestId('run-error-r3');
    expect(details.tagName.toLowerCase()).toBe('details');
    expect(details).toHaveTextContent('connection refused');

    // Status strip basics
    expect(screen.getByTestId('connector-status-strip')).toHaveTextContent('42');
  });

  it('settings tab shows read-only + import for properties origin', async () => {
    api.connectors.get
      .mockResolvedValueOnce(DETAIL_GDRIVE_PROPERTIES)
      .mockResolvedValueOnce({ ...DETAIL_GDRIVE_PROPERTIES, origin: 'db' });
    api.connectors.importFromProperties.mockResolvedValue({ ok: true });

    renderPage('/admin/connectors/gdrive-legacy');
    await waitFor(() => expect(screen.getByTestId('tab-settings')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('tab-settings'));

    await waitFor(() => expect(screen.getByTestId('properties-origin-note')).toBeInTheDocument());
    expect(screen.getByTestId('properties-origin-note')).toHaveTextContent(/wikantik-custom.properties/i);
    expect(screen.getByTestId('properties-origin-note')).toHaveTextContent(/re-enter the client secret/i);
    expect(screen.getByTestId('field-folder_ids')).toBeDisabled();

    fireEvent.click(screen.getByTestId('import-to-database-button'));

    await waitFor(() => expect(api.connectors.importFromProperties).toHaveBeenCalledWith('gdrive-legacy'));
    await waitFor(() => expect(screen.queryByTestId('properties-origin-note')).not.toBeInTheDocument());
    expect(screen.getByTestId('field-folder_ids')).not.toBeDisabled();
  });

  it('settings submit passes 422 errors to fields', async () => {
    api.connectors.get.mockResolvedValue(DETAIL_GITHUB);
    api.connectors.update.mockRejectedValue(
      Object.assign(new Error('validation failed'), {
        status: 422,
        body: { errors: { repo: 'repo is required' } },
      })
    );

    renderPage();
    await waitFor(() => expect(screen.getByTestId('tab-settings')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('tab-settings'));

    await waitFor(() => expect(screen.getByTestId('settings-submit-button')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('settings-submit-button'));

    await waitFor(() => expect(screen.getByTestId('field-error-repo')).toHaveTextContent('repo is required'));
  });

  it('authorization tab saves a secret and shows set state', async () => {
    api.connectors.get.mockResolvedValue({ ...DETAIL_GITHUB, secretsSet: [] });
    api.connectors.setCredential.mockResolvedValue({ ok: true });

    renderPage();
    await waitFor(() => expect(screen.getByTestId('tab-authorization')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('tab-authorization'));

    await waitFor(() => expect(screen.getByTestId('secret-state-token')).toHaveTextContent(/not set/i));

    fireEvent.change(screen.getByTestId('secret-input-token'), { target: { value: 'ghp_abc123' } });
    fireEvent.click(screen.getByTestId('secret-save-token'));

    await waitFor(() =>
      expect(api.connectors.setCredential).toHaveBeenCalledWith('gh-wikantik', 'token', 'ghp_abc123')
    );
    await waitFor(() => expect(screen.getByTestId('secret-state-token')).toHaveTextContent(/^set$/i));
  });

  it('gdrive authorization renders authorize link with return_to', async () => {
    api.connectors.get.mockResolvedValue(DETAIL_GDRIVE_PROPERTIES);

    renderPage('/admin/connectors/gdrive-legacy');
    await waitFor(() => expect(screen.getByTestId('tab-authorization')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('tab-authorization'));

    await waitFor(() => expect(screen.getByTestId('gdrive-authorize-link')).toBeInTheDocument());
    const link = screen.getByTestId('gdrive-authorize-link');
    expect(link.tagName.toLowerCase()).toBe('a');
    expect(link.getAttribute('href')).toBe(
      '/admin/connector-oauth/gdrive/gdrive-legacy/authorize?return_to=' +
        encodeURIComponent('/admin/connectors/gdrive-legacy?oauth_return=1')
    );
    expect(screen.getByTestId('gdrive-consent-state')).toHaveTextContent(/not authorized/i);

    // oauth=ok query param on mount shows a success note.
    api.connectors.get.mockResolvedValue(DETAIL_GDRIVE_PROPERTIES);
    renderPage('/admin/connectors/gdrive-legacy?oauth_return=1&oauth=ok');
    await waitFor(() => expect(screen.getAllByTestId('oauth-result-ok')[0]).toBeInTheDocument());

    // oauth=<error code> shows an error note with the code text.
    api.connectors.get.mockResolvedValue(DETAIL_GDRIVE_PROPERTIES);
    renderPage('/admin/connectors/gdrive-legacy?oauth_return=1&oauth=store_disabled');
    await waitFor(() =>
      expect(screen.getAllByTestId('oauth-result-error')[0]).toHaveTextContent(/store_disabled/i)
    );
  });

  it('opens authorization tab when ?next=authorize', async () => {
    api.connectors.get.mockResolvedValue(DETAIL_GDRIVE_PROPERTIES);

    renderPage('/admin/connectors/gdrive-legacy?next=authorize');

    await waitFor(() => expect(screen.getByTestId('connector-tab-panel-authorization')).toBeInTheDocument());
    expect(screen.getByTestId('tab-authorization')).toHaveClass('active');
    expect(screen.getByTestId('tab-overview')).not.toHaveClass('active');
  });

  it('composes ?next=authorize with ?oauth=ok — opens tab and shows success note', async () => {
    api.connectors.get.mockResolvedValue(DETAIL_GDRIVE_PROPERTIES);

    renderPage('/admin/connectors/gdrive-legacy?next=authorize&oauth=ok');

    await waitFor(() => expect(screen.getByTestId('oauth-result-ok')).toBeInTheDocument());
    expect(screen.getByTestId('tab-authorization')).toHaveClass('active');
    expect(screen.getByTestId('connector-tab-panel-authorization')).toBeInTheDocument();
  });

  it('delete modal gates page deletion behind checkbox + typed id', async () => {
    api.connectors.get.mockResolvedValue(DETAIL_GITHUB);
    api.connectors.pages.mockResolvedValue({ pages: [{ pageName: 'GH-readme', sourceUri: 'x', lastSynced: null }] });
    api.connectors.remove.mockResolvedValue({ deleted: true });

    renderPage();
    await waitFor(() => expect(screen.getByTestId('delete-connector-button')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('delete-connector-button'));

    await waitFor(() => expect(screen.getByTestId('delete-pages-checkbox')).toBeInTheDocument());
    const confirmButton = screen.getByTestId('delete-confirm-button');
    // Unchecked: deleting the connector alone requires no typed confirmation.
    expect(confirmButton).not.toBeDisabled();

    fireEvent.click(screen.getByTestId('delete-pages-checkbox'));
    expect(confirmButton).toBeDisabled();

    fireEvent.change(screen.getByTestId('delete-confirm-input'), { target: { value: 'wrong-id' } });
    expect(confirmButton).toBeDisabled();

    fireEvent.change(screen.getByTestId('delete-confirm-input'), { target: { value: 'gh-wikantik' } });
    expect(confirmButton).not.toBeDisabled();

    fireEvent.click(confirmButton);

    await waitFor(() => expect(api.connectors.remove).toHaveBeenCalledWith('gh-wikantik', true));
    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/admin/connectors'));
  });
});
