/**
 * The table grid of Rich mode in a shared session: two PageEditors (the REAL CodeMirror editor) on one document, wired
 * as PageEditor.collab.test.jsx wires them — useCollabSession's transport override to the in-memory fakeCollabServer,
 * real timers. Both clients are in Rich unless a case says Source.
 *
 * Every case that types runs at three paces, because what a client's grid sees depends on when the other's change
 * arrives: `concurrent` (nothing is exchanged until both have finished), `staggered` (a short pause after every
 * keystroke, so pushes and pulls land mid-typing) and `in turn` (each keystroke reaches the other client first).
 * Whatever the pace, the two editors and the server must end on the same text, and no editor plugin may throw
 * (CodeMirror reports a crashed plugin or filter through console.error, which fails the test).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
// eslint-disable-next-line testing-library/no-manual-cleanup -- flush async state between tests
import { act, cleanup, fireEvent, render, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { EditorView } from '@codemirror/view';
import { StateEffect, Transaction } from '@codemirror/state';
import { undo } from '@codemirror/commands';

vi.mock('../api/client', () => ({
  api: {
    getCapabilities: vi.fn(),
    getProfile: vi.fn().mockResolvedValue({ editorMode: null }),
    updateProfile: vi.fn().mockResolvedValue({}),
    getPage: vi.fn(),
    getBacklinks: vi.fn(() => Promise.resolve({ backlinks: [] })),
    savePage: vi.fn(),
    convertWikiToMarkdown: vi.fn(),
    listAttachments: vi.fn(() => Promise.resolve({ attachments: [] })),
    listPages: vi.fn(() => Promise.resolve({ pages: [] })),
    scanMentions: vi.fn(() => Promise.resolve({ mentions: [] })),
    getFrontmatterSchema: vi.fn(() => Promise.resolve({ fields: [] })),
    listTags: vi.fn(() => Promise.resolve({ tags: [] })),
    validateFrontmatter: vi.fn(() => Promise.resolve({ metadata: {}, violations: [] })),
    search: vi.fn(() => Promise.resolve({ results: [] })),
    draftBlocks: vi.fn(),
    getDiff: vi.fn(),
  },
}));
vi.mock('../hooks/usePagePreview', () => ({ loadPreview: vi.fn(), evictPreview: vi.fn() }));
vi.mock('../hooks/useAuth', () => ({ useAuth: vi.fn() }));
vi.mock('../hooks/useDraft', () => ({ useDraft: vi.fn() }));
vi.mock('../hooks/useAttachments', () => ({ useAttachments: vi.fn() }));
vi.mock('../hooks/useEditorDrop', () => ({ useEditorDrop: vi.fn() }));
vi.mock('../hooks/useToast', () => ({ useToast: vi.fn() }));

import PageEditor from './PageEditor';
import { NavigationGuardProvider } from '../navigation/NavigationGuardProvider';
import { CapabilitiesProvider } from '../hooks/useCapabilities';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';
import { useDraft } from '../hooks/useDraft';
import { useAttachments } from '../hooks/useAttachments';
import { useToast } from '../hooks/useToast';
import { createFakeCollabServer } from '../collab/fakeCollabServer';
import '../test/asyncSelectionChange'; // a command's selection + focus: happy-dom would fire selectionchange into the update
import { parseFully } from '../test/fullParse';
import { until, typeAtCaret, placeCaret, docOf, caretOf } from '../test/realEditorHarness';
import { getCommand, getCommands } from '../commands/registry';
import { currentTable } from '../utils/livePreview/tableGrid';
import { tableAt, deleteRow } from '../utils/tableModel';

const TABLE = '| h1 | h2 | h3 |\n| --- | --- | --- |\n| a1 | a2 | a3 |\n| b1 | b2 | b3 |';
const DOC = `top\n\n${TABLE}\n\nend`;
const PACES = ['concurrent', 'staggered', 'in turn'];

let server;
let consoleError;

vi.setConfig({ testTimeout: 20000 });

/** One editor (a user of the shared session) in its own router and container. */
async function mountClient(user) {
  api.getCapabilities.mockResolvedValue({ collab: { enabled: true } });
  const { container } = render(
    <MemoryRouter initialEntries={[{ pathname: '/edit/Existing' }]}>
      <CapabilitiesProvider>
        <NavigationGuardProvider>
          <Routes>
            <Route path="/edit/:name" element={<PageEditor collabTransport={server.transportFor(user)} />} />
          </Routes>
        </NavigationGuardProvider>
      </CapabilitiesProvider>
    </MemoryRouter>,
  );
  await until(() => container.querySelector('.cm-editor') !== null);
  const view = EditorView.findFromDOM(container.querySelector('.cm-editor'));
  await until(() => docOf(view) === server.doc);
  // Every remote transaction this editor applies, as [fromA, toA, inserted] spans, for "the other's change arrived as sent".
  const remote = [];
  act(() => {
    view.dispatch({
      effects: StateEffect.appendConfig.of(EditorView.updateListener.of((u) => {
        u.transactions.filter((tr) => tr.annotation(Transaction.remote)).forEach((tr) => {
          tr.changes.iterChanges((fromA, toA, _fromB, _toB, inserted) => remote.push([fromA, toA, inserted.toString()]));
        });
      })),
    });
  });
  return { user, view, container, remote };
}

