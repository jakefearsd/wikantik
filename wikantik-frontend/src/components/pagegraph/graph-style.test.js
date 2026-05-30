import { describe, it, expect } from 'vitest';
import { graphStylesheet, shapeForCluster } from './graph-style.js';

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

const VALID_SHAPES = ['ellipse', 'rectangle', 'diamond', 'hexagon', 'triangle', 'pentagon', 'octagon'];

describe('shapeForCluster', () => {
  it('returns a known Cytoscape shape name', () => {
    expect(VALID_SHAPES).toContain(shapeForCluster('math'));
    expect(VALID_SHAPES).toContain(shapeForCluster('science'));
    expect(VALID_SHAPES).toContain(shapeForCluster('history'));
  });

  it('is deterministic — same cluster always same shape', () => {
    const clusters = ['math', 'science', 'history', 'art', 'bio'];
    for (const c of clusters) {
      expect(shapeForCluster(c)).toBe(shapeForCluster(c));
    }
  });

  it('cycles through shape set (different clusters map to different shapes)', () => {
    // Generate enough clusters to cycle the full shape set
    const names = ['alpha', 'beta', 'gamma', 'delta', 'epsilon', 'zeta', 'eta', 'theta'];
    const shapes = names.map(shapeForCluster);
    // All returned values must be in the valid set
    for (const s of shapes) expect(VALID_SHAPES).toContain(s);
    // Across 8 clusters we must see at least 2 distinct shapes (cycle exists)
    expect(new Set(shapes).size).toBeGreaterThan(1);
  });

  it('returns "ellipse" for null/undefined (no cluster)', () => {
    expect(shapeForCluster(null)).toBe('ellipse');
    expect(shapeForCluster(undefined)).toBe('ellipse');
    expect(shapeForCluster('')).toBe('ellipse');
  });

  it('stylesheet includes a node[clusterShape] selector', () => {
    const rule = graphStylesheet.find((r) => r.selector === 'node[clusterShape]');
    expect(rule).toBeTruthy();
    expect(rule.style['shape']).toBe('data(clusterShape)');
  });
});
