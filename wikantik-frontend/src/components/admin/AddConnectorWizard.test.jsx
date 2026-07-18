import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import AddConnectorWizard from './AddConnectorWizard';
import { CONNECTOR_TYPES, TYPE_ORDER } from './connectorGuides';

const mockNavigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, useNavigate: () => mockNavigate };
});

vi.mock('../../api/client', () => ({
  api: {
    connectors: {
      create: vi.fn(),
      test: vi.fn(),
      setCredential: vi.fn(),
      sync: vi.fn(),
    },
  },
}));

import { api } from '../../api/client';

function renderWizard() {
  return render(
    <MemoryRouter>
      <AddConnectorWizard />
    </MemoryRouter>
  );
}

// Advances from the type picker through the source step for `type`, filling
// the connector id and any required per-type fields with `fieldValues`
// ({ fieldName: rawInputText }). Leaves the wizard wherever Next lands next
// (authorize step if the type has one, otherwise the test step).
function pickTypeAndFillSource(type, id, fieldValues = {}) {
  fireEvent.click(screen.getByTestId(`type-card-${type}`));
  fireEvent.change(screen.getByTestId('connector-id'), { target: { value: id } });
  Object.entries(fieldValues).forEach(([name, value]) => {
    fireEvent.change(screen.getByTestId(`field-${name}`), { target: { value } });
  });
  fireEvent.click(screen.getByTestId('settings-submit-button'));
}

