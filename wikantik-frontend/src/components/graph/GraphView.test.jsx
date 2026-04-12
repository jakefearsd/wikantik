import { describe, it, expect, vi, beforeEach } from 'vitest';
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
