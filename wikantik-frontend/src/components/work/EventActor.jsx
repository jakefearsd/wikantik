import { actorLabel, eventText } from './eventText';

/** Who did it: the name, with an "agent" badge for an agent. Nothing for a sentence that names its own subject. */
export default function EventActor({ event }) {
  if (eventText(event).standalone) return null;
  return (
    <>
      <strong className="work-event-actor">{actorLabel(event)}</strong>
      {event?.actorKind === 'agent' && <>{' '}<span className="work-agent-badge">agent</span></>}
      {' '}
    </>
  );
}
