import { describe, it, expect } from 'vitest';
import { EditorState } from '@codemirror/state';
import { markdown } from '@codemirror/lang-markdown';
import { syntaxTree } from '@codemirror/language';
import { editorMarkdownConfig } from './editorMarkdown';
import { fullyParsed } from '../test/fullParse';
import { linearCost, LINEAR_COST_TIMEOUT_MS } from '../test/linearCost';
import {
  readTable, tableAt, cellAt, escapeCell,
  insertRow, deleteRow, insertColumn, deleteColumn, moveColumn, setAlign,
} from './tableModel';

const stateOf = (doc) => fullyParsed(EditorState.create({ doc, extensions: [markdown(editorMarkdownConfig)] }));

/** The first `Table` node of the document, wherever it sits. */
function firstTable(state) {
  let found = null;
  syntaxTree(state).iterate({ enter: (n) => { if (!found && n.name === 'Table') found = n.node; return !found; } });
  return found;
}
/** The model of the first table of `doc`, with the state it was read from. */
function read(doc) {
  const state = stateOf(doc);
  return { state, table: readTable(state, firstTable(state)) };
}
const text = (state, span) => state.sliceDoc(span.from, span.to);
const cellTexts = (state, row) => row.cells.map((c) => text(state, c));

/** `src` after `changes`, applied by hand: the changes must be sorted and must not overlap. */
function apply(src, changes) {
  let out = '';
  let at = 0;
  for (const c of changes) {
    expect(c.from).toBeGreaterThanOrEqual(at);
    expect(c.to).toBeGreaterThanOrEqual(c.from);
    out += src.slice(at, c.from) + c.insert;
    at = c.to;
  }
  return out + src.slice(at);
}
/** The document after an operation on its first table. */
const after = (doc, op) => apply(doc, op(read(doc).table));

const SIMPLE = '| a | b |\n| --- | --- |\n| 1 | 2 |';
const THREE = '| a | b | c |\n| --- | --- | --- |\n| 1 | 2 | 3 |\n| 4 | 5 | 6 |';
// Uneven padding, an empty cell, an escaped pipe, a code span and trailing spaces: nothing an operation may tidy.
const MESSY = 'before\n\n|a|  bb  | c\\|c |\n|:-|:---:|  ---: |\n| 1 || `x|y` |  \n|   |2| 3 |\n\nafter\n';

