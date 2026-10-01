import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import EditorToolbar from './EditorToolbar';

describe('EditorToolbar', () => {
  it('renders all formatting buttons', () => {
    render(<EditorToolbar onRun={vi.fn()} />);
    expect(screen.getByTitle(/bold/i)).toBeInTheDocument();
    expect(screen.getByTitle(/italic/i)).toBeInTheDocument();
    expect(screen.getByTitle(/heading/i)).toBeInTheDocument();
    expect(screen.getByTitle(/list/i)).toBeInTheDocument();
    expect(screen.getByTitle(/inline code/i)).toBeInTheDocument();
    expect(screen.getByTitle(/code block/i)).toBeInTheDocument();
    expect(screen.getByTitle(/table/i)).toBeInTheDocument();
    expect(screen.getByTitle(/link/i)).toBeInTheDocument();
  });

  it('each button has an accessible name (aria-label)', () => {
    render(<EditorToolbar onRun={vi.fn()} />);
    const buttons = screen.getByRole('toolbar').querySelectorAll('button');
    expect(buttons.length).toBe(8);
    buttons.forEach(btn => {
      expect(btn).toHaveAttribute('aria-label');
      expect(btn.getAttribute('aria-label').length).toBeGreaterThan(0);
    });
  });

  it.each([
    [/bold/i, 'format-bold'],
    [/italic/i, 'format-italic'],
    [/heading/i, 'heading-2'],
    [/list/i, 'format-list'],
    [/inline code/i, 'format-code'],
    [/code block/i, 'code-block'],
    [/table/i, 'insert-table'],
    [/link/i, 'insert-link'],
  ])('mousedown on %s runs %s', (title, id) => {
    const onRun = vi.fn();
    render(<EditorToolbar onRun={onRun} />);
    fireEvent.mouseDown(screen.getByTitle(title));
    expect(onRun).toHaveBeenCalledWith(id);
  });
});
