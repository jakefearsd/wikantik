import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import EmptyState from '../ui/EmptyState';
import Spinner from '../ui/Spinner';
import { getInbox, markAllRead } from '../../api/work';
import { announceUnread } from '../../hooks/useWorkUnread';
import { useDocumentTitle } from '../../hooks/useDocumentTitle';
import { eventSentence, reporterEventLine } from './eventText';
import WorkLoadError from './WorkLoadError';
import WorkNotifyPrefs from './WorkNotifyPrefs';

const PAGE = 30;
/** A status move with no acting person (a lapsed claim) reads without an invented actor; every other line is as before. */
const inboxLine = (event) => (event.type === 'status' && !event.actorName && event.actorKind !== 'agent'
  ? reporterEventLine(event) : eventSentence(event));
const REASONS = {
  mentioned: 'Mentioned',
  assigned: 'Assigned',
  acknowledge: 'Needs acknowledging',
  claim_expired: 'Claim lapsed',
  webhook_disabled: 'Webhook switched off',
  watching: 'Watching',
};

const when = (iso) => {
  const d = iso ? new Date(iso) : null;
  return d && !Number.isNaN(d.getTime()) ? d.toLocaleString() : '';
};

function Notice({ n }) {
  const unread = !n.readAt;
  return (
    <li className={`work-notice${unread ? ' work-notice-unread' : ''}`} data-unread={unread ? 'true' : 'false'}>
      <div className="work-notice-head">
        {unread && <span className="work-notice-dot" role="img" aria-label="Unread" />}
        <span className="work-chip">{REASONS[n.reason] || 'Update'}</span>
        {n.itemKey
          ? <Link to={`/work/item/${n.itemKey}`}>{n.itemKey} {n.itemTitle}</Link>
          : n.projectKey && <Link to={`/work/${n.projectKey}`}>{n.projectKey}</Link>}
        <span className="work-muted work-notice-when">{when(n.createdAt)}</span>
      </div>
      <p className="work-notice-text">{n.event ? inboxLine(n.event) : ''}</p>
    </li>
  );
}

/** /work/inbox: what happened on the work the viewer watches, is assigned or is mentioned on, newest first. */
export default function WorkInbox() {
  useDocumentTitle('Inbox');
  const [state, setState] = useState({ notices: null, unread: 0, error: null, more: false });
  const [moreError, setMoreError] = useState(null);
  const [busy, setBusy] = useState(false);
  const seq = useRef(0);

  const load = useCallback(() => {
    const mine = ++seq.current;
    return getInbox({ limit: PAGE })
      .then((r) => {
        if (mine !== seq.current) return;
        const notices = r?.notices || [];
        setState({ notices, unread: r?.unread ?? 0, error: null, more: notices.length >= PAGE });
        announceUnread(r?.unread ?? 0);
      })
      .catch((error) => {
        console.warn('WorkInbox: could not load the inbox', error);
        // A failed reload keeps the last good list.
        if (mine === seq.current) setState((s) => ({ ...s, error }));
      });
  }, []);
  useEffect(() => { load(); }, [load]);

  function loadMore() {
    const last = state.notices[state.notices.length - 1];
    setBusy(true); setMoreError(null);
    getInbox({ before: last.id, limit: PAGE })
      .then((r) => {
        const older = r?.notices || [];
        setState((s) => ({ ...s, notices: [...s.notices, ...older], unread: r?.unread ?? s.unread, more: older.length >= PAGE }));
        if (typeof r?.unread === 'number') announceUnread(r.unread);
      })
      .catch((e) => {
        console.warn('WorkInbox: could not load older notices', e);
        setMoreError(e?.message || 'Could not load older notices.');
      })
      .finally(() => setBusy(false));
  }

  function readAll() {
    setBusy(true); setMoreError(null);
    markAllRead()
      .then(() => {
        const at = new Date().toISOString();
        setState((s) => ({ ...s, unread: 0, notices: (s.notices || []).map((n) => (n.readAt ? n : { ...n, readAt: at })) }));
        announceUnread(0);
      })
      .catch((e) => {
        console.warn('WorkInbox: could not mark everything read', e);
        setMoreError(e?.message || 'Could not mark everything read.');
      })
      .finally(() => setBusy(false));
  }

  const { notices, unread, error, more } = state;
  return (
    <div className="work-inbox">
      <h1 className="work-heading">Inbox</h1>
      <WorkNotifyPrefs />
      {notices == null && error && <WorkLoadError error={error} fallback="Could not load your inbox." onRetry={load} />}
      {notices == null && !error && <Spinner label="Loading…" />}
      {notices != null && (
        <>
          {error && (
            <div className="work-form-error" role="alert">
              {error.message || 'Could not refresh your inbox.'} <button type="button" className="btn btn-sm" onClick={load}>Retry</button>
            </div>
          )}
          <div className="work-actions">
            <span className="work-muted" role="status">{unread > 0 ? `${unread} unread` : 'All read'}</span>
            <button type="button" className="btn btn-sm" disabled={busy || unread === 0} onClick={readAll}>Mark all read</button>
          </div>
          {notices.length === 0
            ? <EmptyState message="Nothing yet. Items you watch, are assigned or are mentioned on show up here." />
            : <ul className="work-notice-list">{notices.map((n) => <Notice key={n.id} n={n} />)}</ul>}
          {moreError && <div className="work-form-error" role="alert">{moreError}</div>}
          {more && <button type="button" className="btn btn-sm" disabled={busy} onClick={loadMore}>Load more</button>}
        </>
      )}
    </div>
  );
}
