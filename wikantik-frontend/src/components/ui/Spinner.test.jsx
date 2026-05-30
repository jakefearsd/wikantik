// Spinner.test.jsx
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import Spinner from './Spinner';

describe('Spinner', () => {
  it('renders with role="status" and aria-label', () => {
    render(<Spinner label="Loading data…" />);
    const spinner = screen.getByRole('status', { name: 'Loading data…' });
    expect(spinner).toBeInTheDocument();
  });

  it('applies size class based on size prop', () => {
    const { container } = render(<Spinner size="sm" />);
    const spinner = container.querySelector('.spinner-sm');
    expect(spinner).toBeInTheDocument();
  });

  it('defaults to md size', () => {
    const { container } = render(<Spinner />);
    const spinner = container.querySelector('.spinner-md');
    expect(spinner).toBeInTheDocument();
  });

  it('includes visually-hidden label text', () => {
    render(<Spinner label="Please wait" />);
    const srText = screen.getByText('Please wait');
    expect(srText).toHaveClass('sr-only');
  });

  it('accepts custom className', () => {
    const { container } = render(<Spinner className="custom-class" />);
    const spinner = container.querySelector('.custom-class');
    expect(spinner).toBeInTheDocument();
  });
});
