import { describe, it, expect } from 'vitest';
import { graphSummary } from './graph-a11y.js';

function makeElements(nodeCount, edgeCount, clusters) {
  const nodes = Array.from({ length: nodeCount }, (_, i) => ({
    data: { id: `n${i}`, cluster: clusters[i % clusters.length] ?? null },
  }));
  const edges = Array.from({ length: edgeCount }, (_, i) => ({ data: { id: `e${i}` } }));
  return { nodes, edges };
}

describe('graphSummary', () => {
  it('reflects node and edge counts and cluster count', () => {
    const els = makeElements(142, 380, ['math', 'science', 'history', 'art', 'bio', 'chem', 'physics']);
    const text = graphSummary(els);
    expect(text).toContain('142 pages');
    expect(text).toContain('380 links');
    expect(text).toContain('7 clusters');
  });

  it('uses singular for 1 page / 1 link / 1 cluster', () => {
    const els = makeElements(1, 1, ['math']);
    const text = graphSummary(els);
    expect(text).toContain('1 page');
    expect(text).toContain('1 link');
    expect(text).toContain('1 cluster');
  });

  it('omits cluster clause when no clusters present', () => {
    const els = makeElements(5, 3, [null]);
    const text = graphSummary(els);
    expect(text).not.toContain('cluster');
  });

  it('handles zero nodes and edges gracefully', () => {
    const text = graphSummary({ nodes: [], edges: [] });
    expect(text).toContain('0 pages');
    expect(text).toContain('0 links');
    expect(text).not.toContain('cluster');
  });

  it('handles null/undefined elements gracefully', () => {
    expect(() => graphSummary(null)).not.toThrow();
    expect(() => graphSummary(undefined)).not.toThrow();
  });

  it('is deterministic — same input same output', () => {
    const els = makeElements(10, 5, ['a', 'b']);
    expect(graphSummary(els)).toBe(graphSummary(els));
  });
});
