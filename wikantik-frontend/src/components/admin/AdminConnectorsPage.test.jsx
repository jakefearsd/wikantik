import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import AdminConnectorsPage from './AdminConnectorsPage';

const mockNavigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, useNavigate: () => mockNavigate };
});

vi.mock('../../api/client', () => ({
  api: {
    connectors: {
      list: vi.fn(),
      sync: vi.fn(),
      importFromProperties: vi.fn(),
    },
  },
}));

import { api } from '../../api/client';

function renderPage() {
  return render(
    <MemoryRouter>
      <AdminConnectorsPage />
    </MemoryRouter>
  );
}

const DB_CONNECTOR = {
  id: 'gh-wikantik',
  type: 'github',
  origin: 'db',
  enabled: true,
  syncIntervalHours: 6,
  lastRun: '2026-07-14T10:00:00Z',
  lastStatus: 'success',
  pageCount: 42,
  secretsSet: ['token'],
};

const PROPERTIES_CONNECTOR = {
  id: 'gdrive-legacy',
  type: 'gdrive',
  origin: 'properties',
  enabled: true,
  syncIntervalHours: 0,
  lastRun: null,
  lastStatus: 'never synced',
  pageCount: 5,
  secretsSet: [],
};

describe('AdminConnectorsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockNavigate.mockReset();
  });

  it('renders connector rows with origin chip and sync button', async () => {
    api.connectors.list.mockResolvedValue({
      syncingEnabled: true,
      credentialStoreEnabled: true,
      connectors: [DB_CONNECTOR, PROPERTIES_CONNECTOR],
    });

    renderPage();

    expect(await screen.findByText('gh-wikantik')).toBeInTheDocument();

    const dbRow = screen.getByText('gh-wikantik').closest('tr');
    expect(within(dbRow).getByText('database')).toBeInTheDocument();
    expect(within(dbRow).getByText(/🐙/)).toBeInTheDocument();
    expect(within(dbRow).getByTestId('sync-gh-wikantik')).toBeInTheDocument();

    const propsRow = screen.getByText('gdrive-legacy').closest('tr');
    expect(within(propsRow).getByText('config file')).toBeInTheDocument();
    expect(within(propsRow).getByText('manual')).toBeInTheDocument();
    expect(within(propsRow).getByText('—')).toBeInTheDocument();
    expect(within(propsRow).getByTestId('sync-gdrive-legacy')).toBeInTheDocument();
  });

  it('shows kill-switch banner when syncingEnabled false', async () => {
    api.connectors.list.mockResolvedValue({
      syncingEnabled: false,
      credentialStoreEnabled: true,
      connectors: [DB_CONNECTOR],
    });

    renderPage();

    expect(await screen.findByTestId('connectors-disabled-banner')).toBeInTheDocument();
    expect(screen.getByTestId('connectors-disabled-banner')).toHaveTextContent(/disabled by the operator/i);
    expect(screen.queryByTestId('credstore-disabled-banner')).not.toBeInTheDocument();
  });

  it('shows credential-store banner with openssl command when store disabled', async () => {
    api.connectors.list.mockResolvedValue({
      syncingEnabled: true,
      credentialStoreEnabled: false,
      connectors: [DB_CONNECTOR],
    });

    renderPage();

    expect(await screen.findByTestId('credstore-disabled-banner')).toBeInTheDocument();
    const banner = screen.getByTestId('credstore-disabled-banner');
    expect(banner).toHaveTextContent(/GitHub \/ Confluence \/ Google Drive/i);
    const codeEl = within(banner).getByText('openssl rand -base64 32');
    expect(codeEl.tagName).toBe('CODE');
  });

  it('shows empty state with Add Connector', async () => {
    api.connectors.list.mockResolvedValue({
      syncingEnabled: true,
      credentialStoreEnabled: true,
      connectors: [],
    });

    renderPage();

    expect(await screen.findByTestId('connectors-empty-state')).toBeInTheDocument();
    expect(screen.getByText(/Connectors sync external sources/i)).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('add-connector-button-empty'));
    expect(mockNavigate).toHaveBeenCalledWith('/admin/connectors/new');
  });

  it('sync button posts and reloads list', async () => {
    api.connectors.list.mockResolvedValue({
      syncingEnabled: true,
      credentialStoreEnabled: true,
      connectors: [DB_CONNECTOR],
    });
    api.connectors.sync.mockResolvedValue({ status: 'started' });

    renderPage();

    expect(await screen.findByTestId('sync-gh-wikantik')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('sync-gh-wikantik'));

    await waitFor(() => expect(api.connectors.sync).toHaveBeenCalledWith('gh-wikantik'));
    await waitFor(() => expect(api.connectors.list).toHaveBeenCalledTimes(2));
  });

  it('surfaces a 409 sync conflict as an inline row message and re-enables the button', async () => {
    api.connectors.list.mockResolvedValue({
      syncingEnabled: true,
      credentialStoreEnabled: true,
      connectors: [DB_CONNECTOR],
    });
    api.connectors.sync.mockRejectedValue(
      Object.assign(new Error('sync already running'), { status: 409 })
    );

    renderPage();

    expect(await screen.findByTestId('sync-gh-wikantik')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('sync-gh-wikantik'));

    await waitFor(() =>
      expect(screen.getByTestId('sync-message-gh-wikantik')).toHaveTextContent(/sync already running/i)
    );
    expect(screen.getByTestId('sync-gh-wikantik')).not.toBeDisabled();
    // Only one list() call — the failed sync did not trigger a reload.
    expect(api.connectors.list).toHaveBeenCalledTimes(1);
  });

  it('import button shows only for properties origin and calls import', async () => {
    api.connectors.list.mockResolvedValue({
      syncingEnabled: true,
      credentialStoreEnabled: true,
      connectors: [DB_CONNECTOR, PROPERTIES_CONNECTOR],
    });
    api.connectors.importFromProperties.mockResolvedValue({ ok: true });

    renderPage();

    expect(await screen.findByText('gh-wikantik')).toBeInTheDocument();

    const dbRow = screen.getByText('gh-wikantik').closest('tr');
    expect(within(dbRow).queryByTestId('import-gh-wikantik')).not.toBeInTheDocument();

    const propsRow = screen.getByText('gdrive-legacy').closest('tr');
    expect(within(propsRow).getByTestId('import-gdrive-legacy')).toBeInTheDocument();
    expect(within(propsRow).getByText('config file')).toHaveAttribute(
      'title',
      'Defined in wikantik-custom.properties'
    );

    fireEvent.click(within(propsRow).getByTestId('import-gdrive-legacy'));

    await waitFor(() => expect(api.connectors.importFromProperties).toHaveBeenCalledWith('gdrive-legacy'));
    await waitFor(() => expect(api.connectors.list).toHaveBeenCalledTimes(2));
  });

  it('surfaces a thrown import error inline and re-enables the button', async () => {
    api.connectors.list.mockResolvedValue({
      syncingEnabled: true,
      credentialStoreEnabled: true,
      connectors: [PROPERTIES_CONNECTOR],
    });
    api.connectors.importFromProperties.mockRejectedValue(new Error('import failed: bad config'));

    renderPage();

    expect(await screen.findByTestId('import-gdrive-legacy')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('import-gdrive-legacy'));

    await waitFor(() =>
      expect(screen.getByTestId('import-message-gdrive-legacy')).toHaveTextContent(/import failed: bad config/i)
    );
    expect(screen.getByTestId('import-gdrive-legacy')).not.toBeDisabled();
    // Only one list() call — the failed import did not trigger a reload.
    expect(api.connectors.list).toHaveBeenCalledTimes(1);
  });

  it('header Add Connector button navigates to the wizard route', async () => {
    api.connectors.list.mockResolvedValue({
      syncingEnabled: true,
      credentialStoreEnabled: true,
      connectors: [DB_CONNECTOR],
    });

    renderPage();
    expect(await screen.findByText('gh-wikantik')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('add-connector-button'));
    expect(mockNavigate).toHaveBeenCalledWith('/admin/connectors/new');
  });
});
