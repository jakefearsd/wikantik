import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import rehypeKatex from 'rehype-katex';
import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkRehype from 'remark-rehype';
import remarkInlineMath from './remarkInlineMath';
import remarkCallouts from './remarkCallouts';
import { remarkWikiMarkup } from './remarkWikiMarkup';
import { remarkWikiLinks } from './remarkWikiLinks';
import { remarkAttachments } from './remarkAttachments';
import { rehypeRenderedFences } from './fences/rehypeRenderedFences';
import rehypeSourceLine from './rehypeSourceLine';
import remarkBlockIds, { rehypeBlockIds } from './remarkBlockIds';
import { WikiEmbedElement } from '../components/WikiEmbed';
import WikiFence from '../components/WikiFence';
import { createLowlight, common } from 'lowlight';
import { rehypeHighlightCode } from './codeHighlight';
import cases from './__fixtures__/block-ids.json';

// The editor preview's plugin stack, as PageEditor builds it (resolution-only plugins left out).
const remarkPlugins = [
  [remarkGfm, { singleTilde: false }],
  [remarkMath, { singleDollarTextMath: false }],
  remarkInlineMath,
  remarkCallouts,
  remarkBlockIds,
  remarkWikiMarkup,
  [remarkWikiLinks, { resolved: new Map(), attachments: [], pageName: 'BlockIdParityHost' }],
  [remarkAttachments, { attachments: [], pageName: 'BlockIdParityHost' }],
];
const rehypePlugins = [rehypeBlockIds, rehypeKatex, rehypeRenderedFences, rehypeSourceLine];
const components = { 'wiki-embed': WikiEmbedElement, 'wiki-fence': WikiFence };

function preview(markdown) {
  const host = document.createElement('div');
  host.innerHTML = renderToStaticMarkup(
    <ReactMarkdown remarkPlugins={remarkPlugins} rehypePlugins={rehypePlugins} components={components}>
      {markdown}
    </ReactMarkdown>);
  return host;
}

/** An access rule the editor shows as a chip; the reader never renders it. */
const isRuleChipParagraph = (el) => el.tagName === 'P' && el.children.length > 0
  && [...el.children].every((c) => c.classList.contains('wiki-plugin-chip'))
  && [...el.childNodes].every((n) => n.nodeType !== Node.TEXT_NODE || n.textContent.trim() === '');

