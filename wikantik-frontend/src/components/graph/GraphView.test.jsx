import { describe, it, expect, vi, beforeEach, describe as d2, it as i2, expect as e2, vi as v2, beforeEach as b2 } from 'vitest';
import { fireEvent as f2 } from '@testing-library/react';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

const MOCK_SNAPSHOT = {
  generatedAt: '2026-04-12T14:32:00Z',
  nodeCount: 2,
  edgeCount: 1,
  hubDegreeThreshold: 10,
  nodes: [
    { id: 'aaa', name: 'PageA', type: 'page', role: 'normal', provenance: 'HUMAN_AUTHORED', sourcePage: 'PageA', degreeIn: 1, degreeOut: 0, restricted: false },
    { id: 'bbb', name: 'PageB', type: 'page', role: 'normal', provenance: 'HUMAN_AUTHORED', sourcePage: 'PageB', degreeIn: 0, degreeOut: 1, restricted: false },
  ],
  edges: [
    { id: 'e1', source: 'bbb', target: 'aaa', relationshipType: 'links_to', provenance: 'HUMAN_AUTHORED' },
  ],
};

vi.mock('../../api/client', () => ({
  api: {
    knowledge: {
      getGraphSnapshot: vi.fn(),
    },
  },
}));

vi.mock('./GraphCanvas.jsx', () => ({
  default: ({ elements }) => <div data-testid="graph-canvas">Canvas: {elements.nodes.length} nodes</div>,
}));

import { api } from '../../api/client';
import GraphView from './GraphView.jsx';

describe('GraphView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows loading then canvas on success', async () => {
    api.knowledge.getGraphSnapshot.mockResolvedValue(MOCK_SNAPSHOT);
    render(<MemoryRouter initialEntries={['/graph']}><GraphView /></MemoryRouter>);
    expect(screen.getByText(/loading/i)).toBeTruthy();
    await waitFor(() => expect(screen.getByTestId('graph-canvas')).toBeTruthy());
  });

  it('shows 401 error for unauthorized', async () => {
    api.knowledge.getGraphSnapshot.mockRejectedValue(Object.assign(new Error('Unauthorized'), { status: 401 }));
    render(<MemoryRouter initialEntries={['/graph']}><GraphView /></MemoryRouter>);
    await waitFor(() => expect(screen.getByText('Sign in to view the knowledge graph.')).toBeTruthy());
  });

  it('shows server error for 5xx', async () => {
    api.knowledge.getGraphSnapshot.mockRejectedValue(Object.assign(new Error('Server error'), { status: 500 }));
    render(<MemoryRouter initialEntries={['/graph']}><GraphView /></MemoryRouter>);
    await waitFor(() => expect(screen.getByText(/unavailable/i)).toBeTruthy());
  });

  it('shows empty state for zero nodes', async () => {
    api.knowledge.getGraphSnapshot.mockResolvedValue({ ...MOCK_SNAPSHOT, nodeCount: 0, nodes: [], edges: [] });
    render(<MemoryRouter initialEntries={['/graph']}><GraphView /></MemoryRouter>);
    await waitFor(() => expect(screen.getByText(/empty/i)).toBeTruthy());
  });

  it('shows empty-for-you when all nodes restricted', async () => {
    api.knowledge.getGraphSnapshot.mockResolvedValue({
      ...MOCK_SNAPSHOT,
      nodes: [{ id: 'x', name: null, role: 'restricted', restricted: true, degreeIn: 0, degreeOut: 0 }],
    });
    render(<MemoryRouter initialEntries={['/graph']}><GraphView /></MemoryRouter>);
    await waitFor(() => expect(screen.getByText(/don't have permission/i)).toBeTruthy());
  });
});

d2('GraphView with FilterPanel', () => {
  const snap = {
    generatedAt: new Date().toISOString(), nodeCount: 2, edgeCount: 1,
    hubDegreeThreshold: 10,
    nodes: [
      { id: 'a', name: 'A', type: 'hub', role: 'hub', restricted: false, cluster: 'math', tags: [], status: 'active', degreeIn: 0, degreeOut: 1, provenance: 'HUMAN_AUTHORED', sourcePage: 'A' },
      { id: 'b', name: 'B', type: 'article', role: 'normal', restricted: false, cluster: 'math', tags: [], status: 'active', degreeIn: 1, degreeOut: 0, provenance: 'HUMAN_AUTHORED', sourcePage: 'B' },
    ],
    edges: [{ id: 'e1', source: 'a', target: 'b', relationshipType: 'links_to', provenance: 'HUMAN_AUTHORED' }],
  };

  b2(() => { v2.clearAllMocks(); });

  i2('renders FilterPanel and writes preset param to URL on preset click', async () => {
    api.knowledge.getGraphSnapshot.mockResolvedValue(snap);
    const replace = v2.spyOn(window.history, 'replaceState');
    render(<MemoryRouter initialEntries={['/graph']}><GraphView /></MemoryRouter>);
    await waitFor(() => expect(screen.getByRole('button', { name: /backbone/i })).toBeInTheDocument());
    f2.click(screen.getByRole('button', { name: /backbone/i }));
    await waitFor(() => {
      const calls = replace.mock.calls.map(c => c[2] || '');
      expect(calls.some(url => url.includes('preset=backbone'))).toBe(true);
    });
  });
});
