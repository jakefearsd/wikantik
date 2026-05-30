/**
 * Estimate reading time for a markdown or plain-text string.
 *
 * Steps:
 *  1. Strip leading YAML frontmatter (--- ... ---).
 *  2. Strip fenced code blocks (``` ... ```).
 *  3. Count whitespace-delimited tokens as words.
 *  4. minutes = Math.max(1, Math.ceil(words / 200)), except when words === 0
 *     where both words and minutes are 0.
 *
 * @param {string} text - Markdown or plain text to measure.
 * @returns {{ words: number, minutes: number }}
 */
export function readingTime(text) {
  if (!text || typeof text !== 'string') return { words: 0, minutes: 0 };

  // Strip HTML tags (handles contentHtml fallback)
  let cleaned = text.replace(/<[^>]+>/g, ' ');

  // Strip leading YAML frontmatter block: --- ... ---
  cleaned = cleaned.replace(/^---[\s\S]*?^---\s*/m, '');

  // Strip fenced code blocks: ``` ... ``` (with optional language tag)
  cleaned = cleaned.replace(/```[\s\S]*?```/g, ' ');

  // Split on whitespace and count non-empty tokens
  const words = cleaned.trim().split(/\s+/).filter((w) => w.length > 0).length;

  if (words === 0) return { words: 0, minutes: 0 };

  const minutes = Math.max(1, Math.ceil(words / 200));
  return { words, minutes };
}
