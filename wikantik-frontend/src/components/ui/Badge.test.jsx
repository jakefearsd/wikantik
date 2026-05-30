// Badge.test.jsx
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import Badge from './Badge';

describe('Badge', () => {
  it('renders children', () => {
    render(<Badge>New</Badge>);
    expect(screen.getByText('New')).toBeInTheDocument();
  });

  it('applies default variant class', () => {
    const { container } = render(<Badge>Default</Badge>);
    const badge = container.querySelector('.badge-default');
    expect(badge).toBeInTheDocument();
    expect(badge).toHaveClass('badge');
  });

  it('applies success variant class', () => {
    const { container } = render(<Badge variant="success">Active</Badge>);
    const badge = container.querySelector('.badge-success');
    expect(badge).toBeInTheDocument();
  });

  it('applies danger variant class', () => {
    const { container } = render(<Badge variant="danger">Error</Badge>);
    const badge = container.querySelector('.badge-danger');
    expect(badge).toBeInTheDocument();
  });

  it('applies warning variant class', () => {
    const { container } = render(<Badge variant="warning">Warning</Badge>);
    const badge = container.querySelector('.badge-warning');
    expect(badge).toBeInTheDocument();
  });

  it('passes through custom className', () => {
    const { container } = render(<Badge className="custom">Text</Badge>);
    const badge = container.querySelector('.custom');
    expect(badge).toBeInTheDocument();
    expect(badge).toHaveClass('badge');
  });
});
