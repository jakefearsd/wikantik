/**
 * Characterisation of the live-preview tree walk's per-node dispatch: one document that reaches every node name the
 * walk handles (both the early-return ones and the switch ones), decorated with no active line, with a caret inside
 * some lines, and over a sub-range. The snapshots pin the specs and their push order.
 */
import { describe, it, expect } from 'vitest';
import { EditorState, EditorSelection } from '@codemirror/state';
import { markdown } from '@codemirror/lang-markdown';
import { syntaxTree } from '@codemirror/language';
import { fullyParsed } from '../../test/fullParse';
import { editorMarkdownConfig } from '../editorMarkdown';
import { livePreviewSpecs, activeLinesOf } from './ranges';

const CONTEXT = { pageName: 'HandlerHost', attachments: [] };
const DOC = [
  '---',
  'type: note',
  '---',
  '# One',
  '## Two',
  '### Three',
  '#### Four',
  '##### Five',
  '###### Six',
  '',
  'Setext One',
  '==========',
  '',
  'Setext Two',
  '----------',
  '',
  'Plain *em* **strong** ~~del~~ `code` \\* esc ==hi== $x+1$ and [[Page|alias]] ![[Img.png]] #tag.',
  '',
  'See <https://example.com/a> and https://bare.example.org/b and [link](https://e.com/x "t") and ![alt](https://e.com/i.png).',
  '',
  '[ref]: https://e.com/ref',
  'Use [defined][ref] and [undefined][nope] and a footnote[^1].',
  '',
  '[^1]: The note text.',
  '',
  '> quoted line',
  '> second',
  '',
  '> [!note] Title',
  '> body',
  '',
  '---',
  '',
  '- bullet',
  '1. numbered',
  '- [ ] task open',
  '- [x] task done',
  '',
  '```js',
  'const a = 1;',
  '```',
  '',
  '```mermaid',
  'graph TD; A-->B;',
  '```',
  '',
  '    indented code',
  '',
  '$$',
  'x^2',
  '$$',
  '',
  '<div>html</div>',
  '',
  '<!-- comment -->',
  '',
  '<?pi data?>',
  '',
  '| a | b |',
  '|---|---|',
  '| 1 | 2 |',
  '',
  '%% a hidden comment %%',
  '',
  '[{Plugin arg=1}] trailing ^blk',
  '',
].join('\n');

const state = () => fullyParsed(EditorState.create({
  doc: DOC,
  extensions: [markdown(editorMarkdownConfig)],
  selection: EditorSelection.single(DOC.indexOf('Plain')),
}));

describe('livePreviewSpecs node dispatch (characterisation)', () => {
  it('the fixture reaches every handled node name', () => {
    const seen = new Set();
    syntaxTree(state()).iterate({ enter: (n) => { seen.add(n.name); } });
    const want = ['Frontmatter', 'Table', 'HTMLBlock', 'CommentBlock', 'ProcessingInstructionBlock', 'CodeBlock', 'LinkReference', 'FencedCode',
      'InlineCode', 'DisplayMath', 'InlineMath', 'WikiLink', 'WikiEmbed', 'Paragraph', 'Task', 'Image',
      'ATXHeading1', 'ATXHeading2', 'ATXHeading3', 'ATXHeading4', 'ATXHeading5', 'ATXHeading6', 'SetextHeading1',
      'SetextHeading2', 'Autolink', 'URL', 'Escape', 'Emphasis', 'StrongEmphasis', 'Strikethrough', 'Link',
      'Blockquote', 'QuoteMark', 'HorizontalRule', 'ListItem', 'Highlight'];
    expect(want.filter((n) => !seen.has(n))).toEqual([]);
  });

  it('no active line', () => {
    expect(livePreviewSpecs(state(), new Set(), { context: CONTEXT })).toMatchSnapshot();
  });

  it('every line active', () => {
    const s = state();
    const all = new Set(Array.from({ length: s.doc.lines }, (_, i) => i + 1));
    expect(livePreviewSpecs(s, all, { context: CONTEXT })).toMatchSnapshot();
  });

  it('caret in a paragraph, and a sub-range', () => {
    const s = state();
    expect(livePreviewSpecs(s, activeLinesOf(s), { context: CONTEXT })).toMatchSnapshot();
    const a = s.doc.line(16).from;
    const b = s.doc.line(30).to;
    expect(livePreviewSpecs(s, new Set(), { from: a, to: b, context: CONTEXT })).toMatchSnapshot();
  });
});
