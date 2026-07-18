// Skeleton.test.jsx
import { render } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import Skeleton from './Skeleton';

describe('Skeleton', () => {
  it('renders a single skeleton by default', () => {
    const { container } = render(<Skeleton />);
    const skeletons = container.querySelectorAll('.skeleton');
    expect(skeletons).toHaveLength(1);
  });

  it('renders multiple skeletons when count prop is set', () => {
    const { container } = render(<Skeleton count={3} />);
    const skeletons = container.querySelectorAll('.skeleton');
    expect(skeletons).toHaveLength(3);
  });

  it('applies variant class', () => {
    const { container } = render(<Skeleton variant="card" />);
    const skeleton = container.querySelector('.skeleton-card');
    expect(skeleton).toBeInTheDocument();
  });

  it('defaults to line variant', () => {
    const { container } = render(<Skeleton />);
    const skeleton = container.querySelector('.skeleton-line');
    expect(skeleton).toBeInTheDocument();
  });

  it('applies width style when provided', () => {
    const { container } = render(<Skeleton width="200px" />);
    const skeleton = container.querySelector('.skeleton');
    expect(skeleton).toHaveStyle('width: 200px');
  });

  it('marks skeletons as aria-hidden (decorative)', () => {
    const { container } = render(<Skeleton count={2} />);
    const skeletons = container.querySelectorAll('.skeleton');
    skeletons.forEach((skeleton) => {
      expect(skeleton).toHaveAttribute('aria-hidden', 'true');
    });
  });

  it('applies custom className', () => {
    const { container } = render(<Skeleton className="custom-class" />);
    const skeleton = container.querySelector('.custom-class');
    expect(skeleton).toBeInTheDocument();
  });
});
