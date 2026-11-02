import { describe, it, expect, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useAttachmentUpload } from './useAttachmentUpload';

function harness({ upload, existingNames = [] } = {}) {
  let body = 'Hello world';
  const updateBody = (fn) => { body = fn(body); };
  const toast = { error: vi.fn() };
  const up = upload || vi.fn(async () => ({ success: true }));
  const { result } = renderHook(() => useAttachmentUpload({ existingNames, upload: up, updateBody, toast }));
  return { call: (...a) => result.current(...a), run: (files, pos, opts) => act(() => result.current(files, pos, opts)), body: () => body, toast, upload: up };
}

const img = (name) => new File(['x'], name, { type: 'image/png' });
const doc = (name) => new File(['x'], name, { type: 'application/pdf' });

describe('useAttachmentUpload', () => {
  it('names pasted images by timestamp and replaces the placeholder with image markup', async () => {
    const h = harness();
    await h.run([img('image.png')], 5, { pasted: true });
    const [[, name]] = h.upload.mock.calls;
    expect(name).toMatch(/^pasted-\d{8}-\d{6}\.png$/);
    expect(h.body()).toBe(`Hello![${name.replace('.png', '')}](${name}) world`);
  });

  it('keeps and normalises dropped file names; non-images get link markup', async () => {
    const h = harness();
    await h.run([doc('Q3 report.pdf')], 0, { pasted: false });
    expect(h.upload.mock.calls[0][1]).toBe('Q3-report.pdf');
    expect(h.body()).toBe('[Q3-report](Q3-report.pdf)Hello world');
  });

  it('avoids collisions with existing attachments and within the batch', async () => {
    const h = harness({ existingNames: ['shot.png'] });
    await h.run([img('shot.png'), img('shot.png')], 0, { pasted: false });
    expect(h.upload.mock.calls.map((c) => c[1])).toEqual(['shot-2.png', 'shot-3.png']);
  });

  it('a failed upload removes only its placeholder and toasts; later files still upload (Review Focus #2)', async () => {
    const upload = vi.fn()
      .mockResolvedValueOnce({})
      .mockRejectedValueOnce(new Error('Files of type .exe may not be uploaded'))
      .mockResolvedValueOnce({});
    const h = harness({ upload });
    await h.run([doc('a.pdf'), doc('b.exe'), doc('c.pdf')], 0, { pasted: false });
    expect(upload).toHaveBeenCalledTimes(3);
    expect(h.body()).toContain('[a](a.pdf)');
    expect(h.body()).toContain('[c](c.pdf)');
    expect(h.body()).not.toContain('Uploading b.exe');
    expect(h.toast.error).toHaveBeenCalledWith(expect.stringContaining('b.exe'));
  });

  it('skips a file with no usable name and says so', async () => {
    const h = harness();
    await h.run([doc('README')], 0, { pasted: false });
    expect(h.upload).not.toHaveBeenCalled();
    expect(h.toast.error).toHaveBeenCalledWith(expect.stringContaining('README'));
  });

  it('reserves names of in-flight uploads so overlapping pastes stay distinct', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 8, 30, 14, 12, 3));
    try {
      let release;
      const upload = vi.fn()
        .mockImplementationOnce(() => new Promise((r) => { release = r; }))
        .mockResolvedValue({});
      const h = harness({ upload });
      let first;
      first = h.call([img('image.png')], 0, { pasted: true });
      await h.run([img('image.png')], 0, { pasted: true });
      release({});
      await first;
      const names = upload.mock.calls.map((c) => c[1]);
      expect(names).toHaveLength(2);
      expect(names[0]).not.toBe(names[1]);
    } finally {
      vi.useRealTimers();
    }
  });

  it('uploads a File whose own name matches the chosen name (M3: image.jpeg pasted as .jpg)', async () => {
    const h = harness();
    await h.run([new File(['x'], 'image.jpeg', { type: 'image/jpeg' })], 0, { pasted: true });
    const [[file, name]] = h.upload.mock.calls;
    expect(name).toMatch(/\.jpg$/);
    expect(file.name).toBe(name);
    expect(file.type).toBe('image/jpeg');
  });

  it('defer: inserts placeholders at once at the given position and uploads only when started (M1)', async () => {
    const h = harness();
    let handle;
    await act(async () => { handle = await h.call([doc('a.pdf')], 5, { defer: true }); });
    expect(h.upload).not.toHaveBeenCalled();
    expect(h.body()).toBe('Hello![Uploading a.pdf…]() world'.replace('![Uploading a.pdf…]()', '![Uploading a.pdf…]()'));
    await act(async () => { await handle.start(); });
    expect(h.upload).toHaveBeenCalledTimes(1);
    expect(h.body()).toBe('Hello[a](a.pdf) world');
  });

  it('defer: cancel removes the placeholders without uploading (M1)', async () => {
    const h = harness();
    let handle;
    await act(async () => { handle = await h.call([doc('a.pdf')], 5, { defer: true }); });
    handle.cancel();
    expect(h.body()).toBe('Hello world');
    expect(h.upload).not.toHaveBeenCalled();
  });
});

