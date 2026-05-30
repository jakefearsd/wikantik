import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import EditorToolbar from './EditorToolbar';

describe('EditorToolbar', () => {
  it('renders six formatting buttons', () => {
    render(<EditorToolbar onCommand={vi.fn()} />);

    // Use title attributes — each button has a unique title
    expect(screen.getByTitle(/bold/i)).toBeInTheDocument();
    expect(screen.getByTitle(/italic/i)).toBeInTheDocument();
    expect(screen.getByTitle(/heading/i)).toBeInTheDocument();
    expect(screen.getByTitle(/list/i)).toBeInTheDocument();
    expect(screen.getByTitle(/code/i)).toBeInTheDocument();
    expect(screen.getByTitle(/link/i)).toBeInTheDocument();
  });

  it('each button has an accessible name (aria-label)', () => {
    render(<EditorToolbar onCommand={vi.fn()} />);
    const toolbar = screen.getByRole('toolbar');
    const buttons = toolbar.querySelectorAll('button');
    expect(buttons.length).toBe(6);
    buttons.forEach(btn => {
      expect(btn).toHaveAttribute('aria-label');
      expect(btn.getAttribute('aria-label').length).toBeGreaterThan(0);
    });
  });

  it('clicking Bold calls onCommand("bold")', () => {
    const onCommand = vi.fn();
    render(<EditorToolbar onCommand={onCommand} />);
    fireEvent.mouseDown(screen.getByTitle(/bold/i));
    expect(onCommand).toHaveBeenCalledWith('bold');
  });

  it('clicking Italic calls onCommand("italic")', () => {
    const onCommand = vi.fn();
    render(<EditorToolbar onCommand={onCommand} />);
    fireEvent.mouseDown(screen.getByTitle(/italic/i));
    expect(onCommand).toHaveBeenCalledWith('italic');
  });

  it('clicking Heading calls onCommand("heading")', () => {
    const onCommand = vi.fn();
    render(<EditorToolbar onCommand={onCommand} />);
    fireEvent.mouseDown(screen.getByTitle(/heading/i));
    expect(onCommand).toHaveBeenCalledWith('heading');
  });

  it('clicking List calls onCommand("list")', () => {
    const onCommand = vi.fn();
    render(<EditorToolbar onCommand={onCommand} />);
    fireEvent.mouseDown(screen.getByTitle(/list/i));
    expect(onCommand).toHaveBeenCalledWith('list');
  });

  it('clicking Code calls onCommand("code")', () => {
    const onCommand = vi.fn();
    render(<EditorToolbar onCommand={onCommand} />);
    fireEvent.mouseDown(screen.getByTitle(/code/i));
    expect(onCommand).toHaveBeenCalledWith('code');
  });

  it('clicking Link calls onCommand("link")', () => {
    const onCommand = vi.fn();
    render(<EditorToolbar onCommand={onCommand} />);
    fireEvent.mouseDown(screen.getByTitle(/link/i));
    expect(onCommand).toHaveBeenCalledWith('link');
  });
});
