import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../api/client', () => ({
  api: { getSimilarPages: vi.fn() },
}));

import SimilarPagesPanel from './SimilarPagesPanel';
import { api } from '../api/client';

const renderPanel = (pageName = 'SourcePage') =>
  render(
    <MemoryRouter>
      <SimilarPagesPanel pageName={pageName} />
    </MemoryRouter>
  );

beforeEach(() => {
  vi.clearAllMocks();
  api.getSimilarPages.mockResolvedValue({ similar: [] });
});

describe('SimilarPagesPanel', () => {
  it('lists similar pages with links', async () => {
    api.getSimilarPages.mockResolvedValue({
      similar: [{ name: 'Alpha' }, { name: 'Beta' }],
    });
    renderPanel();
    expect(await screen.findByRole('link', { name: 'Alpha' })).toHaveAttribute('href', '/wiki/Alpha');
    expect(screen.getByRole('link', { name: 'Beta' })).toHaveAttribute('href', '/wiki/Beta');
    expect(api.getSimilarPages).toHaveBeenCalledWith('SourcePage', 5);
  });

  it('renders nothing when there are no similar pages', async () => {
    const { container } = renderPanel();
    await waitFor(() => expect(api.getSimilarPages).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing (degrades silently) when the API errors', async () => {
    api.getSimilarPages.mockRejectedValue(new Error('boom'));
    const { container } = renderPanel();
    await waitFor(() => expect(api.getSimilarPages).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it('does not call the API without a page name', () => {
    render(
      <MemoryRouter>
        <SimilarPagesPanel />
      </MemoryRouter>
    );
    expect(api.getSimilarPages).not.toHaveBeenCalled();
  });
});
