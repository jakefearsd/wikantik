import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import EmptyState from '../ui/EmptyState';
import Select from '../ui/Select';
import Spinner from '../ui/Spinner';
import { getActivity } from '../../api/work';
import { useWorkProject } from './useWorkProject';
import { eventText } from './eventText';
import WorkLoadError from './WorkLoadError';

const PAGE = 50;
const SOMEONE = 'Someone';
const actorOf = (e) => e.actorName || SOMEONE;
const when = (iso) => (iso ? new Date(iso).toLocaleString() : '');

/** /work/:project/activity: newest first, loaded 50 at a time. Filters apply to what is loaded only. */
/** Keyed by project so filters and loaded pages never carry across projects. */
export default function WorkActivity() {
  const { project } = useWorkProject();
  return <ActivityFeed key={project.key} />;
}

function ActivityFeed() {
  const { project } = useWorkProject();
  const [state, setState] = useState({ key: null, events: null, more: false, error: null });
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreError, setMoreError] = useState(null);
  const [type, setType] = useState('');
  const [person, setPerson] = useState('');
  const seq = useRef(0);

  const load = useCallback(() => {
    const mine = ++seq.current;
    return getActivity({ project: project.key, limit: PAGE })
      .then((page) => {
        if (mine !== seq.current) return;
        setState({ key: project.key, events: page || [], more: (page || []).length >= PAGE, error: null });
      })
      .catch((error) => {
        console.warn('WorkActivity: could not load activity', error);
        if (mine === seq.current) setState({ key: project.key, events: null, more: false, error });
      });
  }, [project.key]);

  const retry = () => { setState({ key: project.key, events: null, more: false, error: null }); setMoreError(null); load(); };

  useEffect(() => { load(); }, [load]);

  const events = state.key === project.key ? state.events : null;

  function loadMore() {
    const mine = seq.current;
    const last = events[events.length - 1];
    setLoadingMore(true); setMoreError(null);
    getActivity({ project: project.key, before: last.at, beforeId: last.id, limit: PAGE })
      .then((page) => {
        if (mine !== seq.current) return;
        setState((s) => {
          const seen = new Set(s.events.map((e) => e.id));
          return { ...s, events: [...s.events, ...(page || []).filter((e) => !seen.has(e.id))], more: (page || []).length >= PAGE };
        });
      })
      .catch((error) => {
        console.warn('WorkActivity: could not load more activity', error);
        if (mine === seq.current) setMoreError(error?.message || 'Could not load more activity.');
      })
      .finally(() => { if (mine === seq.current) setLoadingMore(false); });
  }

  const typeOptions = useMemo(() => [...new Set((events || []).map((e) => e.type).filter(Boolean))].sort()
    .map((t) => ({ value: t, label: t.replace(/_/g, ' ') })), [events]);
  const personOptions = useMemo(() => [...new Set((events || []).map(actorOf))].sort(), [events]);

  if (state.key !== project.key || (!events && !state.error)) return <Spinner label="Loading…" />;
  if (state.error) return <WorkLoadError error={state.error} fallback="Could not load the activity." onRetry={retry} />;
  if (events.length === 0) return <EmptyState message="No activity yet." />;

  const filtered = events.filter((e) => (!type || e.type === type) && (!person || actorOf(e) === person));
  const filtering = !!(type || person);

  return (
    <div className="work-activity">
      <div className="work-board-toolbar">
        <label className="work-toggle">Event type
          <Select ariaLabel="Event type" value={type} options={typeOptions} placeholder="All events" onChange={setType} />
        </label>
        <label className="work-toggle">Person
          <Select ariaLabel="Person" value={person} options={personOptions} placeholder="Everyone" onChange={setPerson} />
        </label>
        {filtering && <span className="work-muted">Filtering loaded activity</span>}
      </div>
      {filtered.length === 0
        ? <p className="work-muted">No loaded activity matches these filters.</p>
        : (
          <ul className="work-timeline work-activity-list">
            {filtered.map((e) => {
              const t = eventText(e);
              return (
                <li key={e.id} className="work-event">
                  <Link to={`/work/item/${e.itemKey}`} className="work-card-key">{e.itemKey}</Link>{' '}
                  <strong>{actorOf(e)}</strong> {t.text}
                  <time className="work-event-time" dateTime={e.at}>{when(e.at)}</time>
                  {t.note && <p className="work-event-note">{t.note}</p>}
                </li>
              );
            })}
          </ul>
        )}
      {moreError && <p className="work-form-error" role="alert">{moreError}</p>}
      {state.more && (
        <button type="button" className="btn btn-sm" disabled={loadingMore} onClick={loadMore}>
          {loadingMore ? 'Loading…' : 'Load more'}
        </button>
      )}
    </div>
  );
}
