import { describe, it, expect } from 'vitest';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import { renderToStaticMarkup } from 'react-dom/server';
import remarkInlineMath from './remarkInlineMath';
import remarkHighlight from './remarkHighlight';
import fixture from './__fixtures__/highlight.json';

const PLUGINS = [[remarkGfm, { singleTilde: false }], [remarkMath, { singleDollarTextMath: false }], remarkInlineMath, remarkHighlight];
const render = (md) => renderToStaticMarkup(<ReactMarkdown remarkPlugins={PLUGINS}>{md}</ReactMarkdown>);
/** The same normalisation HighlightParityTest applies (the reader adds block ordinals and heading ids). */
const normalised = (html) => html.replaceAll('&#61;', '=').replace(/ data-block="\d+"/g, '').replace(/<h([1-6]) id="[^"]*"/g, '<h$1')
  .replace(/>\s+</g, '><').trim();

describe('remarkHighlight (shared fixture highlight.json)', () => {
  it('has rows', () => expect(fixture.length).toBeGreaterThan(10));
  it.each(fixture.map((r) => [r.name, r]))('%s', (_name, row) => {
    const html = normalised(render(row.markdown));
    if (row.html !== undefined) expect(html).toBe(normalised(row.html));
    else expect(html).not.toContain('<mark');
  });
});
