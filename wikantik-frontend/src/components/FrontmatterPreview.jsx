import { useState } from 'react';
import { frontmatterLineCount } from '../utils/scrollSync';

/**
 * Compact, read-only view of the page's YAML frontmatter, pinned at the top of
 * the editor preview. The frontmatter is stripped from the rendered body, so
 * without this it is invisible in the preview. Collapsed to a one-line summary
 * by default (keeps the body preview spacious); expands to show the raw block.
 * Rendered verbatim — no YAML parsing — so it stays robust while the user is
 * mid-edit with temporarily invalid YAML.
 */
export default function FrontmatterPreview({ content }) {
  const [open, setOpen] = useState(false);

  const n = frontmatterLineCount(content);
  if (n === 0) return null;

  const raw = content.split('\n').slice(0, n).join('\n');
  // Count top-level keys (lines like `key:`), ignoring the `---` fences.
  const fields = raw.split('\n').filter(l => /^[A-Za-z0-9_-]+\s*:/.test(l)).length;

  return (
    <div className="editor-frontmatter" data-testid="editor-frontmatter">
      <button
        type="button"
        className="editor-frontmatter-toggle"
        aria-expanded={open}
        onClick={() => setOpen(o => !o)}
      >
        <span aria-hidden="true">{open ? '▾' : '▸'}</span>
        <span>Frontmatter</span>
        <span className="editor-frontmatter-count">{fields} field{fields === 1 ? '' : 's'}</span>
      </button>
      {open && <pre className="editor-frontmatter-raw">{raw}</pre>}
    </div>
  );
}
