import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import EdgeExplorer from './EdgeExplorer';

vi.mock('../../api/client', () => ({
  api: {
    knowledge: {
      queryEdges: vi.fn(),
      getSchema: vi.fn(),
      getNode: vi.fn(),
      getNodeById: vi.fn(),
      deleteEdge: vi.fn(),
      deleteAndRejectEdge: vi.fn(),
      getEdgeAudit: vi.fn(),
      upsertEdge: vi.fn(),
      queryNodes: vi.fn(),
    },
  },
}));
import { api } from '../../api/client';

const row = (id, name) => ({
  id,
  source_id: `${id}-s`,
  target_id: `${id}-t`,
  source_name: name,
  target_name: `${name}_target`,
  relationship_type: 'related_to',
  provenance: 'human-curated',
});

describe('EdgeExplorer', () => {
  beforeEach(() => {
    Object.values(api.knowledge).forEach((fn) => fn.mockReset?.());
    api.knowledge.getSchema.mockResolvedValue({ relationshipTypes: ['related_to', 'depends_on'] });
    api.knowledge.queryEdges.mockResolvedValue({
      edges: [row('e1', 'A'), row('e2', 'B')],
      total: 950,
    });
    api.knowledge.getNode.mockResolvedValue({ id: 's1', name: 'A', node_type: 'concept' });
    api.knowledge.getNodeById.mockImplementation((id) =>
      Promise.resolve({ id, name: `node-${id}`, node_type: 'concept', provenance: 'human-curated' }),
    );
    api.knowledge.getEdgeAudit.mockResolvedValue({ audit: [] });
  });

  it('shows total edge count in the header', async () => {
    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText(/950 total/i));
  });

  it('endpoint-kind dropdown threads through to queryEdges as endpoint_kind', async () => {
    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));
    api.knowledge.queryEdges.mockClear();
    fireEvent.change(screen.getByLabelText(/endpoint kind/i), { target: { value: 'page' } });
    await waitFor(() =>
      expect(api.knowledge.queryEdges).toHaveBeenCalledWith(
        expect.objectContaining({ endpoint_kind: 'page' }),
      ),
    );
  });

  it('shows New edge button that opens the modal', async () => {
    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));
    fireEvent.click(screen.getByRole('button', { name: /new edge/i }));
    expect(screen.getByRole('dialog', { name: /new edge/i })).toBeInTheDocument();
  });

  it('clicking a source-name button opens the detail pane with action buttons', async () => {
    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));
    fireEvent.click(screen.getByText('A'));
    await waitFor(() => screen.getByRole('button', { name: /^edit$/i }));
    expect(screen.getByRole('button', { name: /^delete$/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /delete \+ prevent/i })).toBeInTheDocument();
  });

  it('detail-pane delete uses ConfirmModal and calls deleteEdge', async () => {
    api.knowledge.deleteEdge.mockResolvedValue({ deleted: true });
    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));
    fireEvent.click(screen.getByText('A'));
    await waitFor(() => screen.getByRole('button', { name: /^delete$/i }));
    fireEvent.click(screen.getByRole('button', { name: /^delete$/i }));
    fireEvent.click(screen.getByRole('button', { name: /confirm/i }));
    await waitFor(() => expect(api.knowledge.deleteEdge).toHaveBeenCalledWith('e1'));
  });

  it('detail-pane delete + prevent captures reason and calls deleteAndRejectEdge', async () => {
    api.knowledge.deleteAndRejectEdge.mockResolvedValue({ deleted: true, rejected: true });
    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));
    fireEvent.click(screen.getByText('A'));
    await waitFor(() => screen.getByRole('button', { name: /delete \+ prevent/i }));
    fireEvent.click(screen.getByRole('button', { name: /delete \+ prevent/i }));
    fireEvent.change(screen.getByLabelText(/reason/i), { target: { value: 'wrong direction' } });
    fireEvent.click(screen.getByRole('button', { name: /confirm/i }));
    await waitFor(() =>
      expect(api.knowledge.deleteAndRejectEdge).toHaveBeenCalledWith('e1', 'wrong direction'),
    );
  });

  it('AdminTable bulk delete fans out per-row deleteEdge calls', async () => {
    api.knowledge.deleteEdge.mockResolvedValue({ deleted: true });
    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));

    // Select both rows via their checkboxes (AdminTable labels each one "Select row N").
    const checks = screen.getAllByRole('checkbox');
    // First checkbox is the header (select all); rows follow.
    fireEvent.click(checks[1]);
    fireEvent.click(checks[2]);

    // Selection bar surfaces the bulk action buttons. Pick "Delete".
    const toolbar = await screen.findByRole('toolbar');
    fireEvent.click(within(toolbar).getByRole('button', { name: /^delete$/i }));

    // Confirm in the dialog.
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: /^delete$/i }));

    await waitFor(() => {
      expect(api.knowledge.deleteEdge).toHaveBeenCalledWith('e1');
      expect(api.knowledge.deleteEdge).toHaveBeenCalledWith('e2');
    });
  });

  it('AdminTable bulk reject passes the typed reason per-row', async () => {
    api.knowledge.deleteAndRejectEdge.mockResolvedValue({ deleted: true, rejected: true });
    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));

    const checks = screen.getAllByRole('checkbox');
    fireEvent.click(checks[1]);

    const toolbar = await screen.findByRole('toolbar');
    fireEvent.click(within(toolbar).getByRole('button', { name: /delete \+ prevent/i }));

    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByPlaceholderText(/why are these wrong/i), {
      target: { value: 'bulk bad inference' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: /delete \+ prevent/i }));

    await waitFor(() =>
      expect(api.knowledge.deleteAndRejectEdge).toHaveBeenCalledWith('e1', 'bulk bad inference'),
    );
  });

  it('Edit modal populates Source/Target when the node name contains a slash (Tomcat %2F regression)', async () => {
    // Reject *any* by-name lookup for names containing a slash, mirroring
    // Tomcat's default behaviour of returning 400 for encoded slashes in path
    // segments. The component must therefore fetch by ID — not by name.
    api.knowledge.getNode.mockImplementation((name) => {
      if (typeof name === 'string' && name.includes('/')) {
        return Promise.reject(new Error('400 Bad Request (encoded slash)'));
      }
      return Promise.resolve({ id: 's-fallback', name, node_type: 'concept' });
    });
    api.knowledge.getNodeById.mockImplementation((id) => {
      if (id === 'edge1-s') {
        return Promise.resolve({
          id: 'edge1-s',
          name: 'Automated Storage and Retrieval System (AS/RS)',
          node_type: 'article',
          provenance: 'human-authored',
        });
      }
      if (id === 'edge1-t') {
        return Promise.resolve({
          id: 'edge1-t',
          name: 'stacker crane',
          node_type: 'concept',
          provenance: 'ai-inferred',
        });
      }
      return Promise.resolve(null);
    });
    api.knowledge.queryEdges.mockResolvedValue({
      edges: [
        {
          id: 'edge1',
          source_id: 'edge1-s',
          target_id: 'edge1-t',
          source_name: 'Automated Storage and Retrieval System (AS/RS)',
          target_name: 'stacker crane',
          relationship_type: 'contains',
          provenance: 'human-curated',
        },
      ],
      total: 1,
    });

    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText(/Automated Storage and Retrieval System/));
    fireEvent.click(screen.getByText(/Automated Storage and Retrieval System/));

    // Wait for the detail pane action row to appear.
    const editBtn = await screen.findByRole('button', { name: /^edit$/i });
    fireEvent.click(editBtn);

    // The EdgeFormModal opens. Its Source and Target NodeAutocomplete inputs
    // are aria-labeled "Source" / "Target" and disabled in edit mode; both
    // must show the resolved node names rather than empty strings.
    const sourceInput = await screen.findByLabelText('Source');
    expect(sourceInput).toHaveValue('Automated Storage and Retrieval System (AS/RS)');
    const targetInput = screen.getByLabelText('Target');
    expect(targetInput).toHaveValue('stacker crane');
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
