import { describe, it, expect } from 'vitest';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { renderToStaticMarkup } from 'react-dom/server';

const html = (md) => renderToStaticMarkup(
  <ReactMarkdown remarkPlugins={[[remarkGfm, { singleTilde: false }]]}>{md}</ReactMarkdown>,
);

describe('strikethrough is double-tilde only', () => {
  it('~a~ is not struck', () => expect(html('~a~')).not.toContain('<del>'));
  it('"~5 min to ~10 min" is not struck', () => expect(html('~5 min to ~10 min')).not.toContain('<del>'));
  it('~~a~~ is struck', () => expect(html('~~a~~')).toContain('<del>a</del>'));
});
