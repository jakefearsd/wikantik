import { describe, it, expect } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import EditorStatusBar from './EditorStatusBar';
import { createEditorCursorStore } from '../../utils/editorCursorStore';

describe('EditorStatusBar', () => {
  it('shows words, reading time and cursor; selection count when selecting', () => {
    const store = createEditorCursorStore();
    const body = 'one two three\n\n```\ncode words here\n```\nfour';
    render(<EditorStatusBar body={body} cursorStore={store} />);
    expect(screen.getByTestId('status-words')).toHaveTextContent('4 words');
    expect(screen.getByTestId('editor-status-bar')).toHaveTextContent('1 min read');
    expect(screen.getByTestId('editor-status-bar')).toHaveTextContent('Ln 1, Col 1');
    act(() => store.set({ line: 3, col: 5, selectionText: 'two three' }));
    expect(screen.getByTestId('status-words')).toHaveTextContent('2 of 4 words');
    expect(screen.getByTestId('editor-status-bar')).toHaveTextContent('Ln 3, Col 5');
  });
});
