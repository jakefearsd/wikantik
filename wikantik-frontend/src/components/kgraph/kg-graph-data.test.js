import { describe, it, expect } from 'vitest';
import { toKgCytoscapeElements, colourForNodeType } from './kg-graph-data.js';

const SNAP = {
  generatedAt: '2026-05-02T20:00:00Z',
  nodeCount: 3,
  edgeCount: 1,
  hubDegreeThreshold: 10,
  nodes: [
    { id: 'a', name: 'Alan',    type: 'person',  role: 'normal', degreeIn: 1, degreeOut: 0, restricted: false, cluster: null, tags: [], status: 'active', provenance: 'AI_INFERRED', tier: 'machine' },
    { id: 'b', name: 'Compute', type: 'concept', role: 'normal', degreeIn: 0, degreeOut: 1, restricted: false, cluster: null, tags: [], status: 'active', provenance: 'AI_INFERRED', tier: 'machine' },
    { id: 'c', name: 'Other',   type: 'person',  role: 'normal', degreeIn: 0, degreeOut: 0, restricted: false, cluster: null, tags: [], status: 'active', provenance: 'AI_INFERRED', tier: 'machine' },
  ],
  edges: [
    { id: 'e1', source: 'b', target: 'a', relationshipType: 'mentioned_with', provenance: 'AI_INFERRED' },
  ],
};

const FILTER = {
  visibleNodeIds:  new Set(['a', 'b', 'c']),
  fadedNodeIds:    new Set(),
  visibleEdgeIds:  new Set(['e1']),
  fadedEdgeIds:    new Set(),
  nodeColor:       new Map(),
};

describe('toKgCytoscapeElements', () => {
  it('assigns the same nodeTypeColor to nodes of the same node_type', () => {
    const out = toKgCytoscapeElements(SNAP, FILTER);
    const a = out.nodes.find(n => n.data.id === 'a');
    const b = out.nodes.find(n => n.data.id === 'b');
    const c = out.nodes.find(n => n.data.id === 'c');
    expect(a.data.nodeTypeColor).toBe(c.data.nodeTypeColor);
    expect(a.data.nodeTypeColor).not.toBe(b.data.nodeTypeColor);
  });

  it('writes type, provenance, status, tier into node.data', () => {
    const out = toKgCytoscapeElements(SNAP, FILTER);
    const a = out.nodes.find(n => n.data.id === 'a');
    expect(a.data.type).toBe('person');
    expect(a.data.provenance).toBe('AI_INFERRED');
    expect(a.data.status).toBe('active');
    expect(a.data.tier).toBe('machine');
  });
});

describe('colourForNodeType', () => {
  it('is deterministic for the same input', () => {
    expect(colourForNodeType('person')).toBe(colourForNodeType('person'));
  });

  it('returns a CSS hex colour', () => {
    expect(colourForNodeType('concept')).toMatch(/^#[0-9a-f]{6}$/i);
  });
});
