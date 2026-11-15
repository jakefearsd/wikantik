import { useEffect, useState } from 'react';
import Spinner from '../ui/Spinner';
import { listEvents, listRevisions } from '../../api/work';
import { eventText } from './eventText';
import { lineDiff } from './lineDiff';

const when = (iso) => (iso ? new Date(iso).toLocaleString() : '');
const revisionText = (r) => `${r.title}\n${r.body ?? ''}`;

function RevisionDiff({ revs, ordinal }) {
  if (revs.error) return <p className="work-muted">{revs.error}</p>;
  // Creation is version 1, so the n-th edit (from 0) produced version n + 2.
  const after = revs.rows.find((r) => r.version === ordinal + 2);
  const before = revs.rows.find((r) => r.version === ordinal + 1);
  if (!after || !before) return <p className="work-muted">No earlier version to compare.</p>;
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
  const anyOpen = open.size > 0;
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

  // Revisions are fetched once a diff is first opened, and again after the item changes.
  const needRevs = anyOpen && !currentRevs;
  useEffect(() => {
    if (!needRevs) return undefined;
    let cancelled = false;
    listRevisions(itemKey)
      .then((rows) => { if (!cancelled) setRevs({ for: refreshKey, rows: rows || [] }); })
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
    <ol className="work-timeline">
      {state.events.map((e) => {
        const t = eventText(e);
        const ordinal = t.hasDiff ? ++editOrdinal : -1;
        const shown = open.has(e.id);
        return (
          <li key={e.id} className={`work-event work-event-${e.type}`}>
            <div className="work-event-line">
              <strong className="work-event-actor">{e.actorName || 'Someone'}</strong>{' '}
              <span className="work-event-text">{t.text}</span>
              <time className="work-event-time" dateTime={e.at}>{when(e.at)}</time>
              {t.hasDiff && (
                <button type="button" className="work-link-button" aria-expanded={shown} onClick={() => toggle(e.id)}>
                  {shown ? 'Hide changes' : 'Show changes'}
                </button>
              )}
            </div>
            {t.note && <blockquote className={t.prominentNote ? 'work-handoff-note' : 'work-event-note'}>{t.note}</blockquote>}
            {shown && (currentRevs ? <RevisionDiff revs={currentRevs} ordinal={ordinal} /> : <Spinner label="Loading changes…" />)}
          </li>
        );
      })}
    </ol>
  );
}
