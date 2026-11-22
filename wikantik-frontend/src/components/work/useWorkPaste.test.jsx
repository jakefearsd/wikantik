import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

vi.mock('../../api/work', () => ({ uploadAttachment: vi.fn() }));
import { uploadAttachment } from '../../api/work';
import { useWorkPaste } from './useWorkPaste';
import { rememberLimits, resetLimits } from './attachmentErrors';

const png = () => new File(['x'], 'image.png', { type: 'image/png' });
const clip = (files, text = '') => ({ files, getData: () => text });
const pasteEvent = (files, text, extra = {}) => ({
  clipboardData: clip(files, text), preventDefault: vi.fn(), target: { selectionStart: 5, value: 'Hello world' }, ...extra,
});

function harness(attach, commentId) {
  let text = 'Hello world';
  const setText = (fn) => { text = fn(text); };
  const { result } = renderHook(() => useWorkPaste({ attach, commentId, setText }));
  return { result, text: () => text };
}
const toast = () => ({ error: vi.fn(), success: vi.fn(), info: vi.fn() });

beforeEach(() => { uploadAttachment.mockReset(); resetLimits(); });

describe('useWorkPaste', () => {
  it('aPastedImageUploadsAndItsMarkdownReplacesThePlaceholder', async () => {
    let finish;
    uploadAttachment.mockReturnValue(new Promise((r) => { finish = r; }));
    const onUploaded = vi.fn();
    const h = harness({ itemKey: 'PLAT-1', toast: toast(), onUploaded });
    const e = pasteEvent([png()]);
    let done;
    act(() => { done = h.result.current.onPaste(e); });
    expect(e.preventDefault).toHaveBeenCalled();
    expect(h.text()).toContain('Uploading');
    expect(h.text()).not.toContain('work-attachment:');
    await act(async () => { finish({ id: 4, markdown: '![shot](work-attachment:4)' }); await done; });
    expect(h.text()).toBe('Hello![shot](work-attachment:4) world');
    expect(uploadAttachment.mock.calls[0][0]).toBe('PLAT-1');
    expect(uploadAttachment.mock.calls[0][2]).toBeNull();
    expect(onUploaded).toHaveBeenCalled();
  });

  it('aFailedUploadInsertsNothingAndToastsTheError', async () => {
    uploadAttachment.mockRejectedValue(Object.assign(new Error('Not allowed'), { status: 403 }));
    const t = toast();
    const h = harness({ itemKey: 'PLAT-1', toast: t });
    await act(async () => { await h.result.current.onPaste(pasteEvent([png()])); });
    expect(h.text()).toBe('Hello world');
    expect(t.error).toHaveBeenCalledWith(expect.stringMatching(/not allowed/i));
  });

  it('aFileOverTheLimitIsRefusedBeforeSending', async () => {
    const big = new File([new Uint8Array(11 * 1024 * 1024)], 'big.png', { type: 'image/png' });
    const t = toast();
    rememberLimits('PLAT-1', { mayAttach: true, maxFileBytes: 10 * 1024 * 1024, maxItemBytes: 50 * 1024 * 1024, usedBytes: 0 });
    const h = harness({ itemKey: 'PLAT-1', toast: t });
    await act(async () => { await h.result.current.onPaste(pasteEvent([big])); });
    expect(uploadAttachment).not.toHaveBeenCalled();
    expect(t.error).toHaveBeenCalledWith(expect.stringMatching(/larger than 10\.0 MB/));
    expect(h.text()).toBe('Hello world');
  });

  it('withoutAttachAPasteIsLeftAlone', () => {
    const h = harness(null);
    const e = pasteEvent([png()]);
    h.result.current.onPaste(e);
    expect(e.preventDefault).not.toHaveBeenCalled();
    expect(uploadAttachment).not.toHaveBeenCalled();
  });

  it('aPasteIsLeftAloneWhenTheServerSaidTheCallerMayNotAttach', () => {
    rememberLimits('PLAT-1', { mayAttach: false, maxFileBytes: 100, maxItemBytes: 100, usedBytes: 0 });
    const h = harness({ itemKey: 'PLAT-1', toast: toast() });
    const e = pasteEvent([png()]);
    h.result.current.onPaste(e);
    expect(e.preventDefault).not.toHaveBeenCalled();
    expect(uploadAttachment).not.toHaveBeenCalled();
  });

  it('aPasteWithTextOnTheClipboardIsLeftAlone', () => {
    const h = harness({ itemKey: 'PLAT-1', toast: toast() });
    const e = pasteEvent([png()], 'some words');
    h.result.current.onPaste(e);
    expect(e.preventDefault).not.toHaveBeenCalled();
    expect(uploadAttachment).not.toHaveBeenCalled();
  });

  it('aDroppedFileUploadsWithTheCommentId', async () => {
    uploadAttachment.mockResolvedValue({ id: 5, markdown: '[a](work-attachment:5)' });
    const h = harness({ itemKey: 'PLAT-1', toast: toast() }, 9);
    const file = new File(['x'], 'a.pdf', { type: 'application/pdf' });
    const e = { dataTransfer: { files: [file], types: ['Files'] }, preventDefault: vi.fn(), target: { selectionStart: 0, value: '' } };
    await act(async () => { await h.result.current.onDrop(e); });
    expect(e.preventDefault).toHaveBeenCalled();
    expect(uploadAttachment.mock.calls[0][2]).toBe(9);
    expect(h.text()).toBe('[a](work-attachment:5)Hello world');
  });

  it('dragOverIsClaimedOnlyForFiles', () => {
    const h = harness({ itemKey: 'PLAT-1', toast: toast() });
    const files = { dataTransfer: { types: ['Files'] }, preventDefault: vi.fn() };
    const text = { dataTransfer: { types: ['text/plain'] }, preventDefault: vi.fn() };
    h.result.current.onDragOver(files);
    h.result.current.onDragOver(text);
    expect(files.preventDefault).toHaveBeenCalled();
    expect(text.preventDefault).not.toHaveBeenCalled();
  });
});