describe('readTable', () => {
  it('reads a 3x2 table', () => {
    const { state, table } = read(THREE);
    expect(table).toMatchObject({ from: 0, to: THREE.length, editable: true, columns: 3, aligns: [null, null, null] });
    expect(cellTexts(state, table.header)).toEqual(['a', 'b', 'c']);
    expect(text(state, table.header)).toBe('| a | b | c |');
    expect(text(state, table.delimiter)).toBe('| --- | --- | --- |');
    expect(table.body.map((r) => cellTexts(state, r))).toEqual([['1', '2', '3'], ['4', '5', '6']]);
    expect(table.body.map((r) => text(state, r))).toEqual(['| 1 | 2 | 3 |', '| 4 | 5 | 6 |']);
  });

  it('reads each alignment from the delimiter row', () => {
    expect(read('| a | b | c | d |\n|:--|:-:|--:|---|\n| 1 | 2 | 3 | 4 |').table.aligns).toEqual(['left', 'center', 'right', null]);
  });

  it('keeps an escaped pipe in its cell', () => {
    const { state, table } = read('| a | b |\n| --- | --- |\n| a \\| b | c |');
    expect(table.editable).toBe(true);
    expect(cellTexts(state, table.body[0])).toEqual(['a \\| b', 'c']);
  });

  it('keeps a pipe in a code span in its cell', () => {
    const { state, table } = read('| a | b |\n| --- | --- |\n| `x|y` | c |');
    expect(table.editable).toBe(true);
    expect(cellTexts(state, table.body[0])).toEqual(['`x|y`', 'c']);
  });

  it('a cell span leaves out the padding, whatever its width', () => {
    const { state, table } = read(MESSY);
    expect(table.editable).toBe(true);
    expect(table.aligns).toEqual(['left', 'center', 'right']);
    expect(cellTexts(state, table.header)).toEqual(['a', 'bb', 'c\\|c']);
    expect(table.body.map((r) => cellTexts(state, r))).toEqual([['1', '', '`x|y`'], ['', '2', '3']]);
    expect(text(state, table.body[0])).toBe('| 1 || `x|y` |  ');
  });

  it('an empty cell is a zero-width span where typing goes: after one space of padding when there are two', () => {
    const doc = '| a | b | c |\n| --- | --- | --- |\n||  |      |';
    const { table } = read(doc);
    const row = doc.indexOf('||');
    expect(table.body[0].cells.map((c) => [c.from, c.to])).toEqual([[row + 1, row + 1], [row + 3, row + 3], [row + 6, row + 6]]);
    expect(table.editable).toBe(true);
  });

  it('a ragged table is not editable', () => {
    expect(read('| a | b |\n| --- | --- |\n| 1 |').table.editable).toBe(false);
    expect(read('| a | b |\n| --- | --- |\n| 1 | 2 | 3 |').table.editable).toBe(false);
    expect(read('| a |\n| --- | --- |\n| 1 | 2 |').table.editable).toBe(false);
    expect(read('| a | b |\n| --- | --- |\n| 1 | 2 |\nplain').table.editable).toBe(false);
  });

  it('a table inside a list item or a quote is not editable', () => {
    const inList = read('- | a | b |\n  | --- | --- |\n  | 1 | 2 |').table;
    expect(inList.columns).toBe(2);
    expect(inList.editable).toBe(false);
    expect(read('> | a | b |\n> | --- | --- |\n> | 1 | 2 |').table.editable).toBe(false);
  });

  it('a table with a row that lacks its leading or trailing pipe is read but not editable', () => {
    const { state, table } = read('a | b\n--- | ---\n1 | 2');
    expect(table.columns).toBe(2);
    expect(cellTexts(state, table.header)).toEqual(['a', 'b']);
    expect(cellTexts(state, table.body[0])).toEqual(['1', '2']);
    expect(table.editable).toBe(false);
    expect(read('| a | b |\n| --- | --- |\n| 1 | 2').table.editable).toBe(false);
    expect(read('| a | b |\n| --- | --- |\n1 | 2 |').table.editable).toBe(false);
    expect(read('| a | b |\n--- | --- |\n| 1 | 2 |').table.editable).toBe(false);
    expect(read('| a | b |\n| --- | ---\n| 1 | 2 |').table.editable).toBe(false);
    expect(read('a | b |\n| --- | --- |\n| 1 | 2 |').table.editable).toBe(false);
  });

  it('a table with several header rows is not editable', () => {
    const { state, table } = read('| h1 | h2 |\n| h3 | h4 |\n| --- | --- |\n| 1 | 2 |');
    expect(table.editable).toBe(false);
    expect(cellTexts(state, table.header)).toEqual(['h1', 'h2']);
  });

  it('an indented top-level table is editable, its rows starting after the indent', () => {
    const { state, table } = read('  | a |\n  | --- |\n  | 1 |');
    expect(table.editable).toBe(true);
    expect(text(state, table.body[0])).toBe('| 1 |');
  });

  it('a table with no body rows is editable', () => {
    expect(read('| a | b |\n| --- | --- |').table).toMatchObject({ editable: true, body: [] });
  });

  it('stays linear in the row count', () => {
    const { ratio, bound } = linearCost(
      (n) => { const state = stateOf(`| a | b | c |\n| --- | --- | --- |\n${'| 1 | 2 | 3 |\n'.repeat(n)}`); return { state, node: firstTable(state) }; },
      ({ state, node }) => readTable(state, node),
      { small: 5000 },
    );
    expect(ratio).toBeLessThan(bound);
  }, LINEAR_COST_TIMEOUT_MS);
});

