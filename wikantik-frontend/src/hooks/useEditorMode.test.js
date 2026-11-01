import { describe, it, expect, vi, beforeEach } from 'vitest';
import { StrictMode } from 'react';
import { renderHook, act, waitFor } from '@testing-library/react';

vi.mock('../api/client', () => ({
  api: { getProfile: vi.fn(), updateProfile: vi.fn() },
}));
import { api } from '../api/client';
import { useEditorMode } from './useEditorMode';

const KEY = 'wikantik.editor.mode';
const auth = { authenticated: true };

beforeEach(() => {
  localStorage.clear();
  vi.restoreAllMocks();
  api.getProfile.mockReset().mockResolvedValue({ editorMode: null });
  api.updateProfile.mockReset().mockResolvedValue({});
});

describe('useEditorMode', () => {
  it('defaults to live when nothing is stored and anonymous', () => {
    const set = vi.spyOn(window.localStorage, 'setItem');
    const { result } = renderHook(() => useEditorMode({ authenticated: false }));
    expect(result.current[0]).toBe('live');
    expect(api.getProfile).not.toHaveBeenCalled();
    expect(set).not.toHaveBeenCalled();
  });
  it('uses the stored local choice before the server answers', () => {
    localStorage.setItem(KEY, 'source');
    api.getProfile.mockReturnValue(new Promise(() => {}));
    const { result } = renderHook(() => useEditorMode(auth));
    expect(result.current[0]).toBe('source');
  });
  it('ignores a garbage stored value', () => {
    localStorage.setItem(KEY, 'wysiwyg');
    expect(renderHook(() => useEditorMode()).result.current[0]).toBe('live');
  });
  it('server value wins and is mirrored locally', async () => {
    localStorage.setItem(KEY, 'live');
    api.getProfile.mockResolvedValue({ editorMode: 'source' });
    const { result } = renderHook(() => useEditorMode(auth));
    await waitFor(() => expect(result.current[0]).toBe('source'));
    expect(localStorage.getItem(KEY)).toBe('source');
  });
  it('server value applies under StrictMode', async () => {
    api.getProfile.mockResolvedValue({ editorMode: 'source' });
    const { result } = renderHook(() => useEditorMode(auth), { wrapper: StrictMode });
    await waitFor(() => expect(result.current[0]).toBe('source'));
    expect(api.getProfile).toHaveBeenCalledTimes(1);
  });
  it('server null + explicit local choice migrates it once', async () => {
    localStorage.setItem(KEY, 'source');
    renderHook(() => useEditorMode(auth));
    await waitFor(() => expect(api.updateProfile).toHaveBeenCalledTimes(1));
    expect(api.updateProfile).toHaveBeenCalledWith({ editorMode: 'source' });
  });
  it('server null and nothing local writes nothing', async () => {
    const set = vi.spyOn(window.localStorage, 'setItem');
    const { result } = renderHook(() => useEditorMode(auth));
    await waitFor(() => expect(api.getProfile).toHaveBeenCalled());
    await act(async () => {});
    expect(result.current[0]).toBe('live');
    expect(api.updateProfile).not.toHaveBeenCalled();
    expect(set).not.toHaveBeenCalled();
  });
  it('toggle while authenticated PUTs the new mode and persists locally', async () => {
    const { result } = renderHook(() => useEditorMode(auth));
    await act(async () => {});
    act(() => result.current[1]());
    expect(result.current[0]).toBe('source');
    expect(api.updateProfile).toHaveBeenCalledWith({ editorMode: 'source' });
    expect(localStorage.getItem(KEY)).toBe('source');
  });
  it('toggle while anonymous does not PUT', () => {
    const { result } = renderHook(() => useEditorMode({ authenticated: false }));
    act(() => result.current[1]());
    expect(result.current[0]).toBe('source');
    expect(api.updateProfile).not.toHaveBeenCalled();
  });
  it('failed PUT warns and keeps the toggled mode', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.updateProfile.mockRejectedValue(new Error('boom'));
    const { result } = renderHook(() => useEditorMode(auth));
    await act(async () => {});
    act(() => result.current[1]());
    await waitFor(() => expect(warn).toHaveBeenCalled());
    expect(result.current[0]).toBe('source');
  });
  it('profile fetch failure falls back with a warning', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.getProfile.mockRejectedValue(new Error('down'));
    localStorage.setItem(KEY, 'source');
    const { result } = renderHook(() => useEditorMode(auth));
    await waitFor(() => expect(warn).toHaveBeenCalled());
    expect(result.current[0]).toBe('source');
  });
  it('a server value arriving after a toggle does not undo it', async () => {
    let resolve;
    api.getProfile.mockReturnValue(new Promise((r) => { resolve = r; }));
    const { result } = renderHook(() => useEditorMode(auth));
    act(() => result.current[1]()); // live -> source
    expect(result.current[0]).toBe('source');
    await act(async () => { resolve({ editorMode: 'live' }); });
    expect(result.current[0]).toBe('source');
  });
  it('persist=false never writes or PUTs', async () => {
    const set = vi.spyOn(window.localStorage, 'setItem');
    const { result } = renderHook(() => useEditorMode({ authenticated: true, persist: false }));
    act(() => result.current[1]());
    await act(async () => {});
    expect(result.current[0]).toBe('source');
    expect(set).not.toHaveBeenCalled();
    expect(api.updateProfile).not.toHaveBeenCalled();
    expect(api.getProfile).not.toHaveBeenCalled();
  });
  it('still works (and warns) when storage throws', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.spyOn(window.localStorage, 'getItem').mockImplementation(() => { throw new Error('denied'); });
    vi.spyOn(window.localStorage, 'setItem').mockImplementation(() => { throw new Error('denied'); });
    const { result } = renderHook(() => useEditorMode());
    act(() => result.current[1]());
    expect(result.current[0]).toBe('source');
    const msgs = warn.mock.calls.map((c) => c[0]);
    expect(msgs.some((m) => m.includes('read'))).toBe(true);
    expect(msgs.some((m) => m.includes('save'))).toBe(true);
  });
  it('under StrictMode a toggle writes storage exactly once', () => {
    const set = vi.spyOn(window.localStorage, 'setItem');
    const { result } = renderHook(() => useEditorMode(), { wrapper: StrictMode });
    act(() => result.current[1]());
    expect(result.current[0]).toBe('source');
    expect(set).toHaveBeenCalledTimes(1);
  });
  it('persist flipping to false before the profile resolves neither PUTs nor mirrors', async () => {
    let resolve;
    api.getProfile.mockReturnValue(new Promise((r) => { resolve = r; }));
    localStorage.setItem(KEY, 'source');
    const set = vi.spyOn(window.localStorage, 'setItem');
    const { rerender } = renderHook(({ persist }) => useEditorMode({ authenticated: true, persist }), { initialProps: { persist: true } });
    rerender({ persist: false });
    await act(async () => { resolve({ editorMode: null }); });
    expect(api.updateProfile).not.toHaveBeenCalled();
    expect(set).not.toHaveBeenCalled();
  });
  it('persist flipping to false before a server value arrives does not mirror it', async () => {
    let resolve;
    api.getProfile.mockReturnValue(new Promise((r) => { resolve = r; }));
    const set = vi.spyOn(window.localStorage, 'setItem');
    const { rerender } = renderHook(({ persist }) => useEditorMode({ authenticated: true, persist }), { initialProps: { persist: true } });
    rerender({ persist: false });
    await act(async () => { resolve({ editorMode: 'source' }); });
    expect(set).not.toHaveBeenCalled();
  });
  it('authenticated flipping false to true fetches exactly once', async () => {
    const { rerender } = renderHook(({ authenticated }) => useEditorMode({ authenticated }), { initialProps: { authenticated: false } });
    expect(api.getProfile).not.toHaveBeenCalled();
    rerender({ authenticated: true });
    await act(async () => {});
    rerender({ authenticated: true });
    expect(api.getProfile).toHaveBeenCalledTimes(1);
  });
});
