import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { remarkWikiLinks } from './remarkWikiLinks';
import { collectNativeWikiLinkTargets } from './wikiLinkSyntax';
import cases from './__fixtures__/wikilinks.json';

const resolvedFor = (c) => new Map(collectNativeWikiLinkTargets(c.markdown).map((t) => [
  t.toLowerCase(), c.pages.find((p) => p.toLowerCase() === t.toLowerCase()) ?? null]));
const describeLinks = (el) => [...el.querySelectorAll('a')].map((a) => ({
  href: decodeURIComponent(a.getAttribute('href')),
  class: a.classList.contains('createpage') ? 'createpage' : '',
  text: a.textContent,
}));

describe('remarkWikiLinks', () => {
  it.each(cases.map((c) => [c.name, c]))('matches the shared server fixture: %s', (_n, c) => {
    const { container } = render(
      <ReactMarkdown remarkPlugins={[remarkGfm, [remarkWikiLinks, { resolved: resolvedFor(c), pageName: 'WikilinkParityHost' }]]}>
        {c.markdown}
      </ReactMarkdown>);
    expect(describeLinks(container)).toEqual(c.expected);
  });

  it('turns an embed-only paragraph into wiki-embed elements and an inline embed into a link', () => {
    const { container } = render(
      <ReactMarkdown remarkPlugins={[[remarkWikiLinks, {}]]}
        components={{ 'wiki-embed': (p) => <section data-testid="embed">{p['data-page']}|{p['data-section']}</section> }}>
        {'![[A]]\n![[B#Usage]]\n\nsee ![[C]] here'}
      </ReactMarkdown>);
    expect([...container.querySelectorAll('[data-testid="embed"]')].map((n) => n.textContent)).toEqual(['A|', 'B|Usage']);
    expect(container.querySelector('a').getAttribute('href')).toBe('C');
  });

  it('renders attachment embeds as sized images', () => {
    const { container } = render(
      <ReactMarkdown remarkPlugins={[[remarkWikiLinks, { pageName: 'Host', attachments: [{ fileName: 'pic.png' }] }]]}>
        {'![[pic.png|300]] and ![[Owner/doc.pdf]]'}
      </ReactMarkdown>);
    const img = container.querySelector('img');
    expect(img.getAttribute('src')).toBe('/attach/Host/pic.png');
    expect(img.getAttribute('width')).toBe('300');
    expect(container.querySelector('a').getAttribute('href')).toBe('/attach/Owner/doc.pdf');
  });

  it('leaves backslash-escaped brackets alone', () => {
    const { container } = render(<ReactMarkdown remarkPlugins={[[remarkWikiLinks, {}]]}>{'\\[[NotALink]]'}</ReactMarkdown>);
    expect(container.querySelector('a')).toBeNull();
    expect(container.textContent).toContain('[[NotALink]]');
  });

  it('uses the server-resolved canonical name and marks missing pages', () => {
    const resolved = new Map([['foo bar', 'FooBar'], ['nope', null]]);
    const { container } = render(
      <ReactMarkdown remarkPlugins={[[remarkWikiLinks, { resolved }]]}>{'[[foo bar]] [[Nope]]'}</ReactMarkdown>);
    const [a, b] = container.querySelectorAll('a');
    expect(a.getAttribute('href')).toBe('FooBar');
    expect(b.classList.contains('createpage')).toBe(true);
    expect(b.getAttribute('data-missing-page')).toBe('Nope');
  });
});
