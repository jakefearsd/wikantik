import { describe, it, expect, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useCollabJoin } from './useCollabJoin';

// Offline drafts already offered are remembered per browser tab (module state), so each test uses its own login.
let seq = 0;
const uniqueLogin = () => `user-${++seq}`;

const EXT_A = [{ id: 'extensions-a' }];
const EXT_B = [{ id: 'extensions-b' }];

function props(overrides = {}) {
  return {
    collab: { extensions: EXT_A, docAtJoin: 'Hello', startVersion: 7, draft: null },
    collabMode: true,
    collabSeed: null,
    name: 'MyPage',
    login: uniqueLogin(),
    draft: null,
    setBody: vi.fn(),
    setUnsynced: vi.fn(),
    setSyncedVersion: vi.fn(),
    ...overrides,
  };
}

function mount(initial) {
  return renderHook((p) => useCollabJoin(p), { initialProps: initial });
}

describe('useCollabJoin: joining', () => {
  it('hands the editor the joined document, marks it synced and records the version', () => {
    const p = props();
    mount(p);
    expect(p.setBody).toHaveBeenCalledWith('Hello');
    expect(p.setUnsynced).toHaveBeenCalledWith(false);
    expect(p.setSyncedVersion).toHaveBeenCalledWith(7);
  });

  it('a join without a document or a version resets to empty text and no version', () => {
    const p = props({ collab: { extensions: EXT_A } });
    mount(p);
    expect(p.setBody).toHaveBeenCalledWith('');
    expect(p.setSyncedVersion).toHaveBeenCalledWith(null);
  });

  it('does nothing outside collaborative mode', () => {
    const p = props({ collabMode: false, collab: { extensions: EXT_A, docAtJoin: 'x', draft: { unpublished: true } } });
    const { result } = mount(p);
    expect(p.setBody).not.toHaveBeenCalled();
    expect(result.current.collabDraft).toBeNull();
    expect(result.current.draftBannerOpen).toBe(false);
  });

  it('does nothing before the session has extensions', () => {
    const p = props({ collab: { extensions: null, docAtJoin: 'x' } });
    mount(p);
    expect(p.setBody).not.toHaveBeenCalled();
  });

  it('joins once per set of extensions: a re-render does not re-apply, a rejoin does', () => {
    const p = props();
    const { rerender } = mount(p);
    rerender({ ...p });
    expect(p.setBody).toHaveBeenCalledTimes(1);

    rerender({ ...p, collab: { extensions: EXT_B, docAtJoin: 'Rejoined', startVersion: 9, draft: null } });
    expect(p.setBody).toHaveBeenCalledTimes(2);
    expect(p.setBody).toHaveBeenLastCalledWith('Rejoined');
    expect(p.setSyncedVersion).toHaveBeenLastCalledWith(9);
  });

  it('joins when collaborative mode is switched on after the extensions arrived', () => {
    const p = props({ collabMode: false });
    const { rerender } = mount(p);
    expect(p.setBody).not.toHaveBeenCalled();
    rerender({ ...p, collabMode: true });
    expect(p.setBody).toHaveBeenCalledWith('Hello');
  });
});

describe('useCollabJoin: the draft banner', () => {
  it('opens on the first join that reports unpublished changes and dismisses', () => {
    const p = props({ collab: { extensions: EXT_A, docAtJoin: 'x', draft: { unpublished: true } } });
    const { result } = mount(p);
    expect(result.current.draftBannerOpen).toBe(true);
    expect(result.current.collabDraft).toEqual({ unpublished: true });
    act(() => result.current.dismissDraftBanner());
    expect(result.current.draftBannerOpen).toBe(false);
  });

  it('stays closed when the join reports no unpublished changes', () => {
    const { result } = mount(props({ collab: { extensions: EXT_A, docAtJoin: 'x', draft: { unpublished: false } } }));
    expect(result.current.draftBannerOpen).toBe(false);
  });

  it('closes itself once the changes are published', () => {
    const p = props({ collab: { extensions: EXT_A, docAtJoin: 'x', draft: { unpublished: true } } });
    const { result, rerender } = mount(p);
    expect(result.current.draftBannerOpen).toBe(true);
    rerender({ ...p, collab: { ...p.collab, draft: { unpublished: false } } });
    expect(result.current.draftBannerOpen).toBe(false);
  });

  it('stays open while the changes remain unpublished', () => {
    const p = props({ collab: { extensions: EXT_A, docAtJoin: 'x', draft: { unpublished: true } } });
    const { result, rerender } = mount(p);
    rerender({ ...p, collab: { ...p.collab, draft: { unpublished: true } } });
    expect(result.current.draftBannerOpen).toBe(true);
  });

  it('a rejoin does not reopen a dismissed banner', () => {
    const p = props({ collab: { extensions: EXT_A, docAtJoin: 'x', draft: { unpublished: true } } });
    const { result, rerender } = mount(p);
    act(() => result.current.dismissDraftBanner());
    rerender({ ...p, collab: { extensions: EXT_B, docAtJoin: 'x', draft: { unpublished: true } } });
    expect(result.current.draftBannerOpen).toBe(false);
  });
});

