import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useDocumentTitle } from './useDocumentTitle';

beforeEach(() => {
  document.title = 'Main - Wikantik';
});

describe('useDocumentTitle', () => {
  it('sets document.title with the Wikantik suffix on mount', () => {
    renderHook(() => useDocumentTitle('My Page'));
    expect(document.title).toBe('My Page - Wikantik');
  });

  it('updates document.title when the title argument changes', () => {
    const { rerender } = renderHook(({ title }) => useDocumentTitle(title), {
      initialProps: { title: 'Page One' },
    });
    expect(document.title).toBe('Page One - Wikantik');
    rerender({ title: 'Page Two' });
    expect(document.title).toBe('Page Two - Wikantik');
  });

  it('does not set document.title when title is falsy', () => {
    document.title = 'Unchanged - Wikantik';
    renderHook(() => useDocumentTitle(''));
    expect(document.title).toBe('Unchanged - Wikantik');
  });

  it('uses the server-rendered "{title} - Wikantik" format', () => {
    renderHook(() => useDocumentTitle('NoSQL Database Types'));
    expect(document.title).toBe('NoSQL Database Types - Wikantik');
    expect(document.title).not.toContain('Wikantik:');
  });

  it('restores the previous title on unmount', () => {
    document.title = 'Before - Wikantik';
    const { unmount } = renderHook(() => useDocumentTitle('Current Page'));
    expect(document.title).toBe('Current Page - Wikantik');
    unmount();
    expect(document.title).toBe('Before - Wikantik');
  });
});
