// Chip.test.jsx
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import Chip from './Chip';

describe('Chip', () => {
  it('renders label prop', () => {
    render(<Chip label="React" />);
    expect(screen.getByText('React')).toBeInTheDocument();
  });

  it('renders children when provided', () => {
    render(<Chip>Vue</Chip>);
    expect(screen.getByText('Vue')).toBeInTheDocument();
  });

  it('prefers children over label', () => {
    const { container } = render(<Chip label="Ignored" children="Angular" />);
    expect(screen.getByText('Angular')).toBeInTheDocument();
    expect(container.textContent).not.toContain('Ignored');
  });

  it('renders chip class', () => {
    const { container } = render(<Chip label="Test" />);
    const chip = container.querySelector('.chip');
    expect(chip).toBeInTheDocument();
  });

  it('does not render remove button when onRemove is not provided', () => {
    render(<Chip label="NoRemove" />);
    const removeBtn = screen.queryByRole('button', { name: /remove/i });
    expect(removeBtn).not.toBeInTheDocument();
  });

  it('renders remove button when onRemove is provided', () => {
    const handleRemove = vi.fn();
    render(<Chip label="Removable" onRemove={handleRemove} />);
    const removeBtn = screen.getByRole('button', { name: /remove/i });
    expect(removeBtn).toBeInTheDocument();
  });

  it('calls onRemove when remove button is clicked', () => {
    const handleRemove = vi.fn();
    render(<Chip label="Click me" onRemove={handleRemove} />);
    const removeBtn = screen.getByRole('button', { name: /remove/i });
    fireEvent.click(removeBtn);
    expect(handleRemove).toHaveBeenCalledOnce();
  });
});
