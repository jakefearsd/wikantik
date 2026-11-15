import { Link } from 'react-router-dom';
import Badge from '../ui/Badge';
import Icon from '../ui/Icon';
import Select from '../ui/Select';
import { STATUSES, STATUS_LABELS, isOverdue } from './boardModel';

function formatDue(iso) {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}

/**
 * One board card. `draggable` and `onMove` are supplied only for viewers who may change the item;
 * the Move to… menu is the keyboard path for what drag does with a mouse.
 */
export default function WorkCard({ item, productKey, draggable = false, onDragStart, onDragEnd, onMove }) {
  const overdue = isOverdue(item);
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
      </div>
      <div className="work-card-title">{item.title}</div>
      <div className="work-card-meta">
        {productKey && <span className="work-card-product">{productKey}</span>}
        <span className="work-card-priority">P{item.priority}</span>
        {item.dueOn && <span className="work-card-due" title={item.dueOn}>due {formatDue(item.dueOn)}</span>}
        {item.assigneeName && <span className="work-card-assignee">{item.assigneeName}</span>}
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
