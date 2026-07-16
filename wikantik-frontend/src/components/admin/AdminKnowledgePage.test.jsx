import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

vi.mock('../../hooks/useCapabilities', () => ({ useCapabilities: vi.fn() }));

import AdminKnowledgePage from './AdminKnowledgePage';
import { useCapabilities } from '../../hooks/useCapabilities';

// Every tab mounts a child component that fires API calls on mount. The mock
// below stubs every api.knowledge.* method any of the eight tabs touches so the
// panels render without throwing. Shapes are the minimal happy-path payloads
// each tab needs to get past its loading guard.
vi.mock('../../api/client', () => {
  const ok = (value) => vi.fn().mockResolvedValue(value);
  return {
    api: {
      knowledge: {
        // Proposals tab (ProposalReviewQueue)
        getSchema: ok({
          nodeTypes: [], statusValues: [], relationshipTypes: [],
          stats: {
            nodes: 0, edges: 0, unreviewedProposals: 0,
            pendingBreakdown: {
              total: 0, newNodes: 0, newEdges: 0,
              judgeApproved: 0, judgeAbstained: 0, unjudged: 0,
            },
          },
        }),
        listProposalsFiltered: ok({ proposals: [], total_count: 0 }),
        judgeStatus: ok({ running: false, queue_depth: 0, in_flight: false }),
        // Extraction tab
        getExtractionStatus: ok({ state: 'IDLE', extractorBackend: 'gemma', concurrency: 2 }),
        // Node Explorer (GraphExplorer) + Edge Explorer
        queryNodes: ok({ nodes: [], total: 0 }),
        queryEdges: ok({ edges: [], total: 0 }),
        // Content Embeddings
        getEmbeddingStatus: ok({ ready: true, dimension: 768, mentioned_node_count: 0 }),
        getPagesWithoutFrontmatter: ok({ pages: [], total: 0 }),
        // Hub Proposals
        listHubProposals: ok({ proposals: [], total: 0 }),
        // Hub Discovery
        listHubDiscoveryProposals: ok({ proposals: [], total: 0 }),
        listExistingHubs: ok({ hubs: [] }),
        // LLM Activity
        getLlmActivity: ok({ enabled: true, inFlight: 0, windowMinutes: 60, calls: [] }),
        // Destructive flow
        clearAll: ok({}),
      },
    },
  };
});

import { api } from '../../api/client';

// Per-tab assertion probes. Each returns an element unique to that tab's panel.
const TAB_PROBES = {
  Extraction: () => screen.findByTestId('extraction-header'),
  'Node Explorer': () => screen.findByTestId('kg-schema-header'),
  'Edge Explorer': () => screen.findByRole('button', { name: /new edge/i }),
  'Content Embeddings': () => screen.findByText(/Mention centroid index/i),
  'Hub Proposals': () => screen.findByRole('button', { name: /Generate Hub Proposals/i }),
  'Hub Discovery': () => screen.findByTestId('hub-discovery-tab'),
  'LLM Activity': () => screen.findByText(/in-flight/i),
};

beforeEach(() => {
  vi.clearAllMocks();
  // Fail-open default: capabilities resolved, KG enabled.
  useCapabilities.mockReturnValue({
    capabilities: { knowledgeGraph: true }, loading: false,
  });
});

describe('AdminKnowledgePage capabilities gating (knowledgeGraph flag)', () => {
  it('shows the disabled panel instead of the tabbed UI when knowledgeGraph is false', () => {
    useCapabilities.mockReturnValue({
      capabilities: { knowledgeGraph: false }, loading: false,
    });
    render(<AdminKnowledgePage />);
    // Disabled message cites the flag, ExtractionTab admin-message idiom.
    const message = screen.getByRole('status');
    expect(message).toHaveTextContent(/disabled on this deployment/i);
    expect(message).toHaveTextContent('wikantik.knowledge.enabled=false');
    // None of the tabs (nor the destructive clear-all button) render.
    expect(screen.queryByRole('button', { name: 'Proposals' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Clear all KG data/i })).toBeNull();
  });

  it('renders the tabbed UI while capabilities is still loading (fail-open)', () => {
    useCapabilities.mockReturnValue({
      capabilities: { knowledgeGraph: true }, loading: true,
    });
    render(<AdminKnowledgePage />);
    expect(screen.getByRole('button', { name: 'Proposals' })).toBeInTheDocument();
  });
});

describe('AdminKnowledgePage clarity', () => {
  it('top-right destructive button names its object (KG data), not just "Clear All"', () => {
    render(<AdminKnowledgePage />);
    expect(screen.queryByRole('button', { name: /^Clear All$/ })).toBeNull();
    expect(
      screen.getByRole('button', { name: /Clear all KG data/i }),
    ).toBeInTheDocument();
  });
});

