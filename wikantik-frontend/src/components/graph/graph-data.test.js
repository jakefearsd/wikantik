import { describe, it, expect } from 'vitest';
import { toCytoscapeElements, mergeBidirectionalEdges, mergeParallelEdges } from './graph-data.js';

const SNAPSHOT = {
  generatedAt: '2026-04-12T10:00:00Z',
  nodeCount: 4,
  edgeCount: 3,
  hubDegreeThreshold: 10,
  nodes: [
    { id: 'aaa', name: 'Hub', type: 'page', role: 'hub', provenance: 'HUMAN_AUTHORED', sourcePage: 'Hub', degreeIn: 6, degreeOut: 6, restricted: false },
    { id: 'bbb', name: 'Normal', type: 'page', role: 'normal', provenance: 'HUMAN_AUTHORED', sourcePage: 'Normal', degreeIn: 1, degreeOut: 0, restricted: false },
    { id: 'ccc', name: 'Orphan', type: 'page', role: 'orphan', provenance: 'HUMAN_AUTHORED', sourcePage: 'Orphan', degreeIn: 0, degreeOut: 0, restricted: false },
    { id: 'ddd', name: null, type: null, role: 'restricted', provenance: null, sourcePage: null, degreeIn: 1, degreeOut: 1, restricted: true },
  ],
  edges: [
    { id: 'e1', source: 'aaa', target: 'bbb', relationshipType: 'links_to', provenance: 'HUMAN_AUTHORED' },
    { id: 'e2', source: 'aaa', target: 'ddd', relationshipType: 'related_to', provenance: 'HUMAN_AUTHORED' },
    { id: 'e3', source: 'ddd', target: 'aaa', relationshipType: 'related_to', provenance: 'HUMAN_AUTHORED' },
  ],
};

describe('toCytoscapeElements', () => {
  it('maps role to CSS class', () => {
    const { nodes } = toCytoscapeElements(SNAPSHOT);
    const hub = nodes.find(n => n.data.id === 'aaa');
    expect(hub.classes).toContain('role-hub');
    const orphan = nodes.find(n => n.data.id === 'ccc');
    expect(orphan.classes).toContain('role-orphan');
    const restricted = nodes.find(n => n.data.id === 'ddd');
    expect(restricted.classes).toContain('role-restricted');
  });

  it('assigns edge color from palette', () => {
    const { edges } = toCytoscapeElements(SNAPSHOT);
    const linksTo = edges.find(e => e.data.relationshipType === 'links_to');
    expect(linksTo.data.edgeColor).toBe('#94a3b8');
    const relatedTo = edges.find(e => e.data.relationshipType === 'related_to');
    expect(relatedTo.data.edgeColor).toBe('#2563eb');
  });

  it('preserves restricted nodes with null fields', () => {
    const { nodes } = toCytoscapeElements(SNAPSHOT);
    const restricted = nodes.find(n => n.data.id === 'ddd');
    expect(restricted.data.name).toBeNull();
    expect(restricted.data.restricted).toBe(true);
  });

  it('passes through node metadata', () => {
    const { nodes } = toCytoscapeElements(SNAPSHOT);
    const hub = nodes.find(n => n.data.id === 'aaa');
    expect(hub.data.role).toBe('hub');
    expect(hub.data.degreeIn).toBe(6);
    expect(hub.data.degreeOut).toBe(6);
    expect(hub.data.sourcePage).toBe('Hub');
  });
});

describe('mergeBidirectionalEdges', () => {
  it('collapses matching pairs into bidirectional edge', () => {
    const edges = [
      { id: 'e2', source: 'aaa', target: 'ddd', relationshipType: 'related_to' },
      { id: 'e3', source: 'ddd', target: 'aaa', relationshipType: 'related_to' },
    ];
    const merged = mergeBidirectionalEdges(edges);
    expect(merged).toHaveLength(1);
    expect(merged[0].bidirectional).toBe(true);
  });

  it('does not collapse different relationship types', () => {
    const edges = [
      { id: 'e1', source: 'aaa', target: 'bbb', relationshipType: 'links_to' },
      { id: 'e2', source: 'bbb', target: 'aaa', relationshipType: 'related_to' },
    ];
    const merged = mergeBidirectionalEdges(edges);
    expect(merged).toHaveLength(2);
    expect(merged.every(e => !e.bidirectional)).toBe(true);
  });

  it('preserves singletons', () => {
    const edges = [
      { id: 'e1', source: 'aaa', target: 'bbb', relationshipType: 'links_to' },
    ];
    const merged = mergeBidirectionalEdges(edges);
    expect(merged).toHaveLength(1);
    expect(merged[0].bidirectional).toBeFalsy();
  });

  it('is stable across ordering', () => {
    const edges1 = [
      { id: 'e2', source: 'aaa', target: 'ddd', relationshipType: 'related_to' },
      { id: 'e3', source: 'ddd', target: 'aaa', relationshipType: 'related_to' },
    ];
    const edges2 = [
      { id: 'e3', source: 'ddd', target: 'aaa', relationshipType: 'related_to' },
      { id: 'e2', source: 'aaa', target: 'ddd', relationshipType: 'related_to' },
    ];
    const m1 = mergeBidirectionalEdges(edges1);
    const m2 = mergeBidirectionalEdges(edges2);
    expect(m1[0].source).toBe(m2[0].source);
    expect(m1[0].target).toBe(m2[0].target);
  });
});

