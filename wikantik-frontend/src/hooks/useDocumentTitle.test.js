import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useDocumentTitle } from './useDocumentTitle';

beforeEach(() => {
  document.title = 'Wikantik: Main';
});

describe('useDocumentTitle', () => {
  it('sets document.title with the Wikantik prefix on mount', () => {
    renderHook(() => useDocumentTitle('My Page'));
    expect(document.title).toBe('Wikantik: My Page');
  });

  it('updates document.title when the title argument changes', () => {
    const { rerender } = renderHook(({ title }) => useDocumentTitle(title), {
      initialProps: { title: 'Page One' },
    });
    expect(document.title).toBe('Wikantik: Page One');
    rerender({ title: 'Page Two' });
    expect(document.title).toBe('Wikantik: Page Two');
  });

  it('does not set document.title when title is falsy', () => {
    document.title = 'Wikantik: Unchanged';
    renderHook(() => useDocumentTitle(''));
    expect(document.title).toBe('Wikantik: Unchanged');
  });

  it('does not double-prefix if title already contains "Wikantik:"', () => {
    // The hook always prepends "Wikantik: " — callers must pass bare names.
    renderHook(() => useDocumentTitle('Mentions'));
    expect(document.title).toBe('Wikantik: Mentions');
    // No double-prefix
    expect(document.title).not.toContain('Wikantik: Wikantik:');
  });

  it('restores the previous title on unmount', () => {
    document.title = 'Wikantik: Before';
    const { unmount } = renderHook(() => useDocumentTitle('Current Page'));
    expect(document.title).toBe('Wikantik: Current Page');
    unmount();
    expect(document.title).toBe('Wikantik: Before');
  });
});
