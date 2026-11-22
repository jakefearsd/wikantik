import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import BurndownChart from './BurndownChart';

const days = [
  { date: '2026-11-23', remainingPoints: 8, remainingItems: 3, scopePoints: 8, scopeItems: 3 },
  { date: '2026-11-24', remainingPoints: 6, remainingItems: 2, scopePoints: 8, scopeItems: 3 },
  { date: '2026-11-25', remainingPoints: 7, remainingItems: 3, scopePoints: 10, scopeItems: 4 },
];

describe('BurndownChart', () => {
  it('renders one point per day and an ideal line', () => {
    const { container } = render(<BurndownChart days={days} committedPoints={8} startsOn="2026-11-23" endsOn="2026-11-27" />);
    expect(screen.getByRole('img', { name: /^Burndown/ })).toBeInTheDocument();
    expect(container.querySelectorAll('circle[data-series="remaining"]')).toHaveLength(3);
    const ideal = container.querySelector('polyline[data-series="ideal"]');
    expect(ideal).toHaveAttribute('stroke-dasharray');
    expect(ideal.getAttribute('points').split(' ')).toHaveLength(5);
    // the scope line steps: a flat run, then a rise on the day the scope grew
    expect(container.querySelector('polyline[data-series="scope"]').getAttribute('points').split(' ')).toHaveLength(5);
  });

  it('covers days past the end date and keeps the ideal line to the end date', () => {
    const long = ['23', '24', '25', '26', '27', '28', '29'].map((d, i) => (
      { date: `2026-11-${d}`, remainingPoints: 8 - i, remainingItems: 1, scopePoints: 8, scopeItems: 1 }));
    const { container } = render(<BurndownChart days={long} committedPoints={8} startsOn="2026-11-23" endsOn="2026-11-27" />);
    const circles = [...container.querySelectorAll('circle[data-series="remaining"]')];
    expect(circles).toHaveLength(7);
    expect(Number(circles[6].getAttribute('cx'))).toBe(452);
    expect(container.querySelector('polyline[data-series="ideal"]').getAttribute('points').split(' ')).toHaveLength(5);
  });

  it('says so when there are no days', () => {
    const { container } = render(<BurndownChart days={[]} committedPoints={0} startsOn="2026-11-23" endsOn="2026-11-27" />);
    expect(screen.getByText('No burndown yet: the sprint has not started.')).toBeInTheDocument();
    expect(container.querySelector('svg')).toBeNull();
  });
});
