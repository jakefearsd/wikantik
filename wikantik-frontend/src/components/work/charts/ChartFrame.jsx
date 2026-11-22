import { useId } from 'react';
import { finiteOr, linearScale, formatCount, niceTicks } from './chartScale';

const PAD = { left: 48, right: 10, top: 10, bottom: 26 };

/**
 * The shared frame of the work charts: an inline `<svg role="img">` named by `title` and described by
 * `description`, with horizontal gridlines and y labels at `yTicks` (numbers, ascending; default 0 and 1). The
 * children draw in the plot area through a render prop `({ x0, x1, y0, y1, yScale })`: x0/x1 are the left and
 * right plot edges, y0 the baseline (bottom) and y1 the top, in viewBox units. `yFormat` formats the labels; a
 * label that repeats its neighbour is dropped.
 */
export default function ChartFrame({
  title, description, width = 640, height = 200, yTicks, yFormat = formatCount, children,
}) {
  const id = useId();
  const w = Math.max(120, finiteOr(width, 640));
  const h = Math.max(80, finiteOr(height, 200));
  const ticks = Array.isArray(yTicks) && yTicks.length > 0
    ? yTicks.map((t) => finiteOr(t)).filter((t, i, a) => a.indexOf(t) === i).sort((a, b) => a - b)
    : niceTicks(0);
  const area = { x0: PAD.left, x1: w - PAD.right, y0: h - PAD.bottom, y1: PAD.top };
  const top = ticks.length > 1 ? ticks[ticks.length - 1] : 1;
  const yScale = linearScale([0, top], [area.y0, area.y1]);
  const labels = ticks.map((t) => yFormat(t));
  const rows = ticks.map((t, i) => ({ t, label: labels[i], show: i === 0 || labels[i] !== labels[i - 1], y: yScale(t) }));
  return (
    <svg className="work-chart" role="img" viewBox={`0 0 ${w} ${h}`} width="100%" style={{ maxWidth: w }}
      aria-labelledby={`${id}-t`} aria-describedby={`${id}-d`}>
      <title id={`${id}-t`}>{title}</title>
      <desc id={`${id}-d`}>{description}</desc>
      {rows.map((r) => (
        <g key={r.t}>
          <line className="work-chart-grid" x1={area.x0} x2={area.x1} y1={r.y} y2={r.y}
            stroke={r.t === 0 ? 'var(--border-strong)' : 'var(--border)'} />
          {r.show && (
            <text className="work-chart-ylabel" x={area.x0 - 6} y={r.y + 3} textAnchor="end" fontSize="10"
              fill="var(--text-muted)">{r.label}</text>
          )}
        </g>
      ))}
      {typeof children === 'function' ? children({ ...area, yScale }) : children}
    </svg>
  );
}
