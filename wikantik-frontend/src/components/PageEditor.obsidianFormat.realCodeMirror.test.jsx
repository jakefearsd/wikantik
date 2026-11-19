import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
// eslint-disable-next-line testing-library/no-manual-cleanup -- flush async state between tests
import { render, waitFor, act, cleanup, screen, fireEvent, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { EditorView } from '@codemirror/view';
import { undo } from '@codemirror/commands';
import fenceFixture from '../utils/__fixtures__/renderedFences.json';

vi.mock('../api/client', () => ({
  api: {
    getProfile: vi.fn().mockResolvedValue({ editorMode: null }),
    updateProfile: vi.fn().mockResolvedValue({}),
    getPage: vi.fn(),
    getBacklinks: vi.fn(() => Promise.resolve({ backlinks: [] })),
    savePage: vi.fn(),
    listAttachments: vi.fn(() => Promise.resolve({ attachments: [] })),
    listPages: vi.fn(() => Promise.resolve({ pages: [] })),
    scanMentions: vi.fn(() => Promise.resolve({ mentions: [] })),
    getFrontmatterSchema: vi.fn(() => Promise.resolve({ fields: [] })),
    listTags: vi.fn(() => Promise.resolve({ tags: [] })),
    validateFrontmatter: vi.fn(() => Promise.resolve({ metadata: {}, violations: [] })),
    search: vi.fn(() => Promise.resolve({ results: [] })),
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
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';
import { useDraft } from '../hooks/useDraft';
import { useAttachments } from '../hooks/useAttachments';
import { useToast } from '../hooks/useToast';
import { getCommand } from '../commands/registry';

/**
 * The palette's Highlight and Toggle comment run through PageEditor's applyFormat (the same toggleWrap the
 * Mod-Shift-H / Mod-/ keys use): they wrap the selection, and a second run unwraps it.
 */
async function editorView(content = 'hello world') {
  api.getPage.mockResolvedValue({ content, metadata: {}, version: 1, markupSyntax: 'markdown' });
  render(
    <MemoryRouter initialEntries={['/edit/Existing']}>
      <NavigationGuardProvider>
        <Routes><Route path="/edit/:name" element={<PageEditor />} /></Routes>
      </NavigationGuardProvider>
    </MemoryRouter>,
  );
  await waitFor(() => expect(document.querySelector('.cm-editor')).not.toBeNull());
  const view = EditorView.findFromDOM(document.querySelector('.cm-editor'));
  await waitFor(() => expect(view.state.doc.toString()).toBe(content));
  return view;
}

const select = (view, from, to) => act(() => { view.dispatch({ selection: { anchor: from, head: to } }); });
// applyEdit focuses the editor, and happy-dom fires selectionchange synchronously when a focused editor writes its DOM
// selection inside the next update ("update in progress"). Unfocused, the editor leaves the DOM selection alone.
const blur = (view) => act(() => { view.contentDOM.blur(); });
const run = (id) => act(async () => { await getCommand(id).run(); });

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  localStorage.setItem('wikantik.editor.mode', 'source');
  window.matchMedia = vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} }));
  useToast.mockReturnValue({ success: vi.fn(), error: vi.fn(), info: vi.fn() });
  useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'alice', roles: ['Admin'] } });
  useDraft.mockReturnValue({ draft: null, saveDraft: vi.fn(), clearDraft: vi.fn() });
  useAttachments.mockImplementation(() => ({ list: [], uploadAttachment: vi.fn(), renameAttachment: vi.fn(), deleteAttachment: vi.fn() }));
  api.getPage.mockResolvedValue({ content: 'hello world', metadata: {}, version: 1, markupSyntax: 'markdown' });
  api.savePage.mockResolvedValue({ version: 2 });
});

afterEach(async () => {
  cleanup();
  await act(async () => { await Promise.resolve(); });
});

// One run per mounted editor: happy-dom delivers the selectionchange of applyEdit's focus() inside the next update.
describe('PageEditor palette: Highlight and Toggle comment', () => {
  it('Highlight wraps the selection in ==', async () => {
    const view = await editorView();
    select(view, 6, 11);
    await run('format-highlight');
    expect(view.state.doc.toString()).toBe('hello ==world==');
    expect(view.state.sliceDoc(view.state.selection.main.from, view.state.selection.main.to)).toBe('world');
  });

  it('Highlight on a highlighted selection unwraps it', async () => {
    const view = await editorView('hello ==world==');
    select(view, 8, 13);
    await run('format-highlight');
    expect(view.state.doc.toString()).toBe('hello world');
  });

  it('Toggle comment wraps the selection in %%', async () => {
    const view = await editorView();
    select(view, 0, 5);
    await run('toggle-comment');
    expect(view.state.doc.toString()).toBe('%%hello%% world');
  });

  it('Toggle comment on a commented selection unwraps it', async () => {
    const view = await editorView('%%hello%% world');
    select(view, 2, 7);
    await run('toggle-comment');
    expect(view.state.doc.toString()).toBe('hello world');
  });
});

/**
 * The rich-mode commands: each one writes the text markdownFormat defines for it, as ONE transaction — a single
 * undo gives back the document exactly.
 */
