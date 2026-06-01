import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../api/client', () => ({
  api: { getBacklinks: vi.fn() },
}));

import BacklinksPanel from './BacklinksPanel';
import { api } from '../api/client';

const renderPanel = (pageName = 'TargetPage') =>
  render(
    <MemoryRouter>
      <BacklinksPanel pageName={pageName} />
    </MemoryRouter>
  );

beforeEach(() => {
  vi.clearAllMocks();
  api.getBacklinks.mockResolvedValue({ name: 'TargetPage', backlinks: [], count: 0 });
});

describe('BacklinksPanel', () => {
  it('lists pages that link to this page', async () => {
    api.getBacklinks.mockResolvedValue({
      name: 'TargetPage',
      backlinks: ['Alpha', 'Beta'],
      count: 2,
    });
    renderPanel();
    expect(await screen.findByRole('link', { name: 'Alpha' })).toHaveAttribute('href', '/wiki/Alpha');
    expect(screen.getByRole('link', { name: 'Beta' })).toHaveAttribute('href', '/wiki/Beta');
  });

  it('renders nothing when there are no backlinks', async () => {
    const { container } = renderPanel();
    // Give the resolved (empty) promise a tick to settle.
    await waitFor(() => expect(api.getBacklinks).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing (degrades silently) when the API errors', async () => {
    api.getBacklinks.mockRejectedValue(new Error('boom'));
    const { container } = renderPanel();
    await waitFor(() => expect(api.getBacklinks).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it('does not call the API without a page name', () => {
    render(
      <MemoryRouter>
        <BacklinksPanel />
      </MemoryRouter>
    );
    expect(api.getBacklinks).not.toHaveBeenCalled();
  });
});
