import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import StatTile from './StatTile';

describe('StatTile', () => {
  it('renders value, label and note', () => {
    render(<StatTile label="Lead time" value="2 d" note="median, last 90 days" />);
    expect(screen.getByText('Lead time')).toBeInTheDocument();
    expect(screen.getByText('2 d')).toBeInTheDocument();
    expect(screen.getByText('median, last 90 days')).toBeInTheDocument();
  });
  it('omits the note when there is none', () => {
    const { container } = render(<StatTile label="L" value={3} />);
    expect(container.querySelector('.work-stat-note')).toBeNull();
  });
  it('shows an en dash, never null or NaN, for a missing value', () => {
    [null, undefined, NaN, ''].forEach((v) => {
      const { container, unmount } = render(<StatTile label="L" value={v} />);
      expect(container.querySelector('.work-stat-value').textContent).toBe('–');
      unmount();
    });
  });
  it('shows a zero', () => {
    render(<StatTile label="L" value={0} />);
    expect(screen.getByText('0')).toBeInTheDocument();
  });
});
