import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, fireEvent } from '@testing-library/react';
import { useEditorDrop } from './useEditorDrop';

let container;

beforeEach(() => {
  container = document.createElement('div');
  document.body.appendChild(container);
});

afterEach(() => {
  document.body.removeChild(container);
});

describe('useEditorDrop', () => {
  it('does nothing when the container ref has no current element', () => {
    const ref = { current: null };
    const onInsert = vi.fn();
    expect(() => renderHook(() => useEditorDrop(ref, onInsert, () => 0))).not.toThrow();
  });

  it('prevents default and sets copy dropEffect on dragover when text/plain is offered', () => {
    const ref = { current: container };
    renderHook(() => useEditorDrop(ref, vi.fn(), () => 0));

    let capturedEvent;
    container.addEventListener('dragover', (e) => { capturedEvent = e; });

    const notCancelled = fireEvent.dragOver(container, { dataTransfer: { types: ['text/plain'] } });

    expect(notCancelled).toBe(false); // preventDefault() was called
    expect(capturedEvent.dataTransfer.dropEffect).toBe('copy');
  });

  it('leaves dragover alone when text/plain is not offered', () => {
    const ref = { current: container };
    renderHook(() => useEditorDrop(ref, vi.fn(), () => 0));

    let capturedEvent;
    container.addEventListener('dragover', (e) => { capturedEvent = e; });

    const notCancelled = fireEvent.dragOver(container, { dataTransfer: { types: ['Files'] } });

    expect(notCancelled).toBe(true);
    expect(capturedEvent.dataTransfer.dropEffect).not.toBe('copy');
  });

  it('inserts dropped text at the caret offset from getOffset on drop', () => {
    const onInsert = vi.fn();
    const ref = { current: container };
    renderHook(() => useEditorDrop(ref, onInsert, () => 42));

    fireEvent.drop(container, { dataTransfer: { getData: () => 'hello world' } });

    expect(onInsert).toHaveBeenCalledWith('hello world', 42);
  });

  it('defaults the insertion position to 0 when getOffset is not provided', () => {
    const onInsert = vi.fn();
    const ref = { current: container };
    renderHook(() => useEditorDrop(ref, onInsert));

    fireEvent.drop(container, { dataTransfer: { getData: () => 'text' } });

    expect(onInsert).toHaveBeenCalledWith('text', 0);
  });

  it('defaults the insertion position to 0 when getOffset returns null/undefined', () => {
    const onInsert = vi.fn();
    const ref = { current: container };
    renderHook(() => useEditorDrop(ref, onInsert, () => undefined));

    fireEvent.drop(container, { dataTransfer: { getData: () => 'text' } });

    expect(onInsert).toHaveBeenCalledWith('text', 0);
  });

  it('does not call onInsert (or preventDefault) on drop when there is no plain text', () => {
    const onInsert = vi.fn();
    const ref = { current: container };
    renderHook(() => useEditorDrop(ref, onInsert, () => 5));

    const notCancelled = fireEvent.drop(container, { dataTransfer: { getData: () => '' } });

    expect(onInsert).not.toHaveBeenCalled();
    expect(notCancelled).toBe(true);
  });

  it('removes its listeners on unmount', () => {
    const onInsert = vi.fn();
    const ref = { current: container };
    const { unmount } = renderHook(() => useEditorDrop(ref, onInsert, () => 0));

    unmount();

    fireEvent.drop(container, { dataTransfer: { getData: () => 'text after unmount' } });
    expect(onInsert).not.toHaveBeenCalled();
  });
});
