import { visit, SKIP } from 'unist-util-visit';
import { toString } from 'mdast-util-to-string';
import { safeDecode } from './safeDecode';

// Bare `[{…}]`, optionally followed by `()` — remark leaves it as literal text.
const BARE_PLUGIN = /\[\{([^}\n]*)\}\](?:\(\))?/g;
const MAX_LABEL = 40;
const truncate = (s) => (s.length > MAX_LABEL ? `${s.slice(0, MAX_LABEL - 1)}…` : s);

/** Label for the inside of `[{…}]`: directives keep their text, plugins show their unqualified name. */
export function pluginChipLabel(inner) {
  const text = inner.trim();
  const first = text.split(/\s+/)[0] || '';
  const upper = first.toUpperCase();
  if (upper === 'ALLOW' || upper === 'DENY') return `🔒 ${truncate(text)}`;
  if (upper === 'SET') return `≔ ${truncate(text)}`;
  if (first.startsWith('$')) return truncate(text);
  return `⚙ ${first.split('.').pop()}`;
}

function chipNode(inner) {
  return {
    type: 'wikiPluginChip',
    data: {
      hName: 'span',
      hProperties: { className: ['wiki-plugin-chip'], title: `[{${inner}}]` },
      hChildren: [{ type: 'text', value: pluginChipLabel(inner) }],
    },
  };
}

function citeParts(url) {
  const rest = url.slice('cite://'.length);
  const slash = rest.indexOf('/');
  return slash < 0
    ? { target: safeDecode(rest), heading: '' }
    : { target: safeDecode(rest.slice(0, slash)), heading: safeDecode(rest.slice(slash + 1)) };
}

function citeBadge(link) {
  const { target, heading } = citeParts(link.url);
  return {
    type: 'wikiCiteBadge',
    data: {
      hName: 'span',
      hProperties: { className: ['wiki-cite-badge'], title: link.title || '', 'data-cite-target': target },
      hChildren: [{ type: 'text', value: `↗ ${target}${heading ? ` § ${heading}` : ''}` }],
    },
  };
}

function mergeAdjacentText(tree) {
  visit(tree, (node) => {
    if (!node.children) return;
    const merged = [];
    for (const child of node.children) {
      const prev = merged[merged.length - 1];
      if (child.type === 'text' && prev && prev.type === 'text') prev.value += child.value;
      else merged.push(child);
    }
    node.children = merged;
  });
}

/** remark plugin: `[{…}]` plugin/directive markup → labelled chips; `cite://` links → link + target badge. */
export function remarkWikiMarkup() {
  return (tree) => {
    visit(tree, 'link', (node, index, parent) => {
      if (!parent || index == null) return undefined;
      if (node.url === '') {
        const m = /^\{([\s\S]*)\}$/.exec(toString(node));
        if (m) {
          parent.children[index] = chipNode(m[1]);
          return SKIP;
        }
      }
      if (node.url.startsWith('cite://')) {
        parent.children.splice(index + 1, 0, citeBadge(node));
        return [SKIP, index + 2];
      }
      return undefined;
    });
    mergeAdjacentText(tree);
    visit(tree, 'text', (node, index, parent) => {
      if (!parent || index == null) return undefined;
      BARE_PLUGIN.lastIndex = 0;
      const parts = [];
      let last = 0;
      let m;
      while ((m = BARE_PLUGIN.exec(node.value))) {
        if (m.index > last) parts.push({ type: 'text', value: node.value.slice(last, m.index) });
        parts.push(chipNode(m[1]));
        last = m.index + m[0].length;
      }
      if (parts.length === 0) return undefined;
      if (last < node.value.length) parts.push({ type: 'text', value: node.value.slice(last) });
      parent.children.splice(index, 1, ...parts);
      return [SKIP, index + parts.length];
    });
  };
}

export { citeParts };