describe('PageEditor commands: lists, quote, heading 4, diagrams, footnote, embed, strikethrough', () => {
  // The Mermaid starter is the shared reader fixture's first source its policy allows; a fence's source carries the
  // last line's newline, which the fence writer adds itself.
  const mermaidBody = fenceFixture.mermaidPolicy.find((row) => row.allowed).source.replace(/\n$/, '');
  // The fixture holds no PlantUML source (the server is that policy): the starter is the smallest whole diagram.
  const plantumlBody = '@startuml\nA -> B\n@enduml';

  it.each([
    ['format-strike', [0, 5], '~~alpha~~', [2, 7]],
    ['format-ordered-list', [5, 5], '1. alpha', [8, 8]],
    ['format-task-list', [5, 5], '- [ ] alpha', [11, 11]],
    ['format-quote', [5, 5], '> alpha', [7, 7]],
    ['heading-4', [5, 5], '#### alpha', [10, 10]],
    ['insert-mermaid', [5, 5], 'alpha\n\n```mermaid\n' + mermaidBody + '\n```\n', [18, 18]],
    ['insert-plantuml', [5, 5], 'alpha\n\n```plantuml\n' + plantumlBody + '\n```\n', [19, 19]],
    ['insert-footnote', [5, 5], 'alpha[^1]\n\n[^1]: ', [17, 17]],
    ['insert-embed', [5, 5], 'alpha![[]]', [8, 8]],
  ])('%s writes its text, and one undo restores the document', async (id, [from, to], text, [selFrom, selTo]) => {
    const view = await editorView('alpha');
    select(view, from, to);
    await run(id);
    expect(view.state.doc.toString()).toBe(text);
    expect([view.state.selection.main.from, view.state.selection.main.to]).toEqual([selFrom, selTo]);
    await blur(view);
    act(() => { undo(view); });
    expect(view.state.doc.toString()).toBe('alpha');
  });

  // The marker and the definition are two insertions far apart; as one replaced span they would rewrite everything
  // between them (and a collaborator's edit there would be rebased against a delete-and-reinsert of it).
  it('insert-footnote changes only the two places it writes: the marker and the definition', async () => {
    const content = 'First paragraph.\n\n' + 'Line of text.\n'.repeat(200) + '\nLast.';
    const view = await editorView(content);
    select(view, 15, 15);
    const changed = [];
    const dispatch = view.dispatch.bind(view);
    const spy = vi.spyOn(view, 'dispatch').mockImplementation((...specs) => {
      for (const spec of specs) {
        if (spec?.changes) view.state.changes(spec.changes).iterChanges((fromA, toA, _fromB, _toB, inserted) => changed.push([fromA, toA, inserted.toString()]));
      }
      return dispatch(...specs);
    });
    await run('insert-footnote');
    spy.mockRestore();
    expect(changed).toEqual([[15, 15, '[^1]'], [content.length, content.length, '\n\n[^1]: ']]);
    expect(view.state.doc.toString()).toBe(content.slice(0, 15) + '[^1]' + content.slice(15) + '\n\n[^1]: ');
    expect(view.state.selection.main.head).toBe(view.state.doc.length);
    await blur(view);
    act(() => { undo(view); });
    expect(view.state.doc.toString()).toBe(content);
  });

  it('the Mermaid starter is the fixture source the reader draws', () => {
    expect(mermaidBody).toBe('flowchart TD\n  A-->B');
    expect(fenceFixture.documents[0].markdown).toBe('```mermaid\n' + mermaidBody + '\n```\n');
  });
});

describe('PageEditor command: Access rule', () => {
  const fillAndInsert = () => {
    fireEvent.change(screen.getByLabelText('Permission'), { target: { value: 'edit' } });
    const field = screen.getByLabelText('Add a group, role or user…');
    for (const name of ['Editors', 'Admin']) {
      fireEvent.change(field, { target: { value: name } });
      fireEvent.keyDown(field, { key: 'Enter' });
    }
    // Scoped to the dialog: the toolbar's Insert menu has the same name.
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Access rule' })).getByRole('button', { name: 'Insert' }));
  };

  it('opens the form without touching the document; Insert writes the rule above the caret line, one undo removes it', async () => {
    const view = await editorView('alpha');
    select(view, 5, 5);
    // Opened from a button, as the toolbar does: closing the dialog hands focus back to that button first.
    const opener = document.body.appendChild(document.createElement('button'));
    opener.focus();
    await run('insert-access-rule');
    expect(screen.getByRole('dialog', { name: 'Access rule' })).toBeTruthy();
    expect(view.state.doc.toString()).toBe('alpha');
    fillAndInsert();
    expect(view.state.doc.toString()).toBe('[{ALLOW edit Editors, Admin}]\nalpha');
    expect(view.state.selection.main.head).toBe(30);
    expect(screen.queryByRole('dialog', { name: 'Access rule' })).toBeNull();
    await waitFor(() => expect(view.hasFocus).toBe(true));
    act(() => { undo(view); });
    expect(view.state.doc.toString()).toBe('alpha');
    opener.remove();
  });

  it('Cancel closes the form and leaves the document alone', async () => {
    const view = await editorView('alpha');
    await run('insert-access-rule');
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Access rule' })).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog', { name: 'Access rule' })).toBeNull();
    expect(view.state.doc.toString()).toBe('alpha');
  });
});
