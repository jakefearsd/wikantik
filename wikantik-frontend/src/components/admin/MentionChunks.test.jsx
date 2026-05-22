import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MentionsPanel, makeHighlightRehype } from './MentionChunks';

// MentionChunks.jsx exports MentionsPanel (the fetch+render component), plus the
// pure helpers makeHighlightRehype / CHUNK_COMPONENTS. There is no default
// `MentionChunks` export — the panel is the component under test.

vi.mock('../../api/client', () => ({
  api: {
    knowledge: {
      getNodeMentions: vi.fn(),
    },
  },
}));

import { api } from '../../api/client';

const NODE = { id: 'node-1', name: 'Bitcoin' };

beforeEach(() => {
  vi.clearAllMocks();
  api.knowledge.getNodeMentions.mockResolvedValue({ mentions: [] });
});

describe('MentionsPanel — gating', () => {
  it('renders nothing and does not fetch when node has no id', () => {
    const { container } = render(<MentionsPanel label="Context" node={{}} />);
    expect(container.firstChild).toBeNull();
    expect(api.knowledge.getNodeMentions).not.toHaveBeenCalled();
  });
});

describe('MentionsPanel — fetch and render', () => {
  it('fetches mentions for the node id with the limit', async () => {
    render(<MentionsPanel label="Source" node={NODE} limit={5} />);
    await waitFor(() =>
      expect(api.knowledge.getNodeMentions).toHaveBeenCalledWith('node-1', 5)
    );
  });

  it('shows the loading hint before mentions resolve', () => {
    let resolve;
    api.knowledge.getNodeMentions.mockReturnValue(new Promise((r) => { resolve = r; }));
    render(<MentionsPanel label="Context" node={NODE} />);
    expect(screen.getByText('Loading mentions…')).toBeTruthy();
    resolve({ mentions: [] });
  });

  it('renders the empty hint when no mention chunks exist', async () => {
    api.knowledge.getNodeMentions.mockResolvedValue({ mentions: [] });
    render(<MentionsPanel label="Context" node={NODE} />);
    await screen.findByText('No mention chunks recorded for this node.');
  });

  it('renders mention chunks with page name, heading path, confidence, and body', async () => {
    api.knowledge.getNodeMentions.mockResolvedValue({
      mentions: [
        {
          chunk_id: 'c1',
          page_name: 'WhitepaperPage',
          heading_path: ['Overview', 'History'],
          confidence: 0.91,
          extractor: 'gemma4-assist',
          text: 'A decentralized digital currency.',
        },
      ],
    });
    render(<MentionsPanel label="Source" node={NODE} />);
    // Header includes the count.
    await screen.findByText(/Source mentions \(1\)/);
    expect(screen.getByText('WhitepaperPage')).toBeTruthy();
    expect(screen.getByText(/Overview › History/)).toBeTruthy();
    expect(screen.getByText(/conf 0\.91/)).toBeTruthy();
    expect(screen.getByText(/A decentralized digital currency/)).toBeTruthy();
    // Solid border / attributed (non-fallback) chunk testid.
    expect(screen.getByTestId('mention-attributed')).toBeTruthy();
  });

  it('marks fallback chunks with the inferred-context affordance', async () => {
    api.knowledge.getNodeMentions.mockResolvedValue({
      mentions: [
        {
          chunk_id: 'c2',
          page_name: 'Bitcoin',
          heading_path: [],
          confidence: null,
          extractor: 'edge-proposal-fallback',
          text: 'fallback text',
        },
      ],
    });
    render(<MentionsPanel label="Context" node={NODE} />);
    await screen.findByTestId('mention-fallback');
    expect(screen.getByText('Inferred context')).toBeTruthy();
    // Null confidence renders the em-dash placeholder.
    expect(screen.getByText(/conf —/)).toBeTruthy();
  });
});

describe('MentionsPanel — error', () => {
  it('renders an error message when the fetch rejects', async () => {
    api.knowledge.getNodeMentions.mockRejectedValue(new Error('mentions down'));
    render(<MentionsPanel label="Context" node={NODE} />);
    await screen.findByText('mentions down');
  });
});

describe('makeHighlightRehype — pure transformer', () => {
  it('returns an identity transformer (no-op) when needle is empty', () => {
    const tree = { type: 'root', children: [{ type: 'text', value: 'hello' }] };
    makeHighlightRehype('')()(tree);
    // Unchanged.
    expect(tree.children[0]).toEqual({ type: 'text', value: 'hello' });
  });

  it('wraps case-insensitive matches in <mark> elements, splitting surrounding text', () => {
    const tree = {
      type: 'root',
      children: [
        { type: 'element', tagName: 'p', properties: {}, children: [
          { type: 'text', value: 'A Bitcoin and another bitcoin.' },
        ]},
      ],
    };
    makeHighlightRehype('Bitcoin')()(tree);
    const p = tree.children[0];
    const marks = p.children.filter((c) => c.type === 'element' && c.tagName === 'mark');
    expect(marks).toHaveLength(2);
    expect(marks[0].children[0].value).toBe('Bitcoin');
    // Second match preserves the original (lowercase) casing.
    expect(marks[1].children[0].value).toBe('bitcoin');
  });

  it('does not mark text inside <code> nodes', () => {
    const tree = {
      type: 'root',
      children: [
        { type: 'element', tagName: 'code', properties: {}, children: [
          { type: 'text', value: 'bitcoin' },
        ]},
      ],
    };
    makeHighlightRehype('bitcoin')()(tree);
    const code = tree.children[0];
    expect(code.children[0]).toEqual({ type: 'text', value: 'bitcoin' });
  });
});
