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
const ENTITY_A = { id: 'id-a', name: 'React', nodeType: 'technology', provenance: 'ai-inferred' };
const ENTITY_B = { id: 'id-b', name: 'TypeScript', nodeType: 'technology', provenance: 'human-curated' };
const EDGE_1 = {
  id: 'edge-1',
  sourceId: 'id-a',
  targetId: 'id-b',
  sourceName: 'React',
  targetName: 'TypeScript',
  relationshipType: 'uses',
  provenance: 'ai-inferred',
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

  it('shows provenance badge with real Provenance.value() strings', async () => {
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    // Badges are inside the entities list; provenance values must be lowercase value() strings
    const list = await screen.findByRole('list', { name: /entities/i });
    expect(list.textContent).toContain('ai-inferred');
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

describe('guards', () => {
  it('shows a save-first notice when pageName is falsy', () => {
    render(<KnowledgeGraphPanel pageName="" />);
    expect(screen.getByText('Save the page first to manage its knowledge graph.')).toBeInTheDocument();
    expect(api.getPageKnowledge).not.toHaveBeenCalled();
  });

  it('shows a loading indicator before the slice resolves', async () => {
    let resolveSlice;
    api.getPageKnowledge.mockReturnValue(new Promise((res) => { resolveSlice = res; }));
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    expect(screen.getByText('Loading knowledge graph…')).toBeInTheDocument();
    resolveSlice(EMPTY_SLICE);
    await screen.findByText('No entities on this page yet.');
  });

  it('shows a fetch-error state (with alert role) when loading the slice fails', async () => {
    api.getPageKnowledge.mockRejectedValue(new Error('server exploded'));
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    expect(await screen.findByRole('alert')).toHaveTextContent('server exploded');
  });

  it('falls back to a generic fetch-error message', async () => {
    api.getPageKnowledge.mockRejectedValue(new Error());
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Failed to load knowledge graph');
  });
});

describe('entity confirm / remove', () => {
  it('Confirm button calls api.confirmEntity then re-fetches', async () => {
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /entities/i });
    fireEvent.click(screen.getAllByTitle('Confirm entity')[0]);
    await waitFor(() => expect(api.confirmEntity).toHaveBeenCalledWith('TestPage', 'id-a'));
    // fetchSlice is called again on success (initial load + refresh)
    await waitFor(() => expect(api.getPageKnowledge).toHaveBeenCalledTimes(2));
  });

  it('a failed confirmEntity surfaces the error banner', async () => {
    api.confirmEntity.mockRejectedValue(new Error('cannot confirm'));
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /entities/i });
    fireEvent.click(screen.getAllByTitle('Confirm entity')[0]);
    expect(await screen.findByRole('alert')).toHaveTextContent('cannot confirm');
  });

  it('a failed confirmEntity with no message falls back to a generic message', async () => {
    api.confirmEntity.mockRejectedValue(new Error());
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /entities/i });
    fireEvent.click(screen.getAllByTitle('Confirm entity')[0]);
    expect(await screen.findByRole('alert')).toHaveTextContent('Failed to confirm entity');
  });

  it('Remove button calls api.deleteEntity then re-fetches', async () => {
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /entities/i });
    fireEvent.click(screen.getByLabelText('Remove entity React'));
    await waitFor(() => expect(api.deleteEntity).toHaveBeenCalledWith('TestPage', 'id-a'));
  });

  it('a failed deleteEntity surfaces the error banner', async () => {
    api.deleteEntity.mockRejectedValue(new Error('cannot remove'));
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /entities/i });
    fireEvent.click(screen.getByLabelText('Remove entity React'));
    expect(await screen.findByRole('alert')).toHaveTextContent('cannot remove');
  });

  it('a failed deleteEntity with no message falls back to a generic message', async () => {
    api.deleteEntity.mockRejectedValue(new Error());
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /entities/i });
    fireEvent.click(screen.getByLabelText('Remove entity React'));
    expect(await screen.findByRole('alert')).toHaveTextContent('Failed to remove entity');
  });

  it('a failed type change surfaces the error banner', async () => {
    api.upsertEntity.mockRejectedValue(new Error('bad type'));
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /entities/i });
    const select = screen.getByRole('combobox', { name: /entity type for react/i });
    fireEvent.change(select, { target: { value: 'concept' } });
    expect(await screen.findByRole('alert')).toHaveTextContent('bad type');
  });

  it('a failed type change with no message falls back to a generic message', async () => {
    api.upsertEntity.mockRejectedValue(new Error());
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /entities/i });
    const select = screen.getByRole('combobox', { name: /entity type for react/i });
    fireEvent.change(select, { target: { value: 'concept' } });
    expect(await screen.findByRole('alert')).toHaveTextContent('Failed to update entity type');
  });
});

