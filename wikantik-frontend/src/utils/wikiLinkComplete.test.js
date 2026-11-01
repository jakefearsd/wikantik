import { describe, it, expect, vi, afterEach } from 'vitest';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { history, undo } from '@codemirror/commands';
import { codeFolding, foldEffect, foldedRanges } from '@codemirror/language';
import { CompletionContext, autocompletion } from '@codemirror/autocomplete';
import { createWikiLinkSource } from './wikiLinkComplete';
import { blocksFromMarkdown } from './blockIds';

// Minimal CodeMirror CompletionContext stand-in.
function ctx(textBefore, textAfter = '') {
  return {
    aborted: false,
    pos: textBefore.length,
    state: { doc: { sliceString: (from, to) => (textBefore + textAfter).slice(from, to) } },
    matchBefore(re) {
      const m = textBefore.match(re);
      if (!m) return null;
      return { from: textBefore.length - m[0].length, to: textBefore.length, text: m[0] };
    },
  };
}

const HEADINGS = [
  { level: 1, text: 'Title', line: 1, id: null },
  { level: 2, text: 'Setup', line: 3, id: 'setup' },
  { level: 3, text: 'Install Steps', line: 5, id: 'install-steps' },
];

function deps(overrides = {}) {
  return {
    searchPages: vi.fn(async () => ['MachineLearning', 'MachineLearningHub']),
    getHeadings: vi.fn(async () => HEADINGS),
    getAttachmentNames: vi.fn(() => ['diagram.png', 'notes.pdf']),
    ...overrides,
  };
}

