// JS port of wikantik-main com.wikantik.markdown.extensions.fences.FenceInfo. The two are held to the same
// behaviour by the shared fixture (src/utils/__fixtures__/renderedFences.json, `infoStrings`).
//
// Whitespace parity (controller Ruling D): Java trims with String.trim() — every char <= U+0020 — and splits on
// the ASCII `\s` class, so this port does exactly that and never uses JS's Unicode `\s`.
const SPLIT = /[ \t\n\v\f\r]+/;

/** String.trim(): drops every char <= U+0020 at both ends (a loop, so it can never backtrack). */
function javaTrim(s) {
  let start = 0;
  let end = s.length;
  while (start < end && s.charCodeAt(start) <= 0x20) start++;
  while (end > start && s.charCodeAt(end - 1) <= 0x20) end--;
  return s.slice(start, end);
}

/**
 * Parses a fenced code block's info string (e.g. `mermaid example title=x`).
 *
 * @param {string|null|undefined} info
 * @returns {{ language: string, example: boolean, modifiers: string[] }} the first word lower-cased as
 *   `language` (`''` when absent), `example` when any later word is `example`, and the later words lower-cased.
 */
export function parseFenceInfo(info) {
  const stripped = javaTrim(String(info ?? ''));
  if (!stripped) return { language: '', example: false, modifiers: [] };
  const words = stripped.split(SPLIT).map((w) => w.toLowerCase());
  const modifiers = words.slice(1);
  return { language: words[0], example: modifiers.includes('example'), modifiers };
}