/** What BlockIdParityTest lists from the server HTML: p, li and ^-id elements, outside embeds, math text dropped. */
function describeBlocks(host) {
  const out = [];
  for (const el of host.querySelectorAll('*')) {
    if (el.parentElement?.closest('.wiki-embed') || isRuleChipParagraph(el)) continue;
    const tag = el.tagName.toLowerCase();
    const id = el.getAttribute('id');
    const blockId = id && id.startsWith('^') ? id : null;
    if (tag !== 'p' && tag !== 'li' && !blockId) continue;
    let text = '';
    if (tag === 'p' || tag === 'li') {
      const copy = el.cloneNode(true);
      copy.querySelectorAll('[class*="math"], [class*="katex"]').forEach((m) => m.remove());
      // an access rule's chip: the editor shows it, the reader never renders the rule
      copy.querySelectorAll('.wiki-plugin-chip').forEach((m) => {
        if (/^\[\{(ALLOW|DENY)/.test(m.getAttribute('title') || '')) m.remove();
      });
      text = copy.textContent.replace(/\s+/g, ' ').trim();
    }
    out.push({ tag, id: blockId, text });
  }
  return out;
}

describe('remarkBlockIds', () => {
  it.each(cases.map((c) => [c.name, c]))('matches the shared server fixture: %s', (_n, c) => {
    expect(describeBlocks(preview(c.markdown))).toEqual(c.expected);
  });

  it('leaves no marker text behind', () => {
    const html = preview('Alpha ^abc\n\nPara\n^ll\n\n- x ^k\n\n> q\n\n^q\n\n^lone\n').innerHTML;
    expect(html).not.toMatch(/[ >]\^(abc|ll|k|q|lone)\b/);
  });

  it('keeps the classes a callout already has', () => {
    const el = preview('> [!warning] T\n> body\n\n^cw\n').querySelector('[id="^cw"]');
    expect(el.className).toBe('callout callout-warning');
    expect(el.getAttribute('data-callout')).toBe('warning');
  });

  it('renders the math of a tagged math block', () => {
    const el = preview('$$\nx^2\n$$\n\n^m\n').querySelector('[id="^m"]');
    expect(el.querySelector('.katex-display')).not.toBeNull();
    expect(preview('$$\n$$\n\n^em\n').querySelector('div[id="^em"]')).not.toBeNull();
    const fence = preview('```math\ny\n```\n\n^mf\n').querySelector('[id="^mf"]');
    expect(fence.querySelector('.katex-display')).not.toBeNull();
  });

  it('tags only the first embed of an embed-only paragraph', () => {
    const host = preview('![[A]]\n![[B]] ^two\n');
    const embeds = host.querySelectorAll('.wiki-embed');
    expect(embeds).toHaveLength(2);
    expect(embeds[0].getAttribute('id')).toBe('^two');
    expect(embeds[1].hasAttribute('id')).toBe(false);
  });

  it('reads markers without a source file (no escape check), in a bare pipeline', () => {
    const processor = unified().use(remarkParse).use(remarkBlockIds).use(remarkRehype).use(rehypeBlockIds);
    const tree = processor.runSync(processor.parse('Alpha ^abc\n\n```\nc\n```\n\n^cd\n\n^lone\n'));
    const elements = [];
    const walk = (n) => { if (n.type === 'element') elements.push(n); (n.children || []).forEach(walk); };
    walk(tree);
    const tagged = elements.filter((e) => String(e.properties.id || '').startsWith('^'))
      .map((e) => [e.tagName, e.properties.id]);
    expect(tagged).toEqual([['p', '^abc'], ['pre', '^cd'], ['span', '^lone']]);
  });

  it('never throws on a tree without positions', () => {
    const tree = {
      type: 'root',
      children: [
        { type: 'paragraph', children: [{ type: 'text', value: 'Alpha ^abc' }] },
        { type: 'paragraph', children: [{ type: 'text', value: 'Beta' }] },
        { type: 'paragraph', children: [{ type: 'text', value: '^s' }] },
      ],
    };
    remarkBlockIds()(tree, undefined);
    expect(tree.children).toHaveLength(2);
    expect(tree.children[0].data.hProperties.id).toBe('^abc');
    expect(tree.children[0].children[0].value).toBe('Alpha');
    expect(tree.children[1].data.hProperties.id).toBe('^s');
  });
});

describe('rehypeBlockIds', () => {
  const pre = (codeProps, preProps = {}) => ({
    type: 'root',
    children: [{
      type: 'element', tagName: 'pre', properties: preProps,
      children: [{ type: 'element', tagName: 'code', properties: codeProps, children: [{ type: 'text', value: 'c' }] }],
    }],
  });

  it('moves a block id from the code element to its pre', () => {
    const tree = pre({ id: '^cd', className: ['language-js'] });
    rehypeBlockIds()(tree);
    expect(tree.children[0].properties.id).toBe('^cd');
    expect(tree.children[0].children[0].properties).toEqual({ className: ['language-js'] });
  });

  it('leaves other ids and other parents alone', () => {
    const other = pre({ id: 'custom' });
    rehypeBlockIds()(other);
    expect(other.children[0].properties.id).toBeUndefined();
    const bare = { type: 'root', children: [{ type: 'element', tagName: 'code', properties: { id: '^x' }, children: [] }] };
    rehypeBlockIds()(bare);
    expect(bare.children[0].properties.id).toBe('^x');
    const plain = pre({});
    rehypeBlockIds()(plain);
    expect(plain.children[0].properties).toEqual({});
  });
});

// rehypeBlockIds is meant to run first, but nothing can enforce the order of PageEditor's rehype list, so every
// order must still put each block id where the reader has it.
describe('preview rehype order', () => {
  const lowlight = createLowlight(common);
  const MD = 'Para ^p\n\n```js\nconst x = 1;\n```\n\n^cj\n\n```mermaid\nflowchart TD\n```\n\n^fg\n\n$$\nx^2\n$$\n\n^mt\n';
  const PLUGINS = [['blockIds', rehypeBlockIds], ['katex', rehypeKatex], ['fences', rehypeRenderedFences],
    ['highlight', [rehypeHighlightCode, { lowlight }]]];
  const permutations = (xs) => (xs.length <= 1 ? [xs]
    : xs.flatMap((x, i) => permutations([...xs.slice(0, i), ...xs.slice(i + 1)]).map((rest) => [x, ...rest])));

  it.each(permutations(PLUGINS).map((p) => [p.map(([n]) => n).join(' > '), p.map(([, plugin]) => plugin)]))(
    'ids survive: %s', (_name, rehype) => {
      const host = document.createElement('div');
      host.innerHTML = renderToStaticMarkup(
        <ReactMarkdown remarkPlugins={remarkPlugins} rehypePlugins={[...rehype, rehypeSourceLine]} components={components}>
          {MD}
        </ReactMarkdown>);
      const tagOf = (id) => host.querySelector(`[id="${id}"]`)?.tagName.toLowerCase();
      expect([tagOf('^p'), tagOf('^cj'), tagOf('^fg'), tagOf('^mt')]).toEqual(['p', 'pre', 'figure', 'div']);
      expect(host.querySelector('code[id]')).toBeNull();
    });
});
