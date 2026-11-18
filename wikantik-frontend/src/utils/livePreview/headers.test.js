/**
 * Fence headers and callout titles in Rich mode, on a real EditorView: the opening fence line is drawn as a header
 * with a language/kind picker, the closing fence line is hidden, and a callout's `[!type]` is a type picker that stays
 * drawn under the caret. A picker's choice is one transaction changing only the word it names. The caret never rests
 * on a header or a hidden closing line: every way of getting there lands on the nearest line that shows text.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import { EditorState } from '@codemirror/state';
import { EditorView, keymap } from '@codemirror/view';
import { defaultKeymap, history, undo } from '@codemirror/commands';
import { markdown } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { editorMarkdownConfig } from '../editorMarkdown';
import { livePreview, setLivePreview } from './index';
import { blockField } from './plugin';
import { livePreviewSpecs, activeLinesOf } from './ranges';
import { stickyRangesOf } from './sticky';
import { widgetFor } from './widgets';
import {
  FenceHeaderWidget, fenceLanguageOptions, fenceLanguageChange, calloutTypeOptions, calloutTypeChange,
} from './headers';

vi.mock('../fences/drawFence', () => ({ drawFence: vi.fn(() => Promise.resolve('drawn')) }));

const context = { pageName: 'Headers', attachments: [] };
const views = [];
afterEach(() => { while (views.length) { const v = views.pop(); v.destroy(); v.dom.parentNode?.remove(); } });

function mount(doc, { live = true } = {}) {
  const parent = document.createElement('div');
  document.body.appendChild(parent);
  const transactions = [];
  const view = new EditorView({
    parent,
    state: EditorState.create({
      doc,
      extensions: [markdown(editorMarkdownConfig), history(), livePreview({ getContext: () => context }), keymap.of(defaultKeymap),
        EditorView.updateListener.of((u) => { for (const tr of u.transactions) if (tr.docChanged) transactions.push(tr); })],
    }),
  });
  views.push(view);
  view.changesSeen = transactions;
  ensureSyntaxTree(view.state, view.state.doc.length, 60000);
  if (live) setLivePreview(view, true);
  return view;
}
function press(view, key, init = {}) {
  const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init });
  view.contentDOM.dispatchEvent(event);
  return event.defaultPrevented;
}
const caretAt = (view, pos) => view.dispatch({ selection: { anchor: pos }, userEvent: 'select' });
const head = (view) => view.state.selection.main.head;
const text = (view) => view.state.doc.toString();
const lineText = (view, n) => view.contentDOM.querySelectorAll('.cm-line')[n - 1].textContent;
const lineClass = (view, n) => view.contentDOM.querySelectorAll('.cm-line')[n - 1].className;
const rangesOf = (set) => { const out = []; for (const it = set.iter(); it.value; it.next()) out.push([it.from, it.to]); return out; };
/** The changes of the one document-changing transaction seen, as `{from, to, insert}`. */
function soleChange(view) {
  expect(view.changesSeen).toHaveLength(1);
  const out = [];
  view.changesSeen[0].changes.iterChanges((from, to, _fb, _tb, inserted) => out.push({ from, to, insert: inserted.toString() }));
  return out;
}
function choose(select, value) {
  select.value = value;
  select.dispatchEvent(new Event('change', { bubbles: true }));
}
const labels = (select) => [...select.options].map((o) => o.textContent);
const values = (select) => [...select.options].map((o) => o.value);

const CODE = 'Above\n\n```js\nx\ny\n```\n\nBelow';
const OPEN = CODE.indexOf('```js');   // 7
const X = CODE.indexOf('x\n');        // first body line
const Y = CODE.indexOf('y\n');        // last body line
const CLOSE = CODE.lastIndexOf('```'); // closing fence line
const BELOW = CODE.indexOf('Below');