describe('createWikiLinkSource', () => {
  it('returns null without a trigger', async () => {
    expect(await createWikiLinkSource(deps())(ctx('plain text'))).toBeNull();
  });

  it('[[ searches pages live and inserts [[Name]], unfiltered by CodeMirror', async () => {
    const d = deps();
    const res = await createWikiLinkSource(d)(ctx('see [[machine'));
    expect(d.searchPages).toHaveBeenCalledWith('machine');
    expect(res.from).toBe(4);
    expect(res.filter).toBe(false);
    expect(res.options[0]).toMatchObject({ label: 'MachineLearning', apply: '[[MachineLearning]]' });
  });

  it('[[ replaces the auto-closed ]] that follows the cursor (to = pos + 2)', async () => {
    const res = await createWikiLinkSource(deps())(ctx('see [[machine', ']] tail'));
    expect(res.from).toBe(4);
    expect(res.to).toBe('see [[machine'.length + 2);
  });

  it('stays quiet once an alias is being typed ([[Name|al), so the alias is never overwritten', async () => {
    const d = deps();
    expect(await createWikiLinkSource(d)(ctx('see [[MachineLearning|the ml', ']]'))).toBeNull();
    expect(await createWikiLinkSource(d)(ctx('see [[MachineLearning#Setup|al', ']]'))).toBeNull();
    expect(d.searchPages).not.toHaveBeenCalled();
  });

  it('[[ without trailing ]] leaves `to` unset', async () => {
    const res = await createWikiLinkSource(deps())(ctx('see [[machine', ' tail'));
    expect(res.to).toBeUndefined();
  });

  it('[[Page#heading also replaces a trailing auto-closed ]]', async () => {
    const res = await createWikiLinkSource(deps())(ctx('[[MachineLearning#inst', ']]'));
    expect(res.to).toBe('[[MachineLearning#inst'.length + 2);
  });

  it('](target never extends over the auto-closed )', async () => {
    const res = await createWikiLinkSource(deps())(ctx('[x](not', ')'));
    expect(res.to).toBeUndefined();
  });

  it('[[ offers a new-page link when nothing matches exactly', async () => {
    const res = await createWikiLinkSource(deps())(ctx('[[retirement planning'));
    const last = res.options[res.options.length - 1];
    expect(last.label).toBe('Link to new page: RetirementPlanning');
    expect(last.apply).toBe('[[retirement planning]]');
  });

  it('no new-page item when a result matches exactly (case-insensitive)', async () => {
    const res = await createWikiLinkSource(deps())(ctx('[[machinelearning'));
    expect(res.options.some((o) => o.label.startsWith('Link to new page'))).toBe(false);
  });

  it('[[Page# completes h2/h3 headings of the target page with the view anchor', async () => {
    const d = deps();
    const res = await createWikiLinkSource(d)(ctx('[[MachineLearning#inst'));
    expect(d.getHeadings).toHaveBeenCalledWith('MachineLearning');
    expect(res.options).toHaveLength(1);
    expect(res.options[0]).toMatchObject({ label: 'Install Steps', apply: '[[MachineLearning#Install Steps]]' });
  });

  it('](target completes pages and attachments, replacing only the target', async () => {
    const res = await createWikiLinkSource(deps({ searchPages: vi.fn(async () => ['NotesIndex']) }))(ctx('[x](not'));
    expect(res.from).toBe(4);
    expect(res.options.map((o) => o.apply)).toEqual(['NotesIndex', 'notes.pdf', 'Not']);
  });

  it('](# completes headings of the page being edited', async () => {
    const d = deps();
    const res = await createWikiLinkSource(d)(ctx('[x](#se'));
    expect(d.getHeadings).toHaveBeenCalledWith(null);
    expect(res.from).toBe(5);
    expect(res.options[0]).toMatchObject({ label: 'Setup', apply: 'setup' });
  });

  it('](target stays quiet for URLs with a scheme or a leading slash', async () => {
    const d = deps();
    const src = createWikiLinkSource(d);
    expect(await src(ctx('[x](https://exa'))).toBeNull();
    expect(await src(ctx('[x](mailto:me'))).toBeNull();
    expect(await src(ctx('[x](/wiki/Fo'))).toBeNull();
    expect(d.searchPages).not.toHaveBeenCalled();
  });

  it('a page named HttpClient is still completable (no literal http suppression)', async () => {
    const res = await createWikiLinkSource(deps({ searchPages: vi.fn(async () => ['HttpClient']) }))(ctx('[x](Http'));
    expect(res.options[0].apply).toBe('HttpClient');
  });

  it('returns null and warns when the page search fails, then retries on the next call', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const searchPages = vi.fn()
      .mockRejectedValueOnce(new Error('503'))
      .mockResolvedValueOnce(['MachineLearning']);
    const src = createWikiLinkSource(deps({ searchPages }));
    expect(await src(ctx('[[mach'))).toBeNull();
    expect(warn).toHaveBeenCalled();
    expect((await src(ctx('[[mach'))).options[0].label).toBe('MachineLearning');
    warn.mockRestore();
  });

  it('returns null when the heading fetch fails', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const res = await createWikiLinkSource(deps({ getHeadings: vi.fn(async () => { throw new Error('404'); }) }))(ctx('[[Page#x'));
    expect(res).toBeNull();
    warn.mockRestore();
  });

  it('returns null when aborted during the debounce', async () => {
    const d = deps();
    const c = ctx('[[mach');
    const p = createWikiLinkSource(d)(c);
    c.aborted = true;
    expect(await p).toBeNull();
    expect(d.searchPages).not.toHaveBeenCalled();
  });

  it('I2: [[ with spaces searches the whitespace-free name and offers no new-page item for an existing page', async () => {
    const searchPages = vi.fn(async (q) => (q === 'machinelearning' ? ['MachineLearning'] : []));
    const res = await createWikiLinkSource(deps({ searchPages }))(ctx('see [[machine learning'));
    expect(searchPages).toHaveBeenCalledWith('machinelearning');
    expect(res.options.map((o) => o.label)).toEqual(['MachineLearning']);
  });

  it('M8: strips brackets, pipes and backslashes from heading text', async () => {
    const getHeadings = vi.fn(async () => [{ level: 2, text: 'Arrays [and] a\\b', line: 1, id: 'arrays-and-a-b' }]);
    const res = await createWikiLinkSource(deps({ getHeadings }))(ctx('[[Page#arr'));
    expect(res.options[0].apply).toBe('[[Page#Arrays and ab]]');
  });

  it('[[# completes headings of the page being edited as [[#Heading]]', async () => {
    const d = deps({ getHeadings: vi.fn(async () => [{ text: 'Setup', id: 'setup' }]) });
    const res = await createWikiLinkSource(d)(ctx('[[#se'));
    expect(res.options[0].apply).toBe('[[#Setup]]');
  });

  it('keeps the ! of an embed outside the replaced range', async () => {
    const res = await createWikiLinkSource(deps())(ctx('![[Mach'));
    expect(res.from).toBe(1);
    expect(res.options[0].apply).toBe('[[MachineLearning]]');
  });
});

