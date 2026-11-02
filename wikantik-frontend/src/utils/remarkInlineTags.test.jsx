import { describe, it, expect } from 'vitest';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import { renderToStaticMarkup } from 'react-dom/server';
import remarkInlineMath from './remarkInlineMath';
import remarkInlineTags from './remarkInlineTags';
import { stripComments } from './obsidianComments';
import fixture from './__fixtures__/inline-tags.json';

const PLUGINS = [[remarkGfm, { singleTilde: false }], [remarkMath, { singleDollarTextMath: false }], remarkInlineMath, remarkInlineTags];
// EditorPreviewMarkdown hides comments before the parse; do the same here.
const render = (md) => renderToStaticMarkup(<ReactMarkdown remarkPlugins={PLUGINS}>{stripComments(md)}</ReactMarkdown>);
/** The same normalisation InlineTagParityTest applies (the reader adds block ordinals and heading ids, and escapes `=`). */
const normalised = (html) => html.replaceAll('&#61;', '=').replace(/ data-block="\d+"/g, '').replace(/<h([1-6]) id="[^"]*"/g, '<h$1')
  .replace(/>\s+</g, '><').trim();
/** The tags the preview rendered, by their links. */
const renderedTags = (html) => [...html.matchAll(/class="tag inline-tag" href="\/search\?tag=([^"]+)"/g)].map((m) => decodeURIComponent(m[1]));

describe('remarkInlineTags (shared fixture inline-tags.json)', () => {
  it('has rows', () => expect(fixture.length).toBeGreaterThan(15));
  it.each(fixture.map((r) => [r.name, r]))('%s', (_name, row) => {
    const html = normalised(render(row.markdown));
    if (row.html !== undefined) expect(html).toBe(normalised(row.html));
    else expect(html).not.toContain('inline-tag');
    // What the preview links is exactly what the index counts.
    expect(renderedTags(html)).toEqual(row.tags);
  });
});

describe('remarkInlineTags beyond the fixture', () => {
  it('renders a tag on the line after a hard break and inside a list item or table cell', () => {
    expect(renderedTags(render('a  \n#brk'))).toEqual(['brk']);
    expect(renderedTags(render('| h |\n| - |\n| #cell |'))).toEqual(['cell']);
  });

  it('leaves a tag glued to an earlier inline construct as text', () => {
    expect(renderedTags(render('*em*#x'))).toEqual([]);
  });

  it('does tag a setext heading whose text starts with a tag, and skips ATX headings in containers', () => {
    expect(renderedTags(render('#real tag\n=========='))).toEqual(['real']);
    expect(renderedTags(render('> ## H #x\n\n- # H #y'))).toEqual([]);
  });

  it('does not tag inside a setext-less heading line but does after a heading', () => {
    expect(renderedTags(render('## H #x\n\nprose #y'))).toEqual(['y']);
  });

  it('keeps the text as the author wrote it', () => {
    expect(render('#Team/Core')).toContain('>#Team/Core</a>');
  });
});
