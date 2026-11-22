import BarSeries from './charts/BarSeries';
import { Figure, PercentileFigure, windowText } from './FlowFigures';
import { partialWeek, percent, todayIso, weekLabel } from './flowModel';

const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;
const sum = (rows, field) => rows.reduce((t, r) => t + (Number(r[field]) || 0), 0);

/** The sentence about changes whose commit time the server could not use as given. Exported for the docs and tests. */
export const noCommitText = (n) => (n === 1
  ? '1 change had no usable first-commit time (missing, or later than opening); its lead time starts when it was opened.'
  : `${n} changes had no usable first-commit time (missing, or later than opening); their lead time starts when they were opened.`);
export const clampText = (n, days) => (n === 1
  ? `1 change had a first commit more than ${days} days before it was opened; its lead time starts ${days} days before opening.`
  : `${n} changes had a first commit more than ${days} days before they were opened; their lead time starts ${days} days before opening.`);

export const excludedText = (n) => (n === 1
  ? '1 change was left out because its lead time was negative.'
  : `${n} changes were left out because their lead time was negative.`);

function Rate({ title, definition, rate, noun, none, win }) {
  const d = Number(rate?.denominator) || 0;
  return (
    <Figure title={title} definition={definition}>
      {d === 0 ? <p className="work-figure-empty">{none}</p> : (
        <>
          <div className="work-stat-row">
            <div className="work-stat">
              <div className="work-stat-label">{title}</div>
              <div className="work-stat-value">{percent(rate)}</div>
              <div className="work-stat-note">{rate.numerator} of {plural(d, noun, `${noun}s`)}</div>
            </div>
          </div>
          <p className="work-muted">
            {`from ${plural(d, noun, `${noun}s`)}`}{d < 5 ? ' · few data points, so read this figure with care' : ''} · {win}
          </p>
        </>
      )}
    </Figure>
  );
}

/**
 * The delivery figures of one project (or of every project of a product): deployment frequency, lead time for
 * changes, change failure rate, recovery time, rework rate and the merge mix. Presentational: `report` is the dora
 * JSON. Each figure states its definition, unit, window and sample size; an empty window reads as "none", never zero.
 * `wholeProduct` is set on the product page (every project). `today` (yyyy-MM-dd) marks the week still running as partial.
 */
export default function DoraPanel({ report, wholeProduct = false, today = todayIso() }) {
  if (!report) return null;
  const win = windowText(report);
  const weeks = report.deploymentFrequency || [];
  const deployments = sum(weeks, 'deployments');
  const days = report.maxCommitLeadDays ?? 30;
  // The server's count already includes the changes with no commit time at all, so one sentence covers both.
  const unusable = report.changesWithoutUsableCommitTime ?? report.changesWithoutCommitTime ?? 0;
  const scope = wholeProduct
    ? 'Production deployments of the product, with the changes of every project.'
    : 'Production deployments that carry an item of this project, with the changes of this project\'s items.';
  const merges = report.merges || {};
  const mergeTotal = (merges.reviewed || 0) + (merges.unreviewed || 0) + (merges.direct || 0);

  return (
    <div className="work-dora">
      <p className="work-muted">
        {scope} {report.product ? `Product ${report.product}. ` : ''}Figures are per project and describe how the work flows,
        not how any person performs. {win}.
      </p>

      <Figure title="Deployment frequency"
        definition="How many production deployments were verified in each week (Monday to Sunday). A deployment that was verified and later rolled back still counts here, but ships no changes for lead time.">
        {deployments === 0
          ? <p className="work-figure-empty">No deployments in this period.</p>
          : (
            <>
              <BarSeries title="Production deployments verified per week"
                description="Verified production deployments per week; a week still running or cut by the period is marked partial."
                data={weeks.map((w) => ({
                  label: weekLabel(w.weekStart), value: w.deployments,
                  muted: partialWeek(w.weekStart, report.from, report.to, today),
                }))} />
              <p className="work-muted">
                {plural(deployments, 'deployment', 'deployments')} in total · a muted, dashed bar is a partial week · {win}
              </p>
            </>
          )}
      </Figure>

      <Figure title="Lead time for changes"
        definition={`How long a merged change took from its first commit to the first verified production deployment that carried it. The clock starts at the first commit, but no earlier than ${days} days before the change was opened.`}>
        <PercentileFigure durations={report.leadTimeForChanges} subject="changes" sampleOne="change" sampleMany="changes"
          empty="No changes shipped in this period." window={win} />
        {unusable > 0 && <p className="work-muted">{noCommitText(unusable)}</p>}
        {report.excluded > 0 && <p className="work-muted">{excludedText(report.excluded)}</p>}
        {report.changesWithClampedCommitTime > 0 && <p className="work-muted">{clampText(report.changesWithClampedCommitTime, days)}</p>}
      </Figure>

      <Rate title="Change failure rate" rate={report.changeFailureRate} noun="deployment" win={win}
        none="No deployments in this period."
        definition="The share of production deployments that finished in this period and then failed, were rolled back (even after being verified) or were fixed by a later deployment." />

      <Figure title="Recovery time"
        definition="How long a failed production deployment took to recover: from when it went live to its rollback or to the verification of a deployment that fixes it and still stands (a fix later rolled back or failed does not end the clock). A fix filed under another project still ends the clock; only the duration is shown.">
        <PercentileFigure durations={report.recoveryTime} subject="recoveries" sampleOne="recovered failure"
          sampleMany="recovered failures" empty={report.unrecovered > 0 ? 'No failed deployment has recovered yet.' : 'No failed deployments in this period.'}
          window={win} />
        {report.unrecovered > 0 && (
          <p className="work-muted">{report.unrecovered} not yet recovered; they are left out of the durations above.</p>
        )}
      </Figure>

      <Rate title="Rework rate" rate={report.reworkRate} noun="deployment" win={win}
        none="No deployments in this period."
        definition="The share of production deployments that finished in this period and were themselves a fix for an earlier deployment." />

      <Figure title="How changes were merged"
        definition="The changes merged in this period, by how they were merged. This counts only this period; the flagged list on a change counts every merge still waiting to be acknowledged, whenever it happened.">
        {mergeTotal === 0 && !merges.flagged
          ? <p className="work-figure-empty">No merges in this period.</p>
          : (
            <>
              <ul className="work-merge-mix">
                <li>Reviewed: {merges.reviewed ?? 0}</li>
                <li>Unreviewed: {merges.unreviewed ?? 0}</li>
                <li>Direct: {merges.direct ?? 0}</li>
                <li>Flagged merges in this window: {merges.flagged ?? 0}
                  {' '}(of which not yet acknowledged: {merges.flaggedUnacknowledged ?? 0})</li>
              </ul>
              <p className="work-muted">{win}</p>
            </>
          )}
      </Figure>
    </div>
  );
}
