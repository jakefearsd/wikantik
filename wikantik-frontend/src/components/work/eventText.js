// Turns a WorkEvent into the sentence the timeline shows. Pure and total: an unknown type renders generically
// (later phases add types) and a missing field never throws. People are shown by the display names the server
// resolves (fromValueName / toValueName); a login is never a fallback. Parent and product changes arrive as keys
// (the server resolves the stored ids), so "changed parent PLAT-3 → PLAT-7". Member and settings events belong to
// the project, not an item.

import { clockText } from './workTime';

const words = (s) => String(s).replace(/_/g, ' ');
const orNone = (v) => (v == null || v === '' ? 'none' : String(v));

const QUEUE = 'queue:';
const AGENT = 'agent:';

/**
 * A person, queue or agent as the server named it. A queue value is `queue:<key>` and an agent value `agent:<id>`: the
 * name comes from the `…Name` twin and a missing twin gets a neutral word, never the raw key, id or a login.
 */
function target(name, raw) {
  if (raw && raw.startsWith(QUEUE)) return name ? `Queue · ${name}` : 'a queue';
  if (raw && raw.startsWith(AGENT)) return name || 'an agent';
  if (name) return name;
  return raw ? 'someone' : 'nobody';
}

function splitHandoff(value) {
  const v = value || '';
  const bar = v.indexOf('|');
  return bar < 0 ? { status: v, assignee: '' } : { status: v.slice(0, bar), assignee: v.slice(bar + 1) };
}

/** The sentence's subject: the server's name for the actor, or neutral words for one it could not name. */
export function actorLabel(e) {
  const ev = e || {};
  if (ev.actorName) return ev.actorName;
  return ev.actorKind === 'agent' ? 'An agent' : 'Someone';
}

const until = (iso, at) => (clockText(iso, at) ? ` · until ${clockText(iso, at)}` : '');
const statusWords = (v) => words(orNone(v));

function assignedText(ev) {
  const to = target(ev.toValueName, ev.toValue);
  const from = target(ev.fromValueName, ev.fromValue);
  if (!ev.toValue) return `unassigned this (was ${from})`;
  if (!ev.fromValue) return `assigned this to ${to}`;
  return `reassigned this to ${to} (was ${from})`;
}

const RELEASE_REASONS = {
  moved: 'the item was moved',
  reassigned: 'the item was reassigned',
  'access removed': 'its access was removed',
  'agent retired': 'the agent was retired',
};

function releasedText(ev) {
  const holderId = (ev.fromValue || '').startsWith(AGENT) ? ev.fromValue.slice(AGENT.length) : '';
  const holder = ev.fromValueName || 'an agent';
  const own = ev.actorKind === 'agent' && (holderId && ev.agentId != null ? String(ev.agentId) === holderId
    : !!ev.actorName && ev.actorName === ev.fromValueName);
  const claim = own ? 'its claim' : `${holder}'s claim`;
  const reason = RELEASE_REASONS[ev.note];
  if (reason) return { text: `released ${claim} because ${reason} (now ${statusWords(ev.toValue)})`, note: null };
  const back = ev.toValue ? ` (back to ${statusWords(ev.toValue)})` : '';
  return { text: `released ${claim}${back}`, note: ev.note && ev.note !== 'released' ? ev.note : null };
}

/** A member event value `kind:principal:role`, split at the first and last colon (a name may hold a colon). */
function splitMember(value) {
  const v = value || '';
  const first = v.indexOf(':');
  const last = v.lastIndexOf(':');
  if (first < 0 || last <= first) return null;
  return { kind: v.slice(0, first), role: v.slice(last + 1) };
}

function memberText(ev) {
  const from = splitMember(ev.fromValue);
  const to = splitMember(ev.toValue);
  const side = to || from;
  if (!side) return 'changed the members';
  const name = (to ? ev.toValueName : ev.fromValueName) || (side.kind === 'agent' ? 'an agent' : 'someone');
  const who = side.kind === 'group' || side.kind === 'agent' ? `${side.kind} ${name}` : name;
  if (!from) return `added ${who} as ${to.role}`;
  if (!to) return `removed ${who} (${from.role})`;
  return `changed ${who}'s role ${from.role} → ${to.role}`;
}

