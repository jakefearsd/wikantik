import { describe, it, expect } from 'vitest';
import { linkCardHref } from './useLinkCard';

const context = { pageName: 'Here', attachments: ['pic.png'] };
const wiki = (source, kind = source.startsWith('!') ? 'embed' : 'wiki') => ({ kind, source });

describe('linkCardHref: where "Open" goes', () => {
  it.each([
    [wiki('[[Other Page]]'), '/wiki/Other%20Page'],
    [wiki('[[Other#Some Heading|shown]]'), '/wiki/Other#some-heading'],
    [wiki('![[Other]]'), '/wiki/Other'],
    [wiki('![[pic.png|300]]'), '/attach/Here/pic.png'],
    [wiki('![[Docs/pic.png]]'), '/attach/Docs/pic.png'],
    [{ kind: 'link', url: 'https://example.org/a' }, 'https://example.org/a'],
    [{ kind: 'link', url: 'OtherPage' }, '/wiki/OtherPage'],
    [{ kind: 'image', url: 'https://example.org/a.png' }, 'https://example.org/a.png'],
    [{ kind: 'image', url: 'pic.png' }, '/attach/Here/pic.png'],
    [{ kind: 'image', url: '/attach/Docs/x.png' }, '/attach/Docs/x.png'],
  ])('%j opens %s', (model, href) => {
    expect(linkCardHref(model, context)).toBe(href);
  });
  it.each([
    [wiki('[[#Same page]]')],
    [{ kind: 'link', url: 'javascript:alert(1)' }],
    [{ kind: 'link', url: '' }],
    [{ kind: 'image', url: 'data:image/png;base64,AAAA' }],
    [{ kind: 'image', url: 'not-an-attachment.png' }],
  ])('%j opens nothing', (model) => {
    expect(linkCardHref(model, context)).toBe(null);
  });
});
