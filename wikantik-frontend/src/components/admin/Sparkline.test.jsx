import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import Sparkline from './Sparkline';

describe('Sparkline', () => {
  it('renders an svg with a polyline when given >=2 numeric values', () => {
    const { container } = render(<Sparkline values={[0.6, 0.7, 0.8]} />);
    const polyline = container.querySelector('polyline');
    expect(polyline).not.toBeNull();
    expect(polyline.getAttribute('points')).toMatch(/^[\d.,\s]+$/);
  });

  it('renders an empty svg when fewer than 2 valid values', () => {
    const { container } = render(<Sparkline values={[0.6]} />);
    expect(container.querySelector('polyline')).toBeNull();
  });
});