describe('mergeParallelEdges', () => {
  it('collapses edges between same pair into one composite edge', () => {
    const edges = [
      { id: 'e1', source: 'aaa', target: 'bbb', relationshipType: 'links_to', provenance: 'HUMAN_AUTHORED' },
      { id: 'e2', source: 'aaa', target: 'bbb', relationshipType: 'related_to', provenance: 'HUMAN_AUTHORED' },
    ];
    const merged = mergeParallelEdges(edges);
    expect(merged).toHaveLength(1);
    expect(merged[0].relationshipTypes).toEqual(['links_to', 'related_to']);
    expect(merged[0].source).toBe('aaa');
    expect(merged[0].target).toBe('bbb');
  });

  it('keeps edges between different pairs separate', () => {
    const edges = [
      { id: 'e1', source: 'aaa', target: 'bbb', relationshipType: 'links_to', provenance: 'HUMAN_AUTHORED' },
      { id: 'e2', source: 'aaa', target: 'ccc', relationshipType: 'links_to', provenance: 'HUMAN_AUTHORED' },
    ];
    const merged = mergeParallelEdges(edges);
    expect(merged).toHaveLength(2);
  });

  it('preserves singleton edges with a single-element types array', () => {
    const edges = [
      { id: 'e1', source: 'aaa', target: 'bbb', relationshipType: 'links_to', provenance: 'HUMAN_AUTHORED' },
    ];
    const merged = mergeParallelEdges(edges);
    expect(merged).toHaveLength(1);
    expect(merged[0].relationshipTypes).toEqual(['links_to']);
  });

  it('preserves bidirectional flag', () => {
    const edges = [
      { id: 'e1', source: 'aaa', target: 'bbb', relationshipType: 'links_to', bidirectional: true },
      { id: 'e2', source: 'aaa', target: 'bbb', relationshipType: 'related_to' },
    ];
    const merged = mergeParallelEdges(edges);
    expect(merged[0].bidirectional).toBe(true);
  });

  it('deduplicates relationship types', () => {
    const edges = [
      { id: 'e1', source: 'aaa', target: 'bbb', relationshipType: 'links_to' },
      { id: 'e2', source: 'aaa', target: 'bbb', relationshipType: 'links_to' },
    ];
    const merged = mergeParallelEdges(edges);
    expect(merged[0].relationshipTypes).toEqual(['links_to']);
  });
});

describe('toCytoscapeElements parallel merge', () => {
  it('produces composite label for multi-type edges', () => {
    const snap = {
      ...SNAPSHOT,
      edges: [
        { id: 'e1', source: 'aaa', target: 'bbb', relationshipType: 'links_to', provenance: 'HUMAN_AUTHORED' },
        { id: 'e2', source: 'aaa', target: 'bbb', relationshipType: 'related_to', provenance: 'HUMAN_AUTHORED' },
      ],
    };
    const { edges } = toCytoscapeElements(snap);
    expect(edges).toHaveLength(1);
    expect(edges[0].data.relationshipTypes).toEqual(['links_to', 'related_to']);
    expect(edges[0].data.edgeLabel).toBe('links_to · related_to');
    expect(edges[0].data.compositeWidth).toBeGreaterThan(1);
  });

  it('sets compositeWidth to 1 for single-type edges', () => {
    const { edges } = toCytoscapeElements(SNAPSHOT);
    const linksTo = edges.find(e => e.data.relationshipTypes.includes('links_to'));
    expect(linksTo.data.compositeWidth).toBe(1);
  });
});

describe('edge palette', () => {
  it('is deterministic for unknown types', () => {
    const snap1 = { ...SNAPSHOT, edges: [
      { id: 'x', source: 'aaa', target: 'bbb', relationshipType: 'custom_type', provenance: 'HUMAN_AUTHORED' },
    ]};
    const snap2 = { ...SNAPSHOT, edges: [
      { id: 'x', source: 'aaa', target: 'bbb', relationshipType: 'custom_type', provenance: 'HUMAN_AUTHORED' },
    ]};
    const c1 = toCytoscapeElements(snap1).edges[0].data.edgeColor;
    const c2 = toCytoscapeElements(snap2).edges[0].data.edgeColor;
    expect(c1).toBe(c2);
  });
});
