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
    expect(buttons.length).toBe(9);
    buttons.forEach(btn => {
      expect(btn).toHaveAttribute('aria-label');
      expect(btn.getAttribute('aria-label').length).toBeGreaterThan(0);
    });
  });

  it('the link button face is a glyph, not the shortcut text', () => {
    render(<EditorToolbar onRun={vi.fn()} />);
    const btn = screen.getByTitle('Link (Ctrl+K)');
    expect(btn.textContent).not.toMatch(/Ctrl|K/);
    expect(btn.querySelector('svg')).not.toBeNull();
    expect(btn).toHaveAttribute('aria-label', 'Link (Ctrl+K)');
  });

  it('tooltips spell out the shortcut for this platform', () => {
    render(<EditorToolbar onRun={vi.fn()} />); // jsdom is not a Mac
    expect(screen.getByTitle('Bold (Ctrl+B)')).toBeInTheDocument();
    expect(screen.getByTitle('Italic (Ctrl+I)')).toBeInTheDocument();
    expect(screen.getByTitle('Link (Ctrl+K)')).toBeInTheDocument();
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

  it('the Live toggle reflects the mode and runs toggle-live-preview', () => {
    const onRun = vi.fn();
    const { rerender } = render(<EditorToolbar onRun={onRun} liveMode={false} />);
    const live = screen.getByRole('button', { name: /live preview/i });
    expect(live).toHaveAttribute('aria-pressed', 'false');
    expect(live.textContent).toBe('Live');
    fireEvent.mouseDown(live);
    expect(onRun).toHaveBeenCalledWith('toggle-live-preview');
    rerender(<EditorToolbar onRun={onRun} liveMode />);
    expect(screen.getByRole('button', { name: /live preview/i })).toHaveAttribute('aria-pressed', 'true');
  });
});
