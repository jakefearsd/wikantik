import { visit } from 'unist-util-visit';

const LANG_CLASS = /(?:^|\s)language-([\w+#-]+)/;
let lowlightPromise = null;

/** lowlight with highlight.js's "common" grammars, loaded once and code-split out of the main bundle. */
export function loadLowlight() {
  if (!lowlightPromise) {
    lowlightPromise = import('lowlight').then(({ createLowlight, common }) => createLowlight(common));
  }
  return lowlightPromise;
}

const toArray = (cls) => (Array.isArray(cls) ? cls : String(cls || '').split(/\s+/).filter(Boolean));

/** The declared language of a code element's class (`language-x`), lowercased, or null. */
export function codeLanguage(className) {
  const m = LANG_CLASS.exec(toArray(className).join(' '));
  return m ? m[1].toLowerCase() : null;
}

function hastText(node) {
  if (node.type === 'text') return node.value;
  return (node.children || []).map(hastText).join('');
}

// The preview re-runs on every keystroke; memoise lowlight output per (language, text), bounded.
const CACHE_LIMIT = 200;
const highlightCaches = new WeakMap(); // per lowlight instance

function highlightCached(lowlight, lang, text) {
  let highlightCache = highlightCaches.get(lowlight);
  if (!highlightCache) highlightCaches.set(lowlight, highlightCache = new Map());
  const key = `${lang}\u0000${text}`;
  let children = highlightCache.get(key);
  if (!children) {
    children = lowlight.highlight(lang, text).children;
    if (highlightCache.size >= CACHE_LIMIT) highlightCache.delete(highlightCache.keys().next().value);
    highlightCache.set(key, children);
  }
  return children;
}

/** rehype plugin (editor preview): highlight `pre > code.language-x` once `lowlight` has loaded. */
export function rehypeHighlightCode({ lowlight } = {}) {
  return (tree) => {
    if (!lowlight) return;
    visit(tree, 'element', (node, _index, parent) => {
      if (node.tagName !== 'code' || !parent || parent.tagName !== 'pre') return;
      const lang = codeLanguage(node.properties?.className);
      if (!lang || !lowlight.registered(lang)) return;
      let children;
      try {
        children = highlightCached(lowlight, lang, hastText(node));
      } catch (err) {
        console.warn(`[highlight] failed to highlight ${lang} block`, err?.message || err);
        return;
      }
      node.children = children;
      node.properties.className = [...toArray(node.properties.className), 'hljs'];
    });
  };
}

/** Converts lowlight's hast (spans with classes, text) to DOM nodes. */
export function hastToDom(node, doc) {
  if (node.type === 'text') return doc.createTextNode(node.value);
  const el = doc.createElement(node.tagName);
  const cls = toArray(node.properties?.className);
  if (cls.length) el.className = cls.join(' ');
  (node.children || []).forEach((child) => el.appendChild(hastToDom(child, doc)));
  return el;
}

/** Page-view DOM pass over server HTML: highlight `pre > code.language-x`; idempotent. */
export function highlightCodeBlocks(container, lowlight) {
  if (!container || !lowlight) return;
  container.querySelectorAll('pre > code').forEach((code) => {
    if (code.dataset.highlighted) return;
    const lang = codeLanguage(code.className);
    if (!lang || !lowlight.registered(lang)) return;
    let tree;
    try {
      tree = lowlight.highlight(lang, code.textContent);
    } catch (err) {
      console.warn(`[highlight] failed to highlight ${lang} block`, err?.message || err);
      return;
    }
    code.replaceChildren(...tree.children.map((n) => hastToDom(n, code.ownerDocument)));
    code.classList.add('hljs');
    code.dataset.highlighted = '1';
  });
}
