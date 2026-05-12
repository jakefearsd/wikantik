import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import AdminKnowledgePage from './AdminKnowledgePage';

vi.mock('../../api/client', () => ({
  api: {
    knowledge: {
      getSchema: vi.fn().mockResolvedValue({
        nodeTypes: [],
        statusValues: [],
        stats: {
          nodes: 0,
          edges: 0,
          unreviewedProposals: 0,
          pendingBreakdown: {
            total: 0, newNodes: 0, newEdges: 0,
            judgeApproved: 0, judgeAbstained: 0, unjudged: 0,
          },
        },
      }),
      listProposalsFiltered: vi.fn().mockResolvedValue({ proposals: [] }),
      judgeStatus: vi.fn().mockResolvedValue({ running: false }),
    },
  },
}));

describe('AdminKnowledgePage clarity', () => {
  it('top-right destructive button names its object (KG data), not just "Clear All"', () => {
    render(<AdminKnowledgePage />);
    expect(screen.queryByRole('button', { name: /^Clear All$/ })).toBeNull();
    expect(
      screen.getByRole('button', { name: /Clear all KG data/i }),
    ).toBeInTheDocument();
  });
});
