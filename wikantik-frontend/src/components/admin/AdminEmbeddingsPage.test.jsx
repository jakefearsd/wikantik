import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import AdminEmbeddingsPage from './AdminEmbeddingsPage';

vi.mock('./ContentEmbeddingsTab', () => ({ default: () => <div data-testid="stub-content-embeddings" /> }));
vi.mock('./EmbeddingActivityTab', () => ({ default: () => <div data-testid="stub-embedding-activity" /> }));

describe('AdminEmbeddingsPage', () => {
  it('shows the Content embeddings tab first and no Knowledge Graph wording', () => {
    const { container } = render(<AdminEmbeddingsPage />);
    expect(screen.getByRole('heading', { name: 'Embeddings' })).toBeTruthy();
    expect(screen.getByTestId('stub-content-embeddings')).toBeTruthy();
    expect(screen.queryByTestId('stub-embedding-activity')).toBeNull();
    expect(container.textContent).not.toMatch(/knowledge graph/i);
  });

  it('switches to the Embedding activity tab', () => {
    render(<AdminEmbeddingsPage />);
    fireEvent.click(screen.getByRole('button', { name: 'Embedding activity' }));
    expect(screen.getByTestId('stub-embedding-activity')).toBeTruthy();
    expect(screen.queryByTestId('stub-content-embeddings')).toBeNull();
  });
});
