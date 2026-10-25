import { describe, it, expect } from 'vitest';
import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import remarkRehype from 'remark-rehype';
import { visit } from 'unist-util-visit';
import fixture from '../__fixtures__/renderedFences.json';
import { rehypeRenderedFences } from './rehypeRenderedFences';

function run(markdown) {
  const processor = unified().use(remarkParse).use(remarkGfm).use(remarkRehype).use(rehypeRenderedFences);
  return processor.runSync(processor.parse(markdown));
}
function fencesIn(tree) {
  const found = [];
  visit(tree, 'element', (n) => {
    if (n.tagName === 'wiki-fence') found.push({ language: n.properties.language, source: n.properties.source });
  });
  return found;
}

describe('rehypeRenderedFences', () => {
  it.each(fixture.documents.map((d) => [d.name, d]))('documentsFixtureYieldsSameFences: %s', (_n, doc) => {
    expect(fencesIn(run(doc.markdown))).toEqual(doc.fences);
  });

  it('exampleStaysCode', () => {
    const tree = run('```mermaid example\nflowchart TD\n```\n');
    expect(fencesIn(tree)).toEqual([]);
    let pre = 0;
    visit(tree, 'element', (n) => { if (n.tagName === 'pre') pre += 1; });
    expect(pre).toBe(1);
  });

  it('carries the info string remainder on code.data.meta', () => {
    const processor = unified().use(remarkParse).use(remarkRehype);
    const tree = processor.runSync(processor.parse('```mermaid example\nx\n```\n'));
    let meta;
    visit(tree, 'element', (n) => { if (n.tagName === 'code') meta = n.data?.meta; });
    expect(meta).toBe('example');
  });

  it('leaves unregistered languages alone', () => {
    expect(fencesIn(run('```java\nint x;\n```\n'))).toEqual([]);
  });
});
