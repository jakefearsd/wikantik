import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import ChartFrame from './ChartFrame';
import { badAttributes } from './chartAttrs';

describe('ChartFrame', () => {
  it('has a title, a description and the plot area through the render prop', () => {
    let area;
    const { container } = render(<ChartFrame title="T" description="D" yTicks={[0, 5, 10]}>{(a) => { area = a; return null; }}</ChartFrame>);
    expect(screen.getByRole('img', { name: /T/ })).toHaveAccessibleDescription('D');
    expect(container.querySelectorAll('line.work-chart-grid')).toHaveLength(3);
    expect(area.x0).toBeLessThan(area.x1);
    expect(area.y1).toBeLessThan(area.y0);
    expect(area.yScale(10)).toBe(area.y1);
  });
  it('drops a y label that repeats its neighbour and survives junk ticks', () => {
    const { container } = render(<ChartFrame title="T" description="D" yTicks={[0, 0.2, 0.4, NaN, null]} yFormat={(v) => String(Math.round(v))} />);
    const labels = [...container.querySelectorAll('text.work-chart-ylabel')].map((t) => t.textContent);
    expect(new Set(labels).size).toBe(labels.length);
    expect(badAttributes(container)).toEqual([]);
  });
  it('falls back to default ticks and size for missing or bad props', () => {
    const { container } = render(<ChartFrame title="T" description="D" width={NaN} height={undefined} />);
    expect(badAttributes(container)).toEqual([]);
    expect(container.querySelectorAll('line.work-chart-grid')).toHaveLength(2);
  });
});
