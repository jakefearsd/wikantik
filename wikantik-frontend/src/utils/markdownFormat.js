/**
 * Pure formatting functions for the markdown editor toolbar.
 *
 * Each function receives `state = { text, selStart, selEnd }` and returns
 * a new `{ text, selStart, selEnd }` describing the updated document and
 * the desired selection after applying the format.
 */

/**
 * Wrap/unwrap the selection with a symmetric marker (e.g. '**', '*', '`').
 *
 * - If the selection is already wrapped with `marker`, unwrap it.
 * - Otherwise wrap it.  If there is no selection, place the markers and put
 *   the cursor between them.
 *
 * @param {{ text: string, selStart: number, selEnd: number }} state
 * @param {string} marker - e.g. '**', '*', '`'
 * @returns {{ text: string, selStart: number, selEnd: number }}
 */
export function toggleWrap(state, marker) {
  const { text, selStart, selEnd } = state;
  const selected = text.slice(selStart, selEnd);
  const ml = marker.length;

  // Check if already wrapped
  const before = text.slice(selStart - ml, selStart);
  const after = text.slice(selEnd, selEnd + ml);

  if (before === marker && after === marker) {
    // Unwrap: remove the surrounding markers
    const newText = text.slice(0, selStart - ml) + selected + text.slice(selEnd + ml);
    return {
      text: newText,
      selStart: selStart - ml,
      selEnd: selEnd - ml,
    };
  }

  // Check if selection itself starts and ends with the marker
  if (selected.startsWith(marker) && selected.endsWith(marker) && selected.length >= ml * 2) {
    const inner = selected.slice(ml, selected.length - ml);
    const newText = text.slice(0, selStart) + inner + text.slice(selEnd);
    return {
      text: newText,
      selStart,
      selEnd: selStart + inner.length,
    };
  }

  // Wrap the selection
  const newText = text.slice(0, selStart) + marker + selected + marker + text.slice(selEnd);
  return {
    text: newText,
    selStart: selStart + ml,
    selEnd: selEnd + ml,
  };
}

/**
 * Toggle a line prefix (e.g. '## ', '- ') on every line in the selection.
 *
 * - If ALL selected lines already start with `prefix`, remove it.
 * - Otherwise add it to lines that don't already have it.
 *
 * @param {{ text: string, selStart: number, selEnd: number }} state
 * @param {string} prefix - e.g. '## ', '- '
 * @returns {{ text: string, selStart: number, selEnd: number }}
 */
export function toggleLinePrefix(state, prefix) {
  const { text, selStart, selEnd } = state;

  // Find the start of the first selected line
  const lineStart = text.lastIndexOf('\n', selStart - 1) + 1;
  // Find the end of the last selected line
  let lineEnd = text.indexOf('\n', selEnd);
  if (lineEnd === -1) lineEnd = text.length;

  const selectedBlock = text.slice(lineStart, lineEnd);
  const lines = selectedBlock.split('\n');

  const allHavePrefix = lines.every(line => line.startsWith(prefix));

  let delta = 0;
  const newLines = lines.map(line => {
    if (allHavePrefix) {
      delta -= prefix.length;
      return line.slice(prefix.length);
    } else {
      if (!line.startsWith(prefix)) {
        delta += prefix.length;
        return prefix + line;
      }
      return line;
    }
  });

  const newBlock = newLines.join('\n');
  const newText = text.slice(0, lineStart) + newBlock + text.slice(lineEnd);

  // Adjust selection to cover the same lines
  const newSelStart = Math.max(lineStart, selStart + (allHavePrefix ? -prefix.length : prefix.length));
  const newSelEnd = selEnd + delta;

  return {
    text: newText,
    selStart: Math.max(lineStart, newSelStart),
    selEnd: Math.max(lineStart, newSelEnd),
  };
}

/**
 * Turn the selection into a markdown link `[selected text](url)`.
 * The cursor/selection is placed on `url` so the user can type the URL.
 *
 * If there is no selection, inserts `[text](url)` with the selection on `text`.
 *
 * @param {{ text: string, selStart: number, selEnd: number }} state
 * @returns {{ text: string, selStart: number, selEnd: number }}
 */
/**
 * Leading newline needed so a block insertion starts on its own line: none
 * when at the document start or already after a newline, otherwise '\n'.
 */
function blockLead(text, selStart) {
  return (selStart === 0 || text[selStart - 1] === '\n') ? '' : '\n';
}

/**
 * Insert a GFM table skeleton at the cursor (replacing any selection). The
 * first header cell is selected so the user can type over it immediately.
 *
 * @param {{ text: string, selStart: number, selEnd: number }} state
 * @returns {{ text: string, selStart: number, selEnd: number }}
 */
export function insertTable(state) {
  const { text, selStart, selEnd } = state;
  const lead = blockLead(text, selStart);
  const block = '| Header 1 | Header 2 |\n| --- | --- |\n| Cell 1 | Cell 2 |\n';
  const newText = text.slice(0, selStart) + lead + block + text.slice(selEnd);
  // 'Header 1' starts after the leading newline and the opening '| '.
  const cellStart = selStart + lead.length + 2;
  return { text: newText, selStart: cellStart, selEnd: cellStart + 'Header 1'.length };
}

/**
 * Insert a fenced code block. Any selection becomes the block body; the
 * `language` token is selected so the user can name the language (or delete it).
 *
 * @param {{ text: string, selStart: number, selEnd: number }} state
 * @returns {{ text: string, selStart: number, selEnd: number }}
 */
export function insertCodeBlock(state) {
  const { text, selStart, selEnd } = state;
  const selected = text.slice(selStart, selEnd);
  const lead = blockLead(text, selStart);
  const block = '```language\n' + selected + '\n```\n';
  const newText = text.slice(0, selStart) + lead + block + text.slice(selEnd);
  // 'language' follows the leading newline and the opening ``` fence.
  const langStart = selStart + lead.length + 3;
  return { text: newText, selStart: langStart, selEnd: langStart + 'language'.length };
}

export function insertLink(state) {
  const { text, selStart, selEnd } = state;
  const selected = text.slice(selStart, selEnd);

  if (selected.length > 0) {
    // [selected](url) — selection lands on "url"
    const inserted = `[${selected}](url)`;
    const newText = text.slice(0, selStart) + inserted + text.slice(selEnd);
    // selStart + 1 (for '[') + selected.length + 2 (for '](') = start of 'url'
    const urlStart = selStart + 1 + selected.length + 2;
    const urlEnd = urlStart + 3; // length of 'url'
    return {
      text: newText,
      selStart: urlStart,
      selEnd: urlEnd,
    };
  } else {
    // No selection: insert [text](url) with selection on 'text'
    const inserted = '[text](url)';
    const newText = text.slice(0, selStart) + inserted + text.slice(selEnd);
    return {
      text: newText,
      selStart: selStart + 1,      // start of 'text'
      selEnd: selStart + 1 + 4,   // end of 'text'
    };
  }
}
