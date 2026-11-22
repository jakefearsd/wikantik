import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import Badge from '../ui/Badge';
import { addReportedComment, markItemRead } from '../../api/work';
import { announceUnread } from '../../hooks/useWorkUnread';
import { useDocumentTitle } from '../../hooks/useDocumentTitle';
import { ARCHIVED_TEXT } from './archived';
import { TYPE_OPTIONS } from './boardModel';
import { reporterEventLine, reporterStatusWords } from './eventText';
import WorkAttachments from './WorkAttachments';
import WorkBody from './WorkBody';
import WorkComments from './WorkComments';
import WorkWatchButton from './WorkWatchButton';
import { whenText } from './workTime';

/** The server's limit on a comment (it stays the authority). */
const COMMENT_LIMIT = 20000;
const dateText = (iso) => (iso ? new Date(iso).toLocaleDateString() : '');
const typeLabel = (t) => TYPE_OPTIONS.find((o) => o.value === t)?.label || '';

/**
 * An item as the person who filed it sees it: the reduced view the intake route returns and nothing more. It shows
 * the title, their own text, the status in plain words, the whole discussion, a short history and their files; it
 * has no field to change, no assignee, sprint, labels, estimate, parent, links, changes, deployments, board link or
 * project navigation, and never fetches the project's members (so no @-mention list can name them).
 * `onReload` re-reads the view after a comment; it resolves when the new view is in.
 */
export default function WorkReportedItem({ view, onReload }) {
  const archived = view.archived === true;
  useDocumentTitle(`${view.key} ${view.title}`);

  // Opening the item reads its notices, once. Nothing waits on it and a failure shows nothing.
  useEffect(() => {
    markItemRead(view.key)
      .then(() => announceUnread())
      .catch((e) => console.warn('WorkReportedItem: could not mark the notices read', e));
  }, [view.key]);

  const events = view.events || [];
  return (
    <article className="work-item work-item-reported">
      <nav className="work-crumbs" aria-label="Breadcrumb">
        <Link to="/work">Work</Link> <span aria-hidden="true">/</span> <span>{view.projectName || view.projectKey}</span>
      </nav>
      <header className="work-item-head">
        <span className="work-item-key">{view.key}</span>
        {typeLabel(view.type) && <span className="work-card-level">{typeLabel(view.type)}</span>}
        <Badge variant="default" title="Where this stands">{reporterStatusWords(view.status)}</Badge>
        <h1 className="work-heading">{view.title}</h1>
      </header>
      {archived && <p className="work-archived-banner" role="status">{ARCHIVED_TEXT}. It is read-only.</p>}
      <div className="work-item-watch"><WorkWatchButton itemKey={view.key} archived={archived} /></div>
      <div className="work-item-main">
        <section className="work-section" aria-label="Your request">
          <WorkBody html={view.bodyHtml} empty="No description." />
        </section>
        <WorkAttachments itemKey={view.key} canAttach={!archived} isLead={false} />
        <WorkComments
          comments={view.comments || []} members={[]} canComment={!archived} isLead={false} editable={false} limit={COMMENT_LIMIT}
          onAdd={(body) => addReportedComment(view.key, body).then(() => Promise.resolve(onReload?.())).then(() => true)}
        />
        <section className="work-section" aria-label="History">
          <h2 className="work-subheading">History</h2>
          {events.length === 0 ? <p className="work-muted">No history yet.</p> : (
            <ol className="work-timeline">
              {events.map((e, i) => (
                <li key={e.id ?? i} className={`work-event work-event-${e.type}`}>
                  <div className="work-event-line">
                    <span className="work-event-text">{reporterEventLine(e)}</span>
                    <time className="work-event-time" dateTime={e.at}>{whenText(e.at)}</time>
                  </div>
                </li>
              ))}
            </ol>
          )}
        </section>
        <dl className="work-facts">
          <dt>Reported by</dt><dd>{view.reporterName || 'You'}</dd>
          <dt>Created</dt><dd>{dateText(view.createdAt)}</dd>
          <dt>Updated</dt><dd>{dateText(view.updatedAt)}</dd>
        </dl>
      </div>
    </article>
  );
}