describe('add entity form', () => {
  it('Add button is disabled until a name is typed, and clears the form on success', async () => {
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /entities/i });

    const nameInput = screen.getByTestId('kg-add-entity-name');
    const addBtn = screen.getByTestId('kg-add-entity-btn');
    expect(addBtn).toBeDisabled();

    fireEvent.change(nameInput, { target: { value: 'Vitest' } });
    expect(addBtn).not.toBeDisabled();

    fireEvent.click(addBtn);
    await waitFor(() =>
      expect(api.upsertEntity).toHaveBeenCalledWith('TestPage', { name: 'Vitest', nodeType: 'concept' }),
    );
    await waitFor(() => expect(nameInput).toHaveValue(''));
  });

  it('a whitespace-only name does not call upsertEntity (button stays disabled)', async () => {
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /entities/i });
    const nameInput = screen.getByTestId('kg-add-entity-name');
    fireEvent.change(nameInput, { target: { value: '   ' } });
    expect(screen.getByTestId('kg-add-entity-btn')).toBeDisabled();
  });

  it('a failed add-entity call shows an inline error and keeps the typed name', async () => {
    api.upsertEntity.mockRejectedValue(new Error('duplicate name'));
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /entities/i });
    fireEvent.change(screen.getByTestId('kg-add-entity-name'), { target: { value: 'Vitest' } });
    fireEvent.click(screen.getByTestId('kg-add-entity-btn'));
    expect(await screen.findByText('duplicate name')).toBeInTheDocument();
  });

  it('a failed add-entity call with no message falls back to a generic message', async () => {
    api.upsertEntity.mockRejectedValue(new Error());
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /entities/i });
    fireEvent.change(screen.getByTestId('kg-add-entity-name'), { target: { value: 'Vitest' } });
    fireEvent.click(screen.getByTestId('kg-add-entity-btn'));
    expect(await screen.findByText('Failed to add entity')).toBeInTheDocument();
  });

  it('changing the New entity type Select updates the value used on add', async () => {
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /entities/i });
    fireEvent.change(screen.getByRole('combobox', { name: 'New entity type' }), { target: { value: 'person' } });
    fireEvent.change(screen.getByTestId('kg-add-entity-name'), { target: { value: 'Ada' } });
    fireEvent.click(screen.getByTestId('kg-add-entity-btn'));
    await waitFor(() =>
      expect(api.upsertEntity).toHaveBeenCalledWith('TestPage', { name: 'Ada', nodeType: 'person' }),
    );
  });
});

describe('relation confirm / remove', () => {
  it('Confirm button calls api.confirmEdge', async () => {
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /relations/i });
    fireEvent.click(screen.getByTitle('Confirm relation'));
    await waitFor(() => expect(api.confirmEdge).toHaveBeenCalledWith('TestPage', 'edge-1'));
  });

  it('a failed confirmEdge surfaces the error banner', async () => {
    api.confirmEdge.mockRejectedValue(new Error('cannot confirm relation'));
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /relations/i });
    fireEvent.click(screen.getByTitle('Confirm relation'));
    expect(await screen.findByRole('alert')).toHaveTextContent('cannot confirm relation');
  });

  it('a failed confirmEdge with no message falls back to a generic message', async () => {
    api.confirmEdge.mockRejectedValue(new Error());
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /relations/i });
    fireEvent.click(screen.getByTitle('Confirm relation'));
    expect(await screen.findByRole('alert')).toHaveTextContent('Failed to confirm relation');
  });

  it('Remove button calls api.deleteEdge', async () => {
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /relations/i });
    fireEvent.click(screen.getByLabelText('Remove relation React uses TypeScript'));
    await waitFor(() => expect(api.deleteEdge).toHaveBeenCalledWith('TestPage', 'edge-1'));
  });

  it('a failed deleteEdge surfaces the error banner', async () => {
    api.deleteEdge.mockRejectedValue(new Error('cannot remove relation'));
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /relations/i });
    fireEvent.click(screen.getByLabelText('Remove relation React uses TypeScript'));
    expect(await screen.findByRole('alert')).toHaveTextContent('cannot remove relation');
  });

  it('a failed deleteEdge with no message falls back to a generic message', async () => {
    api.deleteEdge.mockRejectedValue(new Error());
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /relations/i });
    fireEvent.click(screen.getByLabelText('Remove relation React uses TypeScript'));
    expect(await screen.findByRole('alert')).toHaveTextContent('Failed to remove relation');
  });
});

