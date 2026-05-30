import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

// Mock Cytoscape so JSDOM doesn't choke on canvas
vi.mock('react-cytoscapejs', () => ({
  default: Object.assign(
    () => <canvas data-testid="cytoscape-mock" />,
    { normalizeElements: (els) => els },
  ),
}));
vi.mock('cytoscape-cose-bilkent', () => ({ default: {} }));
vi.mock('cytoscape', () => ({ default: Object.assign(() => ({}), { use: () => {} }) }));

import GraphCanvas from './GraphCanvas.jsx';

const ELEMENTS = {
  nodes: [
    { data: { id: 'a', cluster: 'math', label: 'A', role: 'normal', degreeIn: 1 } },
    { data: { id: 'b', cluster: 'math', label: 'B', role: 'normal', degreeIn: 0 } },
    { data: { id: 'c', cluster: 'science', label: 'C', role: 'hub', degreeIn: 5 } },
  ],
  edges: [
    { data: { id: 'e1' } },
    { data: { id: 'e2' } },
  ],
};

const NOOP = () => {};

describe('GraphCanvas a11y', () => {
  it('wraps canvas in an element with role="img"', () => {
    render(
      <GraphCanvas
        elements={ELEMENTS}
        selectedId={null}
        hiddenEdgeTypes={new Set()}
        onlyAnomalies={false}
        focusNodeId={null}
        onNodeClick={NOOP}
        onBackgroundClick={NOOP}
        onReady={NOOP}
        onLayoutTimeout={NOOP}
      />,
    );
    expect(screen.getByRole('img')).toBeTruthy();
  });

  it('aria-label contains node count, edge count, and cluster count', () => {
    render(
      <GraphCanvas
        elements={ELEMENTS}
        selectedId={null}
        hiddenEdgeTypes={new Set()}
        onlyAnomalies={false}
        focusNodeId={null}
        onNodeClick={NOOP}
        onBackgroundClick={NOOP}
        onReady={NOOP}
        onLayoutTimeout={NOOP}
      />,
    );
    const label = screen.getByRole('img').getAttribute('aria-label');
    expect(label).toContain('3 pages');
    expect(label).toContain('2 links');
    expect(label).toContain('2 clusters');
  });

  it('renders a visually-hidden sr-only summary paragraph', () => {
    render(
      <GraphCanvas
        elements={ELEMENTS}
        selectedId={null}
        hiddenEdgeTypes={new Set()}
        onlyAnomalies={false}
        focusNodeId={null}
        onNodeClick={NOOP}
        onBackgroundClick={NOOP}
        onReady={NOOP}
        onLayoutTimeout={NOOP}
      />,
    );
    const srOnly = document.querySelector('.sr-only');
    expect(srOnly).toBeTruthy();
    expect(srOnly.textContent).toContain('3 pages');
  });
});