/** Ada and Bob on `doc`, both in `mode` ('live' is Rich). */
async function session({ mode = 'live', doc = DOC } = {}) {
  localStorage.setItem('wikantik.editor.mode', mode);
  server = createFakeCollabServer({ initialText: doc, pollTimeoutMs: 2000 });
  const a = await mountClient('ada');
  const b = await mountClient('bob');
  for (const { view, container } of [a, b]) {
    if (mode === 'live') await until(() => container.querySelector('.cm-editor.cm-live-preview') !== null);
    act(() => { parseFully(view); });
  }
  return { a, b };
}

const wait = (ms) => act(async () => { await new Promise((r) => setTimeout(r, ms)); });

/** Both editors and the server hold the same text (`expected`, when the case knows it). */
async function converged({ a, b }, expected) {
  try {
    await until(() => docOf(a.view) === server.doc && docOf(b.view) === server.doc && (expected === undefined || server.doc === expected), 5000);
  } catch (err) {
    throw new Error(`not converged: ${err.message}\nada:    ${JSON.stringify(docOf(a.view))}\nbob:    ${JSON.stringify(docOf(b.view))}\nserver: ${JSON.stringify(server.doc)}`
      + `${expected === undefined ? '' : `\nwanted: ${JSON.stringify(expected)}`}`, { cause: err });
  }
  // Nothing more is on its way: a pause longer than the push debounce changes nothing.
  const settled = server.version;
  await wait(250);
  expect(server.version).toBe(settled);
  expect(docOf(a.view)).toBe(server.doc);
  expect(docOf(b.view)).toBe(server.doc);
  act(() => { parseFully(a.view); parseFully(b.view); });
}

/** After each step of an interleaving: nothing, a short pause, or until every editor has every change. */
async function pace(how, clients) {
  if (how === 'staggered') await wait(40);
  else if (how === 'in turn') await converged(clients);
}

/** Runs the two clients' step lists alternately, one step each, pacing after every step. */
async function interleave(how, clients, stepsA, stepsB) {
  for (let i = 0; i < Math.max(stepsA.length, stepsB.length); i += 1) {
    if (stepsA[i]) { await stepsA[i](); await pace(how, clients); }
    if (stepsB[i]) { await stepsB[i](); await pace(how, clients); }
  }
}
const typing = (view, text) => [...text].map((ch) => () => typeAtCaret(view, ch));

const endOf = (view, text) => docOf(view).indexOf(text) + text.length;
const caretAfter = (view, text) => placeCaret(view, endOf(view, text));
const cellOf = (view) => { const cur = currentTable(view.state); return cur ? { row: cur.row, col: cur.col } : null; };
/** The text of the cell the caret is in. */
const cellText = (view) => {
  const cur = currentTable(view.state);
  if (!cur) return null;
  const cell = [cur.table.header, ...cur.table.body][cur.row].cells[cur.col];
  return view.state.sliceDoc(cell.from, cell.to);
};
const toolbarIn = (client) => within(client.container).queryByRole('toolbar', { name: 'Table' });
const tableCommands = () => getCommands().filter((c) => c.section === 'Table').map((c) => c.id);
/** Runs a table command from the client's own controls (the registry is one per window, and both editors are in it). */
async function runFromControls(client, title) {
  await until(() => toolbarIn(client) !== null);
  const bar = within(toolbarIn(client));
  const direct = bar.queryByRole('button', { name: title });
  if (direct) { fireEvent.click(direct); return; }
  fireEvent.click(bar.getByRole('button', { name: 'More table actions' }));
  fireEvent.click(bar.getByRole('menuitem', { name: title }));
}
/** The table of `view`'s document, read as the grid reads it. */
const tableOf = (view) => tableAt(view.state, docOf(view).indexOf('| h1'));

