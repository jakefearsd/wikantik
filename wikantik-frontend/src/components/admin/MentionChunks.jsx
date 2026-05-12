import { useState, useEffect } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { api } from '../../api/client';
import PageLink from './PageLink';

// Rehype plugin: walks the rendered HAST and wraps every case-insensitive
// occurrence of `needle` in a <mark> element. Skips text inside <code>, <pre>,
// and <a> so we don't mangle code identifiers or URLs. Returns an identity
// transformer when needle is empty so the markdown still renders cleanly.
export function makeHighlightRehype(needle) {
  return () => (tree) => {
    if (!needle || !needle.trim()) return;
    const target = needle.trim().toLowerCase();
    const tlen = target.length;
    const visit = (node, skipMarking) => {
      if (!node) return;
      if (node.type === 'element') {
        const skip = skipMarking
          || node.tagName === 'code'
          || node.tagName === 'pre'
          || node.tagName === 'a'
          || node.tagName === 'mark';
        if (node.children) {
          const replaced = [];
          for (const child of node.children) {
            if (!skip && child.type === 'text' && child.value) {
              const text = child.value;
              const lower = text.toLowerCase();
              let cursor = 0;
              let idx = lower.indexOf(target, cursor);
              if (idx === -1) {
                replaced.push(child);
                continue;
              }
              while (idx !== -1) {
                if (idx > cursor) {
                  replaced.push({ type: 'text', value: text.slice(cursor, idx) });
                }
                replaced.push({
                  type: 'element',
                  tagName: 'mark',
                  properties: {},
                  children: [{ type: 'text', value: text.slice(idx, idx + tlen) }],
                });
                cursor = idx + tlen;
                idx = lower.indexOf(target, cursor);
              }
              if (cursor < text.length) {
                replaced.push({ type: 'text', value: text.slice(cursor) });
              }
            } else {
              visit(child, skip);
              replaced.push(child);
            }
          }
          node.children = replaced;
        }
        return;
      }
      if (node.type === 'root' && node.children) {
        for (const child of node.children) visit(child, skipMarking);
      }
    };
    visit(tree, false);
  };
}

// Block-friendly ReactMarkdown overrides for chunk bodies. We have full
// right-pane width, so paragraphs, lists, and code blocks render at normal
// scale — but we still tighten margins and downgrade headings (chunks are
// passages plucked from the middle of pages; their original <h1>/<h2>
// outranks our admin layout's heading hierarchy).
export const CHUNK_COMPONENTS = {
  p:    ({ children }) => <p style={{ margin: '0 0 8px 0' }}>{children}</p>,
  h1:   ({ children }) => <strong style={{ display: 'block', margin: '4px 0' }}>{children}</strong>,
  h2:   ({ children }) => <strong style={{ display: 'block', margin: '4px 0' }}>{children}</strong>,
  h3:   ({ children }) => <strong style={{ display: 'block', margin: '4px 0' }}>{children}</strong>,
  h4:   ({ children }) => <strong style={{ display: 'block', margin: '4px 0' }}>{children}</strong>,
  h5:   ({ children }) => <strong style={{ display: 'block', margin: '4px 0' }}>{children}</strong>,
  h6:   ({ children }) => <strong style={{ display: 'block', margin: '4px 0' }}>{children}</strong>,
  ul:   ({ children }) => <ul style={{ margin: '4px 0 8px 0', paddingLeft: '1.4em' }}>{children}</ul>,
  ol:   ({ children }) => <ol style={{ margin: '4px 0 8px 0', paddingLeft: '1.4em' }}>{children}</ol>,
  li:   ({ children }) => <li style={{ margin: 0 }}>{children}</li>,
  blockquote: ({ children }) => (
    <blockquote
      style={{
        margin: '4px 0',
        padding: '2px 10px',
        borderLeft: '3px solid var(--border)',
        color: 'var(--text-secondary)',
      }}
    >
      {children}
    </blockquote>
  ),
  code: ({ inline, children }) =>
    inline ? (
      <code
        style={{
          background: 'var(--code-bg)',
          padding: '0 4px',
          borderRadius: '3px',
          fontFamily: 'var(--font-mono)',
          fontSize: '0.85em',
        }}
      >
        {children}
      </code>
    ) : (
      <code style={{ fontFamily: 'var(--font-mono)', fontSize: '0.85em' }}>{children}</code>
    ),
  pre:  ({ children }) => (
    <pre
      style={{
        margin: '6px 0',
        padding: '6px 10px',
        background: 'var(--code-bg)',
        borderRadius: 'var(--radius-sm)',
        fontSize: '0.82rem',
        overflowX: 'auto',
        whiteSpace: 'pre-wrap',
      }}
    >
      {children}
    </pre>
  ),
  a:    ({ href, children }) =>
    href ? (
      <a href={href} target="_blank" rel="noopener noreferrer">
        {children}
      </a>
    ) : (
      <span>{children}</span>
    ),
  mark: ({ children }) => (
    <mark
      style={{
        background: 'var(--accent-soft, rgba(255, 200, 0, 0.35))',
        color: 'inherit',
        padding: '0 2px',
        borderRadius: '2px',
      }}
    >
      {children}
    </mark>
  ),
};

