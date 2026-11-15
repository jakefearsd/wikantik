// Turns a WorkEvent into the sentence the timeline shows. Pure and total: an unknown type renders generically
// (later phases add types) and a missing field never throws. People are shown by the display names the server
// resolves (fromValueName / toValueName); a login is never a fallback.

const words = (s) => String(s).replace(/_/g, ' ');
const orNone = (v) => (v == null || v === '' ? 'none' : String(v));

function person(name, raw) {
  if (name) return name;
  return raw ? 'someone' : 'nobody';
}

function splitHandoff(value) {
  const v = value || '';
  const bar = v.indexOf('|');
  return bar < 0 ? { status: v, assignee: '' } : { status: v.slice(0, bar), assignee: v.slice(bar + 1) };
}

function splitLink(value) {
  const v = value || '';
  const colon = v.indexOf(':');
  return colon < 0 ? v : `${v.slice(0, colon)} ${v.slice(colon + 1)}`;
}

/** @returns {{ text: string, note: string|null, hasDiff?: boolean, prominentNote?: boolean }} */
export function eventText(e) {
  const ev = e || {};
  const note = ev.note || null;
  switch (ev.type) {
    case 'created': return { text: 'created this item', note };
    case 'edited': return { text: 'edited the title or text', note, hasDiff: true };
    case 'status': return { text: `status ${words(orNone(ev.fromValue))} → ${words(orNone(ev.toValue))}`, note };
    case 'assigned':
      return {
        text: `assigned ${person(ev.fromValueName, ev.fromValue)} → ${person(ev.toValueName, ev.toValue)}`, note,
      };
    case 'handoff': {
      const from = splitHandoff(ev.fromValue);
      const to = splitHandoff(ev.toValue);
      return {
        text: `handed off: status ${words(orNone(from.status))} → ${words(orNone(to.status))}, assignee `
          + `${person(ev.fromValueName, from.assignee)} → ${person(ev.toValueName, to.assignee)}`,
        note,
        prominentNote: true,
      };
    }
    case 'field': return { text: `changed ${words(ev.note || 'a field')} ${orNone(ev.fromValue)} → ${orNone(ev.toValue)}`, note: null };
    case 'commented': return { text: 'commented', note };
    case 'comment_edited': return { text: 'edited a comment', note };
    case 'comment_removed': return { text: 'removed a comment', note };
    case 'linked': return { text: `linked ${splitLink(ev.toValue)}`, note };
    case 'unlinked': return { text: `removed link ${splitLink(ev.fromValue)}`, note };
    default: {
      if (!ev.type) return { text: 'did something', note };
      const label = words(ev.type);
      const hasValues = ev.fromValue != null || ev.toValue != null;
      return { text: hasValues ? `${label}: ${orNone(ev.fromValue)} → ${orNone(ev.toValue)}` : label, note };
    }
  }
}
