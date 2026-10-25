import { visit } from 'unist-util-visit';
import { parseFenceInfo } from './fenceInfo';
import { rendererFor } from './registry';

const classesOf = (cls) => (Array.isArray(cls) ? cls : String(cls || '').split(/\s+/).filter(Boolean));

function hastText(node) {
  if (node.type === 'text') return node.value;
  return (node.children || []).map(hastText).join('');
}

/**
 * rehype plugin (editor preview): replaces `pre > code.language-X` with a `wiki-fence` element carrying
 * `{ language, source }` whenever the fence is a registered, non-`example` rendered fence. remark-rehype puts the
 * info string's words after the first on `code.data.meta`, so the full info string is `X + ' ' + meta`.
 * The source has CRLF/CR normalised to LF, as the server (Flexmark) does. Must run BEFORE the highlight plugin so the highlighter never sees (and never mutates) a diagram fence.
 */
export function rehypeRenderedFences() {
  return (tree) => {
    visit(tree, 'element', (node, index, parent) => {
      if (node.tagName !== 'pre' || !parent || index == null) return;
      const code = node.children.length === 1 && node.children[0].type === 'element' && node.children[0].tagName === 'code'
        ? node.children[0] : null;
      if (!code) return;
      const cls = classesOf(code.properties?.className).find((c) => c.startsWith('language-'));
      if (!cls) return;
      const info = `${cls.slice('language-'.length)} ${code.data?.meta ?? ''}`;
      if (!rendererFor(parseFenceInfo(info))) return;
      parent.children[index] = {
        type: 'element',
        tagName: 'wiki-fence',
        properties: { language: parseFenceInfo(info).language, source: hastText(code).replace(/\r\n?/g, '\n') },
        children: [],
      };
    });
  };
}
