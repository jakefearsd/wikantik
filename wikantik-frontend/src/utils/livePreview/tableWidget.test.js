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
import { renderInlineMarkdown } from './inlineCells';

// The real renderer, wrapped so a test can count how many cells were rendered.
vi.mock('./inlineCells', async (importOriginal) => {
  const mod = await importOriginal();
  return { ...mod, renderInlineMarkdown: vi.fn(mod.renderInlineMarkdown) };
});

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

  /** A mousedown on the wrapper itself at (offsetX, offsetY), the wrapper sized as a laid-out, scrolling block. */
  function mouseDownOnWrap(wrap, offsetX, offsetY) {
    for (const [k, v] of Object.entries({ clientWidth: 300, clientHeight: 100, offsetWidth: 300, offsetHeight: 115 })) {
      Object.defineProperty(wrap, k, { configurable: true, value: v });
    }
    const ev = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
    Object.defineProperty(ev, 'offsetX', { value: offsetX });
    Object.defineProperty(ev, 'offsetY', { value: offsetY });
    return wrap.dispatchEvent(ev);
  }

  it('a mousedown on the wrapper\'s own horizontal scrollbar is left to the browser', () => {
    const view = { posAtDOM: vi.fn(() => 0), dispatch: vi.fn(), focus: vi.fn(), state: { doc: { lineAt: () => ({ number: 1 }), line: () => ({ from: 0 }), lines: 1 } } };
    const wrap = new TableWidget(SRC).toDOM(view);
    expect(mouseDownOnWrap(wrap, 40, 108)).toBe(true); // not default-prevented: the native scrollbar drag proceeds
    expect(view.dispatch).not.toHaveBeenCalled();
  });

  it('a mousedown inside the wrapper\'s client area still places the caret', () => {
    const view = { posAtDOM: vi.fn(() => 0), dispatch: vi.fn(), focus: vi.fn(), state: { doc: { lineAt: () => ({ number: 1 }), line: () => ({ from: 0 }), lines: 1 } } };
    const wrap = new TableWidget(SRC).toDOM(view);
    expect(mouseDownOnWrap(wrap, 40, 50)).toBe(false);
    expect(view.dispatch).toHaveBeenCalledTimes(1);
  });

  it('a second toDOM for the same source reuses the built table instead of re-rendering every cell', () => {
    const src = '| cache | probe |\n|---|---|\n| **x** | $y$ |\n| 1 | 2 |';
    renderInlineMarkdown.mockClear();
    const first = new TableWidget(src).toDOM(undefined);
    const cellsRendered = renderInlineMarkdown.mock.calls.length;
    expect(cellsRendered).toBe(6);
    const second = new TableWidget(src).toDOM(undefined);
    expect(renderInlineMarkdown.mock.calls.length).toBe(cellsRendered);
    expect(second.outerHTML).toBe(first.outerHTML);
    expect(second.querySelector('table')).not.toBe(first.querySelector('table'));
  });

  it('the table cache keeps only the 20 most recently drawn sources', () => {
    const srcOf = (i) => `| evict${i} |\n|---|\n| v |`;
    new TableWidget(srcOf(0)).toDOM(undefined);
    for (let i = 1; i <= 20; i += 1) new TableWidget(srcOf(i)).toDOM(undefined);
    renderInlineMarkdown.mockClear();
    new TableWidget(srcOf(20)).toDOM(undefined); // still cached
    expect(renderInlineMarkdown).not.toHaveBeenCalled();
    new TableWidget(srcOf(0)).toDOM(undefined); // evicted: rebuilt
    expect(renderInlineMarkdown).toHaveBeenCalledTimes(2);
  });

  it('row clicks work on a table drawn from the cache', () => {
    const doc = `intro\n\n${SRC}\n\nend`;
    liveView(doc); // builds (or reuses) the cached table
    renderInlineMarkdown.mockClear();
    const view = liveView(doc);
    expect(renderInlineMarkdown).not.toHaveBeenCalled();
    const rows = view.dom.querySelectorAll('.cm-lp-table tbody tr');
    fireEvent.mouseDown(rows[1].querySelector('td'));
    expect(view.state.selection.main.head).toBe(view.state.doc.line(6).from);
  });
});
