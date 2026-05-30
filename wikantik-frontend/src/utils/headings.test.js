import { describe, it, expect } from 'vitest';
import { extractHeadings } from './headings';

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
