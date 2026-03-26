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
  const lines = [];
  for (const [key, value] of Object.entries(metadata)) {
    if (value === null || value === undefined) continue;
    if (Array.isArray(value)) {
      if (value.length === 0) {
        lines.push(`${key}: []`);
      } else {
        lines.push(`${key}:`);
        for (const item of value) {
          lines.push(`- ${item}`);
        }
      }
    } else {
      const str = String(value);
      let serialized;
      if (ISO_DATE_RE.test(str) || NEEDS_QUOTE_RE.test(str)) {
        serialized = `'${str.replace(/'/g, "''")}'`;
      } else {
        serialized = str;
      }
      lines.push(`${key}: ${serialized}`);
    }
  }
  return lines.join('\n');
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
