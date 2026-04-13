import { describe, it, expect } from 'vitest';
import { graphStylesheet } from './graph-style.js';

const selectors = graphStylesheet.map((rule) => rule.selector);

describe('graphStylesheet', () => {
  it('defines a base style for nodes and edges', () => {
    expect(selectors).toContain('node');
    expect(selectors).toContain('edge');
  });

  it('defines a selector for every node role used by the graph API', () => {
    for (const role of ['role-hub', 'role-orphan', 'role-stub', 'role-restricted']) {
      expect(selectors).toContain(`node.${role}`);
    }
  });

  it('defines filter states used by the filter engine', () => {
    expect(selectors).toContain('.dimmed');
    expect(selectors).toContain('.hidden');
    expect(selectors).toContain('node.faded');
    expect(selectors).toContain('edge.faded');
  });

  it('defines a selected-node highlight', () => {
    expect(selectors).toContain(':selected');
  });

  it('wires edge visuals from graph-data fields', () => {
    const edge = graphStylesheet.find((r) => r.selector === 'edge');
    expect(edge.style['line-color']).toBe('data(edgeColor)');
    expect(edge.style.width).toBe('data(compositeWidth)');
    expect(edge.style.label).toBe('data(edgeLabel)');
  });

  it('renders bidirectional edges with source arrows', () => {
    const bidir = graphStylesheet.find((r) => r.selector === 'edge[?bidirectional]');
    expect(bidir.style['source-arrow-shape']).toBe('triangle');
  });
});
