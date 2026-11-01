/**
 * Vertical motion across block widgets, on a real EditorView: in live mode ArrowDown from the line above a shown
 * block (table, display math, embed, rendered fence) lands at the start of its first line and ArrowUp from the line
 * below at the start of its last line — revealing it — instead of CodeMirror skipping the whole replaced range.
 * Shift extends the selection the same way. Everywhere else (no block in the way, source mode) the binding declines,
 * so CodeMirror's own motion runs unchanged.
 */
import { describe, it, expect, afterEach } from 'vitest';
import { EditorState, EditorSelection } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { markdown } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { editorMarkdownConfig } from '../editorMarkdown';
import { livePreview, setLivePreview } from './index';
import { blockField } from './plugin';
import { moveIntoBlock } from './verticalMotion';

const context = {
  pageName: 'Motion',
  attachments: [],
  loadEmbed: () => Promise.resolve({ state: 'ok', html: '<p>embedded</p>' }),
};

const TABLE = '| A | B |\n| --- | --- |\n| one | two |';
const MATH = '$$\nx = 1\n$$';
const FENCE = '```mermaid\ngraph TD; A-->B;\n```';
const EMBED = '![[Other]]';
const DOC = `Intro\n\n${TABLE}\n\nMiddle\n\n${MATH}\n\nThen\n\n${FENCE}\n\nAnd\n\n${EMBED}\n\nEnd\n`;

const views = [];
afterEach(() => { while (views.length) { const v = views.pop(); v.destroy(); v.dom.parentNode?.remove(); } });

function mount(doc = DOC, live = true, extra = []) {
  const parent = document.createElement('div');
  document.body.appendChild(parent);
  const view = new EditorView({
    parent,
    state: EditorState.create({ doc, extensions: [markdown(editorMarkdownConfig), livePreview({ getContext: () => context }), extra] }),
  });
  views.push(view);
  ensureSyntaxTree(view.state, view.state.doc.length, 60000);
  if (live) setLivePreview(view, true);
  return view;
}

const lineOf = (view, text) => {
  for (let n = 1; n <= view.state.doc.lines; n += 1) if (view.state.doc.line(n).text === text) return view.state.doc.line(n);
  throw new Error(`no line ${JSON.stringify(text)}`);
};
const shownBlocks = (view) => {
  const out = [];
  for (const it = view.state.field(blockField).decos.iter(); it.value; it.next()) out.push([it.from, it.to]);
  return out;
};
/** Presses `key` (with Shift when `shift`) on the editor; returns whether a binding handled it. */
function press(view, key, shift = false) {
  const event = new KeyboardEvent('keydown', { key, shiftKey: shift, bubbles: true, cancelable: true });
  view.contentDOM.dispatchEvent(event);
  return event.defaultPrevented;
}
const caretAt = (view, pos) => view.dispatch({ selection: { anchor: pos } });

const BLOCKS = [
  ['table', TABLE, 'Intro', 'Middle'],
  ['display math', MATH, 'Middle', 'Then'],
  ['rendered fence', FENCE, 'Then', 'And'],
  ['page embed', EMBED, 'And', 'End'],
];

describe('live preview: arrow keys move into block widgets', () => {
  it('draws every block kind of the fixture as a shown widget before any motion', () => {
    const view = mount();
    caretAt(view, 0);
    expect(shownBlocks(view)).toHaveLength(BLOCKS.length);
  });

  it.each(BLOCKS)('ArrowDown from the line above a %s lands at the start of its first line, revealing it', (_k, block, above) => {
    const view = mount();
    const first = lineOf(view, block.split('\n')[0]);
    caretAt(view, lineOf(view, above).to + 1); // the empty line directly above the block
    const shown = shownBlocks(view).length;

    expect(press(view, 'ArrowDown')).toBe(true);
    expect(view.state.selection.main.head).toBe(first.from);
    expect(view.state.selection.main.empty).toBe(true);
    expect(shownBlocks(view)).toHaveLength(shown - 1); // revealed: its source is on screen
    expect(view.state.doc.toString()).toBe(DOC);
  });

  it.each(BLOCKS)('ArrowUp from the line below a %s lands at the start of its last line, revealing it', (_k, block, _a, below) => {
    const view = mount();
    const lines = block.split('\n');
    const below0 = lineOf(view, below).from - 1; // the empty line directly below the block
    const last = view.state.doc.line(view.state.doc.lineAt(below0).number - 1);
    expect(last.text).toBe(lines[lines.length - 1]);
    caretAt(view, below0);
    const shown = shownBlocks(view).length;

    expect(press(view, 'ArrowUp')).toBe(true);
    expect(view.state.selection.main.head).toBe(last.from);
    expect(shownBlocks(view)).toHaveLength(shown - 1);
    expect(view.state.doc.toString()).toBe(DOC);
  });

  it('Shift+ArrowDown / Shift+ArrowUp extend the selection with the head on that line', () => {
    const view = mount();
    const above = lineOf(view, 'Intro').to + 1;
    caretAt(view, above);
    expect(press(view, 'ArrowDown', true)).toBe(true);
    expect(view.state.selection.main.anchor).toBe(above);
    expect(view.state.selection.main.head).toBe(lineOf(view, '| A | B |').from);

    const below = lineOf(view, 'Then').from - 1;
    caretAt(view, below);
    expect(press(view, 'ArrowUp', true)).toBe(true);
    expect(view.state.selection.main.anchor).toBe(below);
    const mathLast = view.state.doc.line(view.state.doc.lineAt(below).number - 1); // the closing $$
    expect(mathLast.text).toBe('$$');
    expect(view.state.selection.main.head).toBe(mathLast.from);
  });

  it('declines when no shown block is next to the caret (CodeMirror moves as before)', () => {
    const view = mount();
    caretAt(view, lineOf(view, 'Intro').from); // two lines above the table: the empty line is in between
    const before = view.state.selection;
    expect(press(view, 'ArrowDown')).toBe(false);
    expect(press(view, 'ArrowUp')).toBe(false);
    expect(view.state.selection.eq(before)).toBe(true);
    expect(moveIntoBlock(view, true, false)).toBe(false);
  });

  it('declines next to a block that is already revealed', () => {
    const view = mount(DOC, true, EditorState.allowMultipleSelections.of(true));
    const first = lineOf(view, '| A | B |');
    view.dispatch({ selection: EditorSelection.create([EditorSelection.cursor(first.from - 1), EditorSelection.cursor(first.from)]) });
    // The second cursor reveals the table, so the first one's ArrowDown is plain text motion.
    expect(view.state.selection.ranges).toHaveLength(2);
    expect(shownBlocks(view)).toHaveLength(BLOCKS.length - 1);
    expect(moveIntoBlock(view, true, false)).toBe(false);
  });

  it('does nothing in source mode', () => {
    const view = mount(DOC, false);
    caretAt(view, lineOf(view, 'Intro').to + 1);
    const before = view.state.selection;
    expect(press(view, 'ArrowDown')).toBe(false);
    expect(view.state.selection.eq(before)).toBe(true);
  });
});
