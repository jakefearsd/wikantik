import { describe, it, expect, vi, afterEach } from 'vitest';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { markdown } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { fireEvent } from '@testing-library/react';
import { editorMarkdownConfig } from '../editorMarkdown';
import { livePreview, setLivePreview } from './index';
import { parseTableSource, TableWidget } from './tableWidget';
import { widgetFor } from './widgets';

const views = [];
function liveView(doc, caret = doc.length) {
  const parent = document.createElement('div');
  document.body.appendChild(parent);
  const view = new EditorView({
    parent,
    state: EditorState.create({ doc, extensions: [markdown(editorMarkdownConfig), livePreview({ getContext: () => ({}) })] }),
  });
  ensureSyntaxTree(view.state, view.state.doc.length, 5000);
  view.dispatch({ selection: { anchor: caret } });
  setLivePreview(view, true);
  views.push(view);
  return view;
}
afterEach(() => { while (views.length) views.pop().destroy(); vi.restoreAllMocks(); document.body.innerHTML = ''; });

describe('parseTableSource', () => {
  it('reads each column alignment from the delimiter row', () => {
    const t = parseTableSource('| a | b | c | d |\n|:--|:-:|--:|---|\n| 1 | 2 | 3 | 4 |');
    expect(t.align).toEqual(['left', 'center', 'right', null]);
    expect(t.header).toEqual(['a', 'b', 'c', 'd']);
    expect(t.rows).toEqual([['1', '2', '3', '4']]);
  });
  it('treats an escaped pipe as cell text', () => {
    const t = parseTableSource('| a | b |\n|---|---|\n| x \\| y | `p\\|q` |');
    expect(t.rows).toEqual([['x | y', '`p|q`']]);
  });
  it('keeps other backslash escapes for the inline renderer', () => {
    expect(parseTableSource('| a |\n|---|\n| \\*x\\* |').rows).toEqual([['\\*x\\*']]);
  });
  it('pads short rows and truncates long rows to the header width', () => {
    const t = parseTableSource('| a | b | c |\n|---|---|---|\n| 1 |\n| 1 | 2 | 3 | 4 | 5 |');
    expect(t.rows).toEqual([['1', '', ''], ['1', '2', '3']]);
  });
  it('reads rows without outer pipes, and a lazy continuation line as a one-cell row', () => {
    const t = parseTableSource('a | b\n--|--:\n1|2\nlazy line');
    expect(t.header).toEqual(['a', 'b']);
    expect(t.align).toEqual([null, 'right']);
    expect(t.rows).toEqual([['1', '2'], ['lazy line', '']]);
  });
  it('trims cell whitespace and an indented line', () => {
    const t = parseTableSource('  |  a  |b|\n  |---|---|\n  |   |  2 |');
    expect(t.header).toEqual(['a', 'b']);
    expect(t.rows).toEqual([['', '2']]);
  });
  it('a header-only table has no rows', () => {
    expect(parseTableSource('| a |\n|---|')).toEqual({ align: [null], header: ['a'], rows: [] });
  });
});

describe('TableWidget', () => {
  const SRC = '| Name | Qty | Note |\n|:-----|:---:|-----:|\n| **a** | 1 | `c` |\n| b | 2 | [[P\\|Alias]] |';

  it('renders a thead/tbody table with th/td cells, alignment and inline Markdown', () => {
    const dom = new TableWidget(SRC).toDOM(undefined);
    expect(dom.className).toBe('cm-lp-table-block');
    const table = dom.querySelector('table.cm-lp-table');
    expect(table.querySelectorAll('thead th')).toHaveLength(3);
    expect(table.querySelectorAll('tbody tr')).toHaveLength(2);
    expect(table.querySelectorAll('tbody td')).toHaveLength(6);
    expect([...table.querySelectorAll('thead th')].map((c) => c.style.textAlign)).toEqual(['left', 'center', 'right']);
    expect([...table.querySelectorAll('tbody tr:first-child td')].map((c) => c.style.textAlign)).toEqual(['left', 'center', 'right']);
    expect(table.querySelector('tbody td strong').textContent).toBe('a');
    expect(table.querySelector('tbody td code').textContent).toBe('c');
    expect(table.querySelector('tbody td span.cm-lp-link').textContent).toBe('Alias');
    expect(table.querySelector('a')).toBeNull();
  });

  it('leaves alignment unset for a plain --- column', () => {
    const dom = new TableWidget('| a |\n|---|\n| 1 |').toDOM(undefined);
    expect(dom.querySelector('th').style.textAlign).toBe('');
    expect(dom.querySelector('td').style.textAlign).toBe('');
  });

  it('renders hostile cell text inertly', () => {
    const dom = new TableWidget('| a |\n|---|\n| <img src=x onerror=alert(1)> <script>x</script> |').toDOM(undefined);
    expect(dom.querySelector('img, script')).toBeNull();
    expect(dom.querySelector('td').textContent).toContain('<img src=x onerror=alert(1)>');
  });

  it('is equal by source, sizes itself from its row count and handles its own events', () => {
    const w = new TableWidget(SRC);
    expect(w.eq(new TableWidget(SRC))).toBe(true);
    expect(w.eq(new TableWidget(`${SRC}\n| c | 3 | x |`))).toBe(false);
    expect(new TableWidget(`${SRC}\n| c | 3 | x |`).estimatedHeight).toBeGreaterThan(w.estimatedHeight);
    expect(w.ignoreEvent()).toBe(true);
    expect(widgetFor({ type: 'table', source: SRC })).toBeInstanceOf(TableWidget);
  });

  it('a mousedown on body row 2 puts the caret at the start of table line 4, revealing the source', () => {
    const doc = `intro\n\n${SRC}\n\nend`;
    const view = liveView(doc);
    const rows = view.dom.querySelectorAll('.cm-lp-table tbody tr');
    expect(rows).toHaveLength(2);
    const ev = fireEvent.mouseDown(rows[1].querySelector('td'));
    expect(ev).toBe(false); // default prevented: the widget, not the browser, places the caret
    const tableLine1 = view.state.doc.line(3).number;
    expect(view.state.selection.main.head).toBe(view.state.doc.line(tableLine1 + 3).from);
    expect(view.dom.querySelector('.cm-lp-table')).toBeNull();
  });

  it('a mousedown on the header row puts the caret at the start of the header line', () => {
    const doc = `intro\n\n${SRC}\n\nend`;
    const view = liveView(doc);
    fireEvent.mouseDown(view.dom.querySelector('.cm-lp-table thead th'));
    expect(view.state.selection.main.head).toBe(doc.indexOf('| Name'));
  });

  it('a mousedown outside any row puts the caret at the table start', () => {
    const doc = `intro\n\n${SRC}\n\nend`;
    const view = liveView(doc);
    fireEvent.mouseDown(view.dom.querySelector('.cm-lp-table-block'));
    expect(view.state.selection.main.head).toBe(doc.indexOf('| Name'));
  });

  it('a caret-placement failure warns instead of throwing', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const view = { posAtDOM: () => { throw new Error('detached'); }, dispatch: vi.fn(), focus: vi.fn() };
    const dom = new TableWidget(SRC).toDOM(view);
    fireEvent.mouseDown(dom.querySelector('td'));
    expect(view.dispatch).not.toHaveBeenCalled();
    expect(warn).toHaveBeenCalledWith('[live-preview] could not place the caret in a table', 'detached');
  });
});
