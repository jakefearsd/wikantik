export const graphStylesheet = [
  {
    selector: 'node',
    style: {
      'background-color': '#94a3b8',
      'label': 'data(label)',
      'font-size': 10,
      'text-valign': 'bottom',
      'text-halign': 'center',
      'text-margin-y': 4,
      'width': 'mapData(degreeIn, 0, 20, 6, 18)',
      'height': 'mapData(degreeIn, 0, 20, 6, 18)',
      'min-zoomed-font-size': 0,
    },
  },
  {
    selector: 'node.role-hub',
    style: {
      'background-color': '#059669',
      'width': 'mapData(degreeIn, 0, 40, 8, 22)',
      'height': 'mapData(degreeIn, 0, 40, 8, 22)',
    },
  },
  {
    selector: 'node.role-orphan',
    style: {
      'background-color': '#f59e0b',
      'width': 7,
      'height': 7,
    },
  },
  {
    selector: 'node.role-stub',
    style: {
      'background-color': '#fff1f2',
      'border-color': '#dc2626',
      'border-width': 2,
      'border-style': 'dashed',
      'width': 7,
      'height': 7,
    },
  },
  {
    selector: 'node.role-restricted',
    style: {
      'background-color': '#e5e7eb',
      'border-color': '#9ca3af',
      'border-width': 1,
      'border-style': 'solid',
      'label': '\u{1F512}',
      'font-size': 8,
      'width': 8,
      'height': 8,
    },
  },
  {
    selector: 'edge',
    style: {
      'width': 'data(compositeWidth)',
      'curve-style': 'bezier',
      'line-color': 'data(edgeColor)',
      'target-arrow-color': 'data(edgeColor)',
      'target-arrow-shape': 'triangle',
      'arrow-scale': 0.6,
      'label': 'data(edgeLabel)',
      'font-size': 7,
      'text-rotation': 'autorotate',
      'text-opacity': 0,
      'color': '#64748b',
    },
  },
  {
    selector: 'edge.composite',
    style: {
      'text-opacity': 1,
      'text-background-color': '#ffffff',
      'text-background-opacity': 0.85,
      'text-background-padding': '2px',
    },
  },
  {
    selector: 'edge[?bidirectional]',
    style: {
      'source-arrow-shape': 'triangle',
      'source-arrow-color': 'data(edgeColor)',
    },
  },
  {
    selector: '.dimmed',
    style: {
      'opacity': 0.2,
    },
  },
  {
    selector: '.hidden',
    style: {
      'display': 'none',
    },
  },
  {
    selector: 'node.faded',
    style: { 'opacity': 0.15 },
  },
  {
    selector: 'edge.faded',
    style: { 'opacity': 0.08 },
  },
  {
    selector: 'node[clusterColor]',
    style: { 'border-color': 'data(clusterColor)', 'border-width': 3 },
  },
  {
    selector: ':selected',
    style: {
      'border-width': 3,
      'border-color': '#2563eb',
    },
  },
];
