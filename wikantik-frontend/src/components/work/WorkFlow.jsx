import { useEffect, useMemo, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import Select from '../ui/Select';
import Spinner from '../ui/Spinner';
import { getProjectFlow, listLabels } from '../../api/work';
import { useDocumentTitle } from '../../hooks/useDocumentTitle';
import { STATUS_LABELS, TYPE_OPTIONS, labelRows } from './boardModel';
import BarSeries from './charts/BarSeries';
import FlowProjectDora from './FlowProjectDora';
import FlowSprints from './FlowSprints';
import FlowWindow from './FlowWindow';
import { Figure, PercentileFigure, windowText } from './FlowFigures';
import {
  FLOW_PARAMS, durationText, paramsFrom, partialWeek, todayIso, toSearch, weekLabel, windowError,
} from './flowModel';
import { useWorkProject } from './useWorkProject';
import useFlowReport from './useFlowReport';
import WorkLoadError from './WorkLoadError';

const LANE_LABELS = { in_progress: 'In progress', review: 'In review' };
const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;
const SHOWN = (n) => (Number.isInteger(n) ? String(n) : String(Number(n.toFixed(1))));

function WipList({ lane }) {
  const label = LANE_LABELS[lane.status] || STATUS_LABELS[lane.status] || String(lane.status).replace(/_/g, ' ');
  return (
    <section aria-label={`${label} now`}>
      <h4 className="work-subheading">{label}: {lane.count}</h4>
      {lane.items.length === 0 ? <p className="work-figure-empty">Nothing is {label.toLowerCase()} right now.</p> : (
        <ul className="work-story-list">
          {lane.items.map((i) => (
            <li key={i.key}>
              <Link to={`/work/item/${i.key}`}>{i.key}</Link>
              <span className="work-story-title">{i.title}</span>
              <span className="work-muted"> · {durationText(i.ageSeconds)} old</span>
            </li>
          ))}
        </ul>
      )}
      {lane.count > lane.items.length && (
        <p className="work-muted">Showing the oldest {lane.items.length} of {lane.count}.</p>
      )}
    </section>
  );
}

/**
 * /work/:project/flow — how work moves through this project: throughput, cycle and lead time, what is in progress
 * now, sprint figures and the delivery (DORA) figures. The period and filters live in the URL, so a view can be
 * shared; each block loads on its own and a failed reload keeps the last good figures. Figures are about the
 * project's flow, never about a person.
 */
export default function WorkFlow() {
  const { project } = useParams();
  const { sprints } = useWorkProject();
  const [sp, setSp] = useSearchParams();
  useDocumentTitle('Flow');
  const params = useMemo(() => paramsFrom(sp, FLOW_PARAMS), [sp]);
  // `product` narrows only the delivery figures; the flow request never carries it.
  const flowParams = useMemo(() => { const { product: _product, ...rest } = params; return rest; }, [params]);
  const [labels, setLabels] = useState([]);

  const problem = windowError(params) || (params.sprint && !/^\d+$/.test(params.sprint) ? 'A sprint is named by its number.' : null);
  const search = toSearch(flowParams).toString();
  const key = problem ? null : `flow:${project}:${search}`;
  const flow = useFlowReport(() => getProjectFlow(project, flowParams), key, 'flow figures');
  const winParams = useMemo(() => {
    const w = {};
    if (params.from) w.from = params.from;
    if (params.to) w.to = params.to;
    return w;
  }, [params.from, params.to]);

  useEffect(() => {
    let live = true;
    listLabels(project)
      .then((r) => { if (live) setLabels(labelRows(r)); })
      .catch((e) => console.warn('WorkFlow: could not load the labels', e));
    return () => { live = false; };
  }, [project]);

  const apply = (patch) => {
    const next = { ...params, ...patch };
    setSp(toSearch(next));
  };

  const report = flow.data;
  const today = todayIso();
  const throughput = report?.throughput || [];
  const totalItems = throughput.reduce((t, w) => t + (Number(w.items) || 0), 0);
  const totalPoints = throughput.reduce((t, w) => t + (Number(w.points) || 0), 0);
  const win = windowText(report);
  const sprintChoices = sprints.map((s) => ({ value: String(s.id), label: `${s.name} (${s.state})` }));

  return (
    <div className="work-flow">
      <p className="work-muted">
        How work moves through this project. These figures describe its flow, not any person. Dates are in the server&apos;s zone.
      </p>
      <FlowWindow key={`${params.from}|${params.to}|${params.assignee}`} from={params.from} to={params.to}
        assignee={params.assignee} withAssignee onApply={apply} outsideError={problem} />
      <div className="work-flow-filters">
        {sprintChoices.length > 0 && (
          <Select ariaLabel="Filter by sprint" placeholder="Any sprint" value={params.sprint || ''} options={sprintChoices}
            onChange={(v) => apply({ sprint: v })} />
        )}
        <Select ariaLabel="Filter by label" placeholder="All labels" value={params.label || ''}
          options={labels.map((l) => ({ value: l.name, label: l.count == null ? l.name : `${l.name} (${l.count})` }))}
          onChange={(v) => apply({ label: v })} />
        <Select ariaLabel="Filter by type" placeholder="All types" value={params.type || ''} options={TYPE_OPTIONS}
          onChange={(v) => apply({ type: v })} />
      </div>
      {params.sprint && !params.from && !params.to && (
        <p className="work-muted">With a sprint chosen and no dates, the period is that sprint&apos;s own dates.</p>
      )}

      {flow.notFound && <WorkLoadError error={{ status: 404 }} />}
      {flow.error && (
        <div className="work-form-error" role="alert">
          {flow.error.message || 'Could not load the flow figures.'}{' '}
          <button type="button" className="btn btn-sm" onClick={flow.reload}>Retry</button>
        </div>
      )}
      {!problem && !flow.notFound && !report && !flow.error && <Spinner label="Loading flow figures…" />}

      {!flow.notFound && report && (
        <>
          <Figure title="Throughput"
            definition="The items finished in each week (Monday to Sunday), each counted in the week it was last moved to done, under its current filters. This counts items by week, so it will not match a sprint's velocity, which is measured when the sprint ends.">
            {totalItems === 0
              ? <p className="work-figure-empty">No items were completed in this period.</p>
              : (
                <>
                  <BarSeries title="Items completed per week"
                    description="Items completed in each week; a week still running or cut by the period is marked partial."
                    data={throughput.map((w) => ({
                      label: weekLabel(w.weekStart), value: w.items, muted: partialWeek(w.weekStart, report.from, report.to, today),
                    }))} />
                  <p className="work-muted">
                    {plural(totalItems, 'item', 'items')}, {SHOWN(totalPoints)} {totalPoints === 1 ? 'point' : 'points'} in total
                    · a muted, dashed bar is a partial week · {win}
                  </p>
                </>
              )}
          </Figure>

          <Figure title="Cycle time"
            definition="How long finished items took from first being moved to in progress to being finished. Only items finished in this period are counted, and items never moved to in progress are left out.">
            <PercentileFigure durations={report.cycleTime} subject="items" sampleOne="item" sampleMany="items"
              empty="No items were completed in this period." window={win} />
          </Figure>

          <Figure title="Lead time"
            definition="How long finished items took from being created to being finished. Only items finished in this period are counted.">
            <PercentileFigure durations={report.leadTime} subject="items" sampleOne="item" sampleMany="items"
              empty="No items were completed in this period." window={win} />
          </Figure>

          <Figure title="Work in progress"
            definition="The items in progress or in review right now, oldest first. Age runs from the first move to in progress (or from creation). This is the situation now, not limited to the period above.">
            {(report.wip || []).map((lane) => <WipList key={lane.status} lane={lane} />)}
          </Figure>
        </>
      )}

      {sprints.length > 0 && <FlowSprints project={project} sprintId={params.sprint || null} />}

      <FlowProjectDora project={project} window={winParams} windowKey={problem ? null : `${winParams.from || ''}:${winParams.to || ''}`}
        product={params.product || ''} onProduct={(v) => apply({ product: v })} />
    </div>
  );
}
