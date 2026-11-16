import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import EmptyState from '../ui/EmptyState';
import Select from '../ui/Select';
import Spinner from '../ui/Spinner';
import { getActivity } from '../../api/work';
import { useWorkProject } from './useWorkProject';
import EventActor from './EventActor';
import { actorLabel, eventText } from './eventText';
import WorkLoadError from './WorkLoadError';
import { whenText } from './workTime';

const PAGE = 50;
const actorOf = actorLabel;

/**
 * /work/:project/activity: newest first, loaded 50 at a time. The item filter asks the server for one item's events;
 * the type and person filters apply to what is loaded. Keyed by project so filters and loaded pages never carry
 * across projects.
 */
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
  const [itemDraft, setItemDraft] = useState('');
  const [item, setItem] = useState('');
  const seq = useRef(0);
  const scope = useCallback((extra) => ({ project: project.key, ...(item ? { item } : {}), ...extra }), [project.key, item]);

  const load = useCallback(() => {
    const mine = ++seq.current;
    return getActivity(scope({ limit: PAGE }))
      .then((page) => {
        if (mine !== seq.current) return;
        setState({ key: project.key, events: page || [], more: (page || []).length >= PAGE, error: null });
      })
      .catch((error) => {
        console.warn('WorkActivity: could not load activity', error);
        if (mine === seq.current) setState({ key: project.key, events: null, more: false, error });
      });
  }, [project.key, scope]);

  const retry = () => { setState({ key: project.key, events: null, more: false, error: null }); setMoreError(null); load(); };

  useEffect(() => { load(); }, [load]);

  const events = state.key === project.key ? state.events : null;

  function loadMore() {
    const mine = seq.current;
    const last = events[events.length - 1];
    setLoadingMore(true); setMoreError(null);
    getActivity(scope({ before: last.at, beforeId: last.id, limit: PAGE }))
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

  const itemForm = (
    <form className="work-toggle" onSubmit={(e) => {
      e.preventDefault();
      const next = itemDraft.trim().toUpperCase();
      if (next === item) return;
      setState({ key: project.key, events: null, more: false, error: null });
      setItem(next);
    }}>
      <label htmlFor="work-activity-item">Item</label>
      <input id="work-activity-item" className="work-field-input" placeholder={`${project.key}-1`} value={itemDraft}
        onChange={(e) => setItemDraft(e.target.value)} />
      <button type="submit" className="btn btn-sm">{itemDraft.trim() ? 'Show' : 'All items'}</button>
    </form>
  );

  if (state.key !== project.key || (!events && !state.error)) return <Spinner label="Loading…" />;
  if (state.error) {
    if (item && state.error.status === 404) {
      return <div className="work-activity">{itemForm}<p className="work-muted">No such item in this project.</p></div>;
    }
    return <WorkLoadError error={state.error} fallback="Could not load the activity." onRetry={retry} />;
  }
  if (events.length === 0) {
    return item ? <div className="work-activity">{itemForm}<EmptyState message="No activity for this item." /></div>
      : <EmptyState message="No activity yet." />;
  }

  const filtered = events.filter((e) => (!type || e.type === type) && (!person || actorOf(e) === person));
  const filtering = !!(type || person);

  return (
    <div className="work-activity">
      <div className="work-board-toolbar">
        {itemForm}
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
                  {e.itemKey
                    ? <><Link to={`/work/item/${e.itemKey}`} className="work-card-key">{e.itemKey}</Link>{' '}</>
                    : <><span className="work-card-key work-muted">{project.key}</span>{' '}</>}
                  <EventActor event={e} /><span className="work-event-text">{t.text}</span>
                  <time className="work-event-time" dateTime={e.at}>{whenText(e.at)}</time>
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
