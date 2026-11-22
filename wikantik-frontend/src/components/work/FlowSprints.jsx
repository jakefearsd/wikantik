import { getSprintReport, getVelocity } from '../../api/work';
import BarSeries from './charts/BarSeries';
import BurndownChart from './charts/BurndownChart';
import { Figure } from './FlowFigures';
import useFlowReport from './useFlowReport';

/** The project's sprint figures: velocity per sprint, and the chosen sprint's burndown. Loads on its own. */
export default function FlowSprints({ project, sprintId }) {
  const velocity = useFlowReport(() => getVelocity(project), `velocity:${project}`, 'velocity');
  const burn = useFlowReport(() => getSprintReport(sprintId), sprintId ? `burn:${sprintId}` : null, 'burndown');
  const sprints = velocity.data?.sprints || [];
  const report = burn.data?.sprint ? burn.data : null;
  return (
    <>
      <Figure title="Sprint velocity"
        definition="The points each closed sprint completed, counted when the sprint ended. This is a snapshot at sprint end, so it will not match the weekly throughput above, which counts each item in the week it was last completed.">
        {velocity.error && (
          <div className="work-form-error" role="alert">
            {velocity.error.message || 'Could not load the velocity.'}{' '}
            <button type="button" className="btn btn-sm" onClick={velocity.reload}>Retry</button>
          </div>
        )}
        {sprints.length === 0 ? <p className="work-figure-empty">{velocity.data ? 'No closed sprints yet.' : ''}</p> : (
          <>
            <BarSeries title="Points completed per sprint"
              description="Points completed in each sprint, measured at the end of the sprint."
              data={sprints.map((s) => ({ label: s.name, value: s.completedPoints }))} />
            <p className="work-muted">from {sprints.length} {sprints.length === 1 ? 'sprint' : 'sprints'} · unit: points</p>
          </>
        )}
      </Figure>
      {sprintId && (
        <Figure title="Sprint burndown"
          definition="The chosen sprint's remaining points at the end of each day, against the ideal line to zero on its last day.">
          {burn.error && (
            <div className="work-form-error" role="alert">
              {burn.error.message || 'Could not load the burndown.'}{' '}
              <button type="button" className="btn btn-sm" onClick={burn.reload}>Retry</button>
            </div>
          )}
          {report && (
            <BurndownChart days={report.days || []} committedPoints={report.sprint.committed?.points ?? report.sprint.current?.points ?? 0}
              startsOn={report.sprint.startsOn} endsOn={report.sprint.endsOn} />
          )}
        </Figure>
      )}
    </>
  );
}
