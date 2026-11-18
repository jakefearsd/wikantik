import { syntaxTree } from '@codemirror/language';

/** What the caret is in when nothing is: the value toolbar buttons reset to. */
export const EMPTY_FORMAT_STATE = Object.freeze({
  bold: false, italic: false, strike: false, highlight: false, code: false, heading: 0, list: null, quote: false, link: false,
});

const INLINE = {
  StrongEmphasis: 'bold',
  Emphasis: 'italic',
  Strikethrough: 'strike',
  Highlight: 'highlight',
  InlineCode: 'code',
  Link: 'link',
  WikiLink: 'link',
};
const HEADING = /^(?:ATX|Setext)Heading([1-6])$/;

/** The kind of list `list` (a BulletList/OrderedList) holds at the item `item`: a task item makes it 'task'. */
function listKind(list, item) {
  if (hasTask(item)) return 'task';
  return list.name === 'OrderedList' ? 'ordered' : 'bullet';
}

/** Whether the list item starts with a task marker (`[ ]`/`[x]`); the Task node is its child, after the list mark. */
function hasTask(item) {
  for (let child = item?.firstChild; child; child = child.nextSibling) {
    if (child.name === 'Task') return true;
    if (child.name !== 'ListMark') return false;
  }
  return false;
}

/**
 * What formatting the main selection is in, read from the syntax tree: the innermost node at the selection and its
 * ancestors, so the cost is the nesting depth, never the document. With a non-empty selection a flag holds only when
 * the whole selection lies inside one such node (touching its ends counts). The innermost list wins.
 */
export function formatStateAt(state) {
  const { from, to, empty } = state.selection.main;
  const out = { ...EMPTY_FORMAT_STATE };
  let item = null;
  for (let node = syntaxTree(state).resolveInner(from, empty ? -1 : 1); node; node = node.parent) {
    if (node.from > from || node.to < to) continue;
    const inline = INLINE[node.name];
    if (inline) {
      out[inline] = true;
      continue;
    }
    const heading = HEADING.exec(node.name);
    if (heading) {
      if (!out.heading) out.heading = Number(heading[1]);
    } else if (node.name === 'ListItem') {
      if (!item) item = node;
    } else if (node.name === 'BulletList' || node.name === 'OrderedList') {
      if (!out.list) out.list = listKind(node, item);
      item = null;
    } else if (node.name === 'Blockquote') {
      out.quote = true;
    }
  }
  return out;
}
