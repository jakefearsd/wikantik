import { htmlToMarkdown, wikiPageTarget, linkDestination } from './htmlToMarkdown';

const URL_RE = /^https?:\/\/\S+$/i;

/** The clipboard text when it is exactly one http(s) URL (surrounding whitespace ignored), else null. */
export function singleUrl(text) {
  const t = (text || '').trim();
  return URL_RE.test(t) ? t : null;
}

/**
 * What a paste into the editor should insert, or null to let CodeMirror paste the clipboard text as usual.
 *
 *   text / html  the clipboard's text/plain and text/html
 *   selection    the selected text the paste replaces ('' for a caret)
 *   inCode       the caret is inside code (fence, indented block, inline code) — always pasted verbatim
 *   plain        the user asked for a plain-text paste (Mod-Shift-V)
 *   origin       this wiki's origin, so links to its own pages become wiki links
 *
 * Returns { kind: 'link' | 'markdown', insert, droppedImages }.
 */
export function decidePaste({ text = '', html = '', selection = '', inCode = false, plain = false, origin = '' } = {}) {
  if (plain || inCode) return null;
  const url = singleUrl(text);
  if (url) {
    const label = selection.trim();
    if (!label || selection.includes('\n') || singleUrl(label)) return null;
    const [lead] = /^\s*/.exec(selection);
    const [trail] = /\s*$/.exec(selection);
    const target = linkDestination(wikiPageTarget(url, origin) ?? url);
    return { kind: 'link', insert: `${lead}[${label}](${target})${trail}`, droppedImages: 0 };
  }
  const converted = htmlToMarkdown(html, { origin });
  if (!converted) return null;
  let insert = converted.markdown;
  // An inline fragment ("…the <b>word</b> ") keeps the spaces at its edges, which conversion trims.
  if (converted.inline) insert = `${/^[ \t]*/.exec(text)[0]}${insert}${/[ \t]*$/.exec(text)[0]}`;
  return { kind: 'markdown', insert, droppedImages: converted.droppedImages };
}