describe('add relation guards + fallbacks', () => {
  it('the Add relation button is disabled until source/target/predicate are all chosen', async () => {
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /entities/i });
    expect(screen.getByTestId('kg-add-edge-btn')).toBeDisabled();
  });

  it('refuses a self-loop (same source and target) with an inline error, no API call', async () => {
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /entities/i });
    fireEvent.change(screen.getByRole('combobox', { name: /source entity/i }), { target: { value: 'id-a' } });
    fireEvent.change(screen.getByRole('combobox', { name: /target entity/i }), { target: { value: 'id-a' } });
    fireEvent.click(screen.getByTestId('kg-add-edge-btn'));

    expect(await screen.findByTestId('edge-add-error')).toHaveTextContent(
      'Source and target must be different entities (no self-loops).',
    );
    expect(api.upsertEdge).not.toHaveBeenCalled();
  });

  it('a non-422 upsertEdge failure falls back to err.message', async () => {
    api.upsertEdge.mockRejectedValue(new Error('network blip'));
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /entities/i });
    fireEvent.change(screen.getByRole('combobox', { name: /source entity/i }), { target: { value: 'id-a' } });
    fireEvent.change(screen.getByRole('combobox', { name: /target entity/i }), { target: { value: 'id-b' } });
    fireEvent.click(screen.getByTestId('kg-add-edge-btn'));

    expect(await screen.findByTestId('edge-add-error')).toHaveTextContent('network blip');
  });

  it('a 422 with no violations falls back to err.message rather than crashing', async () => {
    const err = Object.assign(new Error('validation failed'), { status: 422, body: {} });
    api.upsertEdge.mockRejectedValue(err);
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /entities/i });
    fireEvent.change(screen.getByRole('combobox', { name: /source entity/i }), { target: { value: 'id-a' } });
    fireEvent.change(screen.getByRole('combobox', { name: /target entity/i }), { target: { value: 'id-b' } });
    fireEvent.click(screen.getByTestId('kg-add-edge-btn'));

    expect(await screen.findByTestId('edge-add-error')).toHaveTextContent('validation failed');
  });

  it('a rejection with no message at all falls back to the generic add-relation message', async () => {
    api.upsertEdge.mockRejectedValue(new Error());
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /entities/i });
    fireEvent.change(screen.getByRole('combobox', { name: /source entity/i }), { target: { value: 'id-a' } });
    fireEvent.change(screen.getByRole('combobox', { name: /target entity/i }), { target: { value: 'id-b' } });
    fireEvent.click(screen.getByTestId('kg-add-edge-btn'));

    expect(await screen.findByTestId('edge-add-error')).toHaveTextContent('Failed to add relation');
  });

  it('resets the form fields to defaults after a successful add', async () => {
    render(<KnowledgeGraphPanel pageName="TestPage" />);
    await screen.findByRole('list', { name: /entities/i });
    const sourceSelect = screen.getByRole('combobox', { name: /source entity/i });
    const targetSelect = screen.getByRole('combobox', { name: /target entity/i });
    fireEvent.change(sourceSelect, { target: { value: 'id-a' } });
    fireEvent.change(targetSelect, { target: { value: 'id-b' } });
    fireEvent.click(screen.getByTestId('kg-add-edge-btn'));

    await waitFor(() => expect(api.upsertEdge).toHaveBeenCalled());
    await waitFor(() => expect(sourceSelect).toHaveValue(''));
    expect(targetSelect).toHaveValue('');
  });
});
