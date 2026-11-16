import { Link } from 'react-router-dom';

/** "Assigned by codex@docker2 (agent)" / "Mentioned by Jane Doe": who put the item on the viewer's plate. */
function reasonText(w) {
  if (!w) return null;
  const verb = w.reason === 'mentioned' ? 'Mentioned' : 'Assigned';
  const who = w.byName || 'someone';
  return `${verb} by ${who}${w.byKind === 'agent' ? ' (agent)' : ''}`;
}

/**
 * The "Waiting on you" lane. Each item says who handed it over (display name only; the server sends a login to admins
 * as `by`, which is never shown) and the hand-off note. `suffix(item)` adds a trailing element, e.g. the project key.
 */
export default function WaitingLane({ items, suffix, as: Tag = 'div' }) {
  return (
    <Tag className="work-waiting" data-testid="waiting-lane">
      <strong>Waiting on you ({items.length})</strong>
      <ul className="work-waiting-list">
        {items.map((i) => {
          const why = reasonText(i.waiting);
          return (
            <li key={i.id}>
              <Link to={`/work/item/${i.key}`}>{i.key}</Link> {i.title}
              {suffix?.(i)}
              {why && <span className="work-muted work-waiting-why"> · {why}</span>}
              {i.waiting?.note && <blockquote className="work-waiting-note">{i.waiting.note}</blockquote>}
            </li>
          );
        })}
      </ul>
    </Tag>
  );
}
