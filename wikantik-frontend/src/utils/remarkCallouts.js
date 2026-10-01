// Obsidian callouts in the editor preview. Mirrors the server's CalloutExtension exactly — the shared
// fixture (__fixtures__/callouts.json) pins the two together.
import { visit } from 'unist-util-visit';

const STYLE = {
  note: 'note', abstract: 'abstract', summary: 'abstract', tldr: 'abstract', info: 'info', todo: 'todo',
  tip: 'tip', hint: 'tip', important: 'tip', success: 'success', check: 'success', done: 'success',
  question: 'question', help: 'question', faq: 'question', warning: 'warning', caution: 'warning',
  attention: 'warning', failure: 'failure', fail: 'failure', missing: 'failure', danger: 'danger',
  error: 'danger', bug: 'bug', example: 'example', quote: 'quote', cite: 'quote',
};
const MARKER = /^\[!([A-Za-z][A-Za-z0-9_-]*)\]([+-]?)[ \t]*/;

export function styleOf(rawType) {
  return STYLE[rawType.toLowerCase()] || 'note';
}

export function defaultTitle(rawType) {
  return rawType ? rawType[0].toUpperCase() + rawType.slice(1) : '';
}

/** Splits the paragraph's first line (after the marker) off as title inline nodes. */
function takeTitle(paragraph, markerLength) {
  const title = [];
  const children = paragraph.children;
  const first = children[0];
  first.value = first.value.slice(markerLength);
  while (children.length > 0) {
    const node = children[0];
    if (node.type === 'text' && node.value.includes('\n')) {
      const at = node.value.indexOf('\n');
      const before = node.value.slice(0, at);
      node.value = node.value.slice(at + 1);
      if (before) title.push({ type: 'text', value: before });
      if (!node.value) children.shift();
      return title;
    }
    if (node.type === 'break') { children.shift(); return title; }
    title.push(children.shift());
  }
  return title;
}

function isContentWrapper(node) {
  return node.data?.hName === 'div' && node.data.hProperties?.className?.includes('callout-content');
}

export default function remarkCallouts() {
  return (tree) => {
    visit(tree, 'blockquote', (node) => {
      if (isContentWrapper(node)) return;
      const paragraph = node.children[0];
      if (!paragraph || paragraph.type !== 'paragraph') return;
      const first = paragraph.children[0];
      if (!first || first.type !== 'text') return;
      const m = MARKER.exec(first.value);
      if (!m) return;

      const [, rawType, foldMarker] = m;
      const style = styleOf(rawType);
      const folded = foldMarker === '+' || foldMarker === '-';
      let titleNodes = takeTitle(paragraph, m[0].length);
      titleNodes = titleNodes.filter((n) => !(n.type === 'text' && n.value.trim() === ''));
      if (titleNodes.length > 0 && titleNodes[titleNodes.length - 1].type === 'text') {
        titleNodes[titleNodes.length - 1].value = titleNodes[titleNodes.length - 1].value.replace(/\s+$/, '');
      }
      if (paragraph.children.length === 0) node.children.shift();

      const titleNode = {
        type: 'paragraph',
        data: { hName: folded ? 'summary' : 'div', hProperties: { className: ['callout-title'] } },
        children: [
          { type: 'emphasis', data: { hName: 'span', hProperties: { className: ['callout-icon'], ariaHidden: 'true' } }, children: [] },
          {
            type: 'emphasis',
            data: { hName: 'span', hProperties: { className: ['callout-title-inner'] } },
            children: titleNodes.length > 0 ? titleNodes : [{ type: 'text', value: defaultTitle(rawType) }],
          },
        ],
      };
      const contentNode = {
        type: 'blockquote',
        data: { hName: 'div', hProperties: { className: ['callout-content'] } },
        children: node.children,
      };
      node.data = {
        hName: folded ? 'details' : 'div',
        hProperties: {
          className: ['callout', `callout-${style}`],
          dataCallout: style,
          ...(foldMarker === '+' ? { open: true } : {}),
        },
      };
      node.children = [titleNode, contentNode];
    });
  };
}
