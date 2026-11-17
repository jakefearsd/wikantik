// Turns a WorkEvent into the sentence the timeline shows. Pure and total: an unknown type renders generically
// (later phases add types) and a missing field never throws. People are shown by the display names the server
// resolves (fromValueName / toValueName); a login is never a fallback. Parent and product changes arrive as keys
// (the server resolves the stored ids), so "changed parent PLAT-3 → PLAT-7". Member and settings events belong to
// the project, not an item.

import { clockText } from './workTime';

const words = (s) => String(s).replace(/_/g, ' ');
const orNone = (v) => (v == null || v === '' ? 'none' : String(v));
/** A value for a generic line: a raw `agent:<id>` / `queue:<key>` is never printed, only its name or neutral words. */
function safeValue(v, name) {
  const raw = v == null || v === '' ? '' : String(v);
  if (raw.startsWith('agent:')) return name || 'an agent';
  if (raw.startsWith('queue:')) return name || 'a queue';
  return orNone(v);
}

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

const commit = (sha) => (sha ? ` at ${String(sha).slice(0, 8)}` : '');
const MERGED = {
  reviewed: 'merged a reviewed change', unreviewed: 'merged a change without review', direct: 'merged directly',
};
const until = (iso, at) => (clockText(iso, at) ? ` · until ${clockText(iso, at)}` : '');
const statusWords = (v) => words(orNone(v));

function assignedText(ev) {
  const to = target(ev.toValueName, ev.toValue);
  const from = target(ev.fromValueName, ev.fromValue);
  if (!ev.toValue) return `unassigned this (was ${from})`;
  if (!ev.fromValue) return `assigned this to ${to}`;
  return `reassigned this to ${to} (was ${from})`;
}

/**
 * `released` carries its reason in `note`. The server sends no field that tells a consequence (the actor moved,
 * reassigned, removed or retired something and the claim ended with it) from a person's own free-text release note, so
 * the six reason keywords are read as consequences; any other note is a deliberate release.
 */
function releasedText(ev) {
  const holderId = (ev.fromValue || '').startsWith(AGENT) ? ev.fromValue.slice(AGENT.length) : '';
  const holder = ev.fromValueName || 'an agent';
  const own = ev.actorKind === 'agent' && (holderId && ev.agentId != null ? String(ev.agentId) === holderId
    : !!ev.actorName && ev.actorName === ev.fromValueName);
  const claim = own ? 'its claim' : `${holder}'s claim`;
  const now = ` (now ${statusWords(ev.toValue)})`;
  switch (ev.note) {
    case 'moved':
      return { text: own ? `moved this item, ending its own claim${now}` : `moved this item, ending ${claim}${now}`, note: null };
    case 'reassigned':
      return {
        text: own ? `handed this item on, ending its own claim${now}` : `reassigned this item, ending ${claim}${now}`, note: null,
      };
    case 'access removed': return { text: `removed ${holder}'s access to this project, ending its claim${now}`, note: null };
    case 'project archived': return { text: `archived this project, ending ${claim}${now}`, note: null };
    case 'agent retired': return { text: `retired ${holder}, ending its claim${now}`, note: null };
    // By a person: a membership change or a change of sponsor took the ground from the agent. By the agent itself:
    // the sweep found its sponsor without standing (an account, a group or the admin role changed elsewhere).
    case 'sponsor access removed':
      return {
        text: own ? `lost its claim because its sponsor lost access${now}`
          : `changed the access of ${holder}'s sponsor, ending its claim${now}`,
        note: null,
      };
    default: {
      const back = ev.toValue ? ` (back to ${statusWords(ev.toValue)})` : '';
      return { text: `released ${claim}${back}`, note: ev.note && ev.note !== 'released' ? ev.note : null };
    }
  }
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
    case 'change_opened': return { text: `opened a change on ${orNone(ev.toValue)}${commit(ev.note)}`, note: null };
    case 'change_updated': return { text: `updated a change${commit(ev.toValue)}`, note: null };
    case 'review_requested': return { text: `asked for a review of ${orNone(ev.toValue)}${commit(ev.note)}`, note: null };
    case 'change_reviewed': return {
      text: ev.toValue === 'approved' ? 'approved a change' : 'sent a change back for more work', note, prominentNote: !!note,
    };
    case 'change_merged': return { text: `${MERGED[ev.toValue] || 'merged a change'}${commit(ev.note)}`, note: null };
    case 'change_abandoned': return { text: `abandoned the change on ${orNone(ev.toValue)}`, note };
    case 'acknowledged': return { text: 'acknowledged that an agent closed this with no person in the path', note: null };
    case 'member': return { text: memberText(ev), note: null };
    case 'settings': return { text: settingsText(ev), note: null };
    default: {
      if (!ev.type) return { text: 'did something', note };
      const label = words(ev.type);
      const hasValues = ev.fromValue != null || ev.toValue != null;
      return {
        text: hasValues ? `${label}: ${safeValue(ev.fromValue, ev.fromValueName)} → ${safeValue(ev.toValue, ev.toValueName)}` : label,
        note,
      };
    }
  }
}

/** The whole line as plain text: actor, sentence and a hand-off's note. For tests, titles and screen-reader labels. */
export function eventSentence(e) {
  const t = eventText(e);
  const head = t.standalone ? t.text : `${actorLabel(e)} ${t.text}`;
  return t.prominentNote && t.note ? `${head} — '${t.note}'` : head;
}
