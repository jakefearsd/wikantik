import { useEffect, useState } from 'react';
import Spinner from '../ui/Spinner';
import { listEvents, listRevisions } from '../../api/work';
import { eventText } from './eventText';
import { lineDiff } from './lineDiff';

const when = (iso) => (iso ? new Date(iso).toLocaleString() : '');
const revisionText = (r) => `${r.title}\n${r.body ?? ''}`;

function RevisionDiff({ before, after }) {
  const lines = lineDiff(revisionText(before), revisionText(after));
  return (
    <pre className="work-diff" data-testid="revision-diff" aria-label={`Changes in version ${after.version}`}>
      {lines.map((l, i) => (
        <span key={i} className={`work-diff-${l.type}`}>{l.type === 'add' ? '+ ' : l.type === 'del' ? '- ' : '  '}{l.text}{'\n'}</span>
      ))}
    </pre>
  );
}

/** The item's full history, oldest first. `refreshKey` reloads it after something changed the item. */
export default function WorkTimeline({ itemKey, refreshKey = 0 }) {
  const [state, setState] = useState({ events: null, error: null });
  const [open, setOpen] = useState(() => new Set());
  const [revs, setRevs] = useState(null);
  const currentRevs = revs && revs.for === refreshKey ? revs : null;

  useEffect(() => {
    let cancelled = false;
    listEvents(itemKey)
      .then((events) => { if (!cancelled) setState({ events: events || [], error: null }); })
      .catch((e) => {
        console.warn('WorkTimeline: could not load the history', e);
        if (!cancelled) setState({ events: null, error: e?.message || 'Could not load the history.' });
      });
    return () => { cancelled = true; };
  }, [itemKey, refreshKey]);

  // Revision versions have gaps (every update bumps the item's version, only text edits write a revision), so
  // an edit is matched to a revision by order: the k-th `edited` event is the (k+1)-th revision, ascending.
  const hasEdits = !!state.events && state.events.some((e) => e.type === 'edited');
  const needRevs = hasEdits && !currentRevs;
  useEffect(() => {
    if (!needRevs) return undefined;
    let cancelled = false;
    listRevisions(itemKey)
      .then((rows) => {
        if (!cancelled) setRevs({ for: refreshKey, rows: [...(rows || [])].sort((a, b) => a.version - b.version) });
      })
      .catch((e) => {
        console.warn('WorkTimeline: could not load revisions', e);
        if (!cancelled) setRevs({ for: refreshKey, error: e?.message || 'Could not load the versions.' });
      });
    return () => { cancelled = true; };
  }, [needRevs, itemKey, refreshKey]);

  const toggle = (id) => setOpen((s) => {
    const next = new Set(s);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });

  if (state.error) return <div className="error-banner" role="alert">{state.error}</div>;
  if (!state.events) return <Spinner label="Loading history…" />;
  if (state.events.length === 0) return <p className="work-muted">No history yet.</p>;

  let editOrdinal = -1;
  return (
    <>
    {currentRevs?.error && <p className="work-muted">Changes could not be shown: {currentRevs.error}</p>}
    <ol className="work-timeline">
      {state.events.map((e) => {
        const t = eventText(e);
        const ordinal = t.hasDiff ? ++editOrdinal : -1;
        const after = ordinal >= 0 && currentRevs?.rows ? currentRevs.rows[ordinal + 1] : null;
        const before = after ? currentRevs.rows[ordinal] : null;
        const canDiff = !!(after && before);
        const shown = canDiff && open.has(e.id);
        return (
          <li key={e.id} className={`work-event work-event-${e.type}`}>
            <div className="work-event-line">
              <strong className="work-event-actor">{e.actorName || 'Someone'}</strong>{' '}
              <span className="work-event-text">{t.text}</span>
              <time className="work-event-time" dateTime={e.at}>{when(e.at)}</time>
              {canDiff && (
                <button type="button" className="work-link-button" aria-expanded={shown} onClick={() => toggle(e.id)}>
                  {shown ? 'Hide changes' : 'Show changes'}
                </button>
              )}
            </div>
            {t.note && <blockquote className={t.prominentNote ? 'work-handoff-note' : 'work-event-note'}>{t.note}</blockquote>}
            {shown && <RevisionDiff before={before} after={after} />}
          </li>
        );
      })}
    </ol>
    </>
  );
}
