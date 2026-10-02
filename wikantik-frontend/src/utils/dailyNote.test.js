import { describe, it, expect, vi, beforeEach } from 'vitest';
import { dailyNoteName, dailyNoteTitle, buildDailyNote, openDailyNote } from './dailyNote';
const OCT1 = new Date(2026, 9, 1, 23, 30); // local time, late evening — must still be the 1st
describe('daily note helpers', () => {
  it('names the note by the LOCAL date and titles it with the long local date', () => {
    expect([dailyNoteName(OCT1), dailyNoteName(new Date(2026, 0, 5))]).toEqual(['2026-10-01', '2026-01-05']);
    expect(dailyNoteTitle(OCT1, 'en-GB')).toBe('Thursday, 1 October 2026');
  });
  it('builds an article tagged daily-note, in the journal cluster only when one exists', () => {
    expect(buildDailyNote(OCT1, { locale: 'en-GB' })).toEqual({
      name: '2026-10-01',
      initialMetadata: { type: 'article', date: '2026-10-01', tags: ['daily-note'], title: 'Thursday, 1 October 2026' },
      initialContent: '# Thursday, 1 October 2026\n\n',
    });
    expect(buildDailyNote(OCT1, { journal: true, locale: 'en-GB' }).initialMetadata.cluster).toBe('journal');
  });
});
describe('openDailyNote', () => {
  let api; let go;
  beforeEach(() => { go = vi.fn(); api = { listPages: vi.fn(), listClusters: vi.fn(() => Promise.resolve({ clusters: [{ name: 'journal' }] })) }; });
  it('opens an existing note without creation state', async () => {
    api.listPages.mockResolvedValue({ pages: [{ name: '2026-10-01' }] });
    await openDailyNote({ api, go, now: OCT1 });
    expect(api.listPages).toHaveBeenCalledWith({ names: ['2026-10-01'], limit: 1 });
    expect(go).toHaveBeenCalledWith('/edit/2026-10-01');
    expect(api.listClusters).not.toHaveBeenCalled();
  });
  it('starts a new note, in the journal cluster when a hub declares it', async () => {
    api.listPages.mockResolvedValue({ pages: [] });
    await openDailyNote({ api, go, now: OCT1, locale: 'en-GB' });
    const [path, opts] = go.mock.calls[0];
    expect(path).toBe('/edit/2026-10-01');
    expect(opts.state.initialMetadata).toMatchObject({ type: 'article', tags: ['daily-note'], cluster: 'journal' });
    expect(opts.state.initialContent).toBe('# Thursday, 1 October 2026\n\n');
  });
  it('omits the cluster when no journal hub exists', async () => {
    api.listPages.mockResolvedValue({ pages: [] });
    api.listClusters.mockResolvedValue({ clusters: [{ name: 'finance' }] });
    await openDailyNote({ api, go, now: OCT1 });
    expect(go.mock.calls[0][1].state.initialMetadata.cluster).toBeUndefined();
  });
  it('when the existence lookup fails, opens /edit/<name> WITHOUT new-page state (never seed over a note that may exist)', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.listPages.mockRejectedValue(new Error('offline'));
    api.listClusters.mockRejectedValue(new Error('offline'));
    await openDailyNote({ api, go, now: OCT1 });
    expect(go).toHaveBeenCalledTimes(1);
    expect(go).toHaveBeenCalledWith('/edit/2026-10-01');
    expect(warn).toHaveBeenCalledTimes(1);
    warn.mockRestore();
  });
  it('does not double-navigate when invoked twice while the lookup is in flight', async () => {
    api.listPages.mockResolvedValue({ pages: [{ name: '2026-10-01' }] });
    await Promise.all([openDailyNote({ api, go, now: OCT1 }), openDailyNote({ api, go, now: OCT1 })]);
    expect(go).toHaveBeenCalledTimes(1);
    expect(api.listPages).toHaveBeenCalledTimes(1);
    await openDailyNote({ api, go, now: OCT1 }); // a later invocation is allowed again
    expect(go).toHaveBeenCalledTimes(2);
  });
});