describe('useAttachmentUpload with an editor adapter', () => {
  function editorHarness(editor) {
    let body = 'Hello world';
    const updateBody = vi.fn((fn) => { body = fn(body); });
    const toast = { error: vi.fn() };
    const upload = vi.fn(async () => ({ success: true }));
    const { result } = renderHook(() => useAttachmentUpload({ existingNames: [], upload, updateBody, toast, editor }));
    return { run: (files, pos, opts) => act(() => result.current(files, pos, opts)), body: () => body, updateBody, upload, toast };
  }

  it('routes placeholder insert and replacement through the editor, not updateBody', async () => {
    const editor = { insertText: vi.fn(() => true), replaceText: vi.fn(() => true) };
    const h = editorHarness(editor);
    await h.run([doc('a.pdf')], 5, { pasted: false });
    expect(editor.insertText).toHaveBeenCalledWith(5, '![Uploading a.pdf…]()');
    expect(editor.replaceText).toHaveBeenCalledWith('![Uploading a.pdf…]()', '[a](a.pdf)');
    expect(h.updateBody).not.toHaveBeenCalled();
  });

  it('a failed upload removes the placeholder through the editor', async () => {
    const editor = { insertText: vi.fn(() => true), replaceText: vi.fn(() => true) };
    const h = editorHarness(editor);
    h.upload.mockRejectedValueOnce(new Error('nope'));
    await h.run([doc('a.pdf')], 0, { pasted: false });
    expect(editor.replaceText).toHaveBeenCalledWith('![Uploading a.pdf…]()', '');
    expect(h.toast.error).toHaveBeenCalled();
  });

  it('falls back to updateBody when the editor has no view (returns false)', async () => {
    const editor = { insertText: vi.fn(() => false), replaceText: vi.fn(() => false) };
    const h = editorHarness(editor);
    await h.run([doc('a.pdf')], 5, { pasted: false });
    expect(h.body()).toBe('Hello[a](a.pdf) world');
  });

  it('cancelling a deferred upload removes placeholders through the editor', async () => {
    const editor = { insertText: vi.fn(() => true), replaceText: vi.fn(() => true) };
    const h = editorHarness(editor);
    let handle;
    await act(async () => { handle = await h.run([doc('a.pdf')], 0, { defer: true, pasted: false }); });
    expect(h.upload).not.toHaveBeenCalled();
    act(() => handle.cancel());
    expect(editor.replaceText).toHaveBeenCalledWith('![Uploading a.pdf…]()', '');
  });
});

describe('useAttachmentUpload placed images (paste with embedded data: images)', () => {
  const T1 = 'WKIMG0';
  const T2 = 'WKIMG1';
  const text = `intro\n\n${T1}\n\nmiddle\n\n${T2}\n\nend`;
  const images = [{ file: img('a.png'), token: T1 }, { file: new File(['y'], 'b.jpg', { type: 'image/jpeg' }), token: T2 }];

  function placedHarness({ upload, existingNames = [] } = {}) {
    let body = '';
    const updateBody = (fn) => { body = fn(body); };
    const toast = { error: vi.fn() };
    const up = upload || vi.fn(async () => ({ success: true }));
    const { result } = renderHook(() => useAttachmentUpload({ existingNames, upload: up, updateBody, toast }));
    return { place: (...a) => result.current.uploadPlaced(...a), setBody: (b) => { body = b; }, body: () => body, toast, upload: up };
  }

  it('swaps each token for its placeholder in position, with pasted-image-<stamp>-<n> names', () => {
    const h = placedHarness();
    const { text: placed } = h.place(images, text);
    const names = [...placed.matchAll(/!\[Uploading (pasted-image-\d{8}-\d{6}-\d\.\w+)…\]\(\)/g)].map((m) => m[1]);
    expect(names).toHaveLength(2);
    expect(names[0]).toMatch(/-1\.png$/);
    expect(names[1]).toMatch(/-2\.jpg$/);
    expect(placed).toBe(`intro\n\n![Uploading ${names[0]}…]()\n\nmiddle\n\n![Uploading ${names[1]}…]()\n\nend`);
    expect(h.upload).not.toHaveBeenCalled(); // nothing uploads until start()
  });

  it('start() uploads in order and each placeholder becomes its attachment markup', async () => {
    const h = placedHarness();
    const { text: placed, start } = h.place(images, text);
    h.setBody(placed);
    await act(() => start());
    const names = h.upload.mock.calls.map((c) => c[1]);
    expect(names).toHaveLength(2);
    expect(h.upload.mock.calls[0][0]).toBeInstanceOf(File);
    expect(h.body()).toBe(`intro\n\n![${names[0].replace('.png', '')}](${names[0]})\n\nmiddle\n\n![${names[1].replace('.jpg', '')}](${names[1]})\n\nend`);
  });

  it('a failed upload removes only its own placeholder and toasts; the other still lands', async () => {
    const upload = vi.fn().mockRejectedValueOnce(new Error('too big')).mockResolvedValueOnce({});
    const h = placedHarness({ upload });
    const { text: placed, start } = h.place(images, text);
    h.setBody(placed);
    await act(() => start());
    expect(h.toast.error).toHaveBeenCalledTimes(1);
    expect(h.toast.error.mock.calls[0][0]).toMatch(/failed: too big/);
    const second = upload.mock.calls[1][1];
    expect(h.body()).toBe(`intro\n\n\n\nmiddle\n\n![${second.replace('.jpg', '')}](${second})\n\nend`);
  });

  it('cancel() (deferred paste on an unsaved page) removes the placeholders without uploading', () => {
    const h = placedHarness();
    const { text: placed, cancel } = h.place(images, text);
    h.setBody(placed);
    cancel();
    expect(h.body()).toBe('intro\n\n\n\nmiddle\n\n\n\nend');
    expect(h.upload).not.toHaveBeenCalled();
  });

  it('avoids names already on the page or claimed by an earlier paste', () => {
    const h = placedHarness();
    const first = h.place([images[0]], T1).text;
    const second = h.place([images[0]], T1).text;
    expect(second).not.toBe(first);
  });
});
