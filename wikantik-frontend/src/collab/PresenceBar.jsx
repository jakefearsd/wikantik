import { colorOf, labelOf } from './remoteCursors.js';

const STATUS_LABELS = {
  live: 'Live', joining: 'Connecting', reconnecting: 'Reconnecting', resync: 'Resync needed', frozen: 'Page deleted',
  'auth-required': 'Sign in again',
};

const initials = (name) => {
  const parts = String(name ?? '').trim().split(/\s+/).filter(Boolean);
  if (!parts.length) return '?';
  return (parts.length === 1 ? parts[0].slice(0, 2) : parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
};

/** Who is editing this page right now. Display names only: a participant without one shows as a neutral placeholder. */
export default function PresenceBar({ self, presence, status }) {
  const others = Object.entries(presence ?? {}).map(([id, p]) => ({ participantId: id, displayName: p.displayName, color: p.color }));
  const people = [...(self ? [self] : []), ...others];
  return (
    <div className="collab-presence" role="status" aria-label="Collaborators">
      <ul className="collab-presence-list">
        {people.map((p) => (
          <li key={p.participantId} className="collab-presence-person">
            <span className="collab-presence-avatar" style={{ backgroundColor: colorOf(p.color) }}>{initials(labelOf(p.displayName))}</span>
            <span className="collab-presence-name" style={{ color: colorOf(p.color) }}>{labelOf(p.displayName)}</span>
          </li>
        ))}
      </ul>
      <span className="collab-presence-count">{people.length} editing</span>
      <span className={`collab-presence-state collab-presence-${status}`}>{STATUS_LABELS[status] ?? status}</span>
    </div>
  );
}
