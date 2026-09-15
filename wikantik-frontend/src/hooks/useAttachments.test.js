import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';

vi.mock('../api/client', () => ({
  api: {
    listAttachments: vi.fn(),
    uploadAttachment: vi.fn(),
    renameAttachment: vi.fn(),
    deleteAttachment: vi.fn(),
  },
}));

import { useAttachments } from './useAttachments';
import { api } from '../api/client';

beforeEach(() => {
  vi.clearAllMocks();
  api.listAttachments.mockResolvedValue({ attachments: [] });
});

describe('useAttachments', () => {
  it('does not call the API when pageName is falsy', () => {
    renderHook(() => useAttachments(undefined));
    expect(api.listAttachments).not.toHaveBeenCalled();
  });

  it('fetches the attachment list on mount and flags image files', async () => {
    api.listAttachments.mockResolvedValue({
      attachments: [
        { fileName: 'photo.PNG' },
        { fileName: 'notes.txt' },
        { fileName: 'noextension' },
      ],
    });

    const { result } = renderHook(() => useAttachments('MyPage'));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(api.listAttachments).toHaveBeenCalledWith('MyPage');
    expect(result.current.list).toEqual([
      { fileName: 'photo.PNG', isImage: true },
      { fileName: 'notes.txt', isImage: false },
      { fileName: 'noextension', isImage: false },
    ]);
    expect(result.current.error).toBe(null);
  });

  it('sets an error message when the list fetch fails', async () => {
    api.listAttachments.mockRejectedValue(new Error('list failed'));
    const { result } = renderHook(() => useAttachments('MyPage'));

    await waitFor(() => expect(result.current.error).toBe('list failed'));
    expect(result.current.list).toEqual([]);
    expect(result.current.loading).toBe(false);
  });

  it('falls back to a default error message when the rejection has none', async () => {
    api.listAttachments.mockRejectedValue({});
    const { result } = renderHook(() => useAttachments('MyPage'));

    await waitFor(() => expect(result.current.error).toBe('Failed to load attachments'));
  });

  it('uploadAttachment calls api.uploadAttachment then reloads the list', async () => {
    api.uploadAttachment.mockResolvedValue({ fileName: 'new.png' });
    const { result } = renderHook(() => useAttachments('MyPage'));
    await waitFor(() => expect(result.current.loading).toBe(false));

    api.listAttachments.mockResolvedValue({ attachments: [{ fileName: 'new.png' }] });

    const file = { name: 'new.png' };
    let uploadResult;
    await act(async () => {
      uploadResult = await result.current.uploadAttachment(file, 'new.png');
    });

    expect(api.uploadAttachment).toHaveBeenCalledWith('MyPage', file, 'new.png');
    expect(uploadResult).toEqual({ fileName: 'new.png' });
    expect(api.listAttachments).toHaveBeenCalledTimes(2);
    await waitFor(() => expect(result.current.list).toEqual([{ fileName: 'new.png', isImage: true }]));
  });

  it('renameAttachment calls api.renameAttachment, reloads, and returns old/new names', async () => {
    api.renameAttachment.mockResolvedValue({ ok: true });
    const { result } = renderHook(() => useAttachments('MyPage'));
    await waitFor(() => expect(result.current.loading).toBe(false));

    let renameResult;
    await act(async () => {
      renameResult = await result.current.renameAttachment('old.png', 'new.png');
    });

    expect(api.renameAttachment).toHaveBeenCalledWith('MyPage', 'old.png', 'new.png');
    expect(renameResult).toEqual({ oldName: 'old.png', newName: 'new.png', data: { ok: true } });
    expect(api.listAttachments).toHaveBeenCalledTimes(2);
  });

  it('deleteAttachment calls api.deleteAttachment then reloads the list', async () => {
    api.deleteAttachment.mockResolvedValue({});
    const { result } = renderHook(() => useAttachments('MyPage'));
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(async () => {
      await result.current.deleteAttachment('old.png');
    });

    expect(api.deleteAttachment).toHaveBeenCalledWith('MyPage', 'old.png');
    expect(api.listAttachments).toHaveBeenCalledTimes(2);
  });

  it('exposes reload as the same fetch function, callable directly', async () => {
    const { result } = renderHook(() => useAttachments('MyPage'));
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(async () => {
      await result.current.reload();
    });

    expect(api.listAttachments).toHaveBeenCalledTimes(2);
  });

  it('refetches when pageName changes', async () => {
    const { rerender } = renderHook(({ page }) => useAttachments(page), {
      initialProps: { page: 'PageA' },
    });
    await waitFor(() => expect(api.listAttachments).toHaveBeenCalledWith('PageA'));

    rerender({ page: 'PageB' });
    await waitFor(() => expect(api.listAttachments).toHaveBeenCalledWith('PageB'));
  });
});