describe('useCollabJoin: a restore into a session others are editing', () => {
  const seed = (over = {}) => ({ name: 'MyPage', initialText: 'Restored\r\ntext', restoreFrom: 'v3', ...over });

  it('offers the restored text, line endings normalised, when it differs from the joined session', () => {
    const { result } = mount(props({ collabSeed: seed() }));
    expect(result.current.restoreOffer).toEqual({ text: 'Restored\ntext', from: 'v3' });
  });

  it('a lone carriage return is a line ending too', () => {
    const { result } = mount(props({ collabSeed: seed({ initialText: 'a\rb' }) }));
    expect(result.current.restoreOffer.text).toBe('a\nb');
  });

  it('is no offer when the session text is the same, trailing newlines aside', () => {
    const { result } = mount(props({
      collab: { extensions: EXT_A, docAtJoin: 'Restored\ntext\n\n', startVersion: 1 },
      collabSeed: seed(),
    }));
    expect(result.current.restoreOffer).toBeNull();
  });

  it('is no offer for another page, or for a seed that is not a restore, or a missing seed text', () => {
    expect(mount(props({ collabSeed: seed({ name: 'Other' }) })).result.current.restoreOffer).toBeNull();
    expect(mount(props({ collabSeed: seed({ restoreFrom: null }) })).result.current.restoreOffer).toBeNull();
    // missing text counts as empty, which differs from "Hello"
    expect(mount(props({ collabSeed: seed({ initialText: undefined }) })).result.current.restoreOffer)
      .toEqual({ text: '', from: 'v3' });
  });

  it('dismisses, and a rejoin does not offer it again', () => {
    const p = props({ collabSeed: seed() });
    const { result, rerender } = mount(p);
    act(() => result.current.dismissRestoreOffer());
    expect(result.current.restoreOffer).toBeNull();
    rerender({ ...p, collab: { extensions: EXT_B, docAtJoin: 'Other text', startVersion: 2 } });
    expect(result.current.restoreOffer).toBeNull();
  });

  it('a rejoin withdraws an offer that was still showing', () => {
    const p = props({ collabSeed: seed() });
    const { result, rerender } = mount(p);
    expect(result.current.restoreOffer).not.toBeNull();
    rerender({ ...p, collab: { extensions: EXT_B, docAtJoin: 'Hello', startVersion: 2 } });
    expect(result.current.restoreOffer).toBeNull();
  });
});

describe('useCollabJoin: an offline draft', () => {
  const offline = (over = {}) => ({ content: '---\ntitle: T\n---\nOffline text\r\nmore', savedAt: 1700, ...over });

  it('offers a differing draft, without its frontmatter, with its key', () => {
    const p = props({ draft: offline() });
    const { result } = mount(p);
    expect(result.current.offlineDraftOffer).toEqual({
      text: 'Offline text\nmore',
      savedAt: 1700,
      key: `${p.login}\u0000MyPage\u00001700`,
    });
  });

  it('is no offer when the draft equals the joined text', () => {
    const { result } = mount(props({
      collab: { extensions: EXT_A, docAtJoin: 'Offline text\nmore', startVersion: 1 },
      draft: offline(),
    }));
    expect(result.current.offlineDraftOffer).toBeNull();
  });

  it('is no offer without a draft or with an empty one', () => {
    expect(mount(props({ draft: null })).result.current.offlineDraftOffer).toBeNull();
    expect(mount(props({ draft: offline({ content: '' }) })).result.current.offlineDraftOffer).toBeNull();
  });

  it('dismisses', () => {
    const { result } = mount(props({ draft: offline() }));
    act(() => result.current.dismissOfflineDraftOffer());
    expect(result.current.offlineDraftOffer).toBeNull();
  });

  it('is offered once per stored draft in this tab, but a newer draft is offered again', () => {
    const login = uniqueLogin();
    const first = mount(props({ login, draft: offline() }));
    expect(first.result.current.offlineDraftOffer).not.toBeNull();
    first.unmount();

    // the same stored draft, entering collab mode again: already offered
    const again = mount(props({ login, draft: offline() }));
    expect(again.result.current.offlineDraftOffer).toBeNull();
    again.unmount();

    const newer = mount(props({ login, draft: offline({ savedAt: 1800 }) }));
    expect(newer.result.current.offlineDraftOffer).not.toBeNull();
  });

  it('a draft that matched the text is not used up: it was never shown', () => {
    const login = uniqueLogin();
    const same = mount(props({
      login,
      collab: { extensions: EXT_A, docAtJoin: 'Offline text\nmore', startVersion: 1 },
      draft: offline(),
    }));
    expect(same.result.current.offlineDraftOffer).toBeNull();
    same.unmount();
    const later = mount(props({ login, draft: offline() }));
    expect(later.result.current.offlineDraftOffer).not.toBeNull();
  });

  it('a rejoin does not offer the draft', () => {
    const p = props({ draft: null });
    const { result, rerender } = mount(p);
    rerender({ ...p, draft: offline(), collab: { extensions: EXT_B, docAtJoin: 'Hello', startVersion: 2 } });
    expect(result.current.offlineDraftOffer).toBeNull();
  });

  it('keys drafts by login and page', () => {
    const login = uniqueLogin();
    mount(props({ login, draft: offline() })).unmount();
    const otherPage = mount(props({ login, name: 'OtherPage', draft: offline() }));
    expect(otherPage.result.current.offlineDraftOffer).not.toBeNull();
  });
});

describe('useCollabJoin: stable handlers', () => {
  it('dismissers keep their identity across renders', () => {
    const p = props();
    const { result, rerender } = mount(p);
    const first = result.current;
    rerender({ ...p });
    expect(result.current.dismissRestoreOffer).toBe(first.dismissRestoreOffer);
    expect(result.current.dismissOfflineDraftOffer).toBe(first.dismissOfflineDraftOffer);
    expect(result.current.dismissDraftBanner).toBe(first.dismissDraftBanner);
  });
});
