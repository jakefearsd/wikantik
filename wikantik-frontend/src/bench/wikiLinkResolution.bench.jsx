/**
 * Wikilink resolution while typing in a page with 300 links: request volume of useWikiLinkResolution under
 * realistic typing (fake clock), and the CPU cost of the whole-page scans that run after every typing pause
 * (wikilink targets, missing-page targets, outline headings). Run with `npm run bench`.
 */
import { bench, describe, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { collectNativeWikiLinkTargets } from '../utils/wikiLinkSyntax';
import { collectWikiLinkTargets } from '../utils/wikiLinkTargets';
import { headingsFromMarkdown } from '../utils/headings';
import { makeDoc, makeLinkDoc, report } from './fixtures';

vi.mock('../api/client', () => ({
  api: {
    listPages: vi.fn(({ names }) => Promise.resolve({
      pages: [],
      resolved: Object.fromEntries(names.map((n) => [n, n.startsWith('LinkedPage') ? n : null])),
    })),
  },
}));
const { api } = await import('../api/client');
const { useWikiLinkResolution } = await import('../hooks/useWikiLinkResolution');

/** Type `text` at the end of `base`, one character every `gapMs`, pausing `pauseMs` after every `burst` chars. */
async function typeInto(rerender, base, text, { gapMs = 120, burst = Infinity, pauseMs = 0 } = {}) {
  let doc = base;
  for (let i = 0; i < text.length; i += 1) {
    doc += text[i];
    rerender({ md: doc });
    await act(async () => { await vi.advanceTimersByTimeAsync((i + 1) % burst === 0 ? pauseMs : gapMs); });
  }
  await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
  return doc;
}

async function measureRequests() {
  vi.useFakeTimers();
  const base = makeLinkDoc(300);
  api.listPages.mockClear();
  const { rerender, unmount } = renderHook(({ md }) => useWikiLinkResolution(md), { initialProps: { md: base } });
  await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
  const names = () => api.listPages.mock.calls.reduce((n, [q]) => n + q.names.length, 0);
  report('resolution: requests on load of a 300-link page', `${api.listPages.mock.calls.length} (${names()} names)`);

  api.listPages.mockClear();
  let doc = await typeInto(rerender, base, '\n\nSome plain prose typed without pauses, two hundred characters or so, nothing linked. '.repeat(2));
  report('resolution: requests while typing ~170 chars of prose (120 ms/char)', `${api.listPages.mock.calls.length} (${names()} names)`);

  api.listPages.mockClear();
  doc = await typeInto(rerender, doc, ' see [[Brand New Page]] and [[LinkedPage7]]');
  report('resolution: requests typing 2 new links continuously', `${api.listPages.mock.calls.length} (${names()} names)`);

  api.listPages.mockClear();
  doc = await typeInto(rerender, doc, ' and [[Another Fresh Page Name]]', { burst: 4, pauseMs: 700 });
  report('resolution: requests typing 1 new link with a 700 ms pause every 4 chars', `${api.listPages.mock.calls.length} (${names()} names)`);

  // With bracket auto-close the link is complete ([[…]]) while its name is still being typed.
  api.listPages.mockClear();
  let typed = '';
  for (const ch of 'Closed Bracket Name') {
    typed += ch;
    rerender({ md: `${doc} [[${typed}]]` });
    await act(async () => { await vi.advanceTimersByTimeAsync(typed.length % 4 === 0 ? 700 : 120); });
  }
  await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
  report('resolution: requests typing 1 name inside auto-closed [[]] with a 700 ms pause every 4 chars', `${api.listPages.mock.calls.length} (${names()} names)`);
  unmount();
  vi.useRealTimers();
}

const DOC = makeDoc(2000);
const LINKS = makeLinkDoc(300);

describe('whole-page scans after each typing pause', () => {
  // Every scan gets a fresh body (as after a real edit), so no parse cache can serve it.
  let n = 0;
  const fresh = (doc) => `${doc}\n${n += 1}`;
  bench('collectNativeWikiLinkTargets, 2,000-line page', () => { collectNativeWikiLinkTargets(fresh(DOC)); },
    { time: 1500, warmupTime: 200, setup: measureRequests });
  bench('collectWikiLinkTargets (missing pages), 2,000-line page', () => { collectWikiLinkTargets(fresh(DOC)); }, { time: 1500, warmupTime: 200 });
  bench('headingsFromMarkdown (outline), 2,000-line page', () => { headingsFromMarkdown(fresh(DOC)); }, { time: 1500, warmupTime: 200 });
  bench('both post-pause link scans of one body (resolution + missing pages), 2,000-line page', () => {
    const md = fresh(DOC);
    collectNativeWikiLinkTargets(md);
    collectWikiLinkTargets(md);
  }, { time: 1500, warmupTime: 200 });
  bench('collectNativeWikiLinkTargets, 300-link page', () => { collectNativeWikiLinkTargets(fresh(LINKS)); }, { time: 1000, warmupTime: 200 });
});