describe('tableAt', () => {
  const doc = `intro\n\n${SIMPLE}\n\noutro`;
  it('finds the table a position is in, at either edge and between rows', () => {
    const state = stateOf(doc);
    const from = doc.indexOf('| a');
    const to = from + SIMPLE.length;
    for (const pos of [from, from + 3, doc.indexOf('\n| ---'), to]) {
      expect(tableAt(state, pos)).toMatchObject({ from, to, columns: 2, editable: true });
    }
  });
  it('is null outside a table', () => {
    const state = stateOf(doc);
    expect(tableAt(state, 0)).toBeNull();
    expect(tableAt(state, doc.length)).toBeNull();
  });
});

describe('cellAt', () => {
  it('names the row and column, the header being row 0', () => {
    const { table } = read(THREE);
    expect(cellAt(table, THREE.indexOf('a'))).toEqual({ row: 0, col: 0 });
    expect(cellAt(table, THREE.indexOf('c') + 1)).toEqual({ row: 0, col: 2 });
    expect(cellAt(table, THREE.indexOf('2'))).toEqual({ row: 1, col: 1 });
    expect(cellAt(table, THREE.indexOf('6'))).toEqual({ row: 2, col: 2 });
    expect(cellAt(table, THREE.indexOf('4') - 1)).toEqual({ row: 2, col: 0 });
  });
  it('is null on the delimiter row, outside the outer pipes and outside the table', () => {
    const { table } = read(MESSY);
    expect(cellAt(table, MESSY.indexOf(':---:'))).toBeNull();
    expect(cellAt(table, table.header.from)).toBeNull();
    expect(cellAt(table, table.body[0].to)).toBeNull();
    expect(cellAt(table, 0)).toBeNull();
    expect(cellAt(table, MESSY.length)).toBeNull();
  });
  it('finds an empty cell at its typing position', () => {
    const { table } = read(MESSY);
    expect(cellAt(table, table.body[0].cells[1].from)).toEqual({ row: 1, col: 1 });
    expect(cellAt(table, table.body[1].cells[0].from)).toEqual({ row: 2, col: 0 });
  });
});

describe('escapeCell', () => {
  it('escapes a pipe and folds line breaks into one space', () => {
    expect(escapeCell('a|b\nc')).toBe('a\\|b c');
    expect(escapeCell('a\r\n\r\nb\n\nc')).toBe('a b c');
  });
  it('leaves an escaped pipe alone, and escapes one that follows an escaped backslash', () => {
    expect(escapeCell('a\\|b')).toBe('a\\|b');
    expect(escapeCell('a\\\\|b')).toBe('a\\\\\\|b');
    expect(escapeCell('||')).toBe('\\|\\|');
    expect(escapeCell('plain')).toBe('plain');
  });
});

describe('insertRow', () => {
  it('adds one line of empty cells at the index', () => {
    expect(after(THREE, (t) => insertRow(t, 1))).toBe('| a | b | c |\n| --- | --- | --- |\n|  |  |  |\n| 1 | 2 | 3 |\n| 4 | 5 | 6 |');
    expect(after(THREE, (t) => insertRow(t, 2))).toBe('| a | b | c |\n| --- | --- | --- |\n| 1 | 2 | 3 |\n|  |  |  |\n| 4 | 5 | 6 |');
    expect(after(THREE, (t) => insertRow(t, 3))).toBe(`${THREE}\n|  |  |  |`);
    expect(after('| a |\n| --- |', (t) => insertRow(t, 1))).toBe('| a |\n| --- |\n|  |');
  });
  it('is one insertion that leaves the text after the table alone', () => {
    const changes = insertRow(read(MESSY).table, 3);
    expect(changes).toHaveLength(1);
    expect(changes[0].to).toBe(changes[0].from);
    expect(apply(MESSY, changes)).toBe(MESSY.replace('|   |2| 3 |\n', '|   |2| 3 |\n|  |  |  |\n'));
  });
  it('does nothing above the header or past the end', () => {
    const { table } = read(THREE);
    expect(insertRow(table, 0)).toEqual([]);
    expect(insertRow(table, 4)).toEqual([]);
  });
});

