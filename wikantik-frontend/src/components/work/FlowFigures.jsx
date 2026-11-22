import StatTile from './charts/StatTile';
import { FEW, durationText } from './flowModel';
import { dayText } from './workTime';

/** "Nov 2 2026 to Nov 15 2026 (Europe/Berlin)": the window a figure covers, as the server read it. */
export function windowText(report) {
  if (!report?.from || !report?.to) return '';
  return `${dayText(report.from)} to ${dayText(report.to)}${report.zone ? ` (${report.zone})` : ''}`;
}

/** A titled block: the heading, then the plain-sentence definition every figure carries. */
export function Figure({ title, definition, children }) {
  return (
    <section className="work-figure" aria-label={title}>
      <h3 className="work-subheading">{title}</h3>
      <p className="work-figure-def">{definition}</p>
      {children}
    </section>
  );
}

/**
 * The two percentile tiles of a { n, p50Seconds, p85Seconds } value, named as plain sentences, with the sample size
 * and the window beneath. No values reads as `empty` (never a zero); fewer than FEW values are flagged, not hidden.
 */
export function PercentileFigure({ durations, subject, sampleOne, sampleMany, empty, window: windowLabel, extra }) {
  const n = durations?.n ?? 0;
  if (n === 0) return <p className="work-figure-empty">{empty}</p>;
  return (
    <>
      <div className="work-stat-row">
        <StatTile label={`Half of ${subject} took less than`} value={durationText(durations.p50Seconds)} />
        <StatTile label={`85% of ${subject} took less than`} value={durationText(durations.p85Seconds)} />
      </div>
      <p className="work-muted">
        {`from ${n} ${n === 1 ? sampleOne : sampleMany}`}
        {n < FEW ? ' · few data points, so read these figures with care' : ''}
        {n === 1 ? ' · with one value both figures are that value' : ''}
        {windowLabel ? ` · ${windowLabel}` : ''}
      </p>
      {extra}
    </>
  );
}