// ── Block completion: [[Page#^q and [[#^q ─────────────────────────────────────────────────────────────────────────────
describe('block completion', () => {
  const REMOTE = {
    version: 3,
    canAssign: true,
    blocks: [
      { index: 0, id: 'abc', kind: 'paragraph', text: 'Alpha text' },
      { index: 2, id: null, kind: 'list-item', text: 'Beta item' },
    ],
  };
  const localBlocks = (md) => ({
    local: true,
    canAssign: true,
    blocks: blocksFromMarkdown(md).map(({ index, id, kind, text, rules }) => ({ index, id, kind, text, rules })),
  });

  let views = [];
  /** A real EditorView with history, the caret at the end (or at `at`), and a count of document changes. */
  function viewOf(doc, at = doc.length, extra = []) {
    const parent = document.createElement('div');
    document.body.appendChild(parent);
    const changes = { count: 0 };
    const view = new EditorView({
      parent,
      state: EditorState.create({
        doc,
        selection: { anchor: at },
        extensions: [...extra, history(), EditorView.updateListener.of((u) => { if (u.docChanged) changes.count += u.transactions.length; })],
      }),
    });
    views.push(view);
    return { view, changes };
  }
  const contextOf = (view) => new CompletionContext(view.state, view.state.selection.main.head, true);
  const blockDeps = (overrides = {}) => deps({
    getBlocks: vi.fn(async () => REMOTE),
    assignBlock: vi.fn(async () => ({ id: 'newid1', version: 4 })),
    notify: vi.fn(),
    ...overrides,
  });
  const flush = () => new Promise((r) => { setTimeout(r, 0); });
  /** Picks `opt` as CodeMirror does: over [from, to), `to` defaulting to the caret. */
  const pick = (view, res, opt) => opt.apply(view, opt, res.from, res.to ?? view.state.selection.main.head);

  afterEach(() => {
    views.forEach((v) => { v.destroy(); v.dom.parentNode?.remove(); });
    views = [];
    vi.restoreAllMocks();
  });

  it('[[Page#^ lists the page blocks, labelled with their text and ^id, filtered by text or id', async () => {
    const d = blockDeps();
    const src = createWikiLinkSource(d);
    const all = await src(ctx('[[Page#^'));
    expect(d.getBlocks).toHaveBeenCalledWith('Page');
    expect(d.getHeadings).not.toHaveBeenCalled();
    expect(all.options.map((o) => [o.label, o.detail])).toEqual([['Alpha text', '^abc'], ['Beta item', undefined]]);
    expect((await src(ctx('[[Page#^bet'))).options.map((o) => o.label)).toEqual(['Beta item']);
    expect((await src(ctx('[[Page#^AB'))).options.map((o) => o.label)).toEqual(['Alpha text']);
    expect(await src(ctx('[[Page#^zzz'))).toBeNull();
  });

  it('picking a block with an id inserts [[Page#^id]], replacing an auto-closed ]]', async () => {
    const res = await createWikiLinkSource(blockDeps())(ctx('[[Page#^alp', ']]'));
    expect(res.options[0].apply).toBe('[[Page#^abc]]');
    expect(res.to).toBe('[[Page#^alp'.length + 2);
  });

  it('[[Page# (no caret) still lists headings only', async () => {
    const d = blockDeps();
    const res = await createWikiLinkSource(d)(ctx('[[Page#inst'));
    expect(d.getBlocks).not.toHaveBeenCalled();
    expect(res.options[0].type).toBe('heading');
  });

  it('labels a long block by its first 120 chars and an empty one by its kind', async () => {
    const getBlocks = vi.fn(async () => ({ canAssign: false, blocks: [
      { index: 0, id: 'l', kind: 'paragraph', text: `${'w'.repeat(130)}\nsecond` },
      { index: 1, id: 't', kind: 'table', text: '  ' },
    ] }));
    const res = await createWikiLinkSource(blockDeps({ getBlocks }))(ctx('[[P#^'));
    expect(res.options[0].label).toBe(`${'w'.repeat(119)}…`);
    expect(res.options[1].label).toBe('(table)');
  });

  it('an id-less block on a page you cannot edit is listed disabled, and picking it does nothing', async () => {
    const getBlocks = vi.fn(async () => ({ ...REMOTE, canAssign: false }));
    const d = blockDeps({ getBlocks });
    const res = await createWikiLinkSource(d)(ctx('[[Page#^'));
    const off = res.options[1];
    expect(off).toMatchObject({ label: 'Beta item', detail: 'no id — needs edit access', disabled: true });
    expect(off.info).toMatch(/edit access/);
    const { view, changes } = viewOf('[[Page#^');
    off.apply(view, off, 0, 8);
    expect(changes.count).toBe(0);
    expect(d.assignBlock).not.toHaveBeenCalled();
    expect(res.options[0].disabled).toBeUndefined();
  });

  it('an id-less block on another page is assigned with the server index and version, then linked', async () => {
    const d = blockDeps();
    const { view } = viewOf('See [[Page#^]]', 'See [[Page#^'.length);
    const res = await createWikiLinkSource(d)(contextOf(view));
    pick(view, res, res.options[1]);
    expect(d.assignBlock).toHaveBeenCalledWith('Page', 3, 2);
    await flush();
    expect(view.state.doc.toString()).toBe('See [[Page#^newid1]]');
    expect(view.state.selection.main.head).toBe('See [[Page#^newid1]]'.length);
    expect(d.notify).not.toHaveBeenCalled();
  });

  it('a stale assign re-fetches the block list once and asks to pick again, inserting nothing', async () => {
    const stale = Object.assign(new Error('409'), { status: 409, body: { reason: 'stale' } });
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const d = blockDeps({ assignBlock: vi.fn(async () => { throw stale; }) });
    const { view, changes } = viewOf('[[Page#^');
    const res = await createWikiLinkSource(d)(contextOf(view));
    pick(view, res, res.options[1]);
    await flush();
    expect(d.getBlocks).toHaveBeenCalledTimes(2);
    expect(d.notify).toHaveBeenCalledWith('This page changed — pick the block again');
    expect(changes.count).toBe(0);
    expect(warn).toHaveBeenCalled();
  });

  it('other assign failures toast and insert nothing', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const derived = Object.assign(new Error('409'), { status: 409, body: { reason: 'derived' } });
    for (const [err, message] of [
      [derived, "This page is generated; block ids can't be added here"],
      [new Error('boom'), "Couldn't add a block id to Page"],
    ]) {
      const d = blockDeps({ assignBlock: vi.fn(async () => { throw err; }) });
      const { view, changes } = viewOf('[[Page#^');
      const res = await createWikiLinkSource(d)(contextOf(view));
      pick(view, res, res.options[1]);
      await flush();
      expect(d.notify).toHaveBeenCalledWith(message);
      expect(d.getBlocks).toHaveBeenCalledTimes(1);
      expect(changes.count).toBe(0);
    }
    expect(warn).toHaveBeenCalled();
  });

  it('does not overwrite text typed while the assign was in flight', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    let resolve;
    const d = blockDeps({ assignBlock: vi.fn(() => new Promise((r) => { resolve = r; })) });
    const { view } = viewOf('[[Page#^');
    const res = await createWikiLinkSource(d)(contextOf(view));
    pick(view, res, res.options[1]);
    view.dispatch({ changes: { from: 2, insert: 'X' } });
    resolve({ id: 'late', version: 4 });
    await flush();
    expect(view.state.doc.toString()).toBe('[[XPage#^');
    expect(d.notify).toHaveBeenCalledWith('The text changed — pick the block again');
    expect(warn).toHaveBeenCalled();
  });

  it('maps the typed span through edits made elsewhere while the assign is in flight (before and after it)', async () => {
    let resolve;
    const d = blockDeps({ assignBlock: vi.fn(() => new Promise((r) => { resolve = r; })) });
    const { view } = viewOf('Intro\n[[Page#^\nOutro');
    view.dispatch({ selection: { anchor: 'Intro\n[[Page#^'.length } });
    const res = await createWikiLinkSource(d)(contextOf(view));
    pick(view, res, res.options[1]);
    view.dispatch({ changes: { from: 0, insert: 'More ' }, selection: { anchor: 5 } });           // before the span
    view.dispatch({ changes: { from: view.state.doc.length, insert: '!' } });                     // after it
    view.dispatch({ changes: { from: 'More Intro\n[[Page#^'.length, insert: ' x' } });          // right at its end
    resolve({ id: 'late', version: 4 });
    await flush();
    expect(view.state.doc.toString()).toBe('More Intro\n[[Page#^late]] x\nOutro!');
    expect(view.state.selection.main.head).toBe(5);   // the caret, now elsewhere, is not yanked to the link
    expect(d.notify).not.toHaveBeenCalled();
  });

  it('tracks several pending picks in one editor independently; a span edited twice stays abandoned', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const answers = [];
    const d = blockDeps({ assignBlock: vi.fn(() => new Promise((r) => { answers.push(r); })) });
    const { view } = viewOf('[[Page#^\n[[Page#^', 8);
    const src = createWikiLinkSource(d);
    const first = await src(contextOf(view));
    pick(view, first, first.options[1]);
    view.dispatch({ selection: { anchor: view.state.doc.length } });
    const second = await src(contextOf(view));
    pick(view, second, second.options[1]);
    view.dispatch({ changes: { from: 3, insert: 'X' } });
    view.dispatch({ changes: { from: 4, insert: 'Y' } });
    answers[1]({ id: 'two', version: 4 });
    answers[0]({ id: 'one', version: 4 });
    await flush();
    expect(view.state.doc.toString()).toBe('[[PXYage#^\n[[Page#^two]]');
    expect(d.notify).toHaveBeenCalledWith('The text changed — pick the block again');
    expect(d.notify).toHaveBeenCalledTimes(1);
  });

  it('a failure after the assign (the view is gone) is logged, never an unhandled rejection', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    let resolve;
    const d = blockDeps({ assignBlock: vi.fn(() => new Promise((r) => { resolve = r; })) });
    const { view } = viewOf('[[Page#^');
    const res = await createWikiLinkSource(d)(contextOf(view));
    pick(view, res, res.options[1]);
    view.dispatch = () => { throw new Error('view is gone'); };
    resolve({ id: 'late', version: 4 });
    await flush();
    expect(warn).toHaveBeenCalledWith('[link-complete] could not link the block', 'Page', 2, 'view is gone');
  });

  it('a local pick unfolds a fold hiding the block before adding its marker, in the same transaction', async () => {
    vi.spyOn(globalThis.crypto, 'getRandomValues').mockImplementation((arr) => arr.fill(4));
    const doc = '## Folded\n\nHidden para\n\n## Next\n\n[[#^hid';
    const { view, changes } = viewOf(doc, doc.length, [codeFolding()]);
    view.dispatch({ effects: foldEffect.of({ from: 9, to: 22 }) });
    expect(foldedRanges(view.state).size).toBe(1);
    const d = blockDeps({ getBlocks: vi.fn(async () => localBlocks(doc)) });
    const res = await createWikiLinkSource(d)(contextOf(view));
    pick(view, res, res.options[0]);
    expect(view.state.doc.toString()).toBe('## Folded\n\nHidden para ^eeeeee\n\n## Next\n\n[[#^eeeeee]]');
    expect(foldedRanges(view.state).size).toBe(0);
    expect(changes.count).toBe(1);
  });

  it('a complete listing is filtered locally; an incomplete one asks the server with q (debounced, not re-filtered)', async () => {
    const big = { version: 7, canAssign: true, complete: false,
      blocks: Array.from({ length: 100 }, (_, i) => ({ index: i, id: `b${i}`, kind: 'paragraph', text: `Block ${i}` })) };
    const narrowed = { version: 8, canAssign: true,
      blocks: [{ index: 140, id: null, kind: 'paragraph', text: 'An excerpt cut before the match' }] };
    const getBlocks = vi.fn(async (page, q) => (q ? narrowed : big));
    const d = blockDeps({ getBlocks });
    const src = createWikiLinkSource(d);

    const first20 = await src(ctx('[[Page#^'));                       // no query: the cached listing serves it
    expect(first20.options).toHaveLength(20);
    expect(getBlocks).toHaveBeenCalledTimes(1);

    const { view } = viewOf('[[Page#^deep');
    const res = await src(contextOf(view));
    expect(getBlocks).toHaveBeenLastCalledWith('Page', 'deep');
    expect(res.options.map((o) => o.label)).toEqual(['An excerpt cut before the match']);
    pick(view, res, res.options[0]);
    expect(d.assignBlock).toHaveBeenCalledWith('Page', 8, 140);      // the narrowed listing's own version and index

    const complete = { ...big, complete: true };
    const local = blockDeps({ getBlocks: vi.fn(async () => complete) });
    const hits = await createWikiLinkSource(local)(ctx('[[Page#^Block 9'));
    expect(local.getBlocks).toHaveBeenCalledTimes(1);
    expect(hits.options.map((o) => o.label)).toEqual(['Block 9', ...Array.from({ length: 10 }, (_, i) => `Block ${90 + i}`)]);
  });

  it('the server query waits out the debounce and is skipped when the completion is aborted', async () => {
    const getBlocks = vi.fn(async (page, q) => (q ? { version: 1, canAssign: true, blocks: [] } : { version: 1, complete: false, blocks: [] }));
    const c = ctx('[[Page#^que');
    const p = createWikiLinkSource(blockDeps({ getBlocks }))(c);
    await flush();
    c.aborted = true;
    expect(await p).toBeNull();
    expect(getBlocks).toHaveBeenCalledTimes(1);
    const failing = vi.fn(async (page, q) => { if (q) throw new Error('503'); return { version: 1, complete: false, blocks: [] }; });
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    expect(await createWikiLinkSource(blockDeps({ getBlocks: failing }))(ctx('[[Page#^que'))).toBeNull();
    expect(warn).toHaveBeenCalled();
  });

  it('[[#^ lists the current buffer blocks; an id-less pick adds the marker and the link in ONE undoable transaction', async () => {
    vi.spyOn(globalThis.crypto, 'getRandomValues').mockImplementation((arr) => arr.fill(2));
    const doc = 'Alpha para\n\nSee [[#^alp]]';
    const { view, changes } = viewOf(doc, 'Alpha para\n\nSee [[#^alp'.length);
    const getBlocks = vi.fn(async () => localBlocks(view.state.doc.toString()));
    const d = blockDeps({ getBlocks });
    const res = await createWikiLinkSource(d)(contextOf(view));
    expect(getBlocks).toHaveBeenCalledWith(null);
    expect(res.options[0].label).toBe('Alpha para');
    pick(view, res, res.options[0]);
    expect(view.state.doc.toString()).toBe('Alpha para ^cccccc\n\nSee [[#^cccccc]]');
    expect(view.state.selection.main.head).toBe(view.state.doc.length);
    expect(changes.count).toBe(1);
    expect(d.assignBlock).not.toHaveBeenCalled();
    undo(view);
    expect(view.state.doc.toString()).toBe(doc);
  });

  it('[[#^ on a block with an id inserts [[#^id]]', async () => {
    const getBlocks = vi.fn(async () => localBlocks('Alpha ^abc\n'));
    const res = await createWikiLinkSource(blockDeps({ getBlocks }))(ctx('x [[#^al'));
    expect(res.options[0].apply).toBe('[[#^abc]]');
  });

  it('a page the buffer IS (local) is linked by name and edited locally', async () => {
    vi.spyOn(globalThis.crypto, 'getRandomValues').mockImplementation((arr) => arr.fill(3));
    const { view } = viewOf('Gamma\n\n[[Self#^ga');
    const getBlocks = vi.fn(async () => localBlocks(view.state.doc.toString()));
    const d = blockDeps({ getBlocks });
    const res = await createWikiLinkSource(d)(contextOf(view));
    pick(view, res, res.options[0]);
    expect(view.state.doc.toString()).toBe('Gamma ^dddddd\n\n[[Self#^dddddd]]');
    expect(d.assignBlock).not.toHaveBeenCalled();
  });

  it('[[#^ lists a block holding access rules disabled, with or without an id, and picking it edits nothing', async () => {
    const doc = 'Para ^id [{ ALLOW view Admin}]\n\nOther [{ALLOW view Admin}]\n\n[[#^';
    const { view, changes } = viewOf(doc);
    const d = blockDeps({ getBlocks: vi.fn(async () => localBlocks(doc)) });
    const res = await createWikiLinkSource(d)(contextOf(view));
    const [tagged, untagged] = res.options;
    for (const off of [tagged, untagged]) {
      expect(off).toMatchObject({ detail: 'contains access rules — link it from the page view', disabled: true });
      off.apply(view, off, res.from, res.to ?? view.state.selection.main.head);
    }
    expect(changes.count).toBe(0);
  });

  it('a local pick whose block came to hold access rules by the time it applies is refused', async () => {
    const { view, changes } = viewOf('Alpha\n\n[[#^');
    const d = blockDeps({ getBlocks: vi.fn(async () => localBlocks('Alpha\n\n[[#^')) });
    const res = await createWikiLinkSource(d)(contextOf(view));
    view.dispatch({ changes: { from: 5, insert: ' [{ ALLOW view Admin}]' } });
    const before = changes.count;
    const opt = res.options[0];
    opt.apply(view, opt, res.from + 22, (res.to ?? res.from) + 22);
    expect(changes.count).toBe(before);
    expect(d.notify).toHaveBeenCalledWith('This block contains access rules — link it from the page view');
  });

  it('a local pick whose block is gone by the time it applies warns and leaves the text alone', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const { view, changes } = viewOf('Alpha\n\n[[#^');
    const getBlocks = vi.fn(async () => localBlocks('Alpha\n\n[[#^'));
    const d = blockDeps({ getBlocks });
    const res = await createWikiLinkSource(d)(contextOf(view));
    view.dispatch({ changes: { from: 0, to: 5, insert: '# Now a heading' } });
    const before = changes.count;
    res.options[0].apply(view, res.options[0], res.from + 10, res.to + 10);
    expect(changes.count).toBe(before);
    expect(d.notify).toHaveBeenCalledWith('The text changed — pick the block again');
    expect(warn).toHaveBeenCalled();
  });

  it('returns null when aborted while the blocks load, or when a listing has no blocks', async () => {
    const c = ctx('[[Page#^');
    const p = createWikiLinkSource(blockDeps())(c);
    c.aborted = true;
    expect(await p).toBeNull();
    expect(await createWikiLinkSource(blockDeps({ getBlocks: vi.fn(async () => ({ canAssign: true })) }))(ctx('[[P#^'))).toBeNull();
    const textless = await createWikiLinkSource(blockDeps({ getBlocks: vi.fn(async () => ({ blocks: [{ index: 0, id: 'x', kind: 'math' }] })) }))(ctx('[[P#^x'));
    expect(textless.options[0]).toMatchObject({ label: '(math)', apply: '[[P#^x]]' });
  });

  it('a stale assign still toasts when the completion cannot be reopened (a non-Error rejection is logged too)', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const d = blockDeps({ assignBlock: vi.fn(() => Promise.reject({ status: 409, body: { reason: 'stale' } })) });
    const { view } = viewOf('[[Page#^', undefined, [autocompletion({ override: [] })]);
    const res = await createWikiLinkSource(d)(contextOf(view));
    const dispatch = view.dispatch.bind(view);
    let calls = 0;
    pick(view, res, res.options[1]);   // tracks the typed span (two dispatches)
    view.dispatch = (...specs) => {   // releasing it works; reopening the completion fails
      calls += 1;
      if (calls > 1) throw new Error('view is gone');
      return dispatch(...specs);
    };
    await flush();
    expect(d.notify).toHaveBeenCalledWith('This page changed — pick the block again');
    expect(warn).toHaveBeenCalledWith('[link-complete] could not reopen completion', 'view is gone');
    const odd = blockDeps({ assignBlock: vi.fn(() => Promise.reject('nope')) });
    const second = viewOf('[[Page#^');
    const res2 = await createWikiLinkSource(odd)(contextOf(second.view));
    pick(second.view, res2, res2.options[1]);
    await flush();
    expect(warn).toHaveBeenCalledWith('[link-complete] could not add a block id', 'Page', 2, 'nope');
  });

  it('a local block that cannot carry a marker is reported, and nothing is inserted', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const { view, changes } = viewOf('- ^x\n\n[[#^');
    const d = blockDeps({ getBlocks: vi.fn(async () => localBlocks('- ^x\n\n[[#^')) });
    const res = await createWikiLinkSource(d)(contextOf(view));
    const item = res.options.find((o) => !o.detail);
    pick(view, res, item);
    expect(changes.count).toBe(0);
    expect(d.notify).toHaveBeenCalledWith("Couldn't add a block id here");
    expect(warn).toHaveBeenCalled();
  });

  it('returns null when the block lookup fails', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const res = await createWikiLinkSource(blockDeps({ getBlocks: vi.fn(async () => { throw new Error('503'); }) }))(ctx('[[P#^x'));
    expect(res).toBeNull();
    expect(warn).toHaveBeenCalled();
  });

  it('without block deps, [[Page#^ offers nothing; without assign/notify, an assign fails into a warning', async () => {
    expect(await createWikiLinkSource(deps())(ctx('[[Page#^x'))).toBeNull();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const { view, changes } = viewOf('[[Page#^');
    const res = await createWikiLinkSource(deps({ getBlocks: vi.fn(async () => REMOTE) }))(contextOf(view));
    pick(view, res, res.options[1]);
    await flush();
    expect(warn).toHaveBeenCalledWith("[link-complete] Couldn't add a block id to Page");
    expect(changes.count).toBe(0);
  });
});
