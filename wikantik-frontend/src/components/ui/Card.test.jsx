// Card.test.jsx
import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import Card from './Card';

describe('Card', () => {
  it('renders children', () => {
    render(<Card>Test content</Card>);
    expect(screen.getByText('Test content')).toBeInTheDocument();
  });

  it('applies surface class', () => {
    const { container } = render(<Card>Content</Card>);
    const card = container.querySelector('.surface');
    expect(card).toBeInTheDocument();
  });

  it('passes through className', () => {
    const { container } = render(<Card className="custom-class">Content</Card>);
    const card = container.querySelector('.custom-class');
    expect(card).toBeInTheDocument();
    expect(card).toHaveClass('surface');
  });

  it('renders as different element when as prop is provided', () => {
    const { container } = render(<Card as="section">Content</Card>);
    const section = container.querySelector('section.surface');
    expect(section).toBeInTheDocument();
  });

  it('forwards onClick and other props', () => {
    const handleClick = vi.fn();
    const { container } = render(
      <Card onClick={handleClick} data-testid="card-button">
        Click me
      </Card>
    );
    const card = container.querySelector('[data-testid="card-button"]');
    card.click();
    expect(handleClick).toHaveBeenCalled();
  });
});
