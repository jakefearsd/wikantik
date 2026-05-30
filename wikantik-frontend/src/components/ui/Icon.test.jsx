// Icon.test.jsx
import { render } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import Icon from './Icon';

describe('Icon', () => {
  it('renders an SVG for a known icon name', () => {
    const { container } = render(<Icon name="edit" />);
    const svg = container.querySelector('svg');
    expect(svg).toBeInTheDocument();
  });

  it('applies the size prop to width and height', () => {
    const { container } = render(<Icon name="edit" size={24} />);
    const svg = container.querySelector('svg');
    expect(svg).toHaveAttribute('width', '24');
    expect(svg).toHaveAttribute('height', '24');
  });

  it('defaults to size 16', () => {
    const { container } = render(<Icon name="edit" />);
    const svg = container.querySelector('svg');
    expect(svg).toHaveAttribute('width', '16');
    expect(svg).toHaveAttribute('height', '16');
  });

  it('sets role and aria-label when title is provided', () => {
    const { container } = render(<Icon name="edit" title="Edit this page" />);
    const svg = container.querySelector('svg');
    expect(svg).toHaveAttribute('role', 'img');
    expect(svg).toHaveAttribute('aria-label', 'Edit this page');
  });

  it('sets aria-hidden="true" when title is not provided', () => {
    const { container } = render(<Icon name="edit" />);
    const svg = container.querySelector('svg');
    expect(svg).toHaveAttribute('aria-hidden', 'true');
  });

  it('applies custom className', () => {
    const { container } = render(<Icon name="edit" className="custom-icon" />);
    const svg = container.querySelector('svg');
    expect(svg).toHaveClass('custom-icon');
  });

  it('returns null for unknown icon name', () => {
    const { container } = render(<Icon name="nonexistent" />);
    expect(container.firstChild).toBeNull();
  });

  it('logs a warning for unknown icon name', () => {
    const warnSpy = vi.spyOn(console, 'warn');
    render(<Icon name="unknownIcon" />);
    expect(warnSpy).toHaveBeenCalledWith('Icon: unknown icon name "unknownIcon"');
    warnSpy.mockRestore();
  });

  it('renders all known icon names', () => {
    const names = ['edit', 'trash', 'comment', 'search', 'sun', 'moon', 'copy', 'link', 'chevron', 'close', 'check', 'warning', 'more'];
    names.forEach((name) => {
      const { container } = render(<Icon name={name} />);
      const svg = container.querySelector('svg');
      expect(svg).toBeInTheDocument();
    });
  });
});
