import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';

const parser = unified().use(remarkParse).use(remarkGfm);
let last = { md: null, tree: null };

/**
 * The GFM mdast of `md`, reusing the previous result for the same text: after every typing pause the editor
 * scans the same body twice (wikilink resolution and the missing-page check), and each whole-page parse costs
 * ~50 ms on a 2,000-line page. Callers must treat the tree as read-only.
 */
export function parseGfm(md) {
  if (last.md !== md) last = { md, tree: parser.parse(md) };
  return last.tree;
}