describe('fence header: the opening fence line', () => {
  it('for ```js the header shows js, and neither fence line shows its backticks — with the caret anywhere', () => {
    const view = mount('```js\nx\n```');
    for (const at of [4, 6, 7]) {
      caretAt(view, at);
      const select = view.contentDOM.querySelector('.cm-lp-fence-header select');
      expect(select.value).toBe('js');
      expect(select.selectedOptions[0].textContent).toBe('js');
      expect(view.contentDOM.textContent).not.toContain('`');
    }
    expect(lineClass(view, 1)).toContain('cm-lp-fence');
    expect(lineClass(view, 3)).toContain('cm-lp-fence-close');
    expect(lineText(view, 2)).toBe('x');
  });

  it('choosing python is exactly one change, {from: 3, to: 5, insert: "python"}', () => {
    const view = mount('```js\nx\n```');
    caretAt(view, 6);
    choose(view.contentDOM.querySelector('.cm-lp-fence-header select'), 'python');
    expect(text(view)).toBe('```python\nx\n```');
    expect(soleChange(view)).toEqual([{ from: 3, to: 5, insert: 'python' }]);
    expect(view.changesSeen[0].isUserEvent('input')).toBe(true);
    expect(head(view)).toBe(10); // the caret stayed in the body, mapped
    expect(view.contentDOM.querySelector('.cm-lp-fence-header select').value).toBe('python');
    undo(view);
    expect(text(view)).toBe('```js\nx\n```');
  });

  it('the rest of the info string is kept', () => {
    const view = mount('```js title="a"\nx\n```');
    caretAt(view, 17);
    choose(view.contentDOM.querySelector('.cm-lp-fence-header select'), 'python');
    expect(text(view)).toBe('```python title="a"\nx\n```');
    expect(soleChange(view)).toEqual([{ from: 3, to: 5, insert: 'python' }]);
  });

  it('a fence with no language shows "Code"; choosing one inserts it right after the fence', () => {
    const view = mount('~~~\nx\n~~~');
    caretAt(view, 4);
    const select = view.contentDOM.querySelector('.cm-lp-fence-header select');
    expect(select.selectedOptions[0].textContent).toBe('Code');
    choose(select, 'bash');
    expect(text(view)).toBe('~~~bash\nx\n~~~');
    expect(soleChange(view)).toEqual([{ from: 3, to: 3, insert: 'bash' }]);
  });

  it('the editor has the focus back after a choice', () => {
    const view = mount('```js\nx\n```');
    caretAt(view, 6);
    const focus = vi.spyOn(view, 'focus');
    choose(view.contentDOM.querySelector('.cm-lp-fence-header select'), 'sql');
    expect(focus).toHaveBeenCalled();
  });

  it('options: the rendered kinds, the common languages, then the current value when it is none of those', () => {
    expect(fenceLanguageOptions('js')).toEqual([
      { value: 'mermaid', label: 'Mermaid diagram' }, { value: 'plantuml', label: 'PlantUML diagram' },
      ...['text', 'bash', 'java', 'javascript', 'typescript', 'json', 'yaml', 'sql', 'python', 'xml', 'html', 'css', 'diff'].map((l) => ({ value: l, label: l })),
      { value: 'js', label: 'js' },
    ]);
    expect(fenceLanguageOptions('java')).toHaveLength(15);
    expect(fenceLanguageOptions('').at(-1)).toEqual({ value: '', label: 'Code' });
    const dom = new FenceHeaderWidget({ language: 'mermaid', kind: 'diagram' }).toDOM();
    expect(dom.querySelector('select').selectedOptions[0].textContent).toBe('Mermaid diagram');
    expect(dom.querySelector('select').getAttribute('aria-label')).toBe('Diagram kind');
    expect(new FenceHeaderWidget({ language: 'js', kind: 'code' }).toDOM().querySelector('select').getAttribute('aria-label')).toBe('Code language');
  });

  it('widget equality is by language and kind', () => {
    const w = widgetFor({ type: 'fence-header', language: 'js', kind: 'code' });
    expect(w.eq(new FenceHeaderWidget({ language: 'js', kind: 'code' }))).toBe(true);
    expect(w.eq(new FenceHeaderWidget({ language: 'ts', kind: 'code' }))).toBe(false);
    expect(w.eq(new FenceHeaderWidget({ language: 'js', kind: 'diagram' }))).toBe(false);
  });

  it('fenceLanguageChange: null when nothing would change or the line is not an opening fence', () => {
    const doc = EditorState.create({ doc: '``` js  x\nbody\n```' }).doc;
    expect(fenceLanguageChange(doc, 0, 'js')).toBeNull();
    expect(fenceLanguageChange(doc, 0, 'ts')).toEqual({ from: 4, to: 6, insert: 'ts' });
    expect(fenceLanguageChange(doc, 10, 'ts')).toBeNull();
  });
});

