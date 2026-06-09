const FRONTMATTER_RE = /^---\r?\n([\s\S]*?\r?\n)?---[ \t]*\r?\n\r?\n?/;

const ISO_DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
const NEEDS_QUOTE_RE = /^['"#>|`*&!?{}[\],]|: /;

/**
 * Serializes a metadata object to a YAML string (no --- delimiters).
 * Handles strings, numbers, arrays of strings.
 * Quoting rules:
 *   - ISO dates (YYYY-MM-DD) → single-quoted
 *   - Strings containing ': ' or '#' or starting with special chars → single-quoted
 *   - Arrays → YAML block sequence (- item per line)
 *   - Empty arrays → key: []
 *   - null/undefined values → omitted
 */
export function metadataToYaml(metadata) {
  if (!metadata || typeof metadata !== 'object') return '';
  return emitMapEntries(metadata, 0).join('\n');
}

function isPlainObject(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

function scalarToYaml(value) {
  const str = String(value);
  if (ISO_DATE_RE.test(str) || NEEDS_QUOTE_RE.test(str)) {
    return `'${str.replace(/'/g, "''")}'`;
  }
  return str;
}

// Block-style YAML for a map's entries at `indent` spaces. Scalars and string arrays match the
// original (flat) output exactly; nested objects and arrays-of-objects (e.g. `relations:`) are
// serialized recursively so the Raw-YAML break-glass view round-trips without dropping them.
function emitMapEntries(obj, indent) {
  const lines = [];
  for (const [key, value] of Object.entries(obj)) {
    if (value === null || value === undefined) continue;
    lines.push(...emitKeyValue(key, value, indent));
  }
  return lines;
}

function emitKeyValue(key, value, indent) {
  const pad = ' '.repeat(indent);
  if (Array.isArray(value)) {
    if (value.length === 0) return [`${pad}${key}: []`];
    const lines = [`${pad}${key}:`];
    for (const item of value) lines.push(...emitSeqItem(item, indent));
    return lines;
  }
  if (isPlainObject(value)) {
    const inner = emitMapEntries(value, indent + 2);
    return inner.length ? [`${pad}${key}:`, ...inner] : [`${pad}${key}: {}`];
  }
  return [`${pad}${key}: ${scalarToYaml(value)}`];
}

function emitSeqItem(item, indent) {
  const pad = ' '.repeat(indent);
  if (isPlainObject(item)) {
    const entries = Object.entries(item).filter(([, v]) => v !== null && v !== undefined);
    if (!entries.length) return [`${pad}- {}`];
    const lines = [];
    entries.forEach(([k, v], i) => {
      const kvLines = emitKeyValue(k, v, indent + 2);
      if (i === 0) {
        // Hoist the first field onto the "- " marker (standard block-sequence style).
        kvLines[0] = `${pad}- ${kvLines[0].slice(indent + 2)}`;
      }
      lines.push(...kvLines);
    });
    return lines;
  }
  if (Array.isArray(item)) {
    const lines = [`${pad}-`];
    for (const sub of item) lines.push(...emitSeqItem(sub, indent + 2));
    return lines;
  }
  return [`${pad}- ${scalarToYaml(item)}`];
}

/**
 * Reconstructs full markdown content from metadata object + body string.
 * Returns `---\n{yaml}\n---\n\n{body}` when metadata has keys.
 * Returns body as-is when metadata is empty/null.
 */
export function reconstructContent(metadata, body) {
  if (!metadata || typeof metadata !== 'object') return body || '';
  const yaml = metadataToYaml(metadata);
  if (!yaml) return body || '';
  return `---\n${yaml}\n---\n\n${body || ''}`;
}

/**
 * Strips a YAML frontmatter block (--- delimited) from the start of content,
 * returning only the body portion. Returns content unchanged if no frontmatter present.
 */
export function stripFrontmatter(content) {
  if (!content) return content || '';
  return content.replace(FRONTMATTER_RE, '');
}

/**
 * Number of leading source lines that {@link stripFrontmatter} removes — i.e.
 * the line offset between a body (preview) line and the full-document line.
 * Derived from the actual matched prefix (which may also consume a trailing
 * blank line), so it stays exact rather than approximating from a line count.
 * Returns 0 when there is no frontmatter.
 */
export function frontmatterOffsetLines(content) {
  if (!content) return 0;
  const m = content.match(FRONTMATTER_RE);
  return m ? m[0].split('\n').length - 1 : 0;
}
