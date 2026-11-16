// Work comments use the wiki's mention token, `@[Display Name](user:<id>)` (utils/mentionTokens.js): the comment
// box's picker inserts it and the server resolves the id. The server renders each comment to sanitised HTML with
// every mention shown as `@Display Name` and lists those names; highlightMentions marks them in the rendered DOM
// with text nodes and elements only, so no HTML is ever injected.

const SKIP = new Set(['CODE', 'PRE', 'A']);
const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** Wraps each `@Name` (for the given names, longest first) in a `comment-mention-chip` span. Returns the count. */
export function highlightMentions(root, names) {
  const list = [...new Set((names || []).filter((n) => typeof n === 'string' && n))].sort((a, b) => b.length - a.length);
  if (!root || list.length === 0) return 0;
  const re = new RegExp(`@(?:${list.map(escapeRe).join('|')})(?![\\p{L}\\p{N}])`, 'gu');
  const doc = root.ownerDocument;
  const walker = doc.createTreeWalker(root, 4 /* NodeFilter.SHOW_TEXT */);
  const texts = [];
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    let skip = false;
    for (let p = n.parentNode; p && p !== root; p = p.parentNode) if (SKIP.has(p.nodeName)) { skip = true; break; }
    if (!skip) texts.push(n);
  }
  let count = 0;
  for (const node of texts) {
    const text = node.nodeValue;
    const parts = [];
    let last = 0;
    for (const m of text.matchAll(re)) {
      if (m.index > last) parts.push(doc.createTextNode(text.slice(last, m.index)));
      const chip = doc.createElement('span');
      chip.className = 'comment-mention-chip';
      chip.textContent = m[0];
      parts.push(chip);
      last = m.index + m[0].length;
      count += 1;
    }
    if (parts.length === 0) continue;
    if (last < text.length) parts.push(doc.createTextNode(text.slice(last)));
    node.replaceWith(...parts);
  }
  return count;
}
