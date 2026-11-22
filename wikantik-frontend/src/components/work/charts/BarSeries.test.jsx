import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import BarSeries from './BarSeries';
import { badAttributes } from './chartAttrs';
import { formatDuration } from './chartScale';

const weeks = (n, f = (i) => i + 1) => Array.from({ length: n }, (_, i) => ({ label: `W${i + 1}`, value: f(i) }));

describe('BarSeries', () => {
  it('draws one bar per datum', () => {
    const { container } = render(<BarSeries data={weeks(12)} title="Throughput" />);
    expect(container.querySelectorAll('rect.work-chart-bar')).toHaveLength(12);
  });
  it('still draws a 1px mark for a zero value', () => {
    const { container } = render(<BarSeries data={[{ label: 'a', value: 0 }, { label: 'b', value: 4 }]} title="T" />);
    const bars = container.querySelectorAll('rect.work-chart-bar');
    expect(bars).toHaveLength(2);
    expect(bars[0].getAttribute('height')).toBe('1');
  });
  it('names the figure through the svg title and an accessible name', () => {
    const { container } = render(<BarSeries data={weeks(3)} title="Throughput per week" />);
    expect(container.querySelector('title').textContent).toBe('Throughput per week');
    expect(screen.getByRole('img', { name: /Throughput per week/ })).toBeInTheDocument();
  });
  it('draws fewer labels than bars at 52 bars', () => {
    const { container } = render(<BarSeries data={weeks(52)} title="T" />);
    const labels = container.querySelectorAll('text.work-chart-xlabel');
    expect(labels.length).toBeGreaterThan(0);
    expect(labels.length).toBeLessThan(52);
  });
  it('marks a muted bar with the secondary token, a dashed outline and "partial"', () => {
    const { container } = render(<BarSeries data={[{ label: 'a', value: 3 }, { label: 'b', value: 2, muted: true }]} title="T" />);
    const [full, part] = container.querySelectorAll('rect.work-chart-bar');
    expect(full.getAttribute('fill')).toBe('var(--accent)');
    expect(part.getAttribute('fill')).toBe('var(--text-muted)');
    expect(part).toHaveAttribute('stroke-dasharray');
    expect(part.querySelector('title').textContent).toContain('partial');
  });
  it('carries every figure in a text table, formatted by `format`', () => {
    render(<BarSeries data={[{ label: 'W1', value: 26 }, { label: 'W2', value: null }]} title="Lead time" format={formatDuration} />);
    const rows = screen.getAllByRole('row', { hidden: true });
    expect(rows[1].textContent).toContain('1 d 2 h');
    expect(rows[2].textContent).toContain('–');
  });
  it('renders no NaN or Infinity for empty, single, zero, null and huge data', () => {
    const cases = [
      [], [{ label: 'a', value: 5 }], weeks(5, () => 0),
      [{ label: 'a', value: null }, { label: 'b', value: undefined }, { label: 'c', value: NaN }, { label: 'd', value: 2 }],
      weeks(4, (i) => 10 ** (10 * i)), [{ label: 'a', value: Number.MAX_VALUE }], undefined,
    ];
    cases.forEach((data) => {
      const { container, unmount } = render(<BarSeries data={data} title="T" />);
      expect(badAttributes(container)).toEqual([]);
      unmount();
    });
  });
  it('says there is no data when empty', () => {
    render(<BarSeries data={[]} title="T" />);
    expect(screen.getByText('No data')).toBeInTheDocument();
  });
});