function settingsText(ev) {
  switch (ev.note) {
    case 'archived': return 'archived the project';
    case 'name': return `renamed the project ${orNone(ev.fromValue)} → ${orNone(ev.toValue)}`;
    case 'description': return 'changed the project description';
    case 'default_autonomy': return `changed the default autonomy ${words(orNone(ev.fromValue))} → ${words(orNone(ev.toValue))}`;
    default: return `changed the project ${words(ev.note || 'settings')}`;
  }
}

function splitLink(value) {
  const v = value || '';
  const colon = v.indexOf(':');
  return colon < 0 ? v : `${v.slice(0, colon)} ${v.slice(colon + 1)}`;
}

/**
 * @returns {{ text: string, note: string|null, hasDiff?: boolean, prominentNote?: boolean, standalone?: boolean }}
 * `text` follows the actor ("Jane Doe" + "moved this ..."); a `standalone` text is a whole sentence that names its own
 * subject, so the actor is not put in front of it.
 */
export function eventText(e) {
  const ev = e || {};
  const note = ev.note || null;
  switch (ev.type) {
    case 'created': return { text: 'created this item', note };
    case 'edited': return { text: 'edited the title or text', note, hasDiff: true };
    case 'status': return { text: `status ${words(orNone(ev.fromValue))} → ${words(orNone(ev.toValue))}`, note };
    case 'assigned': return { text: assignedText(ev), note };
    case 'handoff': {
      const from = splitHandoff(ev.fromValue);
      const to = splitHandoff(ev.toValue);
      return {
        text: `handed this to ${target(ev.toValueName, to.assignee)}: ${statusWords(from.status)} → ${statusWords(to.status)}`,
        note,
        prominentNote: true,
      };
    }
    case 'claimed': {
      const change = ev.fromValue && ev.toValue && ev.fromValue !== ev.toValue
        ? ` (${statusWords(ev.fromValue)} → ${statusWords(ev.toValue)})` : '';
      return { text: `claimed this${change}${until(ev.note, ev.at)}`, note: null };
    }
    case 'claim_renewed': return { text: `renewed its claim${until(ev.toValue, ev.at)}`, note: null };
    case 'released': return releasedText(ev);
    case 'claim_expired': {
      const back = ev.toValue ? ` — back to ${statusWords(ev.toValue)}` : '';
      return { text: `claim by ${ev.actorName || 'an agent'} expired${back}`, note: null, standalone: true };
    }
    case 'field': return { text: `changed ${words(ev.note || 'a field')} ${orNone(ev.fromValue)} → ${orNone(ev.toValue)}`, note: null };
    case 'commented': return { text: 'commented', note };
    case 'comment_edited': return { text: 'edited a comment', note };
    case 'comment_removed': return { text: 'removed a comment', note };
    case 'linked': return { text: `linked ${splitLink(ev.toValue)}`, note };
    case 'unlinked': return { text: `removed link ${splitLink(ev.fromValue)}`, note };
    case 'member': return { text: memberText(ev), note: null };
    case 'settings': return { text: settingsText(ev), note: null };
    default: {
      if (!ev.type) return { text: 'did something', note };
      const label = words(ev.type);
      const hasValues = ev.fromValue != null || ev.toValue != null;
      return { text: hasValues ? `${label}: ${orNone(ev.fromValue)} → ${orNone(ev.toValue)}` : label, note };
    }
  }
}

/** The whole line as plain text: actor, sentence and a hand-off's note. For tests, titles and screen-reader labels. */
export function eventSentence(e) {
  const t = eventText(e);
  const head = t.standalone ? t.text : `${actorLabel(e)} ${t.text}`;
  return t.prominentNote && t.note ? `${head} — '${t.note}'` : head;
}
