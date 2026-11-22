import { Link } from 'react-router-dom';

/** "Assigned by codex@docker2 (agent)" / "Mentioned by Jane Doe": who put the item on the viewer's plate. */
function reasonText(w) {
  if (!w) return null;
  const verb = w.reason === 'mentioned' ? 'Mentioned' : 'Assigned';
  const who = w.byName || 'someone';
  return `${verb} by ${who}${w.byKind === 'agent' ? ' (agent)' : ''}`;
}

const needsAck = (i) => i.waiting?.reason === 'acknowledge';
const outsidePolicy = (i) => i.waiting?.reason === 'change_outside_policy';

/**
 * The "Waiting on you" lane. Each item says who handed it over (display name only; the server sends a login to admins
 * as `by`, which is never shown) and the hand-off note. `suffix(item)` adds a trailing element, e.g. the project key.
 * Items an agent closed with no person in the path form their own group, "Done without you", each with an
 * Acknowledge button when `onAcknowledge(item)` is given. Items whose waiting reason is `change_outside_policy` (a
 * merge on the code host outside the project's policy) are a third group that links to the item: the flag is
 * acknowledged on the change itself, by a lead.
 */
export default function WaitingLane({ items, suffix, as: Tag = 'div', onAcknowledge, error = null }) {
  const waiting = items.filter((i) => !needsAck(i) && !outsidePolicy(i));
  const unattended = items.filter(needsAck);
  const flagged = items.filter(outsidePolicy);
  return (
    <Tag className="work-waiting" data-testid="waiting-lane">
      {waiting.length > 0 && (
        <>
          <strong>Waiting on you ({waiting.length})</strong>
          <ul className="work-waiting-list">
            {waiting.map((i) => {
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
        </>
      )}
      {flagged.length > 0 && (
        <div data-testid="outside-policy">
          <strong>Merged outside policy ({flagged.length})</strong>
          <span className="work-muted"> · merged on the code host without review, or committed directly where that is not allowed</span>
          <ul className="work-waiting-list">
            {flagged.map((i) => (
              <li key={`flag-${i.id}`}>
                <Link to={`/work/item/${i.key}`}>{i.key}</Link> {i.title}
                {suffix?.(i)}
              </li>
            ))}
          </ul>
        </div>
      )}
      {unattended.length > 0 && (
        <div data-testid="done-without-you">
          <strong>Done without you ({unattended.length})</strong>
          <span className="work-muted"> · closed by an agent with no person in the path</span>
          {error && <div className="work-form-error" role="alert">{error}</div>}
          <ul className="work-waiting-list">
            {unattended.map((i) => (
              <li key={`ack-${i.id}`}>
                <Link to={`/work/item/${i.key}`}>{i.key}</Link> {i.title}
                {suffix?.(i)}
                {onAcknowledge && (
                  <button type="button" className="btn btn-sm work-waiting-ack"
                    aria-label={`Acknowledge ${i.key}`} onClick={() => onAcknowledge(i)}>Acknowledge</button>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
    </Tag>
  );
}
