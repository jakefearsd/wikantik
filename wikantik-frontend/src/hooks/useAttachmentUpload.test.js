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
});
