// Presentation of a `[{…}]` match as a compact chip. Decides nothing — the server interprets the markup.
const ACL = /^(ALLOW|DENY)\s+(\S+)\s+(\S[\s\S]*)$/i;
const PLUGIN_NAME = /^[A-Za-z_][\w.]*$/;
const MAX_FALLBACK = 40;

/** @param {string} source full match incl. `[{`, `}]` and an optional trailing `()` */
export function chipFor(source) {
  const inner = source.replace(/^\[\{/, '').replace(/\}\](?:\(\))?$/, '').trim();

  const acl = ACL.exec(inner);
  if (acl) {
    const principals = acl[3].split(',').map((p) => p.trim()).filter(Boolean).join(', ');
    const lead = acl[1].toUpperCase() === 'DENY' ? `deny ${acl[2]}` : acl[2];
    return { text: `${lead}: ${principals}`, cls: 'cm-lp-chip cm-lp-chip-acl' };
  }
  if (inner.startsWith('$')) return { text: inner, cls: 'cm-lp-chip cm-lp-chip-var' };

  const tokens = inner.split(/\s+/);
  if (/^INSERT$/i.test(tokens[0])) tokens.shift();
  const name = tokens[0] || '';
  if (PLUGIN_NAME.test(name) && !/^SET$/i.test(name)) {
    return { text: `⚙ ${name.split('.').pop()}`, cls: 'cm-lp-chip cm-lp-chip-plugin' };
  }
  const text = inner.length > MAX_FALLBACK ? `${inner.slice(0, MAX_FALLBACK)}…` : inner;
  return { text, cls: 'cm-lp-chip' };
}