describe('AdminKnowledgePage tab switching', () => {
  it('defaults to the Proposals tab', async () => {
    render(<AdminKnowledgePage />);
    const proposalsTab = screen.getByRole('button', { name: 'Proposals' });
    expect(proposalsTab).toHaveClass('active');
    // The Proposals panel (ProposalReviewQueue) renders its heading after load.
    await screen.findByRole('heading', { name: /Pending Proposals/i });
  });

  it('only the active tab carries the active class', async () => {
    render(<AdminKnowledgePage />);
    const activeButtons = screen
      .getAllByRole('button')
      .filter((b) => b.classList.contains('admin-tab') && b.classList.contains('active'));
    expect(activeButtons).toHaveLength(1);
    expect(activeButtons[0]).toHaveTextContent('Proposals');
  });

  it.each(Object.keys(TAB_PROBES))(
    'clicking %s activates that tab and mounts its panel',
    async (label) => {
      render(<AdminKnowledgePage />);
      const tab = screen.getByRole('button', { name: label });
      fireEvent.click(tab);
      // The clicked tab is now the active one.
      expect(tab).toHaveClass('active');
      // The previously-default Proposals tab is no longer active.
      expect(screen.getByRole('button', { name: 'Proposals' })).not.toHaveClass('active');
      // Its panel mounts and renders something unique to it.
      await TAB_PROBES[label]();
    },
  );

  it('switching tabs swaps the mounted panel (Proposals panel unmounts)', async () => {
    render(<AdminKnowledgePage />);
    await screen.findByRole('heading', { name: /Pending Proposals/i });
    fireEvent.click(screen.getByRole('button', { name: 'Node Explorer' }));
    await screen.findByTestId('kg-schema-header');
    expect(screen.queryByRole('heading', { name: /Pending Proposals/i })).toBeNull();
  });
});

describe('AdminKnowledgePage clear-all flow', () => {
  let confirmSpy;
  let reloadSpy;
  let alertSpy;
  const originalReload = window.location.reload;

  beforeEach(() => {
    confirmSpy = vi.spyOn(window, 'confirm');
    alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
    // window.location.reload is non-configurable in jsdom in some setups; replace it.
    reloadSpy = vi.fn();
    Object.defineProperty(window, 'location', {
      value: { ...window.location, reload: reloadSpy },
      writable: true,
    });
  });

  afterEach(() => {
    confirmSpy.mockRestore();
    alertSpy.mockRestore();
    Object.defineProperty(window, 'location', {
      value: { ...window.location, reload: originalReload },
      writable: true,
    });
  });

  it('does nothing when the confirm dialog is dismissed', () => {
    confirmSpy.mockReturnValue(false);
    render(<AdminKnowledgePage />);
    fireEvent.click(screen.getByRole('button', { name: /Clear all KG data/i }));
    expect(api.knowledge.clearAll).not.toHaveBeenCalled();
  });

  it('calls clearAll and reloads on confirm', async () => {
    confirmSpy.mockReturnValue(true);
    render(<AdminKnowledgePage />);
    fireEvent.click(screen.getByRole('button', { name: /Clear all KG data/i }));
    await waitFor(() => expect(api.knowledge.clearAll).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(reloadSpy).toHaveBeenCalled());
  });

  it('alerts and does not reload when clearAll fails', async () => {
    confirmSpy.mockReturnValue(true);
    api.knowledge.clearAll.mockRejectedValueOnce(new Error('boom'));
    render(<AdminKnowledgePage />);
    fireEvent.click(screen.getByRole('button', { name: /Clear all KG data/i }));
    await waitFor(() => expect(alertSpy).toHaveBeenCalledWith(expect.stringContaining('boom')));
    expect(reloadSpy).not.toHaveBeenCalled();
    // The button re-enables after the failure (clearing reset in finally).
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /Clear all KG data/i })).not.toBeDisabled(),
    );
  });

  it('shows the disabled "Clearing…" state while the clear is in flight', async () => {
    confirmSpy.mockReturnValue(true);
    let resolveClear;
    api.knowledge.clearAll.mockReturnValueOnce(new Promise((r) => { resolveClear = r; }));
    render(<AdminKnowledgePage />);
    fireEvent.click(screen.getByRole('button', { name: /Clear all KG data/i }));
    const clearingBtn = await screen.findByRole('button', { name: /Clearing…/i });
    expect(clearingBtn).toBeDisabled();
    resolveClear({});
  });
});
