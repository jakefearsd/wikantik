import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

const MOCK_SNAPSHOT = {
  generatedAt: '2026-05-02T20:00:00Z',
  nodeCount: 2,
  edgeCount: 1,
  hubDegreeThreshold: 10,
  nodes: [
    { id: 'aaa', name: 'Alan Turing', type: 'person',  role: 'normal', provenance: 'AI_INFERRED', sourcePage: 'AlanTuring',  degreeIn: 1, degreeOut: 0, restricted: false, cluster: null, tags: [], status: 'active' },
    { id: 'bbb', name: 'Computability', type: 'concept', role: 'normal', provenance: 'AI_INFERRED', sourcePage: 'Computability', degreeIn: 0, degreeOut: 1, restricted: false, cluster: null, tags: [], status: 'active' },
  ],
  edges: [
    { id: 'e1', source: 'bbb', target: 'aaa', relationshipType: 'mentioned_with', provenance: 'AI_INFERRED' },
  ],
};

vi.mock('../../api/client', () => ({
  api: {
    knowledge: {
      getGraphSnapshot: vi.fn(),
    },
  },
}));

vi.mock('../pagegraph/GraphCanvas.jsx', () => ({
  default: ({ elements }) => <div data-testid="graph-canvas">Canvas: {elements.nodes.length} nodes</div>,
}));

import { api } from '../../api/client';
import KnowledgeGraphView from './KnowledgeGraphView.jsx';

describe('KnowledgeGraphView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows loading then canvas on success', async () => {
    api.knowledge.getGraphSnapshot.mockResolvedValue(MOCK_SNAPSHOT);
    render(<MemoryRouter initialEntries={['/knowledge-graph']}><KnowledgeGraphView /></MemoryRouter>);
    expect(screen.getByText(/loading/i)).toBeTruthy();
    await waitFor(() => expect(screen.getByTestId('graph-canvas')).toBeTruthy());
  });

  it('calls getGraphSnapshot with no minTier on first mount', async () => {
    api.knowledge.getGraphSnapshot.mockResolvedValue(MOCK_SNAPSHOT);
    render(<MemoryRouter initialEntries={['/knowledge-graph']}><KnowledgeGraphView /></MemoryRouter>);
    await waitFor(() => expect(api.knowledge.getGraphSnapshot).toHaveBeenCalled());
    const args = api.knowledge.getGraphSnapshot.mock.calls[0][0] || {};
    expect(args.minTier).toBeUndefined();
  });

  it('shows 401 error variant for unauthorized', async () => {
    api.knowledge.getGraphSnapshot.mockRejectedValue(Object.assign(new Error('Unauthorized'), { status: 401 }));
    render(<MemoryRouter initialEntries={['/knowledge-graph']}><KnowledgeGraphView /></MemoryRouter>);
    await waitFor(() => expect(screen.getByText('Sign in to view the knowledge graph.')).toBeTruthy());
  });

  it('shows server error for 5xx', async () => {
    api.knowledge.getGraphSnapshot.mockRejectedValue(Object.assign(new Error('Server error'), { status: 500 }));
    render(<MemoryRouter initialEntries={['/knowledge-graph']}><KnowledgeGraphView /></MemoryRouter>);
    await waitFor(() => expect(screen.getByText(/unavailable/i)).toBeTruthy());
  });

  it('shows empty state when nodeCount is 0', async () => {
    api.knowledge.getGraphSnapshot.mockResolvedValue({ ...MOCK_SNAPSHOT, nodeCount: 0, nodes: [], edges: [] });
    render(<MemoryRouter initialEntries={['/knowledge-graph']}><KnowledgeGraphView /></MemoryRouter>);
    await waitFor(() => expect(screen.getByText(/empty/i)).toBeTruthy());
  });
});