describe('deleteRow', () => {
  it('removes exactly one line and its newline', () => {
    expect(after(THREE, (t) => deleteRow(t, 1))).toBe('| a | b | c |\n| --- | --- | --- |\n| 4 | 5 | 6 |');
    expect(after(THREE, (t) => deleteRow(t, 2))).toBe('| a | b | c |\n| --- | --- | --- |\n| 1 | 2 | 3 |');
    const changes = deleteRow(read(MESSY).table, 1);
    expect(changes).toEqual([{ from: MESSY.indexOf('\n| 1 ||'), to: MESSY.indexOf('\n|   |2|'), insert: '' }]);
    expect(apply(MESSY, changes)).toBe(MESSY.replace('| 1 || `x|y` |  \n', ''));
  });
  it('never removes the header, and nothing out of range', () => {
    const { table } = read(THREE);
    expect(deleteRow(table, 0)).toEqual([]);
    expect(deleteRow(table, 3)).toEqual([]);
  });
});

describe('insertColumn', () => {
  it('adds an empty cell to every row and a --- cell to the delimiter row', () => {
    expect(after(SIMPLE, (t) => insertColumn(t, 1))).toBe('| a |  | b |\n| --- | --- | --- |\n| 1 |  | 2 |');
    expect(after(SIMPLE, (t) => insertColumn(t, 0))).toBe('|  | a | b |\n| --- | --- | --- |\n|  | 1 | 2 |');
    expect(after(SIMPLE, (t) => insertColumn(t, 2))).toBe('| a | b |  |\n| --- | --- | --- |\n| 1 | 2 |  |');
  });
  it('re-pads nothing', () => {
    expect(after(MESSY, (t) => insertColumn(t, 1))).toBe('before\n\n|a|  |  bb  | c\\|c |\n|:-| --- |:---:|  ---: |\n| 1 |  || `x|y` |  \n|   |  |2| 3 |\n\nafter\n');
    expect(after(MESSY, (t) => insertColumn(t, 3))).toBe('before\n\n|a|  bb  | c\\|c |  |\n|:-|:---:|  ---: | --- |\n| 1 || `x|y` |  |  \n|   |2| 3 |  |\n\nafter\n');
  });
  it('gives a table the grid can read back', () => {
    const { state, table } = read(after(MESSY, (t) => insertColumn(t, 1)));
    expect(table).toMatchObject({ editable: true, columns: 4, aligns: ['left', null, 'center', 'right'] });
    expect(cellTexts(state, table.body[0])).toEqual(['1', '', '', '`x|y`']);
  });
  it('does nothing out of range', () => {
    const { table } = read(SIMPLE);
    expect(insertColumn(table, -1)).toEqual([]);
    expect(insertColumn(table, 3)).toEqual([]);
  });
});

describe('deleteColumn', () => {
  it('removes the cell and one pipe from every row', () => {
    expect(after(THREE, (t) => deleteColumn(t, 0))).toBe('| b | c |\n| --- | --- |\n| 2 | 3 |\n| 5 | 6 |');
    expect(after(THREE, (t) => deleteColumn(t, 1))).toBe('| a | c |\n| --- | --- |\n| 1 | 3 |\n| 4 | 6 |');
    expect(after(THREE, (t) => deleteColumn(t, 2))).toBe('| a | b |\n| --- | --- |\n| 1 | 2 |\n| 4 | 5 |');
    expect(after(MESSY, (t) => deleteColumn(t, 1))).toBe('before\n\n|a| c\\|c |\n|:-|  ---: |\n| 1 | `x|y` |  \n|   | 3 |\n\nafter\n');
  });
  it('never removes the last remaining column, and nothing out of range', () => {
    expect(deleteColumn(read('| a |\n| --- |\n| 1 |').table, 0)).toEqual([]);
    expect(deleteColumn(read(SIMPLE).table, 2)).toEqual([]);
  });
});

