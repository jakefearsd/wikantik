import { describe, it, expect } from 'vitest';
import { metadataToYaml, reconstructContent } from './frontmatterUtils.js';

describe('metadataToYaml', () => {
  it('serializes a plain string value without quoting', () => {
    expect(metadataToYaml({ type: 'article' })).toBe('type: article');
  });

  it('single-quotes ISO date strings', () => {
    expect(metadataToYaml({ date: '2026-03-26' })).toBe("date: '2026-03-26'");
  });

  it('single-quotes strings containing ": "', () => {
    expect(metadataToYaml({ summary: 'How AI works: the pipeline' }))
      .toBe("summary: 'How AI works: the pipeline'");
  });

  it('serializes an array as a YAML block sequence', () => {
    expect(metadataToYaml({ tags: ['ai', 'ml'] })).toBe('tags:\n- ai\n- ml');
  });

  it('serializes an empty array as key: []', () => {
    expect(metadataToYaml({ tags: [] })).toBe('tags: []');
  });

  it('omits null values', () => {
    expect(metadataToYaml({ type: 'article', cluster: null })).toBe('type: article');
  });

  it('omits undefined values', () => {
    expect(metadataToYaml({ type: 'article', cluster: undefined })).toBe('type: article');
  });

  it('returns empty string for an empty object', () => {
    expect(metadataToYaml({})).toBe('');
  });

  it('returns empty string for null input', () => {
    expect(metadataToYaml(null)).toBe('');
  });

  it('serializes a mixed metadata object correctly', () => {
    const result = metadataToYaml({
      type: 'article',
      cluster: 'generative-ai',
      date: '2026-03-26',
      tags: ['ai'],
    });
    expect(result).toBe(
      "type: article\ncluster: generative-ai\ndate: '2026-03-26'\ntags:\n- ai"
    );
  });

  it('single-quotes strings starting with #', () => {
    expect(metadataToYaml({ note: '#important' })).toBe("note: '#important'");
  });

  it('serializes numeric values without quoting', () => {
    expect(metadataToYaml({ version: 3 })).toBe('version: 3');
  });
});

describe('reconstructContent', () => {
  it('wraps non-empty metadata + body in frontmatter delimiters', () => {
    const result = reconstructContent({ type: 'article' }, 'Body text here.');
    expect(result).toBe('---\ntype: article\n---\n\nBody text here.');
  });

  it('starts with ---\\n when metadata is non-empty', () => {
    const result = reconstructContent({ type: 'article' }, 'body');
    expect(result.startsWith('---\n')).toBe(true);
  });

  it('contains the YAML block between delimiters', () => {
    const result = reconstructContent({ cluster: 'ai' }, 'body');
    expect(result).toContain('cluster: ai');
  });

  it('ends with ---\\n\\n{body} when metadata is non-empty', () => {
    const result = reconstructContent({ type: 'article' }, 'My body.');
    expect(result.endsWith('---\n\nMy body.')).toBe(true);
  });

  it('returns body as-is when metadata is an empty object', () => {
    expect(reconstructContent({}, 'Just the body.')).toBe('Just the body.');
  });

  it('returns body as-is when metadata is null', () => {
    expect(reconstructContent(null, 'Just the body.')).toBe('Just the body.');
  });

  it('returns empty string when metadata is null and body is undefined', () => {
    expect(reconstructContent(null, undefined)).toBe('');
  });

  it('produces a frontmatter block with empty body after closing delimiter', () => {
    const result = reconstructContent({ type: 'article' }, '');
    expect(result).toBe('---\ntype: article\n---\n\n');
  });
});
