import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import NodeDetail from './NodeDetail';

vi.mock('../../api/client', () => ({
  api: {
    knowledge: {
      getSimilarNodes: vi.fn(),
      getNodeMentions: vi.fn(),
      deleteNode: vi.fn(),
    },
  },
}));
import { api } from '../../api/client';

const baseNode = {
  id: 'n-confucianism',
  name: 'Confucianism',
  node_type: 'concept',
  provenance: 'ai-inferred',
  is_stub: false,
  properties: {},
  edges: [],
};

const edge = (over) => ({
  id: 'e?',
  relationship_type: 'contains',
  provenance: 'ai-inferred',
  source_id: baseNode.id,
  target_id: 'other',
  source_name: baseNode.name,
  target_name: 'Other',
  ...over,
});

beforeEach(() => {
  Object.values(api.knowledge).forEach((fn) => fn.mockReset?.());
  api.knowledge.getSimilarNodes.mockResolvedValue({ similar: [] });
  api.knowledge.getNodeMentions.mockResolvedValue({ mentions: [] });
  api.knowledge.deleteNode.mockResolvedValue({ deleted: true });
});

describe('NodeDetail mentions panel', () => {
  it('shows the mentions loading hint before the fetch resolves', async () => {
    let resolve;
    api.knowledge.getNodeMentions.mockReturnValue(new Promise((r) => { resolve = r; }));

    render(<NodeDetail node={baseNode} />);
    // While the mention fetch is pending, the panel shows the loading hint.
    expect(await screen.findByText(/Loading mentions/i)).toBeInTheDocument();
    expect(screen.queryByText(/No mention chunks recorded/i)).toBeNull();

    resolve({ mentions: [] });
    expect(await screen.findByText(/No mention chunks recorded/i)).toBeInTheDocument();
    expect(screen.queryByText(/Loading mentions/i)).toBeNull();
  });

  it('fetches mentions by node ID and renders chunk markdown', async () => {
    api.knowledge.getNodeMentions.mockResolvedValueOnce({
      mentions: [
        {
          chunk_id: 'c1',
          page_name: 'EasternPhilosophy',
          heading_path: ['Schools'],
          text: 'A primary school is **Confucianism**, founded by Confucius.',
          confidence: 0.92,
          extractor: 'gemma4-assist',
        },
      ],
    });
    render(<NodeDetail node={baseNode} />);
    await waitFor(() =>
      expect(api.knowledge.getNodeMentions).toHaveBeenCalledWith(
        'n-confucianism',
        3,
      ),
    );
    expect(await screen.findByText(/founded by Confucius/i)).toBeInTheDocument();
  });

  it('renders the fallback badge when the only mentions come from the proposal page', async () => {
    api.knowledge.getNodeMentions.mockResolvedValueOnce({
      mentions: [
        {
          chunk_id: 'cf1',
          page_name: 'EasternPhilosophy',
          heading_path: [],
          text: 'Confucianism is a school of thought.',
          confidence: 0.5,
          extractor: 'edge-proposal-fallback',
        },
      ],
    });
    render(<NodeDetail node={baseNode} />);
    expect(await screen.findByTestId('mention-fallback')).toBeInTheDocument();
  });

  it('shows the empty-mentions hint when nothing is returned', async () => {
    render(<NodeDetail node={baseNode} />);
    expect(
        await screen.findByText(/No mention chunks recorded/i),
      ).toBeInTheDocument();
  });
});

describe('NodeDetail deletion impact', () => {
  it('shows the edge-removal note with the combined edge count', () => {
    const node = {
      ...baseNode,
      edges: [
        edge({ id: 'e1', source_id: baseNode.id, target_id: 't1', target_name: 'T1' }),
        edge({ id: 'e2', source_id: 's2', target_id: baseNode.id, source_name: 'S2' }),
      ],
    };
    render(<NodeDetail node={node} />);
    const note = screen.getByTestId('node-delete-impact');
    expect(note.textContent).toMatch(/2\s*edges?/i);
  });

  it('omits the edge-removal note when the node has no edges', () => {
    render(<NodeDetail node={baseNode} />);
    expect(screen.queryByTestId('node-delete-impact')).toBeNull();
  });

  it('cites the edge count in the confirm prompt before deleting', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const node = {
      ...baseNode,
      edges: [
        edge({ id: 'e1', source_id: baseNode.id, target_id: 't1', target_name: 'T1' }),
      ],
    };
    render(<NodeDetail node={node} />);
    fireEvent.click(screen.getByRole('button', { name: /^delete$/i }));
    expect(confirmSpy).toHaveBeenCalled();
    expect(confirmSpy.mock.calls[0][0]).toMatch(/1\s+edge/i);
    confirmSpy.mockRestore();
  });
});
