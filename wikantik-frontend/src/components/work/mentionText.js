// Work comments carry plain `@login` mentions on the wire (the server resolves them against project members).
// People see and type display names; these helpers convert between the two and never show a login.

const SERVER_MENTION = /(?<![\w@.])@([A-Za-z0-9][A-Za-z0-9._-]*)/g;
const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** The member whose login `raw` names, tolerating trailing punctuation ("@ana-login."), or null. */
function memberFor(raw, byLogin) {
  for (let len = raw.length; len > 0; len--) {
    if (len < raw.length && !/[._-]/.test(raw[len])) continue;
    const candidate = raw.slice(0, len);
    if (Object.hasOwn(byLogin, candidate)) return { login: candidate, name: byLogin[candidate] };
  }
  return null;
}

/** login -> display name for user members. */
export function nameMap(members) {
  return Object.fromEntries((members || []).filter((m) => m.principalKind === 'user').map((m) => [m.principal, m.displayName]));
}

/** Splits a wire body into strings and { login, name } mentions of members. */
export function splitBody(body, byLogin) {
  const out = [];
  let last = 0;
  for (const m of (body || '').matchAll(SERVER_MENTION)) {
    const hit = memberFor(m[1], byLogin);
    if (!hit) continue;
    if (m.index > last) out.push(body.slice(last, m.index));
    out.push({ login: hit.login, name: hit.name });
    last = m.index + 1 + hit.login.length;
  }
  if (last < (body || '').length) out.push(body.slice(last));
  return out;
}

/** Wire text -> what the person edits: `@login` becomes `@Display Name`. */
export function toDisplay(body, byLogin) {
  return splitBody(body, byLogin).map((p) => (typeof p === 'string' ? p : `@${p.name}`)).join('');
}

/** What the person typed -> wire text: `@Display Name` becomes `@login` (one pass, longest names first). */
export function toWire(text, members) {
  const users = (members || []).filter((m) => m.principalKind === 'user' && m.displayName)
    .sort((a, b) => b.displayName.length - a.displayName.length);
  if (users.length === 0) return text;
  const loginOf = new Map(users.map((m) => [m.displayName, m.principal]));
  const re = new RegExp(`@(${users.map((m) => escapeRe(m.displayName)).join('|')})(?![\\w])`, 'g');
  return text.replace(re, (_, name) => `@${loginOf.get(name)}`);
}
