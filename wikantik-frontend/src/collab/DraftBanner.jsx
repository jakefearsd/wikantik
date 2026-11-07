import { formatRelative } from '../utils/datetime';
import ContributorList from './ContributorList.jsx';
import { displayNames } from './remoteCursors.js';

/**
 * The shared draft has changes nobody has published yet (D3): "Unpublished changes by Ana and Raj — last edit 2h ago".
 * The user's own display name reads "you". Hidden when `draft.unpublished` is false. Each action is offered only when
 * its handler is given.
 */
export default function DraftBanner({ draft, selfName, onPublish, onDiscard, onViewChanges, onDismiss }) {
  if (!draft?.unpublished) return null;
  const self = typeof selfName === 'string' ? selfName.trim() : '';
  const names = displayNames(draft.contributors).map((n) => (self && n === self ? 'you' : n));
  const when = draft.lastEditAt ? formatRelative(draft.lastEditAt) : '';
  const actions = [
    ['Publish', onPublish, 'btn-primary'],
    ['View changes', onViewChanges, 'btn-secondary'],
    ['Discard', onDiscard, 'btn-ghost'],
    ['Dismiss', onDismiss, 'btn-ghost'],
  ].filter(([, handler]) => typeof handler === 'function');
  return (
    <div className="info-banner collab-draft-banner" role="status" data-testid="collab-draft-banner">
      <span className="collab-draft-banner-text">
        Unpublished changes
        {names.length > 0 && <> by <ContributorList names={names} /></>}
        {when && <> — last edit {when}</>}
      </span>
      {actions.length > 0 && (
        <span className="collab-draft-banner-actions">
          {actions.map(([label, handler, variant]) => (
            <button key={label} type="button" className={`btn ${variant} btn-sm`} onClick={handler}>{label}</button>
          ))}
        </span>
      )}
    </div>
  );
}
