import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import EmptyState from '../ui/EmptyState';
import Select from '../ui/Select';
import Spinner from '../ui/Spinner';
import { getAssignmentLog, listAgents, listProjects } from '../../api/work';
import EventActor from './EventActor';
import { eventText } from './eventText';
import { KINDS, foldRenewals, groupByDay, typesFor } from './logModel';
import WorkLoadError from './WorkLoadError';
import { clockText, whenText } from './workTime';

const PAGE = 50;
const WHO = { people: { label: 'People', actorKind: 'human' }, agents: { label: 'Agents', actorKind: 'agent' } };

/** Options for a Select from a list that may fail to load: an empty list simply offers nothing. */
const asList = (r, key) => (Array.isArray(r) ? r : (r?.[key] || []));

function RenewalLine({ event, count }) {
  const last = clockText(event.toValue, event.at);
  return (
    <>
      <EventActor event={event} />
      <span className="work-event-text">renewed its claim {count} times{last ? `, last until ${last}` : ''}</span>
    </>
  );
}

function LogRow({ event, count }) {
  const t = eventText(event);
  return (
    <li className="work-event work-log-row">
      {event.itemKey
        ? (
          <>
            <Link to={`/work/item/${event.itemKey}`} className="work-card-key">{event.itemKey}</Link>
            {event.itemTitle && <>{' '}<span className="work-log-title">{event.itemTitle}</span></>}
          </>
        )
        : <span className="work-muted work-log-title">Project membership</span>}
      {' '}
      <span className="work-card-key work-muted">{event.projectKey}</span>
      {event.projectArchived && <>{' '}<span className="work-muted">(archived)</span></>}
      {' '}
      {count > 1 ? <RenewalLine event={event} count={count} /> : (
        <>
          <EventActor event={event} />
          <span className="work-event-text">{t.text}</span>
        </>
      )}
      <time className="work-event-time" dateTime={event.at}>{whenText(event.at)}</time>
      {count === 1 && t.note && <p className="work-event-note">{t.note}</p>}
    </li>
  );
}

/**
 * /work/log: who took what and what happened next, over every project the viewer can see (or one, with ?project=).
 * At /work/:project/log (inside the project shell) the project is fixed by the path and its filter is not offered.
 * The filters live in the URL so a filtered view can be shared. Kind, project, people/agents and agent are sent to
 * the server, so "Load more" pages through the filtered log and nothing is hidden by a client-side filter over a
 * partial list. Consecutive renewals of one claim fold into one line unless "Show every renewal" is on.
 */
