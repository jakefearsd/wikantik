/**
 * Seeded randomized interleavings of typing, attachment-rename resolutions and latch-clock advances against the
 * REAL CodeMirror editor. A plain-string model receives the same operations; after every operation the editor
 * must show the model text, the caret must sit where the model says, and save probes must send exactly that text.
 * Deterministic: a fixed list of seeds drives a mulberry32 PRNG, and the latch clock is faked (realEditorHarness).
 */
import { describe, it, expect, vi, beforeEach, afterEach, afterAll } from 'vitest';
// eslint-disable-next-line testing-library/no-manual-cleanup -- flush async state between tests
import { act, cleanup, fireEvent } from '@testing-library/react';

vi.mock('../api/client', () => ({
  api: {
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
    getPageKnowledge: vi.fn(() => Promise.resolve({ entities: [], edges: [] })),
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
import {
  mountRealEditor, fakeLatchClock, releaseLatchClock, advanceLatch, flush, typeAtCaret, placeCaret,
  docOf, caretOf, deferred, startRename,
} from '../test/realEditorHarness';

/** mulberry32 — tiny, fast, good enough to shuffle test interleavings reproducibly. */
function prng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const INITIAL = 'intro ![a](old.png) mid [b](old.png) end\n';
const ALPHABET = 'abcdefghij xyz\n';
const TOKENS = ['![p](old.png)', '[q](old.png)', ' '];

/**
 * The model: the ORIGINAL rename rewrite (two regex passes over the whole text), plus caret mapping — a caret
 * before or at the start of a rewritten name stays, one at/after its end shifts by the growth; one strictly inside
 * is ambiguous and is resolved by asking the editor (the test then checks it landed inside the new name).
 */
function modelRename(model, oldName, newName) {
  const pattern = /(!?\[[^\]]*\])\(old\.png\)/g;
  const spans = [];
  for (let m = pattern.exec(model.text); m; m = pattern.exec(model.text)) {
    const from = m.index + m[1].length + 1;
    spans.push({ from, to: from + oldName.length });
  }
  const text = model.text
    .replace(/(!\[[^\]]*\])\(old\.png\)/g, `$1(${newName})`)
    .replace(/(\[[^\]]*\])\(old\.png\)/g, `$1(${newName})`);
  const grow = newName.length - oldName.length;
  let caret = model.caret;
  let inside = null;
  for (const s of spans) {
    if (model.caret >= s.to) caret += grow;
    else if (model.caret > s.from) inside = { from: s.from + grow * spans.filter((o) => o.to <= s.from).length };
  }
  if (inside) inside.to = inside.from + newName.length;
  return { text, caret, inside };
}

let renameAttachment;

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  window.matchMedia = vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} }));
  useToast.mockReturnValue({ success: vi.fn(), error: vi.fn(), info: vi.fn() });
  useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'alice', roles: ['Admin'] } });
  useDraft.mockReturnValue({ draft: null, saveDraft: vi.fn(), clearDraft: vi.fn() });
  renameAttachment = vi.fn();
  useAttachments.mockImplementation(() => ({
    list: [{ fileName: 'old.png', size: 10, isImage: true }],
    uploadAttachment: vi.fn(), renameAttachment, deleteAttachment: vi.fn(),
  }));
  api.getPage.mockResolvedValue({ content: INITIAL, metadata: {}, version: 1, markupSyntax: 'markdown' });
  // Saves are probes: rejecting keeps the editor mounted while the payload records what React holds.
  api.savePage.mockRejectedValue(new Error('probe'));
});

afterEach(async () => {
  cleanup();
  releaseLatchClock();
  await act(async () => { await Promise.resolve(); });
});

const SEEDS = Array.from({ length: 50 }, (_, i) => 1009 + i * 7919);

// Guard against a vacuous run: across all seeds the interleavings must actually hit the races under test.
const stats = { rewrites: 0, rewritesInLatch: 0, caretShifted: 0, caretInsideName: 0, rejected: 0, saves: 0, keystrokes: 0 };
afterAll(() => {
  expect(stats.rewrites).toBeGreaterThanOrEqual(40);         // renames that changed the text
  expect(stats.rewritesInLatch).toBeGreaterThanOrEqual(20);  // ...landing while the typing latch was armed
  expect(stats.caretShifted).toBeGreaterThanOrEqual(10);     // ...with the caret after a rewritten link
  expect(stats.rejected).toBeGreaterThanOrEqual(1);
  expect(stats.saves).toBeGreaterThanOrEqual(100);
  expect(stats.keystrokes).toBeGreaterThanOrEqual(1000);
});

