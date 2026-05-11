import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import EdgeExplorer from './EdgeExplorer';

vi.mock('../../api/client', () => ({
  api: {
    knowledge: {
      queryEdges: vi.fn(),
      getSchema: vi.fn(),
      getNode: vi.fn(),
      deleteEdge: vi.fn(),
      deleteAndRejectEdge: vi.fn(),
      bulkDeleteEdges: vi.fn(),
      getEdgeAudit: vi.fn(),
      upsertEdge: vi.fn(),
      queryNodes: vi.fn(),
    },
  },
}));
import { api } from '../../api/client';

describe('EdgeExplorer curation buttons', () => {
  beforeEach(() => {
    Object.values(api.knowledge).forEach((fn) => fn.mockReset?.());
    api.knowledge.getSchema.mockResolvedValue({ relationshipTypes: ['related', 'depends_on'] });
    api.knowledge.queryEdges.mockResolvedValue({
      edges: [
        {
          id: 'e1',
          source_id: 's1',
          target_id: 't1',
          source_name: 'A',
          target_name: 'B',
          relationship_type: 'related',
          provenance: 'human-curated',
        },
      ],
      total: 1,
    });
    api.knowledge.getNode.mockResolvedValue({ id: 's1', name: 'A', node_type: 'concept' });
    api.knowledge.getEdgeAudit.mockResolvedValue({ audit: [] });
  });

  it('shows New edge button that opens the modal', async () => {
    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));
    fireEvent.click(screen.getByRole('button', { name: /new edge/i }));
    expect(screen.getByRole('dialog', { name: /new edge/i })).toBeInTheDocument();
  });

  it('shows Edit / Delete / Delete + Prevent buttons in detail pane after selection', async () => {
    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));
    fireEvent.click(screen.getByText('A'));
    await waitFor(() => screen.getByRole('button', { name: /^edit$/i }));
    expect(screen.getByRole('button', { name: /^delete$/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /delete \+ prevent/i })).toBeInTheDocument();
  });

  it('confirms before plain delete and calls deleteEdge', async () => {
    api.knowledge.deleteEdge.mockResolvedValue({ deleted: true });
    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));
    fireEvent.click(screen.getByText('A'));
    await waitFor(() => screen.getByRole('button', { name: /^delete$/i }));
    fireEvent.click(screen.getByRole('button', { name: /^delete$/i }));
    fireEvent.click(screen.getByRole('button', { name: /confirm/i }));
    await waitFor(() => expect(api.knowledge.deleteEdge).toHaveBeenCalledWith('e1'));
  });

  it('delete + prevent captures a reason and calls deleteAndRejectEdge', async () => {
    api.knowledge.deleteAndRejectEdge.mockResolvedValue({ deleted: true, rejected: true });
    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));
    fireEvent.click(screen.getByText('A'));
    await waitFor(() => screen.getByRole('button', { name: /delete \+ prevent/i }));
    fireEvent.click(screen.getByRole('button', { name: /delete \+ prevent/i }));
    fireEvent.change(screen.getByLabelText(/reason/i), { target: { value: 'wrong direction' } });
    fireEvent.click(screen.getByRole('button', { name: /confirm/i }));
    await waitFor(() =>
      expect(api.knowledge.deleteAndRejectEdge).toHaveBeenCalledWith('e1', 'wrong direction')
    );
  });

  it('bulk delete requires typed count match and calls bulkDeleteEdges', async () => {
    api.knowledge.bulkDeleteEdges.mockResolvedValue({ deleted: 1 });
    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));
    fireEvent.click(screen.getByRole('button', { name: /delete filtered \(1\)/i }));
    // Confirm should be disabled until count matches
    const confirm = screen.getByRole('button', { name: /confirm/i });
    expect(confirm).toBeDisabled();
    const input = screen.getByLabelText(/type the count/i);
    fireEvent.change(input, { target: { value: '1' } });
    fireEvent.click(screen.getByRole('button', { name: /confirm/i }));
    await waitFor(() =>
      expect(api.knowledge.bulkDeleteEdges).toHaveBeenCalledWith(
        expect.objectContaining({ expected_count: 1 })
      )
    );
  });

  it('renders History rows from getEdgeAudit when expanded', async () => {
    api.knowledge.getEdgeAudit.mockResolvedValue({
      audit: [
        { id: 'a1', action: 'CREATE', actor: 'alice', created: '2026-05-11T10:00:00Z', reason: null },
      ],
    });
    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));
    fireEvent.click(screen.getByText('A'));
    await waitFor(() => screen.getByRole('button', { name: /history/i }));
    fireEvent.click(screen.getByRole('button', { name: /history/i }));
    await waitFor(() => expect(screen.getByText(/alice/)).toBeInTheDocument());
  });
});
