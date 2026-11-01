// Text-only inline Markdown for table-cell widgets: parsed with the same remark stack as the preview, but
// rendered node-by-node into DOM using only createElement/createTextNode — page text never reaches innerHTML,
// links never get an href, and raw HTML / unknown nodes are shown as their source text.
import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import { remarkWikiLinks } from '../remarkWikiLinks';
import { renderedMath } from './mathRender';

const processor = unified().use(remarkParse).use(remarkGfm).use(remarkMath).use(remarkWikiLinks);

function el(tag, className) {
  const e = document.createElement(tag);
  if (className) e.className = className;
  return e;
}

function plainText(node, source) {
  if (typeof node.value === 'string') return node.value;
  if (node.children) return node.children.map((c) => plainText(c, source)).join('');
  return sliceOf(node, source);
}

function sliceOf(node, source) {
  const s = node.position?.start?.offset;
  const e = node.position?.end?.offset;
  return s == null || e == null ? '' : source.slice(s, e);
}

function wrap(tag, node, source, className) {
  const e = el(tag, className);
  appendChildren(e, node.children, source);
  return e;
}

function renderNode(node, source) {
  switch (node.type) {
    case 'text': return document.createTextNode(node.value);
    case 'emphasis': return wrap('em', node, source);
    case 'strong': return wrap('strong', node, source);
    case 'delete': return wrap('del', node, source);
    case 'inlineCode': { const e = el('code'); e.textContent = node.value; return e; }
    case 'link': return wrap('span', node, source, 'cm-lp-link');
    case 'image': { const e = el('span', 'cm-lp-cell-img'); e.textContent = node.alt || (node.url || '').split(/[?#]/)[0].split('/').pop() || ''; return e; }
    case 'inlineMath': {
      const math = renderedMath(node.value, false);
      if (math.fragment) {
        const e = el('span', 'cm-lp-math');
        e.appendChild(math.fragment.cloneNode(true));
        return e;
      }
      return document.createTextNode(sliceOf(node, source));
    }
    case 'break': return el('br');
    default: return document.createTextNode(sliceOf(node, source) || plainText(node, source));
  }
}

function appendChildren(parent, children, source) {
  for (const child of children || []) parent.appendChild(renderNode(child, source));
}

/** A DocumentFragment of the inline Markdown in `text`; never contains anchors, images, scripts or raw HTML. */
export function renderInlineMarkdown(text) {
  const fragment = document.createDocumentFragment();
  if (!text) return fragment;
  try {
    const tree = processor.runSync(processor.parse(text), text);
    const first = tree.children.find((n) => n.type === 'paragraph');
    if (first) appendChildren(fragment, first.children, text);
    else fragment.appendChild(document.createTextNode(text));
  } catch (err) {
    console.warn('[live-preview] inline cell render failed', err?.message || err);
    fragment.replaceChildren(document.createTextNode(text));
  }
  return fragment;
}