describe('moveColumn', () => {
  it('moves a column to its new index, right or left', () => {
    expect(after(THREE, (t) => moveColumn(t, 0, 2))).toBe('| b | c | a |\n| --- | --- | --- |\n| 2 | 3 | 1 |\n| 5 | 6 | 4 |');
    expect(after(THREE, (t) => moveColumn(t, 2, 0))).toBe('| c | a | b |\n| --- | --- | --- |\n| 3 | 1 | 2 |\n| 6 | 4 | 5 |');
    expect(after(THREE, (t) => moveColumn(t, 0, 1))).toBe('| b | a | c |\n| --- | --- | --- |\n| 2 | 1 | 3 |\n| 5 | 4 | 6 |');
    expect(after(THREE, (t) => moveColumn(t, 2, 1))).toBe('| a | c | b |\n| --- | --- | --- |\n| 1 | 3 | 2 |\n| 4 | 6 | 5 |');
  });
  it('carries each cell with its own padding and its alignment', () => {
    expect(after(MESSY, (t) => moveColumn(t, 2, 0))).toBe('before\n\n| c\\|c |a|  bb  |\n|  ---: |:-|:---:|\n| `x|y` | 1 ||  \n| 3 |   |2|\n\nafter\n');
    expect(after(MESSY, (t) => moveColumn(t, 1, 2))).toBe('before\n\n|a| c\\|c |  bb  |\n|:-|  ---: |:---:|\n| 1 | `x|y` ||  \n|   | 3 |2|\n\nafter\n');
  });
  it('does not touch the columns it passes over', () => {
    const { table } = read(THREE);
    const passed = [table.header, table.delimiter, ...table.body].map((r) => r.cells[1]);
    for (const c of moveColumn(table, 0, 2)) {
      for (const cell of passed) expect(c.to <= cell.from || c.from >= cell.to).toBe(true);
    }
  });
  it('does nothing when the column stays put or an index is out of range', () => {
    const { table } = read(THREE);
    expect(moveColumn(table, 1, 1)).toEqual([]);
    expect(moveColumn(table, 0, 3)).toEqual([]);
    expect(moveColumn(table, -1, 1)).toEqual([]);
  });
});

describe('setAlign', () => {
  it('changes only the colons of that delimiter cell', () => {
    expect(after(SIMPLE, (t) => setAlign(t, 0, 'center'))).toBe('| a | b |\n| :---: | --- |\n| 1 | 2 |');
    expect(after(SIMPLE, (t) => setAlign(t, 0, 'left'))).toBe('| a | b |\n| :--- | --- |\n| 1 | 2 |');
    expect(after(SIMPLE, (t) => setAlign(t, 1, 'right'))).toBe('| a | b |\n| --- | ---: |\n| 1 | 2 |');
    const changes = setAlign(read(SIMPLE).table, 0, 'center');
    const cell = SIMPLE.indexOf('---');
    expect(changes).toEqual([{ from: cell, to: cell, insert: ':' }, { from: cell + 3, to: cell + 3, insert: ':' }]);
  });
  it('keeps the dashes and padding the author wrote', () => {
    expect(after(MESSY, (t) => setAlign(t, 1, null))).toBe(MESSY.replace('|:---:|', '|---|'));
    expect(after(MESSY, (t) => setAlign(t, 1, 'right'))).toBe(MESSY.replace('|:---:|', '|---:|'));
    expect(after(MESSY, (t) => setAlign(t, 2, 'left'))).toBe(MESSY.replace('|  ---: |', '|  :--- |'));
    expect(after(MESSY, (t) => setAlign(t, 0, 'center'))).toBe(MESSY.replace('|:-|', '|:-:|'));
  });
  it('does nothing when the alignment is already set, is unknown, or the column is out of range', () => {
    const { table } = read(MESSY);
    expect(setAlign(table, 1, 'center')).toEqual([]);
    expect(setAlign(table, 0, 'justify')).toEqual([]);
    expect(setAlign(table, 3, 'left')).toEqual([]);
  });
});

