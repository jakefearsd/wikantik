import { Link } from 'react-router-dom';
import Badge from '../ui/Badge';
import Icon from '../ui/Icon';
import Select from '../ui/Select';
import WorkLabels from './WorkLabels';
import { formatPoints } from './sprintModel';
import { STATUSES, STATUS_LABELS, isOverdue } from './boardModel';
import { clockText, fullTime, isPast, useExpiryClock } from './workTime';

const CLAIMABLE = new Set(['ready', 'review']);

function formatDue(iso) {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}

/**
 * One board card. `draggable` and `onMove` are supplied only for viewers who may change the item;
 * the Move to… menu is the keyboard path for what drag does with a mouse.
 */
export default function WorkCard({ item, productKey, changes = null, draggable = false, onDragStart, onDragEnd, onMove, now: injectedNow }) {
  const now = useExpiryClock(item.claimedByAgent != null ? item.claimExpiresAt : null, injectedNow);
  const overdue = isOverdue(item);
  const claimed = item.claimedByAgent != null;
  const holder = item.claimedByName || 'an agent';
  const lapsed = claimed && isPast(item.claimExpiresAt, now);
  const available = !claimed && !!item.queue && CLAIMABLE.has(item.status);
  const moveOptions = STATUSES
    .filter((s) => s !== item.status && (item.level !== 'epic' || ['draft', 'in_progress', 'done', 'cancelled'].includes(s)))
    .map((s) => ({ value: s, label: STATUS_LABELS[s] }));
  return (
    <article
      className={`work-card${overdue ? ' overdue' : ''}${draggable ? ' work-card-draggable' : ''}`}
      draggable={draggable}
      data-item-key={item.key}
      onDragStart={draggable ? (e) => {
        if (e.dataTransfer) {
          e.dataTransfer.effectAllowed = 'move';
          e.dataTransfer.setData('text/plain', item.key);
        }
        onDragStart?.(item);
      } : undefined}
      onDragEnd={draggable ? () => onDragEnd?.(item) : undefined}
    >
      <div className="work-card-head">
        <Link to={`/work/item/${item.key}`} className="work-card-key" draggable={false}>{item.key}</Link>
        {item.level !== 'task' && <span className="work-card-level">{item.level}</span>}
        {item.blocked && <Badge variant="danger" title="Blocked by an open item">Blocked</Badge>}
        {item.type === 'bug' && <Badge variant="warning" title="Bug">Bug</Badge>}
      </div>
      <div className="work-card-title">{item.title}</div>
      {item.labels?.length > 0 && <WorkLabels labels={item.labels} max={3} />}
      <div className="work-card-meta">
        {productKey && <span className="work-card-product">{productKey}</span>}
        <span className="work-card-priority">P{item.priority}</span>
        {item.estimate != null && <span className="work-card-estimate">{formatPoints(item.estimate)} pts</span>}
        {item.dueOn && <span className="work-card-due" title={item.dueOn}>due {formatDue(item.dueOn)}</span>}
        {item.assigneeName && <span className="work-card-assignee">{item.assigneeName}</span>}
        {item.queue && <span className="work-chip work-card-queue">Queue · {item.queueName || 'a queue'}</span>}
        {claimed && (
          <span
            className={`work-card-claim${lapsed ? ' claim-expired' : ''}`}
            title={`Claimed ${fullTime(item.claimedAt)}; ${lapsed ? 'lapsed' : 'runs out'} ${fullTime(item.claimExpiresAt)}`}
            aria-label={`Claimed by ${holder}, ${lapsed ? 'claim lapsed' : 'until'} ${fullTime(item.claimExpiresAt)}`}
          >
            {lapsed
              ? `${holder} · claim lapsed ${clockText(item.claimExpiresAt, now)}`
              : `${holder} · until ${clockText(item.claimExpiresAt, now)}`}
          </span>
        )}
        {available && <span className="work-card-available">Available to agents</span>}
        {changes?.openChanges > 0 && (
          <span className="work-card-changes">
            {changes.inReview > 0 ? 'in review' : `${changes.openChanges} open ${changes.openChanges === 1 ? 'change' : 'changes'}`}
          </span>
        )}
        {item.commentCount > 0 && (
          <span className="work-card-comments" aria-label={`${item.commentCount} comments`}>
            <Icon name="comment" size={12} /> {item.commentCount}
          </span>
        )}
      </div>
      {onMove && (
        <div className="work-card-move">
          <Select
            ariaLabel={`Move ${item.key} to…`}
            placeholder="Move to…"
            value=""
            options={moveOptions}
            onChange={(status) => status && onMove(item, status)}
          />
        </div>
      )}
    </article>
  );
}
