import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import ReactMarkdown from 'react-markdown';
import { wikiLinkTarget, collectWikiLinkTargets, remarkMissingLinks } from './wikiLinkTargets';

describe('wikiLinkTarget', () => {
  it.each([
    ['PageName', 'PageName'],
    ['PageName#setup', 'PageName'],
    ['My%20Page', 'My Page'],
    ['#local', null],
    ['https://x.org', null],
    ['mailto:a@b', null],
    ['cite://T/h', null],
    ['/wiki/Foo', null],
    ['diagram.png', null],
    ['Other/file.pdf', null],
    ['', null],
  ])('%s → %s', (url, expected) => expect(wikiLinkTarget(url)).toBe(expected));
});

describe('collectWikiLinkTargets', () => {
  it('collects distinct page targets plus cite targets, skipping code and names with commas', () => {
    const md = '[a](Alpha) [b](Alpha#x) [c](cite://Beta/h "s") `[d](Gamma)` [e](A,B) ![i](pic.png)';
    expect(collectWikiLinkTargets(md)).toEqual(['Alpha', 'Beta']);
  });
});

describe('remarkMissingLinks', () => {
  it('marks links to missing pages like the page view marks create-links', () => {
    const out = renderToStaticMarkup(
      <ReactMarkdown remarkPlugins={[[remarkMissingLinks, { missing: new Set(['ghost']) }]]}>
        {'[g](Ghost) [h](Home)'}
      </ReactMarkdown>,
    );
    expect(out).toContain('<a href="Ghost" class="createpage" data-missing-page="Ghost"');
    expect(out).toContain('<a href="Home">');
  });
});
