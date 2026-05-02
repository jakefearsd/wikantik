import { graphStylesheet as baseStylesheet } from '../pagegraph/graph-style.js';

const kgOverrides = [
  {
    selector: 'node',
    style: { 'background-color': 'data(nodeTypeColor)' },
  },
  {
    selector: 'node[provenance = "AI_INFERRED"]',
    style: { 'border-width': 1, 'border-style': 'dashed', 'border-color': '#888' },
  },
  {
    selector: 'node[provenance = "AI_REVIEWED"]',
    style: { 'border-width': 2, 'border-style': 'solid',  'border-color': '#3b82f6' },
  },
  {
    selector: 'node[provenance = "HUMAN_AUTHORED"]',
    style: { 'border-width': 2, 'border-style': 'solid',  'border-color': '#16a085' },
  },
  {
    selector: 'node[status = "stub"]',
    style: { 'border-style': 'dashed' },
  },
  {
    selector: 'node[status = "deprecated"]',
    style: { 'opacity': 0.5, 'background-color': '#888' },
  },
  {
    selector: 'node[tier = "human"]',
    style: { 'border-width': 2, 'border-style': 'solid', 'border-color': '#f0b400' },
  },
];

export const kgGraphStylesheet = [...baseStylesheet, ...kgOverrides];
