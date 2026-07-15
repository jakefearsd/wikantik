import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import ConnectorSettingsForm from './ConnectorSettingsForm';

const GITHUB_INITIAL = {
  config: { repo: 'jakefearsd/wikantik', branch: '', path_prefix: '' },
  enabled: true,
  syncIntervalHours: 6,
  cluster: 'engineering',
  defaultTags: 'docs',
  pagePrefix: 'GH-',
};

describe('ConnectorSettingsForm', () => {
  it('renders fields from type metadata', () => {
    render(
      <ConnectorSettingsForm type="github" initialValues={GITHUB_INITIAL} onSubmit={vi.fn()} />
    );

    // Per-type fields from CONNECTOR_TYPES.github.fields
    expect(screen.getByTestId('field-repo')).toHaveValue('jakefearsd/wikantik');
    expect(screen.getByTestId('field-branch')).toBeInTheDocument();
    expect(screen.getByTestId('field-path_prefix')).toBeInTheDocument();
    expect(screen.getByTestId('field-max_files')).toHaveValue(500); // field default

    // Common fields
    expect(screen.getByTestId('field-enabled')).toBeChecked();
    expect(screen.getByTestId('field-syncIntervalHours')).toHaveValue(6);
    expect(screen.getByTestId('field-cluster')).toHaveValue('engineering');
    expect(screen.getByTestId('field-defaultTags')).toHaveValue('docs');
    expect(screen.getByTestId('field-pagePrefix')).toHaveValue('GH-');
    expect(screen.getByText(/Glued directly onto page names/i)).toBeInTheDocument();
  });

  it('list field serializes lines to array on submit', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(
      <ConnectorSettingsForm
        type="webcrawler"
        initialValues={{ config: {}, enabled: true, syncIntervalHours: 0, cluster: '', defaultTags: '', pagePrefix: '' }}
        onSubmit={onSubmit}
      />
    );

    fireEvent.change(screen.getByTestId('field-seeds'), {
      target: { value: 'https://a.example\nhttps://b.example' },
    });
    fireEvent.click(screen.getByTestId('settings-submit-button'));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    const body = onSubmit.mock.calls[0][0];
    expect(body.config.seeds).toEqual(['https://a.example', 'https://b.example']);
  });

  it('preserves unmodeled config fields on submit (full-replace PUT must not drop them)', async () => {
    // The backend supports config keys the UI doesn't render (e.g. webcrawler
    // user_agent). PUT is full-replace, so a save that omits them would
    // silently reset them server-side.
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(
      <ConnectorSettingsForm
        type="webcrawler"
        initialValues={{
          config: { seeds: ['https://x.com'], user_agent: 'CustomBot/2.0' },
          enabled: true, syncIntervalHours: 0, cluster: '', defaultTags: '', pagePrefix: '',
        }}
        onSubmit={onSubmit}
      />
    );

    fireEvent.change(screen.getByTestId('field-seeds'), {
      target: { value: 'https://x.com\nhttps://y.com' },
    });
    fireEvent.click(screen.getByTestId('settings-submit-button'));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    const body = onSubmit.mock.calls[0][0];
    // Rendered-field edits win…
    expect(body.config.seeds).toEqual(['https://x.com', 'https://y.com']);
    // …and unmodeled keys survive untouched.
    expect(body.config.user_agent).toBe('CustomBot/2.0');
  });

  it('renders server error map under matching fields', () => {
    render(
      <ConnectorSettingsForm
        type="github"
        initialValues={{ config: { repo: '' }, enabled: true, syncIntervalHours: 6, cluster: '', defaultTags: '', pagePrefix: '' }}
        onSubmit={vi.fn()}
        errors={{ repo: 'repo is required', cluster: 'must be kebab-case' }}
      />
    );

    expect(screen.getByTestId('field-error-repo')).toHaveTextContent('repo is required');
    expect(screen.getByTestId('field-error-cluster')).toHaveTextContent('must be kebab-case');
  });

  it('renders read-only with disabled inputs and no submit button', () => {
    render(
      <ConnectorSettingsForm type="github" initialValues={GITHUB_INITIAL} onSubmit={vi.fn()} readOnly />
    );

    expect(screen.getByTestId('field-repo')).toBeDisabled();
    expect(screen.getByTestId('field-enabled')).toBeDisabled();
    expect(screen.queryByTestId('settings-submit-button')).not.toBeInTheDocument();
  });
});