describe('which fences get a header', () => {
  const specsOf = (view) => livePreviewSpecs(view.state, activeLinesOf(view.state), { context });
  const sticky = (view) => specsOf(view).filter((s) => s.sticky).map((s) => [s.from, s.to, s.kind, s.hiddenLine ?? null, s.lineMarker === true]);

  it('a closed fence with a body: the header and the closing line are whole-line sticky ranges, never line markers', () => {
    const view = mount(CODE);
    caretAt(view, 0);
    expect(sticky(view)).toEqual([[OPEN, OPEN + 5, 'widget', 'open', false], [CLOSE, CLOSE + 3, 'hide', 'close', false]]);
    expect(rangesOf(stickyRangesOf(view))).toEqual([[OPEN, OPEN + 5], [CLOSE, CLOSE + 3]]);
  });

  it('an unclosed fence, and one with no body line, stay source (there would be nowhere to type)', () => {
    for (const doc of ['Above\n\n```js\nx', 'Above\n\n```js\n```\n\nBelow']) {
      const view = mount(doc);
      caretAt(view, 0);
      expect(sticky(view)).toEqual([]);
      expect(view.contentDOM.querySelector('.cm-lp-fence-header')).toBeNull();
      expect(lineText(view, 3)).toBe('```js');
      caretAt(view, 9);
      expect(head(view)).toBe(9); // the caret rests on the raw fence line
    }
  });

  it('typing the closing fence leaves the caret where it is, so Enter continues below the block', () => {
    const view = mount('```js\nx\n``');
    caretAt(view, 10);
    view.dispatch({ changes: { from: 10, insert: '`' }, selection: { anchor: 11 }, userEvent: 'input.type' });
    expect(view.contentDOM.querySelector('.cm-lp-fence-header')).not.toBeNull();
    expect(head(view)).toBe(11);
    expect(press(view, 'Backspace')).toBe(true); // not three backticks at once (the hidden range is atomic)
    expect(text(view)).toBe('```js\nx\n```');
    view.dispatch({ changes: { from: 11, insert: '\n' }, selection: { anchor: 12 }, userEvent: 'input' });
    expect(head(view)).toBe(12);
    press(view, 'ArrowUp');
    expect(head(view)).toBe(6); // over the hidden closing line, onto the body
  });

  it('a fence in a quote: its header follows the hidden quote mark, and the closing line is hidden with its mark', () => {
    const view = mount('Para\n\n> ```js\n> code\n> ```\n\nend');
    caretAt(view, 0);
    expect(sticky(view)).toEqual([[6, 8, 'hide', null, true], [8, 13, 'widget', 'open', false], [21, 26, 'hide', 'close', false]]);
    expect(view.contentDOM.querySelectorAll('.cm-line')[2].querySelector('select').value).toBe('js');
    expect(view.contentDOM.querySelectorAll('.cm-line')[2].textContent).not.toMatch(/[>`]/);
    expect(lineText(view, 5)).toBe('');
  });

  it('a rendered diagram is drawn with the caret outside, and shows header and source body with the caret inside', () => {
    const doc = 'Above\n\n```mermaid\ngraph TD; A-->B;\n```\n\nBelow';
    const view = mount(doc);
    const shown = () => rangesOf(view.state.field(blockField).decos);
    caretAt(view, 0);
    expect(shown()).toEqual([[7, doc.indexOf('\n\nBelow')]]);
    expect(sticky(view)).toEqual([]);
    expect(view.contentDOM.querySelector('.cm-lp-fence-header')).toBeNull();

    caretAt(view, 20);
    expect(shown()).toEqual([]);
    expect(view.contentDOM.querySelector('.cm-lp-fence-header select').selectedOptions[0].textContent).toBe('Mermaid diagram');
    expect(view.contentDOM.textContent).toContain('graph TD; A-->B;');
    expect(view.contentDOM.textContent).not.toContain('`');
  });

  it('a click on a drawn diagram puts the caret on its first body line, from above and from below', () => {
    const doc = 'Above\n\n```mermaid\ngraph TD; A-->B;\n```\n\nBelow';
    const body = doc.indexOf('graph');
    for (const from of [0, doc.length]) {
      const view = mount(doc);
      caretAt(view, from);
      view.dom.querySelector('.cm-lp-fence-block').dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
      expect(head(view)).toBe(body);
      expect(view.changesSeen).toEqual([]);
    }
  });

  it('ArrowDown into a drawn diagram lands on its first body line; ArrowUp into it on its last', () => {
    const doc = 'Above\n\n```mermaid\ngraph TD; A-->B;\nB-->C;\n```\n\nBelow';
    const view = mount(doc);
    caretAt(view, 6);
    press(view, 'ArrowDown');
    expect(head(view)).toBe(doc.indexOf('graph'));
    caretAt(view, doc.indexOf('\nBelow'));
    press(view, 'ArrowUp');
    expect(head(view)).toBe(doc.indexOf('B-->C;') + 6);
  });
});

describe('the caret never rests on a header or a hidden closing line', () => {
  it('ArrowUp from the first body line skips the header to the line above the fence', () => {
    const view = mount(CODE);
    caretAt(view, X + 1);
    expect(press(view, 'ArrowUp')).toBe(true);
    expect(head(view)).toBe(6); // the empty line above the fence
    expect(view.state.selection.main.empty).toBe(true);
  });

  it('ArrowDown from the line above skips the header onto the first body line, keeping the column where it fits', () => {
    const view = mount('Above\n```js\nxyz\n```');
    caretAt(view, 2);
    expect(press(view, 'ArrowDown')).toBe(true);
    expect(head(view)).toBe(14);
    caretAt(view, 5);
    press(view, 'ArrowDown');
    expect(head(view)).toBe(15); // `xyz` is shorter: its end
  });

  it('ArrowDown from the last body line skips the closing line; ArrowUp from below comes back over it', () => {
    const view = mount(CODE);
    caretAt(view, Y);
    expect(press(view, 'ArrowDown')).toBe(true);
    expect(head(view)).toBe(CLOSE + 4);
    expect(press(view, 'ArrowUp')).toBe(true);
    expect(head(view)).toBe(Y);
  });

  it('Shift+arrow extends the selection over the hidden line', () => {
    const view = mount(CODE);
    caretAt(view, Y);
    expect(press(view, 'ArrowDown', { shiftKey: true })).toBe(true);
    expect([view.state.selection.main.anchor, head(view)]).toEqual([Y, CLOSE + 4]);
  });

  it('at the document edge the arrow key stays put: a fence on the first or the last line', () => {
    const view = mount('```js\nx\n```');
    caretAt(view, 6);
    expect(press(view, 'ArrowUp')).toBe(true);
    expect(head(view)).toBe(6);
    expect(press(view, 'ArrowDown')).toBe(true);
    expect(head(view)).toBe(6);
  });

  it('two fences back to back: the arrow skips the closing line and the next header together', () => {
    const doc = '```js\nx\n```\n```ts\ny\n```';
    const view = mount(doc);
    caretAt(view, 6);
    press(view, 'ArrowDown');
    expect(head(view)).toBe(doc.indexOf('y'));
    press(view, 'ArrowUp');
    expect(head(view)).toBe(6);
  });

  it('a caret placed on the header (a click, a command) goes on in the direction it came from', () => {
    const view = mount(CODE);
    caretAt(view, 0);
    caretAt(view, OPEN);
    expect(head(view)).toBe(X);
    caretAt(view, BELOW);
    caretAt(view, OPEN + 5);
    expect(head(view)).toBe(6);
  });

  it('a click on either line goes into the block, wherever the caret was', () => {
    const view = mount(CODE);
    const click = (pos) => view.dispatch({ selection: { anchor: pos }, userEvent: 'select.pointer' });
    caretAt(view, BELOW);
    click(OPEN + 2);
    expect(head(view)).toBe(X);
    caretAt(view, 0);
    click(CLOSE);
    expect(head(view)).toBe(Y + 1);
  });

  it('an edit that leaves the caret on the header moves it into the body (typing over a selected language)', () => {
    const view = mount('Above\n```language\nx\n```');
    view.dispatch({ selection: { anchor: 9, head: 17 } });
    view.dispatch({ changes: { from: 9, to: 17, insert: 'j' }, selection: { anchor: 10 }, userEvent: 'input.type' });
    expect(text(view)).toBe('Above\n```j\nx\n```');
    expect(head(view)).toBe(11);
  });

  it('a caret placed on the closing line likewise', () => {
    const view = mount(CODE);
    caretAt(view, 0);
    caretAt(view, CLOSE + 3);
    expect(head(view)).toBe(CLOSE + 4);
    caretAt(view, BELOW);
    caretAt(view, CLOSE);
    expect(head(view)).toBe(Y + 1);
    caretAt(view, CLOSE + 1); // inside the hidden range, coming from above
    expect(head(view)).toBe(CLOSE + 4);
  });

  it('ArrowRight from the end of the line above the fence enters the body; ArrowLeft from the body start leaves upward', () => {
    const view = mount('Above\n```js\nx\n```\nBelow');
    caretAt(view, 5);
    press(view, 'ArrowRight');
    expect(head(view)).toBe(12);
    press(view, 'ArrowLeft');
    expect(head(view)).toBe(5);
    caretAt(view, 13);
    press(view, 'ArrowRight');
    expect(head(view)).toBe(18); // over the closing line
    press(view, 'ArrowLeft');
    expect(head(view)).toBe(13);
  });

  it('with the fence on the first line a caret sent to offset 0 rests on the first body line', () => {
    const view = mount('```js\nx\n```\nBelow');
    caretAt(view, 12);
    caretAt(view, 0);
    expect(head(view)).toBe(6);
  });

  it('a non-empty selection may cover the hidden lines', () => {
    const view = mount(CODE);
    view.dispatch({ selection: { anchor: 0, head: CLOSE + 2 } });
    expect([view.state.selection.main.anchor, head(view)]).toEqual([0, CLOSE + 2]);
  });

  it('outside Rich mode nothing is hidden and the caret rests anywhere', () => {
    const view = mount(CODE, { live: false });
    caretAt(view, OPEN);
    expect(head(view)).toBe(OPEN);
    expect(press(view, 'Backspace')).toBe(true);
    expect(text(view)).toBe(CODE.slice(0, OPEN - 1) + CODE.slice(OPEN));
  });
});

describe('Backspace and Delete never join text onto a hidden fence line', () => {
  it('Backspace at the start of the first body line does nothing', () => {
    const view = mount(CODE);
    caretAt(view, X);
    expect(press(view, 'Backspace')).toBe(true);
    expect(text(view)).toBe(CODE);
    expect(head(view)).toBe(X);
  });

  it('Delete at the end of the last body line does nothing', () => {
    const view = mount(CODE);
    caretAt(view, Y + 1);
    expect(press(view, 'Delete')).toBe(true);
    expect(text(view)).toBe(CODE);
  });

  it('Backspace at the start of the text below the block, and Delete at the end of the text above it, do nothing', () => {
    const view = mount('Above\n```js\nx\n```\nBelow');
    caretAt(view, 18);
    expect(press(view, 'Backspace')).toBe(true);
    caretAt(view, 5);
    expect(press(view, 'Delete')).toBe(true);
    expect(press(view, 'Delete', { ctrlKey: true })).toBe(true);
    expect(text(view)).toBe('Above\n```js\nx\n```\nBelow');
  });

  it('an empty line next to the block is still removed, and the caret does not stay on the hidden line', () => {
    const view = mount(CODE);
    caretAt(view, CLOSE + 4); // the empty line below
    press(view, 'Backspace');
    expect(text(view)).toBe('Above\n\n```js\nx\ny\n```\nBelow');
    expect(head(view)).toBe(Y + 1);
    press(view, 'Backspace');
    expect(text(view)).toBe('Above\n\n```js\nx\n\n```\nBelow'); // an ordinary Backspace in the body
  });

  it('inside the body both keys work as usual', () => {
    const view = mount(CODE);
    caretAt(view, Y);
    press(view, 'Backspace');
    expect(text(view)).toBe('Above\n\n```js\nxy\n```\n\nBelow');
  });
});

describe('callout title: the type picker', () => {
  const CALLOUT = '> [!note] Heads up\n> body\n\nend';

  it('stays drawn with the caret on its line, and the title text is ordinary text', () => {
    const view = mount(CALLOUT);
    caretAt(view, 14);
    expect(head(view)).toBe(14);
    const select = view.contentDOM.querySelector('.cm-lp-callout-title-widget select');
    expect(select.value).toBe('note');
    expect(lineText(view, 1)).toBe(`${labels(select).join('')}Heads up`);
    expect(view.contentDOM.querySelector('.cm-lp-callout-title-widget .cm-lp-callout-icon')).not.toBeNull();
    view.dispatch({ changes: { from: 14, insert: 'X' }, userEvent: 'input.type' });
    expect(text(view)).toBe('> [!note] HeadXs up\n> body\n\nend');
  });

  it('choosing warning rewrites only the word inside [!…]', () => {
    const view = mount(CALLOUT);
    caretAt(view, 14);
    choose(view.contentDOM.querySelector('.cm-lp-callout-title-widget select'), 'warning');
    expect(text(view)).toBe('> [!warning] Heads up\n> body\n\nend');
    expect(soleChange(view)).toEqual([{ from: 4, to: 8, insert: 'warning' }]);
    expect(view.changesSeen[0].isUserEvent('input')).toBe(true);
    expect(view.contentDOM.querySelector('.cm-lp-callout-title-widget select').value).toBe('warning');
    expect(lineClass(view, 1)).toContain('cm-lp-callout-warning');
  });

  it('the fold marker and an upper-case type are kept apart from the choice', () => {
    const view = mount('> [!NOTE]- Folded\n> body');
    caretAt(view, 14);
    const select = view.contentDOM.querySelector('.cm-lp-callout-title-widget select');
    expect(select.value).toBe('note');
    expect(values(select)).toEqual(['note', 'tip', 'info', 'warning', 'danger']);
    choose(select, 'tip');
    expect(text(view)).toBe('> [!tip]- Folded\n> body');
    expect(soleChange(view)).toEqual([{ from: 4, to: 8, insert: 'tip' }]);
  });

  it('options: note tip info warning danger, plus the current type when it is none of those', () => {
    expect(calloutTypeOptions('tip').map((o) => o.value)).toEqual(['note', 'tip', 'info', 'warning', 'danger']);
    expect(calloutTypeOptions('faq')).toEqual([
      { value: 'note', label: 'Note' }, { value: 'tip', label: 'Tip' }, { value: 'info', label: 'Info' },
      { value: 'warning', label: 'Warning' }, { value: 'danger', label: 'Danger' }, { value: 'faq', label: 'Faq' },
    ]);
  });

  it('calloutTypeChange: null when nothing would change or there is no marker at the position', () => {
    const doc = EditorState.create({ doc: '> [!note] x\n> y' }).doc;
    expect(calloutTypeChange(doc, 2, 'note')).toBeNull();
    expect(calloutTypeChange(doc, 2, 'tip')).toEqual({ from: 4, to: 8, insert: 'tip' });
    expect(calloutTypeChange(doc, 14, 'tip')).toBeNull();
  });

  it('the caret rests after the picker; Backspace there removes the marker, then the quote mark', () => {
    const view = mount(CALLOUT);
    caretAt(view, 14);
    caretAt(view, 0);
    expect(head(view)).toBe(10);
    caretAt(view, 5); // inside the marker
    expect(head(view)).toBe(10);
    press(view, 'Backspace');
    expect(text(view)).toBe('> Heads up\n> body\n\nend');
    press(view, 'Backspace');
    expect(text(view)).toBe('Heads up\n> body\n\nend');
  });

  it('a callout with no title shows just the picker, and the caret rests at the line end', () => {
    const view = mount('> [!tip]\n> body');
    caretAt(view, 12);
    caretAt(view, 0);
    expect(head(view)).toBe(8);
    expect(view.contentDOM.querySelector('.cm-lp-callout-title-widget select').selectedOptions[0].textContent).toBe('Tip');
  });

  it('a mousedown on the icon or the picker changes nothing', () => {
    const view = mount(CALLOUT);
    caretAt(view, 22);
    // happy-dom fires `selectionchange` synchronously, mid-update (see roundTrip.test.js): held back for the clicks.
    const hold = (e) => e.stopImmediatePropagation();
    window.addEventListener('selectionchange', hold, true);
    try {
      for (const sel of ['.cm-lp-callout-icon', '.cm-lp-callout-title-widget select']) {
        view.contentDOM.querySelector(sel).dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
      }
    } finally {
      window.removeEventListener('selectionchange', hold, true);
    }
    expect(view.changesSeen).toEqual([]);
    expect(text(view)).toBe(CALLOUT);
  });
});