export default function WorkLog() {
  const [params, setParams] = useSearchParams();
  const { project: fixedProject } = useParams();
  const kind = KINDS[params.get('kind')] ? params.get('kind') : '';
  const who = WHO[params.get('who')] ? params.get('who') : '';
  const agent = /^\d+$/.test(params.get('agent') || '') ? params.get('agent') : '';
  const project = fixedProject || params.get('project') || '';
  const everyRenewal = params.get('renewals') === 'all';
  const queryKey = [kind, who, agent, project].join('|');

  const [state, setState] = useState({ key: null, gen: 0, events: null, more: false, error: null });
  // The state of a Load more, tagged with the load it belongs to: a new query makes a new generation, so a Load more
  // still in flight for the old one can neither leave this one's button disabled nor append stale rows.
  const [moreState, setMoreState] = useState({ gen: -1, loading: false, error: null });
  const [options, setOptions] = useState({ agents: [], projects: [] });
  const seq = useRef(0);

  const scope = useCallback((extra) => ({
    types: typesFor(kind), project: project || undefined, actorKind: who ? WHO[who].actorKind : undefined,
    agent: agent || undefined, ...extra,
  }), [kind, who, agent, project]);

  const load = useCallback(() => {
    const mine = ++seq.current;
    return getAssignmentLog(scope({ limit: PAGE }))
      .then((body) => {
        if (mine !== seq.current) return;
        const events = body?.events || [];
        setState({ key: queryKey, gen: mine, events, more: events.length >= PAGE, error: null });
      })
      .catch((error) => {
        console.warn('WorkLog: could not load the log', error);
        if (mine === seq.current) setState({ key: queryKey, gen: mine, events: null, more: false, error });
      });
  }, [scope, queryKey]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => () => { seq.current += 1; }, []);

  useEffect(() => {
    let live = true;
    Promise.all([
      listAgents().catch((e) => { console.warn('WorkLog: could not list agents', e); return []; }),
      listProjects().catch((e) => { console.warn('WorkLog: could not list projects', e); return []; }),
    ]).then(([agents, projects]) => {
      if (live) setOptions({ agents: asList(agents, 'agents'), projects: asList(projects, 'projects') });
    });
    return () => { live = false; };
  }, []);

  const set = (name) => (value) => setParams((p) => {
    const next = new URLSearchParams(p);
    if (value) next.set(name, value); else next.delete(name);
    return next;
  }, { replace: true });

  const events = state.key === queryKey ? state.events : null;
  const more = moreState.gen === state.gen ? moreState : { loading: false, error: null };
  const view = useMemo(() => foldRenewals(events || [], !everyRenewal), [events, everyRenewal]);
  const days = useMemo(() => groupByDay(view.rows), [view.rows]);

  function loadMore() {
    const mine = seq.current;
    const last = events[events.length - 1];
    const gen = state.gen;
    setMoreState({ gen, loading: true, error: null });
    getAssignmentLog(scope({ before: last.at, beforeId: last.id, limit: PAGE }))
      .then((body) => {
        if (mine !== seq.current) return;
        const page = body?.events || [];
        setState((s) => {
          const seen = new Set(s.events.map((e) => e.id));
          return { ...s, events: [...s.events, ...page.filter((e) => !seen.has(e.id))], more: page.length >= PAGE };
        });
      })
      .catch((error) => {
        console.warn('WorkLog: could not load more of the log', error);
        if (mine === seq.current) setMoreState({ gen, loading: false, error: error?.message || 'Could not load more.' });
      })
      .finally(() => { if (mine === seq.current) setMoreState((m) => (m.gen === gen ? { ...m, loading: false } : m)); });
  }

  const filtering = !!(kind || who || agent || (project && !fixedProject));
  const toolbar = (
    <div className="work-board-toolbar work-log-filters">
      <label className="work-toggle">Kind
        <Select ariaLabel="Kind of event" value={kind} placeholder="Everything"
          options={Object.entries(KINDS).map(([value, k]) => ({ value, label: k.label }))} onChange={set('kind')} />
      </label>
      <label className="work-toggle">Who
        <Select ariaLabel="Who" value={who} placeholder="People and agents"
          options={Object.entries(WHO).map(([value, w]) => ({ value, label: w.label }))} onChange={set('who')} />
      </label>
      <label className="work-toggle">Agent
        <Select ariaLabel="Agent" value={agent} placeholder="Any agent"
          options={options.agents.map((a) => ({ value: String(a.id), label: a.name }))} onChange={set('agent')} />
      </label>
      {!fixedProject && (
        <label className="work-toggle">Project
          <Select ariaLabel="Project" value={project} placeholder="All my projects"
            options={options.projects.map((p) => ({ value: p.key, label: `${p.key} ${p.name}` }))} onChange={set('project')} />
        </label>
      )}
    </div>
  );

  const heading = fixedProject ? null : (
    <header className="work-header">
      <h1 className="work-heading"><Link to="/work">Work</Link> <span aria-hidden="true">▸</span> Assignments and claims</h1>
    </header>
  );

  let body;
  if (state.key !== queryKey || (!events && !state.error)) body = <Spinner label="Loading…" />;
  else if (state.error) {
    body = <WorkLoadError error={state.error} fallback="Could not load the log." onRetry={() => { setState({ key: null, gen: 0, events: null, more: false, error: null }); load(); }} />;
  } else if (events.length === 0) {
    body = <EmptyState message={filtering ? 'No assignments or claims match these filters.' : 'No assignments or claims yet.'} />;
  } else {
    body = (
      <>
        <div className="work-log-renewals">
          <button type="button" className="btn btn-sm" aria-pressed={everyRenewal}
            onClick={() => set('renewals')(everyRenewal ? '' : 'all')}>
            {everyRenewal ? 'Fold renewals' : 'Show every renewal'}
          </button>
          {!everyRenewal && view.folded > 0 && (
            <span className="work-muted" role="status">
              {' '}{view.folded} earlier renewal{view.folded === 1 ? '' : 's'} folded into the lines above.
            </span>
          )}
        </div>
        {days.map((day) => (
          <section key={day.dayKey} aria-label={day.label}>
            <h2 className="work-subheading">{day.label}</h2>
            <ul className="work-timeline work-activity-list">
              {day.rows.map((r) => <LogRow key={r.event.id} event={r.event} count={r.count} />)}
            </ul>
          </section>
        ))}
        {more.error && <p className="work-form-error" role="alert">{more.error}</p>}
        {state.more && (
          <button type="button" className="btn btn-sm" disabled={more.loading} onClick={loadMore}>
            {more.loading ? 'Loading…' : 'Load more'}
          </button>
        )}
      </>
    );
  }

  return <div className="work-log">{heading}{toolbar}{body}</div>;
}
