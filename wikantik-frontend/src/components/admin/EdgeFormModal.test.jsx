import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import EdgeFormModal from './EdgeFormModal';

vi.mock('../../api/client', () => ({
  api: {
    knowledge: {
      queryNodes: vi.fn(),
      upsertEdge: vi.fn(),
    },
  },
}));
import { api } from '../../api/client';

const relTypes = ['related', 'depends_on', 'part_of'];

describe('EdgeFormModal', () => {
  beforeEach(() => {
    api.knowledge.queryNodes.mockReset();
    api.knowledge.upsertEdge.mockReset();
  });

  it('renders dropdown populated from relTypes', () => {
    render(<EdgeFormModal mode="create" relTypes={relTypes} onClose={() => {}} onSaved={() => {}} />);
    relTypes.forEach((t) => expect(screen.getByRole('option', { name: t })).toBeInTheDocument());
  });

  it('disables Save when properties JSON is invalid', () => {
    render(<EdgeFormModal mode="create" relTypes={relTypes} onClose={() => {}} onSaved={() => {}} />);
    const props = screen.getByLabelText(/properties/i);
    fireEvent.change(props, { target: { value: 'not-json' } });
    expect(screen.getByRole('button', { name: /save/i })).toBeDisabled();
  });

  it('disables Save while source or target is unset', () => {
    render(<EdgeFormModal mode="create" relTypes={relTypes} onClose={() => {}} onSaved={() => {}} />);
    fireEvent.change(screen.getByLabelText(/relationship/i), { target: { value: 'related' } });
    expect(screen.getByRole('button', { name: /save/i })).toBeDisabled();
  });

  it('submits with parsed properties when create succeeds', async () => {
    const onSaved = vi.fn();
    api.knowledge.upsertEdge.mockResolvedValue({ id: 'edge-1' });
    render(
      <EdgeFormModal
        mode="create"
        relTypes={relTypes}
        initialSource={{ id: 's-1', name: 'NodeA' }}
        initialTarget={{ id: 't-1', name: 'NodeB' }}
        onClose={() => {}}
        onSaved={onSaved}
      />
    );
    fireEvent.change(screen.getByLabelText(/relationship/i), { target: { value: 'related' } });
    fireEvent.change(screen.getByLabelText(/properties/i), { target: { value: '{"weight": 0.8}' } });
    fireEvent.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(api.knowledge.upsertEdge).toHaveBeenCalledWith(
      expect.objectContaining({
        source_id: 's-1',
        target_id: 't-1',
        relationship_type: 'related',
        properties: { weight: 0.8 },
      })
    );
  });

  it('disables source/target fields when in edit mode', () => {
    render(
      <EdgeFormModal
        mode="edit"
        relTypes={relTypes}
        initialEdge={{ id: 'e-1', source_id: 's-1', target_id: 't-1', relationship_type: 'related', properties: {} }}
        initialSource={{ id: 's-1', name: 'NodeA' }}
        initialTarget={{ id: 't-1', name: 'NodeB' }}
        onClose={() => {}}
        onSaved={() => {}}
      />
    );
    expect(screen.getByLabelText('Source')).toBeDisabled();
    expect(screen.getByLabelText('Target')).toBeDisabled();
  });

  it('passes the existing id when submitting in edit mode', async () => {
    api.knowledge.upsertEdge.mockResolvedValue({ id: 'e-1' });
    const onSaved = vi.fn();
    render(
      <EdgeFormModal
        mode="edit"
        relTypes={relTypes}
        initialEdge={{ id: 'e-1', source_id: 's-1', target_id: 't-1', relationship_type: 'related', properties: {} }}
        initialSource={{ id: 's-1', name: 'NodeA' }}
        initialTarget={{ id: 't-1', name: 'NodeB' }}
        onClose={() => {}}
        onSaved={onSaved}
      />
    );
    fireEvent.click(screen.getByRole('button', { name: /save/i }));
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(api.knowledge.upsertEdge).toHaveBeenCalledWith(expect.objectContaining({ id: 'e-1' }));
  });

  it('shows inline error on 409 conflict', async () => {
    api.knowledge.upsertEdge.mockRejectedValue({ status: 409, message: 'Edge already exists' });
    render(
      <EdgeFormModal
        mode="create"
        relTypes={relTypes}
        initialSource={{ id: 's', name: 'A' }}
        initialTarget={{ id: 't', name: 'B' }}
        onClose={() => {}}
        onSaved={() => {}}
      />
    );
    fireEvent.change(screen.getByLabelText(/relationship/i), { target: { value: 'related' } });
    fireEvent.click(screen.getByRole('button', { name: /save/i }));
    expect(await screen.findByText(/already exists/i)).toBeInTheDocument();
  });

  it('calls onClose when Cancel is clicked', () => {
    const onClose = vi.fn();
    render(<EdgeFormModal mode="create" relTypes={relTypes} onClose={onClose} onSaved={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: /cancel/i }));
    expect(onClose).toHaveBeenCalled();
  });
});