describe('AddConnectorWizard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockNavigate.mockReset();
  });

  it('type picker shows six cards and advances', () => {
    renderWizard();

    expect(TYPE_ORDER).toHaveLength(6);
    TYPE_ORDER.forEach((type) => {
      const card = screen.getByTestId(`type-card-${type}`);
      expect(card).toHaveTextContent(CONNECTOR_TYPES[type].label);
    });

    const githubCard = screen.getByTestId('type-card-github');
    expect(githubCard).toHaveTextContent(CONNECTOR_TYPES.github.blurb);
    expect(githubCard).toHaveTextContent(CONNECTOR_TYPES.github.goodFor);

    fireEvent.click(screen.getByTestId('type-card-webcrawler'));

    expect(screen.getByTestId('wizard-step-source')).toBeInTheDocument();
    expect(screen.getByTestId('connector-id')).toBeInTheDocument();
    expect(screen.getByTestId('field-seeds')).toBeInTheDocument();
  });

  it('source step blocks next on missing required field', () => {
    renderWizard();

    fireEvent.click(screen.getByTestId('type-card-github'));
    fireEvent.change(screen.getByTestId('connector-id'), { target: { value: 'gh-missing' } });
    // repo (required) left blank
    fireEvent.click(screen.getByTestId('settings-submit-button'));

    expect(screen.getByTestId('wizard-step-source')).toBeInTheDocument();
    expect(screen.getByTestId('field-error-repo')).toHaveTextContent(/required/i);
    expect(api.connectors.test).not.toHaveBeenCalled();
    expect(api.connectors.create).not.toHaveBeenCalled();
  });

  it("github flow: authorize step shows PAT steps and captures token", () => {
    renderWizard();

    pickTypeAndFillSource('github', 'gh-authtest', { repo: 'jakefearsd/wikantik' });

    expect(screen.getByTestId('wizard-step-authorize')).toBeInTheDocument();
    CONNECTOR_TYPES.github.authGuide.steps.forEach((step) => {
      expect(screen.getByTestId('authorize-steps')).toHaveTextContent(step);
    });
    expect(screen.getByText(CONNECTOR_TYPES.github.authGuide.optionalNote)).toBeInTheDocument();

    fireEvent.change(screen.getByTestId('secret-input-token'), { target: { value: 'ghp_captured' } });
    fireEvent.click(screen.getByTestId('wizard-next-button'));

    expect(screen.getByTestId('wizard-step-test')).toBeInTheDocument();
    expect(screen.getByTestId('run-test-button')).toBeInTheDocument();
  });

  it('test step calls api with transient credentials and shows found count', async () => {
    api.connectors.test.mockResolvedValue({
      ok: true,
      found: 3,
      sample: ['PageA', 'PageB'],
      complete: true,
      message: 'ok',
    });

    renderWizard();
    pickTypeAndFillSource('github', 'gh-testflow', { repo: 'jakefearsd/wikantik' });
    fireEvent.change(screen.getByTestId('secret-input-token'), { target: { value: 'ghp_secret' } });
    fireEvent.click(screen.getByTestId('wizard-next-button'));

    fireEvent.click(screen.getByTestId('run-test-button'));

    await waitFor(() => expect(api.connectors.test).toHaveBeenCalledTimes(1));
    expect(api.connectors.test).toHaveBeenCalledWith({
      type: 'github',
      config: { repo: 'jakefearsd/wikantik', branch: '', path_prefix: '', max_files: 500 },
      credentials: { token: 'ghp_secret' },
    });

    expect(screen.getByTestId('test-result-ok')).toHaveTextContent('found 3 item(s); first: PageA');
  });

  it('failed test shows message and hint, allows skip', async () => {
    api.connectors.test.mockResolvedValue({
      ok: false,
      found: 0,
      sample: [],
      complete: false,
      message: 'Connection refused',
    });

    renderWizard();
    pickTypeAndFillSource('webcrawler', 'wc-failtest', { seeds: 'https://a.example' });

    expect(screen.getByTestId('wizard-step-test')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('run-test-button'));

    expect(await screen.findByTestId('test-result-error')).toBeInTheDocument();
    expect(screen.getByTestId('test-result-error')).toHaveTextContent('Connection refused');
    expect(screen.getByTestId('test-result-error')).toHaveTextContent(/check your settings in step 1/i);

    fireEvent.click(screen.getByTestId('skip-test-link'));
    expect(screen.getByTestId('wizard-step-review')).toBeInTheDocument();
  });

  it('review shows expectations and create+setCredential+navigate on Save', async () => {
    api.connectors.create.mockResolvedValue({ id: 'gh-final', type: 'github' });
    api.connectors.setCredential.mockResolvedValue({});

    renderWizard();
    pickTypeAndFillSource('github', 'gh-final', { repo: 'jakefearsd/wikantik' });
    fireEvent.change(screen.getByTestId('secret-input-token'), { target: { value: 'ghp_final' } });
    fireEvent.click(screen.getByTestId('wizard-next-button'));
    fireEvent.click(screen.getByTestId('skip-test-link'));

    expect(screen.getByTestId('wizard-step-review')).toBeInTheDocument();
    expect(screen.getByTestId('review-expectations')).toHaveTextContent(CONNECTOR_TYPES.github.expectations);

    fireEvent.click(screen.getByTestId('wizard-save-button'));

    await waitFor(() => expect(api.connectors.create).toHaveBeenCalledTimes(1));
    expect(api.connectors.create).toHaveBeenCalledWith({
      id: 'gh-final',
      type: 'github',
      enabled: true,
      syncIntervalHours: 0,
      config: { repo: 'jakefearsd/wikantik', branch: '', path_prefix: '', max_files: 500 },
      cluster: '',
      defaultTags: '',
      pagePrefix: '',
    });

    await waitFor(() => expect(api.connectors.setCredential).toHaveBeenCalledWith('gh-final', 'token', 'ghp_final'));
    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/admin/connectors/gh-final'));
  });

  it('gdrive: shows redirect URI copy block and skips live test', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } });

    api.connectors.create.mockResolvedValue({ id: 'gd-test', type: 'gdrive' });
    api.connectors.setCredential.mockResolvedValue({});

    renderWizard();
    pickTypeAndFillSource('gdrive', 'gd-test', { folder_ids: 'abc123', client_id: 'client-id-1' });

    expect(screen.getByTestId('wizard-step-authorize')).toBeInTheDocument();
    const expectedUri = `${window.location.origin}/admin/connector-oauth/gdrive/callback`;
    expect(screen.getByTestId('redirect-uri-value')).toHaveTextContent(expectedUri);
    expect(screen.getByText(/Register this exact URI in Google Cloud \(step 5\)\./)).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('copy-redirect-uri-button'));
    expect(writeText).toHaveBeenCalledWith(expectedUri);

    fireEvent.change(screen.getByTestId('secret-input-client_secret'), { target: { value: 'gd-secret' } });
    fireEvent.click(screen.getByTestId('wizard-next-button'));

    expect(screen.getByTestId('wizard-step-test')).toBeInTheDocument();
    expect(screen.getByTestId('gdrive-test-deferred-message')).toHaveTextContent(
      /finish the wizard, then use Authorize with Google on the connector's Authorization tab/i
    );
    expect(screen.queryByTestId('run-test-button')).not.toBeInTheDocument();
    expect(api.connectors.test).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId('wizard-next-button'));
    expect(screen.getByTestId('wizard-step-review')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('wizard-save-button'));

    await waitFor(() => expect(api.connectors.setCredential).toHaveBeenCalledWith('gd-test', 'client_secret', 'gd-secret'));
    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/admin/connectors/gd-test?next=authorize'));

    vi.unstubAllGlobals();
  });

  it('successful test shows a Continue control and hides the skip warning', async () => {
    api.connectors.test.mockResolvedValue({
      ok: true,
      found: 2,
      sample: ['PageA'],
      complete: true,
      message: 'ok',
    });

    renderWizard();
    pickTypeAndFillSource('webcrawler', 'wc-ok', { seeds: 'https://a.example' });
    fireEvent.click(screen.getByTestId('run-test-button'));

    expect(await screen.findByTestId('test-result-ok')).toBeInTheDocument();
    expect(screen.getByTestId('test-continue')).toBeInTheDocument();
    expect(screen.queryByTestId('skip-test-link')).not.toBeInTheDocument();
    expect(screen.queryByText(/Skipping means/i)).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('test-continue'));
    expect(screen.getByTestId('wizard-step-review')).toBeInTheDocument();
  });

  it('re-entering the source step invalidates a previous test result', async () => {
    api.connectors.test.mockResolvedValue({
      ok: true,
      found: 1,
      sample: ['PageA'],
      complete: true,
      message: 'ok',
    });

    renderWizard();
    pickTypeAndFillSource('webcrawler', 'wc-stale', { seeds: 'https://a.example' });
    fireEvent.click(screen.getByTestId('run-test-button'));
    expect(await screen.findByTestId('test-result-ok')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('wizard-back-button'));
    expect(screen.getByTestId('wizard-step-source')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('settings-submit-button'));

    expect(screen.getByTestId('wizard-step-test')).toBeInTheDocument();
    expect(screen.queryByTestId('test-result-ok')).not.toBeInTheDocument();
    expect(screen.getByTestId('run-test-button')).toBeInTheDocument();
  });

  it('changing the secret invalidates a previous test result', async () => {
    api.connectors.test.mockResolvedValue({
      ok: true,
      found: 1,
      sample: ['PageA'],
      complete: true,
      message: 'ok',
    });

    renderWizard();
    pickTypeAndFillSource('github', 'gh-stale', { repo: 'jakefearsd/wikantik' });
    fireEvent.change(screen.getByTestId('secret-input-token'), { target: { value: 'ghp_one' } });
    fireEvent.click(screen.getByTestId('wizard-next-button'));
    fireEvent.click(screen.getByTestId('run-test-button'));
    expect(await screen.findByTestId('test-result-ok')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('wizard-back-button'));
    expect(screen.getByTestId('wizard-step-authorize')).toBeInTheDocument();
    fireEvent.change(screen.getByTestId('secret-input-token'), { target: { value: 'ghp_two' } });
    fireEvent.click(screen.getByTestId('wizard-next-button'));

    expect(screen.getByTestId('wizard-step-test')).toBeInTheDocument();
    expect(screen.queryByTestId('test-result-ok')).not.toBeInTheDocument();
  });

  it('source field edits survive Back to the type picker', () => {
    renderWizard();

    fireEvent.click(screen.getByTestId('type-card-github'));
    fireEvent.change(screen.getByTestId('connector-id'), { target: { value: 'gh-keep' } });
    fireEvent.change(screen.getByTestId('field-repo'), { target: { value: 'octo/repo' } });
    fireEvent.change(screen.getByTestId('field-cluster'), { target: { value: 'engineering' } });

    fireEvent.click(screen.getByTestId('wizard-back-button'));
    expect(screen.getByTestId('wizard-step-type')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('type-card-github'));
    expect(screen.getByTestId('connector-id')).toHaveValue('gh-keep');
    expect(screen.getByTestId('field-repo')).toHaveValue('octo/repo');
    expect(screen.getByTestId('field-cluster')).toHaveValue('engineering');
  });

  it('logs a console.warn when the fire-and-forget sync fails', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.connectors.create.mockResolvedValue({ id: 'wc-sync', type: 'webcrawler' });
    api.connectors.sync.mockRejectedValue(new Error('sync boom'));

    renderWizard();
    pickTypeAndFillSource('webcrawler', 'wc-sync', { seeds: 'https://a.example' });
    fireEvent.click(screen.getByTestId('skip-test-link'));
    fireEvent.click(screen.getByTestId('wizard-save-sync-button'));

    await waitFor(() => expect(api.connectors.sync).toHaveBeenCalledWith('wc-sync'));
    await waitFor(() => expect(warnSpy).toHaveBeenCalled());
    expect(
      warnSpy.mock.calls.some((call) => call.join(' ').includes('sync boom'))
    ).toBe(true);

    warnSpy.mockRestore();
  });

  it('server 422 on create returns to source step with field error', async () => {
    api.connectors.create.mockRejectedValue(
      Object.assign(new Error('reserved id'), { status: 422, body: { errors: { connector_id: 'reserved id' } } })
    );

    renderWizard();
    pickTypeAndFillSource('webcrawler', 'test', { seeds: 'https://a.example' });
    fireEvent.click(screen.getByTestId('skip-test-link'));
    expect(screen.getByTestId('wizard-step-review')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('wizard-save-button'));

    expect(await screen.findByTestId('wizard-step-source')).toBeInTheDocument();
    expect(screen.getByTestId('connector-id-error')).toHaveTextContent('reserved id');
    expect(screen.getByTestId('connector-id')).toHaveValue('test');
  });
});