/** How many of the text's table lines (the lines starting with a pipe) do not have the header's number of cells. */
function raggedRows(text) {
  const cells = (line) => line.replace(/\\[\\|]/g, '').split('|').length - 2;
  const rows = text.split('\n').filter((line) => line.startsWith('|'));
  return rows.length === 0 ? 0 : rows.filter((line) => !line.trimEnd().endsWith('|') || cells(line) !== cells(rows[0])).length;
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  window.matchMedia = vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} }));
  useToast.mockReturnValue({ success: vi.fn(), error: vi.fn(), info: vi.fn() });
  useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'ada', roles: ['Admin'] }, login: vi.fn() });
  useDraft.mockImplementation(() => ({ draft: null, saveDraft: vi.fn(), clearDraft: vi.fn() }));
  useAttachments.mockImplementation(() => ({ list: [], uploadAttachment: vi.fn(), renameAttachment: vi.fn(), deleteAttachment: vi.fn() }));
  api.getPage.mockResolvedValue({ content: 'saved page text', metadata: {}, version: 7, markupSyntax: 'markdown' });
  consoleError = vi.spyOn(console, 'error');
});

/** Unmounts every editor and lets what they had pending settle. */
async function unmountAll() {
  document.getSelection()?.removeAllRanges();
  cleanup();
  await act(async () => { await new Promise((r) => setTimeout(r, 0)); });
}

afterEach(async () => {
  const errors = consoleError.mock.calls.map((args) => args.map(String).join(' '));
  consoleError.mockRestore();
  await unmountAll();
  expect(errors, 'an editor plugin, filter or React reported an error').toEqual([]);
});

/** B (caret outside the table) selects from the blank line above the table to the line after it and deletes. */
function deleteWholeTable(view) {
  const from = docOf(view).indexOf('| h1') - 1;
  const to = docOf(view).indexOf('\nend');
  act(() => { view.dispatch({ selection: { anchor: from, head: to } }); });
  act(() => { view.dispatch({ changes: { from, to }, selection: { anchor: from }, userEvent: 'delete.selection' }); });
}

/** A change made in Source by a person, or by an agent: the text as given, no user event of the grid's. */
const rawChange = (view, change) => { act(() => { view.dispatch({ changes: change }); }); };

/**
 * Case 3's operations, in `mode`: A types "xy" after b2 while B deletes body row 2; once both have everything, A
 * types one more character wherever the caret now is. In Rich B deletes the row from the controls; Source has no
 * table commands, so B there makes the very same text change (tableModel's deleteRow).
 */
async function typeInARowBeingDeleted(mode, how) {
  const clients = await session({ mode });
  const { a, b } = clients;
  caretAfter(a.view, 'b2');
  caretAfter(b.view, 'b1');
  const deleteTheRow = mode === 'live'
    ? () => runFromControls(b, 'Delete row')
    : () => rawChange(b.view, deleteRow(tableOf(b.view), 2));
  await interleave(how, clients, typing(a.view, 'xy'), [null, deleteTheRow]);
  await converged(clients);
  const afterDelete = { text: server.doc, caret: caretOf(a.view), table: tableOf(a.view), cell: cellOf(a.view) };
  typeAtCaret(a.view, 'z');
  await converged(clients);
  return { clients, afterDelete, text: server.doc };
}

