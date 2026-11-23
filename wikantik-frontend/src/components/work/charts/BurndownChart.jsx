import { idealLine } from '../sprintModel';
import { dayText } from '../workTime';

const W = 720;
const H = 300;
const PAD = 40;
const LEGEND = [
  { key: 'remaining', label: 'Remaining', stroke: 'var(--accent)' },
  { key: 'ideal', label: 'Ideal', stroke: 'var(--text-muted)', dash: '4 3' },
  { key: 'scope', label: 'Scope', stroke: 'var(--warning)' },
];
const shortDay = (iso) => dayText(iso).slice(0, -5);

/**
 * A sprint's burndown as inline SVG: remaining points per day (a dot each), the ideal line to zero on the last
 * day (dashed) and the sprint's scope (a step line), with the first and last day named under the axis and a legend
 * for the three lines. `days` is the report's day list; it may stop short of `endsOn` while the sprint runs.
 */
export default function BurndownChart({ days = [], committedPoints = 0, startsOn, endsOn }) {
  if (!days || days.length === 0) {
    return <p className="work-muted">No burndown yet: the sprint has not started.</p>;
  }
  const ideal = idealLine(committedPoints, startsOn, endsOn);
  const slots = Math.max(ideal.length, days.length, 2);
  const max = Math.max(1, Number(committedPoints) || 0,
    ...days.map((d) => Math.max(Number(d.remainingPoints) || 0, Number(d.scopePoints) || 0)));
  const x = (i) => PAD + (i * (W - 2 * PAD)) / (slots - 1);
  const y = (v) => H - PAD - ((Number(v) || 0) / max) * (H - 2 * PAD);
  const pt = (i, v) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`;
  const stepped = days.flatMap((d, i) => (i === 0
    ? [pt(0, d.scopePoints)]
    : [pt(i, days[i - 1].scopePoints), pt(i, d.scopePoints)]));
  const last = days[days.length - 1];
  const firstDay = days[0].date;
  const lastDay = days.length >= ideal.length ? last.date : (ideal[ideal.length - 1]?.date || endsOn);
  const label = `Burndown: ${last.remainingPoints} of ${Number(committedPoints) || 0} points remaining after day ${days.length} of ${slots}`;
  return (
    <svg className="work-burndown" role="img" aria-label={label} viewBox={`0 0 ${W} ${H}`} width="100%" style={{ maxWidth: W }}>
      <line x1={PAD} y1={H - PAD} x2={W - PAD} y2={H - PAD} stroke="var(--border-strong)" />
      <line x1={PAD} y1={PAD} x2={PAD} y2={H - PAD} stroke="var(--border-strong)" />
      <text x={PAD - 6} y={PAD + 4} textAnchor="end" fontSize="12" fill="var(--text-muted)">{max}</text>
      <text x={PAD - 6} y={H - PAD + 4} textAnchor="end" fontSize="12" fill="var(--text-muted)">0</text>
      <text data-axis="first" x={PAD} y={H - PAD + 18} textAnchor="start" fontSize="12" fill="var(--text-muted)">{shortDay(firstDay)}</text>
      <text data-axis="last" x={W - PAD} y={H - PAD + 18} textAnchor="end" fontSize="12" fill="var(--text-muted)">{shortDay(lastDay)}</text>
      {LEGEND.map((l, i) => (
        <g key={l.key} data-legend={l.key} transform={`translate(${PAD + 110 + i * 120}, ${H - PAD + 14})`}>
          <line x1="0" y1="0" x2="22" y2="0" stroke={l.stroke} strokeWidth="2" strokeDasharray={l.dash} />
          <text x="28" y="4" fontSize="12" fill="var(--text-secondary)">{l.label}</text>
        </g>
      ))}
      <polyline data-series="ideal" fill="none" stroke="var(--text-muted)" strokeWidth="2" strokeDasharray="4 3"
        points={ideal.map((p, i) => pt(i, p.points)).join(' ')} />
      <polyline data-series="scope" fill="none" stroke="var(--warning)" strokeWidth="2" points={stepped.join(' ')} />
      <polyline fill="none" stroke="var(--accent)" strokeWidth="2"
        points={days.map((d, i) => pt(i, d.remainingPoints)).join(' ')} />
      {days.map((d, i) => (
        <circle key={d.date} data-series="remaining" cx={x(i)} cy={y(d.remainingPoints)} r="4" fill="var(--accent)">
          <title>{`${d.date}: ${d.remainingPoints} points remaining, scope ${d.scopePoints}`}</title>
        </circle>
      ))}
    </svg>
  );
}
