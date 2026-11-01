// `^id` block markers in the editor preview, rendered as the reader renders them (the server's BlockIdPostProcessor):
// the block's element gets id="^id", the marker text is taken out, and an orphan standalone marker leaves an empty
// <span id="^id" class="block-anchor"></span>. __fixtures__/block-ids.json pins the two together. The preview stamps no
// data-block ordinals: only the reader uses them. Visuals only: no id the editor links to or adds comes from here —
// block identity is the server's (POST /blocks/draft, see draftBlockLink.js).
//
// Runs after remarkCallouts (a callout's id goes on its outer element) and before remarkWikiMarkup (access rules are
// still `[{…}]` text, not chips) and remarkWikiLinks (which moves an embed-only paragraph's id onto its embed).
import { walkBlocks, detachMarkers } from './blockIds';

const anchor = (id) => ({
  type: 'blockAnchor',
  children: [],
  data: { hName: 'span', hProperties: { id: `^${id}`, className: ['block-anchor'] } },
});

/** The math of `$$…$$` (or a `math` fence) drawn inside a div that carries the id, as the server's math block is. */
function asMathDiv(node, id) {
  const code = {
    type: 'element',
    tagName: 'code',
    properties: { className: ['language-math', 'math-display'] },
    children: [{ type: 'text', value: node.value || '' }],
  };
  node.type = 'math';
  delete node.lang;
  delete node.meta;
  node.data = { ...node.data, hName: 'div', hProperties: { ...node.data?.hProperties, id: `^${id}` }, hChildren: [code] };
}

function setId(node, id) {
  if (node.type === 'math' || node.type === 'code' && node.lang === 'math' && !node.meta) {
    asMathDiv(node, id);
    return;
  }
  // A code block's id lands on its <code>; rehypeBlockIds moves it to the <pre>.
  node.data = { ...node.data, hProperties: { ...node.data?.hProperties, id: `^${id}` } };
}

/** remark plugin: block ids onto their blocks, markers out of the text. */
export default function remarkBlockIds() {
  return (tree, file) => {
    // The source the positions index; without one (a bare pipeline) escapes cannot be told apart and are trusted.
    const source = file?.value == null ? undefined : String(file);
    const walk = walkBlocks(tree, source);
    for (const e of walk.entries) {
      const id = walk.idOf(e);
      if (id) setId(e.node, id);
    }
    detachMarkers(walk, (m) => anchor(m.id));
  };
}

/** rehype plugin: a code block's block id moved from its <code> to its <pre>, where the reader has it. Run it first. */
export function rehypeBlockIds() {
  return (tree) => {
    const walk = (node) => {
      for (const child of node.children || []) {
        const code = child.type === 'element' && child.tagName === 'pre' && child.children?.length === 1
          ? child.children[0] : null;
        const id = code?.type === 'element' && code.tagName === 'code' ? code.properties?.id : null;
        if (typeof id === 'string' && id.startsWith('^')) {
          delete code.properties.id;
          child.properties = { ...child.properties, id };
        }
        walk(child);
      }
    };
    walk(tree);
  };
}
