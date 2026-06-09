import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

// ── Module mock (hoisted) ───────────────────────────────────────────────────
vi.mock('../../api/client', () => ({
  api: {
    getPageKnowledge: vi.fn(),
    upsertEntity: vi.fn(),
    confirmEntity: vi.fn(),
    deleteEntity: vi.fn(),
    upsertEdge: vi.fn(),
    confirmEdge: vi.fn(),
    deleteEdge: vi.fn(),
    rejectEdge: vi.fn(),
  },
}));

import KnowledgeGraphPanel from './KnowledgeGraphPanel';
import { api } from '../../api/client';

// ── Fixtures ────────────────────────────────────────────────────────────────
const ENTITY_A = { id: 'id-a', name: 'React', nodeType: 'technology', provenance: 'machine' };
const ENTITY_B = { id: 'id-b', name: 'TypeScript', nodeType: 'technology', provenance: 'human-curated' };
const EDGE_1 = {
  id: 'edge-1',
  sourceId: 'id-a',
  targetId: 'id-b',
  sourceName: 'React',
  targetName: 'TypeScript',
  relationshipType: 'uses',
  provenance: 'machine',
};

const EMPTY_SLICE = { entities: [], edges: [] };
const FULL_SLICE = { entities: [ENTITY_A, ENTITY_B], edges: [EDGE_1] };

beforeEach(() => {
  vi.clearAllMocks();
  api.getPageKnowledge.mockResolvedValue(FULL_SLICE);
  api.upsertEntity.mockResolvedValue({ ok: true, nodeId: 'new-id' });
  api.confirmEntity.mockResolvedValue(null);
  api.deleteEntity.mockResolvedValue(null);
  api.upsertEdge.mockResolvedValue({ ok: true, edgeId: 'new-edge' });
  api.confirmEdge.mockResolvedValue(null);
  api.deleteEdge.mockResolvedValue(null);
  api.rejectEdge.mockResolvedValue(null);
});

// ── Renders entities and relations from a mocked slice ──────────────────────
describe('rendering', () => {
  it('renders entities from the mocked slice', async () => {
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    // Wait for loading to finish; entity names appear as .kg-panel-name spans
    const list = await screen.findByRole('list', { name: /entities/i });
    expect(list).toBeInTheDocument();
    expect(list.querySelector('.kg-panel-name')?.textContent).toBe('React');
  });

  it('renders the relation row with source → predicate → target', async () => {
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    // Wait for the relations list
    const list = await screen.findByRole('list', { name: /relations/i });
    expect(list).toBeInTheDocument();
    expect(list.textContent).toContain('uses');
    expect(list.textContent).toContain('React');
    expect(list.textContent).toContain('TypeScript');
  });

  it('shows empty state for entities when the slice is empty', async () => {
    api.getPageKnowledge.mockResolvedValue(EMPTY_SLICE);
    render(<KnowledgeGraphPanel pageName="EmptyPage" />);
    await screen.findByText('No entities on this page yet.');
    expect(screen.getByText('No relations on this page yet.')).toBeInTheDocument();
  });

  it('shows provenance badge', async () => {
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    // Badges are inside the entities list
    const list = await screen.findByRole('list', { name: /entities/i });
    expect(list.textContent).toContain('machine');
    expect(list.textContent).toContain('human-curated');
  });
});

// ── Changing entity nodeType calls upsertEntity ─────────────────────────────
describe('entity type change', () => {
  it('calls api.upsertEntity with the new type when the Select changes', async () => {
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    // Wait for the entities list to appear before querying controls
    await screen.findByRole('list', { name: /entities/i });

    // The Select for ENTITY_A has aria-label "Entity type for React"
    const select = screen.getByRole('combobox', { name: /entity type for react/i });
    fireEvent.change(select, { target: { value: 'concept' } });

    await waitFor(() =>
      expect(api.upsertEntity).toHaveBeenCalledWith('TestPage', {
        name: 'React',
        nodeType: 'concept',
      }),
    );
  });
});

// ── Add relation calls upsertEdge ───────────────────────────────────────────
describe('add relation', () => {
  it('calls api.upsertEdge with the chosen source/target/predicate', async () => {
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    // Wait for the entities list (so entity options are populated in the pickers)
    await screen.findByRole('list', { name: /entities/i });

    // Pick source
    const sourceSelect = screen.getByRole('combobox', { name: /source entity/i });
    fireEvent.change(sourceSelect, { target: { value: 'id-a' } });

    // Pick predicate
    const predSelect = screen.getByRole('combobox', { name: /relationship type/i });
    fireEvent.change(predSelect, { target: { value: 'requires' } });

    // Pick target
    const targetSelect = screen.getByRole('combobox', { name: /target entity/i });
    fireEvent.change(targetSelect, { target: { value: 'id-b' } });

    fireEvent.click(screen.getAllByRole('button', { name: /^add$/i }).at(-1));

    await waitFor(() =>
      expect(api.upsertEdge).toHaveBeenCalledWith('TestPage', {
        sourceId: 'id-a',
        targetId: 'id-b',
        relationshipType: 'requires',
      }),
    );
  });
});

// ── 422 SHACL refusal renders the violation message inline ──────────────────
describe('SHACL refusal', () => {
  it('renders violation message inline when upsertEdge rejects with 422', async () => {
    const shaclErr = Object.assign(
      new Error('SHACL violation'),
      {
        status: 422,
        body: {
          error: 'kg_edge_refused',
          violations: [
            { field: 'edge', severity: 'error', code: 'kg.edge.refused', message: 'located_in requires a place target' },
          ],
        },
      },
    );
    api.upsertEdge.mockRejectedValue(shaclErr);

    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /entities/i });

    const sourceSelect = screen.getByRole('combobox', { name: /source entity/i });
    fireEvent.change(sourceSelect, { target: { value: 'id-a' } });
    const targetSelect = screen.getByRole('combobox', { name: /target entity/i });
    fireEvent.change(targetSelect, { target: { value: 'id-b' } });
    const predSelect = screen.getByRole('combobox', { name: /relationship type/i });
    fireEvent.change(predSelect, { target: { value: 'located_in' } });

    fireEvent.click(screen.getAllByRole('button', { name: /^add$/i }).at(-1));

    await screen.findByTestId('edge-add-error');
    expect(screen.getByTestId('edge-add-error').textContent).toContain(
      'located_in requires a place target',
    );
  });
});
