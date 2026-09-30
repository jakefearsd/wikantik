import { describe, it, expect, vi } from 'vitest';
import { createWikiLinkSource } from './wikiLinkComplete';

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

  it('[[ searches pages live and inserts [Name](Name), unfiltered by CodeMirror', async () => {
    const d = deps();
    const res = await createWikiLinkSource(d)(ctx('see [[machine'));
    expect(d.searchPages).toHaveBeenCalledWith('machine');
    expect(res.from).toBe(4);
    expect(res.filter).toBe(false);
    expect(res.options[0]).toMatchObject({ label: 'MachineLearning', apply: '[MachineLearning](MachineLearning)' });
  });

  it('[[ replaces the auto-closed ]] that follows the cursor (to = pos + 2)', async () => {
    const res = await createWikiLinkSource(deps())(ctx('see [[machine', ']] tail'));
    expect(res.from).toBe(4);
    expect(res.to).toBe('see [[machine'.length + 2);
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
    expect(last.apply).toBe('[retirement planning](RetirementPlanning)');
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
    expect(res.options[0]).toMatchObject({ label: 'Install Steps', apply: '[Install Steps](MachineLearning#install-steps)' });
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

  it('M8: escapes brackets and backslashes in heading link text', async () => {
    const getHeadings = vi.fn(async () => [{ level: 2, text: 'Arrays [and] a\\b', line: 1, id: 'arrays-and-a-b' }]);
    const res = await createWikiLinkSource(deps({ getHeadings }))(ctx('[[Page#arr'));
    expect(res.options[0].apply).toBe('[Arrays \\[and\\] a\\\\b](Page#arrays-and-a-b)');
  });
});
