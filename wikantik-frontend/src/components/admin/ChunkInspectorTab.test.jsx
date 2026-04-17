import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import ChunkInspectorTab from './ChunkInspectorTab';
import { api } from '../../api/client';

const outliersFixture = {
  most_chunks: [
    { page_name: 'BigPage', chunk_count: 42, max_tokens: 510, total_tokens: 12000, char_count: 2048 },
    { page_name: 'Medium', chunk_count: 15, max_tokens: 400, total_tokens: 3000, char_count: 900 },
  ],
  large_single_chunks: [
    { page_name: 'LonelyGiant', chunk_count: 1, max_tokens: 130, total_tokens: 130, char_count: 500 },
  ],
  oversized_chunks: [
    { page_name: 'OverTok', chunk_count: 1, max_tokens: 910, total_tokens: 910, char_count: 300 },
  ],
};

function renderWithRouter(ui) {
  return render(<MemoryRouter>{ui}</MemoryRouter>);
}

describe('ChunkInspectorTab', () => {
  beforeEach(() => {
    vi.spyOn(api.admin, 'getChunkOutliers').mockResolvedValue(outliersFixture);
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('rendersOutliersOnMount', async () => {
    renderWithRouter(<ChunkInspectorTab />);
    await waitFor(() => expect(screen.getByText('BigPage')).toBeInTheDocument());
    expect(screen.getByText('LonelyGiant')).toBeInTheDocument();
    expect(screen.getByText('OverTok')).toBeInTheDocument();
  });

  it('loadsChunksWhenPageSubmitted', async () => {
    vi.spyOn(api.admin, 'getChunks').mockResolvedValue({
      page: 'PageA',
      chunks: [
        {
          chunk_index: 0,
          heading_path: ['Top', 'Section'],
          text: 'first chunk body text',
          char_count: 22,
          token_count_estimate: 7,
          content_hash: 'abc123def456',
          created: '2026-04-17T10:00:00Z',
          modified: '2026-04-17T10:00:00Z',
        },
        {
          chunk_index: 1,
          heading_path: ['Top'],
          text: 'second chunk body text',
          char_count: 24,
          token_count_estimate: 8,
          content_hash: 'zzz999',
          created: '2026-04-17T10:00:00Z',
          modified: '2026-04-17T10:00:00Z',
        },
      ],
    });
    renderWithRouter(<ChunkInspectorTab />);
    await waitFor(() => screen.getByText('BigPage'));

    fireEvent.change(screen.getByLabelText(/Page Name/i), { target: { value: 'PageA' } });
    fireEvent.click(screen.getByRole('button', { name: /Load/i }));

    await waitFor(() => expect(screen.getByText(/first chunk body text/)).toBeInTheDocument());
    expect(screen.getByText(/second chunk body text/)).toBeInTheDocument();
    expect(screen.getByText(/Top > Section/)).toBeInTheDocument();
    expect(screen.getByText(/7 tokens/)).toBeInTheDocument();
  });

  it('showsErrorOn404', async () => {
    vi.spyOn(api.admin, 'getChunks').mockRejectedValue(
      Object.assign(new Error('page not found'), { status: 404, code: 'page_not_found' }),
    );
    renderWithRouter(<ChunkInspectorTab />);
    await waitFor(() => screen.getByText('BigPage'));

    fireEvent.change(screen.getByLabelText(/Page Name/i), { target: { value: 'Ghost' } });
    fireEvent.click(screen.getByRole('button', { name: /Load/i }));

    await waitFor(() => expect(
      screen.getByText(/No chunks found for page Ghost/i)).toBeInTheDocument());
  });

  it('togglesFullTextDisclosure', async () => {
    const fullText = 'x'.repeat(400);
    vi.spyOn(api.admin, 'getChunks').mockResolvedValue({
      page: 'PageB',
      chunks: [{
        chunk_index: 0,
        heading_path: ['Top'],
        text: fullText,
        char_count: 400,
        token_count_estimate: 100,
        content_hash: 'h',
        created: '2026-04-17T10:00:00Z',
        modified: '2026-04-17T10:00:00Z',
      }],
    });
    renderWithRouter(<ChunkInspectorTab />);
    await waitFor(() => screen.getByText('BigPage'));

    fireEvent.change(screen.getByLabelText(/Page Name/i), { target: { value: 'PageB' } });
    fireEvent.click(screen.getByRole('button', { name: /Load/i }));

    await waitFor(() => screen.getByRole('button', { name: /Show full/i }));
    // Truncated preview: 200 chars of 'x' + ellipsis — full text not yet fully rendered
    // in a <pre>. After clicking Show full, the <pre> appears.
    expect(screen.queryByTestId('chunk-full-text-0')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /Show full/i }));
    await waitFor(() => expect(screen.getByTestId('chunk-full-text-0')).toBeInTheDocument());
    expect(screen.getByTestId('chunk-full-text-0').textContent).toBe(fullText);
  });
});