// Renders the top mention chunks for a node — the surrounding text the entity
// appears in, ranked by extractor confidence. Used by Edge Explorer for
// source/target disambiguation, and by Node Explorer for "what was this
// inferred from?" context.
//
// Empty mentions display the "No mention chunks recorded" hint rather than
// nothing — admins need to know the absence is real, not a load failure.
//
// `label` is rendered uppercased ahead of "mentions" — pass "Source" / "Target"
// (Edge Explorer) or "Context" (Node Explorer).
export function MentionsPanel({ label, node, limit = 3 }) {
  const [mentions, setMentions] = useState(null); // null = unloaded; [] = none
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!node?.id) {
      setMentions(null);
      return;
    }
    let cancelled = false;
    setMentions(null);
    setError(null);
    api.knowledge
      .getNodeMentions(node.id, limit)
      .then((data) => {
        if (!cancelled) setMentions(data.mentions || []);
      })
      .catch((e) => {
        if (!cancelled) setError(e?.message || 'Failed to load mentions');
      });
    return () => {
      cancelled = true;
    };
  }, [node?.id, limit]);

  if (!node?.id) return null;

  return (
    <div style={{ marginTop: 'var(--space-md)' }}>
      <h4
        style={{
          fontSize: '0.85em',
          marginBottom: 'var(--space-xs, 4px)',
          textTransform: 'uppercase',
          letterSpacing: '0.5px',
          color: 'var(--text-muted)',
        }}
      >
        {label} mentions{mentions && mentions.length > 0 ? ` (${mentions.length})` : ''}
      </h4>
      {error && <div className="admin-error">{error}</div>}
      {mentions == null && !error && (
        <div style={{ color: 'var(--text-muted)', fontSize: '0.85em' }}>Loading mentions…</div>
      )}
      {mentions && mentions.length === 0 && (
        <div style={{ color: 'var(--text-muted)', fontSize: '0.85em', fontStyle: 'italic' }}>
          No mention chunks recorded for this node.
        </div>
      )}
      {mentions &&
        mentions.length > 0 &&
        mentions.map((m) => {
          const isFallback = m.extractor === 'edge-proposal-fallback';
          return (
          <div
            key={m.chunk_id}
            data-testid={isFallback ? 'mention-fallback' : 'mention-attributed'}
            style={{
              padding: 'var(--space-sm)',
              background: 'var(--bg-base, var(--bg-elevated))',
              border: `1px ${isFallback ? 'dashed' : 'solid'} var(--border)`,
              borderRadius: 'var(--radius-sm)',
              marginBottom: 'var(--space-sm)',
              fontSize: '0.88em',
              lineHeight: 1.5,
            }}
          >
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                gap: 'var(--space-sm)',
                marginBottom: '4px',
                fontSize: '0.85em',
                flexWrap: 'wrap',
              }}
            >
              <span>
                <PageLink name={m.page_name} />
                {m.heading_path && m.heading_path.length > 0 && (
                  <span style={{ color: 'var(--text-muted)' }}>
                    {' · '}
                    {m.heading_path.join(' › ')}
                  </span>
                )}
                {isFallback && (
                  <span
                    title={
                      'This node has no per-chunk attribution. Showing chunks on the originating '
                      + "proposal's source page that contain the entity name."
                    }
                    style={{
                      marginLeft: 'var(--space-sm)',
                      padding: '0 6px',
                      borderRadius: '3px',
                      border: '1px dashed var(--border)',
                      color: 'var(--text-muted)',
                      fontSize: '0.78em',
                      textTransform: 'uppercase',
                      letterSpacing: '0.4px',
                    }}
                  >
                    Inferred context
                  </span>
                )}
              </span>
              <span style={{ color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>
                conf {m.confidence != null ? m.confidence.toFixed(2) : '—'}
              </span>
            </div>
            <div className="mention-chunk-body">
              <ReactMarkdown
                remarkPlugins={[remarkGfm]}
                rehypePlugins={[makeHighlightRehype(node.name)]}
                components={CHUNK_COMPONENTS}
              >
                {m.text}
              </ReactMarkdown>
            </div>
          </div>
        );
        })}
    </div>
  );
}
