import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import GraphExplorer from './GraphExplorer';

vi.mock('../../api/client', () => ({
  api: {
    knowledge: {
      getSchema: vi.fn(),
      queryNodes: vi.fn(),
      getNode: vi.fn(),
      getSimilarNodes: vi.fn(),
      deleteNode: vi.fn(),
      projectAll: vi.fn(),
    },
  },
}));
import { api } from '../../api/client';

const node = (id, name, nodeType = 'concept') => ({
  id,
  name,
  node_type: nodeType,
  provenance: 'ai-inferred',
  is_stub: false,
  properties: {},
  edges: [],
});

describe('GraphExplorer', () => {
  beforeEach(() => {
    Object.values(api.knowledge).forEach((fn) => fn.mockReset?.());
    api.knowledge.getSchema.mockResolvedValue({
      nodeTypes: ['article', 'concept', 'hub'],
      statusValues: ['active', 'archived'],
      stats: { nodes: 1234, edges: 950, unreviewedProposals: 7 },
    });
    api.knowledge.queryNodes.mockResolvedValue({
      nodes: [node('n1', 'Alpha', 'article'), node('n2', 'Beta', 'concept')],
      total: 1234,
    });
    api.knowledge.getSimilarNodes.mockResolvedValue({ similar: [] });
    api.knowledge.getNode.mockImplementation((name) =>
      Promise.resolve({ ...node(`id-${name}`, name), edges: [] }),
    );
  });

  it('shows the total node count in the header', async () => {
    render(<GraphExplorer />);
    await waitFor(() => screen.getByText('Alpha'));
    expect(screen.getByText(/1,234 total/)).toBeInTheDocument();
  });

  it('clicking a name button opens the detail pane', async () => {
    render(<GraphExplorer />);
    await waitFor(() => screen.getByText('Alpha'));
    fireEvent.click(screen.getByText('Alpha'));
    await waitFor(() =>
      expect(api.knowledge.getNode).toHaveBeenCalledWith('Alpha'),
    );
  });

  it('AdminTable bulk delete fans out per-row deleteNode calls', async () => {
    api.knowledge.deleteNode.mockResolvedValue({ deleted: true });
    render(<GraphExplorer />);
    await waitFor(() => screen.getByText('Alpha'));

    const checks = screen.getAllByRole('checkbox');
    // Header checkbox at [0], rows follow.
    fireEvent.click(checks[1]);
    fireEvent.click(checks[2]);

    const toolbar = await screen.findByRole('toolbar');
    fireEvent.click(within(toolbar).getByRole('button', { name: /^delete$/i }));

    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: /^delete$/i }));

    await waitFor(() => {
      expect(api.knowledge.deleteNode).toHaveBeenCalledWith('n1');
      expect(api.knowledge.deleteNode).toHaveBeenCalledWith('n2');
    });
  });

  it('detail-pane single delete refreshes without window.location.reload', async () => {
    // Stub confirm() to auto-accept so the delete flow proceeds.
    const originalConfirm = window.confirm;
    window.confirm = () => true;
    const reloadSpy = vi.spyOn(window.location, 'reload')
      .mockImplementation(() => {
        throw new Error('window.location.reload() must not be called from GraphExplorer/NodeDetail');
      });
    api.knowledge.deleteNode.mockResolvedValue({ deleted: true });

    try {
      render(<GraphExplorer />);
      await waitFor(() => screen.getByText('Alpha'));
      fireEvent.click(screen.getByText('Alpha'));

      // The detail-pane Delete button renders inside NodeDetail.
      const deleteBtn = await screen.findByRole('button', { name: /^delete$/i });
      fireEvent.click(deleteBtn);

      await waitFor(() =>
        expect(api.knowledge.deleteNode).toHaveBeenCalledWith('id-Alpha'),
      );
      expect(reloadSpy).not.toHaveBeenCalled();
    } finally {
      window.confirm = originalConfirm;
      reloadSpy.mockRestore();
    }
  });
});
