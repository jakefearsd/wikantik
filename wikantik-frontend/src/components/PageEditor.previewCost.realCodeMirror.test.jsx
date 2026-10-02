/**
 * The editor preview re-runs react-markdown over the WHOLE page, so it must run only when its inputs change:
 * once per keystroke on a small page, never for an unrelated PageEditor re-render, and only after typing pauses
 * on a page whose preview renders are slow (usePreviewSource).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
// eslint-disable-next-line testing-library/no-manual-cleanup -- flush async state between tests
import { act, cleanup, fireEvent, screen } from '@testing-library/react';

const renders = vi.hoisted(() => ({ count: 0 }));
vi.mock('react-markdown', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, default: (props) => { renders.count += 1; return actual.default(props); } };
});
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
import { mountRealEditor, placeCaret, typeAtCaret, until } from '../test/realEditorHarness';

const preview = () => document.querySelector('.editor-preview .article-prose').textContent;

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  window.matchMedia = vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} }));
  useToast.mockReturnValue({ success: vi.fn(), error: vi.fn(), info: vi.fn() });
  useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'alice', roles: ['Admin'] } });
  useDraft.mockReturnValue({ draft: null, saveDraft: vi.fn(), clearDraft: vi.fn() });
  // A fresh list array on every call, as the real hook may return: the preview must not re-render for it.
  useAttachments.mockImplementation(() => ({ list: [{ fileName: 'a.png' }], uploadAttachment: vi.fn(), renameAttachment: vi.fn(), deleteAttachment: vi.fn() }));
});

afterEach(async () => {
  cleanup();
  await act(async () => { await Promise.resolve(); });
});

describe('editor preview render cost', () => {
  it('renders the preview once per keystroke on a small page, and not for unrelated re-renders', async () => {
    api.getPage.mockResolvedValue({ content: 'hello', metadata: {}, version: 1, markupSyntax: 'markdown' });
    const view = await mountRealEditor(PageEditor, NavigationGuardProvider, 'hello');
    await until(() => preview() === 'hello');
    placeCaret(view, 5);
    renders.count = 0;
    typeAtCaret(view, 'XYZ');
    expect(preview()).toBe('helloXYZ');
    expect(renders.count).toBe(3);
    renders.count = 0;
    fireEvent.click(screen.getByRole('button', { name: /live preview/i })); // re-renders PageEditor, same text
    await until(() => document.querySelector('.cm-editor.cm-live-preview') !== null);
    expect(renders.count).toBe(0);
  });

  it('on a page with slow preview renders, renders once after typing pauses instead of per keystroke', async () => {
    // ~20k characters of inline markup: one preview render takes several frame budgets (~100 ms here).
    const text = `start\n\n${'Some **bold**, *em*, `code` and [[Link]] text with $x^2$.\n\n'.repeat(330)}`;
    api.getPage.mockResolvedValue({ content: text, metadata: {}, version: 1, markupSyntax: 'markdown' });
    const view = await mountRealEditor(PageEditor, NavigationGuardProvider, text);
    await until(() => preview().startsWith('start'), 10000);
    placeCaret(view, 5);
    renders.count = 0;
    typeAtCaret(view, 'ABC');
    expect(renders.count).toBe(0);
    expect(preview().startsWith('startABC')).toBe(false);
    await until(() => preview().startsWith('startABC'), 10000);
    expect(renders.count).toBe(1);
  }, 30000); // slow on purpose (several whole-page renders), and slower still under coverage instrumentation
});
