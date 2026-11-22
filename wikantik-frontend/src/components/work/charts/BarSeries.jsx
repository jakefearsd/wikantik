import ChartFrame from './ChartFrame';
import { finiteOr, formatCount, linearScale, niceTicks } from './chartScale';

const LABEL_SLOT = 48; // viewBox units one x label needs, so labels never overlap

const isMissing = (v) => v === null || v === undefined || v === '' || !Number.isFinite(Number(v)) || Number(v) < 0;

/**
 * A bar chart as inline SVG. `data` is `[{ label, value, muted }]`: one `<rect>` per datum with a value, a zero
 * drawn as a 1px baseline mark, a missing/negative/non-numeric value drawn as nothing (its figure reads as an en
 * dash). A `muted` bar (a partial period) uses the secondary token and a dashed outline, so colour is not the
 * only cue, and is flagged "partial" in its tooltip and the text table. Only every n-th x label is drawn so 52
 * bars stay legible; the hidden table beside the figure carries every figure. `format` formats the values.
 */
export default function BarSeries({ data = [], title, description = '', format = formatCount, width = 640, height = 200 }) {
  const rows = (Array.isArray(data) ? data : []).map((d, i) => ({
    key: `${i}`,
    label: String(d?.label ?? ''),
    missing: isMissing(d?.value),
    value: isMissing(d?.value) ? 0 : Number(d.value),
    muted: !!d?.muted,
  }));
  const max = rows.reduce((m, r) => Math.max(m, r.value), 0);
  const ticks = niceTicks(max);
  const summary = rows.length === 0
    ? `${title}: no data`
    : `${title}: ${rows.length} ${rows.length === 1 ? 'bar' : 'bars'}, highest ${format(max)}`;
  return (
    <figure className="work-chart-figure" aria-label={summary}>
      <ChartFrame title={title} description={description || summary} width={width} height={height}
        yTicks={ticks} yFormat={format}>
        {({ x0, x1, y0, y1, yScale }) => {
          const n = rows.length;
          const slot = n > 0 ? (x1 - x0) / n : 0;
          const bar = Math.max(1, slot * 0.7);
          const every = Math.max(1, Math.ceil(n / Math.max(1, Math.floor((x1 - x0) / LABEL_SLOT))));
          const scale = ticks.length > 1 ? yScale : linearScale([0, 1], [y0, y1]);
          return (
            <>
              {n === 0 && (
                <text className="work-chart-empty" x={(x0 + x1) / 2} y={(y0 + y1) / 2} textAnchor="middle" fontSize="12"
                  fill="var(--text-muted)">No data</text>
              )}
              {rows.map((r, i) => {
                const cx = x0 + slot * i + slot / 2;
                const top = scale(r.value);
                const hgt = Math.max(1, y0 - top);
                const text = r.missing ? `${r.label}: no data` : `${r.label}: ${format(r.value)}${r.muted ? ' (partial)' : ''}`;
                return (
                  <g key={r.key}>
                    {!r.missing && (
                      <rect className="work-chart-bar" data-muted={r.muted ? 'true' : undefined} x={cx - bar / 2}
                        y={y0 - hgt} width={bar} height={hgt}
                        fill={r.muted ? 'var(--text-muted)' : 'var(--accent)'}
                        stroke={r.muted ? 'var(--text-secondary)' : 'none'} strokeDasharray={r.muted ? '3 2' : undefined}>
                        <title>{text}</title>
                      </rect>
                    )}
                    {i % every === 0 && (
                      <text className="work-chart-xlabel" x={cx} y={y0 + 16} textAnchor="middle" fontSize="10"
                        fill="var(--text-muted)">{r.label}</text>
                    )}
                  </g>
                );
              })}
            </>
          );
        }}
      </ChartFrame>
      <table className="sr-only">
        <caption>{title}</caption>
        <thead><tr><th scope="col">Period</th><th scope="col">Value</th><th scope="col">Note</th></tr></thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.key}>
              <th scope="row">{r.label}</th>
              <td>{r.missing ? '–' : format(finiteOr(r.value))}</td>
              <td>{r.muted ? 'partial' : ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  );
}
