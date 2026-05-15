import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import GraphExplorer from './GraphExplorer';

vi.mock('../../api/client', () => ({
  api: {
    knowledge: {
      getSchema: vi.fn(),
      queryNodes: vi.fn(),
      getNode: vi.fn(),
      getNodeById: vi.fn(),
      getNodeMentions: vi.fn(),
      getSimilarNodes: vi.fn(),
      deleteNode: vi.fn(),
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
      stats: {
        nodes: 1234,
        edges: 950,
        unreviewedProposals: 7,
        pendingBreakdown: {
          total: 7,
          newNodes: 5,
          newEdges: 2,
          judgeApproved: 3,
          judgeAbstained: 2,
          unjudged: 2,
        },
      },
    });
    api.knowledge.queryNodes.mockResolvedValue({
      nodes: [node('n1', 'Alpha', 'article'), node('n2', 'Beta', 'concept')],
      total: 1234,
    });
    api.knowledge.getSimilarNodes.mockResolvedValue({ similar: [] });
    api.knowledge.getNodeMentions.mockResolvedValue({ mentions: [] });
    api.knowledge.getNode.mockImplementation((name) =>
      Promise.resolve({ ...node(`id-${name}`, name), edges: [] }),
    );
    api.knowledge.getNodeById.mockImplementation((id) =>
      Promise.resolve({ ...node(id, `name-${id}`), edges: [] }),
    );
  });

  it('shows the total node count in the header', async () => {
    render(<GraphExplorer />);
    await waitFor(() => screen.getByText('Alpha'));
    expect(screen.getByText(/1,234 total/)).toBeInTheDocument();
  });

  it('schema header disambiguates Knowledge Graph and labels nodes/edges in human terms', async () => {
    render(<GraphExplorer />);
    const header = await screen.findByTestId('kg-schema-header');
    expect(header.textContent).toMatch(/Knowledge Graph/i);
    expect(header.textContent).toMatch(/1,234.*nodes/i);
    expect(header.textContent).toMatch(/950.*edges/i);
    expect(header.textContent).toMatch(/entities/i);
    expect(header.textContent).toMatch(/relationships/i);
  });

  it('schema header breaks pending proposals down by type and judge state', async () => {
    render(<GraphExplorer />);
    const queue = await screen.findByTestId('kg-pending-queue');
    expect(queue.textContent).toMatch(/7\s+pending/i);
    expect(queue.textContent).toMatch(/5\s+new nodes/i);
    expect(queue.textContent).toMatch(/2\s+new edges/i);
    expect(queue.textContent).toMatch(/3\s+judge.?approved/i);
    expect(queue.textContent).toMatch(/2\s+abstained/i);
    expect(queue.textContent).toMatch(/2\s+unjudged/i);
  });

  it('Stub column header is replaced with the plainer "No wiki page"', async () => {
    render(<GraphExplorer />);
    await waitFor(() => screen.getByText('Alpha'));
    expect(screen.queryByRole('columnheader', { name: /^Stub$/ })).toBeNull();
    expect(
      screen.getByRole('columnheader', { name: /No wiki page/i }),
    ).toBeInTheDocument();
  });

  it('Status column header carries a tooltip explaining the frontmatter source', async () => {
    render(<GraphExplorer />);
    await waitFor(() => screen.getByText('Alpha'));
    const status = screen.getByRole('columnheader', { name: /^Status$/ });
    expect(status.getAttribute('title')).toMatch(/frontmatter|page.*status/i);
  });

  it('detail-pane empty state describes what the pane will contain', async () => {
    render(<GraphExplorer />);
    await screen.findByTestId('kg-schema-header');
    expect(
      screen.getByText(/Select a node.*properties.*mention.*edges/i),
    ).toBeInTheDocument();
  });

  it('schema header omits the proposal queue line when no proposals are pending', async () => {
    api.knowledge.getSchema.mockResolvedValue({
      nodeTypes: ['article'],
      statusValues: [],
      stats: {
        nodes: 10, edges: 5, unreviewedProposals: 0,
        pendingBreakdown: { total: 0, newNodes: 0, newEdges: 0,
          judgeApproved: 0, judgeAbstained: 0, unjudged: 0 },
      },
    });
    render(<GraphExplorer />);
    await screen.findByTestId('kg-schema-header');
    expect(screen.queryByTestId('kg-pending-queue')).toBeNull();
  });

  it('clicking a name button opens the detail pane via ID-based lookup', async () => {
    render(<GraphExplorer />);
    await waitFor(() => screen.getByText('Alpha'));
    fireEvent.click(screen.getByText('Alpha'));
    // Use the slash-safe by-id lookup, not by-name (Tomcat 400s on encoded "/"
    // — node names like "Foo (Bar/Baz)" must not go through path segments).
    await waitFor(() =>
      expect(api.knowledge.getNodeById).toHaveBeenCalledWith('n1'),
    );
    expect(api.knowledge.getNode).not.toHaveBeenCalled();
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

      // With the ID-based selection flow, selectedNode.id is the row id ('n1').
      await waitFor(() =>
        expect(api.knowledge.deleteNode).toHaveBeenCalledWith('n1'),
      );
      expect(reloadSpy).not.toHaveBeenCalled();
    } finally {
      window.confirm = originalConfirm;
      reloadSpy.mockRestore();
    }
  });
});