describe('every operation', () => {
  const OPS = {
    'insertRow 1': (t) => insertRow(t, 1),
    'insertRow end': (t) => insertRow(t, t.body.length + 1),
    'deleteRow 1': (t) => deleteRow(t, 1),
    'deleteRow last': (t) => deleteRow(t, t.body.length),
    'insertColumn 0': (t) => insertColumn(t, 0),
    'insertColumn 1': (t) => insertColumn(t, 1),
    'insertColumn end': (t) => insertColumn(t, t.columns),
    'deleteColumn 0': (t) => deleteColumn(t, 0),
    'deleteColumn last': (t) => deleteColumn(t, t.columns - 1),
    'moveColumn 0 to last': (t) => moveColumn(t, 0, t.columns - 1),
    'moveColumn last to 0': (t) => moveColumn(t, t.columns - 1, 0),
    'moveColumn 1 to 0': (t) => moveColumn(t, 1, 0),
    'moveColumn 0 to 1': (t) => moveColumn(t, 0, 1),
    'setAlign center': (t) => setAlign(t, 0, 'center'),
    'setAlign left': (t) => setAlign(t, 1, 'left'),
    'setAlign right or none': (t) => setAlign(t, t.columns - 1, t.aligns[t.columns - 1] === 'right' ? null : 'right'),
  };
  const DOCS = { SIMPLE, THREE, MESSY, indented: 'x\n\n  | a | b |\n  |---|:-:|\n  | 1 | 2 |\n   | 3 | 4 |\n\ny' };
  const cases = Object.keys(DOCS).flatMap((d) => Object.keys(OPS).map((o) => [o, d]));

  it.each(cases)('%s on %s changes only the characters inside its change ranges', (opName, docName) => {
    const doc = DOCS[docName];
    const { state, table } = read(doc);
    const changes = OPS[opName](table);
    expect(changes.length).toBeGreaterThan(0);
    const result = apply(doc, changes); // asserts sorted and non-overlapping
    // The editor reads the changes the same way.
    const tr = state.update({ changes });
    expect(tr.state.doc.toString()).toBe(result);
    // Everything outside the ranges is carried over: the kept text, in order, with only the inserts between.
    let at = 0;
    let out = 0;
    for (const c of changes) {
      expect(c.from).toBeGreaterThanOrEqual(table.from);
      expect(c.to).toBeLessThanOrEqual(table.to);
      const kept = doc.slice(at, c.from);
      expect(result.slice(out, out + kept.length)).toBe(kept);
      out += kept.length + c.insert.length;
      at = c.to;
      // A range holds no character it leaves as it was: what it removes and what it writes share no edge.
      const removed = doc.slice(c.from, c.to);
      if (removed && c.insert) {
        expect(removed[0]).not.toBe(c.insert[0]);
        expect(removed[removed.length - 1]).not.toBe(c.insert[c.insert.length - 1]);
      }
      expect(removed.length + c.insert.length).toBeGreaterThan(0);
    }
    expect(result.slice(out)).toBe(doc.slice(at));
    // The text around the table is untouched, and the result is still a table the grid can edit.
    expect(result.startsWith(doc.slice(0, table.from))).toBe(true);
    expect(result.endsWith(doc.slice(table.to))).toBe(true);
    expect(read(result).table.editable).toBe(true);
  });

  it.each(Object.keys(OPS))('%s on a table that is not editable changes nothing', (opName) => {
    for (const doc of ['a | b\n--- | ---\n1 | 2\n3 | 4', '| a | b |\n| --- | --- |\n| 1 |\n| 2 | 3 |', '- | a | b |\n  | --- | --- |\n  | 1 | 2 |\n  | 3 | 4 |']) {
      expect(OPS[opName](read(doc).table)).toEqual([]);
    }
  });
});