describe('randomized interleaving of typing, attachment renames and the typing latch', () => {
  it.each(SEEDS)('seed %i keeps editor text == React body == model text, and every keystroke', async (seed) => {
    const rand = prng(seed);
    const pick = (arr) => arr[Math.floor(rand() * arr.length)];
    const view = await mountRealEditor(PageEditor, NavigationGuardProvider, INITIAL);
    fakeLatchClock();
    placeCaret(view, INITIAL.length);

    const model = { text: INITIAL, caret: INITIAL.length };
    const log = [];
    let pending = null;
    let renames = 0;
    let typedChars = 0;
    let sinceKey = Infinity; // latch-clock ms since the last keystroke (the latch window is 200 ms)
    const wait = (ms) => { advanceLatch(ms); sinceKey += ms; };

    const check = (where) => {
      const ctx = `seed ${seed} after ${where}; ops: ${log.join(' | ')}`;
      expect(docOf(view), ctx).toBe(model.text);
      expect(caretOf(view), ctx).toBe(model.caret);
    };
    const probeSave = async (where) => {
      const before = api.savePage.mock.calls.length;
      fireEvent.keyDown(window, { key: 's', ctrlKey: true });
      expect(api.savePage.mock.calls.length, `seed ${seed} ${where}: save did not fire`).toBe(before + 1);
      expect(api.savePage.mock.calls[before][1].content, `seed ${seed} save probe ${where}; ops: ${log.join(' | ')}`)
        .toBe(docOf(view));
      await flush(); // let the rejected probe settle so the next Ctrl+S is not ignored
      stats.saves += 1;
    };
    const resolvePending = async (outcome) => {
      const { d, newName } = pending;
      pending = null;
      if (outcome === 'reject') {
        log.push(`reject ${newName}`);
        d.reject(new Error('rename refused'));
        await flush();
        stats.rejected += 1;
        return;
      }
      log.push(`resolve ${newName}`);
      d.resolve({ ok: true });
      await flush();
      const next = modelRename(model, 'old.png', newName);
      if (next.text !== model.text) {
        stats.rewrites += 1;
        if (sinceKey < 200) stats.rewritesInLatch += 1;
        if (next.caret !== model.caret && !next.inside) stats.caretShifted += 1;
        if (next.inside) stats.caretInsideName += 1;
      }
      model.text = next.text;
      if (next.inside) {
        const c = caretOf(view);
        expect(c >= next.inside.from && c <= next.inside.to, `seed ${seed}: caret ${c} outside rewritten name`).toBe(true);
        model.caret = c;
      } else {
        model.caret = next.caret;
      }
    };

    for (let step = 0; step < 24; step += 1) {
      const r = rand();
      if (r < 0.42) {
        if (rand() < 0.3) {
          const pos = Math.floor(rand() * (model.text.length + 1));
          placeCaret(view, pos);
          model.caret = pos;
        }
        const burst = rand() < 0.25 ? pick(TOKENS)
          : Array.from({ length: 1 + Math.floor(rand() * 5) }, () => pick([...ALPHABET])).join('');
        log.push(`type ${JSON.stringify(burst)}@${model.caret}`);
        for (const ch of burst) {
          typeAtCaret(view, ch);
          model.text = model.text.slice(0, model.caret) + ch + model.text.slice(model.caret);
          model.caret += 1;
          typedChars += 1;
          stats.keystrokes += 1;
          sinceKey = 0;
          if (rand() < 0.3) wait(Math.floor(rand() * 120));
        }
      } else if (r < 0.58) {
        if (!pending) {
          renames += 1;
          const newName = `n${renames}x${Math.floor(rand() * 10)}.png`;
          const d = deferred();
          renameAttachment.mockReturnValueOnce(d.promise);
          log.push(`start ${newName}`);
          startRename(newName.slice(0, -4));
          pending = { d, newName };
        }
      } else if (r < 0.78) {
        if (pending) await resolvePending(rand() < 0.1 ? 'reject' : 'resolve');
      } else if (r < 0.95) {
        const ms = Math.floor(rand() * 400);
        log.push(`wait ${ms}`);
        wait(ms);
      } else {
        log.push('save');
        await probeSave(`step ${step}`);
      }
      check(`step ${step}`);
    }
    if (pending) await resolvePending('resolve');

    advanceLatch(); // the latch window is long gone: nothing parked may replay now
    check('latch expiry');
    await probeSave('final');
    expect(typedChars).toBeGreaterThan(0);
    // Every typed character is present: the editor text is the model text, which holds each keystroke.
    expect(docOf(view).length).toBe(model.text.length);
  }, 20000);
});
