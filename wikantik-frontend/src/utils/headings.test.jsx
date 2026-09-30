import { describe, it, expect } from 'vitest';
import cases from './__fixtures__/heading-slugs.json';
import { renderToStaticMarkup } from 'react-dom/server';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import { slugify, headingsFromMarkdown, extractHeadings } from './headings';

describe('extractHeadings', () => {
  it('returns empty array for empty html', () => {
    expect(extractHeadings('')).toEqual([]);
  });

  it('extracts h2 and h3 with slugged ids', () => {
    const html = '<h2>Introduction</h2><h3>Background</h3>';
    const result = extractHeadings(html);
    expect(result).toHaveLength(2);
    expect(result[0]).toEqual({ id: 'introduction', text: 'Introduction', level: 2 });
    expect(result[1]).toEqual({ id: 'background', text: 'Background', level: 3 });
  });

  it('excludes h1', () => {
    const html = '<h1>Title</h1><h2>Section</h2>';
    const result = extractHeadings(html);
    expect(result).toHaveLength(1);
    expect(result[0].level).toBe(2);
  });

  it('excludes h4, h5, h6', () => {
    const html = '<h2>Keep</h2><h4>Skip</h4><h5>Skip too</h5>';
    const result = extractHeadings(html);
    expect(result).toHaveLength(1);
    expect(result[0].text).toBe('Keep');
  });

  it('generates unique ids for duplicate headings', () => {
    const html = '<h2>Overview</h2><h2>Overview</h2><h2>Overview</h2>';
    const result = extractHeadings(html);
    expect(result[0].id).toBe('overview');
    expect(result[1].id).toBe('overview-2');
    expect(result[2].id).toBe('overview-3');
  });

  it('slugifies heading text: lowercase, spaces to hyphens, strips non-alphanumeric', () => {
    const html = '<h2>Hello World! What\'s Next?</h2>';
    const result = extractHeadings(html);
    expect(result[0].id).toBe('hello-world-whats-next');
  });

  it('strips html tags from heading text', () => {
    const html = '<h2><em>Formatted</em> Heading</h2>';
    const result = extractHeadings(html);
    expect(result[0].text).toBe('Formatted Heading');
    expect(result[0].id).toBe('formatted-heading');
  });

  it('preserves document order', () => {
    const html = '<h3>Alpha</h3><h2>Beta</h2><h3>Gamma</h3>';
    const result = extractHeadings(html);
    expect(result.map(h => h.text)).toEqual(['Alpha', 'Beta', 'Gamma']);
  });
});

describe('slugify — shared case table (also asserted by HeadingSlugsTest.java)', () => {
  it.each(cases)('$heading → $slug', ({ heading, slug }) => {
    expect(slugify(heading)).toBe(slug);
  });
});

describe('headingsFromMarkdown', () => {
  const md = '# Title\n\n## Setup\n\ntext\n\n### Setup\n\n#### Deep\n\n## Using `Foo` and **bold**\n';

  it('returns every heading with level, plain text and 1-based source line', () => {
    expect(headingsFromMarkdown(md).map(({ level, text, line }) => ({ level, text, line }))).toEqual([
      { level: 1, text: 'Title', line: 1 },
      { level: 2, text: 'Setup', line: 3 },
      { level: 3, text: 'Setup', line: 7 },
      { level: 4, text: 'Deep', line: 9 },
      { level: 2, text: 'Using Foo and bold', line: 11 },
    ]);
  });

  it('assigns ids only to h2/h3, numbering duplicates like the page view', () => {
    expect(headingsFromMarkdown(md).map((h) => h.id)).toEqual([null, 'setup', 'setup-2', null, 'using-foo-and-bold']);
  });

  it('matches extractHeadings ids on the rendered HTML', () => {
    const html = renderToStaticMarkup(
      <ReactMarkdown remarkPlugins={[remarkGfm, remarkMath]}>{md}</ReactMarkdown>,
    );
    const fromHtml = extractHeadings(html).map((h) => h.id);
    const fromMd = headingsFromMarkdown(md).filter((h) => h.id).map((h) => h.id);
    expect(fromMd).toEqual(fromHtml);
  });

  it('returns [] for empty input', () => {
    expect(headingsFromMarkdown('')).toEqual([]);
    expect(headingsFromMarkdown(null)).toEqual([]);
  });
});
