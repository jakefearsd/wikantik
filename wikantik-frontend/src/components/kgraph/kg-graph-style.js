import { graphStylesheet as baseStylesheet } from '../pagegraph/graph-style.js';

const kgOverrides = [
  {
    selector: 'node',
    style: { 'background-color': 'data(nodeTypeColor)' },
  },
];

export const kgGraphStylesheet = [...baseStylesheet, ...kgOverrides];