describe('the table grid in a shared session', () => {
  describe.each(PACES)('typing %s', (how) => {
    it('1. A types in cell (1,0) while B types in cell (2,2): both edits are kept and each caret stays in its cell', async () => {
      const clients = await session();
      const { a, b } = clients;
      caretAfter(a.view, 'a1');
      caretAfter(b.view, 'b3');
      await interleave(how, clients, typing(a.view, 'xyz'), typing(b.view, 'PQR'));
      await converged(clients, DOC.replace('a1', 'a1xyz').replace('b3', 'b3PQR'));
      expect(cellOf(a.view)).toEqual({ row: 1, col: 0 });
      expect(caretOf(a.view)).toBe(endOf(a.view, 'a1xyz'));
      expect(cellOf(b.view)).toEqual({ row: 2, col: 2 });
      expect(caretOf(b.view)).toBe(endOf(b.view, 'b3PQR'));
    });

    it('2. A types in cell (1,1) while B inserts a column left of column 0: A\'s text and caret are in column 2 of row 1', async () => {
      const clients = await session();
      const { a, b } = clients;
      caretAfter(a.view, 'a2');
      caretAfter(b.view, 'b1');
      await interleave(how, clients, typing(a.view, 'xyz'), [null, () => runFromControls(b, 'Insert column left')]);
      await converged(clients, 'top\n\n|  | h1 | h2 | h3 |\n| --- | --- | --- | --- |\n|  | a1 | a2xyz | a3 |\n|  | b1 | b2 | b3 |\n\nend');
      expect(tableOf(a.view)).toMatchObject({ editable: true, columns: 4 });
      expect(cellOf(a.view)).toEqual({ row: 1, col: 2 });
      expect(cellText(a.view)).toBe('a2xyz');
      expect(caretOf(a.view)).toBe(endOf(a.view, 'a2xyz'));
      expect(cellOf(b.view)).toEqual({ row: 2, col: 0 }); // B's caret went to the new cell of its row
      expect(cellText(b.view)).toBe('');
    });

    it('3. A types in row 2 while B deletes row 2: A\'s caret stays in a cell or right after the table, and typing on is no worse than in Source', async () => {
      // The reference first: the same operations by two Source clients.
      const replay = await typeInARowBeingDeleted('source', how);
      const source = { afterDelete: replay.afterDelete.text, text: replay.text };
      expect(source.text).toContain('z');
      await unmountAll();

      const rich = await typeInARowBeingDeleted('live', how);
      const { a } = rich.clients;
      const { caret, table, text } = rich.afterDelete;
      // Up to here the two modes made the same text changes (when a pause decides who had what, they need not have).
      if (how !== 'staggered') expect(text).toBe(source.afterDelete);
      expect(caret).toBeGreaterThanOrEqual(text.indexOf('| h1'));
      expect(caret).toBeLessThanOrEqual(text.indexOf('\n\nend'));
      // While the table is still one the grid draws, a caret in it is in a cell: never beside a hidden pipe.
      if (table.editable) expect(rich.afterDelete.cell).not.toBe(null);
      expect(rich.text).toContain('z');
      expect(raggedRows(rich.text), `Rich ended on ${JSON.stringify(rich.text)}, Source on ${JSON.stringify(source.text)}`)
        .toBeLessThanOrEqual(raggedRows(source.text));
      if (table.editable) expect(tableOf(a.view)).toMatchObject({ editable: true, columns: 3 });
      // What is settled whatever the timing: typed at once, A's text outlives the row as loose text, in both modes
      // alike; typed in turn, B deleted what it had of A's, and A's next character goes into the cell above.
      if (how === 'concurrent') expect(rich.text).toBe(source.text);
      if (how === 'in turn') expect(rich.text).toBe(DOC.replace('\n| b1 | b2 | b3 |', '').replace('a3', 'a3z'));
    });

    it('4. A types in a cell while B deletes the whole table: both converge, and A can type on', async () => {
      const clients = await session();
      const { a, b } = clients;
      caretAfter(a.view, 'a2');
      placeCaret(b.view, 1);
      await interleave(how, clients, typing(a.view, 'xyz'), [null, () => deleteWholeTable(b.view)]);
      await converged(clients);
      expect(server.doc).not.toContain('| h1');
      expect(currentTable(a.view.state)).toBe(null);
      expect(caretOf(a.view)).toBeLessThanOrEqual(docOf(a.view).length);
      const at = caretOf(a.view);
      typeAtCaret(a.view, '!');
      expect(docOf(a.view).slice(at, at + 1)).toBe('!');
      await converged(clients);
      await until(() => toolbarIn(a) === null);
    });

    it('8. both type in the same cell at different offsets: both edits are kept, each arriving exactly as its author stored it', async () => {
      const clients = await session();
      const { a, b } = clients;
      placeCaret(a.view, docOf(a.view).indexOf('a2'));
      caretAfter(b.view, 'a2');
      await interleave(how, clients, typing(a.view, 'x|y'), typing(b.view, 'PQR'));
      await converged(clients, DOC.replace('a2', 'x\\|ya2PQR'));
      // Neither grid touched the other's change: what arrived is insertions only, of the text as it was stored.
      expect(b.remote.every(([fromA, toA]) => fromA === toA)).toBe(true);
      expect(b.remote.map(([, , text]) => text).join('')).toBe('x\\|y');
      expect(a.remote.every(([fromA, toA]) => fromA === toA)).toBe(true);
      expect(a.remote.map(([, , text]) => text).join('')).toBe('PQR');
      expect(tableOf(a.view)).toMatchObject({ editable: true, columns: 3 });
      expect(cellOf(a.view)).toEqual({ row: 1, col: 1 });
      expect(caretOf(a.view)).toBe(endOf(a.view, 'x\\|y'));
      expect(cellOf(b.view)).toEqual({ row: 1, col: 1 });
      expect(caretOf(b.view)).toBe(endOf(b.view, 'a2PQR'));
    });

    it('9. undo on A after interleaved typing in one table takes back only A\'s typing', async () => {
      const clients = await session();
      const { a, b } = clients;
      caretAfter(a.view, 'a1');
      caretAfter(b.view, 'a2');
      await interleave(how, clients, typing(a.view, 'xyz'), typing(b.view, 'PQR'));
      await converged(clients, DOC.replace('a1', 'a1xyz').replace('a2', 'a2PQR'));
      // Typing in turn is slow enough to leave more than one undo step; none of them may touch B's text.
      for (let i = 0; i < 3 && docOf(a.view) !== DOC.replace('a2', 'a2PQR'); i += 1) {
        act(() => { undo(a.view); });
        expect(docOf(a.view)).toContain('a2PQR');
      }
      await converged(clients, DOC.replace('a2', 'a2PQR'));
      expect(tableOf(a.view)).toMatchObject({ editable: true, columns: 3 });
      expect(tableOf(b.view)).toMatchObject({ editable: true, columns: 3 });
      expect(cellOf(b.view)).toEqual({ row: 1, col: 1 });
      // The undo restores A's caret (the history applies it without the caret rules): it is in A's cell, after its text.
      expect(cellOf(a.view)).toEqual({ row: 1, col: 0 });
      expect(caretOf(a.view)).toBe(endOf(a.view, 'a1'));
    });

    it('9b. undo on A of a column added next to the cell B is typing in removes the column and keeps B\'s text', async () => {
      const clients = await session();
      const { a, b } = clients;
      caretAfter(a.view, 'a3');
      caretAfter(b.view, 'b3');
      await interleave(how, clients, [null, () => runFromControls(a, 'Insert column right')], typing(b.view, 'PQR'));
      await converged(clients, 'top\n\n| h1 | h2 | h3 |  |\n| --- | --- | --- | --- |\n| a1 | a2 | a3 |  |\n| b1 | b2 | b3PQR |  |\n\nend');
      expect(cellOf(a.view)).toEqual({ row: 1, col: 3 });
      act(() => { undo(a.view); });
      await converged(clients, DOC.replace('b3', 'b3PQR'));
      expect(tableOf(a.view)).toMatchObject({ editable: true, columns: 3 });
      expect(cellOf(a.view)).toEqual({ row: 1, col: 2 }); // back in the cell the command was run from
      expect(caretOf(a.view)).toBe(endOf(a.view, 'a3'));
      expect(cellOf(b.view)).toEqual({ row: 2, col: 2 });
      expect(caretOf(b.view)).toBe(endOf(b.view, 'b3PQR'));
    });
  });

  // A peer's change is applied without the editor's transaction filters (@codemirror/collab), so the caret rules do
  // not see it: sticky.js sets the caret again afterwards. Each of these leaves A's caret, as mapped, outside any cell.
  it.each([
    ['row 1, the caret\'s (it is mapped onto the hidden delimiter row)', DOC, 'a2', 'a1', 'Delete row', 3],
    ['the only body row', 'top\n\n| h1 | h2 | h3 |\n| --- | --- | --- |\n| a1 | a2 | a3 |\n\nend', 'a2', 'a1', 'Delete row', 3],
    ['the caret\'s column', DOC, 'a2', 'b2', 'Delete column', 2],
    ['the last column, the caret\'s', DOC, 'a3', 'b3', 'Delete column', 2],
  ])('3b. B deletes %s: A\'s caret is put in a cell, and A\'s typing keeps the table whole', async (_what, doc, mine, theirs, command, columns) => {
    const clients = await session({ doc });
    const { a, b } = clients;
    caretAfter(a.view, mine);
    caretAfter(b.view, theirs);
    await runFromControls(b, command);
    await converged(clients);
    expect(server.doc).not.toContain(mine);
    expect(tableOf(a.view)).toMatchObject({ editable: true, columns });
    const cell = cellOf(a.view);
    expect(cell, `A's caret at ${caretOf(a.view)} in ${JSON.stringify(docOf(a.view))}`).not.toBe(null);
    typeAtCaret(a.view, 'z');
    await converged(clients);
    expect(tableOf(a.view)).toMatchObject({ editable: true, columns });
    expect(cellOf(a.view)).toEqual(cell);
    expect(cellText(a.view)).toContain('z');
  });

  it('5. B deletes the table A\'s caret is in: A\'s controls and table commands go, and a command held from before writes nothing', async () => {
    const clients = await session();
    const { a, b } = clients;
    placeCaret(b.view, 1);
    caretAfter(a.view, 'a2');
    await until(() => toolbarIn(a) !== null);
    expect(toolbarIn(b)).toBe(null);
    expect(tableCommands()).toContain('table-row-below');
    const held = ['table-row-below', 'table-column-right', 'table-delete-row', 'table-align-center'].map((id) => getCommand(id));
    deleteWholeTable(b.view);
    await converged(clients, 'top\n\nend');
    await until(() => toolbarIn(a) === null);
    expect(tableCommands()).toEqual([]);
    const version = server.version;
    for (const command of held) await act(async () => { await command.run(); });
    expect(docOf(a.view)).toBe('top\n\nend');
    await converged(clients, 'top\n\nend');
    expect(server.version).toBe(version);
  });

  it('6. the controls hold the focus (Alt-T) when B deletes the table: the focus returns to A\'s editor', async () => {
    const clients = await session();
    const { a, b } = clients;
    placeCaret(b.view, 1);
    caretAfter(a.view, 'a2');
    await until(() => toolbarIn(a) !== null);
    act(() => { a.view.focus(); });
    expect(fireEvent.keyDown(a.view.contentDOM, { key: 't', code: 'KeyT', altKey: true })).toBe(false);
    expect(toolbarIn(a).contains(document.activeElement)).toBe(true);
    deleteWholeTable(b.view);
    await converged(clients, 'top\n\nend');
    await until(() => toolbarIn(a) === null);
    expect(document.activeElement).toBe(a.view.contentDOM);
  });

  it('7. a remote change makes the caret\'s row ragged, another repairs it: the caret stays put and typing lands at it', async () => {
    const clients = await session();
    const { a, b } = clients;
    placeCaret(b.view, 1);
    caretAfter(a.view, 'a2');
    expect(cellOf(a.view)).toEqual({ row: 1, col: 1 });
    const extra = ' more |';
    const rowEnd = docOf(b.view).indexOf('| a3 |') + '| a3 |'.length;
    rawChange(b.view, { from: rowEnd, insert: extra });
    const ragged = DOC.replace('| a3 |', `| a3 |${extra}`);
    await converged(clients, ragged);
    // The table is no grid any more: no cell holds the caret, and the controls are gone.
    for (const { view } of [a, b]) expect(tableOf(view).editable).toBe(false);
    expect(currentTable(a.view.state)).toBe(null);
    await until(() => toolbarIn(a) === null);
    expect(caretOf(a.view)).toBe(endOf(a.view, 'a2'));
    typeAtCaret(a.view, 'Z');
    await converged(clients, ragged.replace('a2', 'a2Z'));
    expect(caretOf(a.view)).toBe(endOf(a.view, 'a2Z'));

    const at = docOf(b.view).indexOf(extra);
    rawChange(b.view, { from: at, to: at + extra.length });
    await converged(clients, DOC.replace('a2', 'a2Z'));
    for (const { view } of [a, b]) expect(tableOf(view)).toMatchObject({ editable: true, columns: 3 });
    expect(cellOf(a.view)).toEqual({ row: 1, col: 1 });
    expect(caretOf(a.view)).toBe(endOf(a.view, 'a2Z'));
    await until(() => toolbarIn(a) !== null);
    typeAtCaret(a.view, 'W');
    await converged(clients, DOC.replace('a2', 'a2ZW'));
    expect(caretOf(a.view)).toBe(endOf(a.view, 'a2ZW'));
    expect(cellText(a.view)).toBe('a2ZW');
  });
});
