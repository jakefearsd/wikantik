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
      getNodeMentions: vi.fn(),
      deleteEdge: vi.fn(),
      deleteAndRejectEdge: vi.fn(),
      confirmEdge: vi.fn(),
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
    api.knowledge.getNodeMentions.mockResolvedValue({ mentions: [] });
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
    // The detail pane also has a "Confirm" button (in-place elevate); scope
    // the confirm click to the dialog so we hit the modal's Confirm.
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: /^confirm$/i }));
    await waitFor(() => expect(api.knowledge.deleteEdge).toHaveBeenCalledWith('e1'));
  });

  it('detail-pane delete + prevent captures reason and calls deleteAndRejectEdge', async () => {
    api.knowledge.deleteAndRejectEdge.mockResolvedValue({ deleted: true, rejected: true });
    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));
    fireEvent.click(screen.getByText('A'));
    await waitFor(() => screen.getByRole('button', { name: /delete \+ prevent/i }));
    fireEvent.click(screen.getByRole('button', { name: /delete \+ prevent/i }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText(/reason/i), {
      target: { value: 'wrong direction' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: /^confirm$/i }));
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

  it('Confirm button calls confirmEdge with the row id and updates provenance in place', async () => {
    // Seed an AI-inferred row so the Confirm button is enabled — the
    // disabled-when-already-curated state is covered separately below.
    api.knowledge.queryEdges.mockResolvedValue({
      edges: [{ ...row('e1', 'A'), provenance: 'ai-inferred' }],
      total: 1,
    });
    api.knowledge.confirmEdge.mockResolvedValue({
      id: 'e1',
      tier: 'human',
      provenance: 'human-curated',
      confirmed: true,
    });

    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));
    fireEvent.click(screen.getByText('A'));

    const confirm = await screen.findByRole('button', { name: /^confirm$/i });
    expect(confirm).not.toBeDisabled();
    fireEvent.click(confirm);

    await waitFor(() => expect(api.knowledge.confirmEdge).toHaveBeenCalledWith('e1'));

    // After the call resolves the button must report the elevated state,
    // i.e. be disabled (we re-read selectedEdge.provenance into the button's
    // `disabled` predicate).
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /^confirm$/i })).toBeDisabled(),
    );
  });

  it('Confirm button is disabled when the edge is already human-curated', async () => {
    api.knowledge.queryEdges.mockResolvedValue({
      edges: [{ ...row('e1', 'A'), provenance: 'human-curated' }],
      total: 1,
    });

    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));
    fireEvent.click(screen.getByText('A'));

    const confirm = await screen.findByRole('button', { name: /^confirm$/i });
    expect(confirm).toBeDisabled();
  });

  it('flags edge-proposal-fallback mentions as "Inferred context" so curators can tell them apart', async () => {
    // Concept nodes auto-created as edge endpoints have no chunk_entity_mentions
    // rows; the server falls back to chunks on the originating proposal's
    // source page and tags them extractor="edge-proposal-fallback". The UI
    // must mark these so curators don't mistake them for real attribution.
    api.knowledge.getNodeMentions.mockImplementation((id) => {
      if (id === 'e1-s') {
        return Promise.resolve({
          mentions: [
            {
              chunk_id: 'c-fb',
              page_name: 'PhilosophyHub',
              chunk_index: 1,
              heading_path: ['Schools'],
              text: 'Confucianism is a system of ethical and philosophical thought.',
              confidence: 1.0,
              extractor: 'edge-proposal-fallback',
            },
          ],
        });
      }
      return Promise.resolve({ mentions: [] });
    });

    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));
    fireEvent.click(screen.getByText('A'));
    await screen.findByRole('button', { name: /^edit$/i });

    // The fallback row carries a data-testid and an "Inferred context" tag.
    const fallback = await screen.findByTestId('mention-fallback');
    expect(fallback).toBeInTheDocument();
    expect(fallback.textContent.toLowerCase()).toContain('inferred context');
    // No real-attribution row got rendered for this node.
    expect(screen.queryByTestId('mention-attributed')).toBeNull();
  });

  it('renders chunk body as Markdown while still highlighting the entity name', async () => {
    // Chunks are stored as Markdown — bold/italic/code/links/lists must
    // render, not show as literal characters. The entity name highlight has
    // to survive Markdown processing (via a rehype plugin that walks the AST
    // and wraps matches in <mark>), skipping <code>/<pre> so identifiers
    // aren't mangled.
    api.knowledge.getNodeMentions.mockImplementation((id) => {
      if (id === 'e1-s') {
        return Promise.resolve({
          mentions: [
            {
              chunk_id: 'c-md',
              page_name: 'KubernetesDeep',
              chunk_index: 4,
              heading_path: ['Probes', 'Readiness'],
              text:
                '**Important:** the `readinessProbe` is *not* the same as the liveness probe.\n\n'
                + 'A node-e1-s reference appears here and should be highlighted.\n\n'
                + '- node-e1-s is mentioned in a bullet too\n'
                + '- the `node-e1-s` code span should not be highlighted',
              confidence: 0.95,
              extractor: 'gemma4-assist:latest',
            },
          ],
        });
      }
      return Promise.resolve({ mentions: [] });
    });

    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));
    fireEvent.click(screen.getByText('A'));
    await screen.findByRole('button', { name: /^edit$/i });

    // Markdown structure: bold renders as <strong>, code as <code>, list as <li>.
    await waitFor(() =>
      expect(screen.getByText('Important:').tagName.toLowerCase()).toBe('strong'),
    );
    expect(screen.getByText('readinessProbe').tagName.toLowerCase()).toBe('code');
    // At least one <li> from the bullet list.
    expect(document.querySelector('.mention-chunk-body li')).toBeTruthy();

    // Highlight: the entity name (node-A) should be wrapped in <mark> in
    // prose, but NOT inside the inline `code-block` span.
    const marks = document.querySelectorAll('.mention-chunk-body mark');
    expect(marks.length).toBeGreaterThanOrEqual(2);
    Array.from(marks).forEach((el) => {
      expect(el.textContent.toLowerCase()).toBe('node-e1-s');
      // Walk ancestors to make sure no <code> wraps a <mark>.
      let p = el.parentElement;
      while (p && p.classList && !p.classList.contains('mention-chunk-body')) {
        expect(p.tagName.toLowerCase()).not.toBe('code');
        p = p.parentElement;
      }
    });
    // The code span '`node-e1-s`' must render as <code> with its text intact
    // and unhighlighted — exactly one such code element.
    const codeNodes = Array.from(
      document.querySelectorAll('.mention-chunk-body code'),
    ).filter((c) => c.textContent === 'node-e1-s');
    expect(codeNodes.length).toBe(1);
    expect(codeNodes[0].querySelector('mark')).toBeNull();
  });

  it('renders source and target mentions with target=_blank page links', async () => {
    // Mentions panel pulls the surrounding chunk text so curators can
    // disambiguate acronyms/initialisms. Each row links to the host page,
    // shows the heading breadcrumb, and the entity name is bolded inside the
    // chunk text via <mark>.
    api.knowledge.getNodeMentions.mockImplementation((id) => {
      if (id === 'e1-s') {
        return Promise.resolve({
          mentions: [
            {
              chunk_id: 'c-src-1',
              page_name: 'AutomatedStorageAndRetrieval',
              chunk_index: 2,
              heading_path: ['AS/RS', 'Stacker cranes'],
              text: 'A stacker crane travels the aisles of the AS/RS, retrieving pallets.',
              confidence: 0.91,
              extractor: 'gemma4-assist:latest',
            },
          ],
        });
      }
      if (id === 'e1-t') {
        return Promise.resolve({
          mentions: [
            {
              chunk_id: 'c-tgt-1',
              page_name: 'StackerCrane',
              chunk_index: 0,
              heading_path: [],
              text: 'A stacker crane is a vertically reciprocating storage machine.',
              confidence: 0.88,
              extractor: 'gemma4-assist:latest',
            },
          ],
        });
      }
      return Promise.resolve({ mentions: [] });
    });

    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));
    fireEvent.click(screen.getByText('A'));
    // Wait for the detail pane to load by selecting the Edit button.
    await screen.findByRole('button', { name: /^edit$/i });

    // The mentions section names both endpoints.
    await waitFor(() => screen.getByText(/source mentions/i));
    expect(screen.getByText(/target mentions/i)).toBeInTheDocument();

    // Page-name links open in a new tab — defence in depth so a curator
    // accidentally clicking doesn't lose their place in the explorer.
    const srcLink = screen.getByRole('link', { name: /AutomatedStorageAndRetrieval/i });
    expect(srcLink).toHaveAttribute('target', '_blank');
    expect(srcLink).toHaveAttribute('rel', expect.stringContaining('noopener'));
    expect(screen.getByRole('link', { name: /StackerCrane/i })).toHaveAttribute(
      'target',
      '_blank',
    );

    // Chunk text rendered with the entity name highlighted.
    expect(screen.getByText(/travels the aisles/i)).toBeInTheDocument();
    expect(screen.getByText(/vertically reciprocating/i)).toBeInTheDocument();
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
