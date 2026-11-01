import { describe, it, expect } from 'vitest';
import { visit } from 'unist-util-visit';
import { unified } from 'unified';
import remarkParse from 'remark-parse';
import { remarkAttachments } from './remarkAttachments';

function transformMarkdown(md, attachments, pageName) {
  const tree = unified().use(remarkParse).parse(md);
  remarkAttachments({ attachments, pageName })(tree);
  return tree;
}

describe('remarkAttachments', () => {
  const attachments = [
    { fileName: 'beach.jpg', isImage: true },
    { fileName: 'report.pdf', isImage: false },
  ];

  it('rewrites known image attachment to /attach/ URL', () => {
    const tree = transformMarkdown('![photo](beach.jpg)', attachments, 'TestPage');
    let imageUrl;
    visit(tree, 'image', (node) => { imageUrl = node.url; });
    expect(imageUrl).toBe('/attach/TestPage/beach.jpg');
  });

  it('rewrites known link attachment to /attach/ URL', () => {
    const tree = transformMarkdown('[doc](report.pdf)', attachments, 'TestPage');
    let linkUrl;
    visit(tree, 'link', (node) => { linkUrl = node.url; });
    expect(linkUrl).toBe('/attach/TestPage/report.pdf');
  });

  it('leaves absolute URLs unchanged', () => {
    const tree = transformMarkdown('![img](https://example.com/pic.jpg)', attachments, 'TestPage');
    let imageUrl;
    visit(tree, 'image', (node) => { imageUrl = node.url; });
    expect(imageUrl).toBe('https://example.com/pic.jpg');
  });

  it('leaves root-relative URLs unchanged', () => {
    const tree = transformMarkdown('![img](/images/logo.png)', attachments, 'TestPage');
    let imageUrl;
    visit(tree, 'image', (node) => { imageUrl = node.url; });
    expect(imageUrl).toBe('/images/logo.png');
  });

  it.each([
    ['an email autolink', '<someone@example.com>', 'mailto:someone@example.com'],
    ['a mailto link', '[mail](mailto:a.b@example.com)', 'mailto:a.b@example.com'],
    ['an ftp link', '[file](ftp://files.example.com/report.pdf)', 'ftp://files.example.com/report.pdf'],
    ['a tel link', '[call](tel:+1.555.0100)', 'tel:+1.555.0100'],
    ['a custom-scheme link', '[open](obsidian://open?vault=a.b)', 'obsidian://open?vault=a.b'],
  ])('never treats %s (a URL with a scheme) as an attachment', (_what, md, url) => {
    const tree = transformMarkdown(md, attachments, 'TestPage');
    let node;
    visit(tree, 'link', (n) => { node = n; });
    expect(node.url).toBe(url);
    expect(node.data?.hProperties?.['data-attachment-missing']).toBeUndefined();
  });

  it('marks missing attachment with data attribute', () => {
    const tree = transformMarkdown('![img](missing.jpg)', attachments, 'TestPage');
    let node;
    visit(tree, 'image', (n) => { node = n; });
    expect(node.data?.hProperties?.['data-attachment-missing']).toBe('true');
  });
});
